package io.github.achirdlabs.rift.testcontainers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.utility.DockerImageName;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code allowWasm} and {@code strictWasm} reach a rift 0.25.0 container through the engine's env vars (rift-java#354). */
class RiftContainerWasmTest {

    @Test
    void allowWasmIsTheEnginesEnvVar() {
        try (RiftContainer container = new RiftContainer().withAllowWasm(false)) {
            assertEquals("false", container.getEnvMap().get("RIFT_ALLOW_WASM"));
        }
        try (RiftContainer container = new RiftContainer()) {
            assertNull(container.getEnvMap().get("RIFT_ALLOW_WASM"));
            assertTrue(container.strictWasmCopy().isEmpty());
        }
    }

    @Test
    void theTrustListIsReadFromTheHostToBeCopiedIn(@TempDir Path dir) throws Exception {
        Path list = Files.writeString(dir.resolve("trusted.txt"), "# twins\n" + "a".repeat(64) + "\n");
        try (RiftContainer container = new RiftContainer().withStrictWasm(list)) {
            assertArrayEquals(Files.readAllBytes(list), container.strictWasmCopy().orElseThrow().getBytes());
        }
        try (RiftContainer missing = new RiftContainer().withStrictWasm(dir.resolve("absent.txt"))) {
            assertThrows(UncheckedIOException.class, missing::strictWasmCopy);
        }
    }

    @Test
    void anImageTagOlderThan025IsRefused() {
        try (RiftContainer container = new RiftContainer(DockerImageName.parse("zainalpour/rift-proxy:v0.24.0"))) {
            assertEquals("allowWasm needs a rift engine >= 0.25.0, but the image tag is v0.24.0 "
                    + "(the older engine ignores RIFT_ALLOW_WASM)",
                    assertThrows(IllegalArgumentException.class, () -> container.withAllowWasm(true)).getMessage());
            assertThrows(IllegalArgumentException.class, () -> container.withStrictWasm(Path.of("t.txt")));
        }
        try (RiftContainer latest = new RiftContainer(DockerImageName.parse("zainalpour/rift-proxy:latest"))) {
            latest.withAllowWasm(true);
        }
    }
}
