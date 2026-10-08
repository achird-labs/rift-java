package io.github.achirdlabs.rift.dsl;

import org.junit.jupiter.api.Test;

import static io.github.achirdlabs.rift.dsl.RiftDsl.imposter;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code ImposterSpec.disabled()} / {@code enabled(boolean)}: the DSL can create an imposter paused (#288). */
class ImposterSpecEnabledTest {

    @Test
    void disabledEmitsEnabledFalse() {
        assertEquals("{\"protocol\":\"http\",\"name\":\"paused\",\"enabled\":false,\"stubs\":[]}",
                imposter("paused").disabled().build().toJson());
        assertFalse(imposter("paused").disabled().build().enabled());
    }

    @Test
    void theDefaultSaysNothing() {
        assertEquals("{\"protocol\":\"http\",\"name\":\"live\",\"stubs\":[]}", imposter("live").build().toJson());
        assertTrue(imposter("live").build().enabled());
    }

    @Test
    void enabledTrueUndoesDisabledAndSurvivesLaterChainCalls() {
        assertTrue(imposter("x").disabled().enabled(true).build().enabled());
        assertFalse(imposter("x").disabled().port(4545).record().strictBehaviors().build().enabled(),
                "every chain method carries the flag forward");
    }
}
