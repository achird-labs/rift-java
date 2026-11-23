package io.github.achirdlabs.rift;

import io.github.achirdlabs.rift.error.CommunicationError;
import io.github.achirdlabs.rift.json.JsonArray;
import io.github.achirdlabs.rift.json.JsonNumber;
import io.github.achirdlabs.rift.json.JsonObject;
import io.github.achirdlabs.rift.json.JsonString;
import io.github.achirdlabs.rift.json.JsonValue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One wasm instance an imposter keeps for its {@code instance: "imposter"} stubs (rift &ge; 0.25.0),
 * as {@link Imposter#wasmInstances()} lists it. There is one per component, export and knobs the
 * stubs reference, once a call has made it.
 *
 * @param id             the instance's id, the key {@link Imposter#wasmSnapshot(String)} takes
 * @param sha256         the component's SHA-256, 64 hex digits
 * @param export         the {@code export} as the stub wrote it; empty when the stub left it unset
 * @param knobsHash      a short hash of the instance's knobs
 * @param instantiatedAt when the instance was made
 * @param calls          the calls it has answered since
 * @param memoryBytes    its linear memory, in bytes
 */
public record WasmInstanceInfo(String id, String sha256, Optional<String> export, String knobsHash,
        Instant instantiatedAt, long calls, long memoryBytes) {

    public WasmInstanceInfo {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sha256, "sha256");
        Objects.requireNonNull(export, "export");
        Objects.requireNonNull(knobsHash, "knobsHash");
        Objects.requireNonNull(instantiatedAt, "instantiatedAt");
    }

    /** Reads {@code {"instances": [ … ]}}; a body of another shape is a {@link CommunicationError}. */
    static List<WasmInstanceInfo> readList(JsonValue body) {
        if (!(body instanceof JsonObject obj) || !(obj.get("instances") instanceof JsonArray instances)) {
            throw new CommunicationError("rift GET /imposters/{port}/wasm/instances answered without an 'instances' array: "
                    + body.toJson());
        }
        List<WasmInstanceInfo> out = new ArrayList<>();
        for (JsonValue item : instances.items()) {
            out.add(read(item));
        }
        return List.copyOf(out);
    }

    private static WasmInstanceInfo read(JsonValue item) {
        try {
            if (item instanceof JsonObject o && o.get("id") instanceof JsonString id && o.get("sha256") instanceof JsonString sha
                    && o.get("knobsHash") instanceof JsonString knobs && o.get("instantiatedAt") instanceof JsonNumber at
                    && o.get("calls") instanceof JsonNumber calls && o.get("memoryBytes") instanceof JsonNumber memory) {
                Optional<String> export = o.get("export") instanceof JsonString e ? Optional.of(e.value()) : Optional.empty();
                return new WasmInstanceInfo(id.value(), sha.value(), export, knobs.value(), Instant.ofEpochMilli(at.asLong()),
                        calls.asLong(), memory.asLong());
            }
        } catch (NumberFormatException e) {
            throw new CommunicationError("rift wasm instance has a non-integer count: " + item.toJson(), e);
        }
        throw new CommunicationError("rift wasm instance is missing a field: " + item.toJson());
    }
}
