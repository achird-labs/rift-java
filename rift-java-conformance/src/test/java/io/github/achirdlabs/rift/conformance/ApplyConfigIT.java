package io.github.achirdlabs.rift.conformance;

import io.github.achirdlabs.rift.ApplyResult;
import io.github.achirdlabs.rift.Rift;
import io.github.achirdlabs.rift.json.JsonValue;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.net.ServerSocket;
import java.util.List;
import java.util.stream.Stream;

import static io.github.achirdlabs.rift.conformance.LiveEngine.engine;
import static io.github.achirdlabs.rift.conformance.LiveEngine.gated;
import static io.github.achirdlabs.rift.dsl.RiftDsl.imposter;
import static io.github.achirdlabs.rift.dsl.RiftDsl.onGet;
import static io.github.achirdlabs.rift.dsl.RiftDsl.status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code Rift.applyConfig} against a live engine (#264). The report names the ports it changed —
 * the engine sends port arrays, which the SDK used to read as 0 — and an unchanged config changes
 * nothing. Both surfaces report: embedded via {@code rift_apply_config}, spawn via {@code PUT
 * /imposters}, whose 200 carries the report since engine 0.20.0 (achird-labs/rift#1304). Needs
 * {@code RIFT_IT=1}.
 */
class ApplyConfigIT {

    @TestFactory
    Stream<DynamicTest> theReportNamesTheCreatedPort() {
        return gated("applyConfig reports the created port, then nothing for the same config", () -> {
            try (Rift rift = engine()) {
                int port = freePort();
                JsonValue config = config(port);

                ApplyResult first = rift.applyConfig(config);
                assertEquals(List.of(port), first.created());
                assertEquals(List.of(), first.failed());
                assertTrue(rift.imposter(port).isPresent(), "the config was applied, not just reloaded from the engine's own sources");

                assertTrue(rift.applyConfig(config).changedNothing(), "the same config again is a no-op");
            }
        });
    }

    @TestFactory
    Stream<DynamicTest> aPauseAndAResumeAreReportedAsToggled() {
        return gated("applyConfig reports an enabled-only change as toggled, both ways", () -> {
            try (Rift rift = engine()) {
                int port = freePort();
                rift.applyConfig(config(port));

                ApplyResult paused = rift.applyConfig(config(port, false));
                assertEquals(List.of(port), paused.toggled());
                assertEquals(List.of(), paused.created());
                assertEquals(List.of(), paused.replaced());
                assertEquals(List.of(), paused.stubPatched());
                assertEquals(List.of(), paused.deleted());
                assertFalse(paused.changedNothing(), "a pause is a change");

                ApplyResult resumed = rift.applyConfig(config(port, true));
                assertEquals(List.of(port), resumed.toggled());
                assertEquals(List.of(), resumed.replaced(), "a resume is not a replace");
            }
        });
    }

    private static JsonValue config(int port) {
        return config(port, true);
    }

    private static JsonValue config(int port, boolean enabled) {
        // The typed field, end to end: ImposterSpec.enabled(false) is what makes the engine report toggled.
        String imposter = imposter("applied").port(port).enabled(enabled).stub(onGet("/").willReturn(status(204)))
                .build().toJson();
        return JsonValue.parse("{\"imposters\":[" + imposter + "]}");
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
