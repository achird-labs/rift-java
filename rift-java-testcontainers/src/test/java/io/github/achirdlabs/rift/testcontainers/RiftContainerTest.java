package io.github.achirdlabs.rift.testcontainers;

import io.github.achirdlabs.rift.UpstreamTrust;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.utility.DockerImageName;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit coverage for {@link RiftContainer} configuration — no Docker required (nothing is started),
 * so these run on every CI lane. The real round-trip lives in {@code RiftContainerIT}.
 */
class RiftContainerTest {

    private static final String PEM = "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n";

    @Test
    void defaultImageUsesPinnedEngineVersion() {
        // AC4: single-sourced from the <rift.engine.version> property via resource filtering.
        assertEquals("0.18.0", RiftContainer.ENGINE_VERSION, "engine version resolved from filtered resource");
        try (RiftContainer container = new RiftContainer()) {
            assertEquals("zainalpour/rift-proxy:v0.18.0", container.configuredImageName());
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
    void noUpstreamTrustConfiguresNothing() {
        try (RiftContainer container = new RiftContainer()) {
            container.configure();
            assertFalse(container.getEnvMap().containsKey("RIFT_UPSTREAM_CA_FILE"));
            assertFalse(container.getEnvMap().containsKey("RIFT_UPSTREAM_TLS_SKIP_VERIFY"));
            assertTrue(container.upstreamCaCopy().isEmpty());
            assertTrue(container.skipVerifyWarning().isEmpty());
        }
    }

    @Test
    void caFileIsCopiedIntoTheContainerAndNamedToTheEngine(@TempDir Path dir) throws Exception {
        Path pem = Files.writeString(dir.resolve("corp-ca.pem"), PEM);
        try (RiftContainer container = new RiftContainer().withUpstreamTrust(new UpstreamTrust.CaFile(pem))) {
            container.configure();
            // RIFT_UPSTREAM_CA_FILE is the engine's env for --upstream-ca-file; the path is in-container.
            assertEquals(RiftContainer.UPSTREAM_CA_PATH, container.getEnvMap().get("RIFT_UPSTREAM_CA_FILE"));
            assertFalse(container.getEnvMap().containsKey("RIFT_UPSTREAM_TLS_SKIP_VERIFY"));
            assertEquals(PEM, new String(container.upstreamCaCopy().orElseThrow().getBytes(), StandardCharsets.UTF_8),
                    "the host file's contents are what the container receives");
            assertTrue(container.skipVerifyWarning().isEmpty());
        }
    }

    @Test
    void caPemIsWrittenIntoTheContainerBecauseTheTransportOwnsTheFile() {
        // Unlike spawn, the container can take an inline PEM: the transport writes the file itself.
        try (RiftContainer container = new RiftContainer().withUpstreamTrust(new UpstreamTrust.CaPem(PEM))) {
            container.configure();
            assertEquals(RiftContainer.UPSTREAM_CA_PATH, container.getEnvMap().get("RIFT_UPSTREAM_CA_FILE"));
            assertFalse(container.getEnvMap().containsKey("RIFT_UPSTREAM_TLS_SKIP_VERIFY"));
            assertEquals(PEM, new String(container.upstreamCaCopy().orElseThrow().getBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void skipVerifySetsTheFlagCopiesNothingAndWarns() {
        try (RiftContainer container = new RiftContainer().withUpstreamTrust(new UpstreamTrust.SkipVerify())) {
            container.configure();
            assertEquals("true", container.getEnvMap().get("RIFT_UPSTREAM_TLS_SKIP_VERIFY"));
            assertFalse(container.getEnvMap().containsKey("RIFT_UPSTREAM_CA_FILE"));
            assertTrue(container.upstreamCaCopy().isEmpty());
            assertTrue(container.skipVerifyWarning().orElseThrow().contains("SkipVerify"));
        }
    }

    @Test
    void aLaterUpstreamTrustReplacesAnEarlierOne() {
        try (RiftContainer container = new RiftContainer()
                .withUpstreamTrust(new UpstreamTrust.SkipVerify())
                .withUpstreamTrust(new UpstreamTrust.CaPem(PEM))) {
            container.configure();
            assertFalse(container.getEnvMap().containsKey("RIFT_UPSTREAM_TLS_SKIP_VERIFY"),
                    "one engine, one policy: skip-verify must not survive the replacement");
            assertEquals(RiftContainer.UPSTREAM_CA_PATH, container.getEnvMap().get("RIFT_UPSTREAM_CA_FILE"));
        }
    }

    @Test
    void anUnreadableCaFileFailsTheStartNamingTheFile(@TempDir Path dir) {
        Path missing = dir.resolve("missing-ca.pem");
        try (RiftContainer container = new RiftContainer().withUpstreamTrust(new UpstreamTrust.CaFile(missing))) {
            UncheckedIOException e = assertThrows(UncheckedIOException.class, container::configure);
            assertTrue(e.getMessage().contains(missing.toString()), e.getMessage());
        }
    }

    @Test
    void rejectsUpstreamTrustOnAnImageOlderThan018() {
        for (String tag : new String[] {"v0.17.0", "0.17.0", "v0.17.0-static"}) {
            try (RiftContainer container = new RiftContainer(DockerImageName.parse("zainalpour/rift-proxy:" + tag))) {
                IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                        () -> container.withUpstreamTrust(new UpstreamTrust.SkipVerify()), tag);
                assertTrue(e.getMessage().contains("0.18.0") && e.getMessage().contains(tag), e.getMessage());
            }
        }
    }

    @Test
    void acceptsUpstreamTrustOnA018ImageOrAnUnversionedTag() {
        // A tag that is not a version (latest, a digest, a custom build) cannot be checked, so it is let through.
        for (String image : new String[] {"zainalpour/rift-proxy:v0.18.0", "zainalpour/rift-proxy:0.19.1",
                "zainalpour/rift-proxy:latest", "acme/rift:nightly"}) {
            try (RiftContainer container = new RiftContainer(DockerImageName.parse(image))) {
                container.withUpstreamTrust(new UpstreamTrust.SkipVerify());
            }
        }
    }

    @Test
    void upstreamTrustRejectsNull() {
        try (RiftContainer container = new RiftContainer()) {
            assertThrows(NullPointerException.class, () -> container.withUpstreamTrust(null));
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
