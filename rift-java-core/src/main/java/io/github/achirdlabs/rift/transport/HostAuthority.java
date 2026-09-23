package io.github.achirdlabs.rift.transport;

import java.net.URI;
import java.util.Objects;

/**
 * Builds the authority of an {@code http://host:port} URI from a host the engine binds.
 *
 * <p>rift 0.18.0 accepts a bare IPv6 literal such as {@code ::1} on every door, but {@link URI}
 * cannot parse one unbracketed: {@code http://::1:4545} has no host and no port. So a bare IPv6
 * literal is bracketed, exactly as the engine's own URLs are, and every other host is left as it
 * is. A zone id ({@code fe80::1%2}) is passed through raw inside the brackets, which Java's URI and
 * HTTP client accept and the engine itself emits.
 */
public final class HostAuthority {

    private HostAuthority() {
    }

    /** {@code [host]} when {@code host} is a bare IPv6 literal; otherwise {@code host} unchanged. */
    public static String bracketed(String host) {
        Objects.requireNonNull(host, "host");
        return host.indexOf(':') >= 0 && !host.startsWith("[") ? "[" + host + "]" : host;
    }

    /** {@code http://host:port}, with a bare IPv6 host bracketed. */
    public static URI httpUri(String host, int port) {
        return URI.create("http://" + bracketed(host) + ":" + port);
    }
}
