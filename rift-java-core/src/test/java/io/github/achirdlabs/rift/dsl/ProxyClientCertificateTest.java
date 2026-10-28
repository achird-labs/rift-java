package io.github.achirdlabs.rift.dsl;

import io.github.achirdlabs.rift.json.JsonString;
import io.github.achirdlabs.rift.json.JsonValue;
import io.github.achirdlabs.rift.model.ProxyResponse;
import io.github.achirdlabs.rift.model.Response;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.github.achirdlabs.rift.dsl.RiftDsl.proxyTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** A proxy's client certificate for an mTLS upstream (#342, rift#1609): {@code cert}, {@code key}, {@code passphrase}. */
class ProxyClientCertificateTest {

    private static final String CERT = "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n";
    private static final String KEY = "-----BEGIN PRIVATE KEY-----\nMIGH\n-----END PRIVATE KEY-----\n";
    private static final String ENCRYPTED_KEY = "-----BEGIN ENCRYPTED PRIVATE KEY-----\nMIHs\n-----END ENCRYPTED PRIVATE KEY-----\n";

    private static ProxyResponse proxyOf(ProxySpec spec) {
        return ((Response.Proxy) spec.build()).proxy();
    }

    @Test
    void certAndKeyGoOutAsTheProxysCertAndKey() {
        ProxyResponse proxy = proxyOf(proxyTo("https://up").clientCertificate(CERT, KEY));
        assertEquals(Map.of("cert", new JsonString(CERT), "key", new JsonString(KEY)), proxy.extra());
        assertEquals(List.of("cert", "key"), List.copyOf(proxy.extra().keySet()));
    }

    @Test
    void aPassphraseFollowsTheKey() {
        ProxyResponse proxy = proxyOf(proxyTo("https://up").clientCertificate(CERT, ENCRYPTED_KEY, "changeit"));
        assertEquals(List.of("cert", "key", "passphrase"), List.copyOf(proxy.extra().keySet()));
        assertEquals(new JsonString("changeit"), proxy.extra().get("passphrase"));
    }

    @Test
    void theWireFormCarriesTheThreeKeysAfterTheModeledOnes() {
        String json = proxyTo("https://up").clientCertificate("-----BEGIN CERTIFICATE-----c", "-----BEGIN EC PRIVATE KEY-----k", "pw")
                .build().toJsonValue().toJson();
        assertEquals(JsonValue.parse("{\"proxy\":{\"to\":\"https://up\",\"mode\":\"\",\"predicateGenerators\":[],"
                        + "\"cert\":\"-----BEGIN CERTIFICATE-----c\",\"key\":\"-----BEGIN EC PRIVATE KEY-----k\","
                        + "\"passphrase\":\"pw\"}}"),
                JsonValue.parse(json));
    }

    @Test
    void aLaterCallReplacesAnEarlierOneIncludingItsPassphrase() {
        ProxyResponse proxy = proxyOf(proxyTo("https://up")
                .clientCertificate(CERT, ENCRYPTED_KEY, "changeit")
                .clientCertificate(CERT, KEY));
        assertEquals(Map.of("cert", new JsonString(CERT), "key", new JsonString(KEY)), proxy.extra());
    }

    @Test
    void theOtherChainersKeepTheCertificate() {
        ProxyResponse proxy = proxyOf(proxyTo("https://up").clientCertificate(CERT, KEY).proxyAlways().injectHeader("X", "1"));
        assertEquals(new JsonString(CERT), proxy.extra().get("cert"));
        assertEquals("proxyAlways", proxy.mode());
    }

    @Test
    void aCertificateWithoutACertificateBlockIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> proxyTo("https://up").clientCertificate(KEY, KEY));
        assertEquals("client certificate PEM contains no certificate: expected a -----BEGIN CERTIFICATE----- block",
                e.getMessage());
    }

    @Test
    void aKeyWithoutAPrivateKeyBlockIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> proxyTo("https://up").clientCertificate(CERT, CERT));
        assertEquals("client key PEM contains no private key: expected a -----BEGIN ... PRIVATE KEY----- block",
                e.getMessage());
    }

    @Test
    void anEmptyPassphraseIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> proxyTo("https://up").clientCertificate(CERT, ENCRYPTED_KEY, ""));
        assertEquals("passphrase must not be empty; use clientCertificate(certPem, keyPem) for an unencrypted key",
                e.getMessage());
    }

    @Test
    void nullsAreRefused() {
        assertThrows(NullPointerException.class, () -> proxyTo("https://up").clientCertificate(null, KEY));
        assertThrows(NullPointerException.class, () -> proxyTo("https://up").clientCertificate(CERT, null));
        assertThrows(NullPointerException.class, () -> proxyTo("https://up").clientCertificate(CERT, KEY, null));
    }
}
