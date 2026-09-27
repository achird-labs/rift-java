package io.github.achirdlabs.rift.testcontainers;

import io.github.achirdlabs.rift.Imposter;
import io.github.achirdlabs.rift.Rift;
import io.github.achirdlabs.rift.dsl.ImposterSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

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

import static io.github.achirdlabs.rift.dsl.RiftDsl.imposter;
import static io.github.achirdlabs.rift.dsl.RiftDsl.okJson;
import static io.github.achirdlabs.rift.dsl.RiftDsl.onGet;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The scheme of an https imposter's {@code uri()} through a container (#250): direct mode reaches
 * the imposter's own TLS listener, so it is {@code https}; gateway mode reaches it through the admin
 * listener, so it stays {@code http}. The imposter serves {@code tls/leaf.pem} (SAN {@code
 * 127.0.0.1}, {@code localhost}), issued by {@code tls/ca.pem}. Gated on {@code RIFT_IT}.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RIFT_IT", matches = "1|true")
class RiftContainerHttpsUriIT {

    @Container
    static final RiftContainer DIRECT = new RiftContainer().withImposterPorts(4545);

    @Container
    static final RiftContainer GATEWAY = new RiftContainer().withGateway();

    @Test
    void directModeReportsAndServesHttps() throws Exception {
        try (Rift client = DIRECT.client()) {
            Imposter users = client.create(httpsUsers());

            URI uri = users.uri();
            assertEquals("https", uri.getScheme());
            assertEquals(DIRECT.getMappedPort(4545), uri.getPort());
            assertEquals(200, get(uri + "/u/1").statusCode());
            assertEquals("https", client.imposter(4545).orElseThrow().uri().getScheme());
        }
    }

    @Test
    void gatewayModeStaysHttpForAnHttpsImposter() throws Exception {
        try (Rift client = GATEWAY.client()) {
            Imposter users = client.create(httpsUsers());

            URI uri = users.uri();
            assertEquals("http", uri.getScheme());
            assertEquals("/__rift/4545", uri.getPath());
            assertEquals(200, get(uri + "/u/1").statusCode(), "the gateway hands plain HTTP to the https imposter");
        }
    }

    private static ImposterSpec httpsUsers() {
        return imposter("users").port(4545)
                .https(resource("tls/leaf.pem"), resource("tls/leaf-key.pem"))
                .stub(onGet("/u/1").willReturn(okJson("{\"id\":1}")));
    }

    private static HttpResponse<String> get(String url) throws Exception {
        HttpClient client = HttpClient.newBuilder().sslContext(trustingTestCa()).build();
        return client.send(
                HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static SSLContext trustingTestCa() throws Exception {
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
        trust.load(null, null);
        try (InputStream ca = Objects.requireNonNull(
                RiftContainerHttpsUriIT.class.getClassLoader().getResourceAsStream("tls/ca.pem"), "tls/ca.pem")) {
            trust.setCertificateEntry("ca", CertificateFactory.getInstance("X.509").generateCertificate(ca));
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, tmf.getTrustManagers(), null);
        return context;
    }

    private static String resource(String name) {
        try (InputStream in = Objects.requireNonNull(
                RiftContainerHttpsUriIT.class.getClassLoader().getResourceAsStream(name), name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
