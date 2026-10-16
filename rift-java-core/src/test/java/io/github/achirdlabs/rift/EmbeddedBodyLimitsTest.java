package io.github.achirdlabs.rift;

import org.junit.jupiter.api.Test;

import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The body and journal limits {@link EmbeddedOptions} carries to {@code rift_serve_admin} (#309). */
class EmbeddedBodyLimitsTest {

    @Test
    void theLimitsAreCarriedWhenSetAndEmptyOtherwise() {
        EmbeddedOptions set = EmbeddedOptions.builder()
                .maxBodySize(1024).bodyMemoryBudget(0).journalMaxBytes(4096).build();
        assertEquals(OptionalLong.of(1024), set.maxBodySize());
        assertEquals(OptionalLong.of(0), set.bodyMemoryBudget());
        assertEquals(OptionalLong.of(4096), set.journalMaxBytes());

        EmbeddedOptions unset = EmbeddedOptions.builder().build();
        assertEquals(OptionalLong.empty(), unset.maxBodySize());
        assertEquals(OptionalLong.empty(), unset.bodyMemoryBudget());
        assertEquals(OptionalLong.empty(), unset.journalMaxBytes());
    }

    @Test
    void outOfRangeValuesAreRefusedAtTheSetter() {
        assertEquals("maxBodySize must be at least 1 byte, was 0",
                assertThrows(IllegalArgumentException.class, () -> EmbeddedOptions.builder().maxBodySize(0)).getMessage());
        assertEquals("maxBodySize must be at least 1 byte, was -5",
                assertThrows(IllegalArgumentException.class, () -> EmbeddedOptions.builder().maxBodySize(-5)).getMessage());
        assertEquals("bodyMemoryBudget must be 0 (unbounded) or more bytes, was -1",
                assertThrows(IllegalArgumentException.class, () -> EmbeddedOptions.builder().bodyMemoryBudget(-1)).getMessage());
        assertEquals("journalMaxBytes must be at least 1 byte, was 0",
                assertThrows(IllegalArgumentException.class, () -> EmbeddedOptions.builder().journalMaxBytes(0)).getMessage());
    }

    @Test
    void theBudgetIsCheckedAgainstTheCeilingAtBuild() {
        assertEquals("bodyMemoryBudget (10) is below maxBodySize (20), which the engine refuses; raise it, or set 0 for no budget",
                assertThrows(IllegalArgumentException.class,
                        () -> EmbeddedOptions.builder().maxBodySize(20).bodyMemoryBudget(10).build()).getMessage());
        assertEquals("maxBodySize (1073741825) is above the engine's default bodyMemoryBudget (1073741824), which "
                        + "the engine refuses; set bodyMemoryBudget too",
                assertThrows(IllegalArgumentException.class,
                        () -> EmbeddedOptions.builder().maxBodySize(1_073_741_825L).build()).getMessage());
        assertEquals("bodyMemoryBudget (1024) is below the engine's default maxBodySize (536870912), which the engine "
                        + "refuses; raise it, lower maxBodySize, or set 0 for no budget",
                assertThrows(IllegalArgumentException.class,
                        () -> EmbeddedOptions.builder().bodyMemoryBudget(1024).build()).getMessage());
        EmbeddedOptions.builder().maxBodySize(1_073_741_824L).build();
        EmbeddedOptions.builder().maxBodySize(20).bodyMemoryBudget(20).build();
        EmbeddedOptions.builder().maxBodySize(5_000_000_000L).bodyMemoryBudget(0).build();
        EmbeddedOptions.builder().maxBodySize(5_000_000_000L).bodyMemoryBudget(5_000_000_000L).build();
    }
}
