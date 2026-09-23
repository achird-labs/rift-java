package io.github.achirdlabs.rift.dsl;

import io.github.achirdlabs.rift.codec.BodyCodecs;
import io.github.achirdlabs.rift.json.JsonString;
import io.github.achirdlabs.rift.json.JsonValue;
import io.github.achirdlabs.rift.model.Behavior;
import io.github.achirdlabs.rift.model.Behaviors;
import io.github.achirdlabs.rift.model.IsResponse;
import io.github.achirdlabs.rift.model.Response;
import io.github.achirdlabs.rift.model.ResponseMode;
import io.github.achirdlabs.rift.model.RiftErrorFault;
import io.github.achirdlabs.rift.model.RiftTcpFault;
import io.github.achirdlabs.rift.model.RiftFaultConfig;
import io.github.achirdlabs.rift.model.RiftLatencyFault;
import io.github.achirdlabs.rift.model.RiftResponseExtension;
import io.github.achirdlabs.rift.model.StateOp;

import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

/**
 * A literal ("is") response under construction: status code, headers, body, behaviors (the
 * {@link BehaviorChain} chainers), and the opt-in {@code _rift} extensions ({@code
 * fault}/{@code templated}). Created via {@link RiftDsl#ok()} and its siblings.
 *
 * <p>Instances are immutable: every chain method returns a new {@code IsSpec}. The terminal {@link
 * #build()} produces the {@link Response} model value.
 *
 * <p>Calling the same behavior chainer twice appends two entries, and both are written — as a
 * {@code behaviors} array, the only wire shape that can carry a repeated key (see {@link
 * Behaviors}). Note what the engine then does with it: rift 0.17.0 merges that array as it parses
 * and applies only the last entry of a repeated key, so two {@code copy} behaviors take effect as
 * one until a later engine release. Mountebank runs both.
 */
public final class IsSpec implements ResponseSpec, BehaviorChain<IsSpec> {

    private final String statusCode;
    private final Map<String, List<String>> headers;
    private final Optional<JsonValue> body;
    private final ResponseMode mode;
    private final List<Behavior> behaviors;
    private final Optional<RiftFaultConfig> fault;
    private final boolean templated;
    private final List<StateOp> stateOps;

    private IsSpec(
            String statusCode,
            Map<String, List<String>> headers,
            Optional<JsonValue> body,
            ResponseMode mode,
            List<Behavior> behaviors,
            Optional<RiftFaultConfig> fault,
            boolean templated,
            List<StateOp> stateOps) {
        this.statusCode = statusCode;
        this.headers = headers;
        this.body = body;
        this.mode = mode;
        this.behaviors = behaviors;
        this.fault = fault;
        this.templated = templated;
        this.stateOps = stateOps;
    }

    /** A fresh "is" response builder at the given status code, with no headers/body/behaviors yet. */
    static IsSpec is(String statusCode) {
        return new IsSpec(
                statusCode, Map.of(), Optional.empty(), ResponseMode.TEXT, List.of(),
                Optional.empty(), false, List.of());
    }

    /**
     * Adds or replaces a response header. Repeatable — each call adds another header, so a response
     * needing several headers chains this method once per header.
     */
    public IsSpec withHeader(String name, String... values) {
        Map<String, List<String>> next = new LinkedHashMap<>(headers);
        next.put(name, List.of(values));
        return new IsSpec(statusCode, next, body, mode, behaviors, fault, templated, stateOps);
    }

    /** Sets the response body to the given JSON value directly. */
    public IsSpec withJsonBody(JsonValue value) {
        return new IsSpec(statusCode, headers, Optional.of(value), mode, behaviors, fault, templated, stateOps);
    }

    /** Sets the response body by parsing {@code jsonText} as JSON. */
    public IsSpec withJsonBody(String jsonText) {
        return withJsonBody(RiftDsl.json(jsonText));
    }

    /**
     * Sets the response body to {@code pojo}, serialized to JSON by the registered {@link
     * io.github.achirdlabs.rift.codec.RiftBodyCodec} (see {@link RiftDsl#useBodyCodec}).
     */
    public IsSpec withBodyFromCodec(Object pojo) {
        return withJsonBody(BodyCodecs.resolve().toJson(pojo));
    }

    /**
     * Sets the response body to the literal text {@code text} (wrapped as a JSON string, per
     * Mountebank's text-mode body convention).
     */
    public IsSpec withTextBody(String text) {
        return new IsSpec(statusCode, headers, Optional.of(new JsonString(text)), mode, behaviors, fault, templated, stateOps);
    }

