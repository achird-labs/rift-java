package io.github.achirdlabs.rift.conformance;

import io.github.achirdlabs.rift.EmbeddedOptions;
import io.github.achirdlabs.rift.Imposter;
import io.github.achirdlabs.rift.Rift;
import io.github.achirdlabs.rift.SpawnOptions;
import io.github.achirdlabs.rift.UpstreamTrust;
import io.github.achirdlabs.rift.dsl.ProxySpec;
import io.github.achirdlabs.rift.json.JsonString;
import io.github.achirdlabs.rift.model.Response;
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
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static io.github.achirdlabs.rift.conformance.LiveEngine.gated;
import static io.github.achirdlabs.rift.dsl.RiftDsl.imposter;
import static io.github.achirdlabs.rift.dsl.RiftDsl.onGet;
import static io.github.achirdlabs.rift.dsl.RiftDsl.onRequest;
import static io.github.achirdlabs.rift.dsl.RiftDsl.proxyTo;
import static io.github.achirdlabs.rift.dsl.RiftDsl.status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * A proxy presents a client certificate to an upstream that requires one (#342, rift#1609), with real
 * TLS handshakes. The upstream is a rift HTTPS imposter serving {@code tls/leaf.pem} and requiring a
 * client certificate issued by {@code tls/ca.pem}; the engine trusts that CA for outbound TLS. Only
 * the client certificate differs between the cases, so only it can explain a refused handshake.
 * Needs {@code RIFT_IT=1}.
 */
class ProxyClientCertificateIT {

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @TestFactory
    Stream<DynamicTest> withoutACertificateTheUpstreamRefusesTheProxy() {
        return gated("no clientCertificate: the mTLS upstream refuses the handshake", () -> {
            try (Rift rift = engine()) {
                assertNotEquals(200, proxyThroughToMutualTlsOrigin(rift, p -> p).statusCode());
            }
        });
    }

    @TestFactory
    Stream<DynamicTest> theCertificateAndKeyAreAccepted() {
        return gated("clientCertificate(cert, key): the mTLS upstream is reached", () -> {
            try (Rift rift = engine()) {
                HttpResponse<String> answer = proxyThroughToMutualTlsOrigin(rift,
                        p -> p.clientCertificate(resource("tls/client.pem"), resource("tls/client-key.pem")));
                assertEquals(200, answer.statusCode(), () -> "proxied response: " + answer.body());
                assertEquals("up", answer.body());
            }
        });
    }

    @TestFactory
    Stream<DynamicTest> anEncryptedKeyIsDecryptedWithThePassphrase() {
        return gated("clientCertificate(cert, encrypted key, passphrase): the mTLS upstream is reached", () -> {
            try (Rift rift = engine()) {
                HttpResponse<String> answer = proxyThroughToMutualTlsOrigin(rift, p -> p.clientCertificate(
                        resource("tls/client.pem"), resource("tls/client-key-encrypted.pem"), "changeit"));
                assertEquals(200, answer.statusCode(), () -> "proxied response: " + answer.body());
            }
        });
    }

    @TestFactory
    Stream<DynamicTest> theKeysReadBackFromTheEngine() {
        return gated("the engine echoes cert/key and the model keeps them", () -> {
            try (Rift rift = engine()) {
                Imposter proxy = rift.create(imposter("proxy-readback").protocol("http").stub(onRequest().willReturn(
                        proxyTo("https://127.0.0.1:1").clientCertificate(resource("tls/client.pem"), resource("tls/client-key.pem")))));
                Response.Proxy read = (Response.Proxy) proxy.definition().stubs().get(0).responses().get(0);
                assertEquals(new JsonString(resource("tls/client.pem")), read.proxy().extra().get("cert"));
                assertEquals(new JsonString(resource("tls/client-key.pem")), read.proxy().extra().get("key"));
            }
        });
    }

    private static Rift engine() throws URISyntaxException {
        UpstreamTrust trust = new UpstreamTrust.CaFile(resourcePath("tls/ca.pem"));
        return switch (ConformanceTransport.selected()) {
            case SPAWN -> Rift.spawn(SpawnOptions.builder().upstreamTrust(trust).build());
            case EMBEDDED -> Rift.embedded(EmbeddedOptions.builder().upstreamTrust(trust).build());
        };
    }

    private static HttpResponse<String> proxyThroughToMutualTlsOrigin(Rift rift, UnaryOperator<ProxySpec> client)
            throws Exception {
        Imposter origin = rift.create(imposter("mtls-origin")
                .https(resource("tls/leaf.pem"), resource("tls/leaf-key.pem"))
                .requireClientCertificate(resource("tls/ca.pem"))
                .stub(onGet("/").willReturn(status(200).withTextBody("up"))));
        Imposter proxy = rift.create(imposter("proxy").protocol("http").stub(onRequest().willReturn(
                client.apply(proxyTo("https://127.0.0.1:" + origin.port()).proxyTransparent()))));
        return HTTP.send(HttpRequest.newBuilder(URI.create(proxy.uri() + "/")).timeout(Duration.ofSeconds(20)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String resource(String name) {
        try (InputStream in = Objects.requireNonNull(
                ProxyClientCertificateIT.class.getClassLoader().getResourceAsStream(name), name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path resourcePath(String name) throws URISyntaxException {
        URL url = Objects.requireNonNull(ProxyClientCertificateIT.class.getClassLoader().getResource(name), name);
        return Path.of(url.toURI());
    }
}
