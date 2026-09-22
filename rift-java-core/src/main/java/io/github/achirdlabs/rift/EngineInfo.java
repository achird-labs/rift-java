package io.github.achirdlabs.rift;

import io.github.achirdlabs.rift.error.CommunicationError;
import io.github.achirdlabs.rift.json.JsonArray;
import io.github.achirdlabs.rift.json.JsonObject;
import io.github.achirdlabs.rift.json.JsonString;
import io.github.achirdlabs.rift.json.JsonValue;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The running engine's {@code GET /config} response: version, commit, supported features, and the
 * {@code serveOptions} keys its embedded {@code rift_serve_admin} accepts. {@code serveOptions} is
 * how an embedder feature-detects a serve option: before rift 0.17.0 an unknown one is silently
 * ignored, and from 0.17.0 it is refused with an error that does not say what to do about it, so
 * presence in this list is the one signal that works on both. Empty before rift 0.17.0.
 */
public record EngineInfo(String version, String commit, Set<String> features, Set<String> serveOptions) {

    public EngineInfo {
        features = Set.copyOf(features);
        serveOptions = Set.copyOf(serveOptions);
    }

    /** An {@code EngineInfo} with no {@code serveOptions}, the shape before rift 0.17.0 advertised them. */
    public EngineInfo(String version, String commit, Set<String> features) {
        this(version, commit, features, Set.of());
    }

    /**
     * Reads an {@code EngineInfo}. A missing/mistyped {@code version} on a {@code GET /config} response
     * is a {@link CommunicationError} (aligning with the connect-time version preflight, which reads the
     * same endpoint); {@code commit}, {@code features} and {@code serveOptions} are advisory and default
     * when absent.
     */
    public static EngineInfo read(JsonValue value) {
        if (!(value instanceof JsonObject obj) || !(obj.get("version") instanceof JsonString versionStr)) {
            throw new CommunicationError("rift admin API GET /config response is missing a 'version' field");
        }
        String version = versionStr.value();
        String commit = stringOrEmpty(obj, "commit");
        return new EngineInfo(version, commit, strings(obj, "features"), strings(obj, "serveOptions"));
    }

    /** The string entries of an array field; absent, mistyped and non-string entries read as nothing. */
    private static Set<String> strings(JsonObject obj, String key) {
        Set<String> out = new LinkedHashSet<>();
        if (obj.get(key) instanceof JsonArray arr) {
            for (JsonValue v : arr.items()) {
                if (v instanceof JsonString s) {
                    out.add(s.value());
                }
            }
        }
        return out;
    }

    private static String stringOrEmpty(JsonObject obj, String key) {
        return obj.get(key) instanceof JsonString s ? s.value() : "";
    }
}
