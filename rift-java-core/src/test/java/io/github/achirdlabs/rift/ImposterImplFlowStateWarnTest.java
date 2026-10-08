package io.github.achirdlabs.rift;

import io.github.achirdlabs.rift.json.JsonValue;
import io.github.achirdlabs.rift.transport.RiftTransport;
import io.github.achirdlabs.rift.transport.StubAddress;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #40 D2/D3 — {@code space()}/{@code flowState()} advise (warn, never throw) when the imposter's def
 * lacks the relevant flow-store configuration, and the def-based check is memoized (fetched once).
 */
class ImposterImplFlowStateWarnTest {

    private static final int PORT = 4545;

    private static ImposterImpl imposter(FakeTransport t) {
        return new ImposterImpl(PORT, t, ConnectOptions.builder(URI.create("http://127.0.0.1:2525")).build());
    }

    private static final String TRIGGERLESS =
            "{\"port\":4545,\"protocol\":\"http\",\"stubs\":[{\"predicates\":[],\"responses\":[{\"is\":{\"statusCode\":200}}]}]}";
    private static final String HEADER_SOURCE =
            "{\"port\":4545,\"protocol\":\"http\",\"stubs\":[],\"_rift\":{\"flowState\":{\"backend\":\"inmemory\",\"flowIdSource\":\"header:X-Flow\"}}}";

    @Test
    void spaceOnSourcelessDefWarnsButDoesNotThrowAndIsMemoized() {
        FakeTransport t = new FakeTransport(TRIGGERLESS);
        ImposterImpl imp = imposter(t);
        assertNotNull(assertDoesNotThrow(() -> imp.space("alice")));
        assertDoesNotThrow(() -> imp.space("alice"));
        assertDoesNotThrow(() -> imp.space("bob"));
        assertEquals(1, t.getImposterCalls, "the def-based check must be memoized (fetched once)");
    }

    @Test
    void flowStateOnTriggerlessDefWarnsButDoesNotThrowAndIsMemoized() {
        FakeTransport t = new FakeTransport(TRIGGERLESS);
        ImposterImpl imp = imposter(t);
        assertNotNull(assertDoesNotThrow(() -> imp.flowState("alice")));
        assertDoesNotThrow(() -> imp.flowState("alice"));
        assertEquals(1, t.getImposterCalls, "the def-based check must be memoized (fetched once)");
    }

    @Test
    void configuredDefDoesNotThrow() {
        FakeTransport t = new FakeTransport(HEADER_SOURCE);
        ImposterImpl imp = imposter(t);
        assertDoesNotThrow(() -> imp.space("alice"));
        assertDoesNotThrow(() -> imp.flowState("alice"));
    }

    @Test
    void warnsOnMisconfiguredDefAndIsSilentOnConfigured() {
        // System.Logger delegates to java.util.logging; capture the ImposterImpl logger's records.
        Logger jul = Logger.getLogger(ImposterImpl.class.getName());
        List<LogRecord> records = new CopyOnWriteArrayList<>();
        Handler handler = new Handler() {
            @Override public void publish(LogRecord r) { records.add(r); }
            @Override public void flush() {}
            @Override public void close() {}
        };
        handler.setLevel(Level.ALL);
        Level previous = jul.getLevel();
        jul.setLevel(Level.ALL);
        jul.addHandler(handler);
        try {
            imposter(new FakeTransport(TRIGGERLESS)).space("alice");
            assertTrue(records.stream().anyMatch(r -> r.getLevel() == Level.WARNING
                            && r.getMessage().contains("flowIdFromHeader")),
                    "space() on a source-less def must emit a WARNING");

            records.clear();
            imposter(new FakeTransport(TRIGGERLESS)).flowState("alice");
            assertTrue(records.stream().anyMatch(r -> r.getLevel() == Level.WARNING
                            && r.getMessage().contains("store trigger")),
                    "flowState() on a trigger-less def must emit a WARNING");

            records.clear();
            imposter(new FakeTransport(HEADER_SOURCE)).space("alice");
            imposter(new FakeTransport(HEADER_SOURCE)).flowState("alice");
            assertTrue(records.stream().noneMatch(r -> r.getLevel() == Level.WARNING),
                    "a configured def must not warn");
        } finally {
            jul.removeHandler(handler);
            jul.setLevel(previous);
        }
    }

    @Test
    void defFetchFailureNeverBreaksTheAccessor() {
        // The advisory check must not propagate a transport failure — the accessor still works.
        FakeTransport t = new FakeTransport(TRIGGERLESS);
        t.failGetImposter = true;
        ImposterImpl imp = imposter(t);
        assertNotNull(assertDoesNotThrow(() -> imp.space("alice")));
        assertNotNull(assertDoesNotThrow(() -> imp.flowState("alice")));
    }

    /** Minimal transport: serves a fixed def from getImposter and counts the calls; the rest is unused. */
    private static final class FakeTransport implements RiftTransport {
        private final String definitionJson;
        int getImposterCalls;
        boolean failGetImposter;

        FakeTransport(String definitionJson) {
            this.definitionJson = definitionJson;
        }

        @Override
        public JsonValue getImposter(int port) {
            getImposterCalls++;
            if (failGetImposter) {
                throw new io.github.achirdlabs.rift.error.CommunicationError("simulated failure");
            }
            return JsonValue.parse(definitionJson);
        }

        @Override public JsonValue getImposter(int port, boolean r, boolean p) { throw new UnsupportedOperationException(); }
        @Override public JsonValue createImposter(JsonValue def) { throw new UnsupportedOperationException(); }
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
