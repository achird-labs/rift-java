package io.github.achirdlabs.rift.dsl;

import io.github.achirdlabs.rift.model.Predicate;
import io.github.achirdlabs.rift.model.PredicateOperation;
import io.github.achirdlabs.rift.model.PredicateParameters;
import io.github.achirdlabs.rift.model.PredicateSelector;

import java.util.Map;
import java.util.Optional;

/**
 * A single whole predicate under construction: an operation (built by binding a {@link Matcher} to
 * a field, joining several such with {@link RiftDsl#merge}, or a combinator such as {@link
 * RiftDsl#and(PredicateSpec...)}) plus the matcher parameters ({@code caseSensitive}, {@code
 * keyCaseSensitive}, {@code except}) and structured selector ({@code jsonPath}/{@code xPath}) that
 * ride alongside it on the wire.
 *
 * <p>Instances are immutable: every chain method returns a new {@code PredicateSpec}. The terminal
 * {@link #build()} produces the {@link Predicate} model value.
 */
public final class PredicateSpec {

    private final PredicateOperation operation;
    private final Optional<Boolean> caseSensitive;
    private final Optional<Boolean> keyCaseSensitive;
    private final String except;
    private final Optional<PredicateSelector> selector;

    private PredicateSpec(
            PredicateOperation operation,
            Optional<Boolean> caseSensitive,
            Optional<Boolean> keyCaseSensitive,
            String except,
            Optional<PredicateSelector> selector) {
        this.operation = operation;
        this.caseSensitive = caseSensitive;
        this.keyCaseSensitive = keyCaseSensitive;
        this.except = except;
        this.selector = selector;
    }

    /** Wraps a bare operation with no parameters or selector yet set. */
    static PredicateSpec of(PredicateOperation operation) {
        return new PredicateSpec(operation, Optional.empty(), Optional.empty(), "", Optional.empty());
    }

    /** The operation, for {@link RiftDsl#merge}. */
    PredicateOperation operation() {
        return operation;
    }

    /** Whether any matcher parameter or selector is set, which {@link RiftDsl#merge} refuses on a part. */
    boolean hasParametersOrSelector() {
        return caseSensitive.isPresent() || keyCaseSensitive.isPresent() || !except.isEmpty() || selector.isPresent();
    }

    /** Sets the {@code caseSensitive} matcher parameter. */
    public PredicateSpec caseSensitive(boolean value) {
        return new PredicateSpec(operation, Optional.of(value), keyCaseSensitive, except, selector);
    }

    /** Sets the {@code keyCaseSensitive} matcher parameter (relevant to header/query key matching). */
    public PredicateSpec keyCaseSensitive(boolean value) {
        return new PredicateSpec(operation, caseSensitive, Optional.of(value), except, selector);
    }

    /** Sets the {@code except} regex: characters matching it are stripped before the comparison runs. */
    public PredicateSpec except(String regex) {
        return new PredicateSpec(operation, caseSensitive, keyCaseSensitive, regex, selector);
    }

    /** Applies a JSONPath selector to the request body before this predicate is evaluated. */
    public PredicateSpec jsonPath(String selector) {
        return new PredicateSpec(
                operation, caseSensitive, keyCaseSensitive, except, Optional.of(new PredicateSelector.JsonPath(selector)));
    }

    /** Applies an XPath selector (with no namespace bindings) to the request body. */
    public PredicateSpec xPath(String selector) {
        return xPath(selector, Optional.empty());
    }

    /** Applies an XPath selector, with the given prefix-to-URI namespace bindings, to the request body. */
    public PredicateSpec xPath(String selector, Map<String, String> namespaces) {
        return xPath(selector, Optional.of(namespaces));
    }

    private PredicateSpec xPath(String selector, Optional<Map<String, String>> namespaces) {
        return new PredicateSpec(
                operation, caseSensitive, keyCaseSensitive, except,
                Optional.of(new PredicateSelector.XPath(selector, namespaces)));
    }

    /** Builds the immutable {@link Predicate} this spec represents. */
    public Predicate build() {
        return new Predicate(new PredicateParameters(caseSensitive, keyCaseSensitive, except, selector), operation);
    }
}
