package io.github.achirdlabs.rift.transport;

import io.github.achirdlabs.rift.ConnectOptions;
import io.github.achirdlabs.rift.Imposter;
import io.github.achirdlabs.rift.RecordedPage;
import io.github.achirdlabs.rift.Rift;
import io.github.achirdlabs.rift.VersionCheck;
import io.github.achirdlabs.rift.error.CommunicationError;
import io.github.achirdlabs.rift.json.JsonValue;
import org.junit.jupiter.api.Test;

import java.util.OptionalLong;

import static io.github.achirdlabs.rift.dsl.RiftDsl.imposter;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The savedRequests journal cursor (rift#603): {@code ?since=}, {@code x-rift-next-index}, and
 * {@code x-rift-truncated}. The engine keeps the response body a bare array and puts the cursor in
 * headers, so these assert on both halves — a body-only check cannot see the cursor at all.
 *
 * <p>The load-bearing distinction throughout: an <em>absent</em> {@code nextIndex} means "do not
 * advance" (old engine / no stable indices / degraded partial read — indistinguishable by design),
 * while a <em>present</em> {@code 0} is a real cursor meaning "nothing recorded yet". Conflating
 * them re-introduces the skip-entries bug the cursor exists to remove.
 */
class RecordedCursorTest {

    private static final String IMP = "{\"port\":4545,\"protocol\":\"http\",\"stubs\":[]}";
    private static final String TWO_REQUESTS =
            "[{\"method\":\"GET\",\"path\":\"/a\"},{\"method\":\"GET\",\"path\":\"/b\"}]";

    private static Rift connect(FakeAdminServer s) {
        return Rift.connect(ConnectOptions.builder(s.baseUri()).versionCheck(VersionCheck.OFF).build());
    }

    private static Imposter created(FakeAdminServer s, Rift rift) {
        s.respond("POST /imposters", 201, IMP);
        return rift.create(imposter("x").port(4545));
    }

    private static String pathOf(FakeAdminServer s) {
        return s.received().stream()
                .filter(r -> r.method().equals("GET") && r.path().startsWith("/imposters/4545/savedRequests"))
                .map(FakeAdminServer.Received::path)
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("no savedRequests GET was issued"));
    }

    @Test
    void recordedPageCarriesTheRequestsAndTheCursor() {
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.on("GET /imposters/4545/savedRequests",
                    (r, v) -> FakeAdminServer.Response.ok(TWO_REQUESTS).withHeader("x-rift-next-index", "12"));
            try (Rift rift = connect(s)) {
                RecordedPage page = created(s, rift).recordedPage();

                assertEquals(2, page.requests().size());
                assertEquals("/a", page.requests().get(0).path());
                assertEquals(OptionalLong.of(12), page.nextIndex());
                assertFalse(page.truncated());
                // The baseline asks "what is retained?", which the engine answers without ever
                // reporting truncation — so it must not smuggle a since= in.
                assertEquals("/imposters/4545/savedRequests", pathOf(s));
            }
        }
    }

    @Test
    void recordedSinceSendsTheCursorAsSinceExclusive() {
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.on("GET /imposters/4545/savedRequests",
                    (r, v) -> FakeAdminServer.Response.ok(TWO_REQUESTS).withHeader("x-rift-next-index", "14"));
            try (Rift rift = connect(s)) {
                RecordedPage page = created(s, rift).recordedSince(12);

                assertEquals(OptionalLong.of(14), page.nextIndex());
                assertEquals("/imposters/4545/savedRequests?since=12", pathOf(s));
            }
        }
    }

    @Test
    void pollAtTheTipReturnsAnEmptyPageButKeepsTheCursor() {
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.on("GET /imposters/4545/savedRequests",
                    (r, v) -> FakeAdminServer.Response.ok("[]").withHeader("x-rift-next-index", "12"));
            try (Rift rift = connect(s)) {
                RecordedPage page = created(s, rift).recordedSince(12);

                assertTrue(page.requests().isEmpty(), "a cursor at the tip returns no entries");
                // Nothing new is NOT the same as no cursor support: a tail that treated the tip as
                // "unsupported" would stop advancing and re-scan the journal forever.
                assertEquals(OptionalLong.of(12), page.nextIndex());
                assertFalse(page.truncated());
            }
        }
    }

    @Test
    void nextIndexZeroIsARealCursorNotAnAbsentOne() {
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.on("GET /imposters/4545/savedRequests",
                    (r, v) -> FakeAdminServer.Response.ok("[]").withHeader("x-rift-next-index", "0"));
            try (Rift rift = connect(s)) {
                RecordedPage page = created(s, rift).recordedPage();

                // 0 means "nothing has been recorded yet" — a cursor the caller may hold and pass
                // back. Only header *absence* means "do not advance".
                assertEquals(OptionalLong.of(0), page.nextIndex());
            }
        }
    }

    @Test
    void anAbsentCursorHeaderYieldsAnAbsentNextIndexAndTheFullList() {
        try (FakeAdminServer s = new FakeAdminServer()) {
            // No x-rift-next-index: an older engine, a backend without stable indices, or a degraded
            // partial read. All three serve the full list and all three mean "do not advance".
            s.respond("GET /imposters/4545/savedRequests", 200, TWO_REQUESTS);
            try (Rift rift = connect(s)) {
                RecordedPage page = created(s, rift).recordedPage();

                assertEquals(OptionalLong.empty(), page.nextIndex());
                assertEquals(2, page.requests().size(), "the full list is still served");
                assertFalse(page.truncated());
            }
        }
    }

    @Test
    void truncatedIsADistinctSignalNotAnError() {
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.on("GET /imposters/4545/savedRequests", (r, v) -> FakeAdminServer.Response.ok(TWO_REQUESTS)
                    .withHeader("x-rift-next-index", "20")
                    .withHeader("x-rift-truncated", "true"));
            try (Rift rift = connect(s)) {
                RecordedPage page = created(s, rift).recordedSince(1);

                assertTrue(page.truncated(), "retention evicted unseen entries — the caller re-baselines");
                // A hole in the caller's view is reported, never thrown: the entries that survived
                // are still real and still delivered.
                assertEquals(2, page.requests().size());
                assertEquals(OptionalLong.of(20), page.nextIndex());
            }
        }
    }

    @Test
    void truncatedIsDecidedByPresenceAndItsValueIsNeverParsed() {
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.on("GET /imposters/4545/savedRequests", (r, v) -> FakeAdminServer.Response.ok(TWO_REQUESTS)
                    .withHeader("x-rift-next-index", "20")
                    .withHeader("x-rift-truncated", "false"));
            try (Rift rift = connect(s)) {
                // The engine emits this header only when true and never sends `false`, so presence is
                // the whole signal. Pinned because parsing the value would invent a case the wire
                // format does not have — and would fail toward "no hole" on anything unexpected,
                // which is the one direction that loses data silently.
                assertTrue(created(s, rift).recordedSince(1).truncated(),
                        "presence alone means truncated; the value is not a boolean to parse");
            }
        }
    }

    @Test
    void aMalformedCursorHeaderFailsLoudlyRatherThanReadingAsUnsupported() {
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.on("GET /imposters/4545/savedRequests",
                    (r, v) -> FakeAdminServer.Response.ok(TWO_REQUESTS).withHeader("x-rift-next-index", "not-a-number"));
            try (Rift rift = connect(s)) {
                Imposter imp = created(s, rift);

                // Silently mapping garbage to "absent" would hide a broken engine/proxy behind the
                // legitimate do-not-advance path, where it would look like a slow tail forever.
                CommunicationError e = assertThrows(CommunicationError.class, imp::recordedPage);
                assertTrue(e.getMessage().contains("x-rift-next-index"), e.getMessage());
            }
        }
    }

    @Test
    void aTransportWithoutCursorSupportReportsAbsentRatherThanSynthesizingOne() {
        // The SPI default is the capability fallback: any transport that only implements recorded()
        // — EmbeddedTransport over FFI has no HTTP headers to read — serves the full list and
        // declines to advance, exactly as a cursor-less engine does over HTTP.
        RiftTransport cursorless = new CursorlessTransport();

        RiftTransport.RecordedSlice slice = cursorless.recordedSince(4545, OptionalLong.of(7), java.util.List.of());

        assertEquals(OptionalLong.empty(), slice.nextIndex(), "never synthesize an index");
        assertFalse(slice.truncated());
        assertEquals(JsonValue.parse(TWO_REQUESTS).toJson(), slice.requests().toJson());
    }

    /** Implements only the pre-cursor read, like a third-party transport predating the cursor API. */
    private static final class CursorlessTransport implements RiftTransport {

        @Override public JsonValue recorded(int port) { return JsonValue.parse(TWO_REQUESTS); }
        @Override public void close() {}

        // --- unused operations ---
        @Override public JsonValue createImposter(JsonValue def) { throw new UnsupportedOperationException(); }
        @Override public JsonValue getImposter(int port) { throw new UnsupportedOperationException(); }
        @Override public JsonValue getImposter(int port, boolean r, boolean p) { throw new UnsupportedOperationException(); }
        @Override public void deleteImposter(int port) { throw new UnsupportedOperationException(); }
        @Override public void deleteAll() { throw new UnsupportedOperationException(); }
        @Override public JsonValue listImposters(boolean r, boolean p) { throw new UnsupportedOperationException(); }
        @Override public void replaceAllImposters(JsonValue doc) { throw new UnsupportedOperationException(); }
        @Override public JsonValue applyConfig(JsonValue config) { throw new UnsupportedOperationException(); }
        @Override public void addStub(int port, JsonValue stub) { throw new UnsupportedOperationException(); }
        @Override public void replaceStubs(int port, JsonValue stubs) { throw new UnsupportedOperationException(); }
        @Override public void replaceStub(int port, StubAddress a, JsonValue s) { throw new UnsupportedOperationException(); }
        @Override public void deleteStub(int port, StubAddress a) { throw new UnsupportedOperationException(); }
        @Override public void clearRecorded(int port) { throw new UnsupportedOperationException(); }
        @Override public void clearProxyResponses(int port) { throw new UnsupportedOperationException(); }
        @Override public void enable(int port) { throw new UnsupportedOperationException(); }
        @Override public void disable(int port) { throw new UnsupportedOperationException(); }
        @Override public JsonValue scenarios(int port, java.util.Optional<String> f) { throw new UnsupportedOperationException(); }
        @Override public void setScenarioState(int port, String n, String s, java.util.Optional<String> f) { throw new UnsupportedOperationException(); }
        @Override public void resetScenarios(int port, java.util.Optional<String> flowId) { throw new UnsupportedOperationException(); }
        @Override public java.util.Optional<JsonValue> flowStateGet(int port, String f, String k) { throw new UnsupportedOperationException(); }
        @Override public void flowStatePut(int port, String f, String k, JsonValue v) { throw new UnsupportedOperationException(); }
        @Override public void flowStateDelete(int port, String f, String k) { throw new UnsupportedOperationException(); }
        @Override public void flowStateClear(int port, String f) { throw new UnsupportedOperationException(); }
        @Override public void spaceAddStub(int port, String f, JsonValue s) { throw new UnsupportedOperationException(); }
        @Override public JsonValue spaceListStubs(int port, String f) { throw new UnsupportedOperationException(); }
        @Override public JsonValue spaceRecorded(int port, String f) { throw new UnsupportedOperationException(); }
        @Override public void spaceDelete(int port, String f) { throw new UnsupportedOperationException(); }
        @Override public JsonValue buildInfo() { throw new UnsupportedOperationException(); }
        @Override public java.net.URI adminUri() { throw new UnsupportedOperationException(); }
        @Override public JsonValue startIntercept(JsonValue options) { throw new UnsupportedOperationException(); }
        @Override public void interceptAddRules(JsonValue rules) { throw new UnsupportedOperationException(); }
        @Override public JsonValue interceptListRules() { throw new UnsupportedOperationException(); }
        @Override public void interceptClearRules() { throw new UnsupportedOperationException(); }
        @Override public String interceptCaPem() { throw new UnsupportedOperationException(); }
    }
}
