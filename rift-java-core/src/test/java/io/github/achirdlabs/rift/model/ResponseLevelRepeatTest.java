package io.github.achirdlabs.rift.model;

import io.github.achirdlabs.rift.json.JsonArray;
import io.github.achirdlabs.rift.json.JsonNumber;
import io.github.achirdlabs.rift.json.JsonObject;
import io.github.achirdlabs.rift.json.JsonValue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #216: {@code repeat} beside {@code is} — Mountebank's canonical spelling, and what
 * {@code mb save} writes — was read as an opaque {@code extra} entry, so it round-tripped but was
 * invisible to anything reading the typed behaviors. It is now a {@link Behavior.Repeat} like the
 * {@code _behaviors.repeat} spelling, and is written back in whichever spelling it arrived in.
 */
class ResponseLevelRepeatTest {

    private static final String RESPONSE_LEVEL = """
            {
              "predicates": [{"equals": {"path": "/r"}}],
              "responses": [{"is": {"statusCode": 200}, "repeat": 3}]
            }
            """;

    private static final String BLOCK_LEVEL = """
            {
              "predicates": [{"equals": {"path": "/b"}}],
              "responses": [{"is": {"statusCode": 200}, "_behaviors": {"repeat": 2}}]
            }
            """;

    private static final String BOTH = """
            {
              "predicates": [{"equals": {"path": "/x"}}],
              "responses": [{"is": {"statusCode": 200}, "repeat": 4, "_behaviors": {"repeat": 2, "wait": 1}}]
            }
            """;

    private static final String FLAT_FORM = """
            {
              "predicates": [{"equals": {"path": "/f"}}],
              "responses": [{"statusCode": 200, "body": "hi", "repeat": 2}]
            }
            """;

    private static final String WITH_ARRAY_BEHAVIORS = """
            {
              "predicates": [{"equals": {"path": "/a"}}],
              "responses": [
                {
                  "is": {"statusCode": 200},
                  "repeat": 3,
                  "behaviors": [
                    {"copy": {"from": "path", "into": "${one}", "using": {"method": "regex", "selector": "/(.*)"}}},
                    {"copy": {"from": "query", "into": "${two}", "using": {"method": "regex", "selector": "(.*)"}}}
                  ]
                }
              ]
            }
            """;

    private static final String PROXY_WITH_REPEAT = """
            {
              "predicates": [{"equals": {"path": "/p"}}],
              "responses": [{"proxy": {"to": "http://upstream.test"}, "repeat": 2}]
            }
            """;

    @Test
    void responseLevelRepeatIsTyped() {
        Response.Is is = isOf(RESPONSE_LEVEL);
        Behavior.Repeat repeat = assertInstanceOf(Behavior.Repeat.class, only(is.behaviors().entries()));
        assertEquals(3, repeat.count());
        assertTrue(repeat.responseLevel(), "it arrived beside `is`, not inside the block");
        assertFalse(is.extra().containsKey("repeat"), "a modeled key must not also sit in extra");
    }

    @Test
    void responseLevelRepeatIsWrittenBackAtResponseLevel() {
        JsonObject written = writtenResponse(RESPONSE_LEVEL);
        assertEquals(JsonNumber.of(3), written.get("repeat"));
        assertNull(written.get("_behaviors"), "a lone response-level repeat writes no block");
        assertNull(written.get("behaviors"));
        RoundTripAssertions.assertRoundTrips(RESPONSE_LEVEL, Stub::fromJson, Stub::toJson);
    }

    @Test
    void blockRepeatStillWritesInsideTheBlock() {
        Response.Is is = isOf(BLOCK_LEVEL);
        Behavior.Repeat repeat = assertInstanceOf(Behavior.Repeat.class, only(is.behaviors().entries()));
        assertEquals(2, repeat.count());
        assertFalse(repeat.responseLevel(), "the block spelling must stay the block spelling");

        JsonObject written = writtenResponse(BLOCK_LEVEL);
        assertNull(written.get("repeat"), "a block repeat must not be hoisted");
        JsonObject block = assertInstanceOf(JsonObject.class, written.get("_behaviors"));
        assertEquals(JsonNumber.of(2), block.get("repeat"));
        RoundTripAssertions.assertRoundTrips(BLOCK_LEVEL, Stub::fromJson, Stub::toJson);
    }

