package io.github.achirdlabs.rift.conformance;

import io.github.achirdlabs.rift.Rift;
import io.github.achirdlabs.rift.SpawnOptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.stream.Stream;

import static io.github.achirdlabs.rift.conformance.LiveEngine.gatedTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@code SpawnOptions.apiKey}: the spawned engine requires the key and the client sends it (#287). Needs {@code RIFT_IT=1}. */
class SpawnApiKeyIT {

    @TestFactory
    Stream<DynamicTest> theSpawnedEngineIsLockedToItsOwnClient() {
        return gatedTo(ConformanceTransport.SPAWN, "the admin key of an embedded engine is EmbeddedOptions.apiKey, covered there",
                "a key-protected spawned engine serves its client and refuses an unauthenticated caller", () -> {
            try (Rift rift = Rift.spawn(SpawnOptions.builder().apiKey("conformance-key").build())) {
                assertEquals(0, rift.imposters().size(), "the client authenticates itself");

                HttpResponse<Void> anonymous = HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(rift.adminUri().resolve("/imposters")).timeout(Duration.ofSeconds(5)).GET().build(),
                        HttpResponse.BodyHandlers.discarding());
                assertEquals(401, anonymous.statusCode());
            }
        });
    }
}
