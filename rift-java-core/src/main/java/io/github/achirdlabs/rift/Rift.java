package io.github.achirdlabs.rift;

import io.github.achirdlabs.rift.dsl.ImposterSpec;
import io.github.achirdlabs.rift.error.EngineUnavailable;
import io.github.achirdlabs.rift.json.JsonValue;
import io.github.achirdlabs.rift.model.ImposterDefinition;
import io.github.achirdlabs.rift.spawn.BinaryResolver;
import io.github.achirdlabs.rift.spawn.RiftProcess;
import io.github.achirdlabs.rift.transport.EmbeddedEngine;
import io.github.achirdlabs.rift.transport.EmbeddedEngineProvider;
import io.github.achirdlabs.rift.transport.RemoteTransport;
import io.github.achirdlabs.rift.transport.RiftTransport;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * A client for a running rift engine's admin API. {@link #connect(URI)}/{@link
 * #connect(ConnectOptions)} talks to an already-running engine; {@link #spawn()}/{@link
 * #spawn(SpawnOptions)} additionally launches and owns the engine process's lifecycle. {@link
 * #embedded()}/{@link #embedded(EmbeddedOptions)} runs the engine in-process via the {@code
 * rift-java-embedded} module (discovered through {@code ServiceLoader}), requiring JDK 22+.
 */
public interface Rift extends AutoCloseable {

    static Rift connect(URI adminUri) {
        return connect(ConnectOptions.builder(adminUri).build());
    }

    static Rift connect(ConnectOptions options) {
        return RiftImpl.connect(options);
    }

    static Rift spawn() {
        return spawn(SpawnOptions.builder().build());
    }

    /**
     * Resolves (downloading if necessary) and launches a {@code rift} engine process, then returns
     * a client bound to it. The returned {@link Rift#close()} also stops the launched process. The
     * process's own version is pinned by {@link SpawnOptions#version()}, so no version preflight
     * runs against it.
     */
    static Rift spawn(SpawnOptions options) {
        Path binary = BinaryResolver.resolve(options);
        RiftProcess process = RiftProcess.launch(binary, options);
        RiftTransport transport = new RemoteTransport(process.adminUri(), Optional.empty(), Duration.ofSeconds(30));
        // The builder's default versionCheck applies: spawned() never runs the preflight (the version is
        // pinned), but the per-feature engine gates on create still read it.
        ConnectOptions connectOptions = ConnectOptions.builder(process.adminUri()).build();
        return RiftImpl.spawned(transport, connectOptions, () -> process.stop(options.shutdownTimeout()));
    }

    static Rift embedded() {
        return embedded(EmbeddedOptions.builder().build());
    }

    /**
     * Starts a rift engine in-process (via the Panama FFM C-ABI, requires JDK 22+) and returns a
     * client bound to it. The returned {@link Rift#close()} also stops the embedded engine and
     * releases any native resources it holds.
     */
    static Rift embedded(EmbeddedOptions options) {
        EmbeddedEngineProvider provider = ServiceLoader.load(EmbeddedEngineProvider.class).findFirst()
                .orElseThrow(() -> new EngineUnavailable(
                        "embedded transport requires rift-java-embedded on the classpath "
                                + "(and a rift-java-natives classifier jar or -Drift.ffi.lib)"));
        EmbeddedEngine engine = provider.start(options);
        return RiftImpl.embedded(engine.transport(), options, engine.onClose());
    }

    static boolean isEmbeddedAvailable() {
        return ServiceLoader.load(EmbeddedEngineProvider.class).findFirst()
                .map(EmbeddedEngineProvider::isAvailable)
                .orElse(false);
    }

    Imposter create(ImposterSpec spec);

    Imposter create(ImposterDefinition def);

    Imposter create(JsonValue json);

    Imposter create(String json);

    /** The imposter bound to {@code port}, or {@link Optional#empty()} if none is. */
    Optional<Imposter> imposter(int port);

    List<Imposter> imposters();

    void deleteAll();

    ApplyResult applyConfig(JsonValue config);

    void replaceAll(List<ImposterDefinition> imposters);

    /**
     * {@return a live subscription to the engine's admin event stream} A push tail of recorded
     * requests and imposter lifecycle changes — the counterpart to polling
     * {@link Imposter#recordedSince(long)}, not a replacement for it: the stream never replays, so
     * polling stays the source of truth and is how a gap is recovered.
     *
     * <p>The caller owns the connection: close the returned stream to release it. Closing this
     * {@code Rift} does not close streams it opened — they hold their own connections and outlive it
     * until closed. The embedded transport is the one exception, and unavoidably so: there the
     * engine serving the stream lives inside this process, so closing the {@code Rift} stops it and
     * iteration then fails with {@link io.github.achirdlabs.rift.error.EngineUnavailable} — the
     * ordinary disconnect path, loud rather than a quiet end.
     *
     * <p>Works on every transport, embedded included: the in-process engine starts its own admin
     * server on demand and streams from that, so the first call there pays that start-up.
     *
     * @throws UnsupportedOperationException if this engine connection cannot stream — an engine too
     *                                       old to serve {@code /events}. That means poll instead,
     *                                       which is a supported baseline rather than a degraded
     *                                       mode.
     * @see EventStream
     */
    EventStream events(EventStreamOptions options);

    EngineInfo info();

    URI adminUri();

    /** Starts an intercept (TLS-MITM) listener with default options. See {@link #intercept(InterceptOptions)}. */
    default Intercept intercept() {
        return intercept(InterceptOptions.builder().build());
    }

    /**
     * Starts an intercept (TLS-MITM) listener on this engine. At most one per engine: a second call
     * on this {@code Rift} throws {@link IllegalStateException}. (The engine also rejects a second
     * listener — surfaced as an engine error — so a separate {@code Rift} bound to the same engine
     * cannot start one either.) A call that fails to start leaves intercept available to retry.
     */
    Intercept intercept(InterceptOptions options);

    RiftAsync async();

    @Override
    void close();
}
