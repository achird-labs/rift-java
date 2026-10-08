package io.github.achirdlabs.rift.conformance;

import io.github.achirdlabs.rift.Intercept;
import io.github.achirdlabs.rift.InterceptOptions;
import io.github.achirdlabs.rift.InterceptStatus;
import io.github.achirdlabs.rift.Rift;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.Optional;
import java.util.stream.Stream;

import static io.github.achirdlabs.rift.conformance.LiveEngine.engine;
import static io.github.achirdlabs.rift.conformance.LiveEngine.gated;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** {@code Rift.interceptStatus()} and a port-less attach agree with the engine (#286). Needs {@code RIFT_IT=1}. */
class InterceptStatusIT {

    @TestFactory
    Stream<DynamicTest> statusFollowsTheListener() {
        return gated("interceptStatus is empty, then the running listener, then empty again", () -> {
            try (Rift rift = engine()) {
                assertEquals(Optional.empty(), rift.interceptStatus());
                assertThrows(IllegalStateException.class, () -> rift.intercept(InterceptOptions.attach()));

                Intercept intercept = rift.intercept();
                InterceptStatus running = rift.interceptStatus().orElseThrow();
                assertEquals(intercept.address().getPort(), running.port());
                assertEquals(intercept.engineAddress(), Optional.of(running.engineUrl()));

                intercept.close();
                assertEquals(Optional.empty(), rift.interceptStatus());
            }
        });
    }
}
