package io.github.achirdlabs.rift.testcontainers;

import io.github.achirdlabs.rift.Intercept;
import io.github.achirdlabs.rift.InterceptOptions;
import io.github.achirdlabs.rift.UpstreamTrust;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        assertEquals("0.20.0", RiftContainer.ENGINE_VERSION, "engine version resolved from filtered resource");
        try (RiftContainer container = new RiftContainer()) {
            assertEquals("zainalpour/rift-proxy:v0.20.0", container.configuredImageName());
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
        for (String image : new String[] {"zainalpour/rift-proxy:v0.20.0", "zainalpour/rift-proxy:0.19.1",
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
    void withExposedInterceptPortExposesWithoutBootingTheListener() {
        try (RiftContainer container = new RiftContainer().withExposedInterceptPort(8889)) {
            assertTrue(container.getExposedPorts().contains(8889), "8889 exposed");
            assertFalse(container.getEnvMap().containsKey("RIFT_INTERCEPT_PORT"),
                    "the engine must not start a listener at launch, or a runtime start answers 409");
        }
    }

    @Test
    void bootThenExposedInterceptPortIsRejected() {
        try (RiftContainer container = new RiftContainer().withInterceptPort(8888)) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> container.withExposedInterceptPort(8889));
            assertTrue(e.getMessage().contains("withInterceptPort") && e.getMessage().contains("withExposedInterceptPort"),
                    e.getMessage());
        }
    }

    @Test
    void exposedThenBootInterceptPortIsRejected() {
        try (RiftContainer container = new RiftContainer().withExposedInterceptPort(8889)) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> container.withInterceptPort(8888));
            assertTrue(e.getMessage().contains("withInterceptPort") && e.getMessage().contains("withExposedInterceptPort"),
                    e.getMessage());
        }
    }

    @Test
    void theClientRoutesARuntimeInterceptThroughTheContainerMapping() {
        // client() needs a running container; its options do not. A start on an unexposed port is
        // refused by this mapping, which the client asks before starting anything.
        try (RiftContainer container = new RiftContainer().withExposedInterceptPort(8889)) {
            var mapping = container.connectOptions(URI.create("http://localhost:2525")).interceptAddress().orElseThrow();
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> mapping.apply(0));
            assertTrue(e.getMessage().contains("withExposedInterceptPort"), e.getMessage());
        }
    }

    @Test
    void anUnexposedInterceptPortIsRefused() {
        try (RiftContainer container = new RiftContainer().withExposedInterceptPort(8889)) {
            IllegalArgumentException zero = assertThrows(IllegalArgumentException.class,
                    () -> container.interceptAddressFor(0));
            assertTrue(zero.getMessage().contains("withExposedInterceptPort"), zero.getMessage());
            assertThrows(IllegalArgumentException.class, () -> container.interceptAddressFor(9999));
        }
    }

    @Test
    void gatewayModeStillRefusesAnUnexposedInterceptPort() {
        // The gateway routes imposter HTTP through the admin port; a CONNECT proxy cannot ride it, so the
        // intercept mapping is the fixed-port one whatever the imposter mode.
        try (RiftContainer container = new RiftContainer().withGateway().withExposedInterceptPort(8889)) {
            assertThrows(IllegalArgumentException.class, () -> container.interceptAddressFor(0));
        }
    }

    @Test
    void interceptOptionsStillRequiresTheBootMode() {
        try (RiftContainer container = new RiftContainer().withExposedInterceptPort(8889)) {
            assertThrows(IllegalStateException.class, container::interceptOptions);
        }
    }

    @Test
    void interceptCaFilesAreCopiedIntoTheContainerAndNamedToTheEngine(@TempDir Path dir) throws Exception {
        Path cert = Files.writeString(dir.resolve("ca.pem"), "CERT-PEM");
        Path key = Files.writeString(dir.resolve("ca-key.pem"), "KEY-PEM");
        try (RiftContainer container = new RiftContainer().withInterceptPort(8888).withInterceptCa(cert, key)) {
            container.configure();
            // The file form of --intercept-ca-cert/--intercept-ca-key; the paths are in-container.
            assertEquals("/etc/rift/intercept-ca-cert.pem", container.getEnvMap().get("RIFT_INTERCEPT_CA_CERT"));
            assertEquals("/etc/rift/intercept-ca-key.pem", container.getEnvMap().get("RIFT_INTERCEPT_CA_KEY"));
            assertEquals(Map.of("/etc/rift/intercept-ca-cert.pem", "CERT-PEM", "/etc/rift/intercept-ca-key.pem", "KEY-PEM"),
                    copiedIntoTheContainer(container), "the host files' contents are what the container receives");
        }
    }

    @Test
    void inlineInterceptCaIsWrittenIntoTheContainer() throws Exception {
        try (RiftContainer container = new RiftContainer().withInterceptCa("CERT-PEM", "KEY-PEM").withInterceptPort(8888)) {
            container.configure();
            assertEquals("/etc/rift/intercept-ca-cert.pem", container.getEnvMap().get("RIFT_INTERCEPT_CA_CERT"));
            assertEquals("/etc/rift/intercept-ca-key.pem", container.getEnvMap().get("RIFT_INTERCEPT_CA_KEY"));
            assertEquals(Map.of("/etc/rift/intercept-ca-cert.pem", "CERT-PEM", "/etc/rift/intercept-ca-key.pem", "KEY-PEM"),
                    copiedIntoTheContainer(container));
        }
    }

    @Test
    void theAttachOptionsCarryTheCaThatWasCopiedIn(@TempDir Path dir) throws Exception {
        Path cert = Files.writeString(dir.resolve("ca.pem"), "CERT-PEM");
        Path key = Files.writeString(dir.resolve("ca-key.pem"), "KEY-PEM");
        try (RiftContainer container = new RiftContainer().withInterceptPort(8888).withInterceptCa(cert, key)) {
            container.configure();
            // Changing the files after the start must not change what the attach claims the listener runs.
            Files.writeString(cert, "EDITED-AFTER-START");
            InterceptOptions options = container.attachOptions("localhost", 49152);
            assertEquals(new Intercept.CaMaterial("CERT-PEM", "KEY-PEM"), attachCa(options));
        }
    }

    @Test
    void theAttachOptionsCarryNoCaWhenNoneWasCommitted() throws Exception {
        try (RiftContainer container = new RiftContainer().withInterceptPort(8888)) {
            container.configure();
            assertNull(attachCa(container.attachOptions("localhost", 49152)));
        }
    }

    @Test
    void interceptCaFilesAreReadWhenTheContainerStartsNotWhenDeclared(@TempDir Path dir) {
        // A static @Container field is built before the test can create the file; reading must wait.
        Path missing = dir.resolve("not-yet.pem");
        try (RiftContainer container = new RiftContainer().withInterceptPort(8888).withInterceptCa(missing, missing)) {
            UncheckedIOException e = assertThrows(UncheckedIOException.class, container::configure);
            assertTrue(e.getMessage().contains("not-yet.pem"), e.getMessage());
        }
    }

    @Test
    void anUnreadableInterceptCaKeyIsNamed(@TempDir Path dir) throws Exception {
        Path cert = Files.writeString(dir.resolve("ca.pem"), "CERT-PEM");
        Path missingKey = dir.resolve("missing-key.pem");
        try (RiftContainer container = new RiftContainer().withInterceptPort(8888).withInterceptCa(cert, missingKey)) {
            UncheckedIOException e = assertThrows(UncheckedIOException.class, container::configure);
            assertTrue(e.getMessage().contains("missing-key.pem"), e.getMessage());
        }
    }

    @Test
    void interceptCaWithoutAnyInterceptListenerIsRejected() {
        // The engine reads the CA env only when it launches a listener; without one it ignores it silently.
        try (RiftContainer container = new RiftContainer().withInterceptCa("CERT", "KEY")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, container::configure);
            assertTrue(e.getMessage().startsWith("withInterceptCa needs withInterceptPort"), e.getMessage());
        }
    }

    @Test
    void interceptCaWithARuntimeListenerPointsAtTheStartOptions() {
        try (RiftContainer container = new RiftContainer().withExposedInterceptPort(8889).withInterceptCa("CERT", "KEY")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, container::configure);
            assertTrue(e.getMessage().contains("InterceptOptions.builder().ca(...)"), e.getMessage());
        }
    }

    @Test
    void noInterceptCaConfiguresNothing() throws Exception {
        try (RiftContainer container = new RiftContainer().withInterceptPort(8888)) {
            container.configure();
            assertFalse(container.getEnvMap().containsKey("RIFT_INTERCEPT_CA_CERT"));
            assertFalse(container.getEnvMap().containsKey("RIFT_INTERCEPT_CA_KEY"));
            assertEquals(Map.of(), copiedIntoTheContainer(container));
        }
    }

    @Test
    void aLaterInterceptCaReplacesAnEarlierOne() throws Exception {
        try (RiftContainer container = new RiftContainer().withInterceptPort(8888)
                .withInterceptCa("OLD", "OLD-KEY").withInterceptCa("NEW", "NEW-KEY")) {
            container.configure();
            assertEquals(Map.of("/etc/rift/intercept-ca-cert.pem", "NEW", "/etc/rift/intercept-ca-key.pem", "NEW-KEY"),
                    copiedIntoTheContainer(container));
        }
    }

    /**
     * What {@code withCopyToContainer} registered, by in-container path. Testcontainers keeps that map
     * package-private, and asserting it, not our own field, is what proves the copy happens.
     */
    private static Map<String, String> copiedIntoTheContainer(RiftContainer container) throws Exception {
        Method getter = GenericContainer.class.getDeclaredMethod("getCopyToTransferableContainerPathMap");
        getter.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Transferable, String> copies = (Map<Transferable, String>) getter.invoke(container);
        Map<String, String> byPath = new HashMap<>();
        copies.forEach((content, path) -> byPath.put(path, new String(content.getBytes(), StandardCharsets.UTF_8)));
        return byPath;
    }

    /** The CA an attach carries; core keeps the accessor package-private. */
    private static Intercept.CaMaterial attachCa(InterceptOptions options) throws Exception {
        Method getter = InterceptOptions.class.getDeclaredMethod("attachCa");
        getter.setAccessible(true);
        return (Intercept.CaMaterial) getter.invoke(options);
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
