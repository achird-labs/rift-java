package io.github.achirdlabs.rift.dsl;

/**
 * Where a request's instant comes from: {@code _rift.scriptEngine.clock} (rift &ge; 0.25.0).
 */
public enum ClockSource {

    /** The imposter's clock only (wall time unless frozen or stepped); {@code X-Rift-Now} is ignored. The engine's default. */
    WALL("wall"),

    /**
     * A request's {@code X-Rift-Now: <ms since epoch>} header is its own instant, ahead of the
     * imposter's clock, so parallel tests need no admin calls. A malformed header is refused with
     * {@code 400}.
     */
    HEADER("header");

    private final String wire;

    ClockSource(String wire) {
        this.wire = wire;
    }

    /** The wire string, e.g. {@code "header"}. */
    String wire() {
        return wire;
    }
}
