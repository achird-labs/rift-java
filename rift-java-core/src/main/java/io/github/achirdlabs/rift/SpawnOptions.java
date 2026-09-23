package io.github.achirdlabs.rift;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable configuration for {@link Rift#spawn(SpawnOptions)}: which {@code rift} binary to run
 * (or how to resolve/download one), how to launch it, and how long to wait for it to become
 * healthy or to shut down.
 */
public final class SpawnOptions {

    private final Optional<Path> binaryPath;
    private final String version;
    private final String host;
    private final int adminPort;
    private final boolean allowInjection;
    private final boolean localOnly;
    private final String logLevel;
    private final Map<String, String> env;
    private final Optional<Path> workingDir;
    private final Optional<URI> mirrorUrl;
    private final Duration startupTimeout;
    private final Duration shutdownTimeout;
    private final boolean inheritLog;
    private final Optional<UpstreamTrust> upstreamTrust;

    private SpawnOptions(Builder b) {
        this.binaryPath = b.binaryPath;
        this.version = b.version;
        this.host = b.host;
        this.adminPort = b.adminPort;
        this.allowInjection = b.allowInjection;
        this.localOnly = b.localOnly;
        this.logLevel = b.logLevel;
        this.env = Map.copyOf(b.env);
        this.workingDir = b.workingDir;
        this.mirrorUrl = b.mirrorUrl;
        this.startupTimeout = b.startupTimeout;
        this.shutdownTimeout = b.shutdownTimeout;
        this.inheritLog = b.inheritLog;
        this.upstreamTrust = b.upstreamTrust;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Optional<Path> binaryPath() {
        return binaryPath;
    }

    public String version() {
        return version;
    }

    public String host() {
        return host;
    }

    public int adminPort() {
        return adminPort;
    }

    public boolean allowInjection() {
        return allowInjection;
    }

    public boolean localOnly() {
        return localOnly;
    }

    public String logLevel() {
        return logLevel;
    }

    public Map<String, String> env() {
        return env;
    }

    public Optional<Path> workingDir() {
        return workingDir;
    }

    public Optional<URI> mirrorUrl() {
        return mirrorUrl;
    }

    public Duration startupTimeout() {
        return startupTimeout;
    }

    public Duration shutdownTimeout() {
        return shutdownTimeout;
    }

    public boolean inheritLog() {
        return inheritLog;
    }

    /** The engine's outbound TLS trust, if set; see {@link Builder#upstreamTrust(UpstreamTrust)}. */
    public Optional<UpstreamTrust> upstreamTrust() {
        return upstreamTrust;
    }

    public static final class Builder {

        private Optional<Path> binaryPath = Optional.empty();
        private String version = RiftVersion.engineVersion();
        private String host = "127.0.0.1";
        private int adminPort = 0;
        private boolean allowInjection = true;
        private boolean localOnly = true;
        private String logLevel = "info";
        private final Map<String, String> env = new LinkedHashMap<>();
        private Optional<Path> workingDir = Optional.empty();
        private Optional<URI> mirrorUrl = Optional.empty();
        private Duration startupTimeout = Duration.ofSeconds(15);
        private Duration shutdownTimeout = Duration.ofSeconds(5);
        private boolean inheritLog = false;
        private Optional<UpstreamTrust> upstreamTrust = Optional.empty();

        private Builder() {
        }

        public Builder binaryPath(Path binaryPath) {
            this.binaryPath = Optional.of(Objects.requireNonNull(binaryPath, "binaryPath"));
            return this;
        }

        /**
         * The rift engine version to spawn (resolve/download). Defaults to the version the SDK is
         * pinned to and tested against ({@link RiftVersion#engineVersion()}, from
         * {@code <rift.engine.version>}), not the compatibility floor — so {@code spawn()} runs the
         * same engine the rest of the SDK targets.
         */
        public Builder version(String version) {
            this.version = Objects.requireNonNull(version, "version");
            return this;
        }

        /**
         * The interface the engine's admin API binds ({@code --host}), default {@code 127.0.0.1}: an
         * IP literal, including IPv6 {@code ::1} bare or bracketed (a bare one needs rift &ge;
         * 0.18.0). The admin URI is built from it, bracketed when it is IPv6. {@link
         * #localOnly(boolean)} is on by default and makes the engine bind loopback whatever this
         * says, so turn it off for this to take effect.
         */
        public Builder host(String host) {
            this.host = Objects.requireNonNull(host, "host");
            return this;
        }

        public Builder adminPort(int adminPort) {
            this.adminPort = adminPort;
            return this;
        }

        public Builder allowInjection(boolean allowInjection) {
            this.allowInjection = allowInjection;
            return this;
        }

        public Builder localOnly(boolean localOnly) {
            this.localOnly = localOnly;
            return this;
        }

        public Builder logLevel(String logLevel) {
            this.logLevel = Objects.requireNonNull(logLevel, "logLevel");
            return this;
        }

        public Builder env(Map<String, String> env) {
            Objects.requireNonNull(env, "env");
            this.env.clear();
            this.env.putAll(env);
            return this;
        }

        public Builder workingDir(Path workingDir) {
            this.workingDir = Optional.of(Objects.requireNonNull(workingDir, "workingDir"));
            return this;
        }

        public Builder mirrorUrl(URI mirrorUrl) {
            this.mirrorUrl = Optional.of(Objects.requireNonNull(mirrorUrl, "mirrorUrl"));
            return this;
        }

        public Builder startupTimeout(Duration startupTimeout) {
            this.startupTimeout = Objects.requireNonNull(startupTimeout, "startupTimeout");
            return this;
        }

        public Builder shutdownTimeout(Duration shutdownTimeout) {
            this.shutdownTimeout = Objects.requireNonNull(shutdownTimeout, "shutdownTimeout");
            return this;
        }

        public Builder inheritLog(boolean inheritLog) {
            this.inheritLog = inheritLog;
            return this;
        }

        /**
         * What the engine trusts when a {@code proxy} stub (or the intercept listener) dials a real
         * origin over TLS — for recording an origin behind a private or corporate CA. Unset by
         * default: the OS trust store. {@link UpstreamTrust.CaFile} is passed as
         * {@code --upstream-ca-file}, {@link UpstreamTrust.SkipVerify} as
         * {@code --upstream-tls-skip-verify}. A later call replaces an earlier one.
         *
         * <p>Requires a rift engine &ge; 0.18.0, checked against {@link #version(String)} at
         * {@link #build()}. With {@link #binaryPath(Path)} that check is only as good as the
         * declared version; an older binary instead fails to start on the unknown flag.
         * {@link UpstreamTrust.CaPem} is rejected at build: the CLI has no inline form, so write the
         * PEM to a file and use {@link UpstreamTrust.CaFile}.
         */
        public Builder upstreamTrust(UpstreamTrust upstreamTrust) {
            this.upstreamTrust = Optional.of(Objects.requireNonNull(upstreamTrust, "upstreamTrust"));
            return this;
        }

        /**
         * @throws IllegalArgumentException if {@link #upstreamTrust(UpstreamTrust)} is an inline PEM,
         *                                  or is set for an engine {@link #version(String)} older
         *                                  than 0.18.0
         */
        public SpawnOptions build() {
            if (upstreamTrust.isPresent()) {
                if (upstreamTrust.get() instanceof UpstreamTrust.CaPem) {
                    throw new IllegalArgumentException("a spawned engine cannot take an inline CA PEM (the rift CLI "
                            + "has no flag for it); write it to a file and use UpstreamTrust.CaFile");
                }
                if (!UpstreamTrust.supportedBy(version)) {
                    throw new IllegalArgumentException("upstreamTrust needs a rift engine >= " + UpstreamTrust.MIN_ENGINE_VERSION
                            + ", but version is " + version + " (the older CLI has no --upstream-ca-file flag)");
                }
            }
            return new SpawnOptions(this);
        }
    }
}
