package io.github.achirdlabs.rift;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Which certificates the engine trusts when it dials a real origin over TLS — a {@code proxy}
 * stub's upstream, and the intercept listener's origin leg. By default that is the OS trust store;
 * this adds to it, or turns verification off. Set it on {@link EmbeddedOptions.Builder#upstreamTrust},
 * {@link SpawnOptions.Builder#upstreamTrust}, or {@code RiftContainer.withUpstreamTrust} in
 * rift-java-testcontainers; one engine has one policy.
 *
 * <p>Requires a rift engine &ge; 0.18.0. A {@link Rift#connect(java.net.URI) connected} engine is
 * configured by whoever started it ({@code --upstream-ca-file}), so there is nothing to set there.
 *
 * <p>Prefer a CA over {@code SSL_CERT_FILE}: the engine honours that variable, but it
 * <em>replaces</em> the trust store, so pointing it at a lone private CA silently drops every
 * public root. {@link CaFile} and {@link CaPem} append.
 */
public sealed interface UpstreamTrust permits UpstreamTrust.CaFile, UpstreamTrust.CaPem, UpstreamTrust.SkipVerify {

    /** The first rift engine release with outbound TLS trust options. */
    String MIN_ENGINE_VERSION = "0.18.0";

    /**
     * Whether an engine of {@code engineVersion} ({@code 0.18.0}, {@code v0.18.0}, {@code 0.19.0-rc.1})
     * takes an outbound trust policy, that is, is {@link #MIN_ENGINE_VERSION} or newer. For transports
     * that know the version before the engine starts (a spawn's declared version, a container's image
     * tag) and so can refuse up front.
     */
    static boolean supportedBy(String engineVersion) {
        return EngineVersion.atLeast(Objects.requireNonNull(engineVersion, "engineVersion"), MIN_ENGINE_VERSION);
    }

    /**
     * Extra CA certificate(s), read by the engine from a PEM file and appended to the OS trust store.
     * The file is read when the engine starts; an unreadable one fails the start.
     *
     * <p>A relative path is resolved against this JVM's working directory here, at construction — a
     * spawned engine may run in another directory ({@code SpawnOptions.workingDir}), and would
     * otherwise read a different file or none.
     */
    record CaFile(Path pem) implements UpstreamTrust {
        public CaFile {
            pem = Objects.requireNonNull(pem, "pem").toAbsolutePath();
        }
    }

    /**
     * The same anchor as {@link CaFile}, supplied inline. Embedded engines and containers only: the
     * {@code rift} CLI has no flag for an inline certificate, so {@link SpawnOptions} rejects this
     * variant (a container takes it because its transport writes the file into the container).
     */
    record CaPem(String pem) implements UpstreamTrust {
        public CaPem {
            Objects.requireNonNull(pem, "pem");
            if (!pem.contains("-----BEGIN CERTIFICATE-----")) {
                throw new IllegalArgumentException(
                        "upstream CA PEM must contain a -----BEGIN CERTIFICATE----- block");
            }
        }
    }

    /**
     * Accept any certificate. <b>Development only</b>: a recording proxy with verification off will
     * faithfully record a man-in-the-middle's traffic. The SDK logs a warning whenever it is used.
     * Prefer {@link CaFile}.
     */
    record SkipVerify() implements UpstreamTrust {
    }
}
