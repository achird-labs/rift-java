package io.github.achirdlabs.rift;

import io.github.achirdlabs.rift.dsl.IsSpec;
import io.github.achirdlabs.rift.error.CommunicationError;
import io.github.achirdlabs.rift.error.InvalidDefinition;
import io.github.achirdlabs.rift.json.JsonArray;
import io.github.achirdlabs.rift.json.JsonNumber;
import io.github.achirdlabs.rift.json.JsonNull;
import io.github.achirdlabs.rift.json.JsonObject;
import io.github.achirdlabs.rift.json.JsonString;
import io.github.achirdlabs.rift.json.JsonValue;
import io.github.achirdlabs.rift.model.IsResponse;
import io.github.achirdlabs.rift.model.Predicate;
import io.github.achirdlabs.rift.model.Response;
import io.github.achirdlabs.rift.model.ResponseMode;
import io.github.achirdlabs.rift.transport.HostAuthority;
import io.github.achirdlabs.rift.transport.RiftTransport;

import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * {@link Intercept} over a {@link RiftTransport}: a thin layer around {@code transport.intercept*}.
 * Rules are built in the engine's wire shape by {@link InterceptRules}, which adds each as it is
 * declared; {@link #replaceRules(Consumer)} stages them instead and installs the set in one call.
 */
final class InterceptImpl implements Intercept {

    private final RiftTransport transport;
    private final InetSocketAddress address;
    private final URI uri;
    private final Optional<URI> engineAddress;
    private final InetSocketAddress engineBound;
    private final CaMaterial caMaterial;
    /** Whether this handle started the listener (and so stops it), rather than attaching to one. */
    private final boolean owned;
    /** Tells the owning {@link RiftImpl} the engine is free for another intercept. */
    private final Runnable onClosed;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private volatile InterceptTrust trust;

    /**
     * Refuses a rule feature the running engine cannot honour; {@link RiftImpl} supplies its
     * engine-version check. An intercept built directly (in a test) checks nothing.
     */
    private final Consumer<RiftImpl.EngineRequirement> engineGate;

    /** Adds each declared rule to the engine as it is declared. */
    private final InterceptRules rules;

    private static final RiftImpl.EngineRequirement REPLACE_RULES = new RiftImpl.EngineRequirement(
            RiftImpl.INTERCEPT_REPLACE_RULES_SINCE, "intercept rule replace",
            "has no PUT /intercept/rules (rift_intercept_replace_rules) and answers 404",
            "clear the rules and add them again (clearRules, then serve/forward/redirectTo)");

    InterceptImpl(RiftTransport transport, JsonValue startResponse) {
        this(transport, startResponse, UnaryOperator.identity(), requirement -> { }, () -> { });
    }

    /**
     * A listener started here. The engine reports the address it bound; {@code dial} maps that to
     * where this client reaches it, which differs when the engine is on another machine.
     */
    InterceptImpl(RiftTransport transport, JsonValue startResponse, UnaryOperator<InetSocketAddress> dial,
            Consumer<RiftImpl.EngineRequirement> engineGate, Runnable onClosed) {
        this.transport = transport;
        this.engineGate = engineGate;
        this.rules = new InterceptRules(rule -> transport.interceptAddRules(rule.raw()), engineGate, this::requireOpen);
        this.owned = true;
        this.onClosed = onClosed;
        if (!(startResponse instanceof JsonObject obj)
                || !(obj.get("interceptPort") instanceof JsonNumber port)
                || !(obj.get("interceptUrl") instanceof JsonString url)) {
            // The keys, never the body: a generateCa() response carries the CA's private key.
            throw new CommunicationError(
                    "rift engine's intercept start response is missing 'interceptPort'/'interceptUrl'; it has "
                            + (startResponse instanceof JsonObject o ? o.fields().keySet() : startResponse.getClass().getSimpleName()));
        }
        URI reported = URI.create(url.value());
        this.engineAddress = Optional.of(reported);
        this.engineBound = new InetSocketAddress(reported.getHost(), port.asInt());
        this.address = Objects.requireNonNull(dial.apply(engineBound), "intercept address mapping returned null");
        this.uri = HostAuthority.httpUri(address.getHostString(), address.getPort());
        // Present only when the listener was started with generateCa() (returnCaKey).
        this.caMaterial = (obj.get("caCertPem") instanceof JsonString cert
                && obj.get("caKeyPem") instanceof JsonString key)
                ? new CaMaterial(cert.value(), key.value()) : null;
    }

    /** Attach mode: bind to a listener already started at engine launch, at the given endpoint. */
    InterceptImpl(RiftTransport transport, String host, int port) {
        this(transport, host, port, requirement -> { });
    }

    /** Attach mode, with the engine-version check {@link RiftImpl} supplies. */
    InterceptImpl(RiftTransport transport, String host, int port, Consumer<RiftImpl.EngineRequirement> engineGate) {
        this(transport, host, port, null, engineGate, () -> { });
    }

    /** Attach mode, carrying the CA the caller started the listener with ({@code null} when not given). */
    InterceptImpl(RiftTransport transport, String host, int port, CaMaterial ca,
            Consumer<RiftImpl.EngineRequirement> engineGate, Runnable onClosed) {
        this.transport = transport;
        this.engineGate = engineGate;
        this.rules = new InterceptRules(rule -> transport.interceptAddRules(rule.raw()), engineGate, this::requireOpen);
        this.owned = false;
        this.onClosed = onClosed;
        this.uri = HostAuthority.httpUri(host, port);
        this.address = new InetSocketAddress(host, port);
        this.engineAddress = Optional.empty();
        this.engineBound = null;
        this.caMaterial = ca;
    }

    /**
     * Refuses an attach whose supplied CA is not the one the listener serves. Certificates are
     * compared as DER: the engine serves its own PEM rendering of the same certificate.
     */
    static void requireListenerCa(CaMaterial supplied, String servedPem) {
        byte[] suppliedDer;
        try {
            suppliedDer = firstCertificate(supplied.certPem());
        } catch (CertificateException e) {
            throw new InvalidDefinition("the CA given to InterceptOptions.attach is not a PEM certificate: " + e.getMessage(), e);
        }
        byte[] servedDer;
        try {
            servedDer = firstCertificate(servedPem);
        } catch (CertificateException e) {
            throw new CommunicationError("the intercept listener's CA (GET /intercept/ca.pem) is not a PEM certificate: "
                    + e.getMessage(), e);
        }
        if (!Arrays.equals(suppliedDer, servedDer)) {
            throw new InvalidDefinition("the CA given to InterceptOptions.attach does not match the listener's CA (GET"
                    + " /intercept/ca.pem): the listener was started with another CA, so a client trusting the given one"
                    + " would fail its TLS handshake");
        }
    }

    private static byte[] firstCertificate(String pem) throws CertificateException {
        return CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)))
                .getEncoded();
    }

    @Override
    public InetSocketAddress address() {
        return address;
    }

    @Override
    public URI uri() {
        return uri;
    }

    @Override
    public Optional<URI> engineAddress() {
        return engineAddress;
    }

    /** Whether the engine bound a listener started here to a loopback address; false when attached. */
    boolean engineBoundToLoopback() {
        return engineBound != null && engineBound.getAddress() != null && engineBound.getAddress().isLoopbackAddress();
    }

    @Override
    public ProxySelector proxySelector() {
        return ProxySelector.of(address);
    }

    @Override
    public InterceptRule serve(String host, IsSpec response) {
        return rules.serve(host, response);
    }

    @Override
    public InterceptRule forward(String host, String target) {
        return rules.forward(host, target);
    }

    @Override
    public InterceptRule redirectTo(String host, Imposter imposter) {
        return rules.redirectTo(host, imposter);
    }

    @Override
    public InterceptRuleBuilder rule() {
        return rules.rule();
    }

    @Override
    public List<InterceptRule> replaceRules(Consumer<? super InterceptRuleSet> declare) {
        requireOpen();
        engineGate.accept(REPLACE_RULES);
        List<InterceptRule> staged = new ArrayList<>();
        AtomicBoolean staging = new AtomicBoolean(true);
        InterceptRules set = new InterceptRules(staged::add, engineGate, () -> {
            requireOpen();
            if (!staging.get()) {
                throw new IllegalStateException("this rule set belonged to a replaceRules call that has returned");
            }
        });
        try {
            declare.accept(set);
        } finally {
            staging.set(false);
        }
        transport.interceptReplaceRules(new JsonArray(staged.stream().map(InterceptRule::raw).toList()));
        return List.copyOf(staged);
    }

    @Override
    public List<InterceptRule> replaceRules(List<InterceptRule> rules) {
        requireOpen();
        engineGate.accept(REPLACE_RULES);
        List<InterceptRule> copy = List.copyOf(rules);
        transport.interceptReplaceRules(new JsonArray(copy.stream().map(InterceptRule::raw).toList()));
        return copy;
    }

    @Override
    public boolean removeRule(InterceptRule rule) {
        requireOpen();
        engineGate.accept(REPLACE_RULES);
        JsonValue target = canonical(rule.raw());
        List<InterceptRule> installed = rules();
        List<InterceptRule> kept = installed.stream().filter(r -> !canonical(r.raw()).equals(target)).toList();
        if (kept.size() == installed.size()) {
            return false;
        }
        replaceRules(kept);
        return true;
    }

    /**
     * A rule as the engine would echo it, minus the serde defaults: {@code GET /intercept/rules}
     * renders {@code "host":null}, {@code "predicates":[]}, {@code "headers":{}} and {@code
     * "body":null} that the SDK never sends, and re-serializes predicates.
     */
    static JsonValue canonical(JsonValue raw) {
        if (!(raw instanceof JsonObject rule)) {
            return raw;
        }
        JsonObject.Builder out = JsonObject.builder();
        rule.fields().forEach((key, value) -> {
            switch (key) {
                case "host" -> {
                    if (!(value instanceof JsonNull)) {
                        out.put(key, value);
                    }
                }
                case "predicates" -> {
                    if (value instanceof JsonArray predicates && !predicates.items().isEmpty()) {
                        out.put(key, new JsonArray(predicates.items().stream()
                                .map(p -> (JsonValue) JsonValue.parse(Predicate.fromJson(p.toJson()).toJson()))
                                .toList()));
                    } else if (!(value instanceof JsonArray)) {
                        out.put(key, value);
                    }
                }
                case "action" -> out.put(key, canonicalAction(value));
                default -> out.put(key, value);
            }
        });
        return out.build();
    }

    private static JsonValue canonicalAction(JsonValue action) {
        if (!(action instanceof JsonObject obj) || !(obj.get("serve") instanceof JsonObject serve)) {
            return action;
        }
        JsonObject.Builder stub = JsonObject.builder();
        serve.fields().forEach((key, value) -> {
            boolean emptyHeaders = key.equals("headers") && value instanceof JsonObject h && h.fields().isEmpty();
            boolean nullBody = key.equals("body") && value instanceof JsonNull;
            if (!emptyHeaders && !nullBody) {
                stub.put(key, value);
            }
        });
        JsonObject.Builder out = JsonObject.builder();
        obj.fields().forEach((key, value) -> out.put(key, key.equals("serve") ? stub.build() : value));
        return out.build();
    }

    @Override
    public List<InterceptRule> rules() {
        requireOpen();
        JsonValue listed = transport.interceptListRules();
        if (!(listed instanceof JsonArray array)) {
            throw new CommunicationError(
                    "rift engine's intercept rule list response is not a JSON array: " + listed.toJson());
        }
        List<InterceptRule> out = new ArrayList<>();
        for (JsonValue item : array.items()) {
            out.add(readRule(item));
        }
        return List.copyOf(out);
    }

    private static InterceptRule readRule(JsonValue item) {
        if (!(item instanceof JsonObject obj)) {
            throw new CommunicationError("intercept rule is not a JSON object: " + item.toJson());
        }
        String host = obj.get("host") instanceof JsonString h ? h.value() : "";
        return new InterceptRule(host, ruleKind(obj), obj);
    }

    private static RuleKind ruleKind(JsonObject obj) {
        if (obj.get("action") instanceof JsonObject action) {
            if (action.has("serve")) {
                return RuleKind.SERVE;
            }
            if (action.has("forward")) {
                return RuleKind.FORWARD;
            }
        }
        throw new CommunicationError("intercept rule has an unrecognized 'action': " + obj.toJson());
    }

    @Override
    public void clearRules() {
        requireOpen();
        transport.interceptClearRules();
    }

    @Override
    public java.util.Optional<CaMaterial> caMaterial() {
        return java.util.Optional.ofNullable(caMaterial);
    }

    @Override
    public InterceptTrust trust() {
        // A restart without a supplied CA mints a new one, so a stopped listener's trust is stale.
        requireOpen();
        InterceptTrust t = trust;
        if (t == null) {
            synchronized (this) {
                t = trust;
                if (t == null) {
                    t = new InterceptTrustImpl(transport.interceptCaPem());
                    trust = t;
                }
            }
        }
        return t;
    }

    /**
     * Synchronized so a concurrent caller waits for the outcome rather than returning before a stop
     * that may yet fail. The handle counts as closed only once the transport call succeeded: a failure
     * leaves it open and the engine claimed, so close() can be retried.
     */
    @Override
    public synchronized void close() {
        if (closed.get()) {
            return;
        }
        boolean engineFreed = true;
        if (owned) {
            try {
                transport.stopIntercept();
            } catch (UnsupportedOperationException e) {
                // A transport that cannot stop a listener: clear its rules, as before stop existed. The
                // listener keeps running, so the engine stays claimed.
                transport.interceptClearRules();
                engineFreed = false;
            }
        } else {
            // An attached listener belongs to whoever launched it: leave it running, rules cleared.
            transport.interceptClearRules();
        }
        closed.set(true);
        if (engineFreed) {
            onClosed.run();
        }
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("intercept is closed");
        }
    }
}
