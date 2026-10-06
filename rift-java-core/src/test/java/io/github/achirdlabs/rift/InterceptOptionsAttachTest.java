package io.github.achirdlabs.rift;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Attach-mode options and the intercept bind-host IP-literal validation (issue #90). */
class InterceptOptionsAttachTest {

    @Test
    void attachFactoryCarriesEndpointAndIsAttachMode() {
        InterceptOptions options = InterceptOptions.attach("127.0.0.1", 8888);
        assertTrue(options.isAttach());
        assertEquals("127.0.0.1", options.host());
        assertEquals(8888, options.port());
    }

    @Test
    void attachWithACaCarriesItAndStillSendsNoStartBody() {
        Intercept.CaMaterial ca = new Intercept.CaMaterial("CERT", "KEY");
        InterceptOptions options = InterceptOptions.attach("127.0.0.1", 8888, ca);
        assertTrue(options.isAttach());
        assertEquals(ca, options.attachCa());
        assertEquals(8888, options.port());
    }

    @Test
    void plainAttachCarriesNoCa() {
        assertNull(InterceptOptions.attach("127.0.0.1", 8888).attachCa());
        assertNull(InterceptOptions.builder().build().attachCa());
    }

    @Test
    void builtOptionsAreNotAttachMode() {
        assertFalse(InterceptOptions.builder().build().isAttach());
    }

    @Test
    void bindHostAcceptsIpLiterals() {
        assertDoesNotThrow(() -> InterceptOptions.builder().host("127.0.0.1"));
        assertDoesNotThrow(() -> InterceptOptions.builder().host("0.0.0.0"));
        assertDoesNotThrow(() -> InterceptOptions.builder().host("::1"));
        assertDoesNotThrow(() -> InterceptOptions.builder().host("10.20.30.40"));
    }

    @Test
    void bindHostRejectsHostnames() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> InterceptOptions.builder().host("localhost"));
        assertTrue(e.getMessage().contains("IP literal"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> InterceptOptions.builder().host("host.docker.internal"));
        assertThrows(IllegalArgumentException.class, () -> InterceptOptions.builder().host("999.1.1.1"));
    }
}
