package io.github.achirdlabs.rift.dsl;

import io.github.achirdlabs.rift.model.Predicate;
import io.github.achirdlabs.rift.model.PredicateOperation;
import io.github.achirdlabs.rift.model.Response;
import io.github.achirdlabs.rift.model.Stub;
import io.github.achirdlabs.rift.verify.RequestMatch;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * A stub under construction: the predicates a request must match, and the responses to serve
 * (cycled in order) once it does.
 *
 * <p>Created by an opener such as {@link RiftDsl#onGet(String)} (seeded with a combined
 * method+path {@code equals} predicate) or {@link RiftDsl#onRequest()} (no seed), then extended
 * with further predicates and responses. Instances are immutable: every chain method returns a new
 * {@code StubSpec}. The terminal {@link #build()} produces the {@link Stub} model value.
 *
 * <p>Implements {@link RequestMatch} so a stub under construction — even before {@link #build()} —
 * can be inspected/verified by its predicates alone.
 */
public final class StubSpec implements RequestMatch {

    private final List<Predicate> predicates;
    private final List<Response> responses;
    private final Optional<String> scenarioName;
    private final Optional<String> requiredScenarioState;
    private final Optional<String> newScenarioState;
    private final Optional<String> space;
    private final Optional<String> id;
    private final Optional<String> routePattern;
    private final Optional<String> name;

    StubSpec(List<Predicate> predicates) {
        this(predicates, List.of(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    private StubSpec(
            List<Predicate> predicates,
            List<Response> responses,
            Optional<String> scenarioName,
            Optional<String> requiredScenarioState,
            Optional<String> newScenarioState,
            Optional<String> space,
            Optional<String> id,
            Optional<String> routePattern,
            Optional<String> name) {
        this.predicates = predicates;
        this.responses = responses;
        this.scenarioName = scenarioName;
        this.requiredScenarioState = requiredScenarioState;
        this.newScenarioState = newScenarioState;
        this.space = space;
        this.id = id;
        this.routePattern = routePattern;
        this.name = name;
    }

    /** Adds a predicate matching the request method against {@code matcher}. */
    public StubSpec withMethod(Matcher matcher) {
        return withPredicate(RiftDsl.method(matcher));
    }

    /** Adds a predicate matching the request path against {@code matcher}. */
    public StubSpec withPath(Matcher matcher) {
        return withPredicate(RiftDsl.path(matcher));
    }

    /**
     * Adds a predicate matching the named request header against {@code matcher}. The header is
     * bound under the {@code headers} request field ({@code {"equals": {"headers": {name: ...}}}}),
     * the shape the engine matches headers against — identical to {@link RiftDsl#header(String,
     * Matcher)} and consistent with {@link #withQuery}.
     */
    public StubSpec withHeader(String name, Matcher matcher) {
        return withPredicate(RiftDsl.header(name, matcher));
    }

    /** Adds a predicate requiring the named request header to equal {@code value}. */
    public StubSpec withHeader(String name, String value) {
        return withHeader(name, RiftDsl.equals(value));
    }

    /** Adds a predicate matching the named query-string parameter against {@code matcher}. */
    public StubSpec withQuery(String name, Matcher matcher) {
        return withPredicate(RiftDsl.query(name, matcher));
    }

    /** Adds a predicate requiring the named query-string parameter to equal {@code value}. */
    public StubSpec withQuery(String name, String value) {
        return withQuery(name, RiftDsl.equals(value));
    }

    /** Adds a predicate matching the request body against {@code matcher}. */
    public StubSpec withBody(Matcher matcher) {
        return withPredicate(RiftDsl.body(matcher));
    }

    /** Adds an arbitrary predicate — for combinators ({@code and}/{@code or}/{@code not}) and structured selectors. */
    public StubSpec withPredicate(PredicateSpec predicate) {
        return withPredicate(predicate.build());
    }

    /** Adds a predicate whose match is decided entirely by an inline JavaScript function. */
    public StubSpec withPredicateInject(String script) {
        return withPredicate(new Predicate(new PredicateOperation.Inject(script)));
    }

    private StubSpec withPredicate(Predicate predicate) {
        List<Predicate> next = Stream.concat(predicates.stream(), Stream.of(predicate)).toList();
        return new StubSpec(next, responses, scenarioName, requiredScenarioState, newScenarioState, space, id, routePattern, name);
    }

    /** Adds a response. Repeatable: multiple calls model response cycling (served in call order). */
    public StubSpec willReturn(ResponseSpec response) {
        return new StubSpec(predicates, appendResponse(response.build()), scenarioName, requiredScenarioState,
                newScenarioState, space, id, routePattern, name);
    }

    private List<Response> appendResponse(Response response) {
        return Stream.concat(responses.stream(), Stream.of(response)).toList();
    }

    /**
     * Tags this stub with a bare scenario name, with no state-transition gate — the label a fixture
     * uses purely for documentation/grouping, as opposed to the full FSM transition {@link
     * RiftDsl#scenario(String)} builds.
     */
    public StubSpec scenario(String name) {
        return new StubSpec(predicates, responses, Optional.of(name), requiredScenarioState, newScenarioState, space, id, routePattern, this.name);
    }

    /** Sets the scenario name directly (scenario sugar, as an alternative to the full FSM builder). */
    public StubSpec inScenario(String name) {
        return scenario(name);
    }

    /** Requires the named scenario state before this stub matches (scenario sugar). */
    public StubSpec whenScenarioState(String state) {
        return new StubSpec(predicates, responses, scenarioName, Optional.of(state), newScenarioState, space, id, routePattern, name);
    }

    /** Transitions the scenario to the named state once this stub serves a response (scenario sugar). */
    public StubSpec willSetScenarioState(String state) {
        return new StubSpec(predicates, responses, scenarioName, requiredScenarioState, Optional.of(state), space, id, routePattern, name);
    }

    /**
     * Isolates this stub's correlated state within the given flow/space id. The imposter must declare
     * a header-form flow-id source ({@link FlowStateSpec#flowIdFromHeader}) — the engine's flow-id
     * source defaults to the imposter port, so a space stub can otherwise never match. {@link
     * ImposterSpec#build()} throws if a space stub is present without one.
     *
     * @throws IllegalArgumentException if {@code flowId} is blank — a blank id is never the default
     *     flow but a distinct, silently-wrong partition, so it is rejected rather than sent verbatim
     */
    public StubSpec inSpace(String flowId) {
        Objects.requireNonNull(flowId, "flowId");
        if (flowId.isBlank()) {
            throw new IllegalArgumentException("flowId must not be blank");
        }
        return new StubSpec(predicates, responses, scenarioName, requiredScenarioState, newScenarioState, Optional.of(flowId), id, routePattern, name);
    }

    /**
     * Labels this stub for the reader. From rift 0.22.0 the engine keeps the name and prints it back
     * (an older engine drops it); it never matches on it. Unlike {@link #scenario(String)}, it sets
     * no scenario. Naming a stub without an {@link #withId id} changes the key {@code applyConfig}
     * reconciles it by, so the engine treats it as a changed stub.
     */
    public StubSpec named(String name) {
        Objects.requireNonNull(name, "name");
        return new StubSpec(predicates, responses, scenarioName, requiredScenarioState, newScenarioState, space, id,
                routePattern, Optional.of(name));
    }

    /** Assigns this stub a stable id, for later reference (e.g. deletion, verification). */
    public StubSpec withId(String stubId) {
        return new StubSpec(predicates, responses, scenarioName, requiredScenarioState, newScenarioState, space, Optional.of(stubId), routePattern, name);
    }

    /** Sets the route pattern (path template) this stub is registered under. */
    public StubSpec withRoute(String pattern) {
        return new StubSpec(predicates, responses, scenarioName, requiredScenarioState, newScenarioState, space, id, Optional.of(pattern), name);
    }

    /** Attaches a full scenario-FSM transition: name, required (from) state, and new (to) state. Used by {@link ScenarioSpec}. */
    StubSpec withScenarioTransition(String scenarioName, Optional<String> requiredState, String newState) {
        return new StubSpec(predicates, responses, Optional.of(scenarioName), requiredState, Optional.of(newState), space, id, routePattern, name);
    }

    /** The predicates added so far — usable to inspect/verify a stub under construction, before {@link #build()}. */
    @Override
    public List<Predicate> predicates() {
        return predicates;
    }

    /** Builds the immutable {@link Stub} this spec represents. */
    public Stub build() {
        return new Stub(
                name, scenarioName, requiredScenarioState, newScenarioState, space, id, routePattern,
                predicates, responses, Optional.empty(), Optional.empty(), Map.of());
    }
}
