package io.github.achirdlabs.rift.verify;

import io.github.achirdlabs.rift.RecordedRequest;
import io.github.achirdlabs.rift.error.InvalidDefinition;
import io.github.achirdlabs.rift.json.JsonObject;
import io.github.achirdlabs.rift.model.Predicate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.github.achirdlabs.rift.dsl.RiftDsl.and;
import static io.github.achirdlabs.rift.dsl.RiftDsl.body;
import static io.github.achirdlabs.rift.dsl.RiftDsl.contains;
import static io.github.achirdlabs.rift.dsl.RiftDsl.deepEquals;
import static io.github.achirdlabs.rift.dsl.RiftDsl.endsWith;
import static io.github.achirdlabs.rift.dsl.RiftDsl.equalTo;
import static io.github.achirdlabs.rift.dsl.RiftDsl.exists;
import static io.github.achirdlabs.rift.dsl.RiftDsl.header;
import static io.github.achirdlabs.rift.dsl.RiftDsl.matches;
import static io.github.achirdlabs.rift.dsl.RiftDsl.method;
import static io.github.achirdlabs.rift.dsl.RiftDsl.not;
import static io.github.achirdlabs.rift.dsl.RiftDsl.notExists;
import static io.github.achirdlabs.rift.dsl.RiftDsl.onRequest;
import static io.github.achirdlabs.rift.dsl.RiftDsl.or;
import static io.github.achirdlabs.rift.dsl.RiftDsl.path;
import static io.github.achirdlabs.rift.dsl.RiftDsl.query;
import static io.github.achirdlabs.rift.dsl.RiftDsl.startsWith;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Mountebank predicate semantics evaluated client-side against a {@link RecordedRequest}. */
class PredicateEvaluatorTest {

    private static RecordedRequest req(String httpMethod, String httpPath, String body,
            Map<String, List<String>> query, Map<String, List<String>> headers) {
        return new RecordedRequest(httpMethod, httpPath, query, headers, body,
                Optional.empty(), Optional.empty(), Optional.empty(), Map.of(), JsonObject.of());
    }

    private static RecordedRequest getReq(String httpPath) {
        return req("GET", httpPath, "", Map.of(), Map.of());
    }

    private static boolean check(RecordedRequest r, Predicate... predicates) {
        return PredicateEvaluator.matches(r, List.of(predicates));
    }

    @Test
    void fieldEqualsOnMethodAndPath() {
        RecordedRequest r = getReq("/api/users/1");
        assertTrue(check(r, method(equalTo("GET")).build(), path(equalTo("/api/users/1")).build()));
        assertFalse(check(r, method(equalTo("POST")).build()));
        assertFalse(check(r, path(equalTo("/api/users/2")).build()));
    }

    @Test
    void containsStartsEndsMatchesOnPath() {
        RecordedRequest r = getReq("/api/users/1");
        assertTrue(check(r, path(contains("users")).build()));
        assertTrue(check(r, path(startsWith("/api")).build()));
        assertTrue(check(r, path(endsWith("/1")).build()));
        assertTrue(check(r, path(matches("/api/users/\\d+")).build()));
        assertFalse(check(r, path(matches("/api/orders/\\d+")).build()));
    }

    @Test
    void existsOnQueryAndHeader() {
        RecordedRequest r = req("GET", "/x", "", Map.of("page", List.of("2")), Map.of("Accept", List.of("application/json")));
        assertTrue(check(r, query("page", exists()).build()));
        assertTrue(check(r, header("Accept", exists()).build()));
        assertFalse(check(r, query("missing", exists()).build()));
    }

    @Test
    void existsOnScalarFields() {
        RecordedRequest present = req("POST", "/x", "hello", Map.of(), Map.of());
        assertTrue(check(present, method(exists()).build()));
        assertTrue(check(present, path(exists()).build()));
        assertTrue(check(present, body(exists()).build()));

        // an empty scalar reads as absent, and notExists() is the inverse assertion — both directions
        // matter, since a flipped comparison would still satisfy the positive case alone.
        RecordedRequest emptyBody = req("POST", "/x", "", Map.of(), Map.of());
        assertFalse(check(emptyBody, body(exists()).build()));
        assertTrue(check(emptyBody, body(notExists()).build()));
        assertFalse(check(present, body(notExists()).build()));
    }

