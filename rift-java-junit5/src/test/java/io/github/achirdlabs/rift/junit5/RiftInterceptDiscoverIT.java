package io.github.achirdlabs.rift.junit5;

import io.github.achirdlabs.rift.Intercept;
import io.github.achirdlabs.rift.dsl.RiftDsl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @RiftIntercept(attach = true)} with no port asks the engine where its listener is ({@code
 * GET /intercept}) instead of being told, so a listener on a port chosen at launch can be attached.
 */
@RiftTest(transport = Transport.CONNECT, adminUri = "${rift.junit.intercept.discover}", reset = Reset.PER_TEST)
@RiftIntercept(attach = true)
class RiftInterceptDiscoverIT {

    static final FakeRiftAdmin ADMIN = new FakeRiftAdmin();

    static {
        ADMIN.runningInterceptPort = 18999;
        System.setProperty("rift.junit.intercept.discover", ADMIN.baseUri().toString());
    }

    @RiftInterceptRules
    static void rules(Intercept intercept) {
        intercept.serve("cdn.example.com", RiftDsl.ok());
    }

    @InjectIntercept
    Intercept intercept;

    @Test
    void discoversTheRunningListenerWithoutStartingOne() {
        assertEquals(0, ADMIN.interceptStarts.get(), "an attach never posts a start");
        assertTrue(ADMIN.interceptStatusReads.get() >= 1, "the port came from GET /intercept");
        assertTrue(ADMIN.interceptRuleAdds.get() >= 1, "@RiftInterceptRules applied to the discovered listener");
        assertEquals(18999, intercept.address().getPort());
    }
}
