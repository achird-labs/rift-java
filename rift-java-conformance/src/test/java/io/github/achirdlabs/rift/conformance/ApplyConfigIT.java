package io.github.achirdlabs.rift.conformance;

import io.github.achirdlabs.rift.ApplyResult;
import io.github.achirdlabs.rift.Rift;
import io.github.achirdlabs.rift.error.EngineUnavailable;
import io.github.achirdlabs.rift.json.JsonValue;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.net.ServerSocket;
import java.util.List;
import java.util.stream.Stream;

import static io.github.achirdlabs.rift.conformance.LiveEngine.engine;
import static io.github.achirdlabs.rift.conformance.LiveEngine.gatedTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code Rift.applyConfig} against a live engine (#264). The report names the ports it changed —
 * the engine sends port arrays, which the SDK used to read as 0 — and an unchanged config changes
 * nothing. Over the admin API the engine applies the config but cannot yet report what changed
 * (achird-labs/rift#1304), which the SDK says rather than inventing a report. Needs {@code RIFT_IT=1}.
 */
class ApplyConfigIT {

    @TestFactory
    Stream<DynamicTest> theEmbeddedReportNamesTheCreatedPort() {
        return gatedTo(ConformanceTransport.EMBEDDED, "rift_apply_config is the only surface that reports today",
                "applyConfig reports the created port, then nothing for the same config", () -> {
            try (Rift rift = engine()) {
                int port = freePort();
                JsonValue config = config(port);

                ApplyResult first = rift.applyConfig(config);
                assertEquals(List.of(port), first.created());
                assertEquals(List.of(), first.failed());

                assertTrue(rift.applyConfig(config).changedNothing(), "the same config again is a no-op");
            }
        });
    }

    @TestFactory
    Stream<DynamicTest> theAdminApiAppliesButCannotYetReport() {
        return gatedTo(ConformanceTransport.SPAWN, "PUT /imposters answers with the imposter list, not a report",
                "applyConfig over the admin API reconciles, then refuses to invent a report", () -> {
            try (Rift rift = engine()) {
                int port = freePort();

                EngineUnavailable e = assertThrows(EngineUnavailable.class, () -> rift.applyConfig(config(port)));
                assertTrue(e.getMessage().contains("replaceAll"), e.getMessage());
                assertTrue(rift.imposter(port).isPresent(), "the config was applied, not just reloaded from the engine's own sources");
            }
        });
    }

    private static JsonValue config(int port) {
        return JsonValue.parse("{\"imposters\":[{\"port\":" + port + ",\"protocol\":\"http\",\"name\":\"applied\","
                + "\"stubs\":[{\"responses\":[{\"is\":{\"statusCode\":204}}]}]}]}");
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
