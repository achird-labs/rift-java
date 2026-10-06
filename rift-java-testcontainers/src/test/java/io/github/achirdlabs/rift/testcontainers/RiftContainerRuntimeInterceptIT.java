package io.github.achirdlabs.rift.testcontainers;

import io.github.achirdlabs.rift.Intercept;
import io.github.achirdlabs.rift.InterceptOptions;
import io.github.achirdlabs.rift.Rift;
import io.github.achirdlabs.rift.dsl.RiftDsl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A listener started at runtime ({@code POST /intercept}) inside a REAL {@code rift-proxy} container,
 * with the caller's own CA: the port is exposed with {@link RiftContainer#withExposedInterceptPort(int)}
 * (no listener at launch, so the runtime start is not refused), and the handle reaches it through
 * Docker's port mapping rather than the engine's in-container bind address. Gated on {@code RIFT_IT}.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RIFT_IT", matches = "1|true")
class RiftContainerRuntimeInterceptIT {

    private static final int INTERCEPT_PORT = 8889;

    @Container
    static final RiftContainer RIFT = new RiftContainer().withExposedInterceptPort(INTERCEPT_PORT);

    @Test
    void runtimeStartOnAnExposedPortIsReachableThroughTheMappedPort() throws Exception {
        try (Rift client = RIFT.client()) {
            Intercept intercept = client.intercept(InterceptOptions.builder()
                    .host("0.0.0.0")
                    .port(INTERCEPT_PORT)
                    .ca(resource("tls/intercept-ca.pem"), resource("tls/intercept-ca-key.pem"))
                    .build());
            assertEquals(RIFT.getMappedPort(INTERCEPT_PORT), intercept.address().getPort(),
                    "the handle points at Docker's mapping, not the in-container bind port");
            assertEquals(INTERCEPT_PORT, intercept.engineAddress().orElseThrow().getPort());

            // 418 is distinctive: no real host answers a plain GET with it, so it proves the MITM.
            intercept.serve("example.com", RiftDsl.status(418));
            HttpClient http = HttpClient.newBuilder()
                    .sslContext(intercept.trust().sslContext())
                    .proxy(intercept.proxySelector())
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder(URI.create("https://example.com/"))
                            .timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(418, resp.statusCode(),
                    "the runtime-started listener answered over MITM TLS signed by the caller's CA");
        }
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = Objects.requireNonNull(
                RiftContainerRuntimeInterceptIT.class.getClassLoader().getResourceAsStream(name), name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
