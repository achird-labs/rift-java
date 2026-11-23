package io.github.achirdlabs.rift.dsl;

/**
 * Which export of a wasm component answers a request: {@code _rift.script.export} (rift &ge; 0.25.0).
 * Unset, the engine uses {@code rift} when the component exports it, else {@code http}.
 */
public enum WasmExport {

    /** The {@code rift:mock/handler} interface: the component decides pass, delay, reset or a response. */
    RIFT("rift"),

    /**
     * A standard {@code wasi:http/incoming-handler}, as {@code componentize-js}, {@code componentize-py},
     * TinyGo and the Rust {@code wasip2} crate build: the component answers the whole response. Knobs
     * reach it as the {@code RIFT_KNOBS} environment variable.
     */
    HTTP("http");

    private final String wire;

    WasmExport(String wire) {
        this.wire = wire;
    }

    /** The wire string, e.g. {@code "http"}. */
    String wire() {
        return wire;
    }
}