    /**
     * Sets the response body to the base64 encoding of {@code bytes} and switches the response mode
     * to {@link ResponseMode#BINARY}.
     */
    public IsSpec withBinaryBody(byte[] bytes) {
        String encoded = Base64.getEncoder().encodeToString(bytes);
        return new IsSpec(statusCode, headers, Optional.of(new JsonString(encoded)), ResponseMode.BINARY, behaviors, fault, templated, stateOps);
    }

    // The behavior chainers live on BehaviorChain. These overrides only pin their IsSpec return type
    // in the class file: a default method returning S erases to ResponseSpec, so without them code
    // compiled against an earlier IsSpec (where these were declared here) fails with NoSuchMethodError.

    @Override
    public IsSpec after(Duration duration) {
        return BehaviorChain.super.after(duration);
    }

    @Override
    public IsSpec waitMs(long milliseconds) {
        return BehaviorChain.super.waitMs(milliseconds);
    }

    @Override
    public IsSpec waitBetween(long minMs, long maxMs) {
        return BehaviorChain.super.waitBetween(minMs, maxMs);
    }

    @Override
    public IsSpec waitInject(String script) {
        return BehaviorChain.super.waitInject(script);
    }

    @Override
    public IsSpec waitScript(String source) {
        return BehaviorChain.super.waitScript(source);
    }

    @Override
    public IsSpec decorate(String script) {
        return BehaviorChain.super.decorate(script);
    }

    @Override
    public IsSpec repeat(int count) {
        return BehaviorChain.super.repeat(count);
    }

    @Override
    public IsSpec copy(CopySpec... copies) {
        return BehaviorChain.super.copy(copies);
    }

    @Override
    public IsSpec copyObject(CopySpec copy) {
        return BehaviorChain.super.copyObject(copy);
    }

    @Override
    public IsSpec lookup(LookupSpec... lookups) {
        return BehaviorChain.super.lookup(lookups);
    }

    @Override
    public IsSpec lookupObject(LookupSpec lookup) {
        return BehaviorChain.super.lookupObject(lookup);
    }

    @Override
    public IsSpec shellTransform(String... commands) {
        return BehaviorChain.super.shellTransform(commands);
    }

    /**
     * Marks this response as templated: its body/headers may contain {@code {{ }}} placeholders the
     * engine resolves against the request (sets {@code _rift.templated}).
     */
    public IsSpec templated() {
        return new IsSpec(statusCode, headers, body, mode, behaviors, fault, true, stateOps);
    }

    /**
     * After this response is built, stores {@code valueTemplate} under {@code key} in the request's
     * flow. The value is rendered as a template against the request (for example {@code {{
     * request.query.id }}}) whether or not the response is {@link #templated()}; a rendering that is
     * a canonical integer is stored as a number.
     *
     * <p>Flow-state writes ({@code _rift.stateOps}) run after templating and behaviors, in the
     * order they are chained, so a templated body reading {@code {{ state.key }}} sees the value from
     * before this response's writes. Without {@link FlowStateSpec#flowIdFromHeader} every caller
     * shares one flow, keyed by the imposter's port. An imposter created with writes and no
     * flow-state configuration gets an in-memory store; the engine decides this when the imposter is
     * created, so a stub with writes added later to an imposter without a store has nowhere to write.
     * The writes do not run when a {@code _rift} fault fires. Requires a rift engine &ge; 0.18.0; on
     * {@code create} and {@code replaceAll} the SDK refuses to send them to an older engine, which
     * would drop them silently. Not carried by a default response ({@link
     * ImposterSpec#defaultResponse}).
     */
    public IsSpec setState(String key, String valueTemplate) {
        return withStateOp(new StateOp.Set(key, valueTemplate));
    }

    /** After this response, adds 1 to the integer under {@code key}; see {@link #setState}. */
    public IsSpec incrementState(String key) {
        return withStateOp(new StateOp.Increment(key));
    }

    /**
     * After this response, adds {@code by} (which may be negative) to the integer under {@code key};
     * see {@link #setState}.
     */
    public IsSpec incrementState(String key, long by) {
        return withStateOp(new StateOp.Increment(key, by));
    }

    /** After this response, removes {@code key} from the flow; see {@link #setState}. */
    public IsSpec deleteState(String key) {
        return withStateOp(new StateOp.Delete(key));
    }

    /** After this response, removes every key in the flow; see {@link #setState}. */
    public IsSpec clearFlowState() {
        return withStateOp(new StateOp.ClearFlow());
    }

