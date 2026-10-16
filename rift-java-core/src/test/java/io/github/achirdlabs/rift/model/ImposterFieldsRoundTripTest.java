package io.github.achirdlabs.rift.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scalar imposter fields not exercised by any corpus fixture: {@code defaultResponse} (and its
 * engine-fidelity number-vs-string {@code statusCode} contract), {@code defaultForward},
 * {@code host}, {@code cert}, {@code key}, {@code serviceName}, {@code serviceInfo} (arbitrary JSON
 * pass-through), {@code strictBehaviors}, {@code recordRequests}, and the {@code allowCORS}/
 * {@code allowCors} spelling alias. Hand-written spec-derived round-trip test (G2 + G3).
 */
class ImposterFieldsRoundTripTest {

    private static final String DEFAULT_RESPONSE_JSON = """
            {
              "port": 7000,
              "protocol": "http",
              "stubs": [],
              "defaultResponse": {"statusCode": 404, "body": "not found"}
            }
            """;

    private static final String SCALAR_FIELDS_JSON = """
            {
              "port": 7100,
              "host": "0.0.0.0",
              "protocol": "https",
              "cert": "CERT_PEM_DATA",
              "key": "KEY_PEM_DATA",
              "defaultForward": "http://fallback:9000",
              "serviceName": "orders-service",
              "serviceInfo": {"version": "2.3.1", "tags": ["billing", "orders"]},
              "strictBehaviors": true,
              "recordRequests": true,
              "stubs": []
            }
            """;

    @Test
    void defaultResponseRoundTrips() {
        RoundTripAssertions.assertRoundTrips(DEFAULT_RESPONSE_JSON, ImposterDefinition::fromJson, ImposterDefinition::toJson);
    }

    @Test
    void defaultResponseStatusCodeIsWrittenAsJsonNumber() {
        ImposterDefinition imposter = ImposterDefinition.fromJson(DEFAULT_RESPONSE_JSON);
        assertEquals("404", imposter.defaultResponse().orElseThrow().statusCode());
        String written = imposter.toJson();
        assertTrue(written.contains("\"statusCode\":404"), () -> "expected a numeric statusCode, got: " + written);
        assertTrue(!written.contains("\"statusCode\":\"404\""), () -> "statusCode must not be a string: " + written);
    }

    @Test
    void scalarFieldsRoundTrip() {
        RoundTripAssertions.assertRoundTrips(SCALAR_FIELDS_JSON, ImposterDefinition::fromJson, ImposterDefinition::toJson);
    }

    @Test
    void scalarFieldsAreTyped() {
        ImposterDefinition imposter = ImposterDefinition.fromJson(SCALAR_FIELDS_JSON);
        assertEquals("0.0.0.0", imposter.host().orElseThrow());
        assertEquals("CERT_PEM_DATA", imposter.cert().orElseThrow());
        assertEquals("KEY_PEM_DATA", imposter.key().orElseThrow());
        assertEquals("http://fallback:9000", imposter.defaultForward().orElseThrow());
        assertEquals("orders-service", imposter.serviceName().orElseThrow());
        assertTrue(imposter.serviceInfo().orElseThrow().toJson().contains("2.3.1"));
        assertTrue(imposter.strictBehaviors());
        assertTrue(imposter.recordRequests());
    }

    @Test
    void allowCorsAcceptsBothSpellingsAndWritesCanonicalAllowCORS() {
        ImposterDefinition viaAlias = ImposterDefinition.fromJson("{\"protocol\":\"http\",\"stubs\":[],\"allowCORS\":true}");
        assertTrue(viaAlias.allowCors());
        assertTrue(viaAlias.toJson().contains("\"allowCORS\":true"));

        // allowCors is what an engine before 0.22.0 exported under ?replayable=true (rift#1374 renamed it)
        ImposterDefinition viaPre022Export = ImposterDefinition.fromJson("{\"protocol\":\"http\",\"stubs\":[],\"allowCors\":true}");
        assertTrue(viaPre022Export.allowCors());
        assertTrue(viaPre022Export.toJson().contains("\"allowCORS\":true"));
    }

    @Test
    void allowCorsReadsAnyJsonValueByJavaScriptTruthiness() {
        // rift#1453: the engine accepts any value and prints it as configured, so a view never fails to decode
        for (String on : new String[] {"\"yes\"", "1", "-0.5", "{}", "[]", "\"false\""}) {
            ImposterDefinition imposter = ImposterDefinition.fromJson("{\"protocol\":\"http\",\"stubs\":[],\"allowCORS\":" + on + "}");
            assertTrue(imposter.allowCors(), on);
            assertTrue(imposter.toJson().contains("\"allowCORS\":true"), on);
        }
        for (String off : new String[] {"false", "0", "0.0", "\"\"", "null"}) {
            ImposterDefinition imposter = ImposterDefinition.fromJson("{\"protocol\":\"http\",\"stubs\":[],\"allowCORS\":" + off + "}");
            assertEquals(false, imposter.allowCors(), off);
            assertEquals(false, imposter.toJson().contains("allowCORS"), off);
        }
    }

    @Test
    void aDisabledImposterReadsIntoTheTypedFieldAndWritesBackOnlyWhenFalse() {
        ImposterDefinition disabled = ImposterDefinition.fromJson("{\"port\":7200,\"protocol\":\"http\",\"enabled\":false,\"stubs\":[]}");
        assertEquals(false, disabled.enabled());
        assertEquals(java.util.Map.of(), disabled.extra(), "enabled is modeled, not carried in extra");
        assertEquals("{\"port\":7200,\"protocol\":\"http\",\"enabled\":false,\"stubs\":[]}", disabled.toJson());
    }

    @Test
    void anEnabledImposterOmitsTheKeyAsTheEngineDoes() {
        // The engine serializes enabled only when false (skip_serializing_if = is_default_enabled).
        ImposterDefinition explicit = ImposterDefinition.fromJson("{\"port\":7201,\"protocol\":\"http\",\"enabled\":true,\"stubs\":[]}");
        assertEquals(true, explicit.enabled());
        assertEquals("{\"port\":7201,\"protocol\":\"http\",\"stubs\":[]}", explicit.toJson());
        assertEquals(true, ImposterDefinition.fromJson("{\"protocol\":\"http\",\"stubs\":[]}").enabled(), "absent means enabled");
    }

    @Test
    void withEnabledChangesOnlyThatField() {
        ImposterDefinition base = ImposterDefinition.fromJson("{\"port\":7202,\"protocol\":\"http\",\"name\":\"n\",\"stubs\":[]}");
        ImposterDefinition paused = base.withEnabled(false);
        assertEquals("{\"port\":7202,\"protocol\":\"http\",\"name\":\"n\",\"enabled\":false,\"stubs\":[]}", paused.toJson());
        assertEquals(base, paused.withEnabled(true));
    }

    @Test
    void enabledIsAModeledKeyAndCannotRideInExtra() {
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> new ImposterDefinition(
                java.util.Optional.empty(), "http", java.util.List.of()).withExtra("enabled", io.github.achirdlabs.rift.json.JsonBool.FALSE));
    }
}
