package io.github.achirdlabs.rift;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

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

    private EmbeddedOptions(
            Optional<Path> libraryPath,
            VersionCheck versionCheck,
            boolean serveAdminEagerly,
            String adminHost,
            int adminPort,
            Optional<String> apiKey) {
        this.libraryPath = libraryPath;
        this.versionCheck = versionCheck;
        this.serveAdminEagerly = serveAdminEagerly;
        this.adminHost = adminHost;
        this.adminPort = adminPort;
        this.apiKey = apiKey;
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

    public static final class Builder {

        private Optional<Path> libraryPath = Optional.empty();
        private VersionCheck versionCheck = VersionCheck.resolveDefault();
        private boolean serveAdminEagerly = false;
        private String adminHost = "127.0.0.1";
        private int adminPort = 0;
        private Optional<String> apiKey = Optional.empty();

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
         * literal such as {@code 127.0.0.1}, {@code 0.0.0.0} or {@code ::1}, <b>not</b> a hostname:
         * the engine parses {@code host:port} as a socket address, so {@code "localhost"} is a
         * bind error rather than loopback. It is also the host imposters report in their own
         * {@code uri()}.
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

        public EmbeddedOptions build() {
            return new EmbeddedOptions(libraryPath, versionCheck, serveAdminEagerly, adminHost, adminPort, apiKey);
        }
    }
}
