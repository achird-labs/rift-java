package io.github.achirdlabs.rift;

import java.util.List;

/** An imposter's scenario finite-state machine: the current state of each named scenario. */
public interface Scenarios {

    /** A scenario's current state, as reported by the engine. */
    record State(String name, String state) {
    }

    /** All scenario states, across every flow/space. */
    List<State> list();

    /**
     * Scenario states scoped to a single flow/space.
     *
     * @throws IllegalArgumentException if {@code flowId} is blank — a blank id is never the default
     *     flow but a distinct, silently-wrong partition, so it is rejected rather than sent verbatim
     */
    List<State> list(String flowId);

    /** The current state of the named scenario. */
    String state(String name);

    /** Forces the named scenario into {@code state} on the imposter's default flow. */
    void setState(String name, String state);

    /**
     * Forces the named scenario into {@code state}, scoped to a single {@code flowId}/space.
     *
     * @throws IllegalArgumentException if {@code flowId} is blank — a blank id is never the default
     *     flow but a distinct, silently-wrong partition, so it is rejected rather than sent verbatim
     */
    void setState(String name, String state, String flowId);

    /** Resets every scenario on this imposter's default flow back to its initial state. */
    void reset();

    /**
     * Resets every scenario on this imposter back to its initial state, scoped to a single
     * {@code flowId}/space; other flows are untouched. Resetting a flow that holds no scenario state
     * succeeds.
     *
     * @throws IllegalArgumentException if {@code flowId} is blank — a blank id is never the default
     *     flow but a distinct, silently-wrong partition, so it is rejected rather than sent verbatim
     */
    void reset(String flowId);
}