    /**
     * The response-level value wins where the engine honours it — but the block one is NOT
     * discarded. Which spelling applies is engine-dependent (rift 0.17.0 and earlier ignore the
     * response-level field and honour the block one), so dropping either at parse time would
     * change how the imposter behaves after nothing but a read and a write.
     */
    @Test
    void responseLevelRepeatWinsButTheBlockOneIsPreserved() {
        Response.Is is = isOf(BOTH);
        List<Behavior> repeats = is.behaviors().entries().stream()
                .filter(Behavior.Repeat.class::isInstance)
                .toList();
        assertEquals(2, repeats.size(), "both spellings are kept");
        assertEquals(4, is.behaviors().effectiveRepeat().orElseThrow().count(),
                "the response-level value is the effective one");

        JsonObject written = writtenResponse(BOTH);
        assertEquals(JsonNumber.of(4), written.get("repeat"));
        JsonObject block = assertInstanceOf(JsonObject.class, written.get("_behaviors"));
        assertEquals(JsonNumber.of(2), block.get("repeat"), "the block repeat survives the write");
        assertEquals(JsonNumber.of(1), block.get("wait"), "the rest of the block is untouched");
        RoundTripAssertions.assertRoundTrips(BOTH, Stub::fromJson, Stub::toJson);
    }

    /** The same, where the block independently needs the array form — the block repeat still survives. */
    @Test
    void responseLevelRepeatWinsWithAnArrayFormBlock() {
        String json = """
                {
                  "predicates": [],
                  "responses": [
                    {
                      "is": {"statusCode": 200},
                      "repeat": 9,
                      "behaviors": [{"repeat": 2}, {"wait": 1}, {"wait": 7}]
                    }
                  ]
                }
                """;
        assertEquals(9, isOf(json).behaviors().effectiveRepeat().orElseThrow().count());
        JsonObject written = writtenResponse(json);
        assertEquals(JsonNumber.of(9), written.get("repeat"));
        JsonArray behaviors = assertInstanceOf(JsonArray.class, written.get("behaviors"));
        assertEquals(3, behaviors.items().size(), "repeated wait keeps the array form, repeat included");
        RoundTripAssertions.assertRoundTrips(json, Stub::fromJson, Stub::toJson);
    }

    /** A block-only repeat spelled via the array form stays a block entry. */
    @Test
    void blockRepeatViaTheArrayFormIsNotResponseLevel() {
        Response.Is is = isOf("""
                {"predicates": [], "responses": [
                  {"is": {"statusCode": 200}, "behaviors": [{"repeat": 2}, {"wait": 1}]}]}
                """);
        Behavior.Repeat repeat = (Behavior.Repeat) is.behaviors().entries().stream()
                .filter(Behavior.Repeat.class::isInstance).findFirst().orElseThrow();
        assertFalse(repeat.responseLevel());
        assertNull(writtenResponse("""
                {"predicates": [], "responses": [
                  {"is": {"statusCode": 200}, "behaviors": [{"repeat": 2}, {"wait": 1}]}]}
                """).get("repeat"), "must not be hoisted out of the block");
    }

    @Test
    void rejectsTwoResponseLevelRepeats() {
        assertThrows(IllegalArgumentException.class, () -> new Behaviors(
                List.of(new Behavior.Repeat(2, true), new Behavior.Repeat(3, true))));
    }

    /** The flat/recorded form has no `is` wrapper, so `repeat` would otherwise land in IsResponse.extra. */
    @Test
    void flatFormResponseLevelRepeatIsTyped() {
        Response.Is is = isOf(FLAT_FORM);
        Behavior.Repeat repeat = assertInstanceOf(Behavior.Repeat.class, only(is.behaviors().entries()));
        assertEquals(2, repeat.count());
        assertTrue(repeat.responseLevel());
        assertFalse(is.is().extra().containsKey("repeat"), "must not hide in the is-content extras");
        assertFalse(is.extra().containsKey("repeat"));

        // G2 only: the flat form normalises into the wrapped `is:{}` shape, so the text changes.
        JsonObject written = writtenResponse(FLAT_FORM);
        assertEquals(JsonNumber.of(2), written.get("repeat"), "written back at response level");
        assertEquals(2, ((Behavior.Repeat) only(isOf(Stub.fromJson(FLAT_FORM).toJson())
                .behaviors().entries())).count(), "and survives a reparse");
    }

