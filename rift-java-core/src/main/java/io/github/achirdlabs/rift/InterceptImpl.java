package io.github.achirdlabs.rift;

import io.github.achirdlabs.rift.dsl.IsSpec;
import io.github.achirdlabs.rift.error.CommunicationError;
import io.github.achirdlabs.rift.error.InvalidDefinition;
import io.github.achirdlabs.rift.json.JsonArray;
import io.github.achirdlabs.rift.json.JsonNumber;
import io.github.achirdlabs.rift.json.JsonObject;
import io.github.achirdlabs.rift.json.JsonString;
import io.github.achirdlabs.rift.json.JsonValue;
import io.github.achirdlabs.rift.model.IsResponse;
import io.github.achirdlabs.rift.model.Predicate;
import io.github.achirdlabs.rift.model.Response;
import io.github.achirdlabs.rift.model.ResponseMode;
import io.github.achirdlabs.rift.transport.RiftTransport;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link Intercept} over a {@link RiftTransport}: every method is a thin JSON-shape translation
 * layer around {@code transport.intercept*}, matching the engine's {@code InterceptRule}/{@code
 * InterceptAction} wire model (host + predicates + one of {@code {"serve":...}}/{@code
 * {"forward":{"port":...}}}) — see {@code intercept_rules.rs} in the rift engine.
 */
final class InterceptImpl implements Intercept {

    private final RiftTransport transport;
    private final InetSocketAddress address;
    private final URI uri;
    private final CaMaterial caMaterial;

    private volatile InterceptTrust trust;

    InterceptImpl(RiftTransport transport, JsonValue startResponse) {
        this.transport = transport;
        if (!(startResponse instanceof JsonObject obj)
                || !(obj.get("interceptPort") instanceof JsonNumber port)
                || !(obj.get("interceptUrl") instanceof JsonString url)) {
            throw new CommunicationError(
                    "rift engine's intercept start response is missing 'interceptPort'/'interceptUrl': "
                            + startResponse.toJson());
        }
        this.uri = URI.create(url.value());
        this.address = new InetSocketAddress(uri.getHost(), port.asInt());
        // Present only when the listener was started with generateCa() (returnCaKey).
        this.caMaterial = (obj.get("caCertPem") instanceof JsonString cert
                && obj.get("caKeyPem") instanceof JsonString key)
                ? new CaMaterial(cert.value(), key.value()) : null;
    }

