package io.github.achirdlabs.rift.model;

import io.github.achirdlabs.rift.json.JsonNumber;
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
