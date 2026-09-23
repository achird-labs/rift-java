package io.github.achirdlabs.rift.conformance;

import io.github.achirdlabs.rift.Imposter;
import io.github.achirdlabs.rift.Rift;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.stream.Stream;

import static io.github.achirdlabs.rift.conformance.LiveEngine.engine;
import static io.github.achirdlabs.rift.conformance.LiveEngine.gatedTo;
import static io.github.achirdlabs.rift.dsl.RiftDsl.copyFrom;
import static io.github.achirdlabs.rift.dsl.RiftDsl.imposter;
import static io.github.achirdlabs.rift.dsl.RiftDsl.onRequest;
import static io.github.achirdlabs.rift.dsl.RiftDsl.regex;
import static io.github.achirdlabs.rift.dsl.RiftDsl.status;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Chain order is run order on rift 0.18.0 (#230). A {@code decorate} chained before a {@code copy}
 * writes a token the copy then fills; run in the engine's fixed object order (copy, then decorate)
 * the token would be left unfilled. Needs {@code RIFT_IT=1}.
 */
class BehaviorRunOrderIT {

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @TestFactory
    Stream<DynamicTest> decorateChainedBeforeCopyRunsFirst() {
        return gatedTo(ConformanceTransport.SPAWN,
                "decorate is an injection surface; --allow-injection is a spawn-CLI flag",
                "a decorate chained before a copy runs before it", () -> {
            try (Rift rift = engine()) {
                Imposter imposter = rift.create(imposter("order").protocol("http")
                        .stub(onRequest().willReturn(status(200).withTextBody("unset")
                                .decorate("function (request, response) { response.body = 'user=${id}'; }")
                                .copy(copyFrom("path").into("${id}").using(regex("[0-9]+"))))));

                HttpResponse<String> response = HTTP.send(
                        HttpRequest.newBuilder(URI.create(imposter.uri() + "/users/42")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals("user=42", response.body());
            }
        });
    }
}
