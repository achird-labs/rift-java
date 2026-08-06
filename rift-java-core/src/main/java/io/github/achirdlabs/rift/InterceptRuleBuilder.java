package io.github.achirdlabs.rift;

import io.github.achirdlabs.rift.dsl.IsSpec;
import io.github.achirdlabs.rift.model.Predicate;
import io.github.achirdlabs.rift.verify.RequestMatch;

import java.util.List;
import java.util.Objects;

/**
 * Builds an intercept rule scoped by request predicates and/or an optional host — the engine's full
 * {@code (host?, predicates, action)} rule shape, beyond the host-only {@link Intercept#serve}/{@link
 * Intercept#forward}/{@link Intercept#redirectTo} convenience methods. Obtain one from {@link
 * Intercept#rule()}, narrow it, then choose a terminal action:
 *
 * <pre>{@code
 * intercept.rule().host("example.com").when(onGet("/health")).serve(ok());
 * intercept.rule().when(onPost("/api/**")).redirectTo(partnerImposter);   // no host = catch-all
 * }</pre>
 */
public final class InterceptRuleBuilder {

    private final InterceptImpl intercept;
    private String host;                          // null = catch-all (engine host: None)
    private List<Predicate> predicates = List.of();

    InterceptRuleBuilder(InterceptImpl intercept) {
        this.intercept = intercept;
    }

    /** Scopes the rule to one host; omit for a catch-all rule matching any intercepted host. */
    public InterceptRuleBuilder host(String host) {
        this.host = Objects.requireNonNull(host, "host");
        return this;
    }

    /** Matches requests by predicates — the same DSL stubs use, e.g. {@code onGet("/health")}. */
    public InterceptRuleBuilder when(RequestMatch match) {
        this.predicates = List.copyOf(Objects.requireNonNull(match, "match").predicates());
        return this;
    }

    /**
     * Answers matching requests inline with {@code response}; the real host is never contacted.
     *
     * <p>Restricted to what the engine's serve action can carry — status, single-valued headers and
     * a text body. See {@link Intercept#serve} for the full deliverable set; anything outside it is
     * rejected rather than silently dropped.
     *
     * @throws io.github.achirdlabs.rift.error.InvalidDefinition if {@code response} carries a
     *         construct the serve action cannot deliver; the rule is not registered
     */
    public InterceptRule serve(IsSpec response) {
        return intercept.addServeRule(host, predicates, response, RuleKind.SERVE);
    }

    /** Forwards matching requests to {@code hostPort} (a {@code host:port} on localhost). */
    public InterceptRule forward(String hostPort) {
        return intercept.addForwardRule(host, predicates, InterceptImpl.parsePort(hostPort), RuleKind.FORWARD);
    }

    /** Forwards matching requests to {@code imposter}'s own port. */
    public InterceptRule redirectTo(Imposter imposter) {
        return intercept.addForwardRule(host, predicates, imposter.port(), RuleKind.REDIRECT);
    }
}
