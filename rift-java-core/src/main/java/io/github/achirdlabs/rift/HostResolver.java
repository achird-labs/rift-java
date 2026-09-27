package io.github.achirdlabs.rift;

import java.net.URI;

/**
 * Where the system under test reaches an imposter: the seam behind {@link Imposter#uri()}, set with
 * {@link ConnectOptions.Builder#hostResolver(HostResolver)}.
 *
 * <p>The resolver is told the imposter's protocol because the right scheme depends on the path the
 * traffic takes, which only the resolver knows. Straight to the imposter's own listener, the scheme
 * is the protocol; through a hop that speaks something else, such as the engine's {@code
 * /__rift/<port>} gateway on the admin listener, it is that hop's.
 */
@FunctionalInterface
public interface HostResolver {

    /**
     * The base URI for the imposter bound on {@code port}.
     *
     * @param protocol the imposter's engine protocol, {@code "http"} or {@code "https"}
     * @param port     the imposter's port on the engine
     */
    URI resolve(String protocol, int port);
}
