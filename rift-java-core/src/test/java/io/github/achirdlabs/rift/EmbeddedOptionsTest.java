package io.github.achirdlabs.rift;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class EmbeddedOptionsTest {

    @Test
    void defaults() {
        EmbeddedOptions o = EmbeddedOptions.builder().build();
        assertEquals(Optional.empty(), o.libraryPath());
        assertEquals(VersionCheck.FAIL, o.versionCheck());
        assertFalse(o.serveAdminEagerly());
        assertEquals("127.0.0.1", o.adminHost());
        assertEquals(0, o.adminPort());
        assertEquals(Optional.empty(), o.apiKey());
    }

    @Test
    void overridesApplied() {
        EmbeddedOptions o = EmbeddedOptions.builder()
                .libraryPath(Path.of("/tmp/librift_ffi.so"))
                .versionCheck(VersionCheck.OFF)
                .serveAdminEagerly(true)
                .adminHost("0.0.0.0")
                .adminPort(2525)
                .apiKey("secret")
                .build();
        assertEquals(Optional.of(Path.of("/tmp/librift_ffi.so")), o.libraryPath());
        assertEquals(VersionCheck.OFF, o.versionCheck());
        assertEquals(true, o.serveAdminEagerly());
        assertEquals("0.0.0.0", o.adminHost());
        assertEquals(2525, o.adminPort());
        assertEquals(Optional.of("secret"), o.apiKey());
    }

    @Test
    void engineLaunchOptionsAreUnsetByDefault() {
        EmbeddedOptions defaults = EmbeddedOptions.builder().build();
        assertEquals(Optional.empty(), defaults.allowInjection());
        assertEquals(Optional.empty(), defaults.requireAdminAuth());
        assertEquals(java.util.OptionalInt.empty(), defaults.metricsPort());
    }

    @Test
    void engineLaunchOptionsAreKeptAsGiven() {
        EmbeddedOptions set = EmbeddedOptions.builder()
                .allowInjection(true).requireAdminAuth(false).metricsPort(19090).build();
        assertEquals(Optional.of(true), set.allowInjection());
        assertEquals(Optional.of(false), set.requireAdminAuth());
        assertEquals(java.util.OptionalInt.of(19090), set.metricsPort());
    }

    @Test
    void metricsPortMustBeAFixedPort() {
        // The engine reports the bound metrics port only in its serve response, which nothing surfaces,
        // so an OS-assigned (0) one could never be found again.
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> EmbeddedOptions.builder().metricsPort(0));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> EmbeddedOptions.builder().metricsPort(65536));
    }
}
