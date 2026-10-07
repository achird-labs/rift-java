package io.github.achirdlabs.rift;

import io.github.achirdlabs.rift.dsl.IsSpec;

/**
 * Declares intercept rules: what happens to a request for an intercepted host. {@link Intercept} is
 * one, installing each rule as it is declared; the set handed to {@link
 * Intercept#replaceRules(java.util.function.Consumer)} is another, which only stages them, so the
 * whole set is installed in one step. A rules method written against this interface works with both.
 */
public interface InterceptRuleSet {

    /**
     * Adds a rule answering requests to {@code host} directly with {@code response}, without
     * contacting the real host.
     *
     * <p>The engine's serve action carries only a numeric {@code statusCode}, {@code headers} and a
     * text {@code body}. A repeated header is sent as one header line per value, which needs rift
     * &ge; 0.18.0; on an older engine it is refused here (unless the version check is off), since
     * that engine would reject the rule. A response using anything else — any behavior ({@code
     * wait}/{@code decorate}/{@code repeat}/{@code copy}/{@code lookup}/{@code shellTransform}), any
     * {@code _rift} extension ({@code templated}, {@code script}, or a latency/error/TCP fault), or a
     * binary body — is rejected here rather than silently dropped. Use {@link #redirectTo} to reach an imposter, which has full stub fidelity.
     *
     * @throws io.github.achirdlabs.rift.error.InvalidDefinition if {@code response} carries a
     *         construct the serve action cannot deliver; the rule is not registered
     */
    InterceptRule serve(String host, IsSpec response);

    /**
     * Adds a rule forwarding requests to {@code host} on to {@code target}, which receives them with
     * the client's original {@code Host} (rift &ge; 0.20.0). {@code target} is one of:
     * <ul>
     *   <li>a port, or {@code localhost:port} / {@code 127.0.0.1:port} / {@code [::1]:port} — the
     *       engine's own machine over http, which every engine version reads;</li>
     *   <li>{@code host:port}, such as a Testcontainers network alias ({@code partner-mock:4600}) or a
     *       bracketed IPv6 literal;</li>
     *   <li>{@code http://host:port} or {@code https://host:port}. An https target is verified against
     *       the engine's outbound trust ({@link SpawnOptions.Builder#upstreamTrust}), with {@code host}
     *       as the server name.</li>
     * </ul>
     * A named host or https needs rift &ge; 0.20.0: an older engine ignores both and forwards to its
     * own machine, so the rule is refused here (unless the version check is off).
     *
     * @throws IllegalArgumentException if {@code target} is none of the above: no port, a port outside
     *         1–65535, a path, query or user info, or a scheme other than http/https
     * @throws io.github.achirdlabs.rift.error.InvalidDefinition if the target needs a newer engine
     */
    InterceptRule forward(String host, String target);

    /** Adds a rule forwarding requests to {@code host} on to {@code imposter}'s own port. */
    InterceptRule redirectTo(String host, Imposter imposter);

    /**
     * Begins a predicate-scoped rule with an optional host — the engine's full rule shape (match by
     * path/method/headers/body like a stub, and/or a catch-all with no host), beyond the host-only
     * {@link #serve}/{@link #forward}/{@link #redirectTo} above. See {@link InterceptRuleBuilder}.
     */
    InterceptRuleBuilder rule();
}
