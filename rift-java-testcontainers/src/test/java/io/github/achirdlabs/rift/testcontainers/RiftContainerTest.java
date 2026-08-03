package io.github.achirdlabs.rift.testcontainers;

import org.junit.jupiter.api.Test;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit coverage for {@link RiftContainer} configuration — no Docker required (nothing is started),
 * so these run on every CI lane. The real round-trip lives in {@code RiftContainerIT}.
 */
class RiftContainerTest {

    @Test
    void defaultImageUsesPinnedEngineVersion() {
        // AC4: single-sourced from the <rift.engine.version> property via resource filtering.
        assertEquals("0.17.0", RiftContainer.ENGINE_VERSION, "engine version resolved from filtered resource");
        try (RiftContainer container = new RiftContainer()) {
            assertEquals("zainalpour/rift-proxy:v0.17.0", container.configuredImageName());
        }
    }

    @Test
    void acceptsCustomImage() {
        try (RiftContainer container = new RiftContainer(DockerImageName.parse("acme/rift:9.9"))) {
            assertEquals("acme/rift:9.9", container.configuredImageName());
        }
    }

    @Test
    void exposesAdminPortByDefault() {
        try (RiftContainer container = new RiftContainer()) {
            assertTrue(container.getExposedPorts().contains(2525), "admin port 2525 exposed");
        }
    }

    @Test
    void withImposterPortsExposesEachPort() {
        try (RiftContainer container = new RiftContainer().withImposterPorts(4545, 4546)) {
            assertTrue(container.getExposedPorts().contains(4545), "4545 exposed");
            assertTrue(container.getExposedPorts().contains(4546), "4546 exposed");
            assertTrue(container.getExposedPorts().contains(2525), "admin port still exposed");
        }
    }

    @Test
    void withApiKeySetsEngineEnv() {
        // MB_APIKEY is the engine CLI's env for --apikey; the client side sends it as Authorization.
        try (RiftContainer container = new RiftContainer().withApiKey("s3cret")) {
            assertEquals("s3cret", container.getEnvMap().get("MB_APIKEY"));
        }
    }

    @Test
    void configurationMethodsAreFluent() {
        try (RiftContainer container = new RiftContainer()
                .withGateway()
                .withApiKey("k")
                .withImposterPorts(4545)) {
            assertEquals("k", container.getEnvMap().get("MB_APIKEY"));
            assertTrue(container.getExposedPorts().contains(4545));
        }
    }
}