    @Test
    void existsWithSelectorOverBody() {
        RecordedRequest r = req("POST", "/users", "{\"name\":\"Alice\",\"empty\":\"\"}", Map.of(), Map.of());
        assertTrue(check(r, body(exists()).jsonPath("$.name").build()));
        assertFalse(check(r, body(exists()).jsonPath("$.missing").build()));
        assertFalse(check(r, body(exists()).jsonPath("$.empty").build()));
        assertTrue(check(r, body(notExists()).jsonPath("$.missing").build()));
    }

    @Test
    void headerAndQueryEquals() {
        RecordedRequest r = req("GET", "/x", "", Map.of("page", List.of("2")), Map.of("Accept", List.of("application/json")));
        assertTrue(check(r, header("Accept", equalTo("application/json")).build()));
        assertTrue(check(r, query("page", equalTo("2")).build()));
        assertFalse(check(r, header("Accept", equalTo("text/plain")).build()));
    }

    @Test
    void caseSensitivity() {
        RecordedRequest r = getReq("/API/Users");
        // Mountebank default: caseSensitive=false
        assertTrue(check(r, path(equalTo("/api/users")).build()));
        assertFalse(check(r, path(equalTo("/api/users")).caseSensitive(true).build()));
        assertTrue(check(r, path(equalTo("/API/Users")).caseSensitive(true).build()));
    }

    @Test
    void exceptStripsBeforeCompare() {
        RecordedRequest r = getReq("/api/users/12345");
        // strip the trailing digits before comparing
        assertTrue(check(r, path(equalTo("/api/users/")).except("[0-9]+").build()));
    }

    @Test
    void combinatorsAndOrNot() {
        RecordedRequest r = getReq("/api/users/1");
        assertTrue(check(r, and(path(startsWith("/api")), path(endsWith("/1"))).build()));
        assertTrue(check(r, or(path(equalTo("/nope")), path(equalTo("/api/users/1"))).build()));
        assertTrue(check(r, not(path(equalTo("/other"))).build()));
        assertFalse(check(r, not(path(equalTo("/api/users/1"))).build()));
    }

    @Test
    void jsonPathSelectorOverBody() {
        RecordedRequest r = req("POST", "/users", "{\"name\":\"Alice\",\"roles\":[\"admin\",\"user\"]}", Map.of(), Map.of());
        assertTrue(check(r, body(equalTo("Alice")).jsonPath("$.name").build()));
        assertTrue(check(r, body(equalTo("admin")).jsonPath("$.roles[0]").build()));
        assertFalse(check(r, body(equalTo("Bob")).jsonPath("$.name").build()));
    }

    @Test
    void xPathSelectorOverBody() {
        RecordedRequest r = req("POST", "/x", "<order><item>book</item></order>", Map.of(), Map.of());
        assertTrue(check(r, body(equalTo("book")).xPath("//item").build()));
        assertFalse(check(r, body(equalTo("pen")).xPath("//item").build()));
    }

    @Test
    void deepEqualsOnBody() {
        RecordedRequest r = req("POST", "/x", "{\"a\":1,\"b\":2}", Map.of(), Map.of());
        assertTrue(check(r, body(deepEquals("{\"b\":2,\"a\":1}")).build()));
        assertFalse(check(r, body(deepEquals("{\"a\":1}")).build()));
    }

    @Test
    void jsonPathWildcardAndRecursiveDescent() {
        RecordedRequest r = req("POST", "/x",
                "{\"items\":[{\"id\":\"a\"},{\"id\":\"b\"}],\"deep\":{\"nested\":{\"id\":\"z\"}}}", Map.of(), Map.of());
        assertTrue(check(r, body(equalTo("b")).jsonPath("$.items[*].id").build()));  // wildcard, any value
        assertTrue(check(r, body(equalTo("z")).jsonPath("$..id").build()));           // recursive descent
    }

