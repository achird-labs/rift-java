package io.github.achirdlabs.rift.transport;

import io.github.achirdlabs.rift.ConnectOptions;
import io.github.achirdlabs.rift.Intercept;
import io.github.achirdlabs.rift.InterceptOptions;
import io.github.achirdlabs.rift.Rift;
import io.github.achirdlabs.rift.VersionCheck;
import io.github.achirdlabs.rift.error.CommunicationError;
import io.github.achirdlabs.rift.error.InvalidDefinition;
import io.github.achirdlabs.rift.json.JsonValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link RemoteTransport} maps the intercept operations onto the admin API's {@code /intercept/*} routes. */
class RemoteInterceptTest {

    private FakeAdminServer server;
    private RemoteTransport transport;

    @BeforeEach
    void setUp() {
        server = new FakeAdminServer();
        server.respond("POST /intercept", 200, "{\"interceptPort\":9000,\"interceptUrl\":\"http://127.0.0.1:9000\"}");
        server.respond("POST /intercept/rules", 200, "{}");
        server.respond("GET /intercept/rules", 200, "[]");
        server.respond("DELETE /intercept/rules", 200, "{}");
        server.respond("DELETE /intercept", 204, "");
        server.respond("GET /intercept/ca.pem", 200, "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----");
        transport = new RemoteTransport(server.baseUri(), Optional.empty(), Duration.ofSeconds(5));
    }

    @AfterEach
    void tearDown() {
        transport.close();
        server.close();
    }

    private boolean sawRequest(String method, String path) {
        return server.received().stream().anyMatch(r -> r.method().equals(method) && r.path().equals(path));
    }

    @Test
    void startInterceptPostsToIntercept() {
        // rift >= 0.13.3 grew a real POST /intercept start route (#493); the transport uses it.
        transport.startIntercept(JsonValue.parse("{\"port\":0}"));
        assertTrue(sawRequest("POST", "/intercept"));
    }

    @Test
    void attachProbesTheListenerAndBindsToTheEndpoint() {
        try (Rift rift = Rift.connect(
                ConnectOptions.builder(server.baseUri()).versionCheck(VersionCheck.OFF).build())) {
            Intercept intercept = rift.intercept(InterceptOptions.attach("127.0.0.1", 9443));
            // Attach probes the existing listener (GET /intercept/rules), never POST /intercept.
            assertTrue(sawRequest("GET", "/intercept/rules"), "attach probes the running listener");
            assertTrue(server.received().stream().noneMatch(r -> r.path().equals("/intercept")),
                    "attach must not attempt to start a listener");
            assertEquals(9443, intercept.address().getPort());
            assertEquals("127.0.0.1", intercept.address().getHostString());
        }
    }

