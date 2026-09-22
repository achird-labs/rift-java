package io.github.achirdlabs.rift.embedded;

import io.github.achirdlabs.rift.EmbeddedOptions;
import io.github.achirdlabs.rift.transport.EmbeddedEngine;
import io.github.achirdlabs.rift.transport.EmbeddedEngineProvider;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;

/** The {@link EmbeddedEngineProvider} discovered via {@code ServiceLoader} by {@code rift-java-core}. */
public final class EmbeddedProvider implements EmbeddedEngineProvider {

    private static final Logger LOG = System.getLogger(EmbeddedProvider.class.getName());

    @Override
    public boolean isAvailable() {
        // Non-extracting probe: checks env/property/classpath resolvability without writing a temp file.
        return NativeLibraryResolver.resolvable(EmbeddedOptions.builder().build());
    }

    @Override
    public EmbeddedEngine start(EmbeddedOptions options) {
        NativeLibraryResolver.ResolvedLibrary resolved = NativeLibraryResolver.resolve(options);
        EmbeddedTransport transport = EmbeddedTransport.open(resolved.path(), options);
        // Trust is applied when the admin plane is served, and an imposter keeps the outbound client
        // it was created with — so the plane must be up before the first imposter can exist.
        if (options.serveAdminEagerly() || options.upstreamTrust().isPresent()) {
            try {
                transport.adminUri();
            } catch (RuntimeException e) {
                // The engine handle and the mapped library are already live, and the onClose that
                // deletes an extracted temp library does not exist yet — so without this the caller
                // gets an exception and leaks both. Eager start exists to surface a bad adminPort or
                // adminHost here rather than at first use (#176), which makes this throw a normal
                // outcome rather than a remote one.
                closeQuietly(transport, resolved);
                throw e;
            }
        }
        Runnable onClose = resolved.temporary()
                ? () -> {
                    try {
                        Files.deleteIfExists(resolved.path());
                    } catch (IOException e) {
                        // Best-effort: the deleteOnExit hook registered at extraction is the fallback,
                        // but leave a trail so a stale temp lib isn't a silent mystery later.
                        LOG.log(Level.DEBUG, "failed to delete extracted native library " + resolved.path()
                                + "; relying on deleteOnExit", e);
                    }
                }
                : () -> { };
        return new EmbeddedEngine(transport, onClose);
    }

    /**
     * Releases the engine handle and any extracted library on a failed start. Suppresses its own
     * failures onto the original exception: that one explains why the engine did not start, and
     * losing it to a cleanup error would leave the caller with the less useful of the two.
     */
    private static void closeQuietly(EmbeddedTransport transport, NativeLibraryResolver.ResolvedLibrary resolved) {
        try {
            transport.close();
        } catch (RuntimeException closeFailure) {
            LOG.log(Level.DEBUG, "failed to close the embedded transport after a failed start", closeFailure);
        }
        if (resolved.temporary()) {
            try {
                Files.deleteIfExists(resolved.path());
            } catch (IOException e) {
                LOG.log(Level.DEBUG, "failed to delete extracted native library " + resolved.path()
                        + " after a failed start; relying on deleteOnExit", e);
            }
        }
    }
}
