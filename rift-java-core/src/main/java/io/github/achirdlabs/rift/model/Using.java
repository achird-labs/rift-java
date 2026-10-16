package io.github.achirdlabs.rift.model;

import io.github.achirdlabs.rift.json.JsonObject;
import io.github.achirdlabs.rift.json.JsonString;
import io.github.achirdlabs.rift.json.JsonValue;

import java.util.Optional;

/**
 * The extraction method for a {@code copy} behavior entry: regex, xpath, or jsonpath.
 *
 * <p>{@code ns} is Mountebank's XPath prefix-to-URI namespace map (an object of strings). rift
 * &ge; 0.22.0 binds it (rift#1326) and refuses it on a {@code regex} or {@code jsonpath}
 * {@code using}; an older engine ignores it, so a prefixed selector extracts nothing there.
 */
public record Using(String method, Optional<String> selector, Optional<JsonValue> options, Optional<JsonValue> ns) {

    public Using {
        java.util.Objects.requireNonNull(method, "method");
        java.util.Objects.requireNonNull(selector, "selector");
        java.util.Objects.requireNonNull(options, "options");
        java.util.Objects.requireNonNull(ns, "ns");
    }

    /** A {@code using} without a namespace map: the shape before {@code ns} was modeled. */
    public Using(String method, Optional<String> selector, Optional<JsonValue> options) {
        this(method, selector, options, Optional.empty());
    }

    static Using read(JsonObject obj) {
        return new Using(
                JsonSupport.requireString(obj, "method"),
                JsonSupport.optString(obj, "selector"),
                Optional.ofNullable(obj.get("options")),
                Optional.ofNullable(obj.get("ns")));
    }

    JsonObject toJsonValue() {
        JsonObject.Builder builder = JsonObject.builder().put("method", new JsonString(method));
        selector.ifPresent(v -> builder.put("selector", new JsonString(v)));
        options.ifPresent(v -> builder.put("options", v));
        ns.ifPresent(v -> builder.put("ns", v));
        return builder.build();
    }
}
