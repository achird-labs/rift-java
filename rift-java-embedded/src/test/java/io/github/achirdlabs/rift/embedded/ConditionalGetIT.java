package io.github.achirdlabs.rift.embedded;

import io.github.achirdlabs.rift.EmbeddedOptions;
import io.github.achirdlabs.rift.Imposter;
import io.github.achirdlabs.rift.Rift;
import io.github.achirdlabs.rift.dsl.RiftDsl;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code IsSpec.conditional()} against the embedded engine (rift 0.20.0, #263): a GET carries an
 * {@code ETag} and a {@code Last-Modified}, and a request presenting either back gets a bodyless 304.
 * Gated by {@link EmbeddedTestLibrary}.
 */
class ConditionalGetIT {

    private static Path lib;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @BeforeAll
    static void requireLibrary() {
        lib = EmbeddedTestLibrary.require();
    }

    private static Rift embedded() {
        return Rift.embedded(EmbeddedOptions.builder().libraryPath(lib).build());
    }

    @Test
    void aGetPresentingTheValidatorsBackIsAnsweredNotModified() throws Exception {
        try (Rift rift = embedded()) {
            Imposter cdn = rift.create(RiftDsl.imposter("cdn")
                    .stub(RiftDsl.onGet("/datafile").willReturn(RiftDsl.okJson("{\"revision\":\"42\"}").conditional())));
            URI datafile = cdn.uri().resolve("/datafile");

            HttpResponse<String> first = get(datafile);
            assertEquals(200, first.statusCode());
            String etag = first.headers().firstValue("ETag").orElseThrow();
            String lastModified = first.headers().firstValue("Last-Modified").orElseThrow();
            assertTrue(etag.startsWith("\"fnv1a64-"), etag);

            HttpResponse<String> byEtag = get(datafile, "If-None-Match", etag);
            assertEquals(304, byEtag.statusCode());
            assertEquals("", byEtag.body(), "a 304 has no body");
            assertEquals(etag, byEtag.headers().firstValue("ETag").orElseThrow());

            assertEquals(304, get(datafile, "If-Modified-Since", lastModified).statusCode());
            assertEquals(200, get(datafile, "If-None-Match", "\"fnv1a64-0000000000000000\"").statusCode(),
                    "a stale tag gets the body");
        }
    }

    @Test
    void aFixedLastModifiedIsServedAsWrittenAndTheEtagCanBeOff() throws Exception {
        try (Rift rift = embedded()) {
            Imposter cdn = rift.create(RiftDsl.imposter("cdn").stub(RiftDsl.onGet("/").willReturn(
                    RiftDsl.ok().withTextBody("v1").conditionalWithoutEtag(Instant.parse("2026-01-05T08:09:10Z")))));

            HttpResponse<String> first = get(cdn.uri());
            assertEquals("Mon, 05 Jan 2026 08:09:10 GMT", first.headers().firstValue("Last-Modified").orElseThrow());
            assertFalse(first.headers().firstValue("ETag").isPresent(), "the ETag is off");
            assertEquals(304, get(cdn.uri(), "If-Modified-Since", "Mon, 05 Jan 2026 08:09:10 GMT").statusCode());
        }
    }

    @Test
    void aBodyChangeInTheSameSecondIsNotAnsweredNotModified() throws Exception {
        // rift 0.20.0 (rift#1301) stamps a change inside the previous stamp's second one second later,
        // so a client revalidating with If-Modified-Since alone still gets the new body.
        try (Rift rift = embedded()) {
            Imposter cdn = rift.create(RiftDsl.imposter("cdn")
                    .stub(RiftDsl.onGet("/").willReturn(RiftDsl.ok().withTextBody("v1").conditional())));
            String lastModified = get(cdn.uri()).headers().firstValue("Last-Modified").orElseThrow();

            cdn.replaceStubs(List.of(RiftDsl.onGet("/").willReturn(RiftDsl.ok().withTextBody("v2").conditional())));

            HttpResponse<String> after = get(cdn.uri(), "If-Modified-Since", lastModified);
            assertEquals(200, after.statusCode());
            assertEquals("v2", after.body());
        }
    }

    @Test
    void aHeadIsConditionalAndANon2xxIsNot() throws Exception {
        try (Rift rift = embedded()) {
            Imposter api = rift.create(RiftDsl.imposter("api")
                    .stub(RiftDsl.onGet("/missing").willReturn(RiftDsl.notFound().conditional()))
                    .stub(RiftDsl.onRequest().willReturn(RiftDsl.ok().withTextBody("x").conditional())));
            HttpResponse<Void> head = HTTP.send(HttpRequest.newBuilder(api.uri())
                            .timeout(Duration.ofSeconds(10)).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.discarding());
            String etag = head.headers().firstValue("ETag").orElseThrow();
            assertEquals(304, HTTP.send(HttpRequest.newBuilder(api.uri()).timeout(Duration.ofSeconds(10))
                            .method("HEAD", HttpRequest.BodyPublishers.noBody()).header("If-None-Match", etag).build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode());

            HttpResponse<String> missing = get(api.uri().resolve("/missing"));
            assertEquals(404, missing.statusCode());
            assertFalse(missing.headers().firstValue("ETag").isPresent(), "only a 2xx carries validators");
        }
    }

    @Test
    void aPostIsServedWithoutValidators() throws Exception {
        try (Rift rift = embedded()) {
            Imposter api = rift.create(RiftDsl.imposter("api")
                    .stub(RiftDsl.onRequest().willReturn(RiftDsl.ok().withTextBody("x").conditional())));
            HttpResponse<String> post = HTTP.send(HttpRequest.newBuilder(api.uri())
                            .timeout(Duration.ofSeconds(10)).POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, post.statusCode());
            assertFalse(post.headers().firstValue("ETag").isPresent(), "conditional applies to GET/HEAD only");
        }
    }

    private static HttpResponse<String> get(URI uri, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET();
        if (headers.length > 0) {
            request.headers(headers);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
