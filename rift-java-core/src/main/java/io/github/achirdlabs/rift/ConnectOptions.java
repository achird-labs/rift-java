package io.github.achirdlabs.rift;

import io.github.achirdlabs.rift.transport.HostAuthority;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntFunction;

/**
 * Immutable configuration for {@link Rift#connect(ConnectOptions)}: where the admin API lives,
 * how to authenticate against it, and how a live imposter's own network address is derived from
 * its protocol and port ({@link HostResolver}).
 *
 * <p>There is no outbound TLS trust setting here ({@link UpstreamTrust}): a connected engine was
 * configured by whoever started it, so pass {@code --upstream-ca-file} to {@code rift} there.
 */
public final class ConnectOptions {

    private final URI adminUri;
    private final Optional<String> apiKey;
    private final Duration requestTimeout;
    private final VersionCheck versionCheck;
    private final HostResolver hostResolver;

    private ConnectOptions(
            URI adminUri,
            Optional<String> apiKey,
            Duration requestTimeout,
            VersionCheck versionCheck,
            HostResolver hostResolver) {
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

    public HostResolver hostResolver() {
        return hostResolver;
    }

    public static final class Builder {

        private final URI adminUri;
        private Optional<String> apiKey = Optional.empty();
        private Duration requestTimeout = Duration.ofSeconds(30);
        private VersionCheck versionCheck = VersionCheck.resolveDefault();
        private HostResolver hostResolver;

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

        /**
         * Overrides where an imposter is reached, given its protocol and bound port. The default is
         * the admin host with the imposter's port, and the imposter's protocol as the scheme.
         */
        public Builder hostResolver(HostResolver hostResolver) {
            this.hostResolver = Objects.requireNonNull(hostResolver, "hostResolver");
            return this;
        }

        /**
         * Overrides where an imposter is reached, given only its bound port. The returned URI is used
         * verbatim, scheme included, whatever the imposter's protocol: use this form when the SUT
         * goes through a hop that owns the scheme, and {@link #hostResolver(HostResolver)} to follow
         * the imposter's protocol.
         */
        public Builder hostResolver(IntFunction<URI> hostResolver) {
            Objects.requireNonNull(hostResolver, "hostResolver");
            this.hostResolver = (protocol, port) -> hostResolver.apply(port);
            return this;
        }

        public ConnectOptions build() {
            return new ConnectOptions(adminUri, apiKey, requestTimeout, versionCheck, hostResolver);
        }

        /**
         * {@code protocol://adminHost:port} — the admin host reused as the imposter's host. The scheme
         * is the imposter's protocol: the admin listener's own scheme says nothing about an imposter's.
         */
        private static HostResolver defaultHostResolver(URI adminUri) {
            String host = adminUri.getHost();
            return (protocol, port) -> HostAuthority.uri(protocol, host, port);
        }
    }
}
