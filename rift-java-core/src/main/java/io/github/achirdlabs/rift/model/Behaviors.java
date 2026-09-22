package io.github.achirdlabs.rift.model;

import io.github.achirdlabs.rift.json.JsonArray;
import io.github.achirdlabs.rift.json.JsonObject;
import io.github.achirdlabs.rift.json.JsonValue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A response's behaviors: an ordered set of {@link Behavior}s. Two wire shapes are accepted — the
 * {@code _behaviors} JSON object every fixture uses ({@code {"wait": 100, "decorate": "..."}}) and
 * the {@code behaviors} array-of-single-key-objects the engine emits in {@code GET /imposters}
 * output.
 *
 * <p>Serialization picks whichever shape preserves every entry. The object form cannot represent
 * two entries that share a key — Mountebank spells two {@code copy} behaviors as two array
 * elements — so a repeated key is written as the {@code behaviors} array instead, one single-key
 * element per entry, in order. With no repeated key the object form loses nothing and is kept, so
 * output is byte-unchanged for everything that already round-tripped.
 *
 * <p>Carrying a repeated key is not the same as the engine running it. The array form is the only
 * wire shape that can express one, and Mountebank honours it, but rift 0.17.0 merges the array
 * into a single object as it parses, so it still applies only the last entry of a repeated key;
 * running every element arrived after that release. Writing the array stops this SDK from being
 * the component that destroys the entry, and is what Mountebank and a later engine read.
 */
public record Behaviors(List<Behavior> entries) {

    public Behaviors {
        entries = List.copyOf(entries);
    }

    public static final Behaviors EMPTY = new Behaviors(List.of());

    static Behaviors read(JsonObject obj) {
        return new Behaviors(obj.fields().entrySet().stream()
                .map(e -> Behavior.read(e.getKey(), e.getValue()))
                .toList());
    }

    /** Reads the array-of-single-key-objects form: {@code [{"wait":100},{"decorate":"..."}]}. */
    static Behaviors readArray(JsonArray arr) {
        List<Behavior> entries = new ArrayList<>();
        for (JsonValue el : arr.items()) {
            JsonObject entry = JsonSupport.requireObject(el, "behaviors[]");
            if (entry.fields().size() != 1) {
                throw new WireFormatException("behaviors[]: each entry must have exactly one key");
            }
            var e = entry.fields().entrySet().iterator().next();
            entries.add(Behavior.read(e.getKey(), e.getValue()));
        }
        return new Behaviors(entries);
    }

    /**
     * The object form. Lossy when a key repeats — callers must consult {@link #requiresArrayForm()}
     * first and use {@link #toJsonArray()} instead.
     */
    JsonObject toJsonValue() {
        JsonObject.Builder builder = JsonObject.builder();
        entries.forEach(b -> builder.put(b.key(), b.value()));
        return builder.build();
    }

    /**
     * Whether writing these entries as the {@code _behaviors} object would drop one. True exactly
     * when a behavior key appears more than once, since the object form keeps only the last.
     */
    boolean requiresArrayForm() {
        Set<String> seen = new HashSet<>();
        for (Behavior entry : entries) {
            if (!seen.add(entry.key())) {
                return true;
            }
        }
        return false;
    }

    /** The array form: one single-key element per entry, in entry order. Never lossy. */
    JsonArray toJsonArray() {
        return new JsonArray(entries.stream()
                .map(entry -> (JsonValue) JsonObject.builder().put(entry.key(), entry.value()).build())
                .toList());
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }
}
