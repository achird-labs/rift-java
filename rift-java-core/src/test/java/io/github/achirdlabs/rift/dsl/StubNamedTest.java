package io.github.achirdlabs.rift.dsl;

import io.github.achirdlabs.rift.model.Stub;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static io.github.achirdlabs.rift.dsl.RiftDsl.ok;
import static io.github.achirdlabs.rift.dsl.RiftDsl.onGet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code StubSpec.named} labels a stub; the label survives every later chain call (#310). */
class StubNamedTest {

    @Test
    void namedSetsTheStubsNameAndWritesItFirst() {
        Stub stub = onGet("/health").named("Health").willReturn(ok()).build();
        assertEquals(Optional.of("Health"), stub.name());
        assertTrue(stub.toJson().startsWith("{\"name\":\"Health\""), stub.toJson());
    }

    @Test
    void theNameSurvivesEveryLaterChainCall() {
        Stub stub = onGet("/x").named("n")
                .withHeader("h", "v").withQuery("q", "1").withPredicateInject("() => true")
                .willReturn(ok()).scenario("sc").whenScenarioState("a").willSetScenarioState("b")
                .inSpace("f").withId("id1").withRoute("/x/{id}")
                .build();
        assertEquals(Optional.of("n"), stub.name());
        assertEquals(Optional.of("sc"), stub.scenarioName());
        assertEquals(Optional.of("id1"), stub.id());
    }

    @Test
    void theNameSurvivesAScenarioTransition() {
        StubSpec spec = onGet("/x").named("n").withScenarioTransition("sc", Optional.empty(), "b");
        assertEquals(Optional.of("n"), spec.build().name());
    }

    @Test
    void scenarioSetsNoNameAndAnUnnamedStubHasNone() {
        assertEquals(Optional.empty(), onGet("/x").scenario("sc").build().name());
    }

    @Test
    void theLastNameWins() {
        assertEquals(Optional.of("b"), onGet("/x").named("a").named("b").build().name());
    }

    @Test
    void aNullNameIsRefused() {
        assertThrows(NullPointerException.class, () -> onGet("/x").named(null));
    }
}
