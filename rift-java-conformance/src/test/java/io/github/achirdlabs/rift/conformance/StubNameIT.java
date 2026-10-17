package io.github.achirdlabs.rift.conformance;

import io.github.achirdlabs.rift.Imposter;
import io.github.achirdlabs.rift.Rift;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.Optional;
import java.util.stream.Stream;

import static io.github.achirdlabs.rift.conformance.LiveEngine.engine;
import static io.github.achirdlabs.rift.conformance.LiveEngine.gated;
import static io.github.achirdlabs.rift.dsl.RiftDsl.imposter;
import static io.github.achirdlabs.rift.dsl.RiftDsl.ok;
import static io.github.achirdlabs.rift.dsl.RiftDsl.onGet;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** rift 0.22.0 keeps a stub's {@code name} and prints it back (#310, rift#1362). Needs {@code RIFT_IT=1}. */
class StubNameIT {

    @TestFactory
    Stream<DynamicTest> aStubsNameReadsBackFromTheEngine() {
        return gated("a named stub reads back with its name, an unnamed one without", () -> {
            try (Rift rift = engine()) {
                Imposter imposter = rift.create(imposter("names")
                        .stub(onGet("/a").named("s1").willReturn(ok()))
                        .stub(onGet("/b").willReturn(ok())));
                assertEquals(Optional.of("s1"), imposter.definition().stubs().get(0).name());
                assertEquals(Optional.empty(), imposter.definition().stubs().get(1).name());

                imposter.addStub(onGet("/c").named("s3").willReturn(ok()));
                assertEquals(Optional.of("s3"), imposter.definition().stubs().get(2).name());
            }
        });
    }
}
