package io.github.achirdlabs.rift.dsl;

import io.github.achirdlabs.rift.json.JsonObject;
import io.github.achirdlabs.rift.json.JsonString;
import io.github.achirdlabs.rift.json.JsonValue;
import io.github.achirdlabs.rift.model.Using;

import java.util.Optional;

/**
 * An extraction method for a {@code copy}/{@code lookup} behavior entry: regex, JSONPath, or XPath
 * over the request, produced by {@link RiftDsl#regex(String)} and its siblings. Builds a {@link
 * Using} model value.
 */
public final class ExtractionSpec {

    private final String method;
    private final Optional<String> selector;
    private final Optional<JsonValue> options;
    private final Optional<JsonValue> ns;

    private ExtractionSpec(String method, Optional<String> selector, Optional<JsonValue> options, Optional<JsonValue> ns) {
        this.method = method;
        this.selector = selector;
        this.options = options;
        this.ns = ns;
    }

    static ExtractionSpec of(String method, String selector, Optional<JsonValue> options) {
        return new ExtractionSpec(method, Optional.of(selector), options, Optional.empty());
    }

    static ExtractionSpec xPath(String selector, JsonObject ns) {
        return new ExtractionSpec("xpath", Optional.of(selector), Optional.empty(), Optional.of(ns));
    }

    /** Builds the immutable {@link Using} this spec represents. */
    Using build() {
        return new Using(method, selector, options, ns);
    }

    /** This extraction's JSON shape, for embedding directly into a {@code lookup} behavior's raw JSON. */
    JsonObject toJsonValue() {
        JsonObject.Builder builder = JsonObject.builder().put("method", new JsonString(method));
        selector.ifPresent(v -> builder.put("selector", new JsonString(v)));
        options.ifPresent(v -> builder.put("options", v));
        ns.ifPresent(v -> builder.put("ns", v));
        return builder.build();
    }
}
