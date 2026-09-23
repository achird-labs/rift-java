package io.github.achirdlabs.rift;

import io.github.achirdlabs.rift.dsl.ImposterSpec;
import io.github.achirdlabs.rift.error.CommunicationError;
import io.github.achirdlabs.rift.error.EngineUnavailable;
import io.github.achirdlabs.rift.error.ImposterNotFound;
import io.github.achirdlabs.rift.error.InvalidDefinition;
import io.github.achirdlabs.rift.error.RiftException;
import io.github.achirdlabs.rift.json.JsonArray;
import io.github.achirdlabs.rift.json.JsonNumber;
import io.github.achirdlabs.rift.json.JsonObject;
import io.github.achirdlabs.rift.json.JsonString;
import io.github.achirdlabs.rift.json.JsonValue;
import io.github.achirdlabs.rift.model.ImposterDefinition;
import io.github.achirdlabs.rift.model.Response;
import io.github.achirdlabs.rift.transport.HostAuthority;
import io.github.achirdlabs.rift.transport.RemoteTransport;
import io.github.achirdlabs.rift.transport.RiftTransport;

import java.lang.System.Logger.Level;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

final class RiftImpl implements Rift {

    /** The oldest engine version this SDK is known to work against; see the version preflight. */
    static final String MIN_ENGINE_VERSION = "0.13.1";

    /** The first engine release that runs behaviors on a {@code proxy} or {@code inject} response. */
    static final String PROXY_INJECT_BEHAVIORS_SINCE = "0.18.0";

    /** The first engine release that honours {@code mutualAuth} (older ones accept every client). */
    static final String CLIENT_AUTH_SINCE = "0.18.0";

    /** The first engine release that runs {@code _rift.stateOps} (older ones drop the block). */
    static final String STATE_OPS_SINCE = "0.18.0";

    /** The first engine release that honours {@code repeat} on a fault or {@code _rift}-only response. */
    static final String FAULT_SCRIPT_REPEAT_SINCE = "0.18.0";

    /** The first engine release whose intercept serve action accepts a repeated header (rift#936). */
    static final String INTERCEPT_MULTI_VALUE_HEADERS_SINCE = "0.18.0";

    private static final System.Logger LOG = System.getLogger(RiftImpl.class.getName());

    private final RiftTransport transport;
    private final ConnectOptions options;
    private final Runnable onClose;
    /**
     * Whether the SDK runs this engine itself (spawn, embedded), so an imposter's own bind host is
     * an address this client can reach. For a connected engine it is an address on another machine.
     */
    private final boolean localEngine;
    private final AtomicBoolean interceptStarted = new AtomicBoolean(false);
    /**
     * The engine's reported version: seeded by the preflight when it ran, else read on first need, then
     * kept for the life of this handle. Concurrent first reads may both hit the engine; they read the
     * same value, so the race is tolerated rather than locked.
     */
    private volatile String engineVersion;

    private RiftImpl(RiftTransport transport, ConnectOptions options, Runnable onClose, String engineVersion,
            boolean localEngine) {
        this.transport = transport;
        this.options = options;
        this.onClose = onClose;
        this.localEngine = localEngine;
        this.engineVersion = engineVersion;
    }

    static Rift connect(ConnectOptions options) {
        RiftTransport transport = new RemoteTransport(options.adminUri(), options.apiKey(), options.requestTimeout());
        String version = options.versionCheck() != VersionCheck.OFF
                ? preflight(transport, options.versionCheck(), false)
                : null;
        return new RiftImpl(transport, options, () -> { }, version, false);
    }

    /**
     * Wraps an already-running transport (e.g. a freshly spawned process whose engine version is
     * pinned by the SDK, so no preflight is needed) with an extra {@code onClose} action run after
     * the transport itself is closed — {@link Rift#spawn(SpawnOptions)} uses this to also stop the
     * process it launched.
     */
    static Rift spawned(RiftTransport transport, ConnectOptions options, Runnable onClose) {
        return new RiftImpl(transport, options, onClose, null, true);
    }

