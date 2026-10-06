package io.github.achirdlabs.rift.junit5;

import io.github.achirdlabs.rift.Intercept;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.testkit.engine.EngineTestKit.engine;

/**
 * The class teardown stops a listener {@code @RiftIntercept} started — freeing a shared engine's one
 * slot for the next class — and leaves an attached one, which its launcher owns, running. Driven over
 * a fake admin via EngineTestKit, since the teardown runs after the class's own tests.
 */
class RiftInterceptTeardownTest {

    static final FakeRiftAdmin ADMIN = new FakeRiftAdmin();

    static {
        System.setProperty("rift.junit.intercept.teardown", ADMIN.baseUri().toString());
    }

    @Test
    void anOwnedListenerIsStoppedOnceWhenTheClassEnds() {
        int before = ADMIN.interceptStops.get();
        engine("junit-jupiter").selectors(selectClass(OwnedFixture.class)).execute()
                .testEvents().assertStatistics(stats -> stats.succeeded(1).failed(0));
        assertEquals(before + 1, ADMIN.interceptStops.get());
    }

    @Test
    void anAttachedListenerIsNeverStopped() {
        int before = ADMIN.interceptStops.get();
        engine("junit-jupiter").selectors(selectClass(AttachedFixture.class)).execute()
                .testEvents().assertStatistics(stats -> stats.succeeded(1).failed(0));
        assertEquals(before, ADMIN.interceptStops.get());
    }

    @RiftTest(transport = Transport.CONNECT, adminUri = "${rift.junit.intercept.teardown}")
    @RiftIntercept
    static class OwnedFixture {
        @Test
        void runs(@InjectIntercept Intercept intercept) {
            assertEquals(19000, intercept.address().getPort());
        }
    }

    @RiftTest(transport = Transport.CONNECT, adminUri = "${rift.junit.intercept.teardown}")
    @RiftIntercept(attach = true, port = 18888)
    static class AttachedFixture {
        @Test
        void runs(@InjectIntercept Intercept intercept) {
            assertEquals(18888, intercept.address().getPort());
        }
    }
}
