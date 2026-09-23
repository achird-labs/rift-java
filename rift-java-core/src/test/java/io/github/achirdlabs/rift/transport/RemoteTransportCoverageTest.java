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
    void applyConfigParsesResult() {
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.respond("POST /admin/reload", 200, "{\"created\":2,\"replaced\":1,\"stubPatched\":0,\"deleted\":3}");
            try (Rift rift = connect(s)) {
                var result = rift.applyConfig(JsonValue.parse("{\"imposters\":[]}"));
                assertEquals(2, result.created());
                assertEquals(3, result.deleted());
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
