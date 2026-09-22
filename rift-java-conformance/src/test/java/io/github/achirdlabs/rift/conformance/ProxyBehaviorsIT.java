package io.github.achirdlabs.rift.conformance;

import io.github.achirdlabs.rift.Imposter;
import io.github.achirdlabs.rift.Rift;
import io.github.achirdlabs.rift.dsl.RequestField;
import io.github.achirdlabs.rift.json.JsonString;
import io.github.achirdlabs.rift.model.Response;
import io.github.achirdlabs.rift.model.Stub;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.stream.Stream;

import static io.github.achirdlabs.rift.conformance.LiveEngine.engine;
import static io.github.achirdlabs.rift.conformance.LiveEngine.gated;
import static io.github.achirdlabs.rift.conformance.LiveEngine.gatedTo;
import static io.github.achirdlabs.rift.dsl.RiftDsl.imposter;
import static io.github.achirdlabs.rift.dsl.RiftDsl.onGet;
import static io.github.achirdlabs.rift.dsl.RiftDsl.onRequest;
import static io.github.achirdlabs.rift.dsl.RiftDsl.proxyTo;
import static io.github.achirdlabs.rift.dsl.RiftDsl.status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behaviors on a {@code proxy} response actually run on a live engine (#215). Before rift 0.18.0 the
 * engine accepted the block and dropped it, which every client-side test would have missed — only a
 * real response shows whether the behavior ran. Needs no corpus, just {@code RIFT_IT=1}.
 */
class ProxyBehaviorsIT {

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private static final long REQUESTED_MS = 600;
    /** Half the requested delay separates "ran" from "dropped" (~0 ms) with a wide margin for CI jitter. */
    private static final long MIN_OBSERVED_MS = REQUESTED_MS / 2;

    private static final String APPEND_X = "function (request, response) { response.body = response.body + '-X'; }";

    @TestFactory
    Stream<DynamicTest> waitRunsOnAProxiedResponse() {
        return gated("a wait behavior delays a proxied response", () -> {
            try (Rift rift = engine()) {
                Imposter origin = rift.create(imposter("origin").protocol("http")
                        .stub(onGet("/").willReturn(status(200).withTextBody("up"))));
                Imposter proxy = rift.create(imposter("proxy").protocol("http")
                        .stub(onRequest().willReturn(proxyTo(origin.uri().toString()).proxyAlways().waitMs(REQUESTED_MS))));

                long started = System.nanoTime();
                HttpResponse<String> response = get(proxy.uri() + "/");
                long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

                assertEquals("up", response.body(), "the upstream body is served");
                assertTrue(elapsedMs >= MIN_OBSERVED_MS,
                        "the wait ran on the proxied response: took " + elapsedMs + " ms, expected >= " + MIN_OBSERVED_MS);
            }
        });
    }

    @TestFactory
    Stream<DynamicTest> decorateRunsBeforeTheResponseIsRecorded() {
        return gatedTo(ConformanceTransport.SPAWN,
                "decorate is an injection surface; --allow-injection is a spawn-CLI flag",
                "a decorate behavior rewrites the proxied response and the recording", () -> {
            try (Rift rift = engine()) {
                Imposter origin = rift.create(imposter("origin").protocol("http")
                        .stub(onGet("/").willReturn(status(200).withTextBody("up"))));
                Imposter proxy = rift.create(imposter("proxy").protocol("http")
                        .stub(onRequest().willReturn(proxyTo(origin.uri().toString())
                                .proxyOnce().generateBy(RequestField.PATH).decorate(APPEND_X))));

                assertEquals("up-X", get(proxy.uri() + "/").body(), "the live response is decorated");

                Stub recorded = proxy.definition().stubs().get(0);
                Response.Is is = assertInstanceOf(Response.Is.class, recorded.responses().get(0),
                        "proxyOnce records an is-stub in front of the proxy");
                assertEquals(new JsonString("up-X"), is.is().body().orElseThrow(),
                        "the decorated body is what was recorded");
                assertTrue(is.behaviors().isEmpty(),
                        "the proxy's own behaviors are not copied onto the recording: " + is.behaviors());

                assertEquals("up-X", get(proxy.uri() + "/").body(), "the replay is not decorated twice");
            }
        });
    }

    private static HttpResponse<String> get(String url) throws Exception {
        HttpResponse<String> response = HTTP.send(
                HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), () -> "unexpected status, body: " + response.body());
        return response;
    }
}
