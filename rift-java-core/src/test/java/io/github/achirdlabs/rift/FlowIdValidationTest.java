package io.github.achirdlabs.rift;

import io.github.achirdlabs.rift.json.JsonObject;
import io.github.achirdlabs.rift.json.JsonValue;
import io.github.achirdlabs.rift.transport.RiftTransport;
import io.github.achirdlabs.rift.transport.StubAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.net.URI;
import java.util.Optional;

import static io.github.achirdlabs.rift.dsl.RiftDsl.ok;
import static io.github.achirdlabs.rift.dsl.RiftDsl.onGet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every caller-supplied {@code flowId} entering the SDK is rejected when null or blank (#153): a
 * blank id is never the default flow — the engine routes it to a distinct, silently-wrong partition
 * (and, on the flow-state DELETE path, a destructive misroute). The rejection lives at the facade
 * boundary; a non-blank id (even a weird one like {@code " x"}) still passes through verbatim.
 */
class FlowIdValidationTest {

    private static final int PORT = 4545;

    private static Imposter imposter(RiftTransport transport) {
        return new ImposterImpl(PORT, transport, ConnectOptions.builder(URI.create("http://127.0.0.1:2525")).build());
    }

    /** Rejects blank ("" and whitespace-only) with IAE, and null with NPE — the per-point contract. */
    private static void assertRejects(Executable withEmpty, Executable withWhitespace, Executable withNull) {
        IllegalArgumentException empty = assertThrows(IllegalArgumentException.class, withEmpty);
        assertTrue(empty.getMessage().toLowerCase().contains("blank"), empty.getMessage());
        assertThrows(IllegalArgumentException.class, withWhitespace);
        assertThrows(NullPointerException.class, withNull);
    }

    @Test
    void flowIdsRequireContract() {
        IllegalArgumentException blank =
                assertThrows(IllegalArgumentException.class, () -> FlowIds.require(""));
        assertTrue(blank.getMessage().toLowerCase().contains("blank"), blank.getMessage());
        assertThrows(IllegalArgumentException.class, () -> FlowIds.require("   "));
        NullPointerException nil = assertThrows(NullPointerException.class, () -> FlowIds.require(null));
        assertEquals("flowId", nil.getMessage());
        // Non-blank passes through verbatim — no trim, no normalization.
        assertEquals(" x", FlowIds.require(" x"));
    }

    @Test
    void spaceRejectsBlankAndNullFlowId() {
        Imposter imp = imposter(new ThrowingTransport());
        assertRejects(() -> imp.space(""), () -> imp.space("   "), () -> imp.space(null));
    }

    @Test
    void flowStateRejectsBlankAndNullFlowId() {
        Imposter imp = imposter(new ThrowingTransport());
        assertRejects(() -> imp.flowState(""), () -> imp.flowState("   "), () -> imp.flowState(null));
    }

    @Test
    void scenariosListRejectsBlankAndNullFlowId() {
        Scenarios sc = imposter(new ThrowingTransport()).scenarios();
        assertRejects(() -> sc.list(""), () -> sc.list("   "), () -> sc.list(null));
    }

    @Test
    void scenariosResetRejectsBlankAndNullFlowId() {
        Scenarios sc = imposter(new ThrowingTransport()).scenarios();
        assertRejects(() -> sc.reset(""), () -> sc.reset("   "), () -> sc.reset(null));
    }

    @Test
    void scenariosSetStateRejectsBlankAndNullFlowId() {
        Scenarios sc = imposter(new ThrowingTransport()).scenarios();
        assertRejects(
                () -> sc.setState("s", "open", ""),
                () -> sc.setState("s", "open", "   "),
                () -> sc.setState("s", "open", null));
    }

    @Test
    void matchClauseFlowIdRejectsBlankAndNull() {
        assertRejects(
                () -> MatchClause.flowId(""),
                () -> MatchClause.flowId("   "),
                () -> MatchClause.flowId(null));
    }

    @Test
    void stubSpecInSpaceRejectsBlankAndNull() {
        assertRejects(
                () -> onGet("/a").willReturn(ok()).inSpace(""),
                () -> onGet("/a").willReturn(ok()).inSpace("   "),
                () -> onGet("/a").willReturn(ok()).inSpace(null));
    }

    @Test
    void nonBlankFlowIdWithLeadingWhitespacePassesVerbatimToTheWire() {
        CapturingTransport tx = new CapturingTransport();
        imposter(tx).scenarios().list(" x");
        // A real-but-odd partition id must reach the transport unchanged — the engine treats ids verbatim.
        assertEquals(Optional.of(" x"), tx.lastScenariosFlowId);
        // And a non-blank id is not rejected at construction either.
        assertEquals(" x", ((MatchClause.FlowId) MatchClause.flowId(" x")).value());
    }

    /** Every abstract op throws — so a validation test that reaches the transport is a test failure. */
    private static class ThrowingTransport implements RiftTransport {
        @Override public JsonValue createImposter(JsonValue def) { throw new UnsupportedOperationException(); }
        @Override public JsonValue getImposter(int port) { throw new UnsupportedOperationException(); }
        @Override public JsonValue getImposter(int port, boolean replayable, boolean removeProxies) { throw new UnsupportedOperationException(); }
        @Override public void deleteImposter(int port) { throw new UnsupportedOperationException(); }
        @Override public void deleteAll() { throw new UnsupportedOperationException(); }
        @Override public JsonValue listImposters(boolean r, boolean p) { throw new UnsupportedOperationException(); }
        @Override public void replaceAllImposters(JsonValue doc) { throw new UnsupportedOperationException(); }
        @Override public JsonValue applyConfig(JsonValue config) { throw new UnsupportedOperationException(); }
        @Override public void addStub(int port, JsonValue stub) { throw new UnsupportedOperationException(); }
        @Override public void replaceStubs(int port, JsonValue stubs) { throw new UnsupportedOperationException(); }
        @Override public void replaceStub(int port, StubAddress a, JsonValue s) { throw new UnsupportedOperationException(); }
        @Override public void deleteStub(int port, StubAddress a) { throw new UnsupportedOperationException(); }
        @Override public JsonValue recorded(int port) { throw new UnsupportedOperationException(); }
        @Override public void clearRecorded(int port) { throw new UnsupportedOperationException(); }
        @Override public void clearProxyResponses(int port) { throw new UnsupportedOperationException(); }
        @Override public void enable(int port) { throw new UnsupportedOperationException(); }
        @Override public void disable(int port) { throw new UnsupportedOperationException(); }
        @Override public JsonValue scenarios(int port, Optional<String> f) { throw new UnsupportedOperationException(); }
        @Override public void setScenarioState(int port, String n, String s, Optional<String> f) { throw new UnsupportedOperationException(); }
        @Override public void resetScenarios(int port, Optional<String> flowId) { throw new UnsupportedOperationException(); }
        @Override public Optional<JsonValue> flowStateGet(int port, String f, String k) { throw new UnsupportedOperationException(); }
        @Override public void flowStatePut(int port, String f, String k, JsonValue v) { throw new UnsupportedOperationException(); }
        @Override public void flowStateDelete(int port, String f, String k) { throw new UnsupportedOperationException(); }
        @Override public void spaceAddStub(int port, String f, JsonValue s) { throw new UnsupportedOperationException(); }
        @Override public JsonValue spaceListStubs(int port, String f) { throw new UnsupportedOperationException(); }
        @Override public JsonValue spaceRecorded(int port, String f) { throw new UnsupportedOperationException(); }
        @Override public void spaceDelete(int port, String f) { throw new UnsupportedOperationException(); }
        @Override public JsonValue buildInfo() { throw new UnsupportedOperationException(); }
        @Override public URI adminUri() { throw new UnsupportedOperationException(); }
        @Override public JsonValue startIntercept(JsonValue o) { throw new UnsupportedOperationException(); }
        @Override public void interceptAddRules(JsonValue r) { throw new UnsupportedOperationException(); }
        @Override public JsonValue interceptListRules() { throw new UnsupportedOperationException(); }
        @Override public void interceptClearRules() { throw new UnsupportedOperationException(); }
        @Override public String interceptCaPem() { throw new UnsupportedOperationException(); }
        @Override public void close() { }
    }

    /** Records the flowId handed to {@code scenarios()} so a verbatim pass-through can be asserted. */
    private static final class CapturingTransport extends ThrowingTransport {
        Optional<String> lastScenariosFlowId;
        @Override public JsonValue scenarios(int port, Optional<String> f) {
            this.lastScenariosFlowId = f;
            return JsonObject.builder().build();
        }
    }
}
