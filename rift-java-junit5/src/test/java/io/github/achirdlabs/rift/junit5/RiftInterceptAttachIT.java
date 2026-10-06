package io.github.achirdlabs.rift.junit5;

import io.github.achirdlabs.rift.Intercept;
import io.github.achirdlabs.rift.dsl.RiftDsl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @RiftIntercept(attach = true)} binds to a listener the engine started at launch (a
 * container's {@code withInterceptPort}) instead of starting one, which such an engine would refuse.
 */
@RiftTest(transport = Transport.CONNECT, adminUri = "${rift.junit.intercept.attach}", reset = Reset.PER_TEST)
@RiftIntercept(attach = true, port = 18888)
class RiftInterceptAttachIT {

    static final FakeRiftAdmin ADMIN = new FakeRiftAdmin();

    static {
        System.setProperty("rift.junit.intercept.attach", ADMIN.baseUri().toString());
    }

    @RiftInterceptRules
    static void rules(Intercept intercept) {
        intercept.serve("cdn.example.com", RiftDsl.ok());
    }

    @InjectIntercept
    Intercept intercept;

    @Test
    void attachesWithoutStartingAndStillAppliesRules() {
        assertEquals(0, ADMIN.interceptStarts.get(), "an attach never posts a start");
        assertTrue(ADMIN.interceptRuleAdds.get() >= 1, "@RiftInterceptRules applied to the attached listener");
        assertEquals("127.0.0.1", intercept.address().getHostString());
        assertEquals(18888, intercept.address().getPort());
    }
}
