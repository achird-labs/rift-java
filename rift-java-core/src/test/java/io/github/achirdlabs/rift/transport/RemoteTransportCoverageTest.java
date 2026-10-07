package io.github.achirdlabs.rift.transport;

import io.github.achirdlabs.rift.ConnectOptions;
import io.github.achirdlabs.rift.EngineInfo;
import io.github.achirdlabs.rift.Imposter;
import io.github.achirdlabs.rift.RecordedRequest;
import io.github.achirdlabs.rift.Rift;
import io.github.achirdlabs.rift.Scenarios;
import io.github.achirdlabs.rift.VersionCheck;
import io.github.achirdlabs.rift.error.CommunicationError;
import io.github.achirdlabs.rift.json.JsonValue;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

import static io.github.achirdlabs.rift.dsl.RiftDsl.imposter;
import static io.github.achirdlabs.rift.dsl.RiftDsl.ok;
import static io.github.achirdlabs.rift.dsl.RiftDsl.onGet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the transport surface beyond the error-mapping gate: stubs, scenarios, spaces, flow-state
 *  writes, async, list, WARN preflight, recorded parsing, and URL-encoding of caller-supplied segments. */
class RemoteTransportCoverageTest {

    private static final String IMP = "{\"port\":4545,\"protocol\":\"http\",\"stubs\":[]}";

    private static Rift connect(FakeAdminServer s) {
        return Rift.connect(ConnectOptions.builder(s.baseUri()).versionCheck(VersionCheck.OFF).build());
    }

    private static Imposter created(FakeAdminServer s, Rift rift) {
        s.respond("POST /imposters", 201, IMP);
        return rift.create(imposter("x").port(4545));
    }

    private static boolean hit(FakeAdminServer s, String method, String path) {
        return s.received().stream().anyMatch(r -> r.method().equals(method) && r.path().equals(path));
    }

