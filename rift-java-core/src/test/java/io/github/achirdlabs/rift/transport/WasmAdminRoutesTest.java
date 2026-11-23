package io.github.achirdlabs.rift.transport;

import io.github.achirdlabs.rift.ComponentInfo;
import io.github.achirdlabs.rift.ConnectOptions;
import io.github.achirdlabs.rift.Imposter;
import io.github.achirdlabs.rift.ImposterClock;
import io.github.achirdlabs.rift.Rift;
import io.github.achirdlabs.rift.VersionCheck;
import io.github.achirdlabs.rift.WasmInstanceInfo;
import io.github.achirdlabs.rift.error.CommunicationError;
import io.github.achirdlabs.rift.error.EngineError;
import io.github.achirdlabs.rift.error.ImposterNotFound;
import io.github.achirdlabs.rift.error.InvalidDefinition;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static io.github.achirdlabs.rift.dsl.RiftDsl.imposter;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rift 0.25.0 admin routes over the remote transport (rift-java#354): the imposter clock, the
 * kept wasm instances and their snapshot, and the component registry. Each 404 is told apart from an
 * engine without the route by the follow-up read the transport makes.
 */
class WasmAdminRoutesTest {

    private static final String IMP = "{\"port\":4545,\"protocol\":\"http\",\"stubs\":[]}";
    private static final String SHA = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    private static final String COMPONENT = "{\"sha256\":\"" + SHA + "\",\"size\":84211,\"world\":\"rift:mock/mock\","
            + "\"imports\":[\"rift:mock/types@0.1.0\",\"wasi:cli/stdout@0.2.6\"],\"uploadedAt\":\"2026-11-20T12:00:00Z\","
            + "\"referencedBy\":[4545]}";

    private static Rift connect(FakeAdminServer s) {
        return Rift.connect(ConnectOptions.builder(s.baseUri()).versionCheck(VersionCheck.OFF).build());
    }

    private static Imposter created(FakeAdminServer s, Rift rift) {
        s.respond("POST /imposters", 201, IMP);
        return rift.create(imposter("x").port(4545));
    }

    private static FakeAdminServer.Received last(FakeAdminServer s) {
        List<FakeAdminServer.Received> all = s.received();
        return all.get(all.size() - 1);
    }

    @Test
    void theClockIsReadFrozenSteppedAndReset() {
        try (FakeAdminServer s = new FakeAdminServer(); Rift rift = connect(s)) {
            s.respond("GET /imposters/4545/clock", 200, "{\"mode\":\"wall\",\"nowMs\":1700000000000}");
            s.respond("PUT /imposters/4545/clock", 200, "{\"mode\":\"frozen\",\"nowMs\":1700000000000}");
            s.respond("DELETE /imposters/4545/clock", 200, "{\"mode\":\"wall\",\"nowMs\":1700000000001}");
            ImposterClock clock = created(s, rift).clock();

            assertEquals(new ImposterClock.Reading(ImposterClock.Mode.WALL, Instant.ofEpochMilli(1_700_000_000_000L)), clock.get());

            ImposterClock.Reading frozen = clock.set(Instant.ofEpochMilli(1_700_000_000_000L));
            assertEquals(ImposterClock.Mode.FROZEN, frozen.mode());
            assertEquals(1_700_000_000_000L, frozen.nowMs());
            assertEquals("{\"nowMs\":1700000000000}", last(s).body());

            clock.advance(Duration.ofDays(1));
            assertEquals("{\"advanceMs\":86400000}", last(s).body());
            assertEquals("application/json", last(s).headers().get("content-type"));

            assertEquals(ImposterClock.Mode.WALL, clock.reset().mode());
            assertEquals("DELETE", last(s).method());
        }
    }

    @Test
    void aNegativeStepIsRefusedBeforeAnythingIsSent() {
        try (FakeAdminServer s = new FakeAdminServer(); Rift rift = connect(s)) {
            ImposterClock clock = created(s, rift).clock();
            int before = s.received().size();
            assertThrows(IllegalArgumentException.class, () -> clock.advance(Duration.ofMillis(-1)));
            assertEquals(before, s.received().size());
        }
    }

    @Test
    void anInstantTheEngineRefusesIsAnInvalidDefinition() {
        try (FakeAdminServer s = new FakeAdminServer(); Rift rift = connect(s)) {
            s.respond("PUT /imposters/4545/clock", 400,
                    "{\"errors\":[{\"code\":\"bad data\",\"message\":\"nowMs is outside years 1-9999\"}]}");
            InvalidDefinition e = assertThrows(InvalidDefinition.class,
                    () -> created(s, rift).clock().set(Instant.parse("+10000-01-01T00:00:00Z")));
            assertEquals("nowMs is outside years 1-9999", e.getMessage());
        }
    }

    @Test
    void aClock404OnAMissingImposterIsImposterNotFound() {
        try (FakeAdminServer s = new FakeAdminServer(); Rift rift = connect(s)) {
            // Nothing answers GET /imposters/4545 either: the imposter is gone.
            ImposterClock clock = created(s, rift).clock();
            assertThrows(ImposterNotFound.class, clock::get);
        }
    }

    @Test
    void aClock404OnAnImposterThatExistsIsAnEngineWithoutTheRoute() {
        try (FakeAdminServer s = new FakeAdminServer(); Rift rift = connect(s)) {
            s.respond("GET /imposters/4545", 200, IMP);
            UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class,
                    () -> created(s, rift).clock().get());
            assertTrue(e.getMessage().contains("needs rift >= 0.25.0"), e.getMessage());
        }
    }

    @Test
    void aClockAnswerOfAnotherShapeIsACommunicationError() {
        try (FakeAdminServer s = new FakeAdminServer(); Rift rift = connect(s)) {
            s.respond("GET /imposters/4545/clock", 200, "{\"mode\":\"sideways\",\"nowMs\":1}");
            assertThrows(CommunicationError.class, () -> created(s, rift).clock().get());
        }
    }

    @Test
    void keptInstancesAreListed() {
        try (FakeAdminServer s = new FakeAdminServer(); Rift rift = connect(s)) {
            s.respond("GET /imposters/4545/wasm/instances", 200, "{\"instances\":[{\"id\":\"k1\",\"sha256\":\"" + SHA
                    + "\",\"export\":null,\"knobsHash\":\"0123456789ab\",\"instantiatedAt\":1700000000000,\"calls\":3,"
                    + "\"memoryBytes\":1114112}]}");
            List<WasmInstanceInfo> instances = created(s, rift).wasmInstances();
            assertEquals(List.of(new WasmInstanceInfo("k1", SHA, Optional.empty(), "0123456789ab",
                    Instant.ofEpochMilli(1_700_000_000_000L), 3, 1_114_112)), instances);
        }
    }

    @Test
    void aSnapshotIsTheGuestsTextByteForByte() {
        try (FakeAdminServer s = new FakeAdminServer(); Rift rift = connect(s)) {
            s.respond("GET /imposters/4545/wasm/snapshot", 200, "{ \"count\" : 2 }");
            Imposter imp = created(s, rift);
            assertEquals("{ \"count\" : 2 }", imp.wasmSnapshot());
            assertEquals("{ \"count\" : 2 }", imp.wasmSnapshot("k 1/2"));
            assertEquals("/imposters/4545/wasm/snapshot?instance=k%201%2F2", last(s).path());
            assertThrows(IllegalArgumentException.class, () -> imp.wasmSnapshot(" "));
        }
    }

    @Test
    void snapshotRefusalsKeepTheEnginesStatus() {
        try (FakeAdminServer s = new FakeAdminServer(); Rift rift = connect(s)) {
            s.respond("GET /imposters/4545", 200, IMP);
            s.respond("GET /imposters/4545/wasm/snapshot", 409,
                    "{\"errors\":[{\"code\":\"resource conflict\",\"message\":\"not instantiated\"}]}");
            Imposter imp = created(s, rift);
            EngineError conflict = assertThrows(EngineError.class, imp::wasmSnapshot);
            assertEquals(409, conflict.code());

            // No introspect export: the route exists, the imposter exists, so the 404 is the engine's answer.
            s.respond("GET /imposters/4545/wasm/snapshot", 404,
                    "{\"errors\":[{\"code\":\"no such resource\",\"message\":\"no rift:mock/introspect export\"}]}");
            EngineError missing = assertThrows(EngineError.class, imp::wasmSnapshot);
            assertEquals(404, missing.code());
            assertTrue(missing.getMessage().contains("introspect"), missing.getMessage());
        }
    }

    @Test
    void aComponentIsUploadedAsRawWasm() {
        try (FakeAdminServer s = new FakeAdminServer(); Rift rift = connect(s)) {
            s.respond("POST /components", 201, COMPONENT);
            ComponentInfo info = rift.components().upload("\0asm".getBytes(StandardCharsets.UTF_8));
            assertEquals("application/wasm", last(s).headers().get("content-type"));
            assertEquals("\0asm", last(s).body());
            assertEquals(SHA, info.sha256());
            assertEquals("sha256:" + SHA, info.reference());
            assertEquals(84211, info.size());
            assertEquals(Optional.of(Instant.parse("2026-11-20T12:00:00Z")), info.uploadedAt());
            assertEquals(List.of(4545), info.referencedBy());
            assertEquals(List.of("rift:mock/types@0.1.0", "wasi:cli/stdout@0.2.6"), info.imports());
        }
    }

    @Test
    void anEngineWithoutTheRegistryIsUnsupported() {
        try (FakeAdminServer s = new FakeAdminServer(); Rift rift = connect(s)) {
            assertThrows(UnsupportedOperationException.class, () -> rift.components().upload(new byte[] {1}));
            assertThrows(UnsupportedOperationException.class, () -> rift.components().list());
            assertThrows(UnsupportedOperationException.class, () -> rift.components().get(SHA));
            assertThrows(UnsupportedOperationException.class, () -> rift.components().delete(SHA));
        }
    }

    @Test
    void aComponentTheEngineDoesNotHoldIsEmptyAndDeletingItSucceeds() {
        try (FakeAdminServer s = new FakeAdminServer(); Rift rift = connect(s)) {
            s.respond("GET /components", 200, "{\"components\":[" + COMPONENT + "]}");
            assertEquals(1, rift.components().list().size());
            assertEquals(Optional.empty(), rift.components().get(SHA));
            rift.components().delete("sha256:" + SHA);
            assertTrue(s.received().stream().anyMatch(r -> r.method().equals("DELETE") && r.path().equals("/components/" + SHA)));
        }
    }

    @Test
    void aReferencedComponentCannotBeDeleted() {
        try (FakeAdminServer s = new FakeAdminServer(); Rift rift = connect(s)) {
            s.respond("DELETE /components/" + SHA, 409,
                    "{\"errors\":[{\"code\":\"409\",\"message\":\"component is referenced by imposters 4545\"}]}");
            EngineError e = assertThrows(EngineError.class, () -> rift.components().delete(SHA));
            assertEquals(409, e.code());
            assertTrue(e.getMessage().contains("4545"), e.getMessage());
        }
    }

    @Test
    void aMistypedHashIsAnArgumentErrorNotAComponentThatIsMissing() {
        try (FakeAdminServer s = new FakeAdminServer(); Rift rift = connect(s)) {
            assertThrows(IllegalArgumentException.class, () -> rift.components().get("abc"));
            assertThrows(IllegalArgumentException.class, () -> rift.components().delete(SHA.toUpperCase()));
            assertTrue(s.received().isEmpty());
        }
    }
}
