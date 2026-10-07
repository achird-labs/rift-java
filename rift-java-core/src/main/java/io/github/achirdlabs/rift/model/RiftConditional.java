package io.github.achirdlabs.rift.model;

import java.util.Objects;
import java.util.Optional;

/**
 * Declarative conditional GET on an {@code is} response, {@code _rift.conditional} (rift &ge; 0.20.0):
 * the engine adds an {@code ETag} and a {@code Last-Modified} to a 2xx answer to a {@code GET} or
 * {@code HEAD}, and answers a request that presents either back with a bodyless {@code 304}.
 *
 * <p>Mirrors the engine's wire form exactly, so a definition read back from the engine re-serializes
 * as it was written: {@code true}/{@code false}, or the validators spelled out with each field kept
 * absent when it was not given.
 */
public sealed interface RiftConditional {

    /**
     * {@code true} — an {@code ETag} and {@code Last-Modified: load} — or {@code false}, the off state.
     *
     * @param enabled whether conditional GET is on
     */
    record Enabled(boolean enabled) implements RiftConditional {
    }

    /**
     * The validators spelled out. An absent field takes the engine's default.
     *
     * @param etag         whether to send a strong {@code ETag} over the served bytes; absent means yes
     * @param lastModified {@code "load"} (when the stub was created or last changed) or a fixed HTTP-date
     *                     served verbatim; absent means {@code "load"}
     */
    record Validators(Optional<Boolean> etag, Optional<String> lastModified) implements RiftConditional {
        public Validators {
            Objects.requireNonNull(etag, "etag");
            Objects.requireNonNull(lastModified, "lastModified");
        }
    }
}
