package io.github.achirdlabs.rift.dsl;

import io.github.achirdlabs.rift.json.JsonValue;
import io.github.achirdlabs.rift.model.RiftScriptConfig;

import java.util.Base64;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A {@code _rift} script source, for use with {@link RiftDsl#script(Script)}: inline Rhai or
 * JavaScript source, a file reference, a named reference resolved by the engine's configuration, or
 * a WebAssembly component (rift &ge; 0.25.0). Exactly one of code/file/ref/component is set, matching
 * {@link RiftScriptConfig}'s own contract.
 *
 * <p>A wasm script takes three more settings, each returning a new {@code Script}: {@link
 * #export(WasmExport)}, {@link #knobs(JsonValue)} and {@link #instance(WasmInstanceScope)}. They are
 * refused on a Rhai, JavaScript or named script, where the engine would refuse them.
 *
 * <p>Since rift 0.25.0 (rift#1756) a Rhai or JavaScript script reads the imposter's clock and seed:
 * JavaScript {@code Date.now()} and {@code new Date()}, Rhai {@code timestamp_header()} and the template
 * {@code now} ({@code {{NOW}}}, {@code {{DAYS±N}}}) follow a frozen or stepped clock
 * ({@code Imposter.clock()}) or a request's {@code X-Rift-Now} under {@link ClockSource#HEADER}, and
 * JavaScript {@code Math.random()} draws from the stream {@code ImposterSpec.seed(long)} seeds. On
 * wall time with no seed nothing changes.
 */
public final class Script {

    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");
    private static final String SHA256_PREFIX = "sha256:";

    private final RiftScriptConfig config;

    private Script(RiftScriptConfig config) {
        this.config = config;
    }

    /** An inline Rhai script. */
    public static Script rhai(String inlineCode) {
        return new Script(new RiftScriptConfig(Optional.of("rhai"), Optional.of(inlineCode), Optional.empty(), Optional.empty()));
    }

    /** An inline JavaScript script. */
    public static Script js(String inlineCode) {
        return new Script(new RiftScriptConfig(Optional.of("js"), Optional.of(inlineCode), Optional.empty(), Optional.empty()));
    }

    /** A Rhai script loaded from the given file path at engine start. */
    public static Script rhaiFile(String path) {
        return new Script(new RiftScriptConfig(Optional.of("rhai"), Optional.empty(), Optional.of(path), Optional.empty()));
    }

    /** A JavaScript script loaded from the given file path at engine start. */
    public static Script jsFile(String path) {
        return new Script(new RiftScriptConfig(Optional.of("js"), Optional.empty(), Optional.of(path), Optional.empty()));
    }

    /** A named script reference, resolved against scripts registered elsewhere in the engine's configuration. */
    public static Script ref(String name) {
        return new Script(new RiftScriptConfig(Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(name)));
    }

    /**
     * A WebAssembly component read from a {@code .wasm} file by the engine ({@code engine: "wasm"},
     * rift &ge; 0.25.0). The path resolves as any script file does: against the config file's directory
     * for {@code --configfile}, or against {@code --scripts-dir} for an imposter created through the
     * admin API (one outside it is refused). The engine stores the component's bytes in {@code code} as
     * base64, so the saved imposter replays without the file.
     */
    public static Script wasmFile(String path) {
        Objects.requireNonNull(path, "path");
        return new Script(new RiftScriptConfig(Optional.of("wasm"), Optional.empty(), Optional.of(path), Optional.empty()));
    }

    /**
     * A WebAssembly component given as its bytes ({@code engine: "wasm"} with the bytes in {@code
     * code} as base64, rift &ge; 0.25.0): the form the engine itself stores, so it needs no file on the
     * engine's host and no upload. For a component used by many imposters, {@link #component(String)}
     * sends it once.
     *
     * @throws IllegalArgumentException if {@code componentBytes} is empty
     */
    public static Script wasm(byte[] componentBytes) {
        Objects.requireNonNull(componentBytes, "componentBytes");
        if (componentBytes.length == 0) {
            throw new IllegalArgumentException("a wasm component cannot be empty");
        }
        String code = Base64.getEncoder().encodeToString(componentBytes);
        return new Script(new RiftScriptConfig(Optional.of("wasm"), Optional.of(code), Optional.empty(), Optional.empty()));
    }

    /**
     * A component uploaded to the engine's registry ({@code Rift.components().upload(...)}), named by
     * its SHA-256 ({@code component: "sha256:<hex>"}, rift &ge; 0.25.0). Accepts the 64 lowercase hex
     * digits with or without the {@code sha256:} prefix, so {@code ComponentInfo.sha256()} and {@code
     * ComponentInfo.reference()} both work. The engine resolves it when the stub is served: a stub whose
     * component the engine does not hold answers a script error.
     *
     * @throws IllegalArgumentException if {@code sha256} is not 64 lowercase hex digits after the
     *                                  optional prefix
     */
    public static Script component(String sha256) {
        Objects.requireNonNull(sha256, "sha256");
        String hex = sha256.startsWith(SHA256_PREFIX) ? sha256.substring(SHA256_PREFIX.length()) : sha256;
        if (!SHA256_HEX.matcher(hex).matches()) {
            throw new IllegalArgumentException("a component is named by 64 lowercase hex digits (optionally prefixed"
                    + " \"sha256:\"), got \"" + sha256 + "\"");
        }
        return new Script(new RiftScriptConfig(Optional.of("wasm"), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(SHA256_PREFIX + hex), Optional.empty(), Optional.empty(), Optional.empty()));
    }

    /**
     * Which export of this wasm component answers ({@code export}). Unset, the engine picks {@link
     * WasmExport#RIFT} when the component exports it, else {@link WasmExport#HTTP}.
     *
     * @throws IllegalStateException if this is not a wasm script
     */
    public Script export(WasmExport export) {
        Objects.requireNonNull(export, "export");
        RiftScriptConfig c = requireWasm("export");
        return new Script(new RiftScriptConfig(c.engine(), c.code(), c.file(), c.ref(), c.component(),
                Optional.of(export.wire()), c.knobs(), c.instance()));
    }

    /**
     * Any JSON this wasm component reads as configuration ({@code knobs}): passed as text to its
     * {@code init} before each call, or as the {@code RIFT_KNOBS} environment variable under {@link
     * WasmExport#HTTP}. Unset, the component sees {@code {}}. Under {@link WasmInstanceScope#IMPOSTER}
     * different knobs keep different instances.
     *
     * @throws IllegalStateException if this is not a wasm script
     */
    public Script knobs(JsonValue knobs) {
        Objects.requireNonNull(knobs, "knobs");
        RiftScriptConfig c = requireWasm("knobs");
        return new Script(new RiftScriptConfig(c.engine(), c.code(), c.file(), c.ref(), c.component(),
                c.export(), Optional.of(knobs), c.instance()));
    }

    /**
     * How long this wasm component's instance lives ({@code instance}); unset is {@link
     * WasmInstanceScope#REQUEST}, a fresh instance per call.
     *
     * @throws IllegalStateException if this is not a wasm script
     */
    public Script instance(WasmInstanceScope scope) {
        Objects.requireNonNull(scope, "scope");
        RiftScriptConfig c = requireWasm("instance");
        return new Script(new RiftScriptConfig(c.engine(), c.code(), c.file(), c.ref(), c.component(),
                c.export(), c.knobs(), Optional.of(scope.wire())));
    }

    private RiftScriptConfig requireWasm(String setting) {
        if (!config.engine().map(e -> e.toLowerCase(Locale.ROOT)).filter("wasm"::equals).isPresent()) {
            throw new IllegalStateException(setting + "(...) applies to a wasm script (Script.wasm, wasmFile or"
                    + " component); this one runs on " + config.engine().map(e -> "\"" + e + "\"")
                            .orElse("the engine its reference names"));
        }
        return config;
    }

    /** The underlying model configuration this script builds. */
    RiftScriptConfig toConfig() {
        return config;
    }
}