    /**
     * Wraps an in-process ({@code rift-java-embedded}) transport. Unlike {@link #spawned}, the
     * loaded cdylib's version is whatever the caller pointed {@code EmbeddedOptions} at — it isn't
     * pinned by the SDK the way a downloaded/spawned binary is — so the preflight still runs here
     * unless the caller opted out with {@code VersionCheck.OFF}.
     */
    static Rift embedded(RiftTransport transport, EmbeddedOptions options, Runnable onClose) {
        String version = null;
        if (options.versionCheck() != VersionCheck.OFF) {
            // start() already loaded the native library and opened the transport. A version mismatch is
            // a first-class outcome here, so release those native resources before propagating.
            try {
                version = preflight(transport, options.versionCheck(), true);
            } catch (RuntimeException e) {
                try {
                    transport.close();
                } finally {
                    onClose.run();
                }
                throw e;
            }
        }
        // adminUri is a required constructor arg but is never read for an embedded transport (the
        // transport already exists; RiftImpl#adminUri() delegates to transport.adminUri(), not this
        // options object) — the hostResolver override below is what actually determines an
        // imposter's uri(), so any placeholder value here is inert.
        ConnectOptions.Builder builder = ConnectOptions
                .builder(HostAuthority.httpUri(options.adminHost(), options.adminPort()))
                .versionCheck(options.versionCheck())
                .hostResolver(port -> HostAuthority.httpUri(options.adminHost(), port));
        options.apiKey().ifPresent(builder::apiKey);
        return new RiftImpl(transport, builder.build(), onClose, version, true);
    }

    /** The outcome of comparing a reported engine version against the floor. Package-private for testing. */
    enum PreflightDecision { PASS, WARN, FAIL }

    /**
     * Decides how to react to a reported {@code version} below {@link #MIN_ENGINE_VERSION}. When
     * {@code abiVerified} (the embedded transport, whose C-ABI symbol set has already been validated
     * by {@code RiftFfi.bind}), the ABI is authoritative: a below-floor version string is a mislabeled
     * dev build (the engine workspace ships a {@code 0.1.0} placeholder), so it is demoted to a warning
     * even in {@code FAIL} mode. A real old engine would have failed the symbol gate first, with a
     * clearer message. Remote/spawn have no symbol gate, so they keep the strict compare.
     */
    static PreflightDecision decide(String version, VersionCheck mode, boolean abiVerified) {
        if (EngineVersion.atLeast(version, MIN_ENGINE_VERSION)) {
            return PreflightDecision.PASS;
        }
        if (abiVerified) {
            return PreflightDecision.WARN;
        }
        return mode == VersionCheck.FAIL ? PreflightDecision.FAIL : PreflightDecision.WARN;
    }

    /** Runs the preflight and returns the version it read, or {@code null} when WARN mode could not read one. */
    private static String preflight(RiftTransport transport, VersionCheck mode, boolean abiVerified) {
        String version;
        try {
            // extractVersion is inside the try so a malformed /config body (CommunicationError) is
            // downgraded to a warning in WARN mode too — WARN must never hard-fail, whatever the cause.
            version = extractVersion(transport.buildInfo());
        } catch (RiftException e) {
            if (mode == VersionCheck.WARN) {
                LOG.log(Level.WARNING, "unable to verify the rift engine version: " + e.getMessage());
                return null;
            }
            throw e;
        }
        switch (decide(version, mode, abiVerified)) {
            case PASS -> { }
            case WARN -> {
                if (abiVerified) {
                    LOG.log(Level.WARNING, "rift engine reports version " + version + " (below the "
                            + MIN_ENGINE_VERSION + " floor) but its C-ABI is v2-complete — trusting the ABI "
                            + "(this is normal for a locally-built engine reporting a placeholder version).");
                } else {
                    LOG.log(Level.WARNING, "rift-java requires rift >= " + MIN_ENGINE_VERSION + ", found " + version);
                }
            }
            case FAIL -> throw new EngineUnavailable("rift-java requires rift >= " + MIN_ENGINE_VERSION
                    + ", found " + version + ". If you know the engine is compatible, relax the check via "
                    + "EmbeddedOptions/ConnectOptions.versionCheck(WARN|OFF) or -Drift.versionCheck=warn|off.");
        }
        return version;
    }

    private static String extractVersion(JsonValue config) {
        if (config instanceof JsonObject obj && obj.get("version") instanceof JsonString s) {
            return s.value();
        }
        throw new CommunicationError("rift admin API GET /config response is missing a 'version' field");
    }

    /**
     * Refuses a typed definition the running engine would accept and then silently ignore part of.
     * Only typed definitions are inspected; the raw-JSON {@code create} overloads are the escape hatch
     * and go through untouched, as does everything under {@link VersionCheck#OFF}.
     *
     * <p>A version below {@link #MIN_ENGINE_VERSION} cannot be judged: it is normally the placeholder a
     * locally built engine reports (the reading {@link #decide} gives an ABI-verified embedded engine),
     * which says nothing about what it supports, and a genuine release that old is outside what this
     * SDK supports at all. It is sent with a warning rather than refused.
     */
    private void requireEngineSupport(ImposterDefinition def) {
        requireEngineSupport(requirementsOf(def));
    }

