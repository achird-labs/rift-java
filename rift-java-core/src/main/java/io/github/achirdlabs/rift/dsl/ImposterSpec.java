package io.github.achirdlabs.rift.dsl;

import io.github.achirdlabs.rift.json.JsonValue;
import io.github.achirdlabs.rift.model.CaCertificates;
import io.github.achirdlabs.rift.model.FlowStateSupport;
import io.github.achirdlabs.rift.model.ImposterDefinition;
import io.github.achirdlabs.rift.model.RiftConfig;
import io.github.achirdlabs.rift.model.RiftConnectionPoolConfig;
import io.github.achirdlabs.rift.model.RiftFlowStateConfig;
import io.github.achirdlabs.rift.model.RiftMetricsConfig;
import io.github.achirdlabs.rift.model.RiftProxyConfig;
import io.github.achirdlabs.rift.model.RiftScriptConfig;
import io.github.achirdlabs.rift.model.RiftScriptEngineConfig;
import io.github.achirdlabs.rift.model.Stub;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An imposter under construction, created by {@link RiftDsl#imposter(String)}.
 *
 * <p>Instances are immutable: every chain method returns a new {@code ImposterSpec}. The terminal
 * {@link #build()} produces the {@link ImposterDefinition} model value.
 */
public final class ImposterSpec {

    private final String name;
    private final Optional<Integer> port;
    private final String protocol;
    private final boolean recordRequests;
    private final boolean recordMatches;
    private final boolean allowCors;
    private final List<Stub> stubs;
    private final Optional<IsSpec> defaultResponse;
    private final Optional<String> host;
    private final Optional<String> cert;
    private final Optional<String> key;
    private final Optional<String> defaultForward;
    private final boolean strictBehaviors;
    private final Optional<String> serviceName;
    private final Optional<JsonValue> serviceInfo;
    private final Optional<FlowStateSpec> flowState;
    private final Optional<RiftMetricsConfig> metrics;
    private final Optional<RiftConnectionPoolConfig> proxyPool;
    private final Optional<RiftScriptEngineConfig> scriptEngine;
    private final Map<String, RiftScriptConfig> scripts;
    /** Absent: no client certificate. Present and empty: any certificate. Otherwise: one chaining to these. */
    private final Optional<List<String>> clientCertificateCas;

    ImposterSpec(String name) {
        this(name, Optional.empty(), ImposterDefinition.DEFAULT_PROTOCOL, false, false, false, List.of(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                false, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Map.of(), Optional.empty());
    }

    private ImposterSpec(
            String name,
            Optional<Integer> port,
            String protocol,
            boolean recordRequests,
            boolean recordMatches,
            boolean allowCors,
            List<Stub> stubs,
            Optional<IsSpec> defaultResponse,
            Optional<String> host,
            Optional<String> cert,
            Optional<String> key,
            Optional<String> defaultForward,
            boolean strictBehaviors,
            Optional<String> serviceName,
            Optional<JsonValue> serviceInfo,
            Optional<FlowStateSpec> flowState,
            Optional<RiftMetricsConfig> metrics,
            Optional<RiftConnectionPoolConfig> proxyPool,
            Optional<RiftScriptEngineConfig> scriptEngine,
            Map<String, RiftScriptConfig> scripts,
            Optional<List<String>> clientCertificateCas) {
        this.name = name;
        this.port = port;
        this.protocol = protocol;
        this.recordRequests = recordRequests;
        this.recordMatches = recordMatches;
        this.allowCors = allowCors;
        this.stubs = stubs;
        this.defaultResponse = defaultResponse;
        this.host = host;
        this.cert = cert;
        this.key = key;
        this.defaultForward = defaultForward;
        this.strictBehaviors = strictBehaviors;
        this.serviceName = serviceName;
        this.serviceInfo = serviceInfo;
        this.flowState = flowState;
        this.metrics = metrics;
        this.proxyPool = proxyPool;
        this.scriptEngine = scriptEngine;
        this.scripts = scripts;
        this.clientCertificateCas = clientCertificateCas;
    }

    /** Binds the imposter to a fixed port, rather than letting the engine assign one. */
    public ImposterSpec port(int port) {
        return new ImposterSpec(name, Optional.of(port), protocol, recordRequests, recordMatches, allowCors, stubs,
                defaultResponse, host, cert, key, defaultForward, strictBehaviors, serviceName, serviceInfo,
                flowState, metrics, proxyPool, scriptEngine, scripts, clientCertificateCas);
    }

    /** Sets the imposter's protocol (e.g. {@code "http"}, {@code "https"}, {@code "tcp"}). */
    public ImposterSpec protocol(String protocol) {
        return new ImposterSpec(name, port, protocol, recordRequests, recordMatches, allowCors, stubs,
                defaultResponse, host, cert, key, defaultForward, strictBehaviors, serviceName, serviceInfo,
                flowState, metrics, proxyPool, scriptEngine, scripts, clientCertificateCas);
    }

    /** Binds the imposter to a specific network interface/host. */
    public ImposterSpec host(String host) {
        return new ImposterSpec(name, port, protocol, recordRequests, recordMatches, allowCors, stubs,
                defaultResponse, Optional.of(host), cert, key, defaultForward, strictBehaviors, serviceName,
                serviceInfo, flowState, metrics, proxyPool, scriptEngine, scripts, clientCertificateCas);
    }

    /**
     * Enables TLS with the given certificate and private key (both PEM-encoded), and sets the
     * protocol to {@code "https"} — the engine ignores {@code cert}/{@code key} on a plain
     * {@code http} imposter, so this makes {@code .https(...)} self-sufficient. To also require client
     * certificates, chain {@link #requireClientCertificate(String...)}.
     */
    public ImposterSpec https(String certPem, String keyPem) {
        return new ImposterSpec(name, port, "https", recordRequests, recordMatches, allowCors, stubs,
                defaultResponse, host, Optional.of(certPem), Optional.of(keyPem), defaultForward, strictBehaviors,
                serviceName, serviceInfo, flowState, metrics, proxyPool, scriptEngine, scripts, clientCertificateCas);
    }

    /**
     * Requires every client of this HTTPS imposter to present a certificate, without validating its
     * chain (sets {@code mutualAuth}). A client with no certificate is refused at the handshake. Use
     * {@link #requireClientCertificate(String...)} to also check who issued it.
     *
     * <p>Requires a rift engine &ge; 0.18.0: an older one drops the setting and accepts every client,
     * so creating the imposter there fails with {@link io.github.achirdlabs.rift.error.InvalidDefinition}
     * — when the engine's version can be checked. With {@code VersionCheck.OFF}, a version that cannot
     * be read in {@code WARN} mode, or a locally built engine's placeholder version, it is sent
     * unchecked.
     * The imposter must be {@code https} ({@link #https(String, String)} or {@code protocol("https")});
     * without its own certificate the engine serves its default or a self-signed one. A later call
     * replaces an earlier one.
     */
    public ImposterSpec requireClientCertificate() {
        return withClientCertificateCas(Optional.of(List.of()));
    }

    /**
     * Requires every client of this HTTPS imposter to present a certificate chaining to one of
     * {@code trustedCaPems} (sets {@code mutualAuth}, {@code rejectUnauthorized} and {@code ca}). Any
     * other client is refused at the handshake.
     *
     * <p>Same engine and protocol requirements as {@link #requireClientCertificate()}. Note that the
     * engine's replayable export ({@code Recording.persist}, {@code rift save}) includes the CA
     * certificates, as it does the imposter's own certificate and key.
     *
     * @param trustedCaPems PEM trust anchors; at least one, each holding a
     *                      {@code -----BEGIN CERTIFICATE-----} block
     * @throws IllegalArgumentException if {@code trustedCaPems} is empty — that would read as "any
     *                                  certificate", which is {@link #requireClientCertificate()} —
     *                                  or an entry holds no certificate
     */
    public ImposterSpec requireClientCertificate(String... trustedCaPems) {
        if (trustedCaPems.length == 0) {
            throw new IllegalArgumentException("requireClientCertificate(...) needs at least one trusted CA PEM; "
                    + "use requireClientCertificate() to accept any client certificate");
        }
        for (String pem : trustedCaPems) {
            if (!pem.contains("-----BEGIN CERTIFICATE-----")) {
                throw new IllegalArgumentException(
                        "a trusted CA PEM contains no certificate: expected a -----BEGIN CERTIFICATE----- block");
            }
        }
        return withClientCertificateCas(Optional.of(List.of(trustedCaPems)));
    }

    private ImposterSpec withClientCertificateCas(Optional<List<String>> cas) {
        return new ImposterSpec(name, port, protocol, recordRequests, recordMatches, allowCors, stubs,
                defaultResponse, host, cert, key, defaultForward, strictBehaviors, serviceName, serviceInfo,
                flowState, metrics, proxyPool, scriptEngine, scripts, cas);
    }

    /** Enables recording of every request the imposter receives (sets {@code recordRequests}). */
    public ImposterSpec record() {
        return new ImposterSpec(name, port, protocol, true, recordMatches, allowCors, stubs,
                defaultResponse, host, cert, key, defaultForward, strictBehaviors, serviceName, serviceInfo,
                flowState, metrics, proxyPool, scriptEngine, scripts, clientCertificateCas);
    }

    /**
     * Sets {@code recordMatches}, which no rift engine acts on: the engine does not record which stub matched
     * each request. Rift 0.18.0 and later report it as {@code config_key_ignored} in the imposter's
     * {@code _rift.warnings}; earlier engines ignore it silently.
     *
     * @deprecated has no effect on the engine. Use {@link #record()} ({@code recordRequests}) and read the
     *     imposter's recorded requests instead.
     */
    @Deprecated(since = "0.2.4")
    public ImposterSpec recordMatches() {
        return new ImposterSpec(name, port, protocol, recordRequests, true, allowCors, stubs,
                defaultResponse, host, cert, key, defaultForward, strictBehaviors, serviceName, serviceInfo,
                flowState, metrics, proxyPool, scriptEngine, scripts, clientCertificateCas);
    }

    /** Enables permissive CORS response headers for this imposter (sets {@code allowCORS}). */
    public ImposterSpec allowCors() {
        return new ImposterSpec(name, port, protocol, recordRequests, recordMatches, true, stubs,
                defaultResponse, host, cert, key, defaultForward, strictBehaviors, serviceName, serviceInfo,
                flowState, metrics, proxyPool, scriptEngine, scripts, clientCertificateCas);
    }

    /** Sets the response served when no stub matches a request. */
    public ImposterSpec defaultResponse(IsSpec response) {
        return new ImposterSpec(name, port, protocol, recordRequests, recordMatches, allowCors, stubs,
                Optional.of(response), host, cert, key, defaultForward, strictBehaviors, serviceName, serviceInfo,
                flowState, metrics, proxyPool, scriptEngine, scripts, clientCertificateCas);
    }

    /** Sets the URL every unmatched request is forwarded to. */
    public ImposterSpec defaultForward(String url) {
        return new ImposterSpec(name, port, protocol, recordRequests, recordMatches, allowCors, stubs,
                defaultResponse, host, cert, key, Optional.of(url), strictBehaviors, serviceName, serviceInfo,
                flowState, metrics, proxyPool, scriptEngine, scripts, clientCertificateCas);
    }

    /** Rejects any behavior key the engine does not recognize, rather than ignoring it. */
    public ImposterSpec strictBehaviors() {
        return new ImposterSpec(name, port, protocol, recordRequests, recordMatches, allowCors, stubs,
                defaultResponse, host, cert, key, defaultForward, true, serviceName, serviceInfo,
                flowState, metrics, proxyPool, scriptEngine, scripts, clientCertificateCas);
    }

    /** Names the logical service this imposter simulates (a {@code _rift}-adjacent metadata field). */
    public ImposterSpec serviceName(String serviceName) {
        return new ImposterSpec(name, port, protocol, recordRequests, recordMatches, allowCors, stubs,
                defaultResponse, host, cert, key, defaultForward, strictBehaviors, Optional.of(serviceName),
                serviceInfo, flowState, metrics, proxyPool, scriptEngine, scripts, clientCertificateCas);
    }

    /** Attaches free-form service metadata. */
    public ImposterSpec serviceInfo(JsonValue info) {
        return new ImposterSpec(name, port, protocol, recordRequests, recordMatches, allowCors, stubs,
                defaultResponse, host, cert, key, defaultForward, strictBehaviors, serviceName, Optional.of(info),
                flowState, metrics, proxyPool, scriptEngine, scripts, clientCertificateCas);
    }

    /** Configures correlated flow state for this imposter's {@code _rift} scripts. */
    public ImposterSpec flowState(FlowStateSpec spec) {
        return new ImposterSpec(name, port, protocol, recordRequests, recordMatches, allowCors, stubs,
                defaultResponse, host, cert, key, defaultForward, strictBehaviors, serviceName, serviceInfo,
                Optional.of(spec), metrics, proxyPool, scriptEngine, scripts, clientCertificateCas);
    }

    /**
     * Sets {@code _rift.metrics}, which no rift engine acts on: metrics are process-wide, served on the engine's
     * {@code --metrics-port} (default 9090), and not configurable per imposter. Rift 0.18.0 and later report it
     * as {@code config_key_ignored} in the imposter's {@code _rift.warnings}; earlier engines ignore it silently.
     *
     * @deprecated has no effect on the engine. Configure metrics on the engine process with
     *     {@code --metrics-port}.
     */
    @Deprecated(since = "0.2.4")
    public ImposterSpec metrics(int port) {
        return new ImposterSpec(name, this.port, protocol, recordRequests, recordMatches, allowCors, stubs,
                defaultResponse, host, cert, key, defaultForward, strictBehaviors, serviceName, serviceInfo,
                flowState, Optional.of(new RiftMetricsConfig(true, port)), proxyPool, scriptEngine, scripts,
                clientCertificateCas);
    }

    /**
     * Sets the default scripting engine and per-invocation timeout for {@code _rift} scripts.
     *
     * <p>The default engine ({@code _rift.scriptEngine.defaultEngine}) is honoured by rift 0.18.0 and later.
     * Rift 0.17.0 and earlier parse it and ignore it: a script that does not name its engine runs as Rhai.
     * On an engine that honours it, a script's engine is resolved in this order: the script's own
     * {@code engine}, then its {@code file} extension ({@code .rhai} or {@code .js}), then this default,
     * then Rhai. A {@link Script#ref(String) reference} takes the engine of the script it names.
     *
     * <p>The {@link Script} factories ({@link Script#rhai}, {@link Script#js}, {@link Script#rhaiFile},
     * {@link Script#jsFile}) always name their engine, so the default only decides scripts that
     * reach the engine without one, such as {@code _rift.script} entries in raw JSON configuration.
     * There is no capability flag for this behaviour; check the engine version.
     */
    public ImposterSpec scriptEngine(ScriptEngine engine, Duration timeout) {
        RiftScriptEngineConfig config = new RiftScriptEngineConfig(engine.wire(), timeout.toMillis());
        return new ImposterSpec(name, port, protocol, recordRequests, recordMatches, allowCors, stubs,
                defaultResponse, host, cert, key, defaultForward, strictBehaviors, serviceName, serviceInfo,
                flowState, metrics, proxyPool, Optional.of(config), scripts, clientCertificateCas);
    }

    /** Registers a named script other responses can reference by {@code {"ref": name}}. */
    public ImposterSpec script(String scriptName, Script script) {
        Map<String, RiftScriptConfig> next = new LinkedHashMap<>(scripts);
        next.put(scriptName, script.toConfig());
        return new ImposterSpec(name, port, protocol, recordRequests, recordMatches, allowCors, stubs,
                defaultResponse, host, cert, key, defaultForward, strictBehaviors, serviceName, serviceInfo,
                flowState, metrics, proxyPool, scriptEngine, next, clientCertificateCas);
    }

    /**
     * Sets {@code _rift.proxy.connectionPool}, which no rift engine acts on: a proxy response's upstream is its
     * own {@code proxy.to}, and connection pooling is not configurable per imposter. Rift 0.18.0 and later report
     * {@code _rift.proxy} as {@code config_key_ignored} in the imposter's {@code _rift.warnings}; earlier engines
     * ignore it silently.
     *
     * @deprecated has no effect on the engine; there is no replacement.
     */
    @Deprecated(since = "0.2.4")
    public ImposterSpec proxyPool(int maxIdlePerHost, Duration idleTimeout) {
        RiftConnectionPoolConfig pool = new RiftConnectionPoolConfig(maxIdlePerHost, idleTimeout.toSeconds());
        return new ImposterSpec(name, port, protocol, recordRequests, recordMatches, allowCors, stubs,
                defaultResponse, host, cert, key, defaultForward, strictBehaviors, serviceName, serviceInfo,
                flowState, metrics, Optional.of(pool), scriptEngine, scripts, clientCertificateCas);
    }

    /** Appends one or more stubs, in the given order. */
    public ImposterSpec stub(StubSpec... specs) {
        List<Stub> next = new ArrayList<>(stubs);
        for (StubSpec spec : specs) {
            next.add(spec.build());
        }
        return new ImposterSpec(name, port, protocol, recordRequests, recordMatches, allowCors, List.copyOf(next),
                defaultResponse, host, cert, key, defaultForward, strictBehaviors, serviceName, serviceInfo,
                flowState, metrics, proxyPool, scriptEngine, scripts, clientCertificateCas);
    }

    /** Appends already-built stubs, in the given order — e.g. the output of {@link ScenarioSpec#stubs()}. */
    public ImposterSpec stub(List<Stub> builtStubs) {
        List<Stub> next = new ArrayList<>(stubs);
        next.addAll(builtStubs);
        return new ImposterSpec(name, port, protocol, recordRequests, recordMatches, allowCors, List.copyOf(next),
                defaultResponse, host, cert, key, defaultForward, strictBehaviors, serviceName, serviceInfo,
                flowState, metrics, proxyPool, scriptEngine, scripts, clientCertificateCas);
    }

    /** Builds the immutable {@link ImposterDefinition} this spec represents. */
    public ImposterDefinition build() {
        if (cert.isPresent() != key.isPresent()) {
            throw new IllegalArgumentException("https(...) requires both a certificate and a key, or neither");
        }
        if (clientCertificateCas.isPresent() && !protocol.equalsIgnoreCase("https")) {
            throw new IllegalArgumentException("requireClientCertificate(...) needs an https imposter, "
                    + "got protocol \"" + protocol + "\" — a client certificate cannot be requested on a "
                    + "cleartext listener");
        }
        boolean verifyChain = clientCertificateCas.map(cas -> !cas.isEmpty()).orElse(false);
        ImposterDefinition def = new ImposterDefinition(
                port, host, protocol, cert, key, Optional.of(name),
                recordRequests, recordMatches, stubs, defaultResponse.map(IsSpec::buildIsResponse),
                defaultForward, allowCors, strictBehaviors, serviceName, serviceInfo, buildRiftConfig(),
                clientCertificateCas.isPresent(), verifyChain,
                // One anchor as a bare string: the spelling the engine echoes back, so a definition read
                // from the engine equals the one built here.
                clientCertificateCas.filter(cas -> !cas.isEmpty())
                        .map(cas -> new CaCertificates(cas, cas.size() == 1)),
                Map.of());
        if (FlowStateSupport.hasSpaceStub(def) && !FlowStateSupport.hasHeaderFlowIdSource(def)) {
            throw new IllegalArgumentException(
                    "space stubs can never match without a header flow-id source (the engine's flow-id "
                            + "default is imposter_port) — declare "
                            + ".flowState(inMemoryFlowState().flowIdFromHeader(\"X-Your-Header\"))");
        }
        return def;
    }

    private Optional<RiftConfig> buildRiftConfig() {
        Optional<RiftFlowStateConfig> flowStateConfig = flowState.map(FlowStateSpec::build);
        Optional<RiftProxyConfig> proxy = proxyPool.map(pool -> new RiftProxyConfig(Optional.empty(), Optional.of(pool)));
        RiftConfig config = new RiftConfig(flowStateConfig, metrics, proxy, scriptEngine, scripts);
        return config.isEmpty() ? Optional.empty() : Optional.of(config);
    }
}
