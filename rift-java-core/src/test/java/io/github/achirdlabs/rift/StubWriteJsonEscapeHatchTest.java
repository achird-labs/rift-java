package io.github.achirdlabs.rift;

import io.github.achirdlabs.rift.dsl.RiftDsl;
import io.github.achirdlabs.rift.dsl.StubSpec;
import io.github.achirdlabs.rift.error.InvalidDefinition;
import io.github.achirdlabs.rift.json.JsonArray;
import io.github.achirdlabs.rift.json.JsonValue;
import io.github.achirdlabs.rift.transport.RiftTransport;
import io.github.achirdlabs.rift.transport.StubAddress;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Raw-JSON escape hatches on the stub-write path (#129) — the seam downstream SDK bridges drive.
 * Each test asserts the exact JSON reaches the transport unaltered: a bridge's whole reason to use
 * these overloads is that the DSL cannot express what its JSON carries.
 */
class StubWriteJsonEscapeHatchTest {

    private static final int PORT = 4545;
    private static final String FLOW = "flow-1";

    /** A definition with two stubs — the fixture for index-range checks. */
    private static final String TWO_STUBS = "{\"protocol\":\"http\",\"port\":4545,\"stubs\":["
            + "{\"predicates\":[{\"equals\":{\"path\":\"/a\"}}],\"responses\":[]},"
            + "{\"predicates\":[{\"equals\":{\"path\":\"/b\"}}],\"responses\":[]}]}";

    /** A fully-formed stub carrying fields the typed DSL cannot express — the motivating bridge case. */
    private static final String RAW_STUB = "{\"predicates\":[{\"equals\":{\"path\":\"/raw\"}}],"
            + "\"responses\":[{\"is\":{\"statusCode\":200}}],"
            + "\"_rift\":{\"flowState\":{\"inMemory\":true}},"
            + "\"extra\":{\"vendor\":\"bridge\",\"unknown\":[1,2]}}";

    private static ImposterImpl imposter(FakeTransport t) {
        return new ImposterImpl(PORT, t, ConnectOptions.builder(URI.create("http://127.0.0.1:2525")).build());
    }

    // --- AC1 / AC3: positional insert from raw JSON ---

    @Test
    void addStubJsonAtIndexForwardsToTransport() {
        FakeTransport t = new FakeTransport(TWO_STUBS);
        JsonValue stub = JsonValue.parse(RAW_STUB);

        StubRef ref = imposter(t).addStub(stub, 1);

        assertEquals(1, t.addStubIndex, "the requested index reaches the transport");
        assertEquals(stub.toJson(), t.addStubPayload.toJson(), "the exact JSON reaches the transport");
        assertEquals(1, ref.index(), "the returned ref addresses the inserted position");
    }

    @Test
    void addStubFirstJsonInsertsAtZero() {
        FakeTransport t = new FakeTransport(TWO_STUBS);

        StubRef ref = imposter(t).addStubFirst(JsonValue.parse(RAW_STUB));

        assertEquals(0, t.addStubIndex, "addStubFirst is addStub(stub, 0) — the overlay idiom");
        assertEquals(0, ref.index(), "the returned ref addresses the front");
    }

    // --- AC2: index-range contract, symmetric with the spec path ---

    @Test
    void addStubJsonAtIndexRejectsOutOfRange() {
        JsonValue stub = JsonValue.parse(RAW_STUB);

        FakeTransport past = new FakeTransport(TWO_STUBS);
        assertThrows(InvalidDefinition.class, () -> imposter(past).addStub(stub, 3),
                "index past stubCount is rejected");
        assertEquals(Integer.MIN_VALUE, past.addStubIndex, "the range check runs before the transport call");

        FakeTransport negative = new FakeTransport(TWO_STUBS);
        assertThrows(InvalidDefinition.class, () -> imposter(negative).addStub(stub, -1),
                "a negative index is rejected");
        assertEquals(Integer.MIN_VALUE, negative.addStubIndex, "nothing is sent when the index is out of range");

        FakeTransport t = new FakeTransport(TWO_STUBS);
        imposter(t).addStub(stub, 2);
        assertEquals(2, t.addStubIndex, "index == stubCount is the append boundary and is allowed");
    }

    // --- AC4 / AC5: replaceStubs from a raw JSON array ---

    @Test
    void replaceStubsJsonForwardsArray() {
        FakeTransport t = new FakeTransport(TWO_STUBS);
        JsonValue stubs = JsonValue.parse("[" + RAW_STUB + "]");

        imposter(t).replaceStubs(stubs);

        assertEquals(1, t.replaceStubsCalls, "replaceStubs round-trips once");
        assertEquals(stubs.toJson(), t.replaceStubsPayload.toJson(), "the exact array reaches the transport");
    }

    @Test
    void replaceStubsJsonRejectsNonArray() {
        FakeTransport t = new FakeTransport(TWO_STUBS);
        JsonValue notAnArray = JsonValue.parse(RAW_STUB);

        InvalidDefinition e = assertThrows(InvalidDefinition.class,
                () -> imposter(t).replaceStubs(notAnArray),
                "a non-array is always a caller bug — caught before the wire");
        assertTrue(e.getMessage().toLowerCase().contains("array"), "the message names the expected shape: " + e.getMessage());
        assertEquals(0, t.replaceStubsCalls, "nothing is sent when the shape is wrong");

        // null is not a JSON array either: the declared InvalidDefinition must win over an NPE
        // raised while building the message.
        assertThrows(InvalidDefinition.class, () -> imposter(t).replaceStubs((JsonValue) null),
                "null honours the declared @throws rather than surfacing an NPE");
    }

    // --- AC6: StubRef.replace from raw JSON ---

    @Test
    void stubRefReplaceJsonForwardsToTransport() {
        FakeTransport t = new FakeTransport(TWO_STUBS);
        JsonValue stub = JsonValue.parse(RAW_STUB);

        new StubRefImpl(PORT, t, new StubAddress.ById("s1")).replace(stub);

        assertEquals(new StubAddress.ById("s1"), t.replaceStubAddress, "the ref's own address is used");
        assertEquals(stub.toJson(), t.replaceStubPayload.toJson(), "the exact JSON reaches the transport");
    }

    // --- AC7: Space.addStub from raw JSON ---

    @Test
    void spaceAddStubJsonForwardsToTransport() {
        FakeTransport t = new FakeTransport(TWO_STUBS);
        JsonValue stub = JsonValue.parse(RAW_STUB);

        StubRef ref = new SpaceImpl(PORT, FLOW, t).addStub(stub);

        assertEquals(FLOW, t.spaceAddStubFlowId, "the space's flow id is used");
        assertEquals(stub.toJson(), t.spaceAddStubPayload.toJson(), "the exact JSON reaches the transport");
        assertEquals(1, ref.index(), "the returned ref addresses the appended stub (2 stubs in the space)");
    }

    // --- AC8: the spec path and the JSON path are the same path ---

    @Test
    void specAndJsonPathsProduceIdenticalPayloads() {
        StubSpec spec = RiftDsl.onGet("/parity").willReturn(RiftDsl.status(204));
        JsonValue asJson = JsonValue.parse(spec.build().toJson());

        FakeTransport viaSpec = new FakeTransport(TWO_STUBS);
        imposter(viaSpec).addStub(spec, 1);
        FakeTransport viaJson = new FakeTransport(TWO_STUBS);
        imposter(viaJson).addStub(asJson, 1);
        assertEquals(viaSpec.addStubPayload.toJson(), viaJson.addStubPayload.toJson(),
                "addStub: the spec path delegates through the JSON path");
        assertEquals(viaSpec.addStubIndex, viaJson.addStubIndex, "addStub: same index reaches the transport");

        FakeTransport replaceViaSpec = new FakeTransport(TWO_STUBS);
        imposter(replaceViaSpec).replaceStubs(List.of(spec));
        FakeTransport replaceViaJson = new FakeTransport(TWO_STUBS);
        imposter(replaceViaJson).replaceStubs(new JsonArray(List.of(asJson)));
        assertEquals(replaceViaSpec.replaceStubsPayload.toJson(), replaceViaJson.replaceStubsPayload.toJson(),
                "replaceStubs: the spec path delegates through the JSON path");

        FakeTransport refViaSpec = new FakeTransport(TWO_STUBS);
        new StubRefImpl(PORT, refViaSpec, new StubAddress.ByIndex(0)).replace(spec);
        FakeTransport refViaJson = new FakeTransport(TWO_STUBS);
        new StubRefImpl(PORT, refViaJson, new StubAddress.ByIndex(0)).replace(asJson);
        assertEquals(refViaSpec.replaceStubPayload.toJson(), refViaJson.replaceStubPayload.toJson(),
                "StubRef.replace: the spec path delegates through the JSON path");

        FakeTransport spaceViaSpec = new FakeTransport(TWO_STUBS);
        new SpaceImpl(PORT, FLOW, spaceViaSpec).addStub(spec);
        FakeTransport spaceViaJson = new FakeTransport(TWO_STUBS);
        new SpaceImpl(PORT, FLOW, spaceViaJson).addStub(asJson);
        assertEquals(spaceViaSpec.spaceAddStubPayload.toJson(), spaceViaJson.spaceAddStubPayload.toJson(),
                "Space.addStub: the spec path delegates through the JSON path");
    }

    // --- AC9: fidelity — the fields the DSL cannot express survive on every op ---

    @Test
    void rawJsonPreservesRiftAndExtraFields() {
        JsonValue stub = JsonValue.parse(RAW_STUB);

        FakeTransport at = new FakeTransport(TWO_STUBS);
        imposter(at).addStub(stub, 0);
        assertPreserved(stub, at.addStubPayload, "addStub(json, index)");

        FakeTransport ft = new FakeTransport(TWO_STUBS);
        imposter(ft).addStubFirst(stub);
        assertPreserved(stub, ft.addStubPayload, "addStubFirst(json)");

        FakeTransport rt = new FakeTransport(TWO_STUBS);
        imposter(rt).replaceStubs(new JsonArray(List.of(stub)));
        assertPreserved(stub, ((JsonArray) rt.replaceStubsPayload).items().get(0), "replaceStubs(json)");

        FakeTransport st = new FakeTransport(TWO_STUBS);
        new StubRefImpl(PORT, st, new StubAddress.ByIndex(0)).replace(stub);
        assertPreserved(stub, st.replaceStubPayload, "StubRef.replace(json)");

        FakeTransport pt = new FakeTransport(TWO_STUBS);
        new SpaceImpl(PORT, FLOW, pt).addStub(stub);
        assertPreserved(stub, pt.spaceAddStubPayload, "Space.addStub(json)");
    }

    /**
     * The facade must hand the caller's own JsonValue to the transport untouched — {@code assertSame}
     * is the precise form of "no reshaping". The field assertions below state the intent that makes
     * that matter: an impl that round-tripped through {@code model.Stub} would drop these.
     */
    private static void assertPreserved(JsonValue expected, JsonValue payload, String op) {
        assertSame(expected, payload, op + " passes the caller's JSON straight through");
        String json = payload.toJson();
        assertTrue(json.contains("_rift"), op + " preserves _rift: " + json);
        assertTrue(json.contains("\"vendor\":\"bridge\""), op + " preserves unknown extra fields: " + json);
    }

    /** Records every stub-write the facade makes; the rest of the SPI is unused. */
    private static final class FakeTransport implements RiftTransport {
        private final String definition;

        JsonValue addStubPayload;
        int addStubIndex = Integer.MIN_VALUE;
        JsonValue replaceStubsPayload;
        int replaceStubsCalls;
        JsonValue replaceStubPayload;
        StubAddress replaceStubAddress;
        JsonValue spaceAddStubPayload;
        String spaceAddStubFlowId;

        FakeTransport(String definition) {
            this.definition = definition;
        }

        @Override
        public JsonValue getImposter(int port) {
            return JsonValue.parse(definition);
        }

        @Override
        public void addStub(int port, JsonValue stub) {
            addStubPayload = stub;
        }

        @Override
        public void addStub(int port, JsonValue stub, int index) {
            addStubPayload = stub;
            addStubIndex = index;
        }

        @Override
        public void replaceStubs(int port, JsonValue stubs) {
            replaceStubsCalls++;
            replaceStubsPayload = stubs;
        }

        @Override
        public void replaceStub(int port, StubAddress addr, JsonValue stub) {
            replaceStubAddress = addr;
            replaceStubPayload = stub;
        }

        @Override
        public void spaceAddStub(int port, String flowId, JsonValue stub) {
            spaceAddStubFlowId = flowId;
            spaceAddStubPayload = stub;
        }

        @Override
        public JsonValue spaceListStubs(int port, String flowId) {
            // The space holds the appended stub plus one pre-existing — Space.addStub derives its
            // ByIndex ref from this count.
            return new JsonArray(new ArrayList<>(List.of(JsonValue.parse(RAW_STUB), JsonValue.parse(RAW_STUB))));
        }

        // --- unused operations ---
        @Override public JsonValue getImposter(int port, boolean r, boolean p) { throw new UnsupportedOperationException(); }
        @Override public JsonValue createImposter(JsonValue def) { throw new UnsupportedOperationException(); }
        @Override public void deleteImposter(int port) { throw new UnsupportedOperationException(); }
        @Override public void deleteAll() { throw new UnsupportedOperationException(); }
        @Override public JsonValue listImposters(boolean r, boolean p) { throw new UnsupportedOperationException(); }
        @Override public void replaceAllImposters(JsonValue doc) { throw new UnsupportedOperationException(); }
        @Override public JsonValue applyConfig(JsonValue config) { throw new UnsupportedOperationException(); }
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
