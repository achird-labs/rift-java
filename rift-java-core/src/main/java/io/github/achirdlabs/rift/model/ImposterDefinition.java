package io.github.achirdlabs.rift.model;

import io.github.achirdlabs.rift.json.JsonArray;
import io.github.achirdlabs.rift.json.JsonBool;
import io.github.achirdlabs.rift.json.JsonNumber;
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
 * An imposter definition: the port it binds (or {@link Optional#empty()} for an OS-assigned port),
 * the protocol, its stubs, and Mountebank/Rift-extension configuration. This is the wire-model
 * value; the transport-bound live handle a client hands back is the separate {@code Imposter} type.
 *
 * <p>{@code allowCORS} is <em>written</em> with that exact wire key (not the {@code allowCors} the
 * engine's default {@code camelCase} rename would otherwise produce) because every corpus fixture
 * — and every admin-API caller in practice — writes {@code allowCORS}, which the engine accepts via
 * a deserialize-only {@code alias}. On <em>read</em> both spellings are accepted, since the engine's
 * own serializer emits {@code allowCors} in {@code GET /imposters} output; consuming that output
 * must not silently lose the flag.
 *
 * <p>{@code mutualAuth}, {@code rejectUnauthorized} and {@code ca} configure client-certificate
 * authentication on an {@code https} imposter (rift &ge; 0.18.0; older engines drop them and accept
 * every client). The model validates no combination of them, so it can read whatever an engine or a
 * fixture holds; rift refuses the combinations that cannot take effect when the imposter is created.
 * {@code ImposterSpec.requireClientCertificate} builds only the two it accepts.
 *
 * <p>{@code extra} carries any wire keys not modeled above, so unknown/future engine fields survive
 * a parse → serialize round-trip instead of being dropped. They are re-emitted after the modeled
 * keys, in insertion order. A modeled key appearing in {@code extra} is rejected at construction.
 */
public record ImposterDefinition(
        Optional<Integer> port,
        Optional<String> host,
        String protocol,
        Optional<String> cert,
        Optional<String> key,
        Optional<String> name,
        boolean recordRequests,
        boolean recordMatches,
        List<Stub> stubs,
        Optional<IsResponse> defaultResponse,
        Optional<String> defaultForward,
        boolean allowCors,
        boolean strictBehaviors,
        Optional<String> serviceName,
        Optional<JsonValue> serviceInfo,
        Optional<RiftConfig> rift,
        boolean mutualAuth,
        boolean rejectUnauthorized,
        Optional<CaCertificates> ca,
        Map<String, JsonValue> extra) {

    public static final String DEFAULT_PROTOCOL = "http";

    /** Wire keys owned by typed components; both {@code allowCORS} spellings are reserved. */
    private static final Set<String> MODELED_KEYS = Set.of(
            "port", "host", "protocol", "cert", "key", "name", "recordRequests", "recordMatches",
            "stubs", "defaultResponse", "defaultForward", "allowCORS", "allowCors", "strictBehaviors",
            "serviceName", "serviceInfo", "_rift", "mutualAuth", "rejectUnauthorized", "ca");

    public ImposterDefinition {
        Objects.requireNonNull(port, "port");
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(protocol, "protocol");
        Objects.requireNonNull(cert, "cert");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(name, "name");
        stubs = List.copyOf(stubs);
        Objects.requireNonNull(defaultResponse, "defaultResponse");
        Objects.requireNonNull(defaultForward, "defaultForward");
        Objects.requireNonNull(serviceName, "serviceName");
        Objects.requireNonNull(serviceInfo, "serviceInfo");
        Objects.requireNonNull(rift, "rift");
        Objects.requireNonNull(ca, "ca");
        Objects.requireNonNull(extra, "extra");
        JsonSupport.rejectModeledExtraKeys(extra, MODELED_KEYS, "imposter");
        extra = JsonSupport.orderedCopy(extra);
    }

    /** A definition with no client-certificate authentication — the shape before {@code mutualAuth} was modeled. */
    public ImposterDefinition(
            Optional<Integer> port,
            Optional<String> host,
            String protocol,
            Optional<String> cert,
            Optional<String> key,
            Optional<String> name,
            boolean recordRequests,
            boolean recordMatches,
            List<Stub> stubs,
            Optional<IsResponse> defaultResponse,
            Optional<String> defaultForward,
            boolean allowCors,
            boolean strictBehaviors,
            Optional<String> serviceName,
            Optional<JsonValue> serviceInfo,
            Optional<RiftConfig> rift,
            Map<String, JsonValue> extra) {
        this(port, host, protocol, cert, key, name, recordRequests, recordMatches, stubs, defaultResponse,
                defaultForward, allowCors, strictBehaviors, serviceName, serviceInfo, rift, false, false,
                Optional.empty(), extra);
    }

    public ImposterDefinition(Optional<Integer> port, String protocol, List<Stub> stubs) {
        this(port, Optional.empty(), protocol, Optional.empty(), Optional.empty(), Optional.empty(),
                false, false, stubs, Optional.empty(), Optional.empty(), false, false,
                Optional.empty(), Optional.empty(), Optional.empty(), false, false, Optional.empty(), Map.of());
    }

    /** Parses a single imposter JSON object. Throws a typed codec error on malformed input. */
    public static ImposterDefinition fromJson(String json) {
        return read(JsonSupport.requireObject(JsonValue.parse(json), "imposter"));
    }

    public String toJson() {
        return toJsonValue().toJson();
    }

    /** Returns a copy with {@code key}/{@code value} added to {@code extra}; rejects a modeled key. */
    public ImposterDefinition withExtra(String extraKey, JsonValue value) {
        Objects.requireNonNull(extraKey, "extraKey");
        Objects.requireNonNull(value, "value");
        Map<String, JsonValue> next = new LinkedHashMap<>(extra);
        next.put(extraKey, value);
        return new ImposterDefinition(port, host, protocol, cert, key, name, recordRequests,
                recordMatches, stubs, defaultResponse, defaultForward, allowCors, strictBehaviors,
                serviceName, serviceInfo, rift, mutualAuth, rejectUnauthorized, ca, next);
    }

    static ImposterDefinition read(JsonObject obj) {
        return new ImposterDefinition(
                JsonSupport.optIntBox(obj, "port"),
                JsonSupport.optString(obj, "host"),
                JsonSupport.optString(obj, "protocol").orElse(DEFAULT_PROTOCOL),
                JsonSupport.optString(obj, "cert"),
                JsonSupport.optString(obj, "key"),
                JsonSupport.optString(obj, "name"),
                JsonSupport.optBool(obj, "recordRequests", false),
                JsonSupport.optBool(obj, "recordMatches", false),
                JsonSupport.optArray(obj, "stubs", v -> Stub.read(JsonSupport.requireObject(v, "stubs[]"))),
                Optional.ofNullable(obj.get("defaultResponse")).map(v -> IsResponse.read(JsonSupport.requireObject(v, "defaultResponse"))),
                JsonSupport.optString(obj, "defaultForward"),
                readAllowCors(obj),
                JsonSupport.optBool(obj, "strictBehaviors", false),
                JsonSupport.optString(obj, "serviceName"),
                Optional.ofNullable(obj.get("serviceInfo")),
                Optional.ofNullable(obj.get("_rift")).map(v -> RiftConfig.read(JsonSupport.requireObject(v, "_rift"))),
                JsonSupport.optBool(obj, "mutualAuth", false),
                JsonSupport.optBool(obj, "rejectUnauthorized", false),
                Optional.ofNullable(obj.get("ca")).map(CaCertificates::read),
                JsonSupport.extraFields(obj, MODELED_KEYS));
    }

    /**
     * Accepts both {@code allowCORS} (the alias admin-API callers write) and {@code allowCors} (the
     * key the engine's serializer emits), so consuming {@code GET /imposters} output never drops it.
     */
    private static boolean readAllowCors(JsonObject obj) {
        if (obj.has("allowCORS")) {
            return JsonSupport.optBool(obj, "allowCORS", false);
        }
        return JsonSupport.optBool(obj, "allowCors", false);
    }

    JsonObject toJsonValue() {
        JsonObject.Builder builder = JsonObject.builder();
        port.ifPresent(v -> builder.put("port", JsonNumber.of(v)));
        host.ifPresent(v -> builder.put("host", new JsonString(v)));
        builder.put("protocol", new JsonString(protocol));
        cert.ifPresent(v -> builder.put("cert", new JsonString(v)));
        key.ifPresent(v -> builder.put("key", new JsonString(v)));
        name.ifPresent(v -> builder.put("name", new JsonString(v)));
        // Unlike the engine's own serde struct (which has no skip_serializing_if on these two and
        // so always emits them), every corpus fixture omits recordRequests/recordMatches entirely
        // when false — so this codec matches the fixtures rather than the engine's literal attrs,
        // for the same reason documented on allowCORS above.
        if (recordRequests) {
            builder.put("recordRequests", JsonBool.TRUE);
        }
        if (recordMatches) {
            builder.put("recordMatches", JsonBool.TRUE);
        }
        builder.put("stubs", new JsonArray(stubs.stream().map(s -> (JsonValue) s.toJsonValue()).toList()));
        // The engine deserializes defaultResponse into a plain IsResponse whose statusCode is a
        // u16 (no string coercion, unlike a stub response) and emits it as a number — so this
        // position must carry statusCode as a JSON number, or the admin API rejects the imposter.
        defaultResponse.ifPresent(v -> builder.put("defaultResponse", v.toJsonValue(true)));
        defaultForward.ifPresent(v -> builder.put("defaultForward", new JsonString(v)));
        if (allowCors) {
            builder.put("allowCORS", JsonBool.TRUE);
        }
        if (strictBehaviors) {
            builder.put("strictBehaviors", JsonBool.TRUE);
        }
        serviceName.ifPresent(v -> builder.put("serviceName", new JsonString(v)));
        serviceInfo.ifPresent(v -> builder.put("serviceInfo", v));
        rift.ifPresent(v -> builder.put("_rift", v.toJsonValue()));
        // Omitted when false or absent, as the engine does; combinations it refuses are written as read.
        if (mutualAuth) {
            builder.put("mutualAuth", JsonBool.TRUE);
        }
        if (rejectUnauthorized) {
            builder.put("rejectUnauthorized", JsonBool.TRUE);
        }
        ca.ifPresent(v -> builder.put("ca", v.toJsonValue()));
        extra.forEach(builder::put);
        return builder.build();
    }
}
