package io.github.achirdlabs.rift.model;

import io.github.achirdlabs.rift.json.JsonNumber;
import io.github.achirdlabs.rift.json.JsonString;
import io.github.achirdlabs.rift.json.JsonValue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The imposter's client-certificate keys — {@code mutualAuth}, {@code rejectUnauthorized}, {@code ca}
 * (#210) — are typed and round-trip as written. The model validates no combination: it has to read
 * whatever an engine or a fixture holds, including combinations rift 0.18.0 refuses.
 */
class ImposterClientAuthModelTest {

    private static final String CA = "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n";

    @Test
    void readsAllThreeKeys() {
        ImposterDefinition def = ImposterDefinition.fromJson("""
                {"protocol": "https", "mutualAuth": true, "rejectUnauthorized": true,
                 "ca": ["-----BEGIN CERTIFICATE-----\\nMIIB\\n-----END CERTIFICATE-----\\n"], "stubs": []}
                """);
        assertTrue(def.mutualAuth());
        assertTrue(def.rejectUnauthorized());
        assertEquals(Optional.of(new CaCertificates(List.of(CA), false)), def.ca());
        assertTrue(def.extra().isEmpty(), "typed keys are not also carried in extra: " + def.extra());
    }

    @Test
    void aSingleStringCaWritesBackAsAString() {
        String json = """
                {"protocol": "https", "mutualAuth": true, "rejectUnauthorized": true, "ca": "PEM", "stubs": []}
                """;
        ImposterDefinition def = ImposterDefinition.fromJson(json);
        assertEquals(Optional.of(new CaCertificates(List.of("PEM"), true)), def.ca());
        assertEquals(JsonValue.parse(json), JsonValue.parse(def.toJson()));
    }

    @Test
    void anArrayCaWritesBackAsAnArrayEvenWithOneEntry() {
        String json = """
                {"protocol": "https", "mutualAuth": true, "rejectUnauthorized": true, "ca": ["PEM"], "stubs": []}
                """;
        assertEquals(JsonValue.parse(json), JsonValue.parse(ImposterDefinition.fromJson(json).toJson()));
    }

    @Test
    void anEmptyCaArrayIsKeptRatherThanDropped() {
        // rift refuses `ca: []` with rejectUnauthorized; dropping it on the way through would change
        // which error the engine reports, so it survives as written.
        String json = """
                {"protocol": "https", "mutualAuth": true, "rejectUnauthorized": true, "ca": [], "stubs": []}
                """;
        ImposterDefinition def = ImposterDefinition.fromJson(json);
        assertEquals(Optional.of(new CaCertificates(List.of(), false)), def.ca());
        assertEquals(JsonValue.parse(json), JsonValue.parse(def.toJson()));
    }

    /** rift 0.24.0's {@code ?replayable=true} export of an https imposter, verbatim (rift#1576). */
    private static final String ENGINE_024_HTTPS_EXPORT = """
            {"port": 24501, "protocol": "https", "ciphers": "ECDHE-RSA-AES128-GCM-SHA256", "recordRequests": false,
             "stubs": [{"responses": [{"is": {"statusCode": 200}}]}],
             "mutualAuth": false, "rejectUnauthorized": false, "ca": null}
            """;

    @Test
    void aNullCaReadsAsAbsent() {
        ImposterDefinition def = ImposterDefinition.fromJson(ENGINE_024_HTTPS_EXPORT);
        assertEquals(Optional.empty(), def.ca());
        assertFalse(def.mutualAuth());
        assertFalse(def.rejectUnauthorized());
        assertEquals(Map.of("ciphers", new JsonString("ECDHE-RSA-AES128-GCM-SHA256")), def.extra(),
                "ciphers is not modeled, so it rides extra unchanged");
    }

    @Test
    void aNullCaIsNotWrittenBackAndCiphersIs() {
        // null and absent mean the same to every engine and to Mountebank, so the write omits it.
        assertEquals(JsonValue.parse("""
                {"port": 24501, "protocol": "https",
                 "stubs": [{"predicates": [], "responses": [{"is": {"statusCode": "200"}}]}],
                 "ciphers": "ECDHE-RSA-AES128-GCM-SHA256"}
                """), JsonValue.parse(ImposterDefinition.fromJson(ENGINE_024_HTTPS_EXPORT).toJson()));
    }

    @Test
    void aWholeExportWithANullCaParses() {
        ImposterDefinitions defs = ImposterDefinitions.fromJson("{\"imposters\": [" + ENGINE_024_HTTPS_EXPORT
                + ", {\"port\": 24502, \"protocol\": \"http\", \"stubs\": []}]}");
        assertEquals(List.of(24501, 24502), defs.imposters().stream().map(d -> d.port().orElseThrow()).toList());
        assertEquals(Optional.empty(), defs.imposters().get(0).ca());
    }

    @Test
    void aCaOfAnotherTypeIsStillRefused() {
        assertThrows(WireFormatException.class, () -> ImposterDefinition.fromJson("""
                {"protocol": "https", "ca": 5, "stubs": []}
                """));
    }

    @Test
    void combinationsTheEngineRefusesStillParse() {
        // Valid Mountebank, refused by rift 0.18.0 at creation: the model reads it and writes it back.
        String json = """
                {"protocol": "http", "rejectUnauthorized": true, "stubs": []}
                """;
        ImposterDefinition def = ImposterDefinition.fromJson(json);
        assertFalse(def.mutualAuth());
        assertTrue(def.rejectUnauthorized());
        assertEquals(JsonValue.parse(json), JsonValue.parse(def.toJson()));
    }

    @Test
    void absentKeysAreNotWritten() {
        String written = ImposterDefinition.fromJson("{\"protocol\": \"https\", \"stubs\": []}").toJson();
        assertFalse(written.contains("mutualAuth"), written);
        assertFalse(written.contains("rejectUnauthorized"), written);
        assertFalse(written.contains("\"ca\""), written);
    }

    @Test
    void theEarlierConstructorStillBuildsADefinitionWithoutClientAuth() {
        ImposterDefinition def = new ImposterDefinition(Optional.empty(), Optional.empty(), "https",
                Optional.empty(), Optional.empty(), Optional.empty(), false, false, List.of(), Optional.empty(),
                Optional.empty(), false, false, Optional.empty(), Optional.empty(), Optional.empty(), Map.of());
        assertFalse(def.mutualAuth());
        assertFalse(def.rejectUnauthorized());
        assertTrue(def.ca().isEmpty());
    }

    @Test
    void clientAuthKeysAreRejectedInExtra() {
        ImposterDefinition def = new ImposterDefinition(Optional.empty(), "https", List.of());
        for (String key : List.of("mutualAuth", "rejectUnauthorized", "ca")) {
            assertThrows(WireFormatException.class, () -> def.withExtra(key, JsonNumber.of(1)), key);
        }
    }

    @Test
    void theSingleStringFormHoldsExactlyOneCertificate() {
        assertThrows(IllegalArgumentException.class, () -> new CaCertificates(List.of("a", "b"), true));
        assertThrows(IllegalArgumentException.class, () -> new CaCertificates(List.of(), true));
    }

    @Test
    void aCaThatIsNeitherAStringNorAnArrayIsACodecError() {
        assertThrows(WireFormatException.class, () -> ImposterDefinition.fromJson("""
                {"protocol": "https", "ca": 5, "stubs": []}
                """));
    }

    @Test
    void aNonStringCaEntryIsACodecError() {
        assertThrows(WireFormatException.class, () -> ImposterDefinition.fromJson("""
                {"protocol": "https", "ca": [7], "stubs": []}
                """));
    }
}