    /** Attach mode: bind to a listener already started at engine launch, at the given endpoint. */
    InterceptImpl(RiftTransport transport, String host, int port) {
        this.transport = transport;
        this.uri = URI.create("http://" + host + ":" + port);
        this.address = new InetSocketAddress(host, port);
        this.caMaterial = null;
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
    public ProxySelector proxySelector() {
        return ProxySelector.of(address);
    }

    @Override
    public InterceptRule serve(String host, IsSpec response) {
        return addServeRule(host, List.of(), response, RuleKind.SERVE);
    }

    @Override
    public InterceptRule forward(String host, String hostPort) {
        return addForwardRule(host, List.of(), parsePort(hostPort), RuleKind.FORWARD);
    }

    @Override
    public InterceptRule redirectTo(String host, Imposter imposter) {
        // REDIRECT is an SDK-level label only: the wire action is identical to forward()'s (see
        // RuleKind), so this rule is indistinguishable from a plain forward() once round-tripped.
        return addForwardRule(host, List.of(), imposter.port(), RuleKind.REDIRECT);
    }

    @Override
    public InterceptRuleBuilder rule() {
        return new InterceptRuleBuilder(this);
    }

    // Shared by the host-only methods above and by InterceptRuleBuilder (predicate-scoped, host-optional).
    InterceptRule addServeRule(String host, List<Predicate> predicates, IsSpec response, RuleKind kind) {
        JsonObject action = JsonObject.builder().put("serve", toServeStub(response)).build();
        JsonObject rule = ruleJson(host, predicates, action);
        transport.interceptAddRules(rule);
        return new InterceptRule(host, kind, rule);
    }

    InterceptRule addForwardRule(String host, List<Predicate> predicates, int port, RuleKind kind) {
        JsonObject action = JsonObject.builder()
                .put("forward", JsonObject.builder().put("port", JsonNumber.of(port)).build())
                .build();
        JsonObject rule = ruleJson(host, predicates, action);
        transport.interceptAddRules(rule);
        return new InterceptRule(host, kind, rule);
    }

    /**
     * The engine's {@code InterceptRule} wire shape: an <em>optional</em> {@code host} (absent = match
     * any intercepted host), the {@code predicates} matched like stub predicates (omitted when empty),
     * and the action — see {@code intercept_rules.rs}.
     */
    private static JsonObject ruleJson(String host, List<Predicate> predicates, JsonObject action) {
        JsonObject.Builder builder = JsonObject.builder();
        if (host != null) {
            builder.put("host", new JsonString(host));
        }
        if (!predicates.isEmpty()) {
            builder.put("predicates", new JsonArray(
                    predicates.stream().map(p -> (JsonValue) JsonValue.parse(p.toJson())).toList()));
        }
        return builder.put("action", action).build();
    }

    /**
     * Extracts the trailing port number from a {@code host:port} (or bare-port) string. The
     * engine's {@code forward} action only ever targets a numeric localhost imposter port (see
     * {@code ForwardTarget { port: u16 }} in {@code intercept_rules.rs}) — any host component here
     * is for this method's own convenience only and is never sent over the wire.
     */
    static int parsePort(String hostPort) {
        int colon = hostPort.lastIndexOf(':');
        String portPart = colon >= 0 ? hostPort.substring(colon + 1) : hostPort;
        try {
            return Integer.parseInt(portPart);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not a valid host:port (or bare port): " + hostPort, e);
        }
    }

    @Override
    public List<InterceptRule> rules() {
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
        transport.interceptClearRules();
    }

    @Override
    public java.util.Optional<CaMaterial> caMaterial() {
        return java.util.Optional.ofNullable(caMaterial);
    }

    @Override
    public InterceptTrust trust() {
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

    @Override
    public void close() {
        // The intercept listener itself has no per-instance stop over this SPI — it is torn down
        // only when the owning engine (and thus its Rift/RiftTransport) is closed. Clearing rules
        // is the best-effort cleanup available here.
        clearRules();
    }

    /**
     * Projects an {@link IsSpec} down to the engine's flat {@code ServeStub} shape: a numeric
     * {@code statusCode}, single-valued {@code headers}, and a plain-text {@code body} (see
     * {@code ServeStub} in {@code intercept_rules.rs}) — narrower than the full {@code is} response
     * shape a stub uses (multi-value headers, a structured JSON body, behaviors, faults). Anything
     * beyond status/headers/body is <em>rejected</em> rather than dropped — see
     * {@link #requireDeliverable}.
     *
     * @throws InvalidDefinition if the response carries a construct the serve action cannot deliver
     */
    private static JsonObject toServeStub(IsSpec response) {
        if (!(response.build() instanceof Response.Is is)) {
            throw new IllegalStateException("unreachable: IsSpec.build() always returns Response.Is");
        }
        requireDeliverable(is);
        IsResponse ir = is.is();
        JsonObject.Builder builder = JsonObject.builder();
        builder.put("statusCode", JsonNumber.of(statusAsInt(ir.statusCode())));
        if (!ir.headers().isEmpty()) {
            JsonObject.Builder headers = JsonObject.builder();
            ir.headers().forEach((name, values) -> {
                if (!values.isEmpty()) {
                    headers.put(name, new JsonString(values.get(0)));
                }
            });
            builder.put("headers", headers.build());
        }
        ir.body().ifPresent(body -> builder.put("body", new JsonString(bodyAsText(body))));
        return builder.build();
    }

    /**
     * Rejects a response the intercept {@code serve} action cannot deliver.
     *
     * <p>The engine's {@code ServeStub} is only {@code {statusCode, headers, body}} with
     * <em>single-valued</em> headers, and its deserializer does not use {@code deny_unknown_fields} —
     * so a richer response posted here is accepted with a {@code 200} and then silently ignored at
     * request time. That is worse than a rejection: a fault-injection test written against a
     * {@code serve} rule stays green while asserting on the success response it never asked for.
     *
     * <p>Every offending construct is collected in one pass so a caller learns about all of them at
     * once rather than one exception per round trip. Ordering is deterministic: behaviors keep their
     * declaration order and headers are insertion-ordered by {@code JsonSupport.orderedCopy}.
     *
     * @throws InvalidDefinition naming every offending construct, and pointing at
     *         {@link Intercept#redirectTo}, which reaches a real imposter and so has full stub fidelity
     */
    static void requireDeliverable(Response.Is is) {
        List<String> undeliverable = new ArrayList<>();
        is.behaviors().entries().forEach(behavior -> undeliverable.add("_behaviors." + behavior.key()));
        is.rift().ifPresent(rift -> {
            rift.fault().ifPresent(fault -> {
                if (fault.latency().isPresent()) {
                    undeliverable.add("_rift.fault.latency (withLatencyFault)");
                }
                if (fault.error().isPresent()) {
                    undeliverable.add("_rift.fault.error (withErrorFault)");
                }
                if (fault.tcp().isPresent()) {
                    undeliverable.add("_rift.fault.tcp (withTcpFault)");
                }
            });
            if (rift.script().isPresent()) {
                undeliverable.add("_rift.script");
            }
            if (rift.templated()) {
                undeliverable.add("_rift.templated (templated)");
            }
        });
        IsResponse ir = is.is();
        if (ir.mode() == ResponseMode.BINARY) {
            undeliverable.add("a binary body (_mode=binary, withBinaryBody)");
        }
        ir.headers().forEach((name, values) -> {
            if (values.size() > 1) {
                undeliverable.add("repeated header '" + name + "'");
            }
        });
        is.extra().keySet().forEach(key -> undeliverable.add("response key '" + key + "'"));
        ir.extra().keySet().forEach(key -> undeliverable.add("is response key '" + key + "'"));

        if (!undeliverable.isEmpty()) {
            throw new InvalidDefinition("intercept serve cannot deliver " + String.join(", ", undeliverable)
                    + " — the engine's serve action carries only statusCode, single-valued headers and body, so"
                    + " the rule would be registered and then answer a response you did not ask for."
                    + " Use redirectTo(imposter) for full stub fidelity.");
        }
    }

    private static int statusAsInt(String statusCode) {
        try {
            return Integer.parseInt(statusCode);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "intercept serve() requires a numeric status code, got: " + statusCode, e);
        }
    }

    private static String bodyAsText(JsonValue body) {
        return body instanceof JsonString s ? s.value() : body.toJson();
    }
}