    @Test
    void responseLevelRepeatCoexistsWithArrayFormBehaviors() {
        JsonObject written = writtenResponse(WITH_ARRAY_BEHAVIORS);
        assertEquals(JsonNumber.of(3), written.get("repeat"));
        JsonArray behaviors = assertInstanceOf(JsonArray.class, written.get("behaviors"));
        assertEquals(2, behaviors.items().size(), "the repeated copy keys still force the array form");
        assertNull(written.get("_behaviors"));
        RoundTripAssertions.assertRoundTrips(WITH_ARRAY_BEHAVIORS, Stub::fromJson, Stub::toJson);
    }

    /** A lone response-level repeat must not drag the block into the array form. */
    @Test
    void aLoneResponseLevelRepeatDoesNotForceTheArrayForm() {
        JsonObject written = writtenResponse("""
                {
                  "predicates": [],
                  "responses": [{"is": {"statusCode": 200}, "repeat": 2, "_behaviors": {"wait": 5}}]
                }
                """);
        assertEquals(JsonNumber.of(2), written.get("repeat"));
        JsonObject block = assertInstanceOf(JsonObject.class, written.get("_behaviors"));
        assertEquals(JsonNumber.of(5), block.get("wait"));
        assertNull(written.get("behaviors"), "one wait entry never needs the array form");
    }

    @Test
    void zeroRepeatRoundTripsVerbatim() {
        String json = """
                {"predicates": [], "responses": [{"is": {"statusCode": 200}, "repeat": 0}]}
                """;
        assertEquals(0, ((Behavior.Repeat) only(isOf(json).behaviors().entries())).count());
        assertEquals(JsonNumber.of(0), writtenResponse(json).get("repeat"), "no clamping in the SDK");
    }

    @Test
    void nonNumericRepeatIsRejected() {
        assertThrows(WireFormatException.class, () -> Stub.fromJson("""
                {"predicates": [], "responses": [{"is": {"statusCode": 200}, "repeat": "3"}]}
                """));
    }

    /**
     * Since #215 a {@code repeat} beside {@code proxy}/{@code inject} is typed like the one beside
     * {@code is}, and written back beside the response rather than moved into the block.
     *
     * <p>No full round-trip assertion here: {@code ProxyResponse.toJsonValue} always writes
     * {@code mode} and {@code predicateGenerators}, so a bare {@code proxy} has never round-tripped
     * byte-faithfully. That is unrelated to {@code repeat}, so this checks the key itself.
     */
    @Test
    void proxyAndInjectTypeResponseLevelRepeat() {
        Response.Proxy proxy = assertInstanceOf(Response.Proxy.class, Stub.fromJson(PROXY_WITH_REPEAT).responses().get(0));
        assertEquals(new Behavior.Repeat(2, true), only(proxy.behaviors().entries()));
        assertFalse(proxy.extra().containsKey("repeat"), "typed, so not also carried in extra");
        assertEquals(JsonNumber.of(2), writtenResponse(PROXY_WITH_REPEAT).get("repeat"),
                "and written back beside proxy");

        Response.Inject inject = assertInstanceOf(Response.Inject.class, Stub.fromJson("""
                {"predicates": [], "responses": [{"inject": "function(){}", "repeat": 2}]}
                """).responses().get(0));
        assertEquals(new Behavior.Repeat(2, true), only(inject.behaviors().entries()));
    }

    @Test
    void faultStillKeepsRepeatInExtra() {
        Response.Fault fault = assertInstanceOf(Response.Fault.class, Stub.fromJson("""
                {"predicates": [], "responses": [{"fault": "CONNECTION_RESET_BY_PEER", "repeat": 2}]}
                """).responses().get(0));
        assertEquals(JsonNumber.of(2), fault.extra().get("repeat"));
    }

    private static Response.Is isOf(String stubJson) {
        return assertInstanceOf(Response.Is.class, Stub.fromJson(stubJson).responses().get(0));
    }

    private static Behavior only(List<Behavior> entries) {
        assertEquals(1, entries.size(), () -> "expected exactly one behavior, got " + entries);
        return entries.get(0);
    }

    private static JsonObject writtenResponse(String stubJson) {
        JsonObject stub = assertInstanceOf(JsonObject.class, JsonValue.parse(Stub.fromJson(stubJson).toJson()));
        JsonArray responses = assertInstanceOf(JsonArray.class, stub.get("responses"));
        return assertInstanceOf(JsonObject.class, responses.items().get(0));
    }
}
