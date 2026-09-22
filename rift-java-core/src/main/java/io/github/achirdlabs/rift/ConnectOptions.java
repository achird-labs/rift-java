package io.github.achirdlabs.rift;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntFunction;

/**
 * Immutable configuration for {@link Rift#connect(ConnectOptions)}: where the admin API lives,
 * how to authenticate against it, and how a live imposter's own network address is derived from
 * its port.
 *
 * <p>There is no outbound TLS trust setting here ({@link UpstreamTrust}): a connected engine was
 * configured by whoever started it, so pass {@code --upstream-ca-file} to {@code rift} there.
 */
public final class ConnectOptions {

    private final URI adminUri;
    private final Optional<String> apiKey;
    private final Duration requestTimeout;
    private final VersionCheck versionCheck;
    private final IntFunction<URI> hostResolver;

    private ConnectOptions(
            URI adminUri,
            Optional<String> apiKey,
            Duration requestTimeout,
            VersionCheck versionCheck,
            IntFunction<URI> hostResolver) {
        this.adminUri = adminUri;
        this.apiKey = apiKey;
        this.requestTimeout = requestTimeout;
        this.versionCheck = versionCheck;
        this.hostResolver = hostResolver;
    }

    public static Builder builder(URI adminUri) {
        return new Builder(adminUri);
    }

    public URI adminUri() {
        return adminUri;
    }

    public Optional<String> apiKey() {
        return apiKey;
    }

    public Duration requestTimeout() {
        return requestTimeout;
    }

    public VersionCheck versionCheck() {
        return versionCheck;
    }

    public IntFunction<URI> hostResolver() {
        return hostResolver;
    }

    public static final class Builder {

        private final URI adminUri;
        private Optional<String> apiKey = Optional.empty();
        private Duration requestTimeout = Duration.ofSeconds(30);
        private VersionCheck versionCheck = VersionCheck.resolveDefault();
        private IntFunction<URI> hostResolver;

        private Builder(URI adminUri) {
            this.adminUri = Objects.requireNonNull(adminUri, "adminUri");
            this.hostResolver = defaultHostResolver(adminUri);
        }

        /** The API key sent as the {@code Authorization} header on every admin-API request. */
        public Builder apiKey(String apiKey) {
            this.apiKey = Optional.of(apiKey);
            return this;
        }

        public Builder requestTimeout(Duration requestTimeout) {
            this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
            return this;
        }

        public Builder versionCheck(VersionCheck versionCheck) {
            this.versionCheck = Objects.requireNonNull(versionCheck, "versionCheck");
            return this;
        }

        /** Overrides how an imposter's own network address is derived from its bound port. */
        public Builder hostResolver(IntFunction<URI> hostResolver) {
            this.hostResolver = Objects.requireNonNull(hostResolver, "hostResolver");
            return this;
        }

        public ConnectOptions build() {
            return new ConnectOptions(adminUri, apiKey, requestTimeout, versionCheck, hostResolver);
        }

        /** {@code scheme://adminHost:port} — the admin host reused as the imposter's host. */
        private static IntFunction<URI> defaultHostResolver(URI adminUri) {
            String scheme = adminUri.getScheme();
            String host = adminUri.getHost();
            return port -> URI.create(scheme + "://" + host + ":" + port);
        }
    }
}
