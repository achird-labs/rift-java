package io.github.achirdlabs.rift;

import io.github.achirdlabs.rift.error.CommunicationError;
import io.github.achirdlabs.rift.json.JsonArray;
import io.github.achirdlabs.rift.json.JsonNumber;
import io.github.achirdlabs.rift.json.JsonObject;
import io.github.achirdlabs.rift.json.JsonString;
import io.github.achirdlabs.rift.json.JsonValue;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A WebAssembly component the engine holds in its registry (rift &ge; 0.25.0), as {@link Components}
 * reports it. A stub runs it with {@code Script.component(info.reference())}.
 *
 * @param sha256       the component's SHA-256, 64 lowercase hex digits: its name in the registry
 * @param size         its size in bytes
 * @param world        the WIT world it targets, e.g. {@code rift:mock/mock}
 * @param imports      the interfaces it imports
 * @param uploadedAt   when it was uploaded; empty when the engine does not say (an upload's own answer)
 * @param referencedBy the ports of the imposters whose stubs reference it; a referenced component
 *                     cannot be deleted
 */
public record ComponentInfo(String sha256, long size, String world, List<String> imports, Optional<Instant> uploadedAt,
        List<Integer> referencedBy) {

    public ComponentInfo {
        Objects.requireNonNull(sha256, "sha256");
        Objects.requireNonNull(world, "world");
        imports = List.copyOf(imports);
        Objects.requireNonNull(uploadedAt, "uploadedAt");
        referencedBy = List.copyOf(referencedBy);
    }

    /** The spelling a stub names it by: {@code "sha256:<hex>"}. */
    public String reference() {
        return "sha256:" + sha256;
    }

    static ComponentInfo read(JsonValue body) {
        if (!(body instanceof JsonObject o) || !(o.get("sha256") instanceof JsonString sha)
                || !(o.get("size") instanceof JsonNumber size)) {
            throw new CommunicationError("rift component answer is missing 'sha256' or 'size': " + body.toJson());
        }
        String world = o.get("world") instanceof JsonString w ? w.value() : "";
        Optional<Instant> uploadedAt;
        try {
            uploadedAt = o.get("uploadedAt") instanceof JsonString at ? Optional.of(Instant.parse(at.value())) : Optional.empty();
        } catch (DateTimeParseException e) {
            throw new CommunicationError("rift component has an unreadable 'uploadedAt': " + body.toJson(), e);
        }
        try {
            return new ComponentInfo(sha.value(), size.asLong(), world, strings(o.get("imports")), uploadedAt,
                    ports(o.get("referencedBy")));
        } catch (NumberFormatException e) {
            throw new CommunicationError("rift component has a non-integer size or port: " + body.toJson(), e);
        }
    }

    static List<ComponentInfo> readList(JsonValue body) {
        if (!(body instanceof JsonObject obj) || !(obj.get("components") instanceof JsonArray arr)) {
            throw new CommunicationError("rift GET /components answered without a 'components' array: " + body.toJson());
        }
        return arr.items().stream().map(ComponentInfo::read).toList();
    }

    private static List<String> strings(JsonValue v) {
        List<String> out = new ArrayList<>();
        if (v instanceof JsonArray arr) {
            for (JsonValue item : arr.items()) {
                if (item instanceof JsonString s) {
                    out.add(s.value());
                }
            }
        }
        return out;
    }

    private static List<Integer> ports(JsonValue v) {
        List<Integer> out = new ArrayList<>();
        if (v instanceof JsonArray arr) {
            for (JsonValue item : arr.items()) {
                if (item instanceof JsonNumber n) {
                    out.add(n.asInt());
                }
            }
        }
        return out;
    }
}