    @Test
    void keyCaseSensitivityOnHeaders() {
        RecordedRequest r = req("GET", "/x", "", Map.of(), Map.of("Content-Type", List.of("application/json")));
        assertTrue(check(r, header("content-type", equalTo("application/json")).build()));  // default: key-insensitive
        assertFalse(check(r, header("content-type", equalTo("application/json")).keyCaseSensitive(true).build()));
    }

    @Test
    void multiValueHeaderMatchesAnyValue() {
        RecordedRequest r = req("GET", "/x", "", Map.of(), Map.of("Accept", List.of("text/html", "application/json")));
        assertTrue(check(r, header("Accept", equalTo("application/json")).build()));
    }

    @Test
    void deepEqualsIsCaseInsensitiveByDefault() {
        RecordedRequest r = req("POST", "/x", "{\"name\":\"alice\"}", Map.of(), Map.of());
        assertTrue(check(r, body(deepEquals("{\"name\":\"Alice\"}")).build()));
        assertFalse(check(r, body(deepEquals("{\"name\":\"Alice\"}")).caseSensitive(true).build()));
    }

    @Test
    void malformedXPathSelectorSurfacesAsInvalidDefinition() {
        RecordedRequest r = req("POST", "/x", "<a><b>1</b></a>", Map.of(), Map.of());
        assertThrows(InvalidDefinition.class, () -> check(r, body(equalTo("1")).xPath("///[bad(").build()));
    }

    @Test
    void anXPathElementReadsAsItsFirstChildNodeAsTheEngineDoes() {
        // rift 0.23.0 (rift#1495) reads a selected element as Mountebank's nodeValue: its first child's
        // text, "undefined" when it has none or starts with an element
        RecordedRequest mixed = req("POST", "/x", "<a>x<b/>y</a>", Map.of(), Map.of());
        assertTrue(check(mixed, body(equalTo("x")).xPath("//a").build()));
        assertFalse(check(mixed, body(equalTo("xy")).xPath("//a").build()));
        assertTrue(check(req("POST", "/x", "<a><b/>z</a>", Map.of(), Map.of()), body(equalTo("undefined")).xPath("//a").build()));
        assertTrue(check(req("POST", "/x", "<a/>", Map.of(), Map.of()), body(equalTo("undefined")).xPath("//a").build()));
        assertTrue(check(req("POST", "/x", "<a>x&amp;y<![CDATA[z]]></a>", Map.of(), Map.of()),
                body(equalTo("x&yz")).xPath("//a").build()), "text, references and CDATA are one node");
    }

    @Test
    void textAndAttributeSelectionsAndStringFunctionsReadTheirValue() {
        RecordedRequest r = req("POST", "/x", "<a id=\"7\">x<b/>y</a>", Map.of(), Map.of());
        assertTrue(check(r, body(equalTo("x")).xPath("//a/text()").build()));
        assertTrue(check(r, body(equalTo("7")).xPath("//a/@id").build()));
        assertTrue(check(r, body(equalTo("xy")).xPath("string(//a)").build()), "string() reads the whole text");
    }

    @Test
    void xxeEntityInBodyIsNotResolved() {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE t [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><t>&x;</t>";
        RecordedRequest r = req("POST", "/x", xxe, Map.of(), Map.of());
        // the hardened parser rejects the DOCTYPE → no extracted value → no match, and never reads the file
        assertFalse(check(r, body(contains("root:")).xPath("//t").build()));
    }

    @Test
    void injectPredicateIsRejected() {
        RecordedRequest r = getReq("/x");
        List<Predicate> preds = onRequest().withPredicateInject("function(req){return true;}").predicates();
        assertThrows(InvalidDefinition.class, () -> PredicateEvaluator.matches(r, preds));
    }
}
