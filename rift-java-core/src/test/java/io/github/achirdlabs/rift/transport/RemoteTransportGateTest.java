package io.github.achirdlabs.rift.transport;

import io.github.achirdlabs.rift.ConnectOptions;
import io.github.achirdlabs.rift.Imposter;
import io.github.achirdlabs.rift.Rift;
import io.github.achirdlabs.rift.VersionCheck;
import io.github.achirdlabs.rift.error.CommunicationError;
import io.github.achirdlabs.rift.error.EngineError;
import io.github.achirdlabs.rift.error.EngineUnavailable;
import io.github.achirdlabs.rift.error.ImposterNotFound;
import io.github.achirdlabs.rift.error.InvalidDefinition;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Optional;

import static io.github.achirdlabs.rift.dsl.RiftDsl.imposter;
import static io.github.achirdlabs.rift.dsl.RiftDsl.ok;
import static io.github.achirdlabs.rift.dsl.RiftDsl.onGet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteTransportGateTest {

    private static final String IMPOSTER_JSON = "{\"port\":4545,\"protocol\":\"http\",\"stubs\":[]}";

    private static Rift connectNoPreflight(FakeAdminServer server) {
        return Rift.connect(ConnectOptions.builder(server.baseUri()).versionCheck(VersionCheck.OFF).build());
    }

    @Test
    void createGetDeleteRoundTrip() {
        try (FakeAdminServer server = new FakeAdminServer()) {
            server.respond("POST /imposters", 201, IMPOSTER_JSON);
            server.respond("GET /imposters/4545", 200, IMPOSTER_JSON);
            server.respond("DELETE /imposters/4545", 200, IMPOSTER_JSON);

            try (Rift rift = connectNoPreflight(server)) {
                Imposter imp = rift.create(imposter("users").port(4545)
                        .stub(onGet("/health").willReturn(ok().withTextBody("OK"))));
                assertEquals(4545, imp.port());
                assertEquals(4545, rift.imposter(4545).orElseThrow().port());
                imp.delete();
            }
            // the create request carried the imposter document
            assertTrue(server.received().stream().anyMatch(r -> r.method().equals("POST") && r.path().equals("/imposters")));
        }
    }

    @Test
    void connectionRefusedMapsToEngineUnavailable() {
        // nothing listening on this port
        Rift rift = Rift.connect(ConnectOptions.builder(URI.create("http://127.0.0.1:1")).versionCheck(VersionCheck.OFF).build());
        assertThrows(EngineUnavailable.class, () -> rift.create(imposter("x").port(4545)));
    }

    @Test
    void badRequestMapsToInvalidDefinitionWithEngineMessage() {
        try (FakeAdminServer server = new FakeAdminServer()) {
            server.respond("POST /imposters", 400, "{\"errors\":[{\"code\":\"bad data\",\"message\":\"port already in use\"}]}");
            try (Rift rift = connectNoPreflight(server)) {
                InvalidDefinition ex = assertThrows(InvalidDefinition.class, () -> rift.create(imposter("x").port(4545)));
                assertTrue(ex.getMessage().contains("port already in use"), ex.getMessage());
            }
        }
    }

    @Test
    void missingImposterOnGetReturnsEmpty() {
        try (FakeAdminServer server = new FakeAdminServer()) {
            server.respond("GET /imposters/9999", 404, "{\"errors\":[{\"code\":\"no such resource\",\"message\":\"missing\"}]}");
            try (Rift rift = connectNoPreflight(server)) {
                assertEquals(Optional.empty(), rift.imposter(9999));
            }
        }
    }

    @Test
    void deleteMissingImposterThrowsImposterNotFound() {
        try (FakeAdminServer server = new FakeAdminServer()) {
            server.respond("POST /imposters", 201, IMPOSTER_JSON);
            server.respond("DELETE /imposters/4545", 404, "{\"errors\":[{\"code\":\"no such resource\",\"message\":\"missing\"}]}");
            try (Rift rift = connectNoPreflight(server)) {
                Imposter imp = rift.create(imposter("x").port(4545));
                ImposterNotFound ex = assertThrows(ImposterNotFound.class, imp::delete);
                assertEquals(4545, ex.port());
            }
        }
    }

    @Test
    void serverErrorMapsToEngineError() {
        try (FakeAdminServer server = new FakeAdminServer()) {
            server.respond("POST /imposters", 503, "{\"errors\":[{\"code\":\"unavailable\",\"message\":\"overloaded\"}]}");
            try (Rift rift = connectNoPreflight(server)) {
                EngineError ex = assertThrows(EngineError.class, () -> rift.create(imposter("x").port(4545)));
                assertEquals(503, ex.code());
            }
        }
    }

    /**
     * The backend-unavailable door (engine 0.16.0, achird-labs/rift#802) carries {@code feature} and
     * {@code detail} <em>inside</em> {@code errors[0]}, beyond the usual {@code code}/{@code type}/
     * {@code message}. Envelope decoding must key off {@code errors[0].message} and ignore the rest
     * rather than reject the object, so a Redis-backed flow-store outage surfaces as its 503 instead
     * of a decode failure.
     *
     * <p>The {@code errors} envelope is the whole body: engine 0.18.0 (achird-labs/rift#801) dropped
     * the deprecated top-level {@code error}/{@code feature}/{@code detail} siblings, so pinning them
     * here would document a shape no supported engine sends.
     */
    @Test
    void backendUnavailableEnvelopeWithExtraKeysMapsToEngineError() {
        try (FakeAdminServer server = new FakeAdminServer()) {
            server.respond("POST /imposters", 503, """
                    {"errors":[{"code":"503","type":"backend unavailable",\
                    "message":"flowState: redis connection refused",\
                    "feature":"flowState","detail":"redis connection refused"}]}""");
            try (Rift rift = connectNoPreflight(server)) {
                EngineError ex = assertThrows(EngineError.class, () -> rift.create(imposter("x").port(4545)));
                assertEquals(503, ex.code());
                assertEquals("flowState: redis connection refused", ex.getMessage());
            }
        }
    }

    /**
     * The same door's non-{@code BackendUnavailable} branch: a 500 whose {@code errors[0]} carries no
     * {@code feature} key at all. Decoding must not require one — that key is specific to the
     * backend-unavailable branch — so this pins the plain {@code code}/{@code type}/{@code message}
     * shape alongside the 503's richer one, and neither can regress independently.
     *
     * <p>The {@code errors} envelope is the whole body here too: engine 0.18.0 (achird-labs/rift#801)
     * dropped the deprecated top-level {@code error}/{@code detail} siblings this fixture used to
     * pin, so keeping them would document a shape no supported engine sends.
     */
    @Test
    void internalErrorEnvelopeWithoutFeatureMapsToEngineError() {
        try (FakeAdminServer server = new FakeAdminServer()) {
            server.respond("POST /imposters", 500, """
                    {"errors":[{"code":"500","type":"internal error",\
                    "message":"flow store write failed: redis connection refused"}]}""");
            try (Rift rift = connectNoPreflight(server)) {
                EngineError ex = assertThrows(EngineError.class, () -> rift.create(imposter("x").port(4545)));
                assertEquals(500, ex.code());
                assertEquals("flow store write failed: redis connection refused", ex.getMessage());
            }
        }
    }

    @Test
    void unparseableSuccessBodyMapsToCommunicationError() {
        try (FakeAdminServer server = new FakeAdminServer()) {
            server.respond("POST /imposters", 201, "this is not json");
            try (Rift rift = connectNoPreflight(server)) {
                assertThrows(CommunicationError.class, () -> rift.create(imposter("x").port(4545)));
            }
        }
    }

    @Test
    void versionPreflightFailModeThrowsEngineUnavailable() {
        try (FakeAdminServer server = new FakeAdminServer()) {
            server.respond("GET /config", 200, "{\"version\":\"0.0.1\",\"commit\":\"abc\"}");
            EngineUnavailable ex = assertThrows(EngineUnavailable.class,
                    () -> Rift.connect(ConnectOptions.builder(server.baseUri()).versionCheck(VersionCheck.FAIL).build()));
            assertTrue(ex.getMessage().toLowerCase().contains("requires"), ex.getMessage());
        }
    }

    @Test
    void versionPreflightOffModeSkipsCheck() {
        try (FakeAdminServer server = new FakeAdminServer()) {
            server.respond("GET /config", 200, "{\"version\":\"0.0.1\",\"commit\":\"abc\"}");
            server.respond("DELETE /imposters", 200, "{\"imposters\":[]}");
            try (Rift rift = Rift.connect(ConnectOptions.builder(server.baseUri()).versionCheck(VersionCheck.OFF).build())) {
                rift.deleteAll(); // succeeds despite the old engine version
            }
        }
    }

    @Test
    void hostResolverControlsImposterUri() {
        try (FakeAdminServer server = new FakeAdminServer()) {
            server.respond("POST /imposters", 201, IMPOSTER_JSON);
            try (Rift rift = Rift.connect(ConnectOptions.builder(server.baseUri())
                    .versionCheck(VersionCheck.OFF)
                    .hostResolver(port -> URI.create("http://sut-host:" + (port + 10000)))
                    .build())) {
                Imposter imp = rift.create(imposter("x").port(4545));
                assertEquals(URI.create("http://sut-host:14545"), imp.uri());
            }
        }
    }

    @Test
    void flowStateGetMissingReturnsEmpty() {
        try (FakeAdminServer server = new FakeAdminServer()) {
            server.respond("POST /imposters", 201, IMPOSTER_JSON);
            server.respond("GET /admin/imposters/4545/flow-state/flow-1/token", 404, "{\"errors\":[{\"code\":\"no such resource\",\"message\":\"x\"}]}");
            try (Rift rift = connectNoPreflight(server)) {
                Imposter imp = rift.create(imposter("x").port(4545));
                assertEquals(Optional.empty(), imp.flowState("flow-1").get("token"));
            }
        }
    }

    @Test
    void apiKeyIsSentAsAuthorizationHeader() {
        try (FakeAdminServer server = new FakeAdminServer()) {
            server.respond("DELETE /imposters", 200, "{\"imposters\":[]}");
            try (Rift rift = Rift.connect(ConnectOptions.builder(server.baseUri())
                    .versionCheck(VersionCheck.OFF).apiKey("secret-token").build())) {
                rift.deleteAll();
            }
            boolean sent = server.received().stream()
                    .anyMatch(r -> "secret-token".equals(r.headers().get("authorization")));
            assertTrue(sent, "apiKey must be sent as the Authorization header");
        }
    }
}
