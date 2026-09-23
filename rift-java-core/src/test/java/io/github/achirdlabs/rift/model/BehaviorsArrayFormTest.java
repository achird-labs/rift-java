package io.github.achirdlabs.rift.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The engine's array-of-single-key-objects {@code "behaviors": [...]} shape (as emitted by
 * {@code GET /imposters}) must parse into the same {@link Behaviors} as the {@code _behaviors}
 * object form every fixture uses — confirming it is not silently dropped.
 *
 * <p>Not a round-trip (G2/G3) test: with no repeated key and the transforming behaviors in
 * canonical order — as here — {@link Behaviors} serializes back using the object form, so an
 * array-form input's key name necessarily changes on write. That
 * is documented, intended engine-compat behavior; it is pinned on the write side by {@code
 * BehaviorsWriteFormTest.arrayFormWithNoRepeatedKeysNormalisesToObjectForm}, not here. A key that
 * repeats keeps the array form instead, since the object form cannot represent it.
 */
class BehaviorsArrayFormTest {

    private static final String ARRAY_JSON = """
            {
              "predicates": [{"equals": {"path": "/arr"}}],
              "responses": [
                {
                  "is": {"statusCode": 200},
                  "behaviors": [{"wait": 100}, {"decorate": "function(request, response) {}"}]
                }
              ]
            }
            """;

    private static final String OBJECT_JSON = """
            {
              "predicates": [{"equals": {"path": "/arr"}}],
              "responses": [
                {
                  "is": {"statusCode": 200},
                  "_behaviors": {"wait": 100, "decorate": "function(request, response) {}"}
                }
              ]
            }
            """;

    @Test
    void arrayFormParsesToSameBehaviorsAsObjectForm() {
        Behaviors arrayBehaviors = behaviorsOf(ARRAY_JSON);
        Behaviors objectBehaviors = behaviorsOf(OBJECT_JSON);
        assertEquals(objectBehaviors, arrayBehaviors, "array form must not be silently dropped or reordered/changed");
    }

    @Test
    void arrayFormEntriesAreTyped() {
        Behaviors behaviors = behaviorsOf(ARRAY_JSON);
        assertEquals(2, behaviors.entries().size());

        Behavior.Wait wait = (Behavior.Wait) behaviors.entries().get(0);
        assertEquals(new WaitSpec.Fixed(100), wait.spec());

        Behavior.Decorate decorate = (Behavior.Decorate) behaviors.entries().get(1);
        assertTrue(decorate.script().contains("function"));
    }

    private static Behaviors behaviorsOf(String json) {
        Stub stub = Stub.fromJson(json);
        Response.Is is = (Response.Is) stub.responses().get(0);
        return is.behaviors();
    }
}
