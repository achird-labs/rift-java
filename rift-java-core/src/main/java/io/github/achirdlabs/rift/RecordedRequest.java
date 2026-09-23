package io.github.achirdlabs.rift;

import io.github.achirdlabs.rift.codec.BodyCodecs;
import io.github.achirdlabs.rift.codec.RiftBodyCodec;
import io.github.achirdlabs.rift.json.JsonArray;
import io.github.achirdlabs.rift.json.JsonNumber;
import io.github.achirdlabs.rift.json.JsonObject;
import io.github.achirdlabs.rift.json.JsonString;
import io.github.achirdlabs.rift.json.JsonValue;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * A single recorded request, as returned by an imposter's {@code savedRequests} endpoint (a space
 * reads the same journal, filtered to its flow). Parsed leniently via {@link #read(JsonValue)}: any field
 * the engine omits comes back empty rather than throwing, since the exact shape recorded requests
 * take can vary by protocol and engine version.
 */
public record RecordedRequest(
        String method,
        String path,
        Map<String, List<String>> query,
        Map<String, List<String>> headers,
        String body,
        Optional<Instant> timestamp,
        Optional<String> requestFrom,
        Optional<String> flowId,
        Map<String, String> pathParams,
        JsonValue raw) {

    public RecordedRequest {
        query = Map.copyOf(query);
        headers = Map.copyOf(headers);
        pathParams = Map.copyOf(pathParams);
    }

    /** Parses one {@code savedRequests} element. Never throws: unrecognized shapes come back mostly-empty. */
    public static RecordedRequest read(JsonValue value) {
        if (!(value instanceof JsonObject obj)) {
            return new RecordedRequest("", "", Map.of(), Map.of(), "", Optional.empty(), Optional.empty(),
                    Optional.empty(), Map.of(), value);
        }
        String method = stringField(obj, "method").orElse("");
        String path = stringField(obj, "path").orElse("");
        Map<String, List<String>> query = multiMapField(obj, "query");
        Map<String, List<String>> headers = multiMapField(obj, "headers");
        String body = bodyField(obj);
        Optional<Instant> timestamp = stringField(obj, "timestamp").flatMap(RecordedRequest::parseInstant);
        Optional<String> requestFrom = stringField(obj, "requestFrom");
        Optional<String> flowId = stringField(obj, "flowId");
        Map<String, String> pathParams = stringMapField(obj, "pathParams");
        return new RecordedRequest(method, path, query, headers, body, timestamp, requestFrom, flowId, pathParams, obj);
    }

    /**
     * The status the imposter answered this request with. See {@link #latencyMs()} for when it is
     * present; the two are present together or absent together.
     */
    public OptionalInt status() {
        if (raw instanceof JsonObject obj && obj.get("status") instanceof JsonNumber n) {
            try {
                int code = n.asInt();
                return code >= 0 && code <= 0xFFFF ? OptionalInt.of(code) : OptionalInt.empty();
            } catch (NumberFormatException e) {
                return OptionalInt.empty();
            }
        }
        return OptionalInt.empty();
    }

    /**
     * How long the imposter took to answer this request, in whole milliseconds: from the engine
     * receiving the request (including reading its body) to the response being ready, before it is
     * written to the socket. It includes any {@code wait} behavior, script or proxied upstream round
     * trip. A present {@code 0} is an ordinary sub-millisecond reading. A value the engine could not
     * have written (negative, or a status above 65535) reads as absent.
     *
     * <p>Requires rift &ge; 0.18.0; older engines never record it. Absent means not recorded, never
     * zero: the engine attaches the outcome once the response exists, so an entry read while its
     * request is still in flight has none yet. Request events on the event stream are pushed before
     * the request is answered and never carry it; re-read the journal instead.
     */
    public OptionalLong latencyMs() {
        if (raw instanceof JsonObject obj && obj.get("latencyMs") instanceof JsonNumber n) {
            try {
                long ms = n.asLong();
                return ms >= 0 ? OptionalLong.of(ms) : OptionalLong.empty();
            } catch (NumberFormatException e) {
                return OptionalLong.empty();
            }
        }
        return OptionalLong.empty();
    }

    /**
     * {@code METHOD path}, followed by {@code  → status in N ms} when the engine recorded the
     * outcome ({@code  → status} alone if only the status is present). An empty method prints as
     * {@code ?} and an empty path as {@code /}. The one-line form verify failures and
     * recorded-request dumps use.
     */
    public String summary() {
        String line = (method.isEmpty() ? "?" : method) + " " + (path.isEmpty() ? "/" : path);
        OptionalInt code = status();
        if (code.isEmpty()) {
            return line;
        }
        line += " → " + code.getAsInt();
        OptionalLong ms = latencyMs();
        return ms.isPresent() ? line + " in " + ms.getAsLong() + " ms" : line;
    }

    /** The first value of the named header, if present (case-sensitive, matching the wire key). */
    public Optional<String> header(String name) {
        List<String> values = headers.get(name);
        return values == null || values.isEmpty() ? Optional.empty() : Optional.of(values.get(0));
    }

    /** The body parsed as JSON, if it is non-blank and valid JSON. */
    public Optional<JsonValue> bodyAsJson() {
        if (body.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(JsonValue.parse(body));
        } catch (io.github.achirdlabs.rift.json.JsonParseException e) {
            return Optional.empty();
        }
    }

    /**
     * Decodes the request body as {@code type}, via the registered {@link RiftBodyCodec} (see
     * {@code RiftDsl.useBodyCodec}). The codec is resolved before parsing the body, so a missing
     * codec fails loudly with the artifact-naming message rather than after doing needless work.
     */
    public <T> T bodyAs(Class<T> type) {
        RiftBodyCodec codec = BodyCodecs.resolve();
        return codec.fromJson(bodyAsJson().orElseGet(() -> new JsonString(body)), type);
    }

    private static Optional<String> stringField(JsonObject obj, String key) {
        return obj.get(key) instanceof JsonString s ? Optional.of(s.value()) : Optional.empty();
    }

    private static String bodyField(JsonObject obj) {
        JsonValue v = obj.get("body");
        if (v instanceof JsonString s) {
            return s.value();
        }
        if (v != null) {
            return v.toJson();
        }
        return "";
    }

    private static Map<String, List<String>> multiMapField(JsonObject obj, String key) {
        if (!(obj.get(key) instanceof JsonObject fields)) {
            return Map.of();
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (var e : fields.fields().entrySet()) {
            out.put(e.getKey(), toStringList(e.getValue()));
        }
        return out;
    }

    private static Map<String, String> stringMapField(JsonObject obj, String key) {
        if (!(obj.get(key) instanceof JsonObject fields)) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (var e : fields.fields().entrySet()) {
            if (e.getValue() instanceof JsonString s) {
                out.put(e.getKey(), s.value());
            }
        }
        return out;
    }

    private static List<String> toStringList(JsonValue v) {
        if (v instanceof JsonString s) {
            return List.of(s.value());
        }
        if (v instanceof JsonArray arr) {
            List<String> out = new ArrayList<>();
            for (JsonValue item : arr.items()) {
                if (item instanceof JsonString s) {
                    out.add(s.value());
                }
            }
            return List.copyOf(out);
        }
        return List.of();
    }

    private static Optional<Instant> parseInstant(String text) {
        try {
            return Optional.of(Instant.parse(text));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
