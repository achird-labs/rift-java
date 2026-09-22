package io.github.achirdlabs.rift.dsl;

/**
 * The scripting engine an imposter's {@code _rift} block defaults to.
 *
 * <p>{@link #JAVASCRIPT} is sent as {@code "js"}, which the engine accepts as an alias of {@code "javascript"}.
 */
public enum ScriptEngine {

    RHAI("rhai"),
    JAVASCRIPT("js");

    private final String wire;

    ScriptEngine(String wire) {
        this.wire = wire;
    }

    /** The wire string this engine serializes as, e.g. {@code "rhai"}. */
    String wire() {
        return wire;
    }
}
