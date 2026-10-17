package io.github.achirdlabs.rift.model;

import io.github.achirdlabs.rift.json.JsonArray;
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
 * A stub: predicates to match a request, and the responses to serve (cycled) when it matches, plus
 * scenario-FSM and correlated-isolation ("space") extensions.
 *
 * <p>{@code name} is a label for the reader: the engine never matches on it, and from rift 0.22.0
 * keeps it and prints it back first (rift#1362). An older engine drops it.
 *
 * <p>{@code extra} carries any wire keys not modeled above, so unknown/future engine fields survive
 * a parse → serialize round-trip instead of being dropped (re-emitted after the modeled keys, in
 * insertion order). A modeled key appearing in {@code extra} is rejected at construction.
 */
public record Stub(
        Optional<String> name,
        Optional<String> scenarioName,
        Optional<String> requiredScenarioState,
        Optional<String> newScenarioState,
        Optional<String> space,
        Optional<String> id,
        Optional<String> routePattern,
        List<Predicate> predicates,
        List<Response> responses,
        Optional<String> recordedFrom,
        Optional<JsonValue> verify,
        Map<String, JsonValue> extra) {

    private static final Set<String> MODELED_KEYS = Set.of("name",
            "scenarioName", "requiredScenarioState", "newScenarioState", "space", "id", "routePattern",
            "predicates", "responses", "recordedFrom", "_verify");

    public Stub {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(scenarioName, "scenarioName");
        Objects.requireNonNull(requiredScenarioState, "requiredScenarioState");
        Objects.requireNonNull(newScenarioState, "newScenarioState");
        Objects.requireNonNull(space, "space");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(routePattern, "routePattern");
        predicates = List.copyOf(predicates);
        responses = List.copyOf(responses);
        Objects.requireNonNull(recordedFrom, "recordedFrom");
        Objects.requireNonNull(verify, "verify");
        Objects.requireNonNull(extra, "extra");
        JsonSupport.rejectModeledExtraKeys(extra, MODELED_KEYS, "stub");
        extra = JsonSupport.orderedCopy(extra);
    }

    /** An unnamed stub: the canonical constructor before {@code name} was modeled. */
    public Stub(Optional<String> scenarioName, Optional<String> requiredScenarioState,
            Optional<String> newScenarioState, Optional<String> space, Optional<String> id,
            Optional<String> routePattern, List<Predicate> predicates, List<Response> responses,
            Optional<String> recordedFrom, Optional<JsonValue> verify, Map<String, JsonValue> extra) {
        this(Optional.empty(), scenarioName, requiredScenarioState, newScenarioState, space, id, routePattern,
                predicates, responses, recordedFrom, verify, extra);
    }

    public Stub(List<Predicate> predicates, List<Response> responses) {
        this(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), predicates, responses, Optional.empty(), Optional.empty(), Map.of());
    }

    /** Parses a single stub JSON object. Throws a typed codec error on malformed input. */
    public static Stub fromJson(String json) {
        return read(JsonSupport.requireObject(JsonValue.parse(json), "stub"));
    }

    public String toJson() {
        return toJsonValue().toJson();
    }

    /** Returns a copy with {@code key}/{@code value} added to {@code extra}; rejects a modeled key. */
    public Stub withExtra(String extraKey, JsonValue value) {
        Objects.requireNonNull(extraKey, "extraKey");
        Objects.requireNonNull(value, "value");
        Map<String, JsonValue> next = new LinkedHashMap<>(extra);
        next.put(extraKey, value);
        return new Stub(name, scenarioName, requiredScenarioState, newScenarioState, space, id, routePattern,
                predicates, responses, recordedFrom, verify, next);
    }

    /** Returns a copy labelled {@code name}, which the engine prints back and never matches on. */
    public Stub withName(String name) {
        Objects.requireNonNull(name, "name");
        return new Stub(Optional.of(name), scenarioName, requiredScenarioState, newScenarioState, space, id,
                routePattern, predicates, responses, recordedFrom, verify, extra);
    }

    static Stub read(JsonObject obj) {
        return new Stub(
                JsonSupport.optString(obj, "name"),
                JsonSupport.optString(obj, "scenarioName"),
                JsonSupport.optString(obj, "requiredScenarioState"),
                JsonSupport.optString(obj, "newScenarioState"),
                JsonSupport.optString(obj, "space"),
                JsonSupport.optString(obj, "id"),
                JsonSupport.optString(obj, "routePattern"),
                JsonSupport.optArray(obj, "predicates", v -> Predicate.read(JsonSupport.requireObject(v, "predicates[]"))),
                JsonSupport.optArray(obj, "responses", v -> Response.read(JsonSupport.requireObject(v, "responses[]"))),
                JsonSupport.optString(obj, "recordedFrom"),
                Optional.ofNullable(obj.get("_verify")),
                JsonSupport.extraFields(obj, MODELED_KEYS));
    }

    JsonObject toJsonValue() {
        JsonObject.Builder builder = JsonObject.builder();
        name.ifPresent(v -> builder.put("name", new JsonString(v)));
        scenarioName.ifPresent(v -> builder.put("scenarioName", new JsonString(v)));
        requiredScenarioState.ifPresent(v -> builder.put("requiredScenarioState", new JsonString(v)));
        newScenarioState.ifPresent(v -> builder.put("newScenarioState", new JsonString(v)));
        space.ifPresent(v -> builder.put("space", new JsonString(v)));
        id.ifPresent(v -> builder.put("id", new JsonString(v)));
        routePattern.ifPresent(v -> builder.put("routePattern", new JsonString(v)));
        builder.put("predicates", new JsonArray(predicates.stream().map(Predicate::toJsonValue).toList()));
        builder.put("responses", new JsonArray(responses.stream().map(r -> (JsonValue) r.toJsonValue()).toList()));
        recordedFrom.ifPresent(v -> builder.put("recordedFrom", new JsonString(v)));
        verify.ifPresent(v -> builder.put("_verify", v));
        extra.forEach(builder::put);
        return builder.build();
    }
}
