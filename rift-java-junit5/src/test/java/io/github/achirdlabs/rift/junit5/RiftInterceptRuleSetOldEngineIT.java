package io.github.achirdlabs.rift.junit5;

import io.github.achirdlabs.rift.InterceptRuleSet;
import io.github.achirdlabs.rift.dsl.RiftDsl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * On an engine without {@code PUT /intercept/rules} (before rift 0.20.0) an {@link InterceptRuleSet}
 * rules method still works: the per-test reset falls back to clearing and re-adding (#262). The
 * version gate refuses the replace here, before anything is sent.
 */
@RiftTest(transport = Transport.CONNECT, adminUri = "${rift.junit.intercept.ruleset.old}", reset = Reset.PER_TEST)
@RiftIntercept(port = 0)
class RiftInterceptRuleSetOldEngineIT {

    static final FakeRiftAdmin ADMIN = new FakeRiftAdmin("0.19.0");

    static {
        System.setProperty("rift.junit.intercept.ruleset.old", ADMIN.baseUri().toString());
    }

    @RiftInterceptRules
    static void rules(InterceptRuleSet rules) {
        rules.serve("cdn.example.com", RiftDsl.ok());
    }

    @Test
    void eachResetClearsAndReadds() {
        assertEquals(0, ADMIN.interceptRuleReplaces.get(), "an old engine is never sent a replace");
        assertTrue(ADMIN.interceptRuleClears.get() >= 1);
        assertTrue(ADMIN.interceptRuleAdds.get() >= 2, "added at start and again after the reset");
    }

    @Test
    void aSecondTestFallsBackToo() {
        assertEquals(0, ADMIN.interceptRuleReplaces.get());
        assertTrue(ADMIN.interceptRuleAdds.get() >= 2);
    }
}
