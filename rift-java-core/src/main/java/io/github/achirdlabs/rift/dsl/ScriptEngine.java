package io.github.achirdlabs.rift.dsl;

/**
 * The scripting engine an imposter's {@code _rift} block defaults to.
 *
 * <p>{@link #JAVASCRIPT} is sent as {@code "js"}, which the engine accepts as an alias of {@code "javascript"}.
 * {@link #WASM} needs rift &ge; 0.25.0 and an engine built with the {@code wasm} feature (the {@code rift}
 * binary and image are; the plain {@code librift_ffi} is not, its {@code -wasm} flavour is).
 */
public enum ScriptEngine {

    RHAI("rhai"),
    JAVASCRIPT("js"),
    WASM("wasm");

    private final String wire;

    ScriptEngine(String wire) {
        this.wire = wire;
    }

    /** The wire string this engine serializes as, e.g. {@code "rhai"}. */
    String wire() {
        return wire;
    }
}
