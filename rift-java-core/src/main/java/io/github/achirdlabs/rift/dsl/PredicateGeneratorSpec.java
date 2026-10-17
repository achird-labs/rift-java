package io.github.achirdlabs.rift.dsl;

import io.github.achirdlabs.rift.json.JsonArray;
import io.github.achirdlabs.rift.json.JsonBool;
import io.github.achirdlabs.rift.json.JsonObject;
import io.github.achirdlabs.rift.json.JsonString;
import io.github.achirdlabs.rift.json.JsonValue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A structured proxy predicate generator under construction, for use with {@link
 * ProxySpec#generateBy(PredicateGeneratorSpec)}: which request fields to match on, plus the knobs
 * {@link ProxySpec#generateBy(RequestField...)} (the plain-fields shortcut) does not expose.
 *
 * <p>{@code caseSensitive} and {@code matches} work on every engine. The rest need rift &ge; 0.22.0
 * (rift#1327, rift#1446): {@link #jsonPath}, {@link #xPath}, {@link #ignore}, {@link
 * #predicateOperator} and {@link #except}. An older engine ignores those keys and records a wider
 * predicate.
 *
 * <p>Instances are immutable: every chain method returns a new {@code PredicateGeneratorSpec}.
 */
public final class PredicateGeneratorSpec {

    private sealed interface Selector {
        record JsonPath(String selector) implements Selector {
        }

        record XPath(String selector, Map<String, String> ns) implements Selector {
        }
    }

    private final Set<RequestField> fields;
    private final Optional<Boolean> caseSensitive;
    private final Optional<Selector> selector;
    private final Map<RequestField, Set<String>> ignore;
    private final Optional<GeneratorOperator> operator;
    private final Optional<String> except;

    private PredicateGeneratorSpec(Set<RequestField> fields, Optional<Boolean> caseSensitive, Optional<Selector> selector,
            Map<RequestField, Set<String>> ignore, Optional<GeneratorOperator> operator, Optional<String> except) {
        this.fields = fields;
        this.caseSensitive = caseSensitive;
        this.selector = selector;
        this.ignore = ignore;
        this.operator = operator;
        this.except = except;
    }

    /** A fresh predicate generator matching no fields yet. */
    public static PredicateGeneratorSpec create() {
        return new PredicateGeneratorSpec(Set.of(), Optional.empty(), Optional.empty(), Map.of(), Optional.empty(), Optional.empty());
    }

    /** Adds the given fields to the set this generator matches on. Repeatable. */
    public PredicateGeneratorSpec matching(RequestField... more) {
        Set<RequestField> next = new LinkedHashSet<>(fields);
        next.addAll(Arrays.asList(more));
        return new PredicateGeneratorSpec(next, caseSensitive, selector, ignore, operator, except);
    }

    /** Sets whether the generated predicate is case-sensitive. */
    public PredicateGeneratorSpec caseSensitive(boolean value) {
        return new PredicateGeneratorSpec(fields, Optional.of(value), selector, ignore, operator, except);
    }

    /**
     * Records only the part of the request body {@code selector} selects, and scopes the recorded
     * predicate with the same selector, so a replay matches on that value alone. Replaces an {@link
     * #xPath} selector. Requires rift &ge; 0.22.0 (rift#1327): an older engine ignores {@code
     * jsonpath} and records the whole body.
     *
     * @throws IllegalArgumentException if {@code selector} is blank
     */
    public PredicateGeneratorSpec jsonPath(String selector) {
        return new PredicateGeneratorSpec(fields, caseSensitive, Optional.of(new Selector.JsonPath(requireSelector(selector))),
                ignore, operator, except);
    }

    /**
     * The XPath counterpart of {@link #jsonPath}: records only the part of an XML body {@code
     * selector} selects. Replaces a {@link #jsonPath} selector. Requires rift &ge; 0.22.0.
     *
     * @throws IllegalArgumentException if {@code selector} is blank
     */
    public PredicateGeneratorSpec xPath(String selector) {
        return xPath(selector, Map.of());
    }

    /**
     * {@link #xPath(String)} with the namespace prefixes {@code selector} uses, as Mountebank's
     * {@code ns} map (prefix to URI).
     *
     * @throws IllegalArgumentException if {@code selector} is blank
     */
    public PredicateGeneratorSpec xPath(String selector, Map<String, String> namespaces) {
        Objects.requireNonNull(namespaces, "namespaces");
        namespaces.forEach((prefix, uri) -> {
            Objects.requireNonNull(prefix, "namespace prefix");
            Objects.requireNonNull(uri, "namespace uri");
        });
        return new PredicateGeneratorSpec(fields, caseSensitive,
                Optional.of(new Selector.XPath(requireSelector(selector), new LinkedHashMap<>(namespaces))), ignore, operator, except);
    }

    /**
     * Leaves the named keys of {@code field} out of the recorded predicate, so requests that differ
     * only in them are one recording (rift &ge; 0.22.0, rift#1446). A whole field with keys left out
     * is recorded as {@code equals} over the kept keys rather than {@code deepEquals}. Repeated calls
     * for one field add to its names.
     *
     * <p>Header names are sent in the engine's Title-Case form ({@code x-request-id} becomes {@code
     * X-Request-Id}), because the engine stores request headers that way and compares the names
     * exactly.
     *
     * @throws IllegalArgumentException if {@code field} is not {@link RequestField#QUERY}, {@link
     *     RequestField#HEADERS} or {@link RequestField#FORM}, or if no name, or a blank one, is given
     */
    public PredicateGeneratorSpec ignore(RequestField field, String... names) {
        Objects.requireNonNull(field, "field");
        if (!field.keyed()) {
            throw new IllegalArgumentException("ignore applies to query, headers and form, not " + field.wire());
        }
        if (names.length == 0) {
            throw new IllegalArgumentException("ignore needs at least one " + field.wire() + " name");
        }
        Map<RequestField, Set<String>> next = new LinkedHashMap<>(ignore);
        Set<String> kept = new LinkedHashSet<>(next.getOrDefault(field, Set.of()));
        for (String name : names) {
            Objects.requireNonNull(name, "name");
            if (name.isBlank()) {
                throw new IllegalArgumentException("an ignored " + field.wire() + " name must not be blank");
            }
            kept.add(field == RequestField.HEADERS ? headerKey(name) : name);
        }
        next.put(field, kept);
        return new PredicateGeneratorSpec(fields, caseSensitive, selector, next, operator, except);
    }

    /**
     * Records each generated predicate with {@code op} instead of the engine's default (rift &ge;
     * 0.22.0).
     */
    public PredicateGeneratorSpec predicateOperator(GeneratorOperator op) {
        Objects.requireNonNull(op, "op");
        return new PredicateGeneratorSpec(fields, caseSensitive, selector, ignore, Optional.of(op), except);
    }

    /** Sets the {@code except} regex copied onto every generated predicate (rift &ge; 0.22.0). */
    public PredicateGeneratorSpec except(String regex) {
        Objects.requireNonNull(regex, "regex");
        return new PredicateGeneratorSpec(fields, caseSensitive, selector, ignore, operator, Optional.of(regex));
    }

    /** The key the engine's imposter listener stores a header under: lowercase, each {@code -}-segment capitalised. */
    static String headerKey(String name) {
        StringBuilder key = new StringBuilder(name.length());
        boolean segmentStart = true;
        for (char c : name.toLowerCase(Locale.ROOT).toCharArray()) {
            key.append(segmentStart ? Character.toUpperCase(c) : c);
            segmentStart = c == '-';
        }
        return key.toString();
    }

    private static String requireSelector(String selector) {
        Objects.requireNonNull(selector, "selector");
        if (selector.isBlank()) {
            throw new IllegalArgumentException("selector must not be blank");
        }
        return selector;
    }

    JsonValue build() {
        JsonObject.Builder matches = JsonObject.builder();
        fields.forEach(f -> matches.put(f.wire(), JsonBool.TRUE));
        JsonObject.Builder builder = JsonObject.builder().put("matches", matches.build());
        caseSensitive.ifPresent(v -> builder.put("caseSensitive", JsonBool.of(v)));
        operator.ifPresent(v -> builder.put("predicateOperator", new JsonString(v.wire())));
        except.ifPresent(v -> builder.put("except", new JsonString(v)));
        selector.ifPresent(s -> {
            if (s instanceof Selector.JsonPath j) {
                builder.put("jsonpath", JsonObject.builder().put("selector", new JsonString(j.selector())).build());
            } else if (s instanceof Selector.XPath x) {
                JsonObject.Builder xpath = JsonObject.builder().put("selector", new JsonString(x.selector()));
                if (!x.ns().isEmpty()) {
                    JsonObject.Builder ns = JsonObject.builder();
                    x.ns().forEach((prefix, uri) -> ns.put(prefix, new JsonString(uri)));
                    xpath.put("ns", ns.build());
                }
                builder.put("xpath", xpath.build());
            }
        });
        if (!ignore.isEmpty()) {
            JsonObject.Builder ignored = JsonObject.builder();
            ignore.forEach((field, names) -> ignored.put(field.wire(),
                    new JsonArray(names.stream().map(n -> (JsonValue) new JsonString(n)).toList())));
            builder.put("ignore", ignored.build());
        }
        return builder.build();
    }
}
