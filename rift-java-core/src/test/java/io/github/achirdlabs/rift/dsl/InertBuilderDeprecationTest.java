package io.github.achirdlabs.rift.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Method;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** The builders whose keys the engine parses but never acts on are deprecated; their working alternatives are not. */
class InertBuilderDeprecationTest {

    @Test
    void metricsIsDeprecatedSince024NotForRemoval() throws NoSuchMethodException {
        assertDeprecated(ImposterSpec.class.getMethod("metrics", int.class));
    }

    @Test
    void proxyPoolIsDeprecatedSince024NotForRemoval() throws NoSuchMethodException {
        assertDeprecated(ImposterSpec.class.getMethod("proxyPool", int.class, Duration.class));
    }

    @Test
    void recordMatchesIsDeprecatedSince024NotForRemoval() throws NoSuchMethodException {
        assertDeprecated(ImposterSpec.class.getMethod("recordMatches"));
    }

    @Test
    void recordIsTheWorkingAlternativeAndIsNotDeprecated() throws NoSuchMethodException {
        assertNull(ImposterSpec.class.getMethod("record").getAnnotation(Deprecated.class));
    }

    private static void assertDeprecated(Method method) {
        Deprecated deprecated = method.getAnnotation(Deprecated.class);
        assertNotNull(deprecated, method.getName() + " should be @Deprecated");
        assertEquals("0.2.4", deprecated.since());
        assertFalse(deprecated.forRemoval());
    }
}
