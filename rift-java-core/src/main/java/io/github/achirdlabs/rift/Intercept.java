package io.github.achirdlabs.rift;

import io.github.achirdlabs.rift.dsl.IsSpec;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.util.List;

/**
 * A live intercept (TLS-MITM forward-proxy) listener: point an HTTPS client's proxy at
 * {@link #address()}/{@link #proxySelector()} (with {@link #trust()} in its trust store), then
 * add rules deciding what happens to each intercepted host — answer inline ({@link #serve}),
 * forward to a plain {@code host:port} ({@link #forward}), or forward to one of this SDK's own
 * {@link Imposter}s ({@link #redirectTo}).
 *
 * <p>Obtained via {@link Rift#intercept()}/{@link Rift#intercept(InterceptOptions)}; at most one
 * per engine — a second call throws {@link IllegalStateException}.
 */
public interface Intercept extends AutoCloseable {

    /** The intercept listener's bound address, for {@code http.proxyHost}/{@code http.proxyPort}-style configuration. */
    InetSocketAddress address();

    /** The intercept listener's base URL. */
    URI uri();

    /** A {@link ProxySelector} routing every request through this intercept — convenience for {@code java.net.http.HttpClient}. */
    ProxySelector proxySelector();

    /**
     * Adds a rule answering requests to {@code host} directly with {@code response}, without
     * contacting the real host.
     *
     * <p>The engine's serve action carries only a numeric {@code statusCode}, <em>single-valued</em>
     * {@code headers} and a text {@code body}. A response using anything else — any behavior
     * ({@code wait}/{@code decorate}/{@code repeat}/{@code copy}/{@code lookup}/{@code
     * shellTransform}), any {@code _rift} extension ({@code templated}, {@code script}, or a
     * latency/error/TCP fault), a binary body, or a repeated header — is rejected here rather than
     * silently dropped. Use {@link #redirectTo} to reach an imposter, which has full stub fidelity.
     *
     * @throws io.github.achirdlabs.rift.error.InvalidDefinition if {@code response} carries a
     *         construct the serve action cannot deliver; the rule is not registered
     */
    InterceptRule serve(String host, IsSpec response);

    /** Adds a rule forwarding requests to {@code host} on to {@code hostPort} (a {@code host:port} on localhost). */
    InterceptRule forward(String host, String hostPort);

    /** Adds a rule forwarding requests to {@code host} on to {@code imposter}'s own port. */
    InterceptRule redirectTo(String host, Imposter imposter);

    /**
     * Begins a predicate-scoped rule with an optional host — the engine's full rule shape (match by
     * path/method/headers/body like a stub, and/or a catch-all with no host), beyond the host-only
     * {@link #serve}/{@link #forward}/{@link #redirectTo} above. See {@link InterceptRuleBuilder}.
     */
    InterceptRuleBuilder rule();

    /** The current intercept rules, in the order they were added. */
    List<InterceptRule> rules();

    /** Removes every intercept rule. */
    void clearRules();

    /** Trust material for this intercept's CA. */
    InterceptTrust trust();

    /**
     * The CA material the engine generated when this intercept was started with
     * {@link InterceptOptions.Builder#generateCa()} — its cert <em>and</em> key, to persist and
     * redistribute a shareable anchor. Empty for an ephemeral or a caller-supplied CA.
     */
    java.util.Optional<CaMaterial> caMaterial();

    /** A generated CA's PEM material (cert + private key). */
    record CaMaterial(String certPem, String keyPem) { }

    /** Clears this intercept's rules; the listener itself is torn down when the owning {@link Rift} is closed. */
    @Override
    void close();
}
