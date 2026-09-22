package io.github.achirdlabs.rift.model;

import io.github.achirdlabs.rift.json.JsonArray;
import io.github.achirdlabs.rift.json.JsonNumber;
import io.github.achirdlabs.rift.json.JsonObject;
import io.github.achirdlabs.rift.json.JsonString;
import io.github.achirdlabs.rift.json.JsonValue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Issue #217: the {@code _behaviors} object form cannot represent two entries that share a key, so
 * writing one there collapses them to the last. These pin the rule that replaces it — write the
 * {@code behaviors} array form whenever, and only whenever, the object form would lose an entry.
 *
 * <p>Mountebank spells two {@code copy} behaviors as two array elements, so this is reachable from
 * a Mountebank-native stub as well as from calling a DSL chainer twice.
 */
class BehaviorsWriteFormTest {

    private static final String TWO_COPIES = """
            {
              "predicates": [{"equals": {"path": "/c"}}],
              "responses": [
                {
                  "is": {"statusCode": 200},
                  "behaviors": [
                    {"copy": {"from": "path", "into": "${first}", "using": {"method": "regex", "selector": "/(.*)"}}},
                    {"copy": {"from": "query", "into": "${second}", "using": {"method": "regex", "selector": "(.*)"}}}
                  ]
                }
              ]
            }
            """;

    private static final String REPEATED_SCALARS = """
            {
              "predicates": [{"equals": {"path": "/s"}}],
              "responses": [
                {
                  "is": {"statusCode": 200},
                  "behaviors": [
                    {"wait": 100},
                    {"decorate": "function(q, r) { r.body += 'a'; }"},
                    {"wait": 250},
                    {"decorate": "function(q, r) { r.body += 'b'; }"}
                  ]
                }
              ]
            }
            """;

    private static final String TWO_LOOKUPS = """
            {
              "predicates": [{"equals": {"path": "/l"}}],
              "responses": [
                {
                  "is": {"statusCode": 200},
                  "behaviors": [{"lookup": {"into": "${a}"}}, {"lookup": {"into": "${b}"}}]
                }
              ]
            }
            """;

    private static final String TWO_REPEATS = """
            {
              "predicates": [{"equals": {"path": "/r"}}],
              "responses": [
                {"is": {"statusCode": 200}, "behaviors": [{"repeat": 2}, {"repeat": 5}]}
              ]
            }
            """;

    private static final String MIXED_COPY_FORMS = """
            {
              "predicates": [{"equals": {"path": "/m"}}],
              "responses": [
                {
                  "is": {"statusCode": 200},
                  "behaviors": [
                    {"copy": {"from": "path", "into": "${obj}", "using": {"method": "regex", "selector": "/(.*)"}}},
                    {"copy": [{"from": "query", "into": "${arr}", "using": {"method": "regex", "selector": "(.*)"}}]}
                  ]
                }
              ]
            }
            """;

    private static final String TWO_SHELL_TRANSFORMS = """
            {
              "predicates": [{"equals": {"path": "/st"}}],
              "responses": [
                {
                  "is": {"statusCode": 200},
                  "behaviors": [{"shellTransform": "./first.sh"}, {"shellTransform": "./second.sh"}]
                }
              ]
            }
            """;

