package io.github.achirdlabs.rift.dsl;

import io.github.achirdlabs.rift.model.Behavior;
import io.github.achirdlabs.rift.model.Behaviors;
import io.github.achirdlabs.rift.model.Response;

import java.util.List;
import java.util.Map;

/**
 * A raw connection-fault response, produced by {@link RiftDsl#fault(Fault)}. It carries no
 * headers or body; of the behaviors, the engine runs only {@code repeat} on it, so {@link
 * #repeat(int)} is its one chain method.
 */
public final class FaultSpec implements ResponseSpec {

    private final String faultName;
    private final Behaviors behaviors;

    private FaultSpec(String faultName, Behaviors behaviors) {
        this.faultName = faultName;
        this.behaviors = behaviors;
    }

    static FaultSpec of(Fault fault) {
        return new FaultSpec(fault.name(), Behaviors.EMPTY);
    }

    /**
     * Serves this response for the next {@code count} matches before the stub moves to its next
     * response, as {@link BehaviorChain#repeat} does on other responses (the block spelling, {@code
     * "_behaviors": {"repeat": n}}). It needs a following response to move to: on a stub's only
     * response it changes nothing. Requires a rift engine &ge; 0.18.0, which honours {@code repeat}
     * on every response; on {@code create} and {@code replaceAll} the SDK refuses to send it to an
     * older engine, which drops it silently. Calling it again replaces the count.
     */
    public FaultSpec repeat(int count) {
        return new FaultSpec(faultName, new Behaviors(List.of(new Behavior.Repeat(count))));
    }

    @Override
    public Response build() {
        return new Response.Fault(faultName, behaviors, Map.of());
    }
}
