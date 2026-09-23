package io.github.achirdlabs.rift.conformance;

import io.github.achirdlabs.rift.Imposter;
import io.github.achirdlabs.rift.Rift;
import io.github.achirdlabs.rift.dsl.ImposterSpec;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import static io.github.achirdlabs.rift.conformance.LiveEngine.engine;
import static io.github.achirdlabs.rift.conformance.LiveEngine.gated;
import static io.github.achirdlabs.rift.dsl.RiftDsl.imposter;
import static io.github.achirdlabs.rift.dsl.RiftDsl.onGet;
import static io.github.achirdlabs.rift.dsl.RiftDsl.status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Client-certificate authentication on an HTTPS imposter (#210), against a live engine, with real
 * TLS handshakes. Before rift 0.18.0 the keys were dropped and the listener accepted every client,
 * which is exactly what a client-side test cannot see — only a handshake that is refused can.
 *
 * <p>The imposter serves {@code tls/leaf.pem} (issued by {@code tls/ca.pem}). {@code tls/client.p12}
 * holds a client certificate issued by the same CA; {@code tls/untrusted-client.p12} one issued by an
 * unrelated CA. See {@code tls/README.md} for how they were made.
 */
class ImposterClientAuthIT {

    private static final char[] P12_PASSWORD = "changeit".toCharArray();

    @TestFactory
    Stream<DynamicTest> verifiedClientCertificates() {
        return gated("requireClientCertificate(ca): only a client chaining to ca is served", () -> {
            try (Rift rift = engine()) {
                Imposter imp = rift.create(served(imposter("mtls-verify")
                        .https(resource("tls/leaf.pem"), resource("tls/leaf-key.pem"))
                        .requireClientCertificate(resource("tls/ca.pem"))));

                assertEquals("hello", get(imp, Optional.of("tls/client.p12")).body(), "a trusted client is served");
                assertThrows(IOException.class, () -> get(imp, Optional.empty()),
                        "a client with no certificate must be refused at the handshake");
                assertThrows(IOException.class, () -> get(imp, Optional.of("tls/untrusted-client.p12")),
                        "a certificate from another CA must be refused: the chain is validated");
            }
        });
    }

    @TestFactory
    Stream<DynamicTest> anyClientCertificate() {
        return gated("requireClientCertificate(): any client certificate is served, none is refused", () -> {
            try (Rift rift = engine()) {
                Imposter imp = rift.create(served(imposter("mtls-any")
                        .https(resource("tls/leaf.pem"), resource("tls/leaf-key.pem"))
                        .requireClientCertificate()));

                assertEquals("hello", get(imp, Optional.of("tls/untrusted-client.p12")).body(),
                        "the chain is not validated in this mode");
                assertThrows(IOException.class, () -> get(imp, Optional.empty()),
                        "but a certificate is still required");
            }
        });
    }

    private static ImposterSpec served(ImposterSpec spec) {
        return spec.stub(onGet("/").willReturn(status(200).withTextBody("hello")));
    }

    private static HttpResponse<String> get(Imposter imp, Optional<String> clientP12) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .sslContext(sslContext(clientP12))
                .build();
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("https://127.0.0.1:" + imp.port() + "/"))
                        .timeout(Duration.ofSeconds(20)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response::body);
        return response;
    }

    /** Trusts the test CA (which issued the imposter's certificate) and presents {@code clientP12}, if any. */
    private static SSLContext sslContext(Optional<String> clientP12) throws Exception {
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
        trust.load(null, null);
        try (InputStream ca = stream("tls/ca.pem")) {
            trust.setCertificateEntry("ca", CertificateFactory.getInstance("X.509").generateCertificate(ca));
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);

        KeyManagerFactory kmf = null;
        if (clientP12.isPresent()) {
            KeyStore keys = KeyStore.getInstance("PKCS12");
            try (InputStream p12 = stream(clientP12.get())) {
                keys.load(p12, P12_PASSWORD);
            }
            kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keys, P12_PASSWORD);
        }
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(kmf == null ? null : kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return context;
    }

    private static InputStream stream(String name) {
        return Objects.requireNonNull(ImposterClientAuthIT.class.getClassLoader().getResourceAsStream(name), name);
    }

    private static String resource(String name) {
        try (InputStream in = stream(name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
