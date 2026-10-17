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
import java.util.List;
import java.util.stream.Stream;

import static io.github.achirdlabs.rift.conformance.LiveEngine.engine;
import static io.github.achirdlabs.rift.conformance.LiveEngine.gated;
import static io.github.achirdlabs.rift.dsl.RiftDsl.body;
import static io.github.achirdlabs.rift.dsl.RiftDsl.equalTo;
import static io.github.achirdlabs.rift.dsl.RiftDsl.imposter;
import static io.github.achirdlabs.rift.dsl.RiftDsl.ok;
import static io.github.achirdlabs.rift.dsl.RiftDsl.onRequest;
import static io.github.achirdlabs.rift.dsl.RiftDsl.status;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code recorded(match)} filters an XPath predicate the way the engine matched it (#330): from rift
 * 0.23.0 a selected element reads as its first child node (rift#1495). Needs {@code RIFT_IT=1}.
 */
class XPathRecordedMatchIT {

    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @TestFactory
    Stream<DynamicTest> theClientSideFilterAgreesWithTheEngine() {
        return gated("an element selection reads as the engine reads it", () -> {
            try (Rift rift = engine()) {
                Imposter imposter = rift.create(imposter("xpath").record()
                        .stub(onRequest().withPredicate(body(equalTo("x")).xPath("//a")).willReturn(ok()))
                        .stub(onRequest().willReturn(status(404))));
                for (String xml : List.of("<a>x<b/>y</a>", "<a><b/>z</a>")) {
                    HttpResponse<Void> answer = CLIENT.send(HttpRequest.newBuilder(URI.create(imposter.uri() + "/"))
                            .timeout(Duration.ofSeconds(5)).POST(HttpRequest.BodyPublishers.ofString(xml)).build(),
                            HttpResponse.BodyHandlers.discarding());
                    int matchedByEngine = answer.statusCode() == 200 ? 1 : 0;
                    assertEquals(xml.startsWith("<a>x") ? 1 : 0, matchedByEngine, "the engine's answer for " + xml);
                }
                assertEquals(1, imposter.recorded(RequestMatch.of(List.of(body(equalTo("x")).xPath("//a").build()))).size());
                assertEquals(1, imposter.recorded(RequestMatch.of(List.of(body(equalTo("undefined")).xPath("//a").build()))).size());
            }
        });
    }
}
