package io.github.achirdlabs.rift.dsl;

/**
 * A part of a proxied request a predicate generator can match on. {@link #REQUEST_FROM}, {@link
 * #IP} and {@link #FORM} need rift &ge; 0.22.0 (rift#1446); an older engine records nothing for them.
 */
public enum RequestField {

    METHOD("method"),
    PATH("path"),
    QUERY("query"),
    HEADERS("headers"),
    BODY("body"),
    /** The client's address and port, so in effect it matches only that one connection. */
    REQUEST_FROM("requestFrom"),
    /** The client's IP address. */
    IP("ip"),
    /** The urlencoded form body, recorded like {@link #QUERY}; nothing is recorded for a request with no form. */
    FORM("form");

    private final String wire;

    RequestField(String wire) {
        this.wire = wire;
    }

    /** Whether the field is a map of named values, the only kind a generator's {@code ignore} applies to. */
    boolean keyed() {
        return this == QUERY || this == HEADERS || this == FORM;
    }

    /** The wire key this field serializes as under {@code matches}. */
    String wire() {
        return wire;
    }
}
