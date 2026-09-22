package io.github.achirdlabs.rift.dsl;

import io.github.achirdlabs.rift.json.JsonValue;
import io.github.achirdlabs.rift.model.CaCertificates;
import io.github.achirdlabs.rift.model.ImposterDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static io.github.achirdlabs.rift.dsl.RiftDsl.imposter;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code requireClientCertificate} (#210): the DSL can express only the two client-auth states rift
 * accepts, so the combinations it refuses at creation cannot be built here in the first place.
 */
class ImposterSpecClientCertificateTest {

    private static final String CERT = "-----BEGIN CERTIFICATE-----\nSERVER\n-----END CERTIFICATE-----\n";
    private static final String KEY = "-----BEGIN PRIVATE KEY-----\nKEY\n-----END PRIVATE KEY-----\n";
    private static final String CA = "-----BEGIN CERTIFICATE-----\nCA\n-----END CERTIFICATE-----\n";
    private static final String CA2 = "-----BEGIN CERTIFICATE-----\nCA2\n-----END CERTIFICATE-----\n";

    @Test
    void requiringAChainEmitsAllThreeKeys() {
        JsonValue written = JsonValue.parse(imposter("s").https(CERT, KEY).requireClientCertificate(CA).build().toJson());
        JsonValue expected = JsonValue.parse("""
                {"protocol": "https", "cert": "-----BEGIN CERTIFICATE-----\\nSERVER\\n-----END CERTIFICATE-----\\n",
                 "key": "-----BEGIN PRIVATE KEY-----\\nKEY\\n-----END PRIVATE KEY-----\\n", "name": "s", "stubs": [],
                 "mutualAuth": true, "rejectUnauthorized": true,
                 "ca": "-----BEGIN CERTIFICATE-----\\nCA\\n-----END CERTIFICATE-----\\n"}
                """);
        assertEquals(expected, written);
    }

    @Test
    void requiringAnyCertificateEmitsMutualAuthOnly() {
        ImposterDefinition def = imposter("s").https(CERT, KEY).requireClientCertificate().build();
        assertTrue(def.mutualAuth());
        assertFalse(def.rejectUnauthorized());
        assertTrue(def.ca().isEmpty());
    }

    @Test
    void severalAnchorsAreAllKept() {
        ImposterDefinition def = imposter("s").https(CERT, KEY).requireClientCertificate(CA, CA2).build();
        assertEquals(Optional.of(new CaCertificates(List.of(CA, CA2), false)), def.ca());
    }

    @Test
    void worksOnAnHttpsImposterWithoutItsOwnCertificate() {
        // The engine falls back to its default or self-signed certificate; mutualAuth does not need cert/key.
        assertTrue(imposter("s").protocol("https").requireClientCertificate().build().mutualAuth());
    }

    @Test
    void refusedOnACleartextImposter() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> imposter("s").requireClientCertificate(CA).build());
        assertTrue(e.getMessage().contains("https"), e.getMessage());
        // Order-independent: switching to https later in the chain is fine.
        assertTrue(imposter("s").requireClientCertificate(CA).https(CERT, KEY).build().mutualAuth());
    }

    @Test
    void anAnchorWithoutACertificateIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> imposter("s").https(CERT, KEY).requireClientCertificate(KEY).build());
        assertThrows(IllegalArgumentException.class,
                () -> imposter("s").https(CERT, KEY).requireClientCertificate(CA, "").build());
    }

    @Test
    void anEmptyAnchorListIsRefusedRatherThanReadAsAnyCertificate() {
        assertThrows(IllegalArgumentException.class,
                () -> imposter("s").https(CERT, KEY).requireClientCertificate(new String[0]).build());
    }

    @Test
    void aLaterCallReplacesAnEarlierOne() {
        ImposterDefinition def = imposter("s").https(CERT, KEY)
                .requireClientCertificate(CA).requireClientCertificate().build();
        assertTrue(def.mutualAuth());
        assertFalse(def.rejectUnauthorized());
        assertTrue(def.ca().isEmpty());
    }

    @Test
    @SuppressWarnings("deprecation") // recordMatches/metrics/proxyPool: chained to prove they carry the setting
    void survivesEveryOtherChainMethod() {
        ImposterDefinition def = imposter("s").https(CERT, KEY).requireClientCertificate(CA)
                .port(4545).protocol("https").host("127.0.0.1").record().recordMatches().allowCors()
                .defaultResponse(RiftDsl.ok()).defaultForward("http://up").strictBehaviors().serviceName("svc")
                .serviceInfo(JsonValue.parse("{}")).flowState(RiftDsl.inMemoryFlowState()).metrics(9090)
                .scriptEngine(ScriptEngine.RHAI, java.time.Duration.ofSeconds(1))
                .script("s", Script.rhai("1")).proxyPool(1, java.time.Duration.ofSeconds(1))
                .stub(RiftDsl.onGet("/").willReturn(RiftDsl.ok())).stub(List.of())
                .build();
        assertTrue(def.mutualAuth());
        assertTrue(def.rejectUnauthorized());
        assertEquals(List.of(CA), def.ca().orElseThrow().pems());
    }

    @Test
    void notSetByDefault() {
        ImposterDefinition def = imposter("s").https(CERT, KEY).build();
        assertFalse(def.mutualAuth());
        assertFalse(def.rejectUnauthorized());
        assertTrue(def.ca().isEmpty());
    }
}
