package io.github.achirdlabs.rift.spawn;

import io.github.achirdlabs.rift.SpawnOptions;
import io.github.achirdlabs.rift.UpstreamTrust;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the rift spawn command's argument order. The engine CLI is {@code rift [OPTIONS] start}: every
 * admin option is a top-level option that clap rejects if it appears after the {@code start}
 * subcommand. A fast guard here catches that class of regression without spawning a real engine
 * (the only other guard, {@code CorpusReplayIT}, needs {@code RIFT_IT=1} + a downloaded binary).
 */
class RiftProcessTest {

    @Test
    void buildCommandPutsEveryOptionBeforeTheStartSubcommand() {
        SpawnOptions opts = SpawnOptions.builder()
                .host("127.0.0.1")
                .allowInjection(true)
                .localOnly(true)
                .logLevel("info")
                .build();

        Path binaryPath = Path.of("/usr/bin/rift");
        List<String> cmd = RiftProcess.buildCommand(binaryPath, opts, 2525, Path.of("/tmp/rift.pid"));

        assertEquals(binaryPath.toString(), cmd.get(0), "the binary is argv[0]");
        assertEquals("start", cmd.get(cmd.size() - 1), "the start subcommand must be last");

        int start = cmd.indexOf("start");
        for (String option : List.of("--port", "--host", "--allow-injection", "--local-only", "--loglevel", "--pidfile")) {
            int at = cmd.indexOf(option);
            assertTrue(at >= 0, () -> option + " must be present");
            assertTrue(at < start, () -> option + " must precede the start subcommand");
        }
        assertEquals("2525", cmd.get(cmd.indexOf("--port") + 1), "--port carries the admin port");
    }

    @Test
    void upstreamCaFileIsPassedAsAGlobalOption() {
        SpawnOptions opts = SpawnOptions.builder()
                .upstreamTrust(new UpstreamTrust.CaFile(Path.of("/etc/corp-ca.pem")))
                .build();
        List<String> cmd = RiftProcess.buildCommand(Path.of("/usr/bin/rift"), opts, 2525, Path.of("/tmp/rift.pid"));

        int at = cmd.indexOf("--upstream-ca-file");
        assertTrue(at >= 0 && at < cmd.indexOf("start"), "precedes start: " + cmd);
        assertEquals(Path.of("/etc/corp-ca.pem").toAbsolutePath().toString(), cmd.get(at + 1));
        assertFalse(cmd.contains("--upstream-tls-skip-verify"));
    }

    @Test
    void skipVerifyIsAFlag() {
        SpawnOptions opts = SpawnOptions.builder().upstreamTrust(new UpstreamTrust.SkipVerify()).build();
        List<String> cmd = RiftProcess.buildCommand(Path.of("/usr/bin/rift"), opts, 2525, Path.of("/tmp/rift.pid"));

        int at = cmd.indexOf("--upstream-tls-skip-verify");
        assertTrue(at >= 0 && at < cmd.indexOf("start"), "precedes start: " + cmd);
        assertFalse(cmd.contains("--upstream-ca-file"));
    }

    @Test
    void noTrustAddsNoTrustFlags() {
        List<String> cmd = RiftProcess.buildCommand(
                Path.of("/usr/bin/rift"), SpawnOptions.builder().build(), 2525, Path.of("/tmp/rift.pid"));
        assertFalse(cmd.contains("--upstream-ca-file"));
        assertFalse(cmd.contains("--upstream-tls-skip-verify"));
    }

    @Test
    void onlySkipVerifyWarns() {
        assertTrue(RiftProcess.skipVerifyWarning(SpawnOptions.builder()
                .upstreamTrust(new UpstreamTrust.SkipVerify()).build()).orElseThrow().contains("skip-verify"));
        assertTrue(RiftProcess.skipVerifyWarning(SpawnOptions.builder().build()).isEmpty());
        assertTrue(RiftProcess.skipVerifyWarning(SpawnOptions.builder()
                .upstreamTrust(new UpstreamTrust.CaFile(Path.of("/ca.pem"))).build()).isEmpty());
    }
}
