package io.github.achirdlabs.rift.conformance;

import io.github.achirdlabs.rift.Imposter;
import io.github.achirdlabs.rift.RecordedRequest;
import io.github.achirdlabs.rift.Rift;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.OptionalInt;
import java.util.stream.Stream;

import static io.github.achirdlabs.rift.conformance.LiveEngine.engine;
import static io.github.achirdlabs.rift.conformance.LiveEngine.gated;
import static io.github.achirdlabs.rift.dsl.RiftDsl.imposter;
import static io.github.achirdlabs.rift.dsl.RiftDsl.onGet;
import static io.github.achirdlabs.rift.dsl.RiftDsl.status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** rift 0.18.0 records the status and latency a request was answered with (#228). Needs {@code RIFT_IT=1}. */
class RecordedOutcomeIT {

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @TestFactory
    Stream<DynamicTest> theJournalCarriesTheOutcome() {
        return gated("a recorded request carries the status and latency it was answered with", () -> {
            try (Rift rift = engine()) {
                Imposter imposter = rift.create(imposter("outcome").protocol("http").record()
                        .stub(onGet("/teapot").willReturn(status(418).withTextBody("short and stout").waitMs(150))));

                HttpResponse<String> response = HTTP.send(
                        HttpRequest.newBuilder(URI.create(imposter.uri() + "/teapot")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(418, response.statusCode());

                List<RecordedRequest> recorded = imposter.recorded();
                assertEquals(1, recorded.size());
                RecordedRequest request = recorded.get(0);
                assertEquals(OptionalInt.of(418), request.status());
                long latency = request.latencyMs().orElseThrow();
                assertTrue(latency >= 150, "the wait behavior is included in the latency: " + latency + " ms");
                assertTrue(request.summary().startsWith("GET /teapot → 418 in "), request.summary());
            }
        });
    }
}
