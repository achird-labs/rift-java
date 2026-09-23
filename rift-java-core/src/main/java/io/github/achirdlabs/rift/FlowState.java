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
}
