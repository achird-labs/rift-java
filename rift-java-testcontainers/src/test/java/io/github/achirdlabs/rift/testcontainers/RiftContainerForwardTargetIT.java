package io.github.achirdlabs.rift.testcontainers;

import io.github.achirdlabs.rift.Intercept;
import io.github.achirdlabs.rift.Rift;
import io.github.achirdlabs.rift.dsl.RiftDsl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A {@code forward} rule names its target's host (rift 0.20.0, #262): the intercept listener in one
 * container forwards to an imposter in a second one, reached by its network alias, and the imposter
 * sees the SUT's original {@code Host}. Gated on {@code RIFT_IT} like the other container ITs.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RIFT_IT", matches = "1|true")
class RiftContainerForwardTargetIT {

    private static final int PARTNER_PORT = 4600;

    static final Network NETWORK = Network.newNetwork();

    @Container
    static final RiftContainer INTERCEPT = new RiftContainer().withInterceptPort(8888).withNetwork(NETWORK);

    @Container
    static final RiftContainer PARTNER = new RiftContainer().withNetwork(NETWORK).withNetworkAliases("partner-mock");

    @Test
    void forwardToANetworkAliasReachesASecondContainerWithTheOriginalHost() throws Exception {
        try (Rift partner = PARTNER.client(); Rift client = INTERCEPT.client()) {
            // Answers only a request that still carries the SUT's Host, not the forward target's.
            partner.create(RiftDsl.imposter("partner").port(PARTNER_PORT)
                    .stub(RiftDsl.onGet("/quote").withHeader("Host", "api.partner.com")
                            .willReturn(RiftDsl.status(418))));
            try (Intercept intercept = client.intercept(INTERCEPT.interceptOptions())) {
                intercept.forward("api.partner.com", "partner-mock:" + PARTNER_PORT);
                HttpClient http = HttpClient.newBuilder()
                        .sslContext(intercept.trust().sslContext())
                        .proxy(intercept.proxySelector())
                        .connectTimeout(Duration.ofSeconds(10))
                        .build();

                HttpResponse<String> resp = http.send(
                        HttpRequest.newBuilder(URI.create("https://api.partner.com/quote"))
                                .timeout(Duration.ofSeconds(10)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());

                assertEquals(418, resp.statusCode(), "the partner container's imposter answered: " + resp.body());
            }
        }
    }
}
