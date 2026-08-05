package io.github.achirdlabs.rift.embedded;

import io.github.achirdlabs.rift.EmbeddedOptions;
import io.github.achirdlabs.rift.EventStream;
import io.github.achirdlabs.rift.EventStreamOptions;
import io.github.achirdlabs.rift.MatchClause;
import io.github.achirdlabs.rift.error.EngineError;
import io.github.achirdlabs.rift.json.JsonNumber;
import io.github.achirdlabs.rift.json.JsonObject;
import io.github.achirdlabs.rift.json.JsonString;
import io.github.achirdlabs.rift.json.JsonValue;
import io.github.achirdlabs.rift.transport.RemoteTransport;
import io.github.achirdlabs.rift.transport.RiftTransport;
import io.github.achirdlabs.rift.transport.StubAddress;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.foreign.Arena;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link RiftTransport} over the in-process rift engine via the Panama FFM C-ABI v2. Every
 * admin-API operation with a direct C-ABI v2 entry point — imposter lifecycle, recording,
 * flow-state, spaces, per-stub CRUD, listing, scenarios, enable/disable, verify, and stub
 * warnings — is driven directly through {@code librift_ffi}. The operations the C-ABI cannot
 * express — {@link #replaceAllImposters} (bulk {@code PUT /imposters}), {@link #events} (the
 * {@code GET /events} SSE stream), and the cursor/clause reads {@link #recordedSince} and the
 * scoped {@link #clearRecorded(int, java.util.List)} — are delegated to a lazily-started in-process
 * admin server over {@link RemoteTransport}.
 */
public final class EmbeddedTransport implements RiftTransport {

    private static final Logger LOG = System.getLogger(EmbeddedTransport.class.getName());

    private final Arena libArena;
    private final FfiCalls calls;
    private final EmbeddedOptions options;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Object adminLock = new Object();
    private volatile RemoteTransport admin;

    private EmbeddedTransport(Arena libArena, FfiCalls calls, EmbeddedOptions options) {
        this.libArena = libArena;
        this.calls = calls;
        this.options = options;
    }

    /** Opens the engine with default admin-plane settings: loopback, an OS-assigned port, no api key. */
    public static EmbeddedTransport open(Path lib) {
        return open(lib, EmbeddedOptions.builder().build());
    }

    /**
     * Opens the engine, configuring the in-process admin plane from {@code options} —
     * {@code adminHost}, {@code adminPort} and {@code apiKey} are handed to {@code rift_serve_admin}
     * when the plane starts, and the api key is also given to the client that delegates to it (#176).
     */
    public static EmbeddedTransport open(Path lib, EmbeddedOptions options) {
        // Before the arena and the engine handle exist: a null caught later would leak both.
        Objects.requireNonNull(options, "options");
        Arena libArena = Arena.ofShared();
        RiftFfi ffi = RiftFfi.load(lib, libArena);
        FfiCalls calls = FfiCalls.start(ffi);
        return new EmbeddedTransport(libArena, calls, options);
    }

    @Override
    public JsonValue createImposter(JsonValue def) {
        // A faithful, uniform wire mapping: the def is passed through untouched so embedded behaves
        // identically to the remote/spawn transports for the same definition. (The engine backs
        // flow-state/spaces with a real store only when the def declares one, e.g. _rift.flowState —
        // that is uniform engine behaviour, not something a transport should paper over. See #40.)
        int port = calls.createImposter(def);
        return JsonObject.builder().put("port", JsonNumber.of(port)).build();
    }

    @Override
    public JsonValue getImposter(int port) {
        return calls.getImposter(port, false, false);
    }

    @Override
    public JsonValue getImposter(int port, boolean replayable, boolean removeProxies) {
        return calls.getImposter(port, replayable, removeProxies);
    }

    @Override
    public void deleteImposter(int port) {
        calls.deleteImposter(port);
    }

    @Override
    public void deleteAll() {
        calls.deleteAll();
    }

    @Override
    public JsonValue listImposters(boolean replayable, boolean removeProxies) {
        return calls.listImposters(replayable, removeProxies);
    }

    @Override
    public void replaceAllImposters(JsonValue doc) {
        admin().replaceAllImposters(doc);
    }

    /**
     * The delegated stream carries this handle's own traffic: the admin server's {@code /events}
     * taps the engine's admin event bus, which lives on the imposter manager the FFI data plane
     * already drives — so events published by directly-driven imposters are what arrives here.
     *
     * <p>Two consequences worth knowing: request events still require the imposter to have been
     * created with {@code recordRequests: true}, and the first call pays the in-process admin
     * server's start-up (set {@code EmbeddedOptions.serveAdminEagerly} to pay it at startup instead).
     */
    @Override
    public EventStream events(EventStreamOptions options) {
        return admin().events(options);
    }

    @Override
    public JsonValue applyConfig(JsonValue config) {
        return calls.applyConfig(config);
    }

    @Override
    public void addStub(int port, JsonValue stub, int index) {
        calls.addStub(port, stub, index);
    }

    @Override
    public void addStub(int port, JsonValue stub) {
        calls.addStub(port, stub);
    }

    @Override
    public void replaceStubs(int port, JsonValue stubs) {
        calls.replaceStubs(port, stubs);
    }

    @Override
    public void replaceStub(int port, StubAddress addr, JsonValue stub) {
        calls.updateStub(port, addr, stub);
    }

    @Override
    public void deleteStub(int port, StubAddress addr) {
        calls.deleteStub(port, addr);
    }

    @Override
    public JsonValue getStub(int port, StubAddress addr) {
        return calls.getStub(port, addr);
    }

    @Override
    public JsonValue recorded(int port) {
        return calls.recorded(port);
    }

    /**
     * Delegated to the in-process admin server: the C-ABI has no cursor-bearing journal read, but
     * {@code GET /imposters/{port}/savedRequests} serves both {@code ?since=} and {@code match=},
     * and returns the cursor in a response header the C-ABI has no equivalent of (#175).
     *
     * <p>Delegated unconditionally, including the bare {@code recordedPage()} form with no cursor
     * and no clauses. That read is the cursor <em>baseline</em>, so answering it from the C-ABI
     * would hand back no cursor at the baseline and a real one on the next call — the caller's
     * first read would look like a transport that cannot tail at all.
     *
     * <p>The price is that a read can now fail for reasons unrelated to reading: the first one
     * starts the admin server, so an environment that cannot bind loopback surfaces that here
     * rather than at startup. {@code EmbeddedOptions.serveAdminEagerly} moves it back to startup.
     */
    @Override
    public RecordedSlice recordedSince(int port, OptionalLong since, List<MatchClause> match) {
        return admin().recordedSince(port, since, match);
    }

    @Override
    public JsonValue stubWarnings(int port) {
        return calls.stubWarnings(port);
    }

    @Override
    public JsonValue verify(int port, JsonValue body) {
        return calls.verify(port, body);
    }

    @Override
    public void clearRecorded(int port) {
        calls.clearRecorded(port);
    }

    /**
     * A scoped clear needs the same server-side clause evaluation as {@link #recordedSince}, so it
     * takes the same route. The unfiltered case stays on the C-ABI: it is the common one, and
     * routing it through the admin server would start that server for a call that never needed it.
     *
     * <p>Splitting on the clause is safe because both arms mean the same thing — an empty clause
     * list makes the admin form emit {@code DELETE .../savedRequests} with no query, which is
     * {@code rift_clear_recorded}'s own behaviour — and because the admin server is served by this
     * handle's imposter manager, so either route mutates one journal, not two.
     */
    @Override
    public void clearRecorded(int port, List<MatchClause> match) {
        if (match.isEmpty()) {
            calls.clearRecorded(port);
            return;
        }
        admin().clearRecorded(port, match);
    }

    @Override
    public void clearProxyResponses(int port) {
        calls.clearProxyResponses(port);
    }

    @Override
    public void enable(int port) {
        calls.setImposterEnabled(port, true);
    }

    @Override
    public void disable(int port) {
        calls.setImposterEnabled(port, false);
    }

    @Override
    public JsonValue scenarios(int port, Optional<String> flowId) {
        return calls.scenarios(port, flowId);
    }

    @Override
    public void setScenarioState(int port, String name, String state, Optional<String> flowId) {
        calls.setScenarioState(port, name, state, flowId);
    }

    @Override
    public void resetScenarios(int port) {
        calls.resetScenarios(port);
    }

    @Override
    public Optional<JsonValue> flowStateGet(int port, String flowId, String key) {
        return calls.flowStateGet(port, flowId, key);
    }

    @Override
    public void flowStatePut(int port, String flowId, String key, JsonValue value) {
        calls.flowStatePut(port, flowId, key, value);
    }

    @Override
    public void flowStateDelete(int port, String flowId, String key) {
        calls.flowStateDelete(port, flowId, key);
    }

    @Override
    public void spaceAddStub(int port, String flowId, JsonValue stub) {
        calls.spaceAddStub(port, flowId, stub);
    }

    @Override
    public JsonValue spaceListStubs(int port, String flowId) {
        return calls.spaceListStubs(port, flowId);
    }

    @Override
    public JsonValue spaceRecorded(int port, String flowId) {
        return calls.spaceRecorded(port, flowId);
    }

    @Override
    public void spaceDelete(int port, String flowId) {
        calls.spaceDelete(port, flowId);
    }

    @Override
    public JsonValue buildInfo() {
        return calls.buildInfo();
    }

    // Intercept is driven directly over the C-ABI (no admin loopback needed).

    @Override
    public JsonValue startIntercept(JsonValue options) {
        return calls.startIntercept(options);
    }

    @Override
    public void interceptAddRules(JsonValue rules) {
        calls.interceptAddRules(rules);
    }

    @Override
    public JsonValue interceptListRules() {
        return calls.interceptListRules();
    }

    @Override
    public void interceptClearRules() {
        calls.interceptClearRules();
    }

    @Override
    public String interceptCaPem() {
        return calls.interceptCaPem();
    }

    @Override
    public URI adminUri() {
        return admin().adminUri();
    }

    /**
     * The {@code rift_serve_admin} payload for these options. Package-private and pure so the wire
     * contract can be pinned without an engine: the engine's {@code ServeOptions} is a camelCase
     * serde struct of all-optional fields, so a misspelled key is not an error there — it is a
     * setting that silently reverts to the default, which is the failure #176 removes.
     *
     * <p>{@code host} and {@code port} are always sent. They are redundant at their defaults (the
     * engine's own fallbacks are the same {@code 127.0.0.1} and {@code 0}), but sending them keeps
     * one code path rather than one per combination of set fields.
     */
    static JsonObject serveOptions(EmbeddedOptions options) {
        return JsonObject.builder()
                .put("host", new JsonString(options.adminHost()))
                .put("port", JsonNumber.of(options.adminPort()))
                // Absent rather than null: the payload then says only what was actually configured.
                // (An explicit null would also parse — serde maps it to None for an Option — so this
                // is idiom, not a correctness requirement.)
                .putIfPresent("apiKey", options.apiKey().map(JsonString::new))
                .build();
    }

    /**
     * Binding wider than loopback with no api key is honoured — refusing would break a container or
     * isolated-network setup that works today, and would make this SDK stricter than the engine and
     * its sibling SDKs, which is a portability bug rather than a security policy. But it is not
     * silent: this plane is the full admin API of an engine inside the caller's own process, and a
     * single builder call is otherwise the only sign the posture changed.
     */
    private void warnIfExposedWithoutAKey() {
        if (options.apiKey().isPresent()) {
            return;
        }
        try {
            if (InetAddress.getByName(options.adminHost()).isLoopbackAddress()) {
                return;
            }
        } catch (UnknownHostException e) {
            // Unresolvable: the engine is about to reject this host itself, with a better message
            // than a guess here would produce. Nothing to warn about that it will not say louder.
            return;
        }
        LOG.log(Level.WARNING, "the in-process rift admin API is binding " + options.adminHost()
                + " with no apiKey — anything that can reach that interface can drive this engine,"
                + " create imposters and start the TLS intercept proxy."
                + " Set EmbeddedOptions.Builder#apiKey, or bind 127.0.0.1.");
    }

    private RemoteTransport admin() {
        RemoteTransport a = admin;
        if (a != null) {
            return a;
        }
        synchronized (adminLock) {
            a = admin;
            if (a == null) {
                warnIfExposedWithoutAKey();
                JsonValue result = calls.serveAdmin(serveOptions(options));
                if (!(result instanceof JsonObject obj && obj.get("adminUrl") instanceof JsonString adminUrl)) {
                    throw new EngineError(EngineError.NO_HTTP_STATUS,
                            "rift_serve_admin did not return an adminUrl: " + result.toJson());
                }
                // The same key the server was just given: a client that could not authenticate to
                // the plane it configured would break every delegated op the moment one was set.
                a = new RemoteTransport(URI.create(adminUrl.value()), options.apiKey(), Duration.ofSeconds(30));
                admin = a;
            }
        }
        return a;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        // Always release the engine handle and unload the library even if the admin client's close
        // throws, otherwise a failure there would leak the native handle and the mapped library.
        try {
            RemoteTransport a = admin;
            if (a != null) {
                a.close();
            }
        } finally {
            try {
                calls.stop();
            } finally {
                libArena.close();
            }
        }
    }
}
