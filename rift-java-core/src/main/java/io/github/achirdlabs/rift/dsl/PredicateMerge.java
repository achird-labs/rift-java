package io.github.achirdlabs.rift.dsl;

import io.github.achirdlabs.rift.json.JsonObject;
import io.github.achirdlabs.rift.json.JsonValue;
import io.github.achirdlabs.rift.model.PredicateOperation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** {@link RiftDsl#merge}: joins single-operator field predicates into one operator object. */
final class PredicateMerge {

    /** The fields whose value is an object of named entries, merged entry by entry. */
    private static final Set<String> KEYED_FIELDS = Set.of("headers", "query");

    private PredicateMerge() {
    }

    static PredicateSpec merge(PredicateSpec first, PredicateSpec... more) {
        String operator = operatorOf(first);
        Map<String, JsonValue> fields = new LinkedHashMap<>();
        addFields(fields, first);
        for (PredicateSpec part : more) {
            String next = operatorOf(part);
            if (!next.equals(operator)) {
                throw new IllegalArgumentException("merge needs one operator, but got " + operator + " and " + next);
            }
            addFields(fields, part);
        }
        return PredicateSpec.of(rebuild(first.operation(), fields));
    }

    private static String operatorOf(PredicateSpec part) {
        if (part.hasParametersOrSelector()) {
            throw new IllegalArgumentException(
                    "set caseSensitive, keyCaseSensitive, except and selectors on the merged predicate, not on a part");
        }
        PredicateOperation op = part.operation();
        if (op instanceof PredicateOperation.Equals) return "equals";
        if (op instanceof PredicateOperation.DeepEquals) return "deepEquals";
        if (op instanceof PredicateOperation.Contains) return "contains";
        if (op instanceof PredicateOperation.StartsWith) return "startsWith";
        if (op instanceof PredicateOperation.EndsWith) return "endsWith";
        if (op instanceof PredicateOperation.Matches) return "matches";
        if (op instanceof PredicateOperation.Exists) return "exists";
        String name = op instanceof PredicateOperation.And ? "and"
                : op instanceof PredicateOperation.Or ? "or"
                : op instanceof PredicateOperation.Not ? "not" : "inject";
        throw new IllegalArgumentException("merge takes field predicates, not " + name);
    }

    private static void addFields(Map<String, JsonValue> into, PredicateSpec part) {
        fieldsOf(part.operation()).forEach((field, value) -> {
            JsonValue existing = into.get(field);
            if (existing == null) {
                into.put(field, value);
            } else if (KEYED_FIELDS.contains(field) && existing instanceof JsonObject had && value instanceof JsonObject add) {
                JsonObject.Builder joined = JsonObject.builder();
                had.fields().forEach(joined::put);
                add.fields().forEach((name, v) -> {
                    if (had.fields().containsKey(name)) {
                        throw new IllegalArgumentException("merge binds " + field + "." + name + " twice");
                    }
                    joined.put(name, v);
                });
                into.put(field, joined.build());
            } else {
                throw new IllegalArgumentException("merge binds " + field + " twice");
            }
        });
    }

    private static Map<String, JsonValue> fieldsOf(PredicateOperation op) {
        if (op instanceof PredicateOperation.Equals o) return o.fields();
        if (op instanceof PredicateOperation.DeepEquals o) return o.fields();
        if (op instanceof PredicateOperation.Contains o) return o.fields();
        if (op instanceof PredicateOperation.StartsWith o) return o.fields();
        if (op instanceof PredicateOperation.EndsWith o) return o.fields();
        if (op instanceof PredicateOperation.Matches o) return o.fields();
        if (op instanceof PredicateOperation.Exists o) return o.fields();
        throw new IllegalStateException("unreachable: operatorOf accepted " + op);
    }

    private static PredicateOperation rebuild(PredicateOperation like, Map<String, JsonValue> fields) {
        Function<Map<String, JsonValue>, PredicateOperation> make =
                like instanceof PredicateOperation.Equals ? PredicateOperation.Equals::new
                : like instanceof PredicateOperation.DeepEquals ? PredicateOperation.DeepEquals::new
                : like instanceof PredicateOperation.Contains ? PredicateOperation.Contains::new
                : like instanceof PredicateOperation.StartsWith ? PredicateOperation.StartsWith::new
                : like instanceof PredicateOperation.EndsWith ? PredicateOperation.EndsWith::new
                : like instanceof PredicateOperation.Matches ? PredicateOperation.Matches::new
                : PredicateOperation.Exists::new;
        return make.apply(fields);
    }
}
