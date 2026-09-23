package io.github.achirdlabs.rift.embedded;

import io.github.achirdlabs.rift.EmbeddedOptions;
import io.github.achirdlabs.rift.Intercept;
import io.github.achirdlabs.rift.InterceptOptions;
import io.github.achirdlabs.rift.InterceptRule;
import io.github.achirdlabs.rift.Rift;
import io.github.achirdlabs.rift.RuleKind;
import io.github.achirdlabs.rift.dsl.RiftDsl;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Real TLS-MITM over the embedded engine: an intercept {@code serve} rule answers an HTTPS request to a
 * host the client never actually contacts. The whole flow is localhost — the client dials the intercept
 * proxy, which terminates TLS with a cert signed by the intercept CA (trusted via {@code sslContext()})
 * and returns the rule's canned response. Skips without {@code -Drift.ffi.lib}.
 */
class InterceptE2eIT {

    private static Path lib;

    @BeforeAll
    static void requireLibrary() {
        String p = System.getProperty("rift.ffi.lib");
        assumeTrue(p != null && !p.isBlank() && Files.exists(Path.of(p)),
                "set -Drift.ffi.lib to run the intercept integration test");
        lib = Path.of(p);
    }

    private static Rift embedded() {
        return Rift.embedded(EmbeddedOptions.builder().libraryPath(lib).build());
    }

