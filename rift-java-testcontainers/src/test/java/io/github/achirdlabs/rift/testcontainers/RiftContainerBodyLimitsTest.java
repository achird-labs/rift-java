package io.github.achirdlabs.rift.testcontainers;

import org.junit.jupiter.api.Test;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The body and journal limits reach a rift 0.22.0 container through the engine's env vars (#309). */
class RiftContainerBodyLimitsTest {

    @Test
    void theLimitsAreSetAsTheEnginesEnvVars() {
        try (RiftContainer container = new RiftContainer()
                .withMaxBodySize(3_000_000_000L).withBodyMemoryBudget(0).withJournalMaxBytes(4096)) {
            assertEquals("3000000000", container.getEnvMap().get("RIFT_MAX_BODY_SIZE"));
            assertEquals("0", container.getEnvMap().get("RIFT_BODY_MEMORY_BUDGET"));
            assertEquals("4096", container.getEnvMap().get("RIFT_JOURNAL_MAX_BYTES"));
        }
    }

    @Test
    void unsetLimitsSetNothing() {
        try (RiftContainer container = new RiftContainer()) {
            assertNull(container.getEnvMap().get("RIFT_MAX_BODY_SIZE"));
            assertNull(container.getEnvMap().get("RIFT_BODY_MEMORY_BUDGET"));
            assertNull(container.getEnvMap().get("RIFT_JOURNAL_MAX_BYTES"));
        }
    }

    @Test
    void outOfRangeValuesAreRefused() {
        try (RiftContainer container = new RiftContainer()) {
            assertEquals("maxBodySize must be at least 1 byte, was 0",
                    assertThrows(IllegalArgumentException.class, () -> container.withMaxBodySize(0)).getMessage());
            assertEquals("bodyMemoryBudget must be 0 (unbounded) or more bytes, was -1",
                    assertThrows(IllegalArgumentException.class, () -> container.withBodyMemoryBudget(-1)).getMessage());
            assertEquals("journalMaxBytes must be at least 1 byte, was 0",
                    assertThrows(IllegalArgumentException.class, () -> container.withJournalMaxBytes(0)).getMessage());
        }
    }

    @Test
    void anImageTagOlderThan022IsRefusedByEverySetter() {
        try (RiftContainer container = new RiftContainer(DockerImageName.parse("zainalpour/rift-proxy:v0.21.0"))) {
            assertEquals("maxBodySize needs a rift engine >= 0.22.0, but the image tag is v0.21.0 "
                    + "(the older engine ignores RIFT_MAX_BODY_SIZE)",
                    assertThrows(IllegalArgumentException.class, () -> container.withMaxBodySize(1024)).getMessage());
            assertEquals("bodyMemoryBudget needs a rift engine >= 0.22.0, but the image tag is v0.21.0 "
                    + "(the older engine ignores RIFT_BODY_MEMORY_BUDGET)",
                    assertThrows(IllegalArgumentException.class, () -> container.withBodyMemoryBudget(0)).getMessage());
            assertEquals("journalMaxBytes needs a rift engine >= 0.22.0, but the image tag is v0.21.0 "
                    + "(the older engine ignores RIFT_JOURNAL_MAX_BYTES)",
                    assertThrows(IllegalArgumentException.class, () -> container.withJournalMaxBytes(1)).getMessage());
        }
        for (String old : new String[] {"0.21", "v0.9.3", "0.21.9-static"}) {
            try (RiftContainer container = new RiftContainer(DockerImageName.parse("zainalpour/rift-proxy:" + old))) {
                assertThrows(IllegalArgumentException.class, () -> container.withJournalMaxBytes(1), old);
            }
        }
    }

    @Test
    void a022OrNewerTagAndAnUnversionedOneAreAccepted() {
        for (String tag : new String[] {"v0.22.0", "0.22", "0.22.0-rc1", "v0.100.0", "1.0.0", "latest"}) {
            try (RiftContainer container = new RiftContainer(DockerImageName.parse("zainalpour/rift-proxy:" + tag))
                    .withJournalMaxBytes(4096)) {
                assertEquals("4096", container.getEnvMap().get("RIFT_JOURNAL_MAX_BYTES"), tag);
            }
        }
    }

    @Test
    void theBudgetIsCheckedAgainstTheCeilingWhenTheContainerStarts() {
        try (RiftContainer below = new RiftContainer().withMaxBodySize(2048).withBodyMemoryBudget(1024)) {
            assertEquals("bodyMemoryBudget (1024) is below maxBodySize (2048), which the engine refuses; raise it, or set 0 "
                    + "for no budget", assertThrows(IllegalArgumentException.class, below::requireBudgetCoversCeiling).getMessage());
        }
        try (RiftContainer alone = new RiftContainer().withBodyMemoryBudget(1024)) {
            assertEquals("bodyMemoryBudget (1024) is below the engine's default maxBodySize (536870912), which the engine "
                    + "refuses; raise it, lower maxBodySize, or set 0 for no budget",
                    assertThrows(IllegalArgumentException.class, alone::requireBudgetCoversCeiling).getMessage());
        }
        try (RiftContainer huge = new RiftContainer().withMaxBodySize((1L << 30) + 1)) {
            assertEquals("maxBodySize (1073741825) is above the engine's default bodyMemoryBudget (1073741824), which the "
                    + "engine refuses; set bodyMemoryBudget too",
                    assertThrows(IllegalArgumentException.class, huge::requireBudgetCoversCeiling).getMessage());
        }
        try (RiftContainer ok = new RiftContainer().withMaxBodySize(1L << 30)) {
            ok.requireBudgetCoversCeiling();
        }
        try (RiftContainer unbounded = new RiftContainer().withMaxBodySize(5_000_000_000L).withBodyMemoryBudget(0)) {
            unbounded.requireBudgetCoversCeiling();
        }
        try (RiftContainer equal = new RiftContainer().withMaxBodySize(2048).withBodyMemoryBudget(2048)) {
            equal.requireBudgetCoversCeiling();
        }
    }
}
