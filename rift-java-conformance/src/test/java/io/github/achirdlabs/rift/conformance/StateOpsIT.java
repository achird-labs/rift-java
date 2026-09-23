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
import static io.github.achirdlabs.rift.conformance.LiveEngine.gated;
import static io.github.achirdlabs.rift.dsl.RiftDsl.imposter;
import static io.github.achirdlabs.rift.dsl.RiftDsl.ok;
import static io.github.achirdlabs.rift.dsl.RiftDsl.onGet;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Declarative {@code _rift.stateOps} actually write flow state on a live engine (#227). rift 0.17.0
 * dropped the block, which only a real response and a real store read can show. Needs {@code RIFT_IT=1}.
 */
class StateOpsIT {

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @TestFactory
    Stream<DynamicTest> opsRunAfterTheResponseIsBuilt() {
        return gated("stateOps increment, set and delete flow state after each response", () -> {
            try (Rift rift = engine()) {
                Imposter imposter = rift.create(imposter("counter").protocol("http")
                        .stub(onGet("/hit").willReturn(ok()
                                .templated()
                                .withTextBody("hits={{ state.hits }}")
                                .incrementState("hits")
                                .setState("last", "{{ request.query.id }}")))
                        .stub(onGet("/peek").willReturn(ok()
                                .templated()
                                .withTextBody("hits={{ state.hits }} last={{ state.last }}")))
                        .stub(onGet("/reset").willReturn(ok().deleteState("hits"))));

                // The body renders before the ops run, so it sees the value from the previous request.
                get(imposter.uri() + "/hit?id=a");
                assertEquals("hits=1", get(imposter.uri() + "/hit?id=b"));
                assertEquals("hits=2 last=b", get(imposter.uri() + "/peek"));

                get(imposter.uri() + "/reset");
                assertEquals("hits= last=b", get(imposter.uri() + "/peek"));
            }
        });
    }

    private static String get(String url) throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "status for " + url);
        return response.body();
    }
}
