package io.github.achirdlabs.rift;

import io.github.achirdlabs.rift.json.JsonValue;
import io.github.achirdlabs.rift.transport.RiftTransport;
import io.github.achirdlabs.rift.transport.StubAddress;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A space cursor always carries its {@code flow_id} clause, so on a transport that cannot filter
 * server-side (the SPI default's shape — no transport the SDK ships is in it) every space cursor
 * call must refuse rather than widen: an unfiltered answer would hand back exactly the other flows'
 * entries the space exists to exclude.
 */
class SpaceCursorDefaultTransportTest {

    @Test
    void theDefaultTransportRefusesASpaceCursorRatherThanWidening() {
        Space space = new SpaceImpl(4545, "alice", new NoCursorTransport());

        UnsupportedOperationException page =
                assertThrows(UnsupportedOperationException.class, () -> space.recordedPage());
        UnsupportedOperationException since =
                assertThrows(UnsupportedOperationException.class, () -> space.recordedSince(1));

        // The refusal must be the SPI default's non-widening one, not an incidental unimplemented op.
        assertTrue(page.getMessage().contains("server-side match"), page.getMessage());
        assertTrue(since.getMessage().contains("server-side match"), since.getMessage());
    }

    /** Implements nothing: every call the cursor path makes beyond the SPI default is a test failure. */
    private static final class NoCursorTransport implements RiftTransport {
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
}
