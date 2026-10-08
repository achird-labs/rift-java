package io.github.achirdlabs.rift.conformance;

import io.github.achirdlabs.rift.FlowState;
import io.github.achirdlabs.rift.Imposter;
import io.github.achirdlabs.rift.Rift;
import io.github.achirdlabs.rift.error.EngineUnavailable;
import io.github.achirdlabs.rift.json.JsonValue;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.Optional;
import java.util.stream.Stream;

import static io.github.achirdlabs.rift.conformance.LiveEngine.engine;
import static io.github.achirdlabs.rift.conformance.LiveEngine.gated;
import static io.github.achirdlabs.rift.dsl.RiftDsl.imposter;
import static io.github.achirdlabs.rift.dsl.RiftDsl.inMemoryFlowState;
import static io.github.achirdlabs.rift.dsl.RiftDsl.ok;
import static io.github.achirdlabs.rift.dsl.RiftDsl.onGet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code FlowState.get} returns the stored value on every transport (#236). Needs {@code RIFT_IT=1}. */
class FlowStateIT {

    @TestFactory
    Stream<DynamicTest> getReturnsTheStoredValue() {
        return gated("a value put into flow state reads back as itself", () -> {
            try (Rift rift = engine()) {
                Imposter imposter = rift.create(imposter("state").protocol("http")
                        .flowState(inMemoryFlowState())
                        .stub(onGet("/").willReturn(ok())));
                FlowState state = imposter.flowState("flow-1");

                state.put("token", JsonValue.parse("{\"a\":1}"));
                state.put("name", "rift");
                assertEquals(Optional.of(JsonValue.parse("{\"a\":1}")), state.get("token"));
                assertEquals(Optional.of(JsonValue.parse("\"rift\"")), state.get("name"));
                assertEquals(Optional.empty(), state.get("missing"));
            }
        });
    }

    @TestFactory
    Stream<DynamicTest> clearRemovesEveryKeyInTheFlow() {
        return gated("clear() empties one flow, leaves other flows alone, and is idempotent", () -> {
            try (Rift rift = engine()) {
                Imposter imposter = rift.create(imposter("state").protocol("http")
                        .flowState(inMemoryFlowState())
                        .stub(onGet("/").willReturn(ok())));
                FlowState state = imposter.flowState("flow-1");
                FlowState other = imposter.flowState("flow-2");
                state.put("token", "a");
                state.put("name", "rift");
                other.put("token", "b");

                try {
                    state.clear();
                } catch (EngineUnavailable e) {
                    // Embedded on natives that predate rift#1328 (<= 0.21.0): the symbol is bound
                    // optionally, so the call names what it needs instead of half-clearing.
                    assertEquals(ConformanceTransport.EMBEDDED, ConformanceTransport.selected(), e.getMessage());
                    assertTrue(e.getMessage().contains("rift_flow_state_clear"), e.getMessage());
                    return;
                }
                assertEquals(Optional.empty(), state.get("token"));
                assertEquals(Optional.empty(), state.get("name"));
                assertEquals(Optional.of(JsonValue.parse("\"b\"")), other.get("token"));
                state.clear();
            }
        });
    }
}
