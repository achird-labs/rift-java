package io.github.achirdlabs.rift.model;

import io.github.achirdlabs.rift.json.JsonArray;
import io.github.achirdlabs.rift.json.JsonNumber;
import io.github.achirdlabs.rift.json.JsonObject;
import io.github.achirdlabs.rift.json.JsonString;
import io.github.achirdlabs.rift.json.JsonValue;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Global script engine settings for an imposter's {@code _rift} block: {@code _rift.scriptEngine}.
 *
 * <p>From rift 0.25.0 the block also carries the wasm limits ({@code wasm}), whether a request may
 * name its own instant ({@code clock}: {@code "wall"} or {@code "header"}), the seed of the imposter's
 * random stream ({@code seed}) and which request parts pick a per-request stream ({@code rngKey},
 * which needs {@code seed}). Each is absent unless set, and an older engine ignores them.
 *
 * <p>{@code seed} is an unsigned 64-bit integer on the wire, which no Java primitive holds, so it is
 * carried as the JSON value itself: the engine admits any value there and runs unseeded on one that is
 * not a {@code u64}, and echoes it back as written, so a read must not refuse it. {@link
 * #unsignedSeed()} reads it as a {@code long} bit pattern; {@link #seedJson(long)} writes one.
 * {@code clock} is carried as the string written for the same reason: the engine runs an unknown one
 * as {@code "wall"}. {@code rngKey} is read strictly, as a list of strings: a 0.25.0 engine refuses
 * any other shape at admission, so only a config an older engine stored can carry one.
 *
 * @param defaultEngine the engine for a script that names none and has no recognised file extension
 * @param timeoutMs     the per-call script deadline in milliseconds
 * @param wasm          the {@code engine: "wasm"} per-call limits
 * @param clock         {@code "wall"} or {@code "header"} ({@code X-Rift-Now} names a request's instant)
 * @param seed          the seed of the imposter's random stream, as a JSON value
 * @param rngKey        the request parts that name a request's own random stream
 */
public record RiftScriptEngineConfig(String defaultEngine, long timeoutMs, Optional<WasmLimits> wasm,
        Optional<String> clock, Optional<JsonValue> seed, Optional<List<String>> rngKey) {

    public static final String DEFAULT_ENGINE = "rhai";
    /** What {@link #read} assumes for a block with no {@code timeoutMs}. */
    public static final long DEFAULT_TIMEOUT_MS = 30_000L;
    /** The engine's own default {@code timeoutMs}, written when a block is built only to carry the other keys. */
    public static final long ENGINE_DEFAULT_TIMEOUT_MS = 5_000L;

    private static final BigInteger U64_MAX = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);

    public RiftScriptEngineConfig {
        Objects.requireNonNull(defaultEngine, "defaultEngine");
        Objects.requireNonNull(wasm, "wasm");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(seed, "seed");
        Objects.requireNonNull(rngKey, "rngKey");
        rngKey = rngKey.map(List::copyOf);
    }

    /** The shape before the 0.25.0 keys (rift-java 0.3.7 and earlier), kept for source and binary compatibility. */
    public RiftScriptEngineConfig(String defaultEngine, long timeoutMs) {
        this(defaultEngine, timeoutMs, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    /**
     * A block carrying the engine's own defaults ({@code rhai}, {@value #ENGINE_DEFAULT_TIMEOUT_MS} ms) and
     * nothing else: the base when only one of the 0.25.0 keys is set, so setting a seed does not
     * change the script deadline.
     */
    public static RiftScriptEngineConfig engineDefaults() {
        return new RiftScriptEngineConfig(DEFAULT_ENGINE, ENGINE_DEFAULT_TIMEOUT_MS);
    }

    /** The seed as a {@code long} bit pattern, when it is an unsigned 64-bit integer; empty when absent or not one. */
    public OptionalLong unsignedSeed() {
        if (seed.orElse(null) instanceof JsonNumber n && n.raw().matches("0|[1-9]\\d*")) {
            BigInteger value = new BigInteger(n.raw());
            if (value.compareTo(U64_MAX) <= 0) {
                return OptionalLong.of(value.longValue());
            }
        }
        return OptionalLong.empty();
    }

    /** The JSON spelling of an unsigned 64-bit seed given as its bit pattern: {@code -1L} is {@code 18446744073709551615}. */
    public static JsonValue seedJson(long unsignedSeed) {
        return new JsonNumber(Long.toUnsignedString(unsignedSeed));
    }

    /** A copy with {@code defaultEngine} and {@code timeoutMs} replaced and every other key kept. */
    public RiftScriptEngineConfig withDefaults(String defaultEngine, long timeoutMs) {
        return new RiftScriptEngineConfig(defaultEngine, timeoutMs, wasm, clock, seed, rngKey);
    }

    /** A copy with {@code wasm} set. */
    public RiftScriptEngineConfig withWasm(WasmLimits wasm) {
        return new RiftScriptEngineConfig(defaultEngine, timeoutMs, Optional.of(wasm), clock, seed, rngKey);
    }

    /** A copy with {@code clock} set. */
    public RiftScriptEngineConfig withClock(String clock) {
        return new RiftScriptEngineConfig(defaultEngine, timeoutMs, wasm, Optional.of(clock), seed, rngKey);
    }

    /** A copy with {@code seed} set to the unsigned 64-bit integer whose bit pattern is {@code unsignedSeed}. */
    public RiftScriptEngineConfig withSeed(long unsignedSeed) {
        return new RiftScriptEngineConfig(defaultEngine, timeoutMs, wasm, clock, Optional.of(seedJson(unsignedSeed)), rngKey);
    }

    /** A copy with {@code rngKey} set. */
    public RiftScriptEngineConfig withRngKey(List<String> rngKey) {
        return new RiftScriptEngineConfig(defaultEngine, timeoutMs, wasm, clock, seed, Optional.of(rngKey));
    }

    /** Whether any key from rift 0.25.0 ({@code wasm}, {@code clock}, {@code seed}, {@code rngKey}) is set. */
    public boolean hasEngine025Keys() {
        return wasm.isPresent() || clock.isPresent() || seed.isPresent() || rngKey.isPresent();
    }

    static RiftScriptEngineConfig read(JsonObject obj) {
        Optional<List<String>> rngKey = Optional.ofNullable(obj.get("rngKey"))
                .map(v -> JsonSupport.requireArray(v, "_rift.scriptEngine.rngKey").items().stream()
                        .map(item -> JsonSupport.requireString(item, "_rift.scriptEngine.rngKey[]"))
                        .toList());
        return new RiftScriptEngineConfig(
                JsonSupport.optString(obj, "defaultEngine").orElse(DEFAULT_ENGINE),
                JsonSupport.optLong(obj, "timeoutMs", DEFAULT_TIMEOUT_MS),
                Optional.ofNullable(obj.get("wasm")).map(v -> WasmLimits.read(JsonSupport.requireObject(v, "_rift.scriptEngine.wasm"))),
                JsonSupport.optString(obj, "clock"),
                Optional.ofNullable(obj.get("seed")),
                rngKey);
    }

    JsonObject toJsonValue() {
        JsonObject.Builder builder = JsonObject.builder()
                .put("defaultEngine", new JsonString(defaultEngine))
                .put("timeoutMs", JsonNumber.of(timeoutMs));
        wasm.ifPresent(v -> builder.put("wasm", v.toJsonValue()));
        clock.ifPresent(v -> builder.put("clock", new JsonString(v)));
        seed.ifPresent(v -> builder.put("seed", v));
        rngKey.ifPresent(v -> builder.put("rngKey", new JsonArray(v.stream().map(s -> (JsonValue) new JsonString(s)).toList())));
        return builder.build();
    }
}
