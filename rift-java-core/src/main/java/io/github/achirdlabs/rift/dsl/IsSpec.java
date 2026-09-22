package io.github.achirdlabs.rift.dsl;

import io.github.achirdlabs.rift.codec.BodyCodecs;
import io.github.achirdlabs.rift.json.JsonArray;
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

import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

/**
 * A literal ("is") response under construction: status code, headers, body, behaviors ({@code
 * wait}/{@code decorate}/{@code repeat}/{@code copy}/{@code lookup}/{@code shellTransform}), and the
 * opt-in {@code _rift} extensions ({@code fault}/{@code templated}). Created via
 * {@link RiftDsl#ok()} and its siblings.
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
public final class IsSpec implements ResponseSpec {

    private final String statusCode;
    private final Map<String, List<String>> headers;
    private final Optional<JsonValue> body;
    private final ResponseMode mode;
    private final List<Behavior> behaviors;
    private final Optional<RiftFaultConfig> fault;
    private final boolean templated;

    private IsSpec(
            String statusCode,
            Map<String, List<String>> headers,
            Optional<JsonValue> body,
            ResponseMode mode,
            List<Behavior> behaviors,
            Optional<RiftFaultConfig> fault,
            boolean templated) {
        this.statusCode = statusCode;
        this.headers = headers;
        this.body = body;
        this.mode = mode;
        this.behaviors = behaviors;
        this.fault = fault;
        this.templated = templated;
    }

    /** A fresh "is" response builder at the given status code, with no headers/body/behaviors yet. */
    static IsSpec is(String statusCode) {
        return new IsSpec(
                statusCode, Map.of(), Optional.empty(), ResponseMode.TEXT, List.of(),
                Optional.empty(), false);
    }

    /**
     * Adds or replaces a response header. Repeatable — each call adds another header, so a response
     * needing several headers chains this method once per header.
     */
    public IsSpec withHeader(String name, String... values) {
        Map<String, List<String>> next = new LinkedHashMap<>(headers);
        next.put(name, List.of(values));
        return new IsSpec(statusCode, next, body, mode, behaviors, fault, templated);
    }

    /** Sets the response body to the given JSON value directly. */
    public IsSpec withJsonBody(JsonValue value) {
        return new IsSpec(statusCode, headers, Optional.of(value), mode, behaviors, fault, templated);
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
        return new IsSpec(statusCode, headers, Optional.of(new JsonString(text)), mode, behaviors, fault, templated);
    }

    /**
     * Sets the response body to the base64 encoding of {@code bytes} and switches the response mode
     * to {@link ResponseMode#BINARY}.
     */
    public IsSpec withBinaryBody(byte[] bytes) {
        String encoded = Base64.getEncoder().encodeToString(bytes);
        return new IsSpec(statusCode, headers, Optional.of(new JsonString(encoded)), ResponseMode.BINARY, behaviors, fault, templated);
    }

    /** Delays the response by the given duration (a {@code wait} behavior with a fixed delay). */
    public IsSpec after(Duration duration) {
        return waitMs(duration.toMillis());
    }

    /**
     * Delays the response by a fixed number of milliseconds (a {@code wait} behavior).
     *
     * <p>Named {@code waitMs} rather than {@code wait}: {@code wait(long)} would silently attempt to
     * override the {@code final} {@link Object#wait(long)} and fail to compile.
     */
    public IsSpec waitMs(long milliseconds) {
        return withBehavior(new Behavior.Wait(new io.github.achirdlabs.rift.model.WaitSpec.Fixed(milliseconds)));
    }

    /** Delays the response by a random duration in {@code [minMs, maxMs]} (a {@code wait} behavior with a range). */
    public IsSpec waitBetween(long minMs, long maxMs) {
        return withBehavior(new Behavior.Wait(new io.github.achirdlabs.rift.model.WaitSpec.Range(minMs, maxMs)));
    }

    /**
     * Delays the response by a duration computed by the given script (an {@code inject}ed {@code wait}
     * value) — rift's object spelling, a superset not portable to Mountebank (rift#608).
     *
     * <p>A function wait is an injection surface in either spelling: the engine must run with
     * {@code --allowInjection} or it rejects the imposter with a 400 ({@link
     * io.github.achirdlabs.rift.error.InvalidDefinition}). {@link #waitMs} and {@link #waitBetween}
     * are unaffected.
     */
    public IsSpec waitInject(String script) {
        return withBehavior(new Behavior.Wait(new io.github.achirdlabs.rift.model.WaitSpec.Inject(script)));
    }

    /**
     * Delays the response by a bare-string {@code wait} (a function body / named latency), round-tripped
     * verbatim — the Mountebank-compatible spelling of {@link #waitInject}, and equally an injection
     * surface: it needs the engine's {@code --allowInjection} for the same reason (rift#610).
     */
    public IsSpec waitScript(String source) {
        return withBehavior(new Behavior.Wait(new io.github.achirdlabs.rift.model.WaitSpec.Script(source)));
    }

    /** Post-processes the response with the given decorator script (a {@code decorate} behavior). */
    public IsSpec decorate(String script) {
        return withBehavior(new Behavior.Decorate(script));
    }

    /**
     * Serves this response only for the first {@code count} matches, after which the stub's next
     * response takes over (a {@code repeat} behavior).
     */
    public IsSpec repeat(int count) {
        return withBehavior(new Behavior.Repeat(count));
    }

    /**
     * Marks this response as templated: its body/headers may contain {@code {{ }}} placeholders the
     * engine resolves against the request (sets {@code _rift.templated}).
     */
    public IsSpec templated() {
        return new IsSpec(statusCode, headers, body, mode, behaviors, fault, true);
    }

    /** Adds a {@code copy} behavior entry per {@link CopySpec} given (the array wire form). */
    public IsSpec copy(CopySpec... copies) {
        List<io.github.achirdlabs.rift.model.CopyEntry> entries =
                Arrays.stream(copies).map(CopySpec::build).toList();
        return withBehavior(new Behavior.Copy(entries));
    }

    /** Adds a single {@code copy} entry in the engine's object wire form (not wrapped in an array). */
    public IsSpec copyObject(CopySpec copy) {
        return withBehavior(new Behavior.Copy(List.of(copy.build()), true));
    }

    /**
     * Adds a {@code lookup} behavior: an array of lookup entries, each keyed by a request extraction
     * and resolved against a data source. There is no typed {@code Behavior.Lookup} — this rides
     * {@link Behavior.Unknown} so it round-trips losslessly while still emitting the correct wire
     * shape.
     */
    public IsSpec lookup(LookupSpec... lookups) {
        JsonArray array = new JsonArray(Arrays.stream(lookups).map(LookupSpec::build).toList());
        return withBehavior(new Behavior.Unknown("lookup", array));
    }

    /** Adds a single {@code lookup} entry in the engine's object wire form (not wrapped in an array). */
    public IsSpec lookupObject(LookupSpec lookup) {
        return withBehavior(new Behavior.Unknown("lookup", lookup.build()));
    }

    /**
     * Adds a {@code shellTransform} behavior running each command in order. Rides {@link
     * Behavior.Unknown} (as a JSON array of commands) rather than the single-command typed {@link
     * Behavior.ShellTransform}, since the multi-command wire shape is an array.
     */
    public IsSpec shellTransform(String... commands) {
        JsonArray array = new JsonArray(Arrays.stream(commands).<JsonValue>map(JsonString::new).toList());
        return withBehavior(new Behavior.Unknown("shellTransform", array));
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
        return new IsSpec(statusCode, headers, body, mode, behaviors, Optional.of(mutator.apply(current)), templated);
    }

    private IsSpec withBehavior(Behavior behavior) {
        List<Behavior> next = Stream.concat(behaviors.stream(), Stream.of(behavior)).toList();
        return new IsSpec(statusCode, headers, body, mode, next, fault, templated);
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
        if (fault.isEmpty() && !templated) {
            return Optional.empty();
        }
        return Optional.of(new RiftResponseExtension(fault, Optional.empty(), templated));
    }
}
