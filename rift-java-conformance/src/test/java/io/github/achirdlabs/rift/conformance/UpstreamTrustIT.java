package io.github.achirdlabs.rift.conformance;

import io.github.achirdlabs.rift.EmbeddedOptions;
import io.github.achirdlabs.rift.Imposter;
import io.github.achirdlabs.rift.Rift;
import io.github.achirdlabs.rift.SpawnOptions;
import io.github.achirdlabs.rift.UpstreamTrust;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import static io.github.achirdlabs.rift.conformance.LiveEngine.gated;
import static io.github.achirdlabs.rift.conformance.LiveEngine.gatedTo;
import static io.github.achirdlabs.rift.dsl.RiftDsl.imposter;
import static io.github.achirdlabs.rift.dsl.RiftDsl.onGet;
import static io.github.achirdlabs.rift.dsl.RiftDsl.onRequest;
import static io.github.achirdlabs.rift.dsl.RiftDsl.proxyTo;
import static io.github.achirdlabs.rift.dsl.RiftDsl.status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Outbound TLS trust (#209) against a live engine: a {@code proxy} stub recording an origin whose
 * certificate is issued by a private CA. The origin is itself a rift HTTPS imposter serving a leaf
 * signed by {@code tls/ca.pem}, so no public root can vouch for it — the proxy reaches it only when
 * the engine was told to trust that CA (or to skip verification).
 *
 * <p>Each case creates its imposters immediately after the engine starts. On the embedded transport
 * that ordering is the point: the engine installs the trust policy when its admin plane is served
 * and an imposter keeps the client it was created with, so an SDK that served the plane lazily would
 * leave these proxies on the default trust store.
 */
class UpstreamTrustIT {

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @TestFactory
    Stream<DynamicTest> withoutTrustTheProxyCannotReachAPrivateCaOrigin() {
        return gated("no trust option: the private-CA origin is refused", () -> {
            try (Rift rift = engine(Optional.empty())) {
                assertNotEquals(200, proxyThroughToPrivateCaOrigin(rift).statusCode(),
                        "the OS trust store does not know the test CA");
            }
        });
    }

    @TestFactory
    Stream<DynamicTest> caFileTrustsThePrivateCa() {
        return gated("upstreamCaFile: the private-CA origin is reached", () -> {
            try (Rift rift = engine(Optional.of(new UpstreamTrust.CaFile(resourcePath("tls/ca.pem"))))) {
                assertReachesOrigin(proxyThroughToPrivateCaOrigin(rift));
            }
        });
    }

    @TestFactory
    Stream<DynamicTest> caPemTrustsThePrivateCa() {
        return gatedTo(ConformanceTransport.EMBEDDED, "the rift CLI has no inline-PEM flag; spawn takes CaFile only",
                "upstreamCaPem: the private-CA origin is reached", () -> {
            try (Rift rift = engine(Optional.of(new UpstreamTrust.CaPem(resource("tls/ca.pem"))))) {
                assertReachesOrigin(proxyThroughToPrivateCaOrigin(rift));
            }
        });
    }

    @TestFactory
    Stream<DynamicTest> skipVerifyReachesAnyOrigin() {
        return gated("upstreamTlsSkipVerify: the private-CA origin is reached", () -> {
            try (Rift rift = engine(Optional.of(new UpstreamTrust.SkipVerify()))) {
                assertReachesOrigin(proxyThroughToPrivateCaOrigin(rift));
            }
        });
    }

    private static Rift engine(Optional<UpstreamTrust> trust) {
        return switch (ConformanceTransport.selected()) {
            case SPAWN -> {
                SpawnOptions.Builder b = SpawnOptions.builder();
                trust.ifPresent(b::upstreamTrust);
                yield Rift.spawn(b.build());
            }
            case EMBEDDED -> {
                EmbeddedOptions.Builder b = EmbeddedOptions.builder();
                trust.ifPresent(b::upstreamTrust);
                yield Rift.embedded(b.build());
            }
        };
    }

    private static HttpResponse<String> proxyThroughToPrivateCaOrigin(Rift rift) throws Exception {
        Imposter origin = rift.create(imposter("private-ca-origin")
                .https(resource("tls/leaf.pem"), resource("tls/leaf-key.pem"))
                .stub(onGet("/").willReturn(status(200).withTextBody("up"))));
        Imposter proxy = rift.create(imposter("proxy").protocol("http")
                .stub(onRequest().willReturn(proxyTo("https://127.0.0.1:" + origin.port()).proxyAlways())));
        return HTTP.send(HttpRequest.newBuilder(URI.create(proxy.uri() + "/")).timeout(Duration.ofSeconds(20)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static void assertReachesOrigin(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), () -> "proxied response: " + response.body());
        assertEquals("up", response.body());
    }

    private static String resource(String name) {
        try (InputStream in = Objects.requireNonNull(UpstreamTrustIT.class.getClassLoader().getResourceAsStream(name), name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path resourcePath(String name) throws URISyntaxException {
        URL url = Objects.requireNonNull(UpstreamTrustIT.class.getClassLoader().getResource(name), name);
        return Path.of(url.toURI());
    }
}
