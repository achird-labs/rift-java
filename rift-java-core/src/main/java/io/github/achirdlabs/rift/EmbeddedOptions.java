package io.github.achirdlabs.rift;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Immutable configuration for {@link Rift#embedded(EmbeddedOptions)}: where to find the
 * {@code librift_ffi} native library, and how the in-process engine's admin surface — used for the
 * operations with no direct C-ABI entry point, and for imposters' own reported {@code uri()} — is
 * both configured and addressed.
 *
 * <p>{@link Builder#adminHost}, {@link Builder#adminPort} and {@link Builder#apiKey} are handed to
 * the engine when that admin server starts, so they govern the real listener rather than only how
 * this client talks to it (#176).
 */
public final class EmbeddedOptions {

    private final Optional<Path> libraryPath;
    private final VersionCheck versionCheck;
    private final boolean serveAdminEagerly;
    private final String adminHost;
    private final int adminPort;
    private final Optional<String> apiKey;
    private final Optional<UpstreamTrust> upstreamTrust;
    private final Optional<Boolean> allowInjection;
    private final Optional<Boolean> requireAdminAuth;
    private final OptionalInt metricsPort;

    private EmbeddedOptions(
            Optional<Path> libraryPath,
            VersionCheck versionCheck,
            boolean serveAdminEagerly,
            String adminHost,
            int adminPort,
            Optional<String> apiKey,
            Optional<UpstreamTrust> upstreamTrust,
            Optional<Boolean> allowInjection,
            Optional<Boolean> requireAdminAuth,
            OptionalInt metricsPort) {
        this.libraryPath = libraryPath;
        this.versionCheck = versionCheck;
        this.serveAdminEagerly = serveAdminEagerly;
        this.adminHost = adminHost;
        this.adminPort = adminPort;
        this.apiKey = apiKey;
        this.upstreamTrust = upstreamTrust;
        this.allowInjection = allowInjection;
        this.requireAdminAuth = requireAdminAuth;
        this.metricsPort = metricsPort;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** An explicit path to the {@code librift_ffi} native library, bypassing classpath/env/property resolution. */
    public Optional<Path> libraryPath() {
        return libraryPath;
    }

    public VersionCheck versionCheck() {
        return versionCheck;
    }

    /** Whether the in-process admin server should be started immediately rather than on first need. */
    public boolean serveAdminEagerly() {
        return serveAdminEagerly;
    }

    public String adminHost() {
        return adminHost;
    }

    public int adminPort() {
        return adminPort;
    }

    public Optional<String> apiKey() {
        return apiKey;
    }

    /** The engine's outbound TLS trust, if set; see {@link Builder#upstreamTrust(UpstreamTrust)}. */
    public Optional<UpstreamTrust> upstreamTrust() {
        return upstreamTrust;
    }

    /** Whether the admin plane accepts script-bearing stubs, if set; see {@link Builder#allowInjection(boolean)}. */
    public Optional<Boolean> allowInjection() {
        return allowInjection;
    }

    /** Whether the engine refuses an unauthenticated off-loopback admin plane, if set; see {@link Builder#requireAdminAuth(boolean)}. */
    public Optional<Boolean> requireAdminAuth() {
        return requireAdminAuth;
    }

    /** The engine's metrics port, if set; see {@link Builder#metricsPort(int)}. */
    public OptionalInt metricsPort() {
        return metricsPort;
    }

    public static final class Builder {

        private Optional<Path> libraryPath = Optional.empty();
        private VersionCheck versionCheck = VersionCheck.resolveDefault();
        private boolean serveAdminEagerly = false;
        private String adminHost = "127.0.0.1";
        private int adminPort = 0;
        private Optional<String> apiKey = Optional.empty();
        private Optional<UpstreamTrust> upstreamTrust = Optional.empty();
        private Optional<Boolean> allowInjection = Optional.empty();
        private Optional<Boolean> requireAdminAuth = Optional.empty();
        private OptionalInt metricsPort = OptionalInt.empty();

        private Builder() {
        }

        public Builder libraryPath(Path libraryPath) {
            this.libraryPath = Optional.of(Objects.requireNonNull(libraryPath, "libraryPath"));
            return this;
        }

        public Builder versionCheck(VersionCheck versionCheck) {
            this.versionCheck = Objects.requireNonNull(versionCheck, "versionCheck");
            return this;
        }

        public Builder serveAdminEagerly(boolean serveAdminEagerly) {
            this.serveAdminEagerly = serveAdminEagerly;
            return this;
        }

        /**
         * The interface the in-process admin server binds, default {@code 127.0.0.1} — an IP
         * literal such as {@code 127.0.0.1}, {@code 0.0.0.0}, or IPv6 {@code ::1} (bare or
         * bracketed; a bare one needs rift &ge; 0.18.0), <b>not</b> a hostname: the engine parses it
         * as an IP address, so {@code "localhost"} is a bind error rather than loopback. It is also
         * the host imposters report in their own {@code uri()}, bracketed when it is IPv6.
         *
         * <p>Loopback is the default deliberately: this server exposes the full admin API of an
         * engine running inside your own process. Binding it wider is honoured, but pair it with
         * {@link #apiKey(String)} — otherwise anything that can reach the interface can drive the
         * engine.
         */
        public Builder adminHost(String adminHost) {
            this.adminHost = Objects.requireNonNull(adminHost, "adminHost");
            return this;
        }

        /**
         * The port the in-process admin server binds, default {@code 0} — meaning the OS assigns a
         * free one, which is what {@link Rift#adminUri()} then reports back.
         *
         * <p>Pin it only when something outside this process must find the admin API at a known
         * address. A pinned port that is already taken fails when the server starts, which by
         * default is the first call that needs it rather than at startup — pair it with
         * {@link #serveAdminEagerly(boolean)} to surface that at construction instead.
         *
         * @throws IllegalArgumentException if {@code adminPort} is outside {@code 0..65535} — the
         *                                  engine's field is a {@code u16}, so an out-of-range value
         *                                  is otherwise a parse error raised far from here, at
         *                                  whichever call first starts the admin plane
         */
        public Builder adminPort(int adminPort) {
            if (adminPort < 0 || adminPort > 65535) {
                throw new IllegalArgumentException("adminPort must be in 0..65535, was " + adminPort);
            }
            this.adminPort = adminPort;
            return this;
        }

        /**
         * Requires this key on every request to the in-process admin server, sent as the
         * {@code Authorization} header; unset by default, meaning no authentication.
         *
         * <p>Worth setting when {@link #adminHost(String)} is not loopback. This SDK's own delegated
         * calls authenticate themselves, so setting it costs nothing here.
         *
         * <p>A blank key is <b>rejected</b>, not treated as "unset" — it is what a
         * {@code getProperty("rift.apiKey", "")} style default produces, and that is not the same
         * thing as asking to run unauthenticated. Historically it was worse than unset: the engine
         * gated on the key being <em>present</em> and then compared it to the request's
         * {@code Authorization} header defaulted to the empty string, so a blank key switched
         * authentication on and then matched every unauthenticated caller — failing open on a plane
         * the caller believed was locked. Omit the key to run unauthenticated deliberately.
         *
         * <p>Engine 0.17.0 (achird-labs/rift#844) closed that hole at the C-ABI boundary:
         * {@code rift_serve_admin} now fails rather than enabling a gate that authenticates
         * everyone. This builder check is kept anyway (achird-labs/rift-java#182) because it
         * rejects here, at the configuring call, whereas the engine's rejection surfaces as an
         * {@link io.github.achirdlabs.rift.error.EngineError} from whichever later call first
         * starts the admin plane — far from the configuration that caused it.
         *
         * @throws IllegalArgumentException if {@code apiKey} is blank
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
         * What the engine trusts when a {@code proxy} stub (or the intercept listener) dials a real
         * origin over TLS — for recording an origin behind a private or corporate CA. Unset by
         * default: the OS trust store. A later call replaces an earlier one.
         *
         * <p>Requires a rift engine &ge; 0.18.0: the engine must advertise the option in its
         * {@code serveOptions}, or starting fails with {@link
         * io.github.achirdlabs.rift.error.EngineUnavailable} — an engine too old to know the option
         * would otherwise ignore it without a word.
         *
         * <p>Setting it starts the in-process admin server when the engine starts, as {@link
         * #serveAdminEagerly(boolean)} does: the engine applies the policy there, and an imposter
         * keeps the outbound client it was created with, so one created before the policy was
         * applied would never see it.
         */
        public Builder upstreamTrust(UpstreamTrust upstreamTrust) {
            this.upstreamTrust = Optional.of(Objects.requireNonNull(upstreamTrust, "upstreamTrust"));
            return this;
        }

        /**
         * Whether the in-process admin server accepts stubs carrying scripts ({@code inject},
         * {@code decorate}, {@code shellTransform}, a {@code wait} function) — {@code allowInjection}
         * on {@code rift_serve_admin}. Unset, the engine's default applies: refused. {@link
         * Rift#replaceAll} is routed through that server, so on the embedded engine it refuses a
         * script-bearing imposter unless this is set; {@link Rift#create} and {@link Rift#applyConfig}
         * run over the C-ABI and are not gated. Keep it off when {@link #adminHost(String)} is
         * not loopback unless {@link #apiKey(String)} is set: it lets whoever reaches the admin API
         * run code in this process.
         *
         * <p>Requires an engine that advertises the option in its {@code serveOptions} (rift &ge;
         * 0.17.0), or starting the admin server fails with {@link
         * io.github.achirdlabs.rift.error.EngineUnavailable}. 0.15 and 0.16 take the key without
         * advertising it, and an engine before them drops it without a word, so absence from that
         * list is refused rather than guessed at.
         */
        public Builder allowInjection(boolean allowInjection) {
            this.allowInjection = Optional.of(allowInjection);
            return this;
        }

        /**
         * Makes the engine refuse to serve its admin API off loopback without an {@link
         * #apiKey(String)} — {@code requireAdminAuth} on {@code rift_serve_admin}. With the default
         * loopback {@link #adminHost(String)} it changes nothing. Advertisement is checked as for
         * {@link #allowInjection(boolean)}.
         */
        public Builder requireAdminAuth(boolean requireAdminAuth) {
            this.requireAdminAuth = Optional.of(requireAdminAuth);
            return this;
        }

        /**
         * Serves the engine's Prometheus registry at {@code http://<adminHost>:<metricsPort>/metrics}
         * — not gated by {@link #apiKey(String)}. Unset, the embedded engine runs no metrics listener.
         * Setting it starts the in-process admin server when the engine starts, as {@link
         * #serveAdminEagerly(boolean)} does, since the listener comes up with it — so a port that is
         * taken fails {@link Rift#embedded(EmbeddedOptions)} itself (unlike a spawned engine, which
         * runs on without metrics). Advertisement is checked as for {@link #allowInjection(boolean)}.
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

        public EmbeddedOptions build() {
            return new EmbeddedOptions(libraryPath, versionCheck, serveAdminEagerly, adminHost, adminPort, apiKey,
                    upstreamTrust, allowInjection, requireAdminAuth, metricsPort);
        }
    }
}
