package io.github.achirdlabs.rift.junit5;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @RiftIntercept(inlineCa = true)} reads the committed CA on the client and ships it in the
 * start request, so an engine in a container needs no file mounted.
 */
@RiftTest(transport = Transport.CONNECT, adminUri = "${rift.junit.intercept.inline}")
@RiftIntercept(inlineCa = true, caCert = "${rift.junit.inline.cert}", caKey = "${rift.junit.inline.key}")
class RiftInterceptInlineCaIT {

    static final FakeRiftAdmin ADMIN = new FakeRiftAdmin();

    static {
        System.setProperty("rift.junit.intercept.inline", ADMIN.baseUri().toString());
        try {
            Path dir = Files.createTempDirectory("rift-inline-ca");
            System.setProperty("rift.junit.inline.cert", Files.writeString(dir.resolve("ca.pem"), "INLINE-CERT-PEM").toString());
            System.setProperty("rift.junit.inline.key", Files.writeString(dir.resolve("ca-key.pem"), "INLINE-KEY-PEM").toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void theCaTravelsInTheStartRequest() {
        String start = ADMIN.lastInterceptStart;
        assertTrue(start.contains("\"caCertPem\":\"INLINE-CERT-PEM\""), start);
        assertTrue(start.contains("\"caKeyPem\":\"INLINE-KEY-PEM\""), start);
        assertFalse(start.contains("caCertPath"), "no engine-side path: " + start);
    }
}
