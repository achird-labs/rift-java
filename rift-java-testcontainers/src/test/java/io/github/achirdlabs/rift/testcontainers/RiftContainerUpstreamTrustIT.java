package io.github.achirdlabs.rift.testcontainers;

import io.github.achirdlabs.rift.Imposter;
import io.github.achirdlabs.rift.Rift;
import io.github.achirdlabs.rift.UpstreamTrust;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

import static io.github.achirdlabs.rift.dsl.RiftDsl.imposter;
import static io.github.achirdlabs.rift.dsl.RiftDsl.onGet;
import static io.github.achirdlabs.rift.dsl.RiftDsl.onRequest;
import static io.github.achirdlabs.rift.dsl.RiftDsl.proxyTo;
import static io.github.achirdlabs.rift.dsl.RiftDsl.status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Outbound TLS trust (#248) against a REAL {@code rift-proxy} container — the container-transport
 * twin of the conformance module's {@code UpstreamTrustIT}. The origin is an HTTPS imposter in the
 * same container serving a leaf issued by {@code tls/ca.pem} (copied from the conformance module's
 * test TLS material), so no public root vouches for it: the proxy stub reaches it only when the
 * container was told to trust that CA, or to skip verification. Gated on {@code RIFT_IT} like the
 * other container ITs.
 */
@EnabledIfEnvironmentVariable(named = "RIFT_IT", matches = "1|true")
class RiftContainerUpstreamTrustIT {

    private static final int PROXY_PORT = 4545;
    private static final int ORIGIN_PORT = 4546;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Test
    void withoutTrustTheProxyCannotReachAPrivateCaOrigin() throws Exception {
        HttpResponse<String> response = proxyThroughToPrivateCaOrigin(Optional.empty());
        assertNotEquals(200, response.statusCode(), "the image's trust store does not know the test CA");
    }

    @Test
    void caFileTrustsThePrivateCa() throws Exception {
        assertReachesOrigin(proxyThroughToPrivateCaOrigin(Optional.of(new UpstreamTrust.CaFile(resourcePath("tls/ca.pem")))));
    }

    @Test
    void caPemTrustsThePrivateCa() throws Exception {
        assertReachesOrigin(proxyThroughToPrivateCaOrigin(Optional.of(new UpstreamTrust.CaPem(resource("tls/ca.pem")))));
    }

    @Test
    void skipVerifyReachesAnyOrigin() throws Exception {
        assertReachesOrigin(proxyThroughToPrivateCaOrigin(Optional.of(new UpstreamTrust.SkipVerify())));
    }

    private static HttpResponse<String> proxyThroughToPrivateCaOrigin(Optional<UpstreamTrust> trust) throws Exception {
        try (RiftContainer container = new RiftContainer().withImposterPorts(PROXY_PORT)) {
            trust.ifPresent(container::withUpstreamTrust);
            container.start();
            try (Rift rift = container.client()) {
                // The origin stays unexposed: the proxy dials it inside the container, as 127.0.0.1,
                // which is what the leaf certificate names.
                rift.create(imposter("private-ca-origin").port(ORIGIN_PORT)
                        .https(resource("tls/leaf.pem"), resource("tls/leaf-key.pem"))
                        .stub(onGet("/").willReturn(status(200).withTextBody("up"))));
                Imposter proxy = rift.create(imposter("proxy").port(PROXY_PORT).protocol("http")
                        .stub(onRequest().willReturn(proxyTo("https://127.0.0.1:" + ORIGIN_PORT).proxyAlways())));
                return HTTP.send(HttpRequest.newBuilder(URI.create(proxy.uri() + "/"))
                                .timeout(Duration.ofSeconds(20)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
            }
        }
    }

    private static void assertReachesOrigin(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), () -> "proxied response: " + response.body());
        assertEquals("up", response.body());
    }

    private static String resource(String name) {
        try (InputStream in = Objects.requireNonNull(
                RiftContainerUpstreamTrustIT.class.getClassLoader().getResourceAsStream(name), name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path resourcePath(String name) throws URISyntaxException {
        URL url = Objects.requireNonNull(RiftContainerUpstreamTrustIT.class.getClassLoader().getResource(name), name);
        return Path.of(url.toURI());
    }
}
