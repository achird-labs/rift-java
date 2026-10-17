package io.github.achirdlabs.rift.dsl;

import io.github.achirdlabs.rift.json.JsonValue;
import org.junit.jupiter.api.Test;

import static io.github.achirdlabs.rift.dsl.RiftDsl.and;
import static io.github.achirdlabs.rift.dsl.RiftDsl.body;
import static io.github.achirdlabs.rift.dsl.RiftDsl.contains;
import static io.github.achirdlabs.rift.dsl.RiftDsl.deepEquals;
import static io.github.achirdlabs.rift.dsl.RiftDsl.endsWith;
import static io.github.achirdlabs.rift.dsl.RiftDsl.eq;
import static io.github.achirdlabs.rift.dsl.RiftDsl.exists;
import static io.github.achirdlabs.rift.dsl.RiftDsl.header;
import static io.github.achirdlabs.rift.dsl.RiftDsl.matches;
import static io.github.achirdlabs.rift.dsl.RiftDsl.merge;
import static io.github.achirdlabs.rift.dsl.RiftDsl.method;
import static io.github.achirdlabs.rift.dsl.RiftDsl.not;
import static io.github.achirdlabs.rift.dsl.RiftDsl.notExists;
import static io.github.achirdlabs.rift.dsl.RiftDsl.path;
import static io.github.achirdlabs.rift.dsl.RiftDsl.query;
import static io.github.achirdlabs.rift.dsl.RiftDsl.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code RiftDsl.merge} builds one predicate over several request fields, so its parameters apply to all of them (#312). */
class DslMergeTest {

    private static String json(PredicateSpec spec) {
        return spec.build().toJson();
    }

    @Test
    void equalsOverPathAndHeaderCarriesKeyCaseSensitive() {
        assertEquals("{\"equals\":{\"path\":\"/kcs\",\"headers\":{\"X-Tenant\":\"acme\"}},\"keyCaseSensitive\":true}",
                json(merge(path(eq("/kcs")), header("X-Tenant", eq("acme"))).keyCaseSensitive(true)));
    }

    @Test
    void everyFieldOperatorMerges() {
        assertEquals("{\"deepEquals\":{\"query\":{\"a\":\"1\"},\"body\":{\"k\":\"v\"}}}",
                json(merge(query("a", deepEquals("1")), body(deepEquals(JsonValue.parse("{\"k\":\"v\"}"))))));
        assertEquals("{\"contains\":{\"path\":\"/a\",\"body\":\"x\"}}", json(merge(path(contains("/a")), body(contains("x")))));
        assertEquals("{\"startsWith\":{\"path\":\"/a\",\"method\":\"P\"}}", json(merge(path(startsWith("/a")), method(startsWith("P")))));
        assertEquals("{\"endsWith\":{\"path\":\"z\",\"body\":\"y\"}}", json(merge(path(endsWith("z")), body(endsWith("y")))));
        assertEquals("{\"matches\":{\"path\":\"^/v\\\\d+\",\"method\":\"GET|HEAD\"}}",
                json(merge(path(matches("^/v\\d+")), method(matches("GET|HEAD")))));
        assertEquals("{\"exists\":{\"headers\":{\"X-A\":true},\"query\":{\"q\":false}}}",
                json(merge(header("X-A", exists()), query("q", notExists()))));
    }

    @Test
    void headerPartsJoinOneHeadersObjectAndKeepDifferentlyCasedNames() {
        assertEquals("{\"equals\":{\"headers\":{\"X-A\":\"1\",\"X-B\":\"2\",\"x-a\":\"3\"}}}",
                json(merge(header("X-A", eq("1")), header("X-B", eq("2")), header("x-a", eq("3")))));
    }

    @Test
    void parametersAndSelectorsGoOnTheMergedPredicate() {
        assertEquals("{\"equals\":{\"path\":\"/a\",\"body\":\"1\"},\"except\":\"\\\\s\",\"jsonpath\":{\"selector\":\"$.id\"}}",
                json(merge(path(eq("/a")), body(eq("1"))).except("\\s").jsonPath("$.id")));
    }

    @Test
    void conflictsAreRefusedWithTheirCause() {
        assertMessage("merge needs one operator, but got equals and contains", () -> merge(path(eq("/a")), body(contains("x"))));
        assertMessage("merge takes field predicates, not and", () -> merge(path(eq("/a")), and(path(eq("/b")))));
        assertMessage("merge takes field predicates, not not", () -> merge(not(path(eq("/b"))), path(eq("/a"))));
        assertMessage("set caseSensitive, keyCaseSensitive, except and selectors on the merged predicate, not on a part",
                () -> merge(path(eq("/a")).caseSensitive(true), body(eq("x"))));
        assertMessage("merge binds path twice", () -> merge(path(eq("/a")), path(eq("/b"))));
        assertMessage("merge binds headers.X-A twice", () -> merge(header("X-A", eq("1")), header("X-A", eq("2"))));
    }

    @Test
    void thePartsAreUnchangedAndOnePartIsItself() {
        PredicateSpec p = path(eq("/a"));
        PredicateSpec h = header("X-A", eq("1"));
        merge(p, h);
        assertEquals("{\"equals\":{\"path\":\"/a\"}}", json(p));
        assertEquals("{\"equals\":{\"headers\":{\"X-A\":\"1\"}}}", json(h));
        assertEquals(json(p), json(merge(p)));
    }

    @Test
    void onStillWritesItsMethodAndPathEquals() {
        assertTrue(RiftDsl.on("GET", "/a").build().toJson().startsWith(
                "{\"predicates\":[{\"equals\":{\"method\":\"GET\",\"path\":\"/a\"}}]"));
    }

    private static void assertMessage(String expected, org.junit.jupiter.api.function.Executable call) {
        assertEquals(expected, assertThrows(IllegalArgumentException.class, call).getMessage());
    }
}
