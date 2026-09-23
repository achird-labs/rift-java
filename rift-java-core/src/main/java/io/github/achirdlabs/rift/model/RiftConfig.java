package io.github.achirdlabs.rift.model;

import io.github.achirdlabs.rift.json.JsonObject;
import io.github.achirdlabs.rift.json.JsonValue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Top-level {@code _rift} configuration block for an imposter: flow state, metrics, proxy, script
 * engine defaults, and a named script registry (issue #356 in the engine — a response script can
 * reference a registry entry via {@code {"ref": "name"}} instead of inlining code).
 *
 * <p>{@code extra} carries any {@code _rift} keys not modeled above (for example rift 0.18.0's
 * {@code sequencing}), so they survive a parse → serialize round-trip instead of being dropped.
 * They are re-emitted after the modeled keys, in insertion order. A modeled key in {@code extra} is
 * rejected at construction.
 */
public record RiftConfig(
        Optional<RiftFlowStateConfig> flowState,
        Optional<RiftMetricsConfig> metrics,
        Optional<RiftProxyConfig> proxy,
        Optional<RiftScriptEngineConfig> scriptEngine,
        Map<String, RiftScriptConfig> scripts,
        Map<String, JsonValue> extra) {

    public static final RiftConfig EMPTY =
            new RiftConfig(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Map.of());

    private static final Set<String> MODELED_KEYS = Set.of("flowState", "metrics", "proxy", "scriptEngine", "scripts");

    public RiftConfig {
        Objects.requireNonNull(flowState, "flowState");
        Objects.requireNonNull(metrics, "metrics");
        Objects.requireNonNull(proxy, "proxy");
        Objects.requireNonNull(scriptEngine, "scriptEngine");
        scripts = JsonSupport.orderedCopy(scripts);
        Objects.requireNonNull(extra, "extra");
        JsonSupport.rejectModeledExtraKeys(extra, MODELED_KEYS, "_rift config");
        extra = JsonSupport.orderedCopy(extra);
    }

    public RiftConfig(
            Optional<RiftFlowStateConfig> flowState,
            Optional<RiftMetricsConfig> metrics,
            Optional<RiftProxyConfig> proxy,
            Optional<RiftScriptEngineConfig> scriptEngine,
            Map<String, RiftScriptConfig> scripts) {
        this(flowState, metrics, proxy, scriptEngine, scripts, Map.of());
    }

    /** Returns a copy with {@code extraKey}/{@code value} added to {@code extra}; rejects a modeled key. */
    public RiftConfig withExtra(String extraKey, JsonValue value) {
        Objects.requireNonNull(extraKey, "extraKey");
        Objects.requireNonNull(value, "value");
        Map<String, JsonValue> next = new LinkedHashMap<>(extra);
        next.put(extraKey, value);
        return new RiftConfig(flowState, metrics, proxy, scriptEngine, scripts, next);
    }

    static RiftConfig read(JsonObject obj) {
        Map<String, RiftScriptConfig> scripts = new LinkedHashMap<>();
        if (obj.get("scripts") != null) {
            for (var e : JsonSupport.requireObject(obj.get("scripts"), "scripts").fields().entrySet()) {
                scripts.put(e.getKey(), RiftScriptConfig.read(JsonSupport.requireObject(e.getValue(), "scripts[]")));
            }
        }
        return new RiftConfig(
                Optional.ofNullable(obj.get("flowState")).map(v -> RiftFlowStateConfig.read(JsonSupport.requireObject(v, "flowState"))),
                Optional.ofNullable(obj.get("metrics")).map(v -> RiftMetricsConfig.read(JsonSupport.requireObject(v, "metrics"))),
                Optional.ofNullable(obj.get("proxy")).map(v -> RiftProxyConfig.read(JsonSupport.requireObject(v, "proxy"))),
                Optional.ofNullable(obj.get("scriptEngine")).map(v -> RiftScriptEngineConfig.read(JsonSupport.requireObject(v, "scriptEngine"))),
                scripts,
                JsonSupport.extraFields(obj, MODELED_KEYS));
    }

    JsonObject toJsonValue() {
        JsonObject.Builder builder = JsonObject.builder();
        flowState.ifPresent(v -> builder.put("flowState", v.toJsonValue()));
        metrics.ifPresent(v -> builder.put("metrics", v.toJsonValue()));
        proxy.ifPresent(v -> builder.put("proxy", v.toJsonValue()));
        scriptEngine.ifPresent(v -> builder.put("scriptEngine", v.toJsonValue()));
        if (!scripts.isEmpty()) {
            JsonObject.Builder scriptsBuilder = JsonObject.builder();
            scripts.forEach((k, v) -> scriptsBuilder.put(k, v.toJsonValue()));
            builder.put("scripts", scriptsBuilder.build());
        }
        extra.forEach(builder::put);
        return builder.build();
    }

    public boolean isEmpty() {
        return flowState.isEmpty() && metrics.isEmpty() && proxy.isEmpty() && scriptEngine.isEmpty() && scripts.isEmpty()
                && extra.isEmpty();
    }
}
