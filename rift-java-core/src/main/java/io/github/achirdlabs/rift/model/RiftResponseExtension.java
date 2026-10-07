package io.github.achirdlabs.rift.model;

import io.github.achirdlabs.rift.json.JsonArray;
import io.github.achirdlabs.rift.json.JsonBool;
import io.github.achirdlabs.rift.json.JsonObject;
import io.github.achirdlabs.rift.json.JsonString;
import io.github.achirdlabs.rift.json.JsonValue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Response-level {@code _rift} extension block: fault injection, script-based generation, opt-in
 * response templating, declarative flow-state writes ({@code stateOps}, rift &ge; 0.18.0; see
 * {@link StateOp}) and declarative conditional GET ({@code conditional}, rift &ge; 0.20.0; see
 * {@link RiftConditional}).
 *
 * <p>{@code extra} carries any {@code _rift} keys not modeled above (for example rift 0.18.0's
 * {@code dataset}), so they survive a parse → serialize round-trip instead of
 * being dropped. They are re-emitted after the modeled keys, in insertion order. A modeled key in
 * {@code extra} is rejected at construction.
 */
public record RiftResponseExtension(Optional<RiftFaultConfig> fault, Optional<RiftScriptConfig> script, boolean templated,
        List<StateOp> stateOps, Optional<RiftConditional> conditional, Map<String, JsonValue> extra) {

    public static final RiftResponseExtension EMPTY = new RiftResponseExtension(Optional.empty(), Optional.empty(), false);

    private static final Set<String> MODELED_KEYS = Set.of("fault", "script", "templated", "stateOps", "conditional");

    public RiftResponseExtension {
        Objects.requireNonNull(fault, "fault");
        Objects.requireNonNull(script, "script");
        stateOps = List.copyOf(Objects.requireNonNull(stateOps, "stateOps"));
        Objects.requireNonNull(conditional, "conditional");
        Objects.requireNonNull(extra, "extra");
        JsonSupport.rejectModeledExtraKeys(extra, MODELED_KEYS, "_rift response extension");
        extra = JsonSupport.orderedCopy(extra);
    }

    public RiftResponseExtension(Optional<RiftFaultConfig> fault, Optional<RiftScriptConfig> script, boolean templated) {
        this(fault, script, templated, List.of(), Optional.empty(), Map.of());
    }

    public RiftResponseExtension(Optional<RiftFaultConfig> fault, Optional<RiftScriptConfig> script, boolean templated,
            Map<String, JsonValue> extra) {
        this(fault, script, templated, List.of(), Optional.empty(), extra);
    }

    /** The shape before {@code conditional} (rift-java 0.3.x), kept for source and binary compatibility. */
    public RiftResponseExtension(Optional<RiftFaultConfig> fault, Optional<RiftScriptConfig> script, boolean templated,
            List<StateOp> stateOps, Map<String, JsonValue> extra) {
        this(fault, script, templated, stateOps, Optional.empty(), extra);
    }

    /** Returns a copy with {@code extraKey}/{@code value} added to {@code extra}; rejects a modeled key. */
    public RiftResponseExtension withExtra(String extraKey, JsonValue value) {
        Objects.requireNonNull(extraKey, "extraKey");
        Objects.requireNonNull(value, "value");
        Map<String, JsonValue> next = new LinkedHashMap<>(extra);
        next.put(extraKey, value);
        return new RiftResponseExtension(fault, script, templated, stateOps, conditional, next);
    }

    static RiftResponseExtension read(JsonObject obj) {
        return new RiftResponseExtension(
                Optional.ofNullable(obj.get("fault")).map(v -> RiftFaultConfig.read(JsonSupport.requireObject(v, "fault"))),
                Optional.ofNullable(obj.get("script")).map(v -> RiftScriptConfig.read(JsonSupport.requireObject(v, "script"))),
                JsonSupport.optBool(obj, "templated", false),
                Optional.ofNullable(obj.get("stateOps"))
                        .map(v -> JsonSupport.requireArray(v, "stateOps").items().stream().map(StateOp::read).toList())
                        .orElse(List.of()),
                Optional.ofNullable(obj.get("conditional")).map(RiftResponseExtension::readConditional),
                JsonSupport.extraFields(obj, MODELED_KEYS));
    }

    private static RiftConditional readConditional(JsonValue value) {
        if (value instanceof JsonBool bool) {
            return new RiftConditional.Enabled(bool.value());
        }
        JsonObject obj = JsonSupport.requireObject(value, "_rift.conditional");
        return new RiftConditional.Validators(JsonSupport.optBoolBox(obj, "etag"), JsonSupport.optString(obj, "lastModified"));
    }

    private static JsonValue writeConditional(RiftConditional conditional) {
        if (conditional instanceof RiftConditional.Enabled enabled) {
            return JsonBool.of(enabled.enabled());
        }
        RiftConditional.Validators validators = (RiftConditional.Validators) conditional;
        JsonObject.Builder builder = JsonObject.builder();
        validators.etag().ifPresent(etag -> builder.put("etag", JsonBool.of(etag)));
        validators.lastModified().ifPresent(date -> builder.put("lastModified", new JsonString(date)));
        return builder.build();
    }

    JsonObject toJsonValue() {
        JsonObject.Builder builder = JsonObject.builder();
        fault.ifPresent(v -> builder.put("fault", v.toJsonValue()));
        script.ifPresent(v -> builder.put("script", v.toJsonValue()));
        if (templated) {
            builder.put("templated", JsonBool.TRUE);
        }
        if (!stateOps.isEmpty()) {
            builder.put("stateOps", new JsonArray(stateOps.stream().<JsonValue>map(StateOp::toJsonValue).toList()));
        }
        conditional.ifPresent(v -> builder.put("conditional", writeConditional(v)));
        extra.forEach(builder::put);
        return builder.build();
    }
}
