package io.github.achirdlabs.rift.dsl;

import io.github.achirdlabs.rift.model.RiftFlowStateConfig;

import java.time.Duration;
import java.util.Optional;

/**
 * A flow-state configuration under construction, produced by {@link RiftDsl#inMemoryFlowState()},
 * for use with {@link ImposterSpec#flowState}.
 *
 * <p>Instances are immutable: every chain method returns a new {@code FlowStateSpec}. The terminal
 * {@link #build()} produces the {@link RiftFlowStateConfig} model value.
 */
public final class FlowStateSpec {

    private final long ttlSeconds;
    private final Optional<String> flowIdSource;

    private FlowStateSpec(
            long ttlSeconds,
            Optional<String> flowIdSource) {
        this.ttlSeconds = ttlSeconds;
        this.flowIdSource = flowIdSource;
    }

    static FlowStateSpec inMemory() {
        return new FlowStateSpec(RiftFlowStateConfig.DEFAULT_TTL_SECONDS, Optional.empty());
    }

    /** Sets how long flow state survives without being touched. */
    public FlowStateSpec ttl(Duration duration) {
        return new FlowStateSpec(duration.toSeconds(), flowIdSource);
    }

    /**
     * Correlates flow state using the named request header rather than the imposter's port. Required
     * for per-space stubs ({@link StubSpec#inSpace}) to match: without it the engine resolves every
     * request's flow id to the port, so a space stub never matches.
     */
    public FlowStateSpec flowIdFromHeader(String name) {
        return new FlowStateSpec(ttlSeconds, Optional.of("header:" + name));
    }

    /** Builds the immutable {@link RiftFlowStateConfig} this spec represents. */
    RiftFlowStateConfig build() {
        return new RiftFlowStateConfig(ttlSeconds, flowIdSource);
    }
}
