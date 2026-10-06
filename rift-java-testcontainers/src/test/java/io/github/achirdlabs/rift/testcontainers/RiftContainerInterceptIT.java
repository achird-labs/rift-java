package io.github.achirdlabs.rift.testcontainers;

import io.github.achirdlabs.rift.Imposter;
import io.github.achirdlabs.rift.Intercept;
import io.github.achirdlabs.rift.Rift;
import io.github.achirdlabs.rift.dsl.RiftDsl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.net.ssl.SSLContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * End-to-end intercept against a REAL {@code rift-proxy} container: the engine starts its TLS-MITM
 * listener at launch (via {@link RiftContainer#withInterceptPort(int)} → {@code RIFT_INTERCEPT_PORT}),
 * and the client attaches to the mapped port ({@link RiftContainer#interceptOptions()}) rather than
 * starting a new one. Gated on {@code RIFT_IT} like the other container ITs.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RIFT_IT", matches = "1|true")
class RiftContainerInterceptIT {

    @Container
    static final RiftContainer INTERCEPT = new RiftContainer().withInterceptPort(8888);

    @Test
    void attachedInterceptServesHttpsThroughTheContainerListener() throws Exception {
        try (Rift client = INTERCEPT.client()) {
            Intercept intercept = client.intercept(INTERCEPT.interceptOptions());
            // 418 is distinctive — no real host answers a plain GET with it, so it proves the MITM.
            intercept.serve("example.com", RiftDsl.status(418));

            SSLContext ssl = intercept.trust().sslContext();
            HttpClient http = HttpClient.newBuilder()
                    .sslContext(ssl)
                    .proxy(intercept.proxySelector())
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder(URI.create("https://example.com/"))
                            .timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(418, resp.statusCode(),
                    "the attached intercept answered over MITM TLS through the container's mapped listener");
        }
    }

    /**
     * The flow {@code examples/optimizely-datafile} shows, against a container: with {@code redirectTo}
     * pointing at an imposter, {@code replaceStubs} is the datafile swap, with no rule re-issued.
     */
    @Test
    void redirectAndReplaceStubsSwapsTheServedDatafile() throws Exception {
        try (Rift client = INTERCEPT.client()) {
            Imposter cdn = client.create(RiftDsl.imposter("optimizely-cdn")
                    .stub(RiftDsl.onGet("/datafiles/acct-1.json").willReturn(RiftDsl.okJson("{\"revision\":\"42\"}"))));
            try (Intercept intercept = client.intercept(INTERCEPT.interceptOptions())) {
                intercept.redirectTo("cdn.optimizely.com", cdn);
                HttpClient http = HttpClient.newBuilder()
                        .sslContext(intercept.trust().sslContext())
                        .proxy(intercept.proxySelector())
                        .connectTimeout(Duration.ofSeconds(10))
                        .build();

                assertEquals("{\"revision\":\"42\"}", datafile(http));
                cdn.replaceStubs(List.of(RiftDsl.onGet("/datafiles/acct-1.json")
                        .willReturn(RiftDsl.okJson("{\"revision\":\"43\"}"))));
                assertEquals("{\"revision\":\"43\"}", datafile(http), "the swap is served through the same redirect");
            }
        }
    }

    private static String datafile(HttpClient http) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("https://cdn.optimizely.com/datafiles/acct-1.json"))
                        .timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }
}
