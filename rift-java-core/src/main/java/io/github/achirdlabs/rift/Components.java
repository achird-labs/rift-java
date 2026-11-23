package io.github.achirdlabs.rift;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The engine's content-addressed registry of WebAssembly components (rift &ge; 0.25.0, {@code
 * /components}), obtained from {@link Rift#components()}. An uploaded component runs without a file on
 * the engine's host: a stub names it with {@code Script.component(info.reference())}. With {@code
 * --datadir} uploads persist; under {@code strictWasm} only listed hashes are admitted.
 *
 * <p>On an engine before 0.25.0, which has no registry, every method throws {@link
 * UnsupportedOperationException}. A component the engine refuses (not a component, an import Rift does
 * not link, no {@code rift:mock/handler} or {@code wasi:http/incoming-handler} export, not on the
 * {@code strictWasm} list) is an {@link io.github.achirdlabs.rift.error.InvalidDefinition}; one past 64
 * MiB, or a full registry, an {@link io.github.achirdlabs.rift.error.EngineError} with status 413 or 507.
 */
public interface Components {

    /**
     * Uploads a component ({@code POST /components}, {@code application/wasm}). Uploading bytes the
     * engine already holds is not an error: it answers the same component.
     */
    ComponentInfo upload(byte[] component);

    /**
     * Reads a {@code .wasm} file here, on the client, and {@link #upload(byte[]) uploads} it.
     *
     * @throws java.io.UncheckedIOException if the file cannot be read
     */
    ComponentInfo upload(Path wasmFile);

    /** Every component the engine holds ({@code GET /components}). */
    List<ComponentInfo> list();

    /**
     * One component, by its 64 hex digits with or without the {@code sha256:} prefix ({@code GET
     * /components/{sha256}}); empty when the engine does not hold it.
     */
    Optional<ComponentInfo> get(String sha256);

    /**
     * Removes a component ({@code DELETE /components/{sha256}}). Idempotent: one the engine does not hold
     * is already gone.
     *
     * @throws io.github.achirdlabs.rift.error.EngineError with status 409 while an imposter references it
     */
    void delete(String sha256);
}
