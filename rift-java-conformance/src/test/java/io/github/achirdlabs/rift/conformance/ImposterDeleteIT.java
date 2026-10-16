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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code Imposter.delete()} is idempotent on every transport (#308, rift#1371). Needs {@code RIFT_IT=1}. */
class ImposterDeleteIT {

    @TestFactory
    Stream<DynamicTest> deleteIsIdempotent() {
        return gated("deleting an imposter twice succeeds, and it is gone", () -> {
            try (Rift rift = engine()) {
                Imposter created = rift.create(imposter("gone").stub(onGet("/").willReturn(ok())));
                int port = created.port();
                assertTrue(rift.imposter(port).isPresent());

                created.delete();
                created.delete();
                assertEquals(Optional.empty(), rift.imposter(port));
            }
        });
    }
}
