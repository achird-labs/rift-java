package io.github.achirdlabs.rift.model;

import io.github.achirdlabs.rift.json.JsonNumber;
import io.github.achirdlabs.rift.json.JsonObject;
import io.github.achirdlabs.rift.json.JsonString;
import io.github.achirdlabs.rift.json.JsonValue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Flow state configuration: enables stateful scripting keyed by a correlation {@code flow_id}
 * (by default the imposter's port; {@code flowIdSource} can select a header instead).
 *
 * <p>{@code extra} carries any {@code flowState} keys not modeled above (options for a
 * provider-supplied store), so they survive a parse → serialize round-trip instead of being dropped.
 * They are re-emitted after the modeled keys, in insertion order. A modeled key in {@code extra} is
 * rejected at construction.
 */
public record RiftFlowStateConfig(String backend, long ttlSeconds, Optional<RiftRedisConfig> redis, Optional<String> flowIdSource,
        Map<String, JsonValue> extra) {

    public static final String DEFAULT_BACKEND = "inmemory";
    public static final long DEFAULT_TTL_SECONDS = 300;

    private static final Set<String> MODELED_KEYS = Set.of("backend", "ttlSeconds", "redis", "flowIdSource");

    public RiftFlowStateConfig {
        Objects.requireNonNull(backend, "backend");
        Objects.requireNonNull(redis, "redis");
        Objects.requireNonNull(flowIdSource, "flowIdSource");
        Objects.requireNonNull(extra, "extra");
        JsonSupport.rejectModeledExtraKeys(extra, MODELED_KEYS, "_rift.flowState");
        extra = JsonSupport.orderedCopy(extra);
    }

    public RiftFlowStateConfig(String backend, long ttlSeconds, Optional<RiftRedisConfig> redis, Optional<String> flowIdSource) {
        this(backend, ttlSeconds, redis, flowIdSource, Map.of());
    }

    /** Returns a copy with {@code extraKey}/{@code value} added to {@code extra}; rejects a modeled key. */
    public RiftFlowStateConfig withExtra(String extraKey, JsonValue value) {
        Objects.requireNonNull(extraKey, "extraKey");
        Objects.requireNonNull(value, "value");
        Map<String, JsonValue> next = new LinkedHashMap<>(extra);
        next.put(extraKey, value);
        return new RiftFlowStateConfig(backend, ttlSeconds, redis, flowIdSource, next);
    }

    static RiftFlowStateConfig read(JsonObject obj) {
        return new RiftFlowStateConfig(
                JsonSupport.optString(obj, "backend").orElse(DEFAULT_BACKEND),
                JsonSupport.optLong(obj, "ttlSeconds", DEFAULT_TTL_SECONDS),
                Optional.ofNullable(obj.get("redis")).map(v -> RiftRedisConfig.read(JsonSupport.requireObject(v, "redis"))),
                JsonSupport.optString(obj, "flowIdSource"),
                JsonSupport.extraFields(obj, MODELED_KEYS));
    }

    JsonObject toJsonValue() {
        JsonObject.Builder builder = JsonObject.builder();
        builder.put("backend", new JsonString(backend));
        builder.put("ttlSeconds", JsonNumber.of(ttlSeconds));
        redis.ifPresent(v -> builder.put("redis", v.toJsonValue()));
        flowIdSource.ifPresent(v -> builder.put("flowIdSource", new JsonString(v)));
        extra.forEach(builder::put);
        return builder.build();
    }
}