    @Test
    void serveRuleInterceptsAnHttpsRequestThroughTheTrustedProxy() throws Exception {
        try (Rift rift = embedded()) {
            Intercept intercept = rift.intercept();
            try {
                // 418 is distinctive — no real host answers a plain GET with it, so it proves the MITM.
                intercept.serve("example.com", RiftDsl.status(418));

                SSLContext ssl = intercept.trust().sslContext();
                HttpClient client = HttpClient.newBuilder()
                        .sslContext(ssl)
                        .proxy(intercept.proxySelector())
                        .connectTimeout(Duration.ofSeconds(10))
                        .build();

                HttpResponse<String> resp = client.send(
                        HttpRequest.newBuilder(URI.create("https://example.com/"))
                                .timeout(Duration.ofSeconds(10)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());

                assertEquals(418, resp.statusCode(),
                        "the serve rule answered over MITM TLS, not the real example.com");
            } finally {
                intercept.close();
            }
        }
    }

    @Test
    void aRepeatedHeaderArrivesAsOneLinePerValue() throws Exception {
        try (Rift rift = embedded()) {
            Intercept intercept = rift.intercept();
            try {
                // rift 0.18.0 serves each value as its own header line (#231), which set-cookie needs.
                intercept.serve("example.com", RiftDsl.status(200).withHeader("Set-Cookie", "a=1", "b=2"));

                HttpClient client = HttpClient.newBuilder()
                        .sslContext(intercept.trust().sslContext())
                        .proxy(intercept.proxySelector())
                        .connectTimeout(Duration.ofSeconds(10))
                        .build();
                HttpResponse<String> resp = client.send(
                        HttpRequest.newBuilder(URI.create("https://example.com/"))
                                .timeout(Duration.ofSeconds(10)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());

                assertEquals(java.util.List.of("a=1", "b=2"), resp.headers().allValues("set-cookie"));
            } finally {
                intercept.close();
            }
        }
    }

    @Test
    void predicateScopedRuleMatchesOnlyItsPathThroughTheProxy() throws Exception {
        try (Rift rift = embedded()) {
            Intercept intercept = rift.intercept();
            try {
                // Serve 418 ONLY for GET /health on example.com; other paths must not match this rule.
                InterceptRule rule = intercept.rule()
                        .host("example.com")
                        .when(RiftDsl.onGet("/health"))
                        .serve(RiftDsl.status(418));
                assertEquals(1, rule.predicates().size(), "readback exposes the rule's predicate");

                SSLContext ssl = intercept.trust().sslContext();
                HttpClient client = HttpClient.newBuilder()
                        .sslContext(ssl).proxy(intercept.proxySelector())
                        .connectTimeout(Duration.ofSeconds(10)).build();

                HttpResponse<String> matched = client.send(
                        HttpRequest.newBuilder(URI.create("https://example.com/health"))
                                .timeout(Duration.ofSeconds(10)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(418, matched.statusCode(), "GET /health matched the predicate-scoped serve rule");

                HttpResponse<String> unmatched = client.send(
                        HttpRequest.newBuilder(URI.create("https://example.com/other"))
                                .timeout(Duration.ofSeconds(10)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertTrue(unmatched.statusCode() != 418,
                        "GET /other must not match the /health predicate, got " + unmatched.statusCode());
            } finally {
                intercept.close();
            }
        }
    }

    @Test
    void rulesAreListedByKindAndCleared() {
        try (Rift rift = embedded()) {
            Intercept intercept = rift.intercept();
            try {
                InterceptRule rule = intercept.serve("a.example", RiftDsl.status(200));
                assertEquals("a.example", rule.host());
                assertEquals(RuleKind.SERVE, rule.kind());
                assertTrue(intercept.rules().stream().anyMatch(r -> r.host().equals("a.example")));

                intercept.clearRules();
                assertTrue(intercept.rules().isEmpty(), "rules cleared");
            } finally {
                intercept.close();
            }
        }
    }

    @Test
    void inMemoryCaIsLoadedByTheEngine() throws Exception {
        String certPem = resource("/test-inmemory-ca-cert.pem");
        String keyPem = resource("/test-inmemory-ca-key.pem");
        try (Rift rift = embedded()) {
            // Supply the CA entirely in memory (no caller-side file); the engine must adopt it, not
            // mint an ephemeral one — so its exported CA equals the certificate we passed in.
            Intercept intercept = rift.intercept(InterceptOptions.builder().ca(certPem, keyPem).build());
            try {
                assertEquals(parseCert(certPem), parseCert(intercept.trust().caPem()),
                        "the engine's exported CA is the in-memory CA we supplied");
            } finally {
                intercept.close();
            }
        }
    }

    @Test
    void generateCaReturnsCertAndKey() throws Exception {
        try (Rift rift = embedded()) {
            Intercept intercept = rift.intercept(InterceptOptions.builder().generateCa().build());
            try {
                Intercept.CaMaterial ca = intercept.caMaterial().orElseThrow(
                        () -> new AssertionError("generateCa() must return the generated CA material"));
                assertTrue(ca.certPem().contains("BEGIN CERTIFICATE"), ca.certPem());
                assertTrue(ca.keyPem().contains("PRIVATE KEY"), "the private key is returned");
                // The returned cert is the CA the engine actually uses.
                assertEquals(parseCert(ca.certPem()), parseCert(intercept.trust().caPem()));
            } finally {
                intercept.close();
            }
        }
    }

    private static String resource(String name) throws Exception {
        try (java.io.InputStream in = InterceptE2eIT.class.getResourceAsStream(name)) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static java.security.cert.Certificate parseCert(String pem) throws Exception {
        return java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(
                new java.io.ByteArrayInputStream(pem.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    void onlyOneInterceptPerEngine() {
        try (Rift rift = embedded()) {
            Intercept first = rift.intercept();
            try {
                assertThrows(IllegalStateException.class, rift::intercept,
                        "a second intercept on the same engine is rejected");
            } finally {
                first.close();
            }
        }
    }

    @Test
    void interceptIsRetryableAfterAFailedStart() {
        try (Rift rift = embedded()) {
            // A committed-CA pair pointing at non-existent files makes the engine reject the start.
            io.github.achirdlabs.rift.InterceptOptions bad = io.github.achirdlabs.rift.InterceptOptions
                    .builder().ca(Path.of("/nonexistent/ca.pem"), Path.of("/nonexistent/key.pem")).build();
            assertThrows(io.github.achirdlabs.rift.error.RiftException.class, () -> rift.intercept(bad));

            // The failed start must not have poisoned the per-engine flag — a valid intercept works.
            Intercept ok = rift.intercept();
            ok.close();
        }
    }
}
