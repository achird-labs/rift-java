package io.github.achirdlabs.rift;

import io.github.achirdlabs.rift.json.JsonNumber;
import io.github.achirdlabs.rift.json.JsonObject;
import io.github.achirdlabs.rift.json.JsonString;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where a {@code forward} rule sends a request: the engine's {@code ForwardTarget {port, host?,
 * scheme?}} (see {@code intercept_rules.rs}). An absent host is the engine's own machine and http is
 * the default scheme — the only target an engine before rift 0.20.0 reads.
 *
 * @param host  the target host as written (a name, an IPv4 literal or a bracketed IPv6 literal);
 *              empty for the engine's own machine
 * @param port  1–65535
 * @param https whether the engine dials the target over TLS, verifying it against {@code host}
 */
record ForwardTarget(Optional<String> host, int port, boolean https) {

    /** {@code scheme://} (optional), a bracketed IPv6 literal or a plain host (optional), {@code :port}. */
    private static final Pattern TARGET = Pattern.compile(
            "(?:(?<scheme>[A-Za-z][A-Za-z0-9+.-]*)://)?(?:(?<host>\\[[^\\]]*\\]|[^:\\[\\]]*):)?(?<port>[^:]*)");
    /** The engine's own host check ({@code check_forward_host}): what a DNS name or IPv4 literal may contain. */
    private static final Pattern PLAIN_HOST = Pattern.compile("[A-Za-z0-9._-]+");
    /** A bracketed IPv6 literal; the engine refuses a zone id. */
    private static final Pattern IPV6_LITERAL = Pattern.compile("\\[[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*\\]");
    private static final Pattern PORT = Pattern.compile("[0-9]{1,5}");
    /** Spellings of the engine's own machine, which an http target need not name. */
    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "[::1]");
    private static final int MAX_PORT = 65535;

    ForwardTarget {
        Objects.requireNonNull(host, "host");
        if (port < 1 || port > MAX_PORT) {
            throw new IllegalArgumentException("a forward target port must be 1-" + MAX_PORT + ", got " + port);
        }
        if (https && host.isEmpty()) {
            throw new IllegalArgumentException("an https forward target needs a host to verify the upstream against");
        }
    }

    /** The engine's own machine, over http: the port-only wire every engine reads. */
    static ForwardTarget local(int port) {
        return new ForwardTarget(Optional.empty(), port, false);
    }

    /**
     * Reads a {@code forward} target. A loopback host is dropped for http, keeping the port-only wire
     * every engine reads; for https it is kept, because the engine verifies the upstream certificate
     * against that host.
     *
     * @throws IllegalArgumentException if {@code target} is not one of the accepted forms
     */
    static ForwardTarget parse(String target) {
        Matcher m = TARGET.matcher(target);
        if (!m.matches()) {
            throw invalid(target, "expected [http(s)://]host:port or a bare port");
        }
        String scheme = m.group("scheme") == null ? null : m.group("scheme").toLowerCase(Locale.ROOT);
        if (scheme != null && !scheme.equals("http") && !scheme.equals("https")) {
            throw invalid(target, "the scheme must be http or https");
        }
        int port = port(target, m.group("port"));
        String host = m.group("host");
        if (host == null) {
            if (scheme != null) {
                throw invalid(target, "a URL target needs a host");
            }
            return local(port);
        }
        if (!(IPV6_LITERAL.matcher(host).matches() || PLAIN_HOST.matcher(host).matches())) {
            throw invalid(target, "the host may only use letters, digits, '.', '-' and '_', or be a bracketed IPv6"
                    + " literal");
        }
        boolean https = "https".equals(scheme);
        if (!https && LOOPBACK.contains(host.toLowerCase(Locale.ROOT))) {
            return local(port);
        }
        return new ForwardTarget(Optional.of(host), port, https);
    }

    private static int port(String target, String text) {
        if (!PORT.matcher(text).matches()) {
            throw invalid(target, "'" + text + "' is not a port");
        }
        int port = Integer.parseInt(text);
        if (port < 1 || port > MAX_PORT) {
            throw invalid(target, "the port must be 1-" + MAX_PORT);
        }
        return port;
    }

    private static IllegalArgumentException invalid(String target, String why) {
        return new IllegalArgumentException("not a valid forward target '" + target + "': " + why);
    }

    /** Whether the target uses what only rift &ge; 0.20.0 reads: an older engine ignores both keys. */
    boolean needsHostOrScheme() {
        return host.isPresent() || https;
    }

    JsonObject toJson() {
        JsonObject.Builder builder = JsonObject.builder().put("port", JsonNumber.of(port));
        host.ifPresent(h -> builder.put("host", new JsonString(h)));
        if (https) {
            builder.put("scheme", new JsonString("https"));
        }
        return builder.build();
    }
}
