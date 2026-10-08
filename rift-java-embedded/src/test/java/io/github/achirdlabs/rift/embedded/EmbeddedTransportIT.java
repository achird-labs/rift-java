package io.github.achirdlabs.rift.embedded;

import io.github.achirdlabs.rift.error.RiftException;
import io.github.achirdlabs.rift.json.JsonArray;
import io.github.achirdlabs.rift.json.JsonObject;
import io.github.achirdlabs.rift.json.JsonString;
import io.github.achirdlabs.rift.json.JsonValue;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end integration of {@link EmbeddedTransport} over the REAL {@code librift_ffi} engine (loaded
 * named by {@code -Drift.ffi.lib} or {@code $RIFT_FFI_LIB}). Exercises the direct-FFM data plane (drive a
 * real imposter with HTTP, read it back via {@code recorded()}), flow-state and spaces, {@code buildInfo},
 * the lazily-started admin plane and the operations delegated to it, and error mapping. Gated by
 * {@link EmbeddedTestLibrary}.
 */
class EmbeddedTransportIT {

    private static Path lib;
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @BeforeAll
    static void requireLibrary() {
        lib = EmbeddedTestLibrary.require();
    }

    private static EmbeddedTransport open() {
        return EmbeddedTransport.open(lib);
    }

    /** A recording HTTP imposter that answers GET /ping with 200 "pong". */
    private static JsonValue recordingImposter() {
        return JsonValue.parse("{\"protocol\":\"http\",\"recordRequests\":true,\"stubs\":[{"
                + "\"predicates\":[{\"equals\":{\"method\":\"GET\",\"path\":\"/ping\"}}],"
                + "\"responses\":[{\"is\":{\"statusCode\":200,\"body\":\"pong\"}}]}]}");
    }

    /**
     * The engine only backs flow-state/spaces with a real store when the def declares one (uniform
     * across transports) — so an imposter exercising those APIs opts in via {@code _rift.flowState}.
     */
    private static JsonValue flowStateImposter() {
        return JsonValue.parse("{\"protocol\":\"http\",\"recordRequests\":true,"
                + "\"_rift\":{\"flowState\":{\"backend\":\"inmemory\"}},\"stubs\":[{"
                + "\"predicates\":[{\"equals\":{\"method\":\"GET\",\"path\":\"/ping\"}}],"
                + "\"responses\":[{\"is\":{\"statusCode\":200,\"body\":\"pong\"}}]}]}");
    }

    private static int portOf(JsonValue created) {
        assertTrue(created instanceof JsonObject, "createImposter returns an object");
        JsonValue port = ((JsonObject) created).get("port");
        assertTrue(port instanceof io.github.achirdlabs.rift.json.JsonNumber, "with a port");
        return ((io.github.achirdlabs.rift.json.JsonNumber) port).asInt();
    }

    /** recorded()/spaceRecorded() may return {"requests":[…]} or a bare array — normalize to the list. */
    private static List<JsonValue> requests(JsonValue recorded) {
        if (recorded instanceof JsonArray arr) {
            return arr.items();
        }
        if (recorded instanceof JsonObject obj && obj.get("requests") instanceof JsonArray arr) {
            return arr.items();
        }
        throw new AssertionError("unexpected recorded shape: " + recorded.toJson());
    }

    private static String pathOf(JsonValue request) {
        return request instanceof JsonObject o && o.get("path") instanceof JsonString s ? s.value() : null;
    }

    @Test
    void lifecycleOpensAndClosesCleanly() {
        try (EmbeddedTransport t = open()) {
            assertNotNull(t);
        }
    }

    @Test
    void buildInfoReportsAVersion() {
        try (EmbeddedTransport t = open()) {
            JsonValue info = t.buildInfo();
            assertTrue(info instanceof JsonObject o && o.get("version") instanceof JsonString v
                    && !v.value().isBlank(), "buildInfo has a non-blank version: " + info.toJson());
        }
    }

