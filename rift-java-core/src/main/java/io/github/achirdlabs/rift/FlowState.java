package io.github.achirdlabs.rift;

import io.github.achirdlabs.rift.json.JsonValue;

import java.util.Optional;

/** Correlated {@code _rift} flow state for a single flow id, scoped to one imposter. */
public interface FlowState {

    /**
     * The value stored under {@code key}, or {@link Optional#empty()} if unset. The same on every
     * transport: the stored JSON value itself, never the engine's response envelope.
     */
    Optional<JsonValue> get(String key);

    void put(String key, JsonValue value);

    void put(String key, String value);

    void delete(String key);

    /**
     * Removes every key in this flow; other flows are untouched. Idempotent: clearing an absent or
     * empty flow succeeds. The engine keeps a flow's scenario state in the same store, so this also
     * returns every scenario in the flow to its initial state.
     *
     * @throws io.github.achirdlabs.rift.error.EngineUnavailable on the embedded transport when the
     *     loaded native library predates {@code rift_flow_state_clear} (rift &lt; 0.22.0)
     */
    void clear();
}
