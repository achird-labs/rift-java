package io.github.achirdlabs.rift.conformance;

import io.github.achirdlabs.rift.Imposter;
import io.github.achirdlabs.rift.Rift;
import io.github.achirdlabs.rift.model.Behavior;
import io.github.achirdlabs.rift.model.Response;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.List;
import java.util.stream.Stream;

import static io.github.achirdlabs.rift.conformance.LiveEngine.engine;
import static io.github.achirdlabs.rift.conformance.LiveEngine.gatedTo;
import static io.github.achirdlabs.rift.dsl.RiftDsl.imposter;
import static io.github.achirdlabs.rift.dsl.RiftDsl.onGet;
import static io.github.achirdlabs.rift.dsl.RiftDsl.status;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A multi-command {@code shellTransform} survives a trip through a live engine and back into the
 * model (#234): the engine echoes one step per command, and the SDK reads each one. Needs {@code RIFT_IT=1}.
 */
class ShellTransformReadBackIT {

    @TestFactory
    Stream<DynamicTest> severalCommandsReadBackFromTheEngine() {
        return gatedTo(ConformanceTransport.SPAWN,
                "shellTransform is an injection surface; --allow-injection is a spawn-CLI flag",
                "a two-command shellTransform reads back as two steps", () -> {
            try (Rift rift = engine()) {
                Imposter imposter = rift.create(imposter("shell").protocol("http")
                        .stub(onGet("/").willReturn(status(200).shellTransform("./first.sh", "./second.sh"))));

                Response.Is is = (Response.Is) imposter.definition().stubs().get(0).responses().get(0);
                assertEquals(List.of(new Behavior.ShellTransform("./first.sh"), new Behavior.ShellTransform("./second.sh")),
                        is.behaviors().entries());
            }
        });
    }
}
