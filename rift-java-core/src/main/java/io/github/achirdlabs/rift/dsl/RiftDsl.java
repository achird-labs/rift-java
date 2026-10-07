package io.github.achirdlabs.rift.dsl;

import io.github.achirdlabs.rift.codec.BodyCodecs;
import io.github.achirdlabs.rift.codec.RiftBodyCodec;
import io.github.achirdlabs.rift.json.JsonBool;
import io.github.achirdlabs.rift.json.JsonObject;
import io.github.achirdlabs.rift.json.JsonString;
import io.github.achirdlabs.rift.json.JsonValue;
import io.github.achirdlabs.rift.model.Predicate;
import io.github.achirdlabs.rift.model.PredicateOperation;
import io.github.achirdlabs.rift.model.PredicateParameters;
import io.github.achirdlabs.rift.verify.VerificationTimes;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A fluent, static-import DSL over the typed Rift/Mountebank wire model
 * ({@code io.github.achirdlabs.rift.model}).
 *
 * <p>Every method here builds {@code model.*} values directly — none of them parse JSON. The
 * intended use is {@code import static io.github.achirdlabs.rift.dsl.RiftDsl.*;}, then compose
 * an imposter as a single expression:
 *
 * <pre>{@code
 * ImposterDefinition imposter = imposter("Basic REST API")
 *         .port(4545)
 *         .stub(onGet("/health").willReturn(ok().withTextBody("OK")))
 *         .build();
 * }</pre>
 *
 * <p>The grammar has three parts: imposter/stub construction ({@link #imposter(String)}, {@link
 * #onGet(String)} and siblings), predicate matchers and field binders ({@link #equals(String)} and
 * siblings, {@link #method(Matcher)} and siblings, {@link #and(PredicateSpec...)} and siblings),
 * and response construction ({@link #ok()} and siblings, {@link #proxyTo(String)}, {@link
 * #fault(Fault)}, {@link #inject(String)}, {@link #script(Script)}). {@link #scenario(String)}
 * builds a scenario finite-state machine as a list of stubs.
 */
public final class RiftDsl {

    private RiftDsl() {}

    // ------------------------------------------------------------------
    // Imposter / stub construction
    // ------------------------------------------------------------------

    /** Starts building an imposter with the given {@code name}. */
    public static ImposterSpec imposter(String name) {
        return new ImposterSpec(name);
    }

    /** A stub seeded with a combined {@code equals} predicate on {@code method: "GET"} and {@code path}. */
    public static StubSpec onGet(String path) {
        return on("GET", path);
    }

    /** A stub seeded with a combined {@code equals} predicate on {@code method: "POST"} and {@code path}. */
    public static StubSpec onPost(String path) {
        return on("POST", path);
    }

    /** A stub seeded with a combined {@code equals} predicate on {@code method: "PUT"} and {@code path}. */
    public static StubSpec onPut(String path) {
        return on("PUT", path);
    }

    /** A stub seeded with a combined {@code equals} predicate on {@code method: "DELETE"} and {@code path}. */
    public static StubSpec onDelete(String path) {
        return on("DELETE", path);
    }

    /** A stub seeded with a combined {@code equals} predicate on {@code method: "PATCH"} and {@code path}. */
    public static StubSpec onPatch(String path) {
        return on("PATCH", path);
    }

    /** A stub seeded with a combined {@code equals} predicate on {@code method: "HEAD"} and {@code path}. */
    public static StubSpec onHead(String path) {
        return on("HEAD", path);
    }

    /** A stub seeded with a combined {@code equals} predicate on {@code method: "OPTIONS"} and {@code path}. */
    public static StubSpec onOptions(String path) {
        return on("OPTIONS", path);
    }

    /** A stub with no seed predicate: build it up entirely with {@code .withXxx(...)} calls. */
    public static StubSpec onRequest() {
        return new StubSpec(List.of());
    }

    /** A stub seeded with a combined {@code equals} predicate on the given {@code method} and {@code path}. */
    public static StubSpec on(String method, String path) {
        Map<String, JsonValue> fields = new LinkedHashMap<>();
        fields.put("method", new JsonString(method));
        fields.put("path", new JsonString(path));
        Predicate seed = new Predicate(PredicateParameters.EMPTY, new PredicateOperation.Equals(fields));
        return new StubSpec(List.of(seed));
    }

    // ------------------------------------------------------------------
    // Matchers
    // ------------------------------------------------------------------

    /** An {@code equals} match against the literal string {@code value}. */
    public static Matcher equals(String value) {
        return Matcher.create(Matcher.Kind.EQUALS, new JsonString(value));
    }

    /** An {@code equals} match against the literal JSON value {@code value} (typically an object or array body). */
    public static Matcher equals(JsonValue value) {
        return Matcher.create(Matcher.Kind.EQUALS, value);
    }

    /**
     * An {@code equals} match — alias for {@link #equals(String)} usable under {@code import static}.
     *
     * <p>A bare {@code equals(...)} call always resolves to the inherited {@link Object#equals(Object)}
     * before a static import is considered, so {@code equals} is the one matcher that cannot be
     * static-imported unqualified. {@code eq} has no such collision; prefer it in DSL code.
     */
    public static Matcher eq(String value) {
        return equals(value);
    }

    /** An {@code equals} match against a literal JSON value — alias for {@link #equals(JsonValue)}. */
    public static Matcher eq(JsonValue value) {
        return equals(value);
    }

    /** An {@code equals} match — alias for {@link #equals(String)}, usable unqualified under {@code import static}. */
    public static Matcher equalTo(String value) {
        return equals(value);
    }

    /** An {@code equals} match against a literal JSON value — alias for {@link #equals(JsonValue)}. */
    public static Matcher equalTo(JsonValue value) {
        return equals(value);
    }

    /**
     * An {@code equals} match against {@code pojo}, serialized to JSON by the registered {@link
     * RiftBodyCodec} (see {@link #useBodyCodec}).
     */
    public static Matcher equalTo(Object pojo) {
        return equalTo(BodyCodecs.resolve().toJson(pojo));
    }

    /** A {@code deepEquals} match against the literal string {@code value} (exact match, no partial-object semantics). */
    public static Matcher deepEquals(String value) {
        return Matcher.create(Matcher.Kind.DEEP_EQUALS, new JsonString(value));
    }

    /** A {@code deepEquals} match against the literal JSON value {@code value}. */
    public static Matcher deepEquals(JsonValue value) {
        return Matcher.create(Matcher.Kind.DEEP_EQUALS, value);
    }

    /**
     * A {@code deepEquals} match against {@code pojo}, serialized to JSON by the registered {@link
     * RiftBodyCodec} (see {@link #useBodyCodec}).
     */
    public static Matcher deepEquals(Object pojo) {
        return deepEquals(BodyCodecs.resolve().toJson(pojo));
    }

    /** A {@code contains} (substring / partial-object) match against the literal string {@code value}. */
    public static Matcher contains(String value) {
        return Matcher.create(Matcher.Kind.CONTAINS, new JsonString(value));
    }

    /** A {@code contains} (partial-object) match against the literal JSON value {@code value}. */
    public static Matcher contains(JsonValue value) {
        return Matcher.create(Matcher.Kind.CONTAINS, value);
    }

    /** A {@code startsWith} match against the literal string prefix {@code value}. */
    public static Matcher startsWith(String value) {
        return Matcher.create(Matcher.Kind.STARTS_WITH, new JsonString(value));
    }

    /** An {@code endsWith} match against the literal string suffix {@code value}. */
    public static Matcher endsWith(String value) {
        return Matcher.create(Matcher.Kind.ENDS_WITH, new JsonString(value));
    }

    /** A {@code matches} (regular expression) match against {@code regex}. */
    public static Matcher matches(String regex) {
        return Matcher.create(Matcher.Kind.MATCHES, new JsonString(regex));
    }

    /** An {@code exists} match requiring the bound field to be present (and, if a string, non-empty). */
    public static Matcher exists() {
        return Matcher.create(Matcher.Kind.EXISTS, JsonBool.TRUE);
    }

    /** An {@code exists} match requiring the bound field to be absent (or, if a string, empty). */
    public static Matcher notExists() {
        return Matcher.create(Matcher.Kind.EXISTS, JsonBool.FALSE);
    }

    // ------------------------------------------------------------------
    // Field binders — bind a Matcher onto a specific predicate field
    // ------------------------------------------------------------------

    /** Binds {@code matcher} to the request method, as a bare-string {@code method} field. */
    public static PredicateSpec method(Matcher matcher) {
        return PredicateSpec.of(matcher.toOperation(Map.of("method", matcher.value())));
    }

    /** Binds {@code matcher} to the request path, as a bare-string {@code path} field. */
    public static PredicateSpec path(Matcher matcher) {
        return PredicateSpec.of(matcher.toOperation(Map.of("path", matcher.value())));
    }

    /** Binds {@code matcher} to the named request header, nested under the {@code headers} field. */
    public static PredicateSpec header(String name, Matcher matcher) {
        Map<String, JsonValue> fields = Map.of("headers", new JsonObject(Map.of(name, matcher.value())));
        return PredicateSpec.of(matcher.toOperation(fields));
    }

    /** Binds an {@code equals} match against {@code value} to the named request header. */
    public static PredicateSpec header(String name, String value) {
        return header(name, equals(value));
    }

    /** Binds {@code matcher} to the named query-string parameter, nested under the {@code query} field. */
    public static PredicateSpec query(String name, Matcher matcher) {
        Map<String, JsonValue> fields = Map.of("query", new JsonObject(Map.of(name, matcher.value())));
        return PredicateSpec.of(matcher.toOperation(fields));
    }

    /** Binds an {@code equals} match against {@code value} to the named query-string parameter. */
    public static PredicateSpec query(String name, String value) {
        return query(name, equals(value));
    }

    /** Binds {@code matcher} to the request body, as the {@code body} field (verbatim). */
    public static PredicateSpec body(Matcher matcher) {
        return PredicateSpec.of(matcher.toOperation(Map.of("body", matcher.value())));
    }

    // ------------------------------------------------------------------
    // Combinators over whole predicates
    // ------------------------------------------------------------------

    /** The logical AND of the given predicates: all must match. */
    public static PredicateSpec and(PredicateSpec... predicates) {
        return PredicateSpec.of(new PredicateOperation.And(built(predicates)));
    }

    /** The logical OR of the given predicates: at least one must match. */
    public static PredicateSpec or(PredicateSpec... predicates) {
        return PredicateSpec.of(new PredicateOperation.Or(built(predicates)));
    }

    /** The logical negation of the given predicate. */
    public static PredicateSpec not(PredicateSpec predicate) {
        return PredicateSpec.of(new PredicateOperation.Not(predicate.build()));
    }

    private static List<Predicate> built(PredicateSpec... predicates) {
        return Arrays.stream(predicates).map(PredicateSpec::build).toList();
    }

    // ------------------------------------------------------------------
    // Responses
    // ------------------------------------------------------------------

    /** A bare 200 response, with no headers or body. */
    public static IsSpec ok() {
        return IsSpec.is("200");
    }

    /** A 200 response with a {@code Content-Type: application/json} header and the given JSON body. */
    public static IsSpec okJson(JsonValue body) {
        return IsSpec.is("200").withHeader("Content-Type", "application/json").withJsonBody(body);
    }

    /**
     * A 200 response with a {@code Content-Type: application/json} header and the body <em>parsed</em>
     * from {@code jsonText} — the payload is reparsed and re-serialized, so its exact byte form
     * (whitespace, key order) is not preserved. Use {@link #okJsonRaw(String)} to serve the text verbatim.
     */
    public static IsSpec okJson(String jsonText) {
        return okJson(json(jsonText));
    }

    /**
     * A 200 response with a {@code Content-Type: application/json} header and {@code jsonText} served
     * <em>verbatim</em> — byte-for-byte, no reparse or re-serialization. Use this for a payload whose
     * exact form matters (a signed body, a datafile a client fingerprints), where {@link #okJson(String)}'s
     * canonicalization would be a surprise.
     */
    public static IsSpec okJsonRaw(String jsonText) {
        return IsSpec.is("200").withHeader("Content-Type", "application/json").withTextBody(jsonText);
    }

    /**
     * A 200 response with a {@code Content-Type: application/json} header and the body serialized
     * from {@code pojo} by the registered {@link RiftBodyCodec} (see {@link #useBodyCodec}).
     */
    public static IsSpec okJson(Object pojo) {
        return okJson(BodyCodecs.resolve().toJson(pojo));
    }

    /** A bare 201 response, with no headers or body. */
    public static IsSpec created() {
        return IsSpec.is("201");
    }

    /** A bare 204 response, with no headers or body. */
    public static IsSpec noContent() {
        return IsSpec.is("204");
    }

    /** A bare 404 response, with no headers or body. */
    public static IsSpec notFound() {
        return IsSpec.is("404");
    }

    /** A bare response at the given status code, with no headers or body. */
    public static IsSpec status(int code) {
        return IsSpec.is(Integer.toString(code));
    }

    /**
     * Starts building a proxy response forwarding matched requests to {@code url}. Without a mode
     * chooser ({@code proxyOnce()}/{@code proxyAlways()}/{@code proxyTransparent()}) the engine's
     * default applies, which is {@code proxyOnce} from rift 0.21.0: see {@link ProxySpec#proxyTransparent()}
     * to keep forwarding every request.
     */
    public static ProxySpec proxyTo(String url) {
        return ProxySpec.to(url);
    }

    /** A raw connection-fault response, e.g. {@link Fault#CONNECTION_RESET_BY_PEER}. */
    public static FaultSpec fault(Fault fault) {
        return FaultSpec.of(fault);
    }

    /** A response entirely computed by the given inline JavaScript function (Mountebank's {@code inject} response). */
    public static InjectSpec inject(String javascript) {
        return InjectSpec.of(javascript);
    }

    /** A response entirely generated by a {@code _rift} script, with no {@code is} block. */
    public static ScriptSpec script(Script script) {
        return ScriptSpec.of(script);
    }

    // ------------------------------------------------------------------
    // Scenario FSM
    // ------------------------------------------------------------------

    /** Starts building a scenario finite-state machine named {@code name}. */
    public static ScenarioSpec scenario(String name) {
        return new ScenarioSpec(name);
    }

    // ------------------------------------------------------------------
    // Copy / lookup behaviors and their extraction methods
    // ------------------------------------------------------------------

    /** Starts building a {@code copy} behavior entry extracting a value from {@code from}. */
    public static CopySpec copyFrom(String from) {
        return CopySpec.from(from);
    }

    /** Starts a {@code copy} entry extracting from a query parameter (the object {@code from} form). */
    public static CopySpec copyFromQuery(String name) {
        return CopySpec.fromQuery(name);
    }

    /** Starts a {@code copy} entry extracting from a request header (the object {@code from} form). */
    public static CopySpec copyFromHeader(String name) {
        return CopySpec.fromHeader(name);
    }

    /** Starts building a {@code lookup} behavior entry keyed by a value extracted from {@code from}. */
    public static LookupSpec lookupKey(String from) {
        return LookupSpec.key(from);
    }

    /** A regular-expression extraction over {@code selector} (the first capture group, or the whole match). */
    public static ExtractionSpec regex(String selector) {
        return ExtractionSpec.of("regex", selector, Optional.empty());
    }

    /** A regular-expression extraction with explicit case-sensitivity/multiline flags. */
    public static ExtractionSpec regex(String selector, boolean ignoreCase, boolean multiline) {
        JsonObject options = JsonObject.builder()
                .put("ignoreCase", JsonBool.of(ignoreCase))
                .put("multiline", JsonBool.of(multiline))
                .build();
        return ExtractionSpec.of("regex", selector, Optional.of(options));
    }

    /** A JSONPath extraction over the request body. */
    public static ExtractionSpec jsonPath(String selector) {
        return ExtractionSpec.of("jsonpath", selector, Optional.empty());
    }

    /** An XPath extraction over the request body, with no namespace bindings. */
    public static ExtractionSpec xPath(String selector) {
        return ExtractionSpec.of("xpath", selector, Optional.empty());
    }

    /** An XPath extraction over the request body, with the given prefix-to-URI namespace bindings. */
    public static ExtractionSpec xPath(String selector, Map<String, String> namespaces) {
        JsonObject.Builder builder = JsonObject.builder();
        namespaces.forEach((prefix, uri) -> builder.put(prefix, new JsonString(uri)));
        return ExtractionSpec.of("xpath", selector, Optional.of(builder.build()));
    }

    // ------------------------------------------------------------------
    // Flow state
    // ------------------------------------------------------------------

    /** Starts building an in-memory flow-state configuration. */
    public static FlowStateSpec inMemoryFlowState() {
        return FlowStateSpec.inMemory();
    }

    /** Starts building a Redis-backed flow-state configuration against {@code url}. */
    public static FlowStateSpec redisFlowState(String url) {
        return FlowStateSpec.redis(url);
    }

    // ------------------------------------------------------------------
    // Verification times
    // ------------------------------------------------------------------

    /** Expect exactly {@code n} matching requests. */
    public static VerificationTimes times(int n) {
        return VerificationTimes.times(n);
    }

    /** Alias for {@link #times(int)}. */
    public static VerificationTimes exactly(int n) {
        return VerificationTimes.exactly(n);
    }

    /** Expect at least {@code n} matching requests. */
    public static VerificationTimes atLeast(int n) {
        return VerificationTimes.atLeast(n);
    }

    /** Expect at most {@code n} matching requests. */
    public static VerificationTimes atMost(int n) {
        return VerificationTimes.atMost(n);
    }

    /** Expect between {@code min} and {@code max} matching requests, inclusive. */
    public static VerificationTimes between(int min, int max) {
        return VerificationTimes.between(min, max);
    }

    /** Expect zero matching requests. */
    public static VerificationTimes never() {
        return VerificationTimes.never();
    }

    // ------------------------------------------------------------------
    // Body codec (SPI)
    // ------------------------------------------------------------------

    /**
     * Registers {@code codec} as the explicit {@link RiftBodyCodec} used by the {@code Object}-typed
     * DSL overloads ({@link #okJson(Object)}, {@link #equalTo(Object)}, {@link #deepEquals(Object)},
     * {@code IsSpec.withBodyFromCodec}, {@code RecordedRequest.bodyAs}), overriding {@code
     * ServiceLoader} discovery. Passing {@code null} resets to auto-discovery.
     */
    public static void useBodyCodec(RiftBodyCodec codec) {
        BodyCodecs.explicit = codec;
    }

    // ------------------------------------------------------------------
    // Misc
    // ------------------------------------------------------------------

    /** Parses {@code text} as JSON, for embedding literal request/response body content in DSL calls. */
    public static JsonValue json(String text) {
        return JsonValue.parse(text);
    }
}
