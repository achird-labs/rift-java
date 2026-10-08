package io.github.achirdlabs.rift;

import io.github.achirdlabs.rift.dsl.RequestField;
import io.github.achirdlabs.rift.error.CommunicationError;
import io.github.achirdlabs.rift.json.JsonValue;
import io.github.achirdlabs.rift.model.Stub;
import io.github.achirdlabs.rift.transport.RiftTransport;
import io.github.achirdlabs.rift.transport.StubAddress;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Recording API over a fake transport (#25 Part 1) — the logic the Docker IT can't isolate. */
class ImposterRecordingTest {

    private static final int PORT = 4545;

    // An exported (replayable, proxy-removed) definition whose recorded stub predicates on headers.
    private static final String EXPORT = "{\"protocol\":\"http\",\"port\":4545,\"stubs\":["
            + "{\"predicates\":[{\"equals\":{\"method\":\"GET\",\"path\":\"/p\","
            + "\"headers\":{\"Date\":\"Fri\",\"Accept\":\"application/json\"}}}],"
            + "\"responses\":[{\"is\":{\"statusCode\":200,\"body\":\"hi\"}}]}]}";

    private static ImposterImpl imposter(FakeTransport transport) {
        return new ImposterImpl(PORT, transport, ConnectOptions.builder(URI.create("http://127.0.0.1:2525")).build());
    }

    @Test
    void onceModePrependsProxyStubAlwaysAppends() {
        FakeTransport once = new FakeTransport("{\"stubs\":[{\"predicates\":[{\"equals\":{\"path\":\"/existing\"}}],\"responses\":[]}]}", EXPORT);
        imposter(once).startRecording("http://origin");
        assertTrue(once.lastReplaceStubs.get(0).toJson().contains("proxy"), "ONCE prepends the proxy stub");

        FakeTransport always = new FakeTransport("{\"stubs\":[{\"predicates\":[{\"equals\":{\"path\":\"/existing\"}}],\"responses\":[]}]}", EXPORT);
        imposter(always).startRecording("http://origin", RecordSpec.builder().mode(RecordMode.ALWAYS).build());
        List<JsonValue> body = always.lastReplaceStubs;
        assertTrue(body.get(body.size() - 1).toJson().contains("proxy"), "ALWAYS appends the proxy stub");
    }

    @Test
    void stopExportsSwapsAndReturnsRecordedStubs() {
        FakeTransport t = new FakeTransport("{\"stubs\":[]}", EXPORT);
        Recording recording = imposter(t).startRecording("http://origin");
        t.replaceStubsCalls = 0; // ignore the startRecording install

        List<Stub> recorded = recording.stop();
        assertFalse(recorded.isEmpty(), "stop returns recorded stubs");
        assertEquals(1, t.replaceStubsCalls, "stop swaps once");
        assertTrue(t.exportCalls > 0, "stop exported the replayable definition");
    }

    @Test
    void ignoreHeadersStripsTheNamedHeaderFromPredicates() {
        FakeTransport t = new FakeTransport("{\"stubs\":[]}", EXPORT);
        Recording recording = imposter(t).startRecording("http://origin",
                RecordSpec.builder().generateBy(RequestField.METHOD, RequestField.PATH, RequestField.HEADERS)
                        .ignoreHeaders("Date").build());

        List<Stub> recorded = recording.stop();
        String json = recorded.get(0).toJson();
        assertFalse(json.contains("Date"), "the ignored header is stripped from the predicate: " + json);
        assertTrue(json.contains("Accept"), "other headers are kept: " + json);
    }

    @Test
    void snapshotExportsWithoutSwapping() {
        FakeTransport t = new FakeTransport("{\"stubs\":[]}", EXPORT);
        Recording recording = imposter(t).startRecording("http://origin");
        t.replaceStubsCalls = 0;

        List<Stub> snap = recording.snapshot();
        assertFalse(snap.isEmpty(), "snapshot returns what's recorded");
        assertEquals(0, t.replaceStubsCalls, "snapshot keeps the proxy — no swap");
    }

    @Test
    void stopIsIdempotent() {
        FakeTransport t = new FakeTransport("{\"stubs\":[]}", EXPORT);
        Recording recording = imposter(t).startRecording("http://origin");
        t.replaceStubsCalls = 0;

        recording.stop();
        recording.stop();
        assertEquals(1, t.replaceStubsCalls, "a second stop does not re-swap");
    }

    @Test
    void swapRetriesAfterATransientFailure() {
        FakeTransport t = new FakeTransport("{\"stubs\":[]}", EXPORT);
        Recording recording = imposter(t).startRecording("http://origin");
        t.replaceStubsCalls = 0;
        t.failNextReplaceStubs = true;

        assertThrows(CommunicationError.class, recording::stop);
        // The failure must NOT latch the swapped flag — a retry re-attempts the swap.
        recording.stop();
        assertEquals(2, t.replaceStubsCalls, "the swap is retried after the transient failure");
    }

    @Test
    void persistWritesTheReplayableDefinition() throws Exception {
        FakeTransport t = new FakeTransport("{\"stubs\":[]}", EXPORT);
        Recording recording = imposter(t).startRecording("http://origin");
        Path file = Files.createTempFile("rift-golden", ".json");
        try {
            recording.persist(file);
            String written = Files.readString(file);
            assertTrue(written.contains("\"stubs\""), "persisted file is a replayable imposter definition");
            JsonValue.parse(written); // valid JSON or throws
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /** Minimal {@link RiftTransport} recording only what the Recording API touches; the rest is unused. */
    private static final class FakeTransport implements RiftTransport {
        private final String currentDefinition;
        private final String export;
        List<JsonValue> lastReplaceStubs = new ArrayList<>();
        int replaceStubsCalls;
        int exportCalls;
        boolean failNextReplaceStubs;

        FakeTransport(String currentDefinition, String export) {
            this.currentDefinition = currentDefinition;
            this.export = export;
        }

        @Override
        public JsonValue getImposter(int port) {
            return JsonValue.parse(currentDefinition);
        }

        @Override
        public JsonValue getImposter(int port, boolean replayable, boolean removeProxies) {
            exportCalls++;
            return JsonValue.parse(export);
        }

        @Override
        public void replaceStubs(int port, JsonValue stubs) {
            replaceStubsCalls++;
            if (failNextReplaceStubs) {
                failNextReplaceStubs = false;
                throw new CommunicationError("simulated transient failure");
            }
            this.lastReplaceStubs = ((io.github.achirdlabs.rift.json.JsonArray) stubs).items();
        }

        // --- unused operations ---
        @Override public JsonValue createImposter(JsonValue def) { throw new UnsupportedOperationException(); }
        @Override public void deleteImposter(int port) { throw new UnsupportedOperationException(); }
        @Override public void deleteAll() { throw new UnsupportedOperationException(); }
        @Override public JsonValue listImposters(boolean r, boolean p) { throw new UnsupportedOperationException(); }
        @Override public void replaceAllImposters(JsonValue doc) { throw new UnsupportedOperationException(); }
        @Override public JsonValue applyConfig(JsonValue config) { throw new UnsupportedOperationException(); }
        @Override public void addStub(int port, JsonValue stub) { throw new UnsupportedOperationException(); }
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
        @Override public void flowStateClear(int port, String f) { throw new UnsupportedOperationException(); }
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
