package io.github.achirdlabs.rift.conformance;

import io.github.achirdlabs.rift.Imposter;
import io.github.achirdlabs.rift.RecordedRequest;
import io.github.achirdlabs.rift.Rift;
import io.github.achirdlabs.rift.SpawnOptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.stream.Stream;

import static io.github.achirdlabs.rift.conformance.LiveEngine.gatedTo;
import static io.github.achirdlabs.rift.dsl.RiftDsl.imposter;
import static io.github.achirdlabs.rift.dsl.RiftDsl.ok;
import static io.github.achirdlabs.rift.dsl.RiftDsl.onRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code SpawnOptions} body and journal limits reach a spawned rift 0.22.0 engine (#309). Needs {@code RIFT_IT=1}. */
class SpawnBodyLimitsIT {

    private static final String EMBEDDED_COVERED = "the embedded limits are EmbeddedOptions', covered by LaunchOptionsIT";

    @TestFactory
    Stream<DynamicTest> maxBodySizeRefusesALargerBody() {
        return gatedTo(ConformanceTransport.SPAWN, EMBEDDED_COVERED, "a body over maxBodySize gets 413", () -> {
            try (Rift rift = Rift.spawn(SpawnOptions.builder().maxBodySize(1024).build())) {
                int port = rift.create(imposter("capped").stub(onRequest().willReturn(ok()))).port();
                assertEquals(413, post(port, 2048).statusCode());
                assertEquals(200, post(port, 512).statusCode());
            }
        });
    }

    @TestFactory
    Stream<DynamicTest> journalMaxBytesOmitsALargerBody() {
        return gatedTo(ConformanceTransport.SPAWN, EMBEDDED_COVERED,
                "a body larger than the journal budget is recorded without it", () -> {
            try (Rift rift = Rift.spawn(SpawnOptions.builder().journalMaxBytes(4096).build())) {
                Imposter imposter = rift.create(imposter("journal").record().stub(onRequest().willReturn(ok())));
                assertEquals(200, post(imposter.port(), 16_384).statusCode());
                RecordedRequest recorded = imposter.recorded().get(0);
                assertTrue(recorded.bodyOmitted(), "recorded with its body (" + recorded.body().length() + " bytes)");
                assertEquals("", recorded.body());
            }
        });
    }

    private static HttpResponse<Void> post(int port, int bodyBytes) throws Exception {
        // a fresh client each time: the engine closes the connection after a 413
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/upload"))
                        .timeout(Duration.ofSeconds(5)).POST(HttpRequest.BodyPublishers.ofString("x".repeat(bodyBytes))).build(),
                HttpResponse.BodyHandlers.discarding());
    }
}