    @Test
    void startMapsTheEngineBindAddressThroughTheInterceptAddressSeam() {
        server.respond("POST /intercept", 200, "{\"interceptPort\":9000,\"interceptUrl\":\"http://0.0.0.0:9000\"}");
        try (Rift rift = Rift.connect(ConnectOptions.builder(server.baseUri())
                .versionCheck(VersionCheck.OFF)
                .interceptAddress(port -> InetSocketAddress.createUnresolved("localhost", port + 10000))
                .build())) {
            Intercept intercept = rift.intercept(InterceptOptions.builder().host("0.0.0.0").port(9000).build());

            assertEquals("localhost", intercept.address().getHostString());
            assertEquals(19000, intercept.address().getPort());
            assertEquals(URI.create("http://localhost:19000"), intercept.uri());
            assertEquals(List.of(new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("localhost", 19000))),
                    intercept.proxySelector().select(URI.create("https://example.com/")));
            assertEquals(Optional.of(URI.create("http://0.0.0.0:9000")), intercept.engineAddress(),
                    "the engine's own bind address is still available");
        }
    }

    @Test
    void startDefaultsToTheAdminHostWithTheEnginePort() {
        server.respond("POST /intercept", 200, "{\"interceptPort\":9000,\"interceptUrl\":\"http://0.0.0.0:9000\"}");
        try (Rift rift = Rift.connect(ConnectOptions.builder(server.baseUri()).versionCheck(VersionCheck.OFF).build())) {
            Intercept intercept = rift.intercept(InterceptOptions.builder().host("0.0.0.0").build());

            assertEquals(server.baseUri().getHost(), intercept.address().getHostString());
            assertEquals(9000, intercept.address().getPort());
        }
    }

    @Test
    void theImposterHostResolverNeverSteersTheIntercept() {
        // A gateway-style imposter resolver points at the admin port; the CONNECT listener is elsewhere.
        server.respond("POST /intercept", 200, "{\"interceptPort\":9000,\"interceptUrl\":\"http://0.0.0.0:9000\"}");
        try (Rift rift = Rift.connect(ConnectOptions.builder(server.baseUri())
                .versionCheck(VersionCheck.OFF)
                .hostResolver(port -> URI.create("http://gateway.example:2525/__rift/" + port))
                .build())) {
            Intercept intercept = rift.intercept(InterceptOptions.builder().host("0.0.0.0").build());

            assertEquals(server.baseUri().getHost(), intercept.address().getHostString());
            assertEquals(9000, intercept.address().getPort());
        }
    }

    @Test
    void aRefusedMappingStopsTheStartBeforeAnyListenerIsStarted() {
        try (Rift rift = Rift.connect(ConnectOptions.builder(server.baseUri())
                .versionCheck(VersionCheck.OFF)
                .interceptAddress(port -> {
                    throw new IllegalArgumentException("intercept port " + port + " is not exposed");
                })
                .build())) {
            IllegalArgumentException first = assertThrows(IllegalArgumentException.class,
                    () -> rift.intercept(InterceptOptions.builder().build()));
            assertEquals("intercept port 0 is not exposed", first.getMessage());
            assertTrue(server.received().stream().noneMatch(r -> r.path().equals("/intercept")),
                    "nothing may be started when the address cannot be mapped");
            // The refusal is retryable: it must not leave the one-intercept-per-engine latch set.
            assertThrows(IllegalArgumentException.class, () -> rift.intercept(InterceptOptions.builder().build()));
        }
    }

    @Test
    void startKeepsAnExplicitlyBoundAddressByDefault() {
        // Only a wildcard bind is replaced: an engine bound to a routable IP is dialled there.
        server.respond("POST /intercept", 200, "{\"interceptPort\":9000,\"interceptUrl\":\"http://10.1.2.3:9000\"}");
        try (Rift rift = Rift.connect(ConnectOptions.builder(server.baseUri()).versionCheck(VersionCheck.OFF).build())) {
            Intercept intercept = rift.intercept(InterceptOptions.builder().host("10.1.2.3").build());

            assertEquals("10.1.2.3", intercept.address().getHostString());
            assertEquals(9000, intercept.address().getPort());
        }
    }

    @Test
    void theMappingSeesTheRequestedPortBeforeTheStartThenTheBoundPort() {
        server.respond("POST /intercept", 200, "{\"interceptPort\":40123,\"interceptUrl\":\"http://0.0.0.0:40123\"}");
        List<Integer> asked = new CopyOnWriteArrayList<>();
        try (Rift rift = Rift.connect(ConnectOptions.builder(server.baseUri())
                .versionCheck(VersionCheck.OFF)
                .interceptAddress(port -> {
                    asked.add(port);
                    return InetSocketAddress.createUnresolved("localhost", port + 10000);
                })
                .build())) {
            Intercept intercept = rift.intercept(InterceptOptions.builder().host("0.0.0.0").build());

            assertEquals(List.of(0, 40123), asked);
            assertEquals(50123, intercept.address().getPort());
        }
    }

    @Test
    void aMappingThatFailsAfterTheStartStopsTheListenerAndStaysRetryable() {
        server.respond("POST /intercept", 200, "{\"interceptPort\":40123,\"interceptUrl\":\"http://0.0.0.0:40123\","
                + "\"caCertPem\":\"CERT\",\"caKeyPem\":\"SECRET-CA-KEY\"}");
        try (Rift rift = Rift.connect(failingAfterTheStart())) {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> rift.intercept(InterceptOptions.builder().host("0.0.0.0").generateCa().build()));
            assertTrue(e.getMessage().contains("http://0.0.0.0:40123"), e.getMessage());
            assertTrue(e.getMessage().contains("port 40123 is not mapped"), e.getMessage());
            assertTrue(e.getMessage().contains("stopped"), e.getMessage());
            assertFalse(e.getMessage().contains("SECRET-CA-KEY"), "a generated CA's private key must never reach an error");
            assertTrue(sawRequest("DELETE", "/intercept"), "the unusable listener is stopped, not orphaned");

            // Stopped, so the engine's one slot is free again: a retry is sent, not refused here.
            assertThrows(IllegalStateException.class, () -> rift.intercept(InterceptOptions.builder().host("0.0.0.0").build()));
            assertEquals(2, server.received().stream().filter(r -> r.method().equals("POST") && r.path().equals("/intercept")).count());
        }
    }

    @Test
    void aMappingThatFailsAfterTheStartKeepsTheListenerClaimedWhenItCannotBeStopped() {
        server.respond("POST /intercept", 200, "{\"interceptPort\":40123,\"interceptUrl\":\"http://0.0.0.0:40123\"}");
        server.respond("DELETE /intercept", 500, "{\"errors\":[{\"message\":\"boom\"}]}");
        try (Rift rift = Rift.connect(failingAfterTheStart())) {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> rift.intercept(InterceptOptions.builder().host("0.0.0.0").build()));
            assertEquals(1, e.getSuppressed().length, "the failed stop is attached, not lost");

            // Still running and the engine allows one: a retry is refused here, not sent.
            assertThrows(IllegalStateException.class, () -> rift.intercept(InterceptOptions.builder().build()));
            assertEquals(1, server.received().stream().filter(r -> r.method().equals("POST") && r.path().equals("/intercept")).count());
        }
    }

    private ConnectOptions failingAfterTheStart() {
        return ConnectOptions.builder(server.baseUri())
                .versionCheck(VersionCheck.OFF)
                .interceptAddress(port -> {
                    if (port != 0) {
                        throw new IllegalArgumentException("port " + port + " is not mapped");
                    }
                    return InetSocketAddress.createUnresolved("localhost", 1);
                })
                .build();
    }

    @Test
    void aMalformedStartResponseNeverEchoesTheGeneratedCaKey() {
        server.respond("POST /intercept", 200, "{\"interceptPort\":9000,\"caCertPem\":\"CERT\",\"caKeyPem\":\"SECRET-CA-KEY\"}");
        try (Rift rift = Rift.connect(ConnectOptions.builder(server.baseUri()).versionCheck(VersionCheck.OFF).build())) {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> rift.intercept(InterceptOptions.builder().generateCa().build()));
            assertTrue(e.getCause().getMessage().contains("caKeyPem"), "names the keys it did get");
            assertFalse(e.getMessage().contains("SECRET-CA-KEY"), e.getMessage());
            assertFalse(e.getCause().getMessage().contains("SECRET-CA-KEY"), e.getCause().getMessage());
        }
    }

    @Test
    void aRemappedIpv6HostIsBracketedInTheUri() {
        server.respond("POST /intercept", 200, "{\"interceptPort\":9000,\"interceptUrl\":\"http://[::]:9000\"}");
        try (Rift rift = Rift.connect(ConnectOptions.builder(server.baseUri())
                .versionCheck(VersionCheck.OFF)
                .interceptAddress(port -> InetSocketAddress.createUnresolved("::1", port + 10000))
                .build())) {
            Intercept intercept = rift.intercept(InterceptOptions.builder().host("::").build());

            assertEquals(URI.create("http://[::1]:19000"), intercept.uri());
        }
    }

    @Test
    void aLoopbackBindOnAConnectedEngineWarns() {
        server.respond("POST /intercept", 200, "{\"interceptPort\":9000,\"interceptUrl\":\"http://127.0.0.1:9000\"}");
        List<String> warnings = captureWarnings(() -> {
            try (Rift rift = Rift.connect(ConnectOptions.builder(server.baseUri()).versionCheck(VersionCheck.OFF).build())) {
                rift.intercept(InterceptOptions.builder().build());
            }
        });
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("127.0.0.1") && warnings.get(0).contains("host(\"0.0.0.0\")"),
                warnings.get(0));
    }

    @Test
    void aWildcardBindDoesNotWarn() {
        server.respond("POST /intercept", 200, "{\"interceptPort\":9000,\"interceptUrl\":\"http://0.0.0.0:9000\"}");
        List<String> warnings = captureWarnings(() -> {
            try (Rift rift = Rift.connect(ConnectOptions.builder(server.baseUri()).versionCheck(VersionCheck.OFF).build())) {
                rift.intercept(InterceptOptions.builder().host("0.0.0.0").build());
            }
        });
        assertEquals(List.of(), warnings);
    }

    /** {@code System.Logger} delegates to java.util.logging; captures RiftImpl's WARNING records. */
    private static List<String> captureWarnings(Runnable action) {
        Logger jul = Logger.getLogger("io.github.achirdlabs.rift.RiftImpl");
        List<String> warnings = new CopyOnWriteArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel() == Level.WARNING) {
                    warnings.add(record.getMessage());
                }
            }

            @Override
            public void flush() { }

            @Override
            public void close() { }
        };
        jul.addHandler(handler);
        try {
            action.run();
        } finally {
            jul.removeHandler(handler);
        }
        return warnings;
    }

    @Test
    void anAttachedEndpointIsNeverRemapped() {
        try (Rift rift = Rift.connect(ConnectOptions.builder(server.baseUri())
                .versionCheck(VersionCheck.OFF)
                .interceptAddress(port -> InetSocketAddress.createUnresolved("localhost", port + 10000))
                .build())) {
            Intercept intercept = rift.intercept(InterceptOptions.attach("127.0.0.1", 9443));

            assertEquals("127.0.0.1", intercept.address().getHostString());
            assertEquals(9443, intercept.address().getPort());
        }
    }

    @Test
    void attachCarriesTheSuppliedCaOnceItMatchesTheListener() throws Exception {
        server.respond("GET /intercept/ca.pem", 200, resource("test-inmemory-ca-cert.pem"));
        Intercept.CaMaterial ca = new Intercept.CaMaterial(resource("test-inmemory-ca-cert.pem"), "THE-KEY");
        try (Rift rift = connect()) {
            Intercept intercept = rift.intercept(InterceptOptions.attach("127.0.0.1", 9443, ca));

            assertEquals(Optional.of(ca), intercept.caMaterial(), "attach hands back what the caller gave the listener");
            assertTrue(sawRequest("GET", "/intercept/ca.pem"), "checked against the listener's CA");
            assertTrue(server.received().stream().noneMatch(r -> r.path().equals("/intercept")),
                    "attach never starts a listener");
        }
    }

    @Test
    void anIdenticalCertificateInADifferentPemEncodingMatches() throws Exception {
        // The engine serves its own PEM rendering; the same certificate must match whatever the line endings.
        server.respond("GET /intercept/ca.pem", 200, resource("test-inmemory-ca-cert.pem").replace("\n", "\r\n") + "\n\n");
        Intercept.CaMaterial ca = new Intercept.CaMaterial(resource("test-inmemory-ca-cert.pem"), "THE-KEY");
        try (Rift rift = connect()) {
            assertEquals(Optional.of(ca), rift.intercept(InterceptOptions.attach("127.0.0.1", 9443, ca)).caMaterial());
        }
    }

    @Test
    void attachWithACaTheListenerIsNotUsingIsRefusedAndRetryable() throws Exception {
        server.respond("GET /intercept/ca.pem", 200, resource("test-intercept-ca.pem"));
        Intercept.CaMaterial wrong = new Intercept.CaMaterial(resource("test-inmemory-ca-cert.pem"), "THE-KEY");
        try (Rift rift = connect()) {
            InvalidDefinition e = assertThrows(InvalidDefinition.class,
                    () -> rift.intercept(InterceptOptions.attach("127.0.0.1", 9443, wrong)));
            assertTrue(e.getMessage().contains("does not match"), e.getMessage());

            // Nothing was started or claimed: attaching with the listener's real CA still works.
            Intercept.CaMaterial right = new Intercept.CaMaterial(resource("test-intercept-ca.pem"), "THE-KEY");
            assertEquals(Optional.of(right), rift.intercept(InterceptOptions.attach("127.0.0.1", 9443, right)).caMaterial());
        }
    }

    @Test
    void attachWithASuppliedCertThatIsNotACertificateIsRefused() {
        try (Rift rift = connect()) {
            InvalidDefinition e = assertThrows(InvalidDefinition.class, () -> rift.intercept(
                    InterceptOptions.attach("127.0.0.1", 9443, new Intercept.CaMaterial("not a certificate", "k"))));
            assertTrue(e.getMessage().contains("certificate"), e.getMessage());
        }
    }

    @Test
    void aListenerServingSomethingOtherThanACertificateIsACommunicationError() throws Exception {
        server.respond("GET /intercept/ca.pem", 200, "not a certificate");
        Intercept.CaMaterial ca = new Intercept.CaMaterial(resource("test-inmemory-ca-cert.pem"), "THE-KEY");
        try (Rift rift = connect()) {
            CommunicationError e = assertThrows(CommunicationError.class,
                    () -> rift.intercept(InterceptOptions.attach("127.0.0.1", 9443, ca)));
            assertTrue(e.getMessage().contains("GET /intercept/ca.pem"), e.getMessage());
        }
    }

    @Test
    void attachWithoutACaHasNoCaMaterialAndChecksNothing() {
        try (Rift rift = connect()) {
            Intercept intercept = rift.intercept(InterceptOptions.attach("127.0.0.1", 9443));

            assertEquals(Optional.empty(), intercept.caMaterial());
            assertFalse(sawRequest("GET", "/intercept/ca.pem"));
        }
    }

    private Rift connect() {
        return Rift.connect(ConnectOptions.builder(server.baseUri()).versionCheck(VersionCheck.OFF).build());
    }

    private static String resource(String name) throws java.io.IOException {
        try (var in = RemoteInterceptTest.class.getClassLoader().getResourceAsStream(name)) {
            return new String(java.util.Objects.requireNonNull(in, name).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    @Test
    void stopInterceptDeletesIntercept() {
        transport.stopIntercept();
        assertTrue(sawRequest("DELETE", "/intercept"));
    }

    @Test
    void closeStopsAListenerItStarted() {
        try (Rift rift = connect()) {
            rift.intercept(InterceptOptions.builder().build()).close();

            assertTrue(sawRequest("DELETE", "/intercept"), "an owned listener is stopped");
            assertFalse(sawRequest("DELETE", "/intercept/rules"), "stopping drops the rules with it");
        }
    }

    @Test
    void closeIsIdempotent() {
        try (Rift rift = connect()) {
            Intercept intercept = rift.intercept(InterceptOptions.builder().build());
            intercept.close();
            intercept.close();

            assertEquals(1, server.received().stream().filter(r -> r.method().equals("DELETE") && r.path().equals("/intercept")).count());
        }
    }

    @Test
    void aClosedListenerCanBeStartedAgain() {
        try (Rift rift = connect()) {
            rift.intercept(InterceptOptions.builder().build()).close();
            Intercept again = rift.intercept(InterceptOptions.builder().build());

            assertEquals(9000, again.address().getPort());
            assertEquals(2, server.received().stream().filter(r -> r.method().equals("POST") && r.path().equals("/intercept")).count());
        }
    }

    @Test
    void closeOnAnAttachedHandleOnlyClearsRules() {
        // The launcher (a container, --intercept-port) owns an attached listener; closing must not stop it.
        try (Rift rift = connect()) {
            rift.intercept(InterceptOptions.attach("127.0.0.1", 9443)).close();

            assertTrue(sawRequest("DELETE", "/intercept/rules"));
            assertFalse(sawRequest("DELETE", "/intercept"));
        }
    }

    @Test
    void anAttachedListenerCanBeAttachedAgainAfterClose() {
        try (Rift rift = connect()) {
            rift.intercept(InterceptOptions.attach("127.0.0.1", 9443)).close();
            assertEquals(9443, rift.intercept(InterceptOptions.attach("127.0.0.1", 9443)).address().getPort());
        }
    }

    @Test
    void aClosedHandleRefusesRulesAndTrustButStillSaysWhereItWas() {
        try (Rift rift = connect()) {
            Intercept intercept = rift.intercept(InterceptOptions.builder().build());
            intercept.close();

            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> intercept.serve("example.com", io.github.achirdlabs.rift.dsl.RiftDsl.ok()));
            assertEquals("intercept is closed", e.getMessage());
            assertThrows(IllegalStateException.class, intercept::rules);
            assertThrows(IllegalStateException.class, intercept::clearRules);
            assertThrows(IllegalStateException.class, () -> intercept.rule().host("example.com"),
                    "the builder is refused up front, not at its terminal action");
            assertThrows(IllegalStateException.class, () -> intercept.forward("example.com", "9443"));
            assertThrows(IllegalStateException.class, () -> intercept.redirectTo("example.com", null),
                    "refused as closed before the imposter is even read");
            // A restart without a supplied CA mints a new one: a cached trust() would hand out the old anchor.
            assertThrows(IllegalStateException.class, intercept::trust);
            assertEquals(9000, intercept.address().getPort());
        }
    }

    @Test
    void aBuilderTakenBeforeTheCloseCannotAddARuleAfterIt() {
        try (Rift rift = connect()) {
            Intercept intercept = rift.intercept(InterceptOptions.builder().build());
            var builder = intercept.rule().host("example.com");
            intercept.close();

            assertThrows(IllegalStateException.class, () -> builder.serve(io.github.achirdlabs.rift.dsl.RiftDsl.ok()));
            assertThrows(IllegalStateException.class, () -> builder.forward("9443"));
            assertFalse(sawRequest("POST", "/intercept/rules"));
        }
    }

    @Test
    void aFailedStopLeavesTheHandleOpenAndTheListenerClaimed() {
        server.respond("DELETE /intercept", 500, "{\"errors\":[{\"message\":\"boom\"}]}");
        try (Rift rift = connect()) {
            Intercept intercept = rift.intercept(InterceptOptions.builder().build());
            assertThrows(RuntimeException.class, intercept::close);

            assertThrows(IllegalStateException.class, () -> rift.intercept(InterceptOptions.builder().build()),
                    "the listener may still run, so the slot stays claimed");
            server.respond("DELETE /intercept", 204, "");
            intercept.close();
            assertEquals(2, server.received().stream().filter(r -> r.method().equals("DELETE") && r.path().equals("/intercept")).count(),
                    "a failed close can be retried");
            rift.intercept(InterceptOptions.builder().build());
        }
    }

    @Test
    void addRulesPostsToInterceptRules() {
        transport.interceptAddRules(JsonValue.parse("{\"host\":\"a.example\",\"serve\":{\"statusCode\":200}}"));
        assertTrue(sawRequest("POST", "/intercept/rules"));
    }

    @Test
    void replaceRulesPutsABareArray() {
        server.respond("PUT /intercept/rules", 200, "[{\"host\":\"a.example\",\"predicates\":[],"
                + "\"action\":{\"forward\":{\"port\":1}}}]");
        JsonValue rules = JsonValue.parse("[{\"host\":\"a.example\",\"action\":{\"forward\":{\"port\":1}}}]");

        transport.interceptReplaceRules(rules);

        var put = server.received().stream().filter(r -> r.method().equals("PUT")).findFirst().orElseThrow();
        assertEquals("/intercept/rules", put.path());
        assertEquals(rules, JsonValue.parse(put.body()), "a bare array: the route takes no {\"rules\":...} wrapper");
    }

    @Test
    void aReplaceWithNoListenerIsAnError() {
        server.respond("PUT /intercept/rules", 404, "{\"error\":\"intercept listener not running\"}");
        assertThrows(io.github.achirdlabs.rift.error.RiftException.class,
                () -> transport.interceptReplaceRules(JsonValue.parse("[]")));
    }

    @Test
    void listRulesGetsInterceptRules() {
        transport.interceptListRules();
        assertTrue(sawRequest("GET", "/intercept/rules"));
    }

    @Test
    void clearRulesDeletesInterceptRules() {
        transport.interceptClearRules();
        assertTrue(sawRequest("DELETE", "/intercept/rules"));
    }

    @Test
    void caPemGetsInterceptCaPem() {
        String pem = transport.interceptCaPem();
        assertTrue(pem.contains("BEGIN CERTIFICATE"), pem);
        assertTrue(sawRequest("GET", "/intercept/ca.pem"));
    }
}
