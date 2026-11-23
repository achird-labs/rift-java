package io.github.achirdlabs.rift.spawn;

import io.github.achirdlabs.rift.SpawnOptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code --allow-wasm} and {@code --strict-wasm} on a spawned rift 0.25.0 engine (rift#1752, rift#1757). */
class SpawnWasmOptionsTest {

    private static List<String> command(SpawnOptions opts) {
        return RiftProcess.buildCommand(Path.of("/usr/bin/rift"), opts, 2525, Path.of("/tmp/rift.pid"));
    }

    @Test
    void bothAreGlobalOptionsBeforeStart() {
        Path list = Path.of("/srv/trusted-components.txt");
        List<String> cmd = command(SpawnOptions.builder().allowWasm(false).strictWasm(list).build());
        int start = cmd.indexOf("start");
        assertTrue(cmd.indexOf("--allow-wasm") >= 0 && cmd.indexOf("--allow-wasm") < start, cmd.toString());
        assertEquals("false", cmd.get(cmd.indexOf("--allow-wasm") + 1), "a value: the engine's default is on");
        assertEquals(list.toString(), cmd.get(cmd.indexOf("--strict-wasm") + 1));
        assertTrue(cmd.indexOf("--strict-wasm") < start);
    }

    @Test
    void allowWasmTrueIsSentAsWritten() {
        List<String> cmd = command(SpawnOptions.builder().allowWasm(true).build());
        assertEquals("true", cmd.get(cmd.indexOf("--allow-wasm") + 1));
    }

    @Test
    void unsetAddsNothing() {
        SpawnOptions opts = SpawnOptions.builder().build();
        assertFalse(command(opts).contains("--allow-wasm"));
        assertFalse(command(opts).contains("--strict-wasm"));
        assertTrue(opts.allowWasm().isEmpty());
        assertTrue(opts.strictWasm().isEmpty());
    }

    @Test
    void anOlderEngineIsRefusedAtBuild() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SpawnOptions.builder().version("0.24.0").allowWasm(false).build());
        assertEquals("allowWasm needs a rift engine >= 0.25.0, but version is 0.24.0 (the older CLI has no"
                + " --allow-wasm flag)", e.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> SpawnOptions.builder().version("0.24.0").strictWasm(Path.of("t.txt")).build());
        assertThrows(NullPointerException.class, () -> SpawnOptions.builder().strictWasm(null));
    }
}