    /** The check above for requirements gathered anywhere, e.g. by an intercept rule (see {@link InterceptImpl}). */
    private void requireEngineSupport(List<EngineRequirement> requirements) {
        if (options.versionCheck() == VersionCheck.OFF || requirements.isEmpty()) {
            return;
        }
        engineVersion().ifPresent(version -> {
            if (!EngineVersion.atLeast(version, MIN_ENGINE_VERSION)) {
                LOG.log(Level.WARNING, "rift engine reports version " + version + ", below the " + MIN_ENGINE_VERSION
                        + " floor, so it cannot be checked for "
                        + requirements.stream().map(r -> r.feature() + " (rift >= " + r.since() + ")").toList()
                        + "; sending it unchecked.");
                return;
            }
            for (EngineRequirement requirement : requirements) {
                if (!EngineVersion.atLeast(version, requirement.since())) {
                    throw new InvalidDefinition(requirement.feature() + ": needs rift >= " + requirement.since()
                            + "; the running engine (" + version + ") " + requirement.olderEngine()
                            + ". Upgrade the engine, " + requirement.remedy() + ", or turn the check off "
                            + "(versionCheck(OFF), or -Drift.versionCheck=off) to send it anyway.");
                }
            }
        });
    }

    /**
     * Something in a definition that an older engine would accept and not honour.
     *
     * @param olderEngine what an engine older than {@code since} does with it, as the rest of a sentence
     * @param remedy      how to do without it, as an imperative clause
     */
    record EngineRequirement(String since, String feature, String olderEngine, String remedy) {
    }

    private void requireEngineSupportOf(EngineRequirement requirement) {
        requireEngineSupport(List.of(requirement));
    }

    private static List<EngineRequirement> requirementsOf(ImposterDefinition def) {
        List<EngineRequirement> requirements = new ArrayList<>();
        if (hasProxyOrInjectBehaviors(def)) {
            requirements.add(new EngineRequirement(PROXY_INJECT_BEHAVIORS_SINCE,
                    "behaviors on a proxy/inject response", "accepts them and drops them silently",
                    "remove the behaviors"));
        }
        // Any of the three, not only mutualAuth: an older engine drops each of them without a word.
        if (def.mutualAuth() || def.rejectUnauthorized() || def.ca().isPresent()) {
            requirements.add(new EngineRequirement(CLIENT_AUTH_SINCE,
                    "client-certificate authentication (mutualAuth / rejectUnauthorized / ca)",
                    "ignores it and would accept every client", "remove requireClientCertificate"));
        }
        if (hasFaultOrScriptBehaviors(def)) {
            requirements.add(new EngineRequirement(FAULT_SCRIPT_REPEAT_SINCE, "repeat on a fault/script response",
                    "accepts it and drops it silently", "remove the repeat"));
        }
        if (hasStateOps(def)) {
            requirements.add(new EngineRequirement(STATE_OPS_SINCE, "stateOps on an is response",
                    "drops them silently", "replace them with a script"));
        }
        return requirements;
    }

    private static boolean hasFaultOrScriptBehaviors(ImposterDefinition def) {
        return def.stubs().stream()
                .flatMap(stub -> stub.responses().stream())
                // Only repeat acts on these responses, on every engine, so only a repeat is at stake.
                .anyMatch(r -> (r instanceof Response.Fault f && f.behaviors().effectiveRepeat().isPresent())
                        || (r instanceof Response.RiftScript s && s.behaviors().effectiveRepeat().isPresent()));
    }

    private static boolean hasStateOps(ImposterDefinition def) {
        return def.stubs().stream()
                .flatMap(stub -> stub.responses().stream())
                .anyMatch(r -> r instanceof Response.Is is
                        && is.rift().map(rift -> !rift.stateOps().isEmpty()).orElse(false));
    }

    private static boolean hasProxyOrInjectBehaviors(ImposterDefinition def) {
        return def.stubs().stream()
                .flatMap(stub -> stub.responses().stream())
                .anyMatch(r -> (r instanceof Response.Proxy p && !p.behaviors().isEmpty())
                        || (r instanceof Response.Inject i && !i.behaviors().isEmpty()));
    }

    /**
     * The engine's version, read once. An unreadable version fails the call in {@code FAIL} mode and
     * is logged and skipped in {@code WARN} mode — the same split the connect-time preflight makes.
     */
    private Optional<String> engineVersion() {
        String cached = engineVersion;
        if (cached != null) {
            return Optional.of(cached);
        }
        try {
            cached = extractVersion(transport.buildInfo());
        } catch (RiftException e) {
            if (options.versionCheck() == VersionCheck.WARN) {
                LOG.log(Level.WARNING, "unable to verify the rift engine version, so this definition is sent "
                        + "unchecked: " + e.getMessage());
                return Optional.empty();
            }
            throw e;
        }
        engineVersion = cached;
        return Optional.of(cached);
    }

