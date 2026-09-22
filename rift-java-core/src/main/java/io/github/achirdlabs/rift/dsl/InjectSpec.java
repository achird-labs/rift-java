package io.github.achirdlabs.rift.dsl;

import io.github.achirdlabs.rift.model.Behavior;
import io.github.achirdlabs.rift.model.Behaviors;
import io.github.achirdlabs.rift.model.Response;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * A response entirely computed by inline JavaScript (Mountebank's {@code inject} response),
 * produced by {@link RiftDsl#inject(String)}. Its only chain methods are the {@link BehaviorChain}
 * chainers, which run on the script's response (a script that fails runs none) and need a rift
 * engine &ge; 0.18.0.
 *
 * <p>Instances are immutable: every chain method returns a new {@code InjectSpec}.
 */
public final class InjectSpec implements ResponseSpec, BehaviorChain<InjectSpec> {

    private final String javascript;
    private final List<Behavior> behaviors;

    private InjectSpec(String javascript, List<Behavior> behaviors) {
        this.javascript = javascript;
        this.behaviors = behaviors;
    }

    static InjectSpec of(String javascript) {
        return new InjectSpec(javascript, List.of());
    }

    @Override
    public InjectSpec withBehavior(Behavior behavior) {
        return new InjectSpec(javascript, Stream.concat(behaviors.stream(), Stream.of(behavior)).toList());
    }

    @Override
    public Response build() {
        return new Response.Inject(javascript, new Behaviors(behaviors), Map.of());
    }
}
