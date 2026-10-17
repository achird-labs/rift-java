package io.github.achirdlabs.rift.conformance;

import io.github.achirdlabs.rift.Imposter;
import io.github.achirdlabs.rift.RecordMode;
import io.github.achirdlabs.rift.RecordSpec;
import io.github.achirdlabs.rift.Recording;
import io.github.achirdlabs.rift.Rift;
import io.github.achirdlabs.rift.dsl.RequestField;
import io.github.achirdlabs.rift.model.Stub;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import static io.github.achirdlabs.rift.conformance.LiveEngine.engine;
import static io.github.achirdlabs.rift.conformance.LiveEngine.gated;
import static io.github.achirdlabs.rift.dsl.RiftDsl.imposter;
import static io.github.achirdlabs.rift.dsl.RiftDsl.okJson;
import static io.github.achirdlabs.rift.dsl.RiftDsl.onGet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * {@code RecordSpec.ignoreHeaders} reaches the engine's predicate generator, so a proxyOnce
 * recording treats requests that differ only in an ignored header as one (#311, rift#1446): the
 * origin is hit once and one stub is recorded. Needs {@code RIFT_IT=1}.
 */
class RecordIgnoreHeadersIT {

    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @TestFactory
    Stream<DynamicTest> proxyOnceRecordsOnceAcrossDifferingIgnoredHeader() {
        return gated("three requests differing only in an ignored header reach the origin once", () -> {
            try (Rift rift = engine()) {
                Imposter origin = rift.create(imposter("origin").record()
                        .stub(onGet("/p").willReturn(okJson("{\"ok\":true}"))));
                Imposter proxy = rift.create(imposter("proxy").record());
                Recording recording = proxy.startRecording("http://127.0.0.1:" + origin.port(), RecordSpec.builder()
                        .mode(RecordMode.ONCE)
                        .generateBy(RequestField.METHOD, RequestField.PATH, RequestField.HEADERS)
                        .ignoreHeaders("x-request-id")
                        .build());

                for (String id : List.of("aaa", "bbb", "ccc")) {
                    assertEquals(200, get(proxy, id).statusCode(), id);
                }
                assertEquals(1, origin.recorded().size(), "the origin is hit once");

                List<Stub> recorded = recording.stop();
                assertEquals(1, recorded.size(), "one stub is recorded");
                assertFalse(recorded.get(0).toJson().contains("aaa"), recorded.get(0).toJson());
                assertEquals(200, get(proxy, "zzz").statusCode(), "the recording replays for any id");
            }
        });
    }

    private static HttpResponse<String> get(Imposter imposter, String requestId) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create(imposter.uri() + "/p")).timeout(Duration.ofSeconds(5))
                .header("X-Request-Id", requestId).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
