package io.github.achirdlabs.rift.spawn;

import com.sun.net.httpserver.HttpServer;
import io.github.achirdlabs.rift.SpawnOptions;
import io.github.achirdlabs.rift.error.EngineUnavailable;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The engine launch options a spawned engine takes (#287): where each reaches the rift CLI. */
class SpawnLaunchOptionsTest {

    private static List<String> command(SpawnOptions opts) {
        return RiftProcess.buildCommand(Path.of("/usr/bin/rift"), opts, 2525, Path.of("/tmp/rift.pid"));
    }

    @Test
    void metricsPortAndRequireAdminAuthAreGlobalOptionsBeforeStart() {
        List<String> cmd = command(SpawnOptions.builder().metricsPort(19090).requireAdminAuth(true).build());
        int start = cmd.indexOf("start");
        assertEquals(cmd.size() - 1, start);
        assertTrue(cmd.indexOf("--metrics-port") >= 0 && cmd.indexOf("--metrics-port") < start, cmd.toString());
        assertEquals("19090", cmd.get(cmd.indexOf("--metrics-port") + 1));
        assertTrue(cmd.indexOf("--require-admin-auth") >= 0 && cmd.indexOf("--require-admin-auth") < start, cmd.toString());
    }

    @Test
    void unsetOptionsAddNothing() {
        List<String> cmd = command(SpawnOptions.builder().build());
        assertFalse(cmd.contains("--metrics-port"), cmd.toString());
        assertFalse(cmd.contains("--require-admin-auth"), cmd.toString());
        assertFalse(cmd.contains("--api-key"), cmd.toString());
        assertFalse(RiftProcess.environment(SpawnOptions.builder().build()).containsKey("MB_APIKEY"));
    }

    @Test
    void theApiKeyTravelsInTheEnvironmentNeverOnTheCommandLine() {
        // argv is world-readable through ps; the environment is not.
        SpawnOptions opts = SpawnOptions.builder().apiKey("s3cret-token").build();
        assertFalse(String.join(" ", command(opts)).contains("s3cret-token"), command(opts).toString());
        assertEquals("s3cret-token", RiftProcess.environment(opts).get("MB_APIKEY"));
    }

    @Test
    void anEnvEntryForTheKeyIsRefusedBecauseTheClientWouldNotKnowIt() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SpawnOptions.builder().env(Map.of("MB_APIKEY", "x")).build());
        assertEquals("env carries MB_APIKEY: set it with apiKey(...) instead, so the returned client sends it too",
                e.getMessage());
        assertEquals(Map.of("OTHER", "x", "MB_APIKEY", "k"),
                RiftProcess.environment(SpawnOptions.builder().env(Map.of("OTHER", "x")).apiKey("k").build()));
    }

    @Test
    void aBlankApiKeyIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SpawnOptions.builder().apiKey("  "));
        assertTrue(e.getMessage().contains("blank"), e.getMessage());
    }

    @Test
    void metricsPortMustBeAFixedPort() {
        assertThrows(IllegalArgumentException.class, () -> SpawnOptions.builder().metricsPort(0));
        assertThrows(IllegalArgumentException.class, () -> SpawnOptions.builder().metricsPort(65536));
        assertEquals(1, SpawnOptions.builder().metricsPort(1).build().metricsPort().getAsInt());
    }

    @Test
    void requireAdminAuthNeedsAnEngineThatHasTheFlag() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SpawnOptions.builder().version("0.16.0").requireAdminAuth(true).build());
        assertEquals("requireAdminAuth needs a rift engine >= 0.17.0, but version is 0.16.0"
                + " (the older CLI has no --require-admin-auth flag)", e.getMessage());
        SpawnOptions.builder().version("0.16.0").requireAdminAuth(false).build();
        SpawnOptions.builder().version("0.17.0").requireAdminAuth(true).build();
    }

    @Test
    void aStartupProbeTheEngineRefusesFailsAtOnceAndSaysWhy() throws Exception {
        HttpServer engine = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        engine.createContext("/", exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            exchange.sendResponseHeaders("right".equals(auth) ? 200 : 401, -1);
            exchange.close();
        });
        engine.start();
        try {
            URI imposters = URI.create("http://127.0.0.1:" + engine.getAddress().getPort() + "/imposters");
            HttpClient client = HttpClient.newHttpClient();
            assertTrue(RiftProcess.poll(client, imposters, Optional.of("right")), "the key is sent as Authorization");
            EngineUnavailable wrong = assertThrows(EngineUnavailable.class,
                    () -> RiftProcess.poll(client, imposters, Optional.of("wrong")));
            assertEquals("the rift process answered 401 to its startup probe: it rejected SpawnOptions.apiKey",
                    wrong.getMessage());
            EngineUnavailable none = assertThrows(EngineUnavailable.class,
                    () -> RiftProcess.poll(client, imposters, Optional.empty()));
            assertEquals("the rift process answered 401 to its startup probe: it requires an admin key, but"
                    + " SpawnOptions.apiKey is unset", none.getMessage());
        } finally {
            engine.stop(0);
        }
    }
}
