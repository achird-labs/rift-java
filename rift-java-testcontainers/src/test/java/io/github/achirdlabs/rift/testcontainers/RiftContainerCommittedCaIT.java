package io.github.achirdlabs.rift.testcontainers;

import io.github.achirdlabs.rift.Intercept;
import io.github.achirdlabs.rift.Rift;
import io.github.achirdlabs.rift.dsl.RiftDsl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A container-booted intercept listener with a committed CA, against a REAL {@code rift-proxy}: the
 * contract a containerized SUT relies on, which must trust the CA <em>before</em> it starts, so the
 * client below trusts the committed certificate it was built with, never {@code trust()}.
 * Gated on {@code RIFT_IT}.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RIFT_IT", matches = "1|true")
class RiftContainerCommittedCaIT {

    private static final Path CA_CERT = fixture("tls/intercept-ca.pem");
    private static final Path CA_KEY = fixture("tls/intercept-ca-key.pem");

    @Container
    static final RiftContainer RIFT = new RiftContainer().withInterceptPort(8888).withInterceptCa(CA_CERT, CA_KEY);

    @Test
    void aContainerBootedListenerServesTheCommittedCa() throws Exception {
        String certPem = Files.readString(CA_CERT);
        try (Rift client = RIFT.client()) {
            Intercept intercept = client.intercept(RIFT.interceptOptions());
            assertEquals(Optional.of(new Intercept.CaMaterial(certPem, Files.readString(CA_KEY))), intercept.caMaterial(),
                    "attach hands back the committed pair, to give a second container");

            // 418 is distinctive: no real host answers a plain GET with it, so it proves the MITM.
            intercept.serve("example.com", RiftDsl.status(418));
            HttpClient http = HttpClient.newBuilder()
                    .sslContext(trusting(certPem))
                    .proxy(intercept.proxySelector())
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder(URI.create("https://example.com/"))
                            .timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(418, resp.statusCode(), "the listener signs with the committed CA the SUT already trusts");
        }
    }

    /** An SSLContext over the committed certificate alone, as a SUT image would bake it in. */
    private static SSLContext trusting(String certPem) throws Exception {
        Certificate ca = CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(certPem.getBytes(StandardCharsets.UTF_8)));
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        store.setCertificateEntry("committed-ca", ca);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(store);
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(null, tmf.getTrustManagers(), null);
        return ssl;
    }

    private static Path fixture(String name) {
        try {
            return Path.of(Objects.requireNonNull(RiftContainerCommittedCaIT.class.getClassLoader().getResource(name), name).toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