    private IsSpec withStateOp(StateOp op) {
        Objects.requireNonNull(op, "op");
        List<StateOp> next = Stream.concat(stateOps.stream(), Stream.of(op)).toList();
        return new IsSpec(statusCode, headers, body, mode, behaviors, fault, templated, next);
    }

    /** Injects a latency fault: {@code probability} of the time, delay the response by a fixed duration. */
    public IsSpec withLatencyFault(double probability, Duration fixed) {
        RiftLatencyFault latency = new RiftLatencyFault(probability, 0, 0, Optional.of(fixed.toMillis()));
        return withFault(cfg -> new RiftFaultConfig(Optional.of(latency), cfg.error(), cfg.tcp()));
    }

    /** Injects a latency fault: {@code probability} of the time, delay the response by a random duration in {@code [min, max]}. */
    public IsSpec withLatencyFault(double probability, Duration min, Duration max) {
        RiftLatencyFault latency = new RiftLatencyFault(probability, min.toMillis(), max.toMillis(), Optional.empty());
        return withFault(cfg -> new RiftFaultConfig(Optional.of(latency), cfg.error(), cfg.tcp()));
    }

    /** Injects an error fault: {@code probability} of the time, respond with {@code status} instead. */
    public IsSpec withErrorFault(double probability, int status) {
        return withErrorFault(probability, status, Optional.empty(), Map.of());
    }

    /**
     * Injects an error fault: {@code probability} of the time, respond with {@code status} and the
     * given response {@code body} instead. The body is the literal response body (the model's error
     * body is text); to send a JSON error body, pass its serialized text.
     */
    public IsSpec withErrorFault(double probability, int status, String body) {
        return withErrorFault(probability, status, Optional.of(body), Map.of());
    }

    /**
     * Injects an error fault carrying response {@code headers}: {@code probability} of the time,
     * respond with {@code status}, {@code body} and {@code headers} instead (e.g. a {@code
     * Retry-After} on a synthesized 503).
     */
    public IsSpec withErrorFault(double probability, int status, String body, Map<String, String> headers) {
        return withErrorFault(probability, status, Optional.of(body), headers);
    }

    private IsSpec withErrorFault(double probability, int status, Optional<String> body, Map<String, String> headers) {
        RiftErrorFault error = new RiftErrorFault(probability, status, body, headers);
        return withFault(cfg -> new RiftFaultConfig(cfg.latency(), Optional.of(error), cfg.tcp()));
    }

    /**
     * Injects a raw TCP-level fault, e.g. {@link Fault#CONNECTION_RESET_BY_PEER}, that always fires
     * (the bare wire form). For a probabilistic TCP fault use {@link #withTcpFault(double, Fault)}.
     */
    public IsSpec withTcpFault(Fault kind) {
        return withFault(cfg -> new RiftFaultConfig(cfg.latency(), cfg.error(),
                Optional.of(new RiftTcpFault.Bare(kind.name()))));
    }

    /**
     * Injects a probabilistic raw TCP-level fault that fires with the given {@code probability}
     * (the object wire form). Requires a rift engine &ge; 0.13.2 (rift#531).
     */
    public IsSpec withTcpFault(double probability, Fault kind) {
        return withFault(cfg -> new RiftFaultConfig(cfg.latency(), cfg.error(),
                Optional.of(new RiftTcpFault.Probabilistic(probability, kind.name()))));
    }

    private IsSpec withFault(UnaryOperator<RiftFaultConfig> mutator) {
        RiftFaultConfig current = fault.orElse(new RiftFaultConfig(Optional.empty(), Optional.empty(), Optional.empty()));
        return new IsSpec(statusCode, headers, body, mode, behaviors, Optional.of(mutator.apply(current)), templated, stateOps);
    }

    @Override
    public IsSpec withBehavior(Behavior behavior) {
        List<Behavior> next = Stream.concat(behaviors.stream(), Stream.of(behavior)).toList();
        return new IsSpec(statusCode, headers, body, mode, next, fault, templated, stateOps);
    }

    /** Builds the immutable {@link Response} this spec represents. */
    @Override
    public Response build() {
        return new Response.Is(new IsResponse(statusCode, headers, body, mode), new Behaviors(behaviors), riftExtension());
    }

    /** Builds this spec as an imposter-level {@link IsResponse} default response. */
    IsResponse buildIsResponse() {
        return new IsResponse(statusCode, headers, body, mode);
    }

    private Optional<RiftResponseExtension> riftExtension() {
        if (fault.isEmpty() && !templated && stateOps.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new RiftResponseExtension(fault, Optional.empty(), templated, stateOps, Map.of()));
    }
}
