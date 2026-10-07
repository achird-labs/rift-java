package io.github.achirdlabs.rift.junit5;

import io.github.achirdlabs.rift.InterceptRuleSet;
import io.github.achirdlabs.rift.dsl.RiftDsl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@code @RiftInterceptRules} method declared with an {@link InterceptRuleSet} is re-applied per
 * test as one atomic {@code PUT /intercept/rules} (rift 0.20.0, #262): a SUT request in flight
 * never sees the empty set that a clear followed by adds leaves in between.
 */
@RiftTest(transport = Transport.CONNECT, adminUri = "${rift.junit.intercept.ruleset}", reset = Reset.PER_TEST)
@RiftIntercept(port = 0)
class RiftInterceptRuleSetIT {

    static final FakeRiftAdmin ADMIN = new FakeRiftAdmin("0.20.0");

    static {
        System.setProperty("rift.junit.intercept.ruleset", ADMIN.baseUri().toString());
    }

    @RiftInterceptRules
    static void rules(InterceptRuleSet rules) {
        rules.serve("cdn.example.com", RiftDsl.ok());
    }

    @Test
    void theFirstApplyAddsAndEachResetReplaces() {
        assertEquals(1, ADMIN.interceptRuleAdds.get(), "the listener's first rules are added once");
        assertTrue(ADMIN.interceptRuleReplaces.get() >= 1, "a per-test reset is one replace");
        assertEquals(0, ADMIN.interceptRuleClears.get(), "never cleared: the set is swapped, not emptied");
        assertEquals("[{\"host\":\"cdn.example.com\",\"action\":{\"serve\":{\"statusCode\":200}}}]",
                ADMIN.lastInterceptReplace, "the replace carries the rules method's rule");
    }

    @Test
    void aSecondTestIsAnotherReplace() {
        assertEquals(1, ADMIN.interceptRuleAdds.get());
        assertEquals(0, ADMIN.interceptRuleClears.get());
    }
}
