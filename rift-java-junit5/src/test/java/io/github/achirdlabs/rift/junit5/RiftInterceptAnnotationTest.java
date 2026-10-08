package io.github.achirdlabs.rift.junit5;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code @RiftIntercept} combinations that cannot work are refused before the engine is asked. */
class RiftInterceptAnnotationTest {

    @RiftIntercept(attach = true, host = "rift.internal")
    static class AttachWithAHostButNoPort { }

    @RiftIntercept(attach = true, port = 8888, caCert = "ca.pem", caKey = "ca-key.pem")
    static class AttachWithACa { }

    @RiftIntercept(attach = true, port = 8888, caKey = "ca-key.pem")
    static class AttachWithOnlyAKey { }

    @RiftIntercept(attach = true, port = 8888, caCert = "${rift.junit.test.never.set}")
    static class AttachWithAnUnsetPlaceholderCa { }

    @RiftIntercept(attach = true, port = 8888, inlineCa = true)
    static class AttachInline { }

    @RiftIntercept(inlineCa = true, caCert = "ca.pem")
    static class InlineWithoutAKey { }

    @RiftIntercept(inlineCa = true, caKey = "ca-key.pem")
    static class InlineWithoutACert { }

    @RiftIntercept(inlineCa = true, caCert = "/no/such/rift-ca.pem", caKey = "/no/such/rift-ca-key.pem")
    static class InlineUnreadableCert { }

    @RiftIntercept(inlineCa = true, caCert = "pom.xml", caKey = "/no/such/rift-ca-key.pem")
    static class InlineUnreadableKey { }

    @Test
    void attachAtANamedHostNeedsTheListenersPort() {
        // Without a port the listener is discovered and reached through the engine's own report, so a
        // host given alone would be silently ignored.
        assertRefused(AttachWithAHostButNoPort.class, "names a host but no port");
    }

    @Test
    void attachCannotInstallACa() {
        // The listener already runs with its own CA; attach can only bind to it.
        assertRefused(AttachWithACa.class, "cannot take caCert/caKey");
        assertRefused(AttachWithOnlyAKey.class, "cannot take caCert/caKey");
    }

    @Test
    void anUnsetPlaceholderDoesNotSlipPastTheAttachCheck() {
        assertRefused(AttachWithAnUnsetPlaceholderCa.class, "cannot take caCert/caKey");
    }

    @Test
    void attachCannotShipACaInline() {
        assertRefused(AttachInline.class, "cannot take inlineCa");
    }

    @Test
    void inlineNeedsBothPemFiles() {
        assertRefused(InlineWithoutAKey.class, "needs both caCert and caKey");
        assertRefused(InlineWithoutACert.class, "needs both caCert and caKey");
    }

    @Test
    void anUnreadableInlineCaFileIsNamed() {
        assertRefused(InlineUnreadableCert.class, "cannot read /no/such/rift-ca.pem");
        assertRefused(InlineUnreadableKey.class, "cannot read /no/such/rift-ca-key.pem");
    }

    private static void assertRefused(Class<?> annotated, String mentions) {
        RiftIntercept config = annotated.getAnnotation(RiftIntercept.class);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> RiftTestExtension.interceptOptions(config));
        assertTrue(e.getMessage().contains(mentions), e.getMessage());
    }
}
