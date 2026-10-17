package io.github.achirdlabs.rift;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Immutable configuration for {@link Rift#spawn(SpawnOptions)}: which {@code rift} binary to run
 * (or how to resolve/download one), how to launch it, and how long to wait for it to become
 * healthy or to shut down.
 */
public final class SpawnOptions {

    /** The first engine whose CLI takes {@code --require-admin-auth} (rift#863). */
    private static final String REQUIRE_ADMIN_AUTH_SINCE = "0.17.0";

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
    private final Optional<String> apiKey;
    private final boolean requireAdminAuth;
    private final OptionalInt metricsPort;
    private final OptionalLong maxBodySize;
    private final OptionalLong bodyMemoryBudget;
    private final OptionalLong journalMaxBytes;

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
        this.apiKey = b.apiKey;
        this.requireAdminAuth = b.requireAdminAuth;
        this.metricsPort = b.metricsPort;
        this.maxBodySize = b.maxBodySize;
        this.bodyMemoryBudget = b.bodyMemoryBudget;
        this.journalMaxBytes = b.journalMaxBytes;
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

    /** The admin API key the engine requires and the client sends; see {@link Builder#apiKey(String)}. */
    public Optional<String> apiKey() {
        return apiKey;
    }

    /** Whether the engine refuses to expose an unauthenticated admin API off loopback; see {@link Builder#requireAdminAuth(boolean)}. */
    public boolean requireAdminAuth() {
        return requireAdminAuth;
    }

    /** The engine's metrics port, if set; see {@link Builder#metricsPort(int)}. */
    public OptionalInt metricsPort() {
        return metricsPort;
    }

    /** The largest body the engine accepts, if set; see {@link Builder#maxBodySize(long)}. */
    public OptionalLong maxBodySize() {
        return maxBodySize;
    }

    /** The memory buffered bodies may share, if set; see {@link Builder#bodyMemoryBudget(long)}. */
    public OptionalLong bodyMemoryBudget() {
        return bodyMemoryBudget;
    }

    /** Each imposter's recorded-request budget, if set; see {@link Builder#journalMaxBytes(long)}. */
    public OptionalLong journalMaxBytes() {
        return journalMaxBytes;
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
        private Optional<String> apiKey = Optional.empty();
        private boolean requireAdminAuth = false;
        private OptionalInt metricsPort = OptionalInt.empty();
        private OptionalLong maxBodySize = OptionalLong.empty();
        private OptionalLong bodyMemoryBudget = OptionalLong.empty();
        private OptionalLong journalMaxBytes = OptionalLong.empty();

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
         * The engine's {@code --host}, default {@code 127.0.0.1}: an IP literal, including IPv6
         * {@code ::1} bare or bracketed (a bare one needs rift &ge; 0.18.0). The admin API binds it,
         * and the admin URI is built from it, bracketed when it is IPv6. {@link #localOnly(boolean)}
         * is on by default and makes the engine bind loopback whatever this says, so turn it off for
         * this to take effect.
         *
         * <p>From rift 0.23.0 (rift#1517) an imposter created without its own {@code host} binds this
         * address too, as in Mountebank, so spawned imposters are loopback-only by default; before
         * it, a host-less imposter bound every interface. A system under test outside the loopback
         * interface (in a container, through {@code host.docker.internal} or the bridge gateway)
         * reaches an imposter that names its own interface, {@code imposter("x").host("0.0.0.0")}, on
         * an engine started with {@link #localOnly(boolean) localOnly(false)}: from 0.23.0 {@code
         * --local-only} also refuses non-loopback clients on imposters (rift#1523). Docker Desktop
         * forwards to loopback and is admitted either way; a Linux bridge is not.
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

        /**
         * The engine's {@code --local-only}, default on: it binds the admin API to loopback and, from
         * rift 0.23.0 (rift#1523), refuses non-loopback clients on every imposter port too. Turn it off
         * for a system under test that reaches the engine from off the loopback interface.
         */
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
         * Requires this key on every admin API request to the spawned engine (its {@code MB_APIKEY}),
         * and sends it from the returned client as the {@code Authorization} header. Unset by
         * default: no authentication. It reaches the engine through its environment rather than the
         * command line, which other users of the machine can read. An {@code MB_APIKEY} this JVM
         * inherited is not passed on, and one in {@link #env(Map)} is refused at {@link #build()}:
         * either would lock the engine against a client that does not know the key. The engine's
         * separate metrics listener ({@link #metricsPort(int)}) is not gated by it.
         *
         * @throws IllegalArgumentException if {@code apiKey} is blank — a misconfiguration, not a
         *                                  key; omit it to run unauthenticated
         */
        public Builder apiKey(String apiKey) {
            Objects.requireNonNull(apiKey, "apiKey");
            if (apiKey.isBlank()) {
                throw new IllegalArgumentException(
                        "apiKey must not be blank — blank is a misconfiguration, not a key; omit it to run unauthenticated");
            }
            this.apiKey = Optional.of(apiKey);
            return this;
        }

        /**
         * Makes the engine refuse to start when its admin API would be reachable off loopback
         * without a key ({@code --require-admin-auth}). With the defaults — {@link #localOnly(boolean)}
         * on, {@code 127.0.0.1} — it changes nothing; it matters once the admin API binds wider. Needs
         * a rift engine &ge; 0.17.0, checked against {@link #version(String)} at {@link #build()}.
         */
        public Builder requireAdminAuth(boolean requireAdminAuth) {
            this.requireAdminAuth = requireAdminAuth;
            return this;
        }

        /**
         * The port the engine's Prometheus metrics listener binds ({@code --metrics-port}). Unset, the
         * engine uses its own default, 9090 — so two spawned engines contend for it, and the one that
         * loses runs without metrics (the engine logs it and carries on). Set a distinct port per
         * engine you scrape. A port that is taken does not stop the engine either: it starts without
         * metrics and says so only in its log.
         *
         * @throws IllegalArgumentException if {@code metricsPort} is outside {@code 1..65535}: the
         *                                  port the engine bound is reported nowhere, so an
         *                                  OS-assigned one could not be found
         */
        public Builder metricsPort(int metricsPort) {
            if (metricsPort < 1 || metricsPort > 65_535) {
                throw new IllegalArgumentException("metricsPort must be in 1..65535, was " + metricsPort);
            }
            this.metricsPort = OptionalInt.of(metricsPort);
            return this;
        }

        /**
         * The largest request, proxied upstream response or admin body the engine accepts
         * ({@code --max-body-size}, in bytes); a larger one gets {@code 413} and the connection is
         * closed. Unset, the engine's own 512 MiB applies. A value above 1 GiB needs {@link
         * #bodyMemoryBudget(long)} too, since the engine refuses a ceiling above its budget. Needs a
         * rift engine &ge; 0.22.0, checked against {@link #version(String)} at {@link #build()}.
         *
         * @throws IllegalArgumentException if {@code bytes} is below 1
         */
        public Builder maxBodySize(long bytes) {
            this.maxBodySize = OptionalLong.of(BodyLimits.maxBodySize(bytes));
            return this;
        }

        /**
         * The memory every body buffered at once may share ({@code --body-memory-budget}, in bytes);
         * past it a request gets {@code 503} with {@code Retry-After: 1}. {@code 0} is no budget.
         * Unset, the engine's own 1 GiB applies. A non-zero budget must be at least {@link
         * #maxBodySize(long)}, or the engine's 512 MiB default when that is unset. Needs a rift
         * engine &ge; 0.22.0, checked as for {@code maxBodySize}.
         *
         * @throws IllegalArgumentException if {@code bytes} is negative
         */
        public Builder bodyMemoryBudget(long bytes) {
            this.bodyMemoryBudget = OptionalLong.of(BodyLimits.bodyMemoryBudget(bytes));
            return this;
        }

        /**
         * How many bytes of recorded requests each imposter keeps ({@code --journal-max-bytes}),
         * oldest evicted first. A request larger than the whole budget is recorded without its body:
         * see {@link RecordedRequest#bodyOmitted()}. Unset, the engine's own 768 MiB applies. Needs a
         * rift engine &ge; 0.22.0, checked as for {@link #maxBodySize(long)}.
         *
         * @throws IllegalArgumentException if {@code bytes} is below 1
         */
        public Builder journalMaxBytes(long bytes) {
            this.journalMaxBytes = OptionalLong.of(BodyLimits.journalMaxBytes(bytes));
            return this;
        }

        /**
         * @throws IllegalArgumentException if {@link #upstreamTrust(UpstreamTrust)} is an inline PEM,
         *                                  or is set for an engine {@link #version(String)} older
         *                                  than 0.18.0; or {@link #requireAdminAuth(boolean)} is on
         *                                  for one older than 0.17.0; or {@link #env(Map)} carries
         *                                  {@code MB_APIKEY}, which belongs in {@link #apiKey(String)};
         *                                  or a body or journal limit is set for an engine older
         *                                  than 0.22.0, or the budget does not cover the ceiling
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
            if (env.containsKey("MB_APIKEY")) {
                throw new IllegalArgumentException("env carries MB_APIKEY: set it with apiKey(...) instead, so the"
                        + " returned client sends it too");
            }
            if (requireAdminAuth && !EngineVersion.atLeast(version, REQUIRE_ADMIN_AUTH_SINCE)) {
                throw new IllegalArgumentException("requireAdminAuth needs a rift engine >= " + REQUIRE_ADMIN_AUTH_SINCE
                        + ", but version is " + version + " (the older CLI has no --require-admin-auth flag)");
            }
            requireBodyLimitFlag("maxBodySize", maxBodySize, "--max-body-size");
            requireBodyLimitFlag("bodyMemoryBudget", bodyMemoryBudget, "--body-memory-budget");
            requireBodyLimitFlag("journalMaxBytes", journalMaxBytes, "--journal-max-bytes");
            BodyLimits.requireBudgetCoversCeiling(maxBodySize, bodyMemoryBudget);
            return new SpawnOptions(this);
        }

        private void requireBodyLimitFlag(String option, OptionalLong value, String flag) {
            if (value.isPresent() && !EngineVersion.atLeast(version, BodyLimits.SINCE)) {
                throw new IllegalArgumentException(option + " needs a rift engine >= " + BodyLimits.SINCE
                        + ", but version is " + version + " (the older CLI has no " + flag + " flag)");
            }
        }
    }
}
