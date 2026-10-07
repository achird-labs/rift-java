package io.github.achirdlabs.rift.junit5;

import io.github.achirdlabs.rift.Intercept;
import io.github.achirdlabs.rift.InterceptRuleSet;
import io.github.achirdlabs.rift.dsl.RiftDsl;
import org.junit.jupiter.api.Test;
import org.junit.platform.testkit.engine.EngineExecutionResults;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.testkit.engine.EngineTestKit.engine;

/**
 * Which per-test reset a {@code @RiftInterceptRules} class gets on an engine that can replace rules
 * atomically (rift 0.20.0, #262), run through {@code EngineTestKit} so a deliberately failing fixture
 * does not fail this suite.
 */
class RiftInterceptRulesResetTest {

    @Test
    void aRulesMethodTakingTheLiveInterceptKeepsClearAndAdd() {
        EngineExecutionResults results = run(TakesTheIntercept.class);

        assertEquals(2, results.testEvents().succeeded().count());
        assertEquals(0, TakesTheIntercept.ADMIN.interceptRuleReplaces.get(),
                "it may read rules() or trust(), which a staged set cannot answer");
        assertEquals(2, TakesTheIntercept.ADMIN.interceptRuleClears.get(), "one clear per test");
    }

    @Test
    void aRulesMethodThatThrowsFailsTheTestInsteadOfFallingBack() {
        EngineExecutionResults results = run(ThrowsOnReset.class);

        assertEquals(1, results.testEvents().failed().count());
        Throwable failure = results.testEvents().failed().list().get(0)
                .getPayload(org.junit.platform.engine.TestExecutionResult.class).orElseThrow()
                .getThrowable().orElseThrow();
        assertTrue(failure.getMessage().contains("failed to apply @RiftInterceptRules method rules"), failure.getMessage());
        assertEquals(0, ThrowsOnReset.ADMIN.interceptRuleClears.get(),
                "the rules are left as they were: no fallback clears them");
        assertEquals(0, ThrowsOnReset.ADMIN.interceptRuleReplaces.get(), "and nothing half-declared is sent");
    }

    @Test
    void anEngineThatRefusesTheRulesFailsTheTestInsteadOfFallingBack() {
        // A 400 maps to InvalidDefinition, the type the version gate throws; it comes after the rules
        // were declared, so it is the engine judging them, not an engine that cannot replace.
        EngineExecutionResults results = run(EngineRefusesTheReplace.class);

        assertEquals(2, results.testEvents().failed().count(), "every reset fails loudly, none falls back");
        assertEquals(2, EngineRefusesTheReplace.ADMIN.interceptRuleReplaces.get(), "each reset tried the replace");
        assertEquals(0, EngineRefusesTheReplace.ADMIN.interceptRuleClears.get(), "no clear + add fallback");
    }

    @Test
    void anOldEngineLetPastTheGateFallsBackOnItsNotFound() {
        // With the version check off the replace is sent unchecked; an engine without the route
        // answers 404, and the reset clears and re-adds instead.
        EngineExecutionResults results;
        System.setProperty("rift.versionCheck", "off");
        try {
            results = run(UncheckedOldEngine.class);
        } finally {
            System.clearProperty("rift.versionCheck");
        }

        assertEquals(2, results.testEvents().succeeded().count());
        assertEquals(1, UncheckedOldEngine.ADMIN.interceptRuleReplaces.get(), "tried once, then no more for the class");
        assertEquals(2, UncheckedOldEngine.ADMIN.interceptRuleClears.get(), "both resets cleared and re-added");
    }

    private static EngineExecutionResults run(Class<?> fixture) {
        return engine("junit-jupiter").selectors(selectClass(fixture)).execute();
    }

    // ---- EngineTestKit fixtures (not discovered by surefire: names lack Test/IT suffix) ----

    @RiftTest(transport = Transport.CONNECT, adminUri = "${rift.junit.reset.live}", reset = Reset.PER_TEST)
    @RiftIntercept(port = 0)
    static class TakesTheIntercept {
        static final FakeRiftAdmin ADMIN = new FakeRiftAdmin("0.20.0");

        static {
            System.setProperty("rift.junit.reset.live", ADMIN.baseUri().toString());
        }

        @RiftInterceptRules
        static void rules(Intercept intercept) {
            intercept.serve("cdn.example.com", RiftDsl.ok());
        }

        @Test
        void first() {
        }

        @Test
        void second() {
        }
    }

    @RiftTest(transport = Transport.CONNECT, adminUri = "${rift.junit.reset.refused}", reset = Reset.PER_TEST)
    @RiftIntercept(port = 0)
    static class EngineRefusesTheReplace {
        static final FakeRiftAdmin ADMIN = new FakeRiftAdmin("0.20.0");

        static {
            ADMIN.interceptReplaceStatus = 400;
            System.setProperty("rift.junit.reset.refused", ADMIN.baseUri().toString());
        }

        @RiftInterceptRules
        static void rules(InterceptRuleSet rules) {
            rules.serve("cdn.example.com", RiftDsl.ok());
        }

        @Test
        void first() {
        }

        @Test
        void second() {
        }
    }

    @RiftTest(transport = Transport.CONNECT, adminUri = "${rift.junit.reset.unchecked}", reset = Reset.PER_TEST)
    @RiftIntercept(port = 0)
    static class UncheckedOldEngine {
        static final FakeRiftAdmin ADMIN = new FakeRiftAdmin("0.19.0");

        static {
            ADMIN.interceptReplaceStatus = 404;
            System.setProperty("rift.junit.reset.unchecked", ADMIN.baseUri().toString());
        }

        @RiftInterceptRules
        static void rules(InterceptRuleSet rules) {
            rules.serve("cdn.example.com", RiftDsl.ok());
        }

        @Test
        void first() {
        }

        @Test
        void second() {
        }
    }

    @RiftTest(transport = Transport.CONNECT, adminUri = "${rift.junit.reset.throws}", reset = Reset.PER_TEST)
    @RiftIntercept(port = 0)
    static class ThrowsOnReset {
        static final FakeRiftAdmin ADMIN = new FakeRiftAdmin("0.20.0");
        static final AtomicInteger CALLS = new AtomicInteger();

        static {
            System.setProperty("rift.junit.reset.throws", ADMIN.baseUri().toString());
        }

        @RiftInterceptRules
        static void rules(InterceptRuleSet rules) {
            if (CALLS.incrementAndGet() > 1) {
                // An undeliverable serve: InvalidDefinition, the type a refused replace also throws.
                rules.serve("cdn.example.com", RiftDsl.ok().templated());
            }
            rules.serve("cdn.example.com", RiftDsl.ok());
        }

        @Test
        void only() {
        }
    }
}