    /** The engine's {@code GET /imposters} shape after rift#1191: `repeat` beside `is`, not in the array. */
    private static final String RESPONSE_LEVEL_REPEAT_WITH_ARRAY = """
            {
              "predicates": [{"equals": {"path": "/e"}}],
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

    private static final String ARRAY_FORM_NO_REPEAT = """
            {
              "predicates": [{"equals": {"path": "/a"}}],
              "responses": [
                {
                  "is": {"statusCode": 200},
                  "behaviors": [{"wait": 100}, {"decorate": "function(q, r) {}"}]
                }
              ]
            }
            """;

    private static final String NO_REPEATED_KEY = """
            {
              "predicates": [{"equals": {"path": "/o"}}],
              "responses": [
                {
                  "is": {"statusCode": 200},
                  "_behaviors": {"wait": 100, "decorate": "function(q, r) {}"}
                }
              ]
            }
            """;

    @Test
    void repeatedCopyEntriesSurviveTheWrite() {
        JsonArray behaviors = writtenBehaviorsArray(TWO_COPIES);
        assertEquals(2, behaviors.items().size(), "both copy entries must be written");
        assertEquals("${first}", copyInto(behaviors.items().get(0)));
        assertEquals("${second}", copyInto(behaviors.items().get(1)));
    }

    @Test
    void repeatedScalarKeysSurviveTheWrite() {
        JsonArray behaviors = writtenBehaviorsArray(REPEATED_SCALARS);
        assertEquals(4, behaviors.items().size(), "repeated scalars must not collapse onto the last");
        assertEquals(JsonNumber.of(100), soleValue(behaviors.items().get(0), "wait"));
        assertEquals(new JsonString("function(q, r) { r.body += 'a'; }"),
                soleValue(behaviors.items().get(1), "decorate"));
        assertEquals(JsonNumber.of(250), soleValue(behaviors.items().get(2), "wait"));
        assertEquals(new JsonString("function(q, r) { r.body += 'b'; }"),
                soleValue(behaviors.items().get(3), "decorate"));
    }

    @Test
    void repeatedUnknownKeyEntriesSurviveTheWrite() {
        JsonArray behaviors = writtenBehaviorsArray(TWO_LOOKUPS);
        assertEquals(2, behaviors.items().size(), "lookup rides Behavior.Unknown and must repeat too");
        assertEquals("${a}", intoOf(soleValue(behaviors.items().get(0), "lookup")));
        assertEquals("${b}", intoOf(soleValue(behaviors.items().get(1), "lookup")));
    }

    @Test
    void repeatedRepeatEntriesAreWrittenAsElements() {
        JsonArray behaviors = writtenBehaviorsArray(TWO_REPEATS);
        assertEquals(2, behaviors.items().size());
        assertEquals(JsonNumber.of(2), soleValue(behaviors.items().get(0), "repeat"));
        assertEquals(JsonNumber.of(5), soleValue(behaviors.items().get(1), "repeat"));
    }

    /**
     * A {@code repeat} is written as an array element, never hoisted to a response-level field: the
     * pinned engine (0.17.0, the latest published release) has no response-level {@code repeat}
     * field at all, so hoisting would silently drop it on the engine this SDK targets.
     *
     * <p>A forward-looking guard on that decision, not coverage of the #217 fix: it passes against
     * the pre-fix code too, since nothing hoisted then either.
     */
    @Test
    void repeatIsNotHoistedToAResponseLevelField() {
        JsonObject response = writtenResponse(TWO_REPEATS);
        assertNull(response.get("repeat"), "repeat must stay inside the behaviors array");
    }

    @Test
    void perElementCopyFormIsPreserved() {
        JsonArray behaviors = writtenBehaviorsArray(MIXED_COPY_FORMS);
        assertEquals(2, behaviors.items().size());
        assertInstanceOf(JsonObject.class, soleValue(behaviors.items().get(0), "copy"),
                "an object-form copy element stays an object");
        assertInstanceOf(JsonArray.class, soleValue(behaviors.items().get(1), "copy"),
                "an array-form copy element stays an array");
    }

    @Test
    void nonRepeatingBehaviorsStillWriteTheObjectForm() {
        JsonObject response = writtenResponse(NO_REPEATED_KEY);
        assertNull(response.get("behaviors"), "no key repeats, so the object form loses nothing");
        JsonObject block = assertInstanceOf(JsonObject.class, response.get("_behaviors"));
        assertEquals(JsonNumber.of(100), block.get("wait"));
        assertEquals(new JsonString("function(q, r) {}"), block.get("decorate"));
    }

    @Test
    void repeatedShellTransformEntriesSurviveTheWrite() {
        JsonArray behaviors = writtenBehaviorsArray(TWO_SHELL_TRANSFORMS);
        assertEquals(2, behaviors.items().size());
        assertEquals(new JsonString("./first.sh"), soleValue(behaviors.items().get(0), "shellTransform"));
        assertEquals(new JsonString("./second.sh"), soleValue(behaviors.items().get(1), "shellTransform"));
    }

    /**
     * The array form is chosen only to avoid losing an entry, never merely because the input used
     * it. Without this, an implementation that echoed the input's shape would pass every other test
     * here while churning the output of every engine {@code GET /imposters} response, which is
     * always array-form.
     */
    @Test
    void arrayFormWithNoRepeatedKeysNormalisesToObjectForm() {
        JsonObject response = writtenResponse(ARRAY_FORM_NO_REPEAT);
        assertNull(response.get("behaviors"), "no key repeats, so the object form loses nothing");
        JsonObject block = assertInstanceOf(JsonObject.class, response.get("_behaviors"));
        assertEquals(JsonNumber.of(100), block.get("wait"));
        assertEquals(new JsonString("function(q, r) {}"), block.get("decorate"));
    }

    /**
     * The engine's post-rift#1191 {@code GET} shape: a response-level {@code repeat} sibling
     * alongside an array that repeats a key. The {@code repeat} is not modelled yet (issue #216),
     * so it rides {@code Is.extra} — it must still be re-emitted beside the array, unchanged.
     */
    @Test
    void responseLevelRepeatSurvivesAlongsideArrayFormBehaviors() {
        JsonObject response = writtenResponse(RESPONSE_LEVEL_REPEAT_WITH_ARRAY);
        assertEquals(JsonNumber.of(3), response.get("repeat"), "the response-level repeat must survive");
        JsonArray behaviors = assertInstanceOf(JsonArray.class, response.get("behaviors"));
        assertEquals(2, behaviors.items().size());
        assertEquals("${one}", copyInto(behaviors.items().get(0)));
        assertEquals("${two}", copyInto(behaviors.items().get(1)));
        RoundTripAssertions.assertRoundTrips(RESPONSE_LEVEL_REPEAT_WITH_ARRAY, Stub::fromJson, Stub::toJson);
    }

    @Test
    void arrayFormWithRepeatedKeysRoundTrips() {
        RoundTripAssertions.assertRoundTrips(TWO_COPIES, Stub::fromJson, Stub::toJson);
        RoundTripAssertions.assertRoundTrips(REPEATED_SCALARS, Stub::fromJson, Stub::toJson);
        RoundTripAssertions.assertRoundTrips(TWO_LOOKUPS, Stub::fromJson, Stub::toJson);
        RoundTripAssertions.assertRoundTrips(MIXED_COPY_FORMS, Stub::fromJson, Stub::toJson);
        RoundTripAssertions.assertRoundTrips(TWO_REPEATS, Stub::fromJson, Stub::toJson);
        RoundTripAssertions.assertRoundTrips(TWO_SHELL_TRANSFORMS, Stub::fromJson, Stub::toJson);
    }

    @Test
    void emptyBehaviorsWriteNeitherKey() {
        JsonObject response = writtenResponse("""
                {"predicates": [], "responses": [{"is": {"statusCode": 204}}]}
                """);
        assertNull(response.get("_behaviors"));
        assertNull(response.get("behaviors"));
    }

    private static JsonObject writtenResponse(String stubJson) {
        JsonObject stub = assertInstanceOf(JsonObject.class, JsonValue.parse(Stub.fromJson(stubJson).toJson()));
        JsonArray responses = assertInstanceOf(JsonArray.class, stub.get("responses"));
        return assertInstanceOf(JsonObject.class, responses.items().get(0));
    }

    private static JsonArray writtenBehaviorsArray(String stubJson) {
        JsonObject response = writtenResponse(stubJson);
        assertNull(response.get("_behaviors"), "a repeated key cannot be written in the object form");
        return assertInstanceOf(JsonArray.class, response.get("behaviors"));
    }

    /** The value of a single-key array element, asserting the element really has just that key. */
    private static JsonValue soleValue(JsonValue element, String key) {
        JsonObject obj = assertInstanceOf(JsonObject.class, element);
        assertEquals(1, obj.fields().size(), "each behaviors element carries exactly one key");
        JsonValue value = obj.get(key);
        assertNotNull(value, () -> "element is not keyed " + key + ": " + obj.toJson());
        return value;
    }

    private static String copyInto(JsonValue element) {
        return intoOf(soleValue(element, "copy"));
    }

    private static String intoOf(JsonValue value) {
        JsonObject obj = assertInstanceOf(JsonObject.class, value);
        return assertInstanceOf(JsonString.class, obj.get("into")).value();
    }
}
