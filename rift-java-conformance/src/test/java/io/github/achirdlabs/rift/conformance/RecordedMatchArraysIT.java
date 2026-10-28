package io.github.achirdlabs.rift.conformance;

import io.github.achirdlabs.rift.Imposter;
import io.github.achirdlabs.rift.Rift;
import io.github.achirdlabs.rift.verify.RequestMatch;
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
import static io.github.achirdlabs.rift.dsl.RiftDsl.onRequest;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code recorded(match)} answers with the engine's predicate rules (#337): from rift 0.24.0 a repeated
 * query key or header matches by Mountebank's array rules (rift#1553) and a JSON body by its subset,
 * number and missing-field rules (rift#1579). Needs {@code RIFT_IT=1}.
 */
class RecordedMatchArraysIT {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();

    private static void send(HttpRequest.Builder request) throws Exception {
        CLIENT.send(request.timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.discarding());
    }

    private static int count(Imposter imposter, String predicateArray) {
        return imposter.recorded(RequestMatch.ofJson(predicateArray)).size();
    }

    @TestFactory
    Stream<DynamicTest> arraysMatchAsTheEngineMatchesThem() {
        return gated("repeated keys and JSON-body arrays match by the engine's rules", () -> {
            try (Rift rift = engine()) {
                Imposter imposter = rift.create(imposter("arrays").record().stub(onRequest().willReturn(ok())));
                String base = imposter.uri().toString();
                send(HttpRequest.newBuilder(URI.create(base + "/q?k=a&k=b")));
                send(HttpRequest.newBuilder(URI.create(base + "/q?k=b")));
                send(HttpRequest.newBuilder(URI.create(base + "/j")).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"items\":[{\"id\":1},{\"id\":2}],\"n\":1.0}")));
                send(HttpRequest.newBuilder(URI.create(base + "/j")).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"items\":[]}")));
                send(HttpRequest.newBuilder(URI.create(base + "/h")).header("X-Tag", "x").header("X-Tag", "y"));

                assertAll(
                        () -> assertEquals(1, count(imposter, "[{\"equals\":{\"query\":{\"k\":[\"b\",\"a\"]}}}]"),
                                "an expected array is a subset of the values, in any order"),
                        () -> assertEquals(1, count(imposter, "[{\"deepEquals\":{\"query\":{\"k\":[\"b\",\"a\"]}}}]"),
                                "deepEquals compares the values as a multiset"),
                        () -> assertEquals(2, count(imposter, "[{\"equals\":{\"query\":{\"k\":\"b\"}}}]"),
                                "a scalar matches any one of a repeated key's values"),
                        () -> assertEquals(1, count(imposter,
                                "[{\"equals\":{\"path\":\"/j\",\"body\":{\"items\":[{\"id\":2}]}}}]"),
                                "an expected body array is a subset of the actual one"),
                        () -> assertEquals(1, count(imposter, "[{\"equals\":{\"path\":\"/j\",\"body\":{\"n\":1}}}]"),
                                "numbers compare by their JavaScript spelling (1.0 is 1)"),
                        () -> assertEquals(1, count(imposter, "[{\"equals\":{\"path\":\"/j\",\"body\":{\"n\":\"\"}}}]"),
                                "a missing body field reads as \"\""),
                        () -> assertEquals(1, count(imposter, "[{\"equals\":{\"headers\":{\"X-Tag\":[\"y\",\"x\"]}}}]"),
                                "an expected array over a repeated header"));
            }
        });
    }
}
