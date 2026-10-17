package io.github.achirdlabs.rift.dsl;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The predicateGenerators keys rift 0.22.0 reads: xpath, ignore, predicateOperator, except and the new fields (#311). */
class PredicateGeneratorSpecTest {

    private static String json(PredicateGeneratorSpec spec) {
        return spec.build().toJson();
    }

    private static PredicateGeneratorSpec body() {
        return PredicateGeneratorSpec.create().matching(RequestField.BODY);
    }

    @Test
    void xPathCarriesItsSelectorAndNamespaces() {
        assertEquals("{\"matches\":{\"body\":true},\"xpath\":{\"selector\":\"//a:id\",\"ns\":{\"a\":\"urn:a\"}}}",
                json(body().xPath("//a:id", Map.of("a", "urn:a"))));
        assertEquals("{\"matches\":{\"body\":true},\"xpath\":{\"selector\":\"//id\"}}", json(body().xPath("//id")));
    }

    @Test
    void jsonPathAndXPathReplaceEachOther() {
        assertEquals("{\"matches\":{\"body\":true},\"xpath\":{\"selector\":\"//id\"}}", json(body().jsonPath("$.id").xPath("//id")));
        assertEquals("{\"matches\":{\"body\":true},\"jsonpath\":{\"selector\":\"$.id\"}}", json(body().xPath("//id").jsonPath("$.id")));
    }

    @Test
    void ignoreWritesAnArrayPerFieldAndMergesRepeatedCalls() {
        assertEquals("{\"matches\":{\"query\":true},\"ignore\":{\"query\":[\"ts\",\"nonce\"]}}",
                json(PredicateGeneratorSpec.create().matching(RequestField.QUERY).ignore(RequestField.QUERY, "ts", "nonce")));
        assertEquals("{\"matches\":{\"form\":true},\"ignore\":{\"form\":[\"a\",\"b\"]}}",
                json(PredicateGeneratorSpec.create().matching(RequestField.FORM)
                        .ignore(RequestField.FORM, "a").ignore(RequestField.FORM, "b", "a")));
    }

    @Test
    void ignoredHeaderNamesTakeTheEnginesTitleCase() {
        assertEquals("{\"matches\":{\"headers\":true},\"ignore\":{\"headers\":[\"X-Request-Id\",\"Date\"]}}",
                json(PredicateGeneratorSpec.create().matching(RequestField.HEADERS)
                        .ignore(RequestField.HEADERS, "x-request-id", "DATE")));
        assertEquals("X-Request-Id", PredicateGeneratorSpec.headerKey("X-REQUEST-ID"));
        assertEquals("Www-Authenticate", PredicateGeneratorSpec.headerKey("WWW-Authenticate"));
        assertEquals("A--B", PredicateGeneratorSpec.headerKey("a--b"));
    }

    @Test
    void invalidUsesFailFast() {
        PredicateGeneratorSpec spec = PredicateGeneratorSpec.create();
        assertThrows(IllegalArgumentException.class, () -> spec.ignore(RequestField.PATH, "x"));
        assertThrows(IllegalArgumentException.class, () -> spec.ignore(RequestField.QUERY));
        assertThrows(IllegalArgumentException.class, () -> spec.ignore(RequestField.QUERY, " "));
        assertThrows(NullPointerException.class, () -> spec.ignore(RequestField.QUERY, (String) null));
        assertThrows(IllegalArgumentException.class, () -> spec.xPath(" "));
        assertThrows(IllegalArgumentException.class, () -> spec.jsonPath(""));
        assertThrows(NullPointerException.class, () -> spec.xPath("//a", null));
    }

    @Test
    void theNewFieldsSerializeUnderMatches() {
        assertEquals("{\"matches\":{\"requestFrom\":true,\"ip\":true,\"form\":true}}",
                json(PredicateGeneratorSpec.create().matching(RequestField.REQUEST_FROM, RequestField.IP, RequestField.FORM)));
    }

    @Test
    void operatorAndExceptAreWritten() {
        assertEquals("{\"matches\":{\"path\":true},\"predicateOperator\":\"startsWith\",\"except\":\"\\\\d+\"}",
                json(PredicateGeneratorSpec.create().matching(RequestField.PATH)
                        .predicateOperator(GeneratorOperator.STARTS_WITH).except("\\d+")));
        assertEquals("deepEquals", GeneratorOperator.DEEP_EQUALS.wire());
        assertEquals(7, GeneratorOperator.values().length);
    }

    @Test
    void aGeneratorWithEveryKeySurvivesAModelRoundTrip() {
        String stub = RiftDsl.onRequest().willReturn(RiftDsl.proxyTo("http://o").generateBy(
                PredicateGeneratorSpec.create().matching(RequestField.QUERY, RequestField.HEADERS)
                        .caseSensitive(true).predicateOperator(GeneratorOperator.EQUALS).except("x")
                        .xPath("//a", Map.of("a", "urn:a")).ignore(RequestField.QUERY, "ts"))).build().toJson();
        assertEquals(stub, io.github.achirdlabs.rift.model.Stub.fromJson(stub).toJson());
    }
}