    @Override
    public Imposter create(ImposterSpec spec) {
        return create(spec.build());
    }

    @Override
    public Imposter create(ImposterDefinition def) {
        requireEngineSupport(def);
        return create(JsonValue.parse(def.toJson()));
    }

    @Override
    public Imposter create(JsonValue json) {
        JsonValue created = transport.createImposter(json);
        // The host comes from what was posted: the engine's create response does not echo it.
        return imposterAt(extractPort(created), json);
    }

    @Override
    public Imposter create(String json) {
        return create(JsonValue.parse(json));
    }

    @Override
    public Optional<Imposter> imposter(int port) {
        try {
            if (localEngine) {
                // Only the replayable list carries an imposter's host (a single GET never does), and
                // only a local engine's host is an address this client can use.
                return listedConfigs(true).stream()
                        .filter(v -> extractPort(v) == port)
                        .findFirst()
                        .map(v -> imposterAt(port, v));
            }
            transport.getImposter(port);
            return Optional.of(imposterAt(port, JsonObject.of()));
        } catch (ImposterNotFound e) {
            return Optional.empty();
        }
    }

    @Override
    public List<Imposter> imposters() {
        return listedConfigs(localEngine).stream().<Imposter>map(v -> imposterAt(extractPort(v), v)).toList();
    }

    /**
     * Every imposter the engine lists. The replayable shape is the only one carrying each
     * imposter's host, so a local engine asks for it; a connected one has no use for the host.
     */
    private List<JsonValue> listedConfigs(boolean replayable) {
        JsonValue result = transport.listImposters(replayable, false);
        if (result instanceof JsonObject obj && obj.get("imposters") instanceof JsonArray arr) {
            return arr.items();
        }
        return List.of();
    }

    /** An imposter handle, told the host it is bound to when that is an address this client can use. */
    private ImposterImpl imposterAt(int port, JsonValue definition) {
        Optional<String> host = localEngine && definition instanceof JsonObject obj && obj.get("host") instanceof JsonString h
                ? Optional.of(h.value())
                : Optional.empty();
        return new ImposterImpl(port, transport, options, host);
    }

    @Override
    public void deleteAll() {
        transport.deleteAll();
    }

    @Override
    public ApplyResult applyConfig(JsonValue config) {
        return ApplyResult.read(transport.applyConfig(config));
    }

    @Override
    public void replaceAll(List<ImposterDefinition> imposters) {
        imposters.forEach(this::requireEngineSupport);
        JsonArray docs = new JsonArray(imposters.stream().map(d -> (JsonValue) JsonValue.parse(d.toJson())).toList());
        JsonObject doc = JsonObject.builder().put("imposters", docs).build();
        transport.replaceAllImposters(doc);
    }


    @Override
    public EventStream events(EventStreamOptions options) {
        return transport.events(options);
    }

    @Override
    public EngineInfo info() {
        return EngineInfo.read(transport.buildInfo());
    }

    @Override
    public URI adminUri() {
        return transport.adminUri();
    }

    @Override
    public Intercept intercept(InterceptOptions options) {
        // Checked (and flipped) before touching the transport at all: a rejected second call must
        // never start a second listener even transiently.
        if (!interceptStarted.compareAndSet(false, true)) {
            throw new IllegalStateException("intercept already started for this engine");
        }
        try {
            if (options.isAttach()) {
                // No listener to start: probe the already-running one (started at engine launch via
                // --intercept-port), then bind to the given endpoint.
                transport.interceptListRules();
                return new InterceptImpl(transport, options.host(), options.port(), this::requireEngineSupportOf);
            }
            JsonValue response = transport.startIntercept(options.toJson());
            return new InterceptImpl(transport, response, this::requireEngineSupportOf);
        } catch (RuntimeException e) {
            // The listener didn't actually start — reset so a genuine failure is retryable, while a
            // concurrent/second call was still blocked by the CAS above.
            interceptStarted.set(false);
            throw e;
        }
    }

    @Override
    public RiftAsync async() {
        return new RiftAsyncImpl(this);
    }

    @Override
    public void close() {
        // onClose (which stops a spawned process) must run even if the transport close ever throws,
        // so the managed engine is never left running past the Rift handle that owns it.
        try {
            transport.close();
        } finally {
            onClose.run();
        }
    }

    private static int extractPort(JsonValue value) {
        if (value instanceof JsonObject obj && obj.get("port") instanceof JsonNumber n) {
            return n.asInt();
        }
        throw new CommunicationError("rift admin API response is missing a 'port' field");
    }
}
