package io.github.achirdlabs.rift.model;

import io.github.achirdlabs.rift.json.JsonObject;
import io.github.achirdlabs.rift.json.JsonString;
import io.github.achirdlabs.rift.json.JsonValue;

import java.util.Objects;
import java.util.Optional;

/**
 * Script configuration for response generation. Exactly one of {@code code}, {@code file},
 * {@code ref} or {@code component} is valid; this is enforced by the engine's config-time resolution
 * pass, not by the wire codec, so a config using only {@code code} (the legacy shape) keeps parsing
 * unchanged.
 *
 * <p>{@code component}, {@code export}, {@code knobs} and {@code instance} belong to
 * {@code engine: "wasm"} (rift &ge; 0.25.0): {@code component} names a component uploaded with
 * {@code POST /components} as {@code "sha256:<hex>"}; {@code export} picks {@code "rift"} (the
 * {@code rift:mock/handler} interface) or {@code "http"} (a standard {@code wasi:http/incoming-handler});
 * {@code knobs} is any JSON the component reads at {@code init}; {@code instance} is {@code "request"}
 * (a fresh instance per call, the default) or {@code "imposter"} (one kept instance, whose memory is
 * the twin's state). They are carried as written, so a value a newer engine adds still round-trips.
 * For a wasm script {@code code} is the component's bytes in base64.
 */
public record RiftScriptConfig(Optional<String> engine, Optional<String> code, Optional<String> file, Optional<String> ref,
        Optional<String> component, Optional<String> export, Optional<JsonValue> knobs, Optional<String> instance) {

    public RiftScriptConfig {
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(component, "component");
        Objects.requireNonNull(export, "export");
        Objects.requireNonNull(knobs, "knobs");
        Objects.requireNonNull(instance, "instance");
    }

    /** The shape before the wasm keys (rift-java 0.3.7 and earlier), kept for source and binary compatibility. */
    public RiftScriptConfig(Optional<String> engine, Optional<String> code, Optional<String> file, Optional<String> ref) {
        this(engine, code, file, ref, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    public static final RiftScriptConfig EMPTY =
            new RiftScriptConfig(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

    /** How many of code/file/ref/component are set; the engine treats exactly one as valid. */
    public int sourceCount() {
        return (code.isPresent() ? 1 : 0) + (file.isPresent() ? 1 : 0) + (ref.isPresent() ? 1 : 0)
                + (component.isPresent() ? 1 : 0);
    }

    /**
     * Whether this script runs on the wasm engine or carries a wasm-only key: {@code engine: "wasm"}, a
     * {@code component}, a {@code .wasm} file, or any of {@code export}/{@code knobs}/{@code instance}.
     */
    public boolean isWasm() {
        return engine.filter("wasm"::equalsIgnoreCase).isPresent()
                || component.isPresent() || export.isPresent() || knobs.isPresent() || instance.isPresent()
                || (engine.isEmpty() && file.filter(f -> f.toLowerCase(java.util.Locale.ROOT).endsWith(".wasm")).isPresent());
    }

    static RiftScriptConfig read(JsonObject obj) {
        return new RiftScriptConfig(
                JsonSupport.optString(obj, "engine"),
                JsonSupport.optString(obj, "code"),
                JsonSupport.optString(obj, "file"),
                JsonSupport.optString(obj, "ref"),
                JsonSupport.optString(obj, "component"),
                JsonSupport.optString(obj, "export"),
                Optional.ofNullable(obj.get("knobs")),
                JsonSupport.optString(obj, "instance"));
    }

    JsonObject toJsonValue() {
        JsonObject.Builder builder = JsonObject.builder();
        engine.ifPresent(v -> builder.put("engine", new JsonString(v)));
        code.ifPresent(v -> builder.put("code", new JsonString(v)));
        file.ifPresent(v -> builder.put("file", new JsonString(v)));
        ref.ifPresent(v -> builder.put("ref", new JsonString(v)));
        component.ifPresent(v -> builder.put("component", new JsonString(v)));
        export.ifPresent(v -> builder.put("export", new JsonString(v)));
        knobs.ifPresent(v -> builder.put("knobs", v));
        instance.ifPresent(v -> builder.put("instance", new JsonString(v)));
        return builder.build();
    }
}
