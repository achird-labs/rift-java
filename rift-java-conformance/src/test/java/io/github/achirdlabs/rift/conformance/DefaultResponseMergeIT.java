package io.github.achirdlabs.rift.conformance;

import io.github.achirdlabs.rift.Imposter;
import io.github.achirdlabs.rift.Rift;
import io.github.achirdlabs.rift.json.JsonValue;
import io.github.achirdlabs.rift.model.ImposterDefinition;
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
import static io.github.achirdlabs.rift.dsl.RiftDsl.response;
import static io.github.achirdlabs.rift.dsl.RiftDsl.status;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * rift 0.22.0 fills an {@code is} response's unwritten status and headers from the imposter's
 * {@code defaultResponse} (rift#1369); the SDK must neither write a status the author left out nor
 * lose that absence on a read and re-post (#302). Needs {@code RIFT_IT=1}.
 */
class DefaultResponseMergeIT {

    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @TestFactory
    Stream<DynamicTest> anUnstatedResponseTakesTheDefaultResponsesStatusAndHeaders() {
        return gated("response() takes the default's status and headers; ok() keeps its 200", () -> {
            try (Rift rift = engine()) {
                Imposter imposter = rift.create(imposter("merge")
                        .defaultResponse(status(418).withHeader("X-D", "1"))
                        .stub(onGet("/unstated").willReturn(response().withTextBody("hi")))
                        .stub(onGet("/stated").willReturn(ok().withTextBody("hi"))));

                HttpResponse<String> unstated = get(imposter.port(), "/unstated");
                assertEquals(418, unstated.statusCode());
                assertEquals("1", unstated.headers().firstValue("X-D").orElse(null));
                assertEquals("hi", unstated.body());
                assertEquals(200, get(imposter.port(), "/stated").statusCode());
            }
        });
    }

    @TestFactory
    Stream<DynamicTest> aReadAndRepostKeepsTheStatusUnwritten() {
        return gated("an is without statusCode, read back and re-posted, still takes the default's status", () -> {
            try (Rift rift = engine()) {
                Imposter original = rift.create(JsonValue.parse("{\"protocol\":\"http\",\"name\":\"raw\","
                        + "\"defaultResponse\":{\"statusCode\":418},"
                        + "\"stubs\":[{\"predicates\":[{\"equals\":{\"path\":\"/r\"}}],\"responses\":[{\"is\":{\"body\":\"hi\"}}]}]}"));
                assertEquals(418, get(original.port(), "/r").statusCode());

                ImposterDefinition readBack = original.definition();
                original.delete();
                Imposter reposted = rift.create(readBack);
                assertEquals(418, get(reposted.port(), "/r").statusCode());
            }
        });
    }

    private static HttpResponse<String> get(int port, String path) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
