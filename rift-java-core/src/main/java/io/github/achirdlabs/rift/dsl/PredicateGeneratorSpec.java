package io.github.achirdlabs.rift.dsl;

import io.github.achirdlabs.rift.json.JsonBool;
import io.github.achirdlabs.rift.json.JsonObject;
import io.github.achirdlabs.rift.json.JsonString;
import io.github.achirdlabs.rift.json.JsonValue;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * A structured proxy predicate generator under construction, for use with {@link
 * ProxySpec#generateBy(PredicateGeneratorSpec)}: which request fields to match on, plus the
 * {@code caseSensitive}/{@code jsonpath} knobs {@link ProxySpec#generateBy(RequestField...)} (the
 * plain-fields shortcut) does not expose.
 *
 * <p>Instances are immutable: every chain method returns a new {@code PredicateGeneratorSpec}.
 */
public final class PredicateGeneratorSpec {

    private final Set<RequestField> fields;
    private final Optional<Boolean> caseSensitive;
    private final Optional<String> jsonPathSelector;

    private PredicateGeneratorSpec(Set<RequestField> fields, Optional<Boolean> caseSensitive, Optional<String> jsonPathSelector) {
        this.fields = fields;
        this.caseSensitive = caseSensitive;
        this.jsonPathSelector = jsonPathSelector;
    }

    /** A fresh predicate generator matching no fields yet. */
    public static PredicateGeneratorSpec create() {
        return new PredicateGeneratorSpec(Set.of(), Optional.empty(), Optional.empty());
    }

    /** Adds the given fields to the set this generator matches on. Repeatable. */
    public PredicateGeneratorSpec matching(RequestField... more) {
        Set<RequestField> next = new LinkedHashSet<>(fields);
        next.addAll(Arrays.asList(more));
        return new PredicateGeneratorSpec(next, caseSensitive, jsonPathSelector);
    }

    /** Sets whether the generated predicate is case-sensitive. */
    public PredicateGeneratorSpec caseSensitive(boolean value) {
        return new PredicateGeneratorSpec(fields, Optional.of(value), jsonPathSelector);
    }

    /**
     * Records only the part of the request body {@code selector} selects, and scopes the recorded
     * predicate with the same selector, so a replay matches on that value alone. Requires rift
     * &ge; 0.22.0 (rift#1327): an older engine ignores {@code jsonpath} and records the whole body.
     */
    public PredicateGeneratorSpec jsonPath(String selector) {
        return new PredicateGeneratorSpec(fields, caseSensitive, Optional.of(selector));
    }

    JsonValue build() {
        JsonObject.Builder matches = JsonObject.builder();
        fields.forEach(f -> matches.put(f.wire(), JsonBool.TRUE));
        JsonObject.Builder builder = JsonObject.builder().put("matches", matches.build());
        caseSensitive.ifPresent(v -> builder.put("caseSensitive", JsonBool.of(v)));
        jsonPathSelector.ifPresent(v -> builder.put("jsonpath", JsonObject.builder().put("selector", new JsonString(v)).build()));
        return builder.build();
    }
}
