package io.github.achirdlabs.rift;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * A live intercept (TLS-MITM forward-proxy) listener: point an HTTPS client's proxy at
 * {@link #address()}/{@link #proxySelector()} (with {@link #trust()} in its trust store), then
 * add rules deciding what happens to each intercepted host — answer inline ({@link #serve}),
 * forward to a plain {@code host:port} ({@link #forward}), or forward to one of this SDK's own
 * {@link Imposter}s ({@link #redirectTo}).
 *
 * <p>Obtained via {@link Rift#intercept()}/{@link Rift#intercept(InterceptOptions)}; one at a time
 * per engine — a second call while one is open throws {@link IllegalStateException}, and {@link
 * #close()} frees the engine for another.
 */
public interface Intercept extends InterceptRuleSet, AutoCloseable {

    /** The intercept listener's bound address, for {@code http.proxyHost}/{@code http.proxyPort}-style configuration. */
    InetSocketAddress address();

    /** The intercept listener's base URL. */
    URI uri();

    /**
     * The address the engine reported binding the listener to, for diagnostics; empty for an {@link
     * InterceptOptions#attach attached} listener, whose engine-side address is never reported. On a
     * connected engine it can differ from {@link #address()}, which is where this client dials.
     */
    default Optional<URI> engineAddress() {
        return Optional.empty();
    }

    /** A {@link ProxySelector} routing every request through this intercept — convenience for {@code java.net.http.HttpClient}. */
    ProxySelector proxySelector();

    /**
     * Replaces every intercept rule with the ones {@code declare} adds to the set it is given, in a
     * single engine call ({@code PUT /intercept/rules}, rift &ge; 0.20.0): a request arriving
     * meanwhile meets either the old rules or the new ones, never a partial or empty set. Rules match
     * first-to-last, so this is also the only way to put a rule ahead of one already installed.
     * Declaring nothing clears the rules.
     *
     * <p>{@code declare} only describes the rules; nothing reaches the engine until it returns, and
     * nothing at all if it throws, so the old rules stay. The set must not be used after it returns.
     * Every rule installed this way counts as added at runtime, including one first seeded from the
     * engine's config file, which a later {@code POST /admin/reload} then seeds again ahead of it.
     *
     * @return the rules installed, as their declaring calls returned them (a {@link
     *         RuleKind#REDIRECT} stays one)
     * @throws io.github.achirdlabs.rift.error.InvalidDefinition if the engine is older than 0.20.0
     *         (before {@code declare} runs), or a declared rule is refused
     */
    List<InterceptRule> replaceRules(Consumer<? super InterceptRuleSet> declare);

    /**
     * Replaces every intercept rule with {@code rules}, in order, in a single engine call — for
     * re-installing a filtered or reordered {@link #rules()}. Needs rift &ge; 0.20.0.
     *
     * @return the rules installed: {@code rules}, as an unmodifiable copy
     */
    List<InterceptRule> replaceRules(List<InterceptRule> rules);

    /**
     * Removes every installed rule equal to {@code rule} (one returned by a rule-adding call, or by
     * {@link #rules()}), keeping the others in order. Rules are compared as the engine stores them,
     * ignoring the defaults its listing adds. Reads the rules and replaces them, so it needs rift
     * &ge; 0.20.0, and a rule another client adds in between is lost.
     *
     * @return whether a rule was removed; when none matched, the rules are left untouched
     */
    boolean removeRule(InterceptRule rule);

    /** The current intercept rules, in the order they were added. */
    List<InterceptRule> rules();

    /** Removes every intercept rule. */
    void clearRules();

    /** Trust material for this intercept's CA. */
    InterceptTrust trust();

    /**
     * This intercept's CA cert <em>and</em> key, to persist or hand to another container: the pair the
     * engine generated when started with {@link InterceptOptions.Builder#generateCa()}, or the pair
     * given to {@link InterceptOptions#attach(String, int, CaMaterial)} — checked at attach against the
     * certificate the listener serves, since the engine never returns a key it was given (the key itself
     * is not checked). Empty for an ephemeral CA, a CA supplied to a start, or an attach given no pair.
     */
    java.util.Optional<CaMaterial> caMaterial();

    /** A CA's PEM material (cert + private key). */
    record CaMaterial(String certPem, String keyPem) { }

    /**
     * Stops a listener this handle started, freeing the engine for a new {@link
     * Rift#intercept(InterceptOptions)} — which, without a supplied CA, mints a new one. For an {@link
     * InterceptOptions#attach attached} listener, which its launcher owns, clears the rules and leaves
     * it running: only a re-attach can follow. Afterwards rule operations and {@link #trust()} throw
     * {@link IllegalStateException}; the address accessors still answer. Idempotent; a failed stop
     * leaves the handle open, to retry. Not to be called while another thread is mid-way through a
     * rule operation on this handle.
     */
    @Override
    void close();
}