    @Test
    void listImposters() {
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.respond("GET /imposters", 200, "{\"imposters\":[" + IMP + "]}");
            try (Rift rift = connect(s)) {
                List<Imposter> list = rift.imposters();
                assertEquals(1, list.size());
                assertEquals(4545, list.get(0).port());
            }
        }
    }

    @Test
    void stubOpsByIndexAndById() {
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.respond("GET /imposters/4545", 200, IMP); // addStub reads the definition to index the new StubRef
            s.respond("POST /imposters/4545/stubs", 200, IMP);
            s.respond("PUT /imposters/4545/stubs", 200, IMP);
            s.respond("PUT /imposters/4545/stubs/by-id/s1", 200, IMP);
            s.respond("DELETE /imposters/4545/stubs/by-id/s1", 200, IMP);
            try (Rift rift = connect(s)) {
                Imposter imp = created(s, rift);
                imp.addStub(onGet("/a").willReturn(ok()));
                imp.replaceStubs(List.of(onGet("/b").willReturn(ok())));
                imp.stub("s1").replace(onGet("/c").willReturn(ok()));
                imp.stub("s1").delete();
            }
            assertTrue(hit(s, "POST", "/imposters/4545/stubs"));
            assertTrue(hit(s, "PUT", "/imposters/4545/stubs"));
            assertTrue(hit(s, "PUT", "/imposters/4545/stubs/by-id/s1"));
            assertTrue(hit(s, "DELETE", "/imposters/4545/stubs/by-id/s1"));
        }
    }

    @Test
    void scenariosListSetReset() {
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.respond("GET /imposters/4545/scenarios", 200, "{\"scenarios\":[{\"name\":\"cart\",\"state\":\"empty\"}]}");
            s.respond("PUT /imposters/4545/scenarios/cart/state", 200, "{}");
            s.respond("POST /imposters/4545/scenarios/reset", 200, "{}");
            try (Rift rift = connect(s)) {
                Imposter imp = created(s, rift);
                List<Scenarios.State> states = imp.scenarios().list();
                assertEquals("cart", states.get(0).name());
                assertEquals("empty", states.get(0).state());
                imp.scenarios().setState("cart", "filled");
                imp.scenarios().reset();
            }
            assertTrue(hit(s, "PUT", "/imposters/4545/scenarios/cart/state"));
            assertTrue(hit(s, "POST", "/imposters/4545/scenarios/reset"));
        }
    }

    @Test
    void scenariosSetStateFlowScoped() {
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.respond("PUT /imposters/4545/scenarios/cart/state", 200, "{}");
            try (Rift rift = connect(s)) {
                Imposter imp = created(s, rift);
                imp.scenarios().setState("cart", "filled", "flow-1");
                imp.scenarios().setState("cart", "empty");
            }
            List<String> bodies = s.received().stream()
                    .filter(r -> r.method().equals("PUT")
                            && r.path().equals("/imposters/4545/scenarios/cart/state"))
                    .map(FakeAdminServer.Received::body)
                    .toList();
            assertEquals(2, bodies.size());
            // Flow-scoped write carries flowId in the PUT body alongside state.
            assertTrue(bodies.get(0).contains("\"flowId\":\"flow-1\""), bodies.get(0));
            assertTrue(bodies.get(0).contains("\"state\":\"filled\""), bodies.get(0));
            // Default (flowId-less) write must NOT emit a flowId field — the engine falls back to
            // the imposter's default flow, so a stray flowId would silently retarget the write.
            assertFalse(bodies.get(1).contains("flowId"), bodies.get(1));
            assertTrue(bodies.get(1).contains("\"state\":\"empty\""), bodies.get(1));
        }
    }

    @Test
    void spacesAddListDelete() {
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.respond("POST /imposters/4545/spaces/flow-1/stubs", 200, IMP);
            s.respond("GET /imposters/4545/spaces/flow-1/stubs", 200, "{\"stubs\":[]}");
            s.respond("DELETE /imposters/4545/spaces/flow-1", 200, "{}");
            try (Rift rift = connect(s)) {
                Imposter imp = created(s, rift);
                imp.space("flow-1").addStub(onGet("/a").willReturn(ok()));
                imp.space("flow-1").stubs();
                imp.space("flow-1").delete();
            }
            assertTrue(hit(s, "POST", "/imposters/4545/spaces/flow-1/stubs"));
            assertTrue(hit(s, "GET", "/imposters/4545/spaces/flow-1/stubs"));
            assertTrue(hit(s, "DELETE", "/imposters/4545/spaces/flow-1"));
        }
    }

    @Test
    void aConnectedEngineKeepsItsResolverWhateverTheImposterHost() {
        // The imposter's host is an address on the remote machine, not one this client can use (#243).
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.respond("POST /imposters", 201, "{\"port\":4545,\"host\":\"127.0.0.2\"}");
            try (Rift rift = connect(s)) {
                Imposter imp = rift.create(imposter("x").port(4545).host("127.0.0.2"));
                assertEquals(s.baseUri().getHost(), imp.uri().getHost());
            }
        }
    }

    @Test
    void aConnectedEngineReportsAnHttpsImposterOverHttps() {
        // The protocol, unlike the host, is an attribute of the imposter: a connected engine uses it too (#250).
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.respond("POST /imposters", 201, "{\"port\":4545}");
            try (Rift rift = connect(s)) {
                Imposter imp = rift.create("{\"port\":4545,\"protocol\":\"https\",\"stubs\":[]}");
                assertEquals(URI.create("https://" + s.baseUri().getHost() + ":4545"), imp.uri());
            }
        }
    }

    @Test
    void aConnectedLookupReadsTheProtocolFromTheImposterItFetched() {
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.respond("GET /imposters/4545", 200, "{\"port\":4545,\"protocol\":\"https\",\"stubs\":[]}");
            s.respond("GET /imposters", 200,
                    "{\"imposters\":[{\"port\":4545,\"protocol\":\"https\"},{\"port\":4546,\"protocol\":\"http\"},"
                            + "{\"port\":4547}]}");
            try (Rift rift = connect(s)) {
                String host = s.baseUri().getHost();
                assertEquals(URI.create("https://" + host + ":4545"), rift.imposter(4545).orElseThrow().uri());
                List<Imposter> listed = rift.imposters();
                assertEquals(URI.create("https://" + host + ":4545"), listed.get(0).uri());
                assertEquals(URI.create("http://" + host + ":4546"), listed.get(1).uri());
                assertEquals(URI.create("http://" + host + ":4547"), listed.get(2).uri(), "an absent protocol is http");
            }
        }
    }

    @Test
    void flowStatePutAndDelete() {
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.respond("PUT /admin/imposters/4545/flow-state/flow-1/token", 200, "{}");
            s.respond("DELETE /admin/imposters/4545/flow-state/flow-1/token", 200, "{}");
            try (Rift rift = connect(s)) {
                Imposter imp = created(s, rift);
                imp.flowState("flow-1").put("token", "abc");
                imp.flowState("flow-1").delete("token");
            }
            assertTrue(hit(s, "PUT", "/admin/imposters/4545/flow-state/flow-1/token"));
            assertTrue(hit(s, "DELETE", "/admin/imposters/4545/flow-state/flow-1/token"));
        }
    }

    @Test
    void flowStateGetReturnsTheStoredValueNotTheEnvelope() {
        // The engine answers {"flowId","key","value"} (#236); get() must return the value alone, as
        // the embedded transport already does.
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.respond("GET /admin/imposters/4545/flow-state/flow-1/token", 200,
                    "{\"flowId\":\"flow-1\",\"key\":\"token\",\"value\":{\"a\":1}}");
            s.respond("GET /admin/imposters/4545/flow-state/flow-1/hits", 200,
                    "{\"flowId\":\"flow-1\",\"key\":\"hits\",\"value\":2}");
            s.respond("GET /admin/imposters/4545/flow-state/flow-1/nothing", 200,
                    "{\"flowId\":\"flow-1\",\"key\":\"nothing\",\"value\":null}");
            try (Rift rift = connect(s)) {
                Imposter imp = created(s, rift);
                assertEquals(Optional.of(JsonValue.parse("{\"a\":1}")), imp.flowState("flow-1").get("token"));
                assertEquals(Optional.of(JsonValue.parse("2")), imp.flowState("flow-1").get("hits"));
                assertEquals(Optional.of(JsonValue.parse("null")), imp.flowState("flow-1").get("nothing"));
            }
        }
    }

    @Test
    void flowStateGetRejectsASuccessBodyWithoutAValue() {
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.respond("GET /admin/imposters/4545/flow-state/flow-1/token", 200, "{\"flowId\":\"flow-1\"}");
            try (Rift rift = connect(s)) {
                Imposter imp = created(s, rift);
                CommunicationError e = assertThrows(CommunicationError.class, () -> imp.flowState("flow-1").get("token"));
                assertTrue(e.getMessage().contains("flow-state/flow-1/token"), e.getMessage());
            }
        }
    }

    @Test
    void flowStateKeyWithSpecialCharsIsPercentEncoded() {
        try (FakeAdminServer s = new FakeAdminServer()) {
            // caller-supplied key with a space and a slash must be encoded, not mis-routed or thrown
            s.respond("GET /admin/imposters/4545/flow-state/flow-1/a%20b%2Fc", 404, "{\"errors\":[{\"code\":\"x\",\"message\":\"x\"}]}");
            try (Rift rift = connect(s)) {
                Imposter imp = created(s, rift);
                assertEquals(Optional.empty(), imp.flowState("flow-1").get("a b/c"));
            }
            assertTrue(hit(s, "GET", "/admin/imposters/4545/flow-state/flow-1/a%20b%2Fc"),
                    "the flow-state key must be percent-encoded in the request path");
        }
    }

    @Test
    void enableDisable() {
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.respond("POST /imposters/4545/enable", 200, "{}");
            s.respond("POST /imposters/4545/disable", 200, "{}");
            try (Rift rift = connect(s)) {
                Imposter imp = created(s, rift);
                imp.enable();
                imp.disable();
            }
            assertTrue(hit(s, "POST", "/imposters/4545/enable"));
            assertTrue(hit(s, "POST", "/imposters/4545/disable"));
        }
    }

    @Test
    void recordedParsesSavedRequests() {
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.respond("GET /imposters/4545/savedRequests", 200,
                    "{\"requests\":[{\"method\":\"GET\",\"path\":\"/api/x\",\"headers\":{\"Accept\":\"application/json\"},\"body\":\"hi\"}]}");
            try (Rift rift = connect(s)) {
                Imposter imp = created(s, rift);
                List<RecordedRequest> recorded = imp.recorded();
                assertEquals(1, recorded.size());
                assertEquals("GET", recorded.get(0).method());
                assertEquals("/api/x", recorded.get(0).path());
            }
        }
    }

    @Test
    void applyConfigReconcilesThroughPutImposters() {
        // POST /admin/reload never reads a body: it reloads the engine's own sources. PUT /imposters
        // reconciles toward the argument, the HTTP twin of rift_apply_config.
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.respond("PUT /imposters", 200,
                    "{\"imposters\":[],\"created\":[2],\"replaced\":[1],\"stubPatched\":[],\"deleted\":[3]}");
            try (Rift rift = connect(s)) {
                var result = rift.applyConfig(JsonValue.parse("{\"imposters\":[]}"));
                assertEquals(java.util.List.of(2), result.created());
                assertEquals(java.util.List.of(3), result.deleted());
            }
            assertTrue(s.received().stream().anyMatch(r -> r.method().equals("PUT") && r.path().equals("/imposters")));
            assertTrue(s.received().stream().noneMatch(r -> r.path().equals("/admin/reload")));
        }
    }

    @Test
    void applyConfigPartialFailureIsAReportNotAnException() {
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.respond("PUT /imposters", 500, "{\"errors\":[{\"code\":\"500\",\"type\":\"internal_error\","
                    + "\"message\":\"Replace partially failed: 19478: Address already in use\"}],"
                    + "\"failed\":[\"19478: Address already in use\"],"
                    + "\"created\":[19477],\"replaced\":[],\"stubPatched\":[],\"deleted\":[]}");
            try (Rift rift = connect(s)) {
                var result = rift.applyConfig(JsonValue.parse("{\"imposters\":[]}"));
                assertEquals(java.util.List.of(19477), result.created(), "what did apply is reported");
                assertEquals(java.util.List.of(new io.github.achirdlabs.rift.ApplyResult.ApplyFailure(
                        java.util.OptionalInt.of(19478), "Address already in use")), result.failed());
            }
        }
    }

    @Test
    void applyConfigOtherServerErrorsStillThrow() {
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.respond("PUT /imposters", 500, "{\"errors\":[{\"message\":\"boom\"}]}");
            try (Rift rift = connect(s)) {
                io.github.achirdlabs.rift.error.EngineError e = assertThrows(io.github.achirdlabs.rift.error.EngineError.class,
                        () -> rift.applyConfig(JsonValue.parse("{\"imposters\":[]}")));
                assertEquals(500, e.code());
            }
        }
    }

    @Test
    void applyConfigSendsTheConfigAsThePutBody() {
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.respond("PUT /imposters", 200, "{\"imposters\":[],\"created\":[],\"replaced\":[],\"stubPatched\":[],\"deleted\":[]}");
            try (Rift rift = connect(s)) {
                rift.applyConfig(JsonValue.parse("{\"imposters\":[{\"port\":4545,\"protocol\":\"http\"}]}"));
            }
            String sent = s.received().stream().filter(r -> r.method().equals("PUT")).findFirst().orElseThrow().body();
            assertEquals(JsonValue.parse("{\"imposters\":[{\"port\":4545,\"protocol\":\"http\"}]}"), JsonValue.parse(sent));
        }
    }

    @Test
    void applyConfigWrapsABareArrayLikeTheEmbeddedEngineAcceptsIt() {
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.respond("PUT /imposters", 200, "{\"imposters\":[],\"created\":[],\"replaced\":[],\"stubPatched\":[],\"deleted\":[]}");
            try (Rift rift = connect(s)) {
                rift.applyConfig(JsonValue.parse("[{\"port\":4545,\"protocol\":\"http\"}]"));
            }
            String sent = s.received().stream().filter(r -> r.method().equals("PUT")).findFirst().orElseThrow().body();
            assertEquals(JsonValue.parse("{\"imposters\":[{\"port\":4545,\"protocol\":\"http\"}]}"), JsonValue.parse(sent));
        }
    }

    @Test
    void applyConfigRejectedAsInvalidIsAnInvalidDefinition() {
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.respond("PUT /imposters", 400, "{\"errors\":[{\"message\":\"Invalid imposter set (imposters unchanged): dup\"}]}");
            try (Rift rift = connect(s)) {
                assertThrows(io.github.achirdlabs.rift.error.InvalidDefinition.class,
                        () -> rift.applyConfig(JsonValue.parse("{\"imposters\":[]}")));
            }
        }
    }

    @Test
    void aServerErrorThatOnlyLooksLikeAReportIsStillAnError() {
        for (String body : java.util.List.of("{\"failed\":\"not an array\"}", "not json", "[\"failed\"]")) {
            try (FakeAdminServer s = new FakeAdminServer()) {
                s.respond("PUT /imposters", 500, body);
                try (Rift rift = connect(s)) {
                    io.github.achirdlabs.rift.error.EngineError e = assertThrows(io.github.achirdlabs.rift.error.EngineError.class,
                            () -> rift.applyConfig(JsonValue.parse("{\"imposters\":[]}")), body);
                    assertEquals(500, e.code());
                }
            }
        }
    }

    @Test
    void aPartialFailureReportWithoutPortListsIsStillReported() {
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.respond("PUT /imposters", 500, "{\"failed\":[\"4545: bind failed\"]}");
            try (Rift rift = connect(s)) {
                assertEquals(1, rift.applyConfig(JsonValue.parse("{\"imposters\":[]}")).failed().size(),
                        "a failure must never be dropped as 'no report'");
            }
        }
    }

    @Test
    void applyConfigAnsweredWithABareListIsRefusedToo() {
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.respond("PUT /imposters", 200, "[{\"port\":4545}]");
            try (Rift rift = connect(s)) {
                assertThrows(io.github.achirdlabs.rift.error.EngineUnavailable.class,
                        () -> rift.applyConfig(JsonValue.parse("{\"imposters\":[]}")));
            }
        }
    }

    @Test
    void applyConfigWithoutAReportIsRefused() {
        // Engines before 0.20.0 answer PUT /imposters with the imposter list only (rift#1304 added the report).
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.respond("PUT /imposters", 200, "{\"imposters\":[{\"port\":4545,\"protocol\":\"http\"}]}");
            try (Rift rift = connect(s)) {
                io.github.achirdlabs.rift.error.EngineUnavailable e = assertThrows(
                        io.github.achirdlabs.rift.error.EngineUnavailable.class,
                        () -> rift.applyConfig(JsonValue.parse("{\"imposters\":[]}")));
                assertTrue(e.getMessage().contains("replaceAll"), e.getMessage());
                assertTrue(e.getMessage().contains("reconciled"), "it says the apply itself happened: " + e.getMessage());
                assertTrue(e.getMessage().contains("0.20.0"), "it names the engine that reports: " + e.getMessage());
            }
        }
    }

    @Test
    void infoParsesEngineInfo() {
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.respond("GET /config", 200, "{\"version\":\"0.13.1\",\"commit\":\"abc\",\"options\":{\"features\":[]}}");
            try (Rift rift = connect(s)) {
                EngineInfo info = rift.info();
                assertEquals("0.13.1", info.version());
                assertEquals("abc", info.commit());
            }
        }
    }

    @Test
    void asyncMirrorsRunOnCommonPool() throws ExecutionException, InterruptedException {
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.respond("POST /imposters", 201, IMP);
            s.respond("DELETE /imposters", 200, "{\"imposters\":[]}");
            try (Rift rift = connect(s)) {
                Imposter imp = rift.async().createAsync(imposter("x").port(4545)).get();
                assertEquals(4545, imp.port());
                rift.async().deleteAllAsync().get();
            }
        }
    }

    @Test
    void warnPreflightOldVersionConnectsWithoutThrowing() {
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.respond("GET /config", 200, "{\"version\":\"0.0.1\"}");
            s.respond("DELETE /imposters", 200, "{\"imposters\":[]}");
            try (Rift rift = Rift.connect(ConnectOptions.builder(s.baseUri()).versionCheck(VersionCheck.WARN).build())) {
                rift.deleteAll(); // old engine, WARN mode: connects and works
            }
        }
    }

    @Test
    void warnPreflightMalformedConfigConnectsWithoutThrowing() {
        try (FakeAdminServer s = new FakeAdminServer()) {
            // 200 but the body carries no "version" — WARN mode must downgrade this to a log, not hard-fail
            s.respond("GET /config", 200, "{\"unexpected\":true}");
            s.respond("DELETE /imposters", 200, "{\"imposters\":[]}");
            try (Rift rift = Rift.connect(ConnectOptions.builder(s.baseUri()).versionCheck(VersionCheck.WARN).build())) {
                rift.deleteAll();
            }
        }
    }
}
