package io.github.achirdlabs.rift.dsl;

/** How long a wasm component's instance lives: {@code _rift.script.instance} (rift &ge; 0.25.0). */
public enum WasmInstanceScope {

    /** A fresh instance for every call: the engine's default. */
    REQUEST("request"),

    /**
     * One instance kept on the imposter, keyed by the component, its export and its knobs, so the
     * guest's memory is its state across requests. It survives stub edits that keep it referenced; a
     * restart, a recreate or a trap drops it. Read it with {@code Imposter.wasmInstances()} and
     * {@code Imposter.wasmSnapshot()}.
     */
    IMPOSTER("imposter");

    private final String wire;

    WasmInstanceScope(String wire) {
        this.wire = wire;
    }

    /** The wire string, e.g. {@code "imposter"}. */
    String wire() {
        return wire;
    }
}
