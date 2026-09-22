package io.github.achirdlabs.rift.model;

import io.github.achirdlabs.rift.json.JsonArray;
import io.github.achirdlabs.rift.json.JsonString;
import io.github.achirdlabs.rift.json.JsonValue;

import java.util.List;

/**
 * An HTTPS imposter's {@code ca}: the PEM trust anchor(s) a client certificate must chain to. The
 * wire accepts a single string or an array; {@code singleForm} remembers which, so a definition
 * writes back in the spelling it was read in.
 *
 * @param pems       the PEM entries, in order
 * @param singleForm {@code true} for the bare-string spelling, which holds exactly one entry
 */
public record CaCertificates(List<String> pems, boolean singleForm) {

    public CaCertificates {
        pems = List.copyOf(pems);
        if (singleForm && pems.size() != 1) {
            throw new IllegalArgumentException("the single-string ca form holds exactly one PEM, got " + pems.size());
        }
    }

    static CaCertificates read(JsonValue value) {
        if (value instanceof JsonString single) {
            return new CaCertificates(List.of(single.value()), true);
        }
        JsonArray array = JsonSupport.requireArray(value, "ca");
        return new CaCertificates(array.items().stream().map(v -> JsonSupport.requireString(v, "ca[]")).toList(), false);
    }

    JsonValue toJsonValue() {
        if (singleForm) {
            return new JsonString(pems.get(0));
        }
        return new JsonArray(pems.stream().<JsonValue>map(JsonString::new).toList());
    }
}