    @Test
    void dataPlaneRecordsRealTraffic() throws Exception {
        try (EmbeddedTransport t = open()) {
            int port = portOf(t.createImposter(recordingImposter()));

            HttpResponse<String> resp = HTTP.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/ping"))
                            .timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, resp.statusCode());
            assertEquals("pong", resp.body());

            List<JsonValue> recorded = requests(t.recorded(port));
            assertTrue(recorded.stream().anyMatch(r -> "/ping".equals(pathOf(r))),
                    "the GET /ping was recorded: " + t.recorded(port).toJson());

            t.deleteImposter(port);
        }
    }

    @Test
    void flowStateRoundTrips() {
        try (EmbeddedTransport t = open()) {
            int port = portOf(t.createImposter(flowStateImposter()));

            assertEquals(Optional.empty(), t.flowStateGet(port, "flow-A", "k"),
                    "absent key is empty, not an error");
            t.flowStatePut(port, "flow-A", "k", JsonValue.parse("{\"n\":1}"));
            Optional<JsonValue> got = t.flowStateGet(port, "flow-A", "k");
            assertTrue(got.isPresent(), "put value is readable");
            t.flowStateDelete(port, "flow-A", "k");
            assertEquals(Optional.empty(), t.flowStateGet(port, "flow-A", "k"), "deleted key is empty");

            t.deleteImposter(port);
        }
    }

    @Test
    void spacesRoundTrip() {
        try (EmbeddedTransport t = open()) {
            int port = portOf(t.createImposter(flowStateImposter()));

            t.spaceAddStub(port, "flow-B", JsonValue.parse(
                    "{\"predicates\":[{\"equals\":{\"path\":\"/scoped\"}}],\"responses\":[{\"is\":{\"statusCode\":204}}]}"));
            assertNotNull(t.spaceListStubs(port, "flow-B"));
            assertNotNull(t.spaceRecorded(port, "flow-B"));
            t.spaceDelete(port, "flow-B");

            t.deleteImposter(port);
        }
    }

    @Test
    void adminPlaneReachable() throws Exception {
        // Several ops have no direct C-ABI symbol — replaceAllImposters, events, and the
        // cursor/clause reads — so the loopback admin server must still start on demand.
        try (EmbeddedTransport t = open()) {
            URI admin = t.adminUri();
            assertNotNull(admin, "adminUri lazily starts the in-process admin server");
            HttpResponse<String> health = HTTP.send(
                    HttpRequest.newBuilder(admin.resolve("/imposters")).timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, health.statusCode(), "admin API is reachable");
        }
    }

    @Test
    void resetScenariosIsPerFlow() {
        // The first non-NULL flow through rift_reset_scenarios: a flow-scoped reset leaves every
        // other flow's scenario state alone, and the NULL form still resets the default flow.
        try (EmbeddedTransport t = open()) {
            int port = portOf(t.createImposter(JsonValue.parse("{\"protocol\":\"http\",\"stubs\":[{"
                    + "\"scenarioName\":\"s1\",\"requiredScenarioState\":\"Started\",\"newScenarioState\":\"open\","
                    + "\"responses\":[{\"is\":{\"statusCode\":200}}]}]}")));
            t.setScenarioState(port, "s1", "open", Optional.empty());
            t.setScenarioState(port, "s1", "open", Optional.of("flow-1"));

            t.resetScenarios(port, Optional.of("flow-1"));
            assertEquals("Started", scenarioState(t.scenarios(port, Optional.of("flow-1")), "s1"));
            assertEquals("open", scenarioState(t.scenarios(port, Optional.empty()), "s1"));

            t.resetScenarios(port, Optional.empty());
            assertEquals("Started", scenarioState(t.scenarios(port, Optional.empty()), "s1"));
        }
    }

    private static String scenarioState(JsonValue listing, String name) {
        for (JsonValue sc : ((JsonArray) ((JsonObject) listing).get("scenarios")).items()) {
            JsonObject o = (JsonObject) sc;
            if (name.equals(((JsonString) o.get("name")).value())) {
                return ((JsonString) o.get("state")).value();
            }
        }
        throw new AssertionError("no scenario '" + name + "' in " + listing.toJson());
    }

    @Test
    void v2AdminLongTailDirectFfi() throws Exception {
        // #65: the v2-admin ops now run over direct FFI (rift#491). Exercise each end-to-end against
        // the real engine — no native crash, correct data.
        try (EmbeddedTransport t = open()) {
            int port = portOf(t.createImposter(recordingImposter()));

            // list / get imposter (options form)
            assertNotNull(t.listImposters(false, false));
            JsonValue imp = t.getImposter(port, false, false);
            assertTrue(imp instanceof JsonObject, "getImposter returns the imposter object");

            // per-stub CRUD: add, get, update, delete (by index)
            t.addStub(port, JsonValue.parse(
                    "{\"predicates\":[{\"equals\":{\"path\":\"/added\"}}],\"responses\":[{\"is\":{\"statusCode\":201}}]}"));
            int added = ((JsonArray) ((JsonObject) t.getImposter(port)).get("stubs")).items().size() - 1;
            io.github.achirdlabs.rift.transport.StubAddress at =
                    new io.github.achirdlabs.rift.transport.StubAddress.ByIndex(added);
            assertNotNull(t.getStub(port, at), "getStub returns the added stub");
            t.replaceStub(port, at, JsonValue.parse(
                    "{\"predicates\":[{\"equals\":{\"path\":\"/updated\"}}],\"responses\":[{\"is\":{\"statusCode\":202}}]}"));
            t.deleteStub(port, at);

            // enable/disable (set_imposter_enabled), scenarios, clear-recorded/proxy, verify, warnings
            t.disable(port);
            t.enable(port);
            assertNotNull(t.scenarios(port, Optional.empty()));
            t.setScenarioState(port, "s1", "open", Optional.empty());
            t.setScenarioState(port, "s1", "open", Optional.of("flow-1"));
            t.resetScenarios(port, Optional.empty());
            t.clearRecorded(port);
            t.clearProxyResponses(port);
            JsonValue verify = t.verify(port, JsonValue.parse(
                    "{\"predicates\":[{\"equals\":{\"method\":\"GET\",\"path\":\"/ping\"}}]}"));
            assertTrue(verify instanceof JsonObject o && o.get("matched") != null,
                    "verify returns a {matched,total,…} envelope: " + verify.toJson());
            assertNotNull(t.stubWarnings(port), "stubWarnings returns an array");

            t.deleteImposter(port);
        }
    }

    @Test
    void directFfiMatchesLoopbackHttp() throws Exception {
        // FFI-vs-HTTP parity (the diff rift-conformance#75 needs): a direct-FFI result must equal the
        // loopback admin HTTP result for the same op, modulo the HTTP-only `_links` hypermedia (the
        // admin server injects navigational links carrying its own base URL; the direct-FFI form has
        // no URL context, so it returns the pure domain JSON — this is exactly what the parity diff
        // strips).
        try (EmbeddedTransport t = open()) {
            int port = portOf(t.createImposter(recordingImposter()));
            try (io.github.achirdlabs.rift.transport.RemoteTransport http =
                    new io.github.achirdlabs.rift.transport.RemoteTransport(
                            t.adminUri(), Optional.empty(), Duration.ofSeconds(5))) {
                assertParity("getImposter", http.getImposter(port), t.getImposter(port));
                assertParity("scenarios", http.scenarios(port, Optional.empty()),
                        t.scenarios(port, Optional.empty()));
                io.github.achirdlabs.rift.transport.StubAddress s0 =
                        new io.github.achirdlabs.rift.transport.StubAddress.ByIndex(0);
                assertParity("getStub", http.getStub(port, s0), t.getStub(port, s0));
            }
            t.deleteImposter(port);
        }
    }

    @Test
    void stubCrudByIdDirectFfi() {
        // The `{"id":"..."}` ref_json encoding (StubAddress.ById) exercised end-to-end.
        try (EmbeddedTransport t = open()) {
            int port = portOf(t.createImposter(JsonValue.parse("{\"protocol\":\"http\",\"stubs\":[{\"id\":\"s1\","
                    + "\"predicates\":[{\"equals\":{\"path\":\"/byid\"}}],\"responses\":[{\"is\":{\"statusCode\":200}}]}]}")));
            io.github.achirdlabs.rift.transport.StubAddress byId =
                    new io.github.achirdlabs.rift.transport.StubAddress.ById("s1");
            assertNotNull(t.getStub(port, byId), "getStub by id");
            t.replaceStub(port, byId, JsonValue.parse("{\"id\":\"s1\","
                    + "\"predicates\":[{\"equals\":{\"path\":\"/byid2\"}}],\"responses\":[{\"is\":{\"statusCode\":202}}]}"));
            t.deleteStub(port, byId);
            t.deleteImposter(port);
        }
    }

    @Test
    void stubWarningsReturnsWarningsAndMatchesHttp() throws Exception {
        // Two identical-predicate stubs → an exact_duplicate warning. Proves the direct rift_stub_warnings
        // symbol actually returns warnings (not just []), and that it matches the HTTP-derived form.
        try (EmbeddedTransport t = open()) {
            int port = portOf(t.createImposter(JsonValue.parse("{\"protocol\":\"http\",\"stubs\":["
                    + "{\"predicates\":[{\"equals\":{\"path\":\"/dup\"}}],\"responses\":[{\"is\":{\"statusCode\":200}}]},"
                    + "{\"predicates\":[{\"equals\":{\"path\":\"/dup\"}}],\"responses\":[{\"is\":{\"statusCode\":201}}]}]}")));
            JsonValue warnings = t.stubWarnings(port);
            assertTrue(warnings instanceof JsonArray arr && !arr.items().isEmpty(),
                    "duplicate stubs produce a warning: " + warnings.toJson());
            try (io.github.achirdlabs.rift.transport.RemoteTransport http =
                    new io.github.achirdlabs.rift.transport.RemoteTransport(
                            t.adminUri(), Optional.empty(), Duration.ofSeconds(5))) {
                assertParity("stubWarnings", http.stubWarnings(port), warnings);
            }
            t.deleteImposter(port);
        }
    }

    @Test
    void directFfiErrorMapsToRiftException() {
        // A native sentinel (NULL char* / rc != 0) must surface as a typed RiftException, not a crash.
        try (EmbeddedTransport t = open()) {
            int port = portOf(t.createImposter(recordingImposter()));
            assertThrows(RiftException.class,
                    () -> t.getStub(port, new io.github.achirdlabs.rift.transport.StubAddress.ByIndex(99)),
                    "getStub on a nonexistent index maps to a RiftException");
            t.deleteImposter(port);
        }
    }

    private static void assertParity(String op, JsonValue http, JsonValue ffi) {
        assertTrue(JsonValue.semanticEquals(stripLinks(http), stripLinks(ffi)),
                op + " FFI == HTTP (modulo _links): FFI=" + ffi.toJson() + " HTTP=" + http.toJson());
    }

    /** Recursively drops the HTTP-only {@code _links} hypermedia so FFI and HTTP forms are comparable. */
    private static JsonValue stripLinks(JsonValue v) {
        if (v instanceof JsonObject obj) {
            JsonObject.Builder b = JsonObject.builder();
            obj.fields().forEach((k, val) -> {
                if (!"_links".equals(k)) {
                    b.put(k, stripLinks(val));
                }
            });
            return b.build();
        }
        if (v instanceof JsonArray arr) {
            return new JsonArray(arr.items().stream().map(EmbeddedTransportIT::stripLinks).toList());
        }
        return v;
    }

    @Test
    void invalidDefinitionIsMappedToRiftException() {
        try (EmbeddedTransport t = open()) {
            assertThrows(RiftException.class,
                    () -> t.createImposter(JsonValue.parse("{\"protocol\":\"not-a-protocol\"}")));
        }
    }

    @Test
    void useAfterCloseThrowsCleanlyNotNativeCrash() {
        EmbeddedTransport t = open();
        t.close();
        assertThrows(IllegalStateException.class, () -> t.recorded(1),
                "a downcall after close must fail as a Java exception, never a native crash");
        t.close(); // idempotent
    }

    @Test
    void repeatedCyclesStayStable() {
        // Arena/string ownership discipline: many create/record/delete cycles must not wedge or leak.
        try (EmbeddedTransport t = open()) {
            for (int i = 0; i < 200; i++) {
                int port = portOf(t.createImposter(recordingImposter()));
                t.recorded(port);
                t.deleteImposter(port);
            }
        }
    }
}
