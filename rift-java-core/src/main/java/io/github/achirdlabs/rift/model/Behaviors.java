package io.github.achirdlabs.rift.model;

import io.github.achirdlabs.rift.json.JsonArray;
import io.github.achirdlabs.rift.json.JsonObject;
import io.github.achirdlabs.rift.json.JsonValue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A response's behaviors: an ordered set of {@link Behavior}s. Two wire shapes are accepted — the
 * {@code _behaviors} JSON object every fixture uses ({@code {"wait": 100, "decorate": "..."}}) and
 * the {@code behaviors} array-of-single-key-objects the engine emits in {@code GET /imposters}
 * output.
 *
 * <p>Serialization picks whichever shape preserves every entry and its order. The object form
 * cannot represent two entries that share a key — Mountebank spells two {@code copy} behaviors as
 * two array elements — so a repeated key is written as the {@code behaviors} array instead, one
 * single-key element per entry, in order. Nor can it carry order: rift 0.18.0 and Mountebank run an
 * object in a fixed order ({@code wait, lookup, copy, shellTransform, decorate}) but an array in
 * the order written. So when the transforming behaviors ({@code lookup}, {@code copy}, {@code
 * shellTransform}, {@code decorate}) are declared out of that order, the array form is written too,
 * and the declared order is the order that runs. An object read from the wire is normalised to the
 * order it runs in (see {@link #read(JsonObject)}), so only a DSL chain or an array can produce that
 * case. Otherwise the object form loses nothing and is kept, so output is byte-unchanged for
 * everything that already round-tripped. {@code wait},
 * {@code repeat} and unknown keys do not count towards order: where {@code wait} sits changes only
 * when the delay happens, not the response.
 *
 * <p>Carrying a repeated key is not the same as the engine running it. The array form is the only
 * wire shape that can express one, and Mountebank honours it, but rift 0.17.0 merges the array
 * into a single object as it parses, so it still applies only the last entry of a repeated key;
 * running every element arrived after that release. Writing the array stops this SDK from being
 * the component that destroys the entry, and is what Mountebank and a later engine read. rift
 * 0.17.0 likewise runs its own fixed order (copy before lookup, decorate before shellTransform)
 * whatever the form.
 */
public record Behaviors(List<Behavior> entries) {

    public Behaviors {
        entries = List.copyOf(entries);
        if (entries.stream().filter(e -> e instanceof Behavior.Repeat r && r.responseLevel()).count() > 1) {
            throw new IllegalArgumentException(
                    "a response has at most one response-level repeat, got " + entries);
        }
    }

    public static final Behaviors EMPTY = new Behaviors(List.of());

    /**
     * The transforming behaviors in the order rift 0.18.0 and Mountebank run them from a {@code
     * _behaviors} object ({@code wait}, which comes first, is left out: its position changes only
     * when the delay happens).
     */
    private static final List<String> CANONICAL_ORDER = List.of("lookup", "copy", "shellTransform", "decorate");

    /**
     * Reads the object form. An engine runs an object's transforming behaviors in {@link
     * #CANONICAL_ORDER} whatever order they were written in, so they are read in that order: the
     * slots they occupy are refilled in canonical order, and every other key stays where it was. An
     * object already in canonical order reads back unchanged, and one that is not writes back as the
     * canonical object it always meant, rather than as an array that would run in its written order.
     */
    static Behaviors read(JsonObject obj) {
        List<Behavior> entries = new ArrayList<>(obj.fields().entrySet().stream()
                .map(e -> Behavior.read(e.getKey(), e.getValue()))
                .toList());
        List<Integer> slots = new ArrayList<>();
        List<Behavior> transforming = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            if (CANONICAL_ORDER.contains(entries.get(i).key())) {
                slots.add(i);
                transforming.add(entries.get(i));
            }
        }
        transforming.sort(java.util.Comparator.comparingInt(b -> CANONICAL_ORDER.indexOf(b.key())));
        for (int i = 0; i < slots.size(); i++) {
            entries.set(slots.get(i), transforming.get(i));
        }
        return new Behaviors(entries);
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
     * Whether writing these entries as the {@code _behaviors} object would drop one or change the
     * order they run in: true when a behavior key appears more than once, since the object form
     * keeps only the last, or when the transforming behaviors are out of {@link #CANONICAL_ORDER},
     * since an engine runs the object form in that order whatever order it was written in.
     */
    boolean requiresArrayForm() {
        Set<String> seen = new HashSet<>();
        int lastRank = -1;
        boolean outOfOrder = false;
        for (Behavior entry : entries) {
            if (!seen.add(entry.key())) {
                return true;
            }
            int rank = CANONICAL_ORDER.indexOf(entry.key());
            if (rank >= 0) {
                outOfOrder |= rank < lastRank;
                lastRank = Math.max(lastRank, rank);
            }
        }
        return outOfOrder;
    }

    /**
     * The {@code repeat} that takes effect, honouring the response-level spelling over a block one
     * as a current engine does. Both are kept in {@link #entries()} so neither is lost on a write;
     * this reports which of them wins.
     *
     * <p>Engine caveat: rift 0.17.0 has no response-level {@code repeat} field and honours the block
     * one instead, so against that release the effective value is the block entry.
     */
    public Optional<Behavior.Repeat> effectiveRepeat() {
        return responseLevelRepeat().or(() -> entries.stream()
                .filter(Behavior.Repeat.class::isInstance)
                .map(Behavior.Repeat.class::cast)
                .findFirst());
    }

    /**
     * The entry that arrived as a response-level {@code repeat}, if any — it is written back beside
     * {@code is} rather than inside the block.
     */
    Optional<Behavior.Repeat> responseLevelRepeat() {
        return entries.stream()
                .filter(Behavior.Repeat.class::isInstance)
                .map(Behavior.Repeat.class::cast)
                .filter(Behavior.Repeat::responseLevel)
                .findFirst();
    }

    /**
     * These entries minus any response-level {@code repeat}: what the block itself holds. The
     * object-versus-array choice is made over this, so a lone response-level {@code repeat} never
     * drags the block into the array form.
     */
    Behaviors withoutResponseLevelRepeat() {
        List<Behavior> block = entries.stream()
                .filter(entry -> !(entry instanceof Behavior.Repeat repeat && repeat.responseLevel()))
                .toList();
        return block.size() == entries.size() ? this : new Behaviors(block);
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
