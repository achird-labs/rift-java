package io.github.achirdlabs.rift.spawn;

import io.github.achirdlabs.rift.SpawnOptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The {@code --scripts-dir} a spawned engine reads lookup CSVs and modules under from rift 0.24.0 (rift#1617). */
class SpawnScriptsDirTest {

    private static List<String> command(SpawnOptions opts) {
        return RiftProcess.buildCommand(Path.of("/usr/bin/rift"), opts, 2525, Path.of("/tmp/rift.pid"));
    }

    @Test
    void scriptsDirIsAGlobalOptionBeforeStart() {
        List<String> cmd = command(SpawnOptions.builder().scriptsDir(Path.of("/srv/fixtures")).build());
        int flag = cmd.indexOf("--scripts-dir");
        assertTrue(flag >= 0 && flag < cmd.indexOf("start"), cmd.toString());
        assertEquals("/srv/fixtures", cmd.get(flag + 1));
    }

    @Test
    void unsetScriptsDirAddsNothing() {
        SpawnOptions opts = SpawnOptions.builder().build();
        assertFalse(command(opts).contains("--scripts-dir"));
        assertTrue(opts.scriptsDir().isEmpty());
    }

    @Test
    void nullScriptsDirIsRefused() {
        assertThrows(NullPointerException.class, () -> SpawnOptions.builder().scriptsDir(null));
    }
}
