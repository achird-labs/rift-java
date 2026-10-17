package io.github.achirdlabs.rift.model;

import io.github.achirdlabs.rift.json.JsonArray;
import io.github.achirdlabs.rift.json.JsonNumber;
import io.github.achirdlabs.rift.json.JsonObject;
import io.github.achirdlabs.rift.json.JsonString;
import io.github.achirdlabs.rift.json.JsonValue;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A literal ("is") response: status, headers, body, and body encoding mode.
 *
 * <p>{@code statusCode} is kept as its canonical wire string (Mountebank always serializes it as
 * a string) even though a fixture may supply it as a JSON number — {@link #read} accepts both.
 *
 * <p>{@code headers} supports Mountebank's multi-value convention: a header may appear as a bare
 * string (one value) or an array (multiple values); a single value is always written back as a
 * bare string, never a one-element array, so re-serializing always converges on the same shape.
 *
 * <p>{@code extra} carries any wire keys not modeled above, so unknown/future engine fields on an
 * {@code is} object survive a parse → serialize round-trip instead of being dropped (re-emitted
 * after the modeled keys, in insertion order). A modeled key in {@code extra} is rejected at
 * construction.
 *
 * <p>{@code omitted} names the fields the author left unwritten. From rift 0.22.0 the engine fills
 * an unwritten {@code statusCode}, {@code headers} or {@code _mode} from the imposter's {@code
 * defaultResponse}, while a written {@code "headers": {}} or {@code "_mode": "text"} counts as a value
 * and keeps the default out (rift#1369). An omitted field still reads as the value the engine assumes
 * without a default ({@code "200"}, no headers, {@link ResponseMode#TEXT}), so the accessors stay
 * usable; {@link #isWritten} tells the two apart, and {@link #read} and the writer keep the
 * distinction across a round trip. An older engine treats an unwritten field as that same value.
 */
public record IsResponse(String statusCode, Map<String, List<String>> headers, Optional<JsonValue> body,
        ResponseMode mode, Map<String, JsonValue> extra, Set<IsResponse.Field> omitted) {

    /** A field an {@code is} response may leave unwritten for its imposter's {@code defaultResponse} to supply. */
    public enum Field { STATUS_CODE, HEADERS, MODE }

    public static final String DEFAULT_STATUS_CODE = "200";

    private static final Set<String> MODELED_KEYS = Set.of("statusCode", "headers", "body", "_mode");

    public IsResponse {
        Objects.requireNonNull(statusCode, "statusCode");
        headers = JsonSupport.orderedCopy(headers);
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(extra, "extra");
        JsonSupport.rejectModeledExtraKeys(extra, MODELED_KEYS, "is response");
        extra = JsonSupport.orderedCopy(extra);
        Objects.requireNonNull(omitted, "omitted");
        omitted = omitted.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(omitted));
        if (omitted.contains(Field.STATUS_CODE) && !statusCode.equals(DEFAULT_STATUS_CODE)) {
            throw new IllegalArgumentException("an omitted statusCode reads as " + DEFAULT_STATUS_CODE + ", not " + statusCode);
        }
        if (omitted.contains(Field.HEADERS) && !headers.isEmpty()) {
            throw new IllegalArgumentException("omitted headers must be empty, were " + headers);
        }
        if (omitted.contains(Field.MODE) && mode != ResponseMode.TEXT) {
            throw new IllegalArgumentException("an omitted _mode reads as text, not " + mode.wire());
        }
    }

    /**
     * A response that writes its status, and writes its headers and {@code _mode} only when they say
     * something (non-empty headers, a non-text mode): the shape before presence was modeled.
     */
    public IsResponse(String statusCode, Map<String, List<String>> headers, Optional<JsonValue> body,
            ResponseMode mode, Map<String, JsonValue> extra) {
        this(statusCode, headers, body, mode, extra, unsaid(headers, mode));
    }

    public IsResponse(String statusCode) {
        this(statusCode, Map.of(), Optional.empty(), ResponseMode.TEXT, Map.of());
    }

    public IsResponse(String statusCode, Map<String, List<String>> headers, Optional<JsonValue> body, ResponseMode mode) {
        this(statusCode, headers, body, mode, Map.of());
    }

    /** Returns a copy with {@code key}/{@code value} added to {@code extra}; rejects a modeled key. */
    public IsResponse withExtra(String extraKey, JsonValue value) {
        Objects.requireNonNull(extraKey, "extraKey");
        Objects.requireNonNull(value, "value");
        Map<String, JsonValue> next = new LinkedHashMap<>(extra);
        next.put(extraKey, value);
        return new IsResponse(statusCode, headers, body, mode, next, omitted);
    }

    /** Whether {@code field} is written, rather than left for the imposter's {@code defaultResponse}. */
    public boolean isWritten(Field field) {
        return !omitted.contains(field);
    }

    /**
     * Returns a copy that leaves {@code statusCode} unwritten, so the imposter's {@code
     * defaultResponse} supplies it (rift &gt;= 0.22.0; an older engine answers 200).
     *
     * @throws IllegalStateException if the status is not {@value #DEFAULT_STATUS_CODE}, the value an
     *     unwritten status reads as
     */
    public IsResponse withoutStatusCode() {
        if (!statusCode.equals(DEFAULT_STATUS_CODE)) {
            throw new IllegalStateException("only a " + DEFAULT_STATUS_CODE + " status can be left unwritten, was " + statusCode);
        }
        EnumSet<Field> next = omitted.isEmpty() ? EnumSet.noneOf(Field.class) : EnumSet.copyOf(omitted);
        next.add(Field.STATUS_CODE);
        return new IsResponse(statusCode, headers, body, mode, extra, next);
    }

    /** The fields the pre-presence writer left out: empty headers and the text mode. */
    static Set<Field> unsaid(Map<String, List<String>> headers, ResponseMode mode) {
        EnumSet<Field> unsaid = EnumSet.noneOf(Field.class);
        if (headers.isEmpty()) {
            unsaid.add(Field.HEADERS);
        }
        if (mode == ResponseMode.TEXT) {
            unsaid.add(Field.MODE);
        }
        return unsaid;
    }

    static IsResponse read(JsonObject obj) {
        EnumSet<Field> omitted = EnumSet.noneOf(Field.class);
        if (obj.get("statusCode") == null) {
            omitted.add(Field.STATUS_CODE);
        }
        if (obj.get("headers") == null) {
            omitted.add(Field.HEADERS);
        }
        if (obj.get("_mode") == null) {
            omitted.add(Field.MODE);
        }
        return new IsResponse(
                readStatusCode(obj.get("statusCode")),
                readHeaders(obj.get("headers")),
                Optional.ofNullable(obj.get("body")),
                Optional.ofNullable(obj.get("_mode"))
                        .map(v -> ResponseMode.read(JsonSupport.requireString(v, "'_mode'")))
                        .orElse(ResponseMode.TEXT),
                JsonSupport.extraFields(obj, MODELED_KEYS),
                omitted);
    }

    static String readStatusCode(JsonValue v) {
        if (v == null) {
            return DEFAULT_STATUS_CODE;
        }
        if (v instanceof JsonString s) {
            return s.value();
        }
        if (v instanceof JsonNumber n) {
            return n.raw();
        }
        throw new WireFormatException("'statusCode': expected a number or string, got " + JsonSupport.typeName(v));
    }

    static Map<String, List<String>> readHeaders(JsonValue v) {
        if (v == null) {
            return Map.of();
        }
        JsonObject obj = JsonSupport.requireObject(v, "headers");
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (var e : obj.fields().entrySet()) {
            out.put(e.getKey(), readHeaderValues(e.getValue(), e.getKey()));
        }
        return out;
    }

    private static List<String> readHeaderValues(JsonValue v, String key) {
        if (v instanceof JsonString s) {
            return List.of(s.value());
        }
        if (v instanceof JsonArray arr) {
            return arr.items().stream()
                    .map(el -> {
                        if (el instanceof JsonString s) {
                            return s.value();
                        }
                        throw new WireFormatException("'headers." + key + "': array elements must be strings");
                    })
                    .toList();
        }
        throw new WireFormatException("'headers." + key + "': expected a string or array of strings");
    }

    JsonObject toJsonValue() {
        return toJsonValue(false);
    }

    /**
     * Writes this response, writing each of {@code statusCode}, {@code headers} and {@code _mode} only
     * if it {@link #isWritten is written}. A stub response serializes {@code statusCode} as a string
     * (Mountebank convention); an imposter's {@code defaultResponse} serializes it as a JSON number,
     * because the engine deserializes that position into a {@code u16} — hence the {@code
     * statusCodeAsNumber}. That position also never writes empty headers, as the engine prints it:
     * an empty map supplies nothing as a default.
     */
    JsonObject toJsonValue(boolean statusCodeAsNumber) {
        JsonObject.Builder builder = JsonObject.builder();
        if (isWritten(Field.STATUS_CODE)) {
            builder.put("statusCode", statusCodeAsNumber ? statusCodeAsNumber() : new JsonString(statusCode));
        }
        if (isWritten(Field.HEADERS) && !(statusCodeAsNumber && headers.isEmpty())) {
            builder.put("headers", writeHeaders(headers));
        }
        body.ifPresent(b -> builder.put("body", b));
        if (isWritten(Field.MODE)) {
            builder.put("_mode", new JsonString(mode.wire()));
        }
        extra.forEach(builder::put);
        return builder.build();
    }

    /** The status as a JSON number for the defaultResponse position; falls back to a string only if
     * the canonical status text is somehow non-integer (never true for a well-formed status). */
    private JsonValue statusCodeAsNumber() {
        try {
            return JsonNumber.of(Integer.parseInt(statusCode));
        } catch (NumberFormatException e) {
            return new JsonString(statusCode);
        }
    }

    static JsonObject writeHeaders(Map<String, List<String>> headers) {
        JsonObject.Builder builder = JsonObject.builder();
        headers.forEach((key, values) -> {
            if (values.isEmpty()) {
                return; // a header with no values would emit no header line; omit it
            }
            if (values.size() == 1) {
                builder.put(key, new JsonString(values.get(0)));
            } else {
                builder.put(key, new JsonArray(values.stream().map(v -> (JsonValue) new JsonString(v)).toList()));
            }
        });
        return builder.build();
    }
}
