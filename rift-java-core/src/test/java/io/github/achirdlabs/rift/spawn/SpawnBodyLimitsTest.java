package io.github.achirdlabs.rift.spawn;

import io.github.achirdlabs.rift.SpawnOptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The body and journal limits a spawned rift 0.22.0 engine takes (#309, rift#1439, rift#1441). */
class SpawnBodyLimitsTest {

    private static List<String> command(SpawnOptions opts) {
        return RiftProcess.buildCommand(Path.of("/usr/bin/rift"), opts, 2525, Path.of("/tmp/rift.pid"));
    }

    @Test
    void theLimitsAreGlobalOptionsBeforeStartInPlainBytes() {
        List<String> cmd = command(SpawnOptions.builder()
                .maxBodySize(3_000_000_000L).bodyMemoryBudget(4_000_000_000L).journalMaxBytes(65_536).build());
        int start = cmd.indexOf("start");
        assertEquals(cmd.size() - 1, start);
        for (String flag : List.of("--max-body-size", "--body-memory-budget", "--journal-max-bytes")) {
            assertTrue(cmd.indexOf(flag) >= 0 && cmd.indexOf(flag) < start, flag + " in " + cmd);
        }
        assertEquals("3000000000", cmd.get(cmd.indexOf("--max-body-size") + 1));
        assertEquals("4000000000", cmd.get(cmd.indexOf("--body-memory-budget") + 1));
        assertEquals("65536", cmd.get(cmd.indexOf("--journal-max-bytes") + 1));
    }

    @Test
    void unsetLimitsAddNothing() {
        SpawnOptions opts = SpawnOptions.builder().build();
        List<String> cmd = command(opts);
        assertFalse(cmd.contains("--max-body-size"), cmd.toString());
        assertFalse(cmd.contains("--body-memory-budget"), cmd.toString());
        assertFalse(cmd.contains("--journal-max-bytes"), cmd.toString());
        assertTrue(opts.maxBodySize().isEmpty() && opts.bodyMemoryBudget().isEmpty() && opts.journalMaxBytes().isEmpty());
    }

    @Test
    void aZeroBudgetIsUnboundedAndAccepted() {
        List<String> cmd = command(SpawnOptions.builder().maxBodySize(2_000_000_000L).bodyMemoryBudget(0).build());
        assertEquals("0", cmd.get(cmd.indexOf("--body-memory-budget") + 1));
    }

    @Test
    void outOfRangeValuesAreRefusedAtTheSetter() {
        assertEquals("maxBodySize must be at least 1 byte, was 0",
                assertThrows(IllegalArgumentException.class, () -> SpawnOptions.builder().maxBodySize(0)).getMessage());
        assertEquals("bodyMemoryBudget must be 0 (unbounded) or more bytes, was -1",
                assertThrows(IllegalArgumentException.class, () -> SpawnOptions.builder().bodyMemoryBudget(-1)).getMessage());
        assertEquals("journalMaxBytes must be at least 1 byte, was 0",
                assertThrows(IllegalArgumentException.class, () -> SpawnOptions.builder().journalMaxBytes(0)).getMessage());
        assertEquals("journalMaxBytes must be at least 1 byte, was -1",
                assertThrows(IllegalArgumentException.class, () -> SpawnOptions.builder().journalMaxBytes(-1)).getMessage());
    }

    @Test
    void aBudgetBelowTheCeilingIsRefusedAsTheEngineWould() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SpawnOptions.builder().maxBodySize(2048).bodyMemoryBudget(1024).build());
        assertEquals("bodyMemoryBudget (1024) is below maxBodySize (2048), which the engine refuses; "
                + "raise it, or set 0 for no budget", e.getMessage());
    }

    @Test
    void aBudgetAloneIsCheckedAgainstTheEnginesDefaultCeiling() {
        // the engine fills in its 512 MiB maxBodySize before comparing (rift-ffi lib.rs, server.rs body_limits_from)
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SpawnOptions.builder().bodyMemoryBudget(256L << 20).build());
        assertEquals("bodyMemoryBudget (268435456) is below the engine's default maxBodySize (536870912), which the "
                + "engine refuses; raise it, lower maxBodySize, or set 0 for no budget", e.getMessage());
        SpawnOptions.builder().bodyMemoryBudget(512L << 20).build();
        SpawnOptions.builder().bodyMemoryBudget(256L << 20).maxBodySize(256L << 20).build();
    }

    @Test
    void aCeilingAboveTheDefaultBudgetNeedsABudget() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SpawnOptions.builder().maxBodySize(2_000_000_000L).build());
        assertEquals("maxBodySize (2000000000) is above the engine's default bodyMemoryBudget (1073741824), which "
                + "the engine refuses; set bodyMemoryBudget too", e.getMessage());
    }

    @Test
    void theLimitsNeedAnEngineThatHasTheFlags() {
        IllegalArgumentException max = assertThrows(IllegalArgumentException.class,
                () -> SpawnOptions.builder().version("0.21.0").maxBodySize(1024).build());
        assertEquals("maxBodySize needs a rift engine >= 0.22.0, but version is 0.21.0 "
                + "(the older CLI has no --max-body-size flag)", max.getMessage());
        IllegalArgumentException journal = assertThrows(IllegalArgumentException.class,
                () -> SpawnOptions.builder().version("0.21.0").journalMaxBytes(1024).build());
        assertEquals("journalMaxBytes needs a rift engine >= 0.22.0, but version is 0.21.0 "
                + "(the older CLI has no --journal-max-bytes flag)", journal.getMessage());
        IllegalArgumentException budget = assertThrows(IllegalArgumentException.class,
                () -> SpawnOptions.builder().version("0.21.0").bodyMemoryBudget(0).build());
        assertEquals("bodyMemoryBudget needs a rift engine >= 0.22.0, but version is 0.21.0 "
                + "(the older CLI has no --body-memory-budget flag)", budget.getMessage());
        SpawnOptions.builder().version("0.22.0").maxBodySize(1024).build();
    }
}
