package io.github.achirdlabs.rift;

import io.github.achirdlabs.rift.error.CommunicationError;
import io.github.achirdlabs.rift.error.InvalidDefinition;
import io.github.achirdlabs.rift.error.RiftException;
import io.github.achirdlabs.rift.json.JsonObject;
import io.github.achirdlabs.rift.json.JsonValue;
import io.github.achirdlabs.rift.model.Predicate;
import io.github.achirdlabs.rift.transport.RiftTransport;
import io.github.achirdlabs.rift.transport.StubAddress;
import io.github.achirdlabs.rift.verify.ClosestMiss;
import io.github.achirdlabs.rift.verify.RequestMatch;
import io.github.achirdlabs.rift.verify.VerificationException;
import io.github.achirdlabs.rift.verify.VerificationResult;
import io.github.achirdlabs.rift.verify.VerificationTimes;
import io.github.achirdlabs.rift.verify.VerifyDetail;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Optional;

import static io.github.achirdlabs.rift.dsl.RiftDsl.onGet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Structured verify results (#127) over a fake transport. Verification is engine-authoritative:
 * these tests pin the exact {@code POST /imposters/{port}/verify} body the facade sends per detail
 * flag, and the envelope→record mapping — the contract a downstream SDK bridge consumes.
 */
class VerifyResultTest {

    private static final int PORT = 4545;
    private static final String FLOW = "flow-1";

    private static final String RECORDING_DEF =
            "{\"protocol\":\"http\",\"port\":4545,\"recordRequests\":true,\"stubs\":[]}";
    private static final String NON_RECORDING_DEF =
            "{\"protocol\":\"http\",\"port\":4545,\"stubs\":[]}";

    /** The full engine envelope: matched/total plus both optional sections. */
    private static final String FULL_ENVELOPE = "{\"matched\":1,\"total\":3,"
            + "\"requests\":[{\"method\":\"GET\",\"path\":\"/api/users/1\"}],"
            + "\"closest\":{\"request\":{\"method\":\"GET\",\"path\":\"/api/users/2\"},"
            + "\"failedPredicates\":[{\"predicate\":{\"equals\":{\"path\":\"/api/users/1\"}},"
            + "\"actual\":{\"path\":\"/api/users/2\"}}]}}";

    private static final String COUNTS_ONLY = "{\"matched\":2,\"total\":5}";

    private static ImposterImpl imposter(RiftTransport t) {
        return new ImposterImpl(PORT, t, ConnectOptions.builder(URI.create("http://127.0.0.1:2525")).build());
    }

    private static JsonObject body(FakeTransport t) {
        return (JsonObject) t.verifyBody;
    }

    // --- AC1–AC4: the body carries a flag only when its detail was asked for ---

    @Test
    void verifyResultNoDetailsSendsCountsOnlyBody() {
        FakeTransport t = new FakeTransport(RECORDING_DEF, COUNTS_ONLY);

        VerificationResult result = imposter(t).verifyResult(onGet("/api/users/1"));

        assertEquals(1, t.verifyCalls, "one round-trip");
        JsonObject sent = body(t);
        assertTrue(sent.has("predicates"), "predicates are always sent");
        assertFalse(sent.has("includeRequests"), "no journal is shipped when REQUESTS wasn't asked for");
        assertFalse(sent.has("includeClosest"), "no closest scoring is asked for by default");
        assertFalse(sent.has("flowId"), "an imposter-scoped verify carries no flowId");
        assertEquals(2, result.matched());
        assertEquals(5, result.total());
        assertTrue(result.satisfied(), "no times → atLeast(1), and 2 matched");
    }

    @Test
    void verifyResultWithoutTimesDefaultsToAtLeastOnce() {
        // Pins the false branch too: an always-true default would pass the test above.
        FakeTransport none = new FakeTransport(RECORDING_DEF, "{\"matched\":0,\"total\":5}");
        assertFalse(imposter(none).verifyResult(onGet("/x")).satisfied(), "0 matched does not satisfy atLeast(1)");

        FakeTransport one = new FakeTransport(RECORDING_DEF, "{\"matched\":1,\"total\":5}");
        assertTrue(imposter(one).verifyResult(onGet("/x")).satisfied(), "1 matched satisfies atLeast(1)");
    }

    @Test
    void verifyResultRequestsDetailSetsOnlyThatFlag() {
        FakeTransport t = new FakeTransport(RECORDING_DEF, FULL_ENVELOPE);

        imposter(t).verifyResult(onGet("/api/users/1"), VerifyDetail.REQUESTS);

        assertEquals("true", body(t).get("includeRequests").toJson());
        assertFalse(body(t).has("includeClosest"), "REQUESTS alone must not request closest");
    }

    @Test
    void verifyResultClosestDetailSetsOnlyThatFlag() {
        FakeTransport t = new FakeTransport(RECORDING_DEF, FULL_ENVELOPE);

        imposter(t).verifyResult(onGet("/api/users/1"), VerifyDetail.CLOSEST);

        assertEquals("true", body(t).get("includeClosest").toJson());
        assertFalse(body(t).has("includeRequests"), "CLOSEST alone must not ship the journal");
    }

    @Test
    void verifyResultBothDetailsSetBothFlags() {
        FakeTransport t = new FakeTransport(RECORDING_DEF, FULL_ENVELOPE);

        imposter(t).verifyResult(onGet("/x"), VerificationTimes.times(1),
                VerifyDetail.REQUESTS, VerifyDetail.CLOSEST);

        assertEquals("true", body(t).get("includeRequests").toJson());
        assertEquals("true", body(t).get("includeClosest").toJson());
    }

    // --- AC5: space scoping ---

    @Test
    void spaceVerifyResultScopesByFlowId() {
        FakeTransport t = new FakeTransport(RECORDING_DEF, COUNTS_ONLY);

        VerificationResult result = new SpaceImpl(PORT, FLOW, t).verifyResult(onGet("/only-here"));

        assertEquals("\"" + FLOW + "\"", body(t).get("flowId").toJson(), "the space's flow id scopes the count");
        assertEquals(2, result.matched());
    }

    // --- AC6/AC7: envelope → records ---

    @Test
    void verifyResultMapsFullEnvelope() {
        FakeTransport t = new FakeTransport(RECORDING_DEF, FULL_ENVELOPE);

        VerificationResult result = imposter(t).verifyResult(onGet("/api/users/1"),
                VerifyDetail.REQUESTS, VerifyDetail.CLOSEST);

        assertEquals(1, result.matched());
        assertEquals(3, result.total());
        assertEquals(1, result.requests().size(), "requests are mapped when present");
        assertEquals("/api/users/1", result.requests().get(0).path());

        ClosestMiss closest = result.closest().orElseThrow();
        assertEquals("/api/users/2", closest.request().path(), "the near-miss request is mapped");
        assertEquals(1, closest.failedPredicates().size());
        Predicate failed = closest.failedPredicates().get(0).predicate();
        assertTrue(failed.toJson().contains("/api/users/1"), "the failed predicate is typed: " + failed.toJson());
        assertEquals("{\"path\":\"/api/users/2\"}", closest.failedPredicates().get(0).actual().toJson(),
                "actual stays raw JSON — it is an arbitrary engine value");
    }

    @Test
    void verifyResultMapsAbsentOptionalKeys() {
        FakeTransport t = new FakeTransport(RECORDING_DEF, COUNTS_ONLY);

        VerificationResult result = imposter(t).verifyResult(onGet("/x"));

        assertTrue(result.requests().isEmpty(), "an absent requests key maps to an empty list, not null");
        assertEquals(Optional.empty(), result.closest(), "an absent closest key maps to empty");
    }

    // --- AC8: satisfied is client arithmetic over the engine's count ---

    @Test
    void satisfiedReflectsVerificationTimes() {
        record Case(VerificationTimes times, boolean expected, String why) {}
        // The canned envelope reports matched=2.
        for (Case c : java.util.List.of(
                new Case(VerificationTimes.times(2), true, "exactly 2 == 2"),
                new Case(VerificationTimes.times(3), false, "exactly 3 != 2"),
                new Case(VerificationTimes.atLeast(2), true, "2 >= 2"),
                new Case(VerificationTimes.atLeast(3), false, "2 < 3"),
                new Case(VerificationTimes.atMost(2), true, "2 <= 2"),
                new Case(VerificationTimes.atMost(1), false, "2 > 1"),
                new Case(VerificationTimes.between(1, 3), true, "1 <= 2 <= 3"),
                new Case(VerificationTimes.never(), false, "2 != 0"))) {
            FakeTransport t = new FakeTransport(RECORDING_DEF, COUNTS_ONLY);
            VerificationResult r = imposter(t).verifyResult(onGet("/x"), c.times());
            assertEquals(c.expected(), r.satisfied(), c.why());
            assertEquals(2, r.matched(), "the engine's count is passed through untouched");
        }
    }

    // --- AC9/AC10: verify() is one engine call, and the exception carries the result ---

    @Test
    void verifyDelegatesToVerifyResultOnceWithClosest() {
        FakeTransport pass = new FakeTransport(RECORDING_DEF, COUNTS_ONLY);
        imposter(pass).verify(onGet("/x"), VerificationTimes.times(2));
        assertEquals(1, pass.verifyCalls, "verdict and diff come from ONE journal snapshot");
        assertEquals("true", ((JsonObject) pass.verifyBody).get("includeClosest").toJson(),
                "verify asks for closest so a failure can render a diff");

        FakeTransport fail = new FakeTransport(RECORDING_DEF, FULL_ENVELOPE);
        assertThrows(VerificationException.class,
                () -> imposter(fail).verify(onGet("/api/users/1"), VerificationTimes.times(2)),
                "matched=1 does not satisfy exactly 2");
        assertEquals(1, fail.verifyCalls, "the failing path also makes exactly one call");
    }

    @Test
    void spaceVerifyDelegatesToVerifyResultOnceWithClosest() {
        FakeTransport pass = new FakeTransport(RECORDING_DEF, COUNTS_ONLY);
        new SpaceImpl(PORT, FLOW, pass).verify(onGet("/x"), VerificationTimes.times(2));
        assertEquals(1, pass.verifyCalls, "a space verify is also one engine call");
        assertEquals("true", ((JsonObject) pass.verifyBody).get("includeClosest").toJson());

        FakeTransport fail = new FakeTransport(RECORDING_DEF, FULL_ENVELOPE);
        assertThrows(VerificationException.class,
                () -> new SpaceImpl(PORT, FLOW, fail).verify(onGet("/x"), VerificationTimes.times(2)));
        assertEquals(1, fail.verifyCalls);
    }

    @Test
    void verificationExceptionCarriesResult() {
        FakeTransport t = new FakeTransport(RECORDING_DEF, FULL_ENVELOPE);

        VerificationException e = assertThrows(VerificationException.class,
                () -> imposter(t).verify(onGet("/api/users/1"), VerificationTimes.times(2)));

        VerificationResult carried = e.result().orElseThrow();
        assertEquals(1, carried.matched(), "the structured result rides on the exception");
        assertEquals(3, carried.total());
        assertTrue(carried.closest().isPresent(), "the engine's near-miss is reachable as a value");
        assertTrue(e.getMessage().contains("/api/users/2"),
                "the message renders the engine's closest miss: " + e.getMessage());
    }

    @Test
    void closestMissLineShowsTheOutcomeTheEngineRecorded() {
        String envelope = "{\"matched\":1,\"total\":3,"
                + "\"closest\":{\"request\":{\"method\":\"GET\",\"path\":\"/api/users/2\",\"status\":404,\"latencyMs\":7},"
                + "\"failedPredicates\":[]}}";
        VerificationException e = assertThrows(VerificationException.class,
                () -> imposter(new FakeTransport(RECORDING_DEF, envelope)).verify(onGet("/api/users/1"), VerificationTimes.times(2)));
        assertTrue(e.getMessage().contains("✗ GET /api/users/2 → 404 in 7 ms"), e.getMessage());

        // FULL_ENVELOPE's closest request carries no outcome (an older engine): the line is unchanged.
        VerificationException old = assertThrows(VerificationException.class,
                () -> imposter(new FakeTransport(RECORDING_DEF, FULL_ENVELOPE)).verify(onGet("/api/users/1"), VerificationTimes.times(2)));
        assertTrue(old.getMessage().contains("✗ GET /api/users/2\n"), old.getMessage());
    }

    // --- AC11: the recordRequests guard ---

    @Test
    void guardAppliesToBothImposterFormsNotSpace() {
        FakeTransport t = new FakeTransport(NON_RECORDING_DEF, COUNTS_ONLY);
        InvalidDefinition onVerify = assertThrows(InvalidDefinition.class,
                () -> imposter(t).verify(onGet("/x")),
                "verify still guards on a non-recording imposter");
        assertTrue(onVerify.getMessage().contains("record"), onVerify.getMessage());

        assertThrows(InvalidDefinition.class, () -> imposter(t).verifyResult(onGet("/x")),
                "the query form guards too — an always-zero count is the same silent trap");
        assertEquals(0, t.verifyCalls, "the guard fires before the wire");

        // A space's recording is configured on the owning imposter, so it carries no guard.
        FakeTransport space = new FakeTransport(NON_RECORDING_DEF, COUNTS_ONLY);
        new SpaceImpl(PORT, FLOW, space).verifyResult(onGet("/x"));
        assertEquals(1, space.verifyCalls, "Space.verifyResult has no recordRequests precondition");
    }

    // --- AC12: inject is now the engine's business ---

    @Test
    void injectPredicateReachesTheEngineInsteadOfThrowing() {
        FakeTransport t = new FakeTransport(RECORDING_DEF, COUNTS_ONLY);
        RequestMatch inject = RequestMatch.ofJson("[{\"inject\":\"function (r) { return true; }\"}]");

        imposter(t).verifyResult(inject);

        assertEquals(1, t.verifyCalls, "inject is evaluated by the engine, not rejected client-side");
        assertTrue(body(t).get("predicates").toJson().contains("inject"),
                "the inject predicate is forwarded verbatim: " + body(t).get("predicates").toJson());
    }

    // --- AC13: a transport that doesn't implement verify fails loudly ---

    @Test
    void nonOverridingTransportSurfacesUnsupported() {
        // A custom transport predating #127 inherits RiftTransport's throwing default. The facade
        // must surface that, never silently fall back to a second (client-side) evaluator.
        assertThrows(UnsupportedOperationException.class,
                () -> imposter(new NoVerifyTransport()).verifyResult(onGet("/x")));
    }

    // --- AC14: an off-contract envelope fails loudly, and as part of the RiftException hierarchy ---

    @Test
    void malformedEnvelopeThrowsCommunicationError() {
        // CommunicationError, not a bare wire-format error: the engine answered but its body was
        // unparseable, so `catch (RiftException)` — the documented cross-SDK contract — must cover it.
        for (String envelope : List.of(
                "{\"total\":3}",                       // no matched count
                "{\"matched\":1}",                     // no total
                "[]",                                  // not an object
                "{\"matched\":\"1\",\"total\":3}",     // matched is not a number
                "{\"matched\":1.5,\"total\":3}",       // matched is not an integer
                "{\"matched\":1,\"total\":3,\"requests\":{}}")) { // requests present but not an array
            FakeTransport t = new FakeTransport(RECORDING_DEF, envelope);
            CommunicationError e = assertThrows(CommunicationError.class,
                    () -> imposter(t).verifyResult(onGet("/x"), VerifyDetail.REQUESTS),
                    "off-contract envelope must not yield a verdict: " + envelope);
            assertTrue(e instanceof RiftException, "callers catch RiftException: " + envelope);
        }
    }

    @Test
    void unparseableFailedPredicateFromTheEngineIsACommunicationError() {
        // The engine echoes back a predicate we sent; one we cannot re-parse means it answered
        // off-contract, and must never surface as a silently-empty closest miss.
        FakeTransport t = new FakeTransport(RECORDING_DEF, "{\"matched\":0,\"total\":1,\"closest\":{"
                + "\"request\":{\"method\":\"GET\",\"path\":\"/x\"},"
                + "\"failedPredicates\":[{\"predicate\":{},\"actual\":{}}]}}");

        assertThrows(CommunicationError.class,
                () -> imposter(t).verifyResult(onGet("/x"), VerifyDetail.CLOSEST));
    }

    @Test
    void verificationResultReadIsPubliclyReusable() {
        // The bridge-facing parse seam: a caller holding a raw envelope maps it without the facade.
        VerificationResult r = VerificationResult.read(JsonValue.parse(FULL_ENVELOPE), VerificationTimes.times(1));
        assertEquals(1, r.matched());
        assertTrue(r.satisfied(), "satisfied is computed against the supplied times");
    }

    /** Records the verify body and returns a canned envelope; everything else is unused. */
    private static final class FakeTransport implements RiftTransport {
        private final String definition;
        private final String envelope;
        JsonValue verifyBody;
        int verifyCalls;

        FakeTransport(String definition, String envelope) {
            this.definition = definition;
            this.envelope = envelope;
        }

        @Override
        public JsonValue getImposter(int port) {
            return JsonValue.parse(definition);
        }

        @Override
        public JsonValue verify(int port, JsonValue body) {
            verifyCalls++;
            verifyBody = body;
            return JsonValue.parse(envelope);
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
        @Override public void resetScenarios(int port) { throw new UnsupportedOperationException(); }
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

    /** Inherits {@link RiftTransport}'s throwing {@code verify} default — a pre-#127 custom transport. */
    private static final class NoVerifyTransport implements RiftTransport {
        @Override
        public JsonValue getImposter(int port) {
            return JsonValue.parse(RECORDING_DEF);
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
        @Override public void resetScenarios(int port) { throw new UnsupportedOperationException(); }
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
