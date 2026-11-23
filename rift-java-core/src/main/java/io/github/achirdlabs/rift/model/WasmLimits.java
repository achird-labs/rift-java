package io.github.achirdlabs.rift.model;

import io.github.achirdlabs.rift.json.JsonNumber;
import io.github.achirdlabs.rift.json.JsonObject;

import java.util.Objects;
import java.util.Optional;

/**
 * Per-call limits on an {@code engine: "wasm"} script: {@code _rift.scriptEngine.wasm} (rift &ge;
 * 0.25.0). An absent field is the engine's default, and stays absent on the wire: unlimited
 * {@code fuel}, 64 MiB of linear memory, 4 tables of at most 100 000 elements. The wall-clock deadline
 * is {@code _rift.scriptEngine.timeoutMs}, as for every engine.
 *
 * @param fuel             the compute budget in wasmtime fuel units; a call that spends it fails with
 *                         "fuel exhausted". Deterministic, unlike the deadline
 * @param maxMemoryMiB     the largest linear memory a call may grow to, in MiB
 * @param maxTables        the most tables a call may create
 * @param maxTableElements the most elements one table may hold
 */
public record WasmLimits(Optional<Long> fuel, Optional<Long> maxMemoryMiB, Optional<Long> maxTables,
        Optional<Long> maxTableElements) {

    /** Every limit at the engine's default. */
    public static final WasmLimits DEFAULTS = new WasmLimits(Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty());

    /** The engine reads the three size limits as {@code u32}. */
    private static final long U32_MAX = 0xFFFF_FFFFL;

    public WasmLimits {
        Objects.requireNonNull(fuel, "fuel");
        Objects.requireNonNull(maxMemoryMiB, "maxMemoryMiB");
        Objects.requireNonNull(maxTables, "maxTables");
        Objects.requireNonNull(maxTableElements, "maxTableElements");
        fuel.ifPresent(v -> requireRange("fuel", v, Long.MAX_VALUE));
        maxMemoryMiB.ifPresent(v -> requireRange("maxMemoryMiB", v, U32_MAX));
        maxTables.ifPresent(v -> requireRange("maxTables", v, U32_MAX));
        maxTableElements.ifPresent(v -> requireRange("maxTableElements", v, U32_MAX));
    }

    /** A copy with {@code fuel} set. */
    public WasmLimits withFuel(long fuel) {
        return new WasmLimits(Optional.of(fuel), maxMemoryMiB, maxTables, maxTableElements);
    }

    /** A copy with {@code maxMemoryMiB} set. */
    public WasmLimits withMaxMemoryMiB(long mib) {
        return new WasmLimits(fuel, Optional.of(mib), maxTables, maxTableElements);
    }

    /** A copy with {@code maxTables} set. */
    public WasmLimits withMaxTables(long tables) {
        return new WasmLimits(fuel, maxMemoryMiB, Optional.of(tables), maxTableElements);
    }

    /** A copy with {@code maxTableElements} set. */
    public WasmLimits withMaxTableElements(long elements) {
        return new WasmLimits(fuel, maxMemoryMiB, maxTables, Optional.of(elements));
    }

    private static void requireRange(String key, long value, long max) {
        if (value < 0 || value > max) {
            throw new IllegalArgumentException("wasm " + key + " must be in 0.." + max + ", was " + value);
        }
    }

    static WasmLimits read(JsonObject obj) {
        try {
            return new WasmLimits(
                    JsonSupport.optLongBox(obj, "fuel"),
                    JsonSupport.optLongBox(obj, "maxMemoryMiB"),
                    JsonSupport.optLongBox(obj, "maxTables"),
                    JsonSupport.optLongBox(obj, "maxTableElements"));
        } catch (IllegalArgumentException e) {
            throw new WireFormatException("_rift.scriptEngine.wasm: " + e.getMessage());
        }
    }

    JsonObject toJsonValue() {
        JsonObject.Builder builder = JsonObject.builder();
        fuel.ifPresent(v -> builder.put("fuel", JsonNumber.of(v)));
        maxMemoryMiB.ifPresent(v -> builder.put("maxMemoryMiB", JsonNumber.of(v)));
        maxTables.ifPresent(v -> builder.put("maxTables", JsonNumber.of(v)));
        maxTableElements.ifPresent(v -> builder.put("maxTableElements", JsonNumber.of(v)));
        return builder.build();
    }
}
