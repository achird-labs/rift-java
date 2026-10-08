package io.github.achirdlabs.rift.conformance;

import io.github.achirdlabs.rift.Imposter;
import io.github.achirdlabs.rift.Rift;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.stream.Stream;

import static io.github.achirdlabs.rift.conformance.LiveEngine.engine;
import static io.github.achirdlabs.rift.conformance.LiveEngine.gated;
import static io.github.achirdlabs.rift.dsl.RiftDsl.imposter;
import static io.github.achirdlabs.rift.dsl.RiftDsl.ok;
import static io.github.achirdlabs.rift.dsl.RiftDsl.onGet;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code ImposterSpec.disabled()} and {@code Imposter.isEnabled()} against a live engine (#288). Needs {@code RIFT_IT=1}. */
class ImposterEnabledIT {

    @TestFactory
    Stream<DynamicTest> isEnabledFollowsTheEngine() {
        return gated("an imposter created disabled reads as disabled, and follows enable/disable", () -> {
            try (Rift rift = engine()) {
                Imposter paused = rift.create(imposter("paused").disabled().stub(onGet("/").willReturn(ok())));
                assertFalse(paused.isEnabled(), "created paused");
                paused.enable();
                assertTrue(paused.isEnabled());
                paused.disable();
                assertFalse(paused.isEnabled());

                assertTrue(rift.create(imposter("live").stub(onGet("/").willReturn(ok()))).isEnabled());
            }
        });
    }
}
