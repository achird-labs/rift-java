package io.github.achirdlabs.rift.dsl;

import io.github.achirdlabs.rift.json.JsonBool;
import io.github.achirdlabs.rift.json.JsonString;
import io.github.achirdlabs.rift.json.JsonValue;
import io.github.achirdlabs.rift.model.Behavior;
import io.github.achirdlabs.rift.model.ImposterDefinition;
import io.github.achirdlabs.rift.model.IsResponse;
import io.github.achirdlabs.rift.model.Predicate;
import io.github.achirdlabs.rift.model.PredicateOperation;
import io.github.achirdlabs.rift.model.PredicateSelector;
import io.github.achirdlabs.rift.model.ProxyResponse;
import io.github.achirdlabs.rift.model.Response;
import io.github.achirdlabs.rift.model.RiftResponseExtension;
import io.github.achirdlabs.rift.model.Stub;
import io.github.achirdlabs.rift.model.WaitSpec;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static io.github.achirdlabs.rift.dsl.RiftDsl.and;
import static io.github.achirdlabs.rift.dsl.RiftDsl.body;
import static io.github.achirdlabs.rift.dsl.RiftDsl.contains;
import static io.github.achirdlabs.rift.dsl.RiftDsl.created;
import static io.github.achirdlabs.rift.dsl.RiftDsl.deepEquals;
import static io.github.achirdlabs.rift.dsl.RiftDsl.endsWith;
import static io.github.achirdlabs.rift.dsl.RiftDsl.exists;
import static io.github.achirdlabs.rift.dsl.RiftDsl.fault;
import static io.github.achirdlabs.rift.dsl.RiftDsl.header;
import static io.github.achirdlabs.rift.dsl.RiftDsl.imposter;
import static io.github.achirdlabs.rift.dsl.RiftDsl.inject;
import static io.github.achirdlabs.rift.dsl.RiftDsl.matches;
import static io.github.achirdlabs.rift.dsl.RiftDsl.method;
import static io.github.achirdlabs.rift.dsl.RiftDsl.noContent;
import static io.github.achirdlabs.rift.dsl.RiftDsl.not;
import static io.github.achirdlabs.rift.dsl.RiftDsl.notFound;
import static io.github.achirdlabs.rift.dsl.RiftDsl.notExists;
import static io.github.achirdlabs.rift.dsl.RiftDsl.ok;
import static io.github.achirdlabs.rift.dsl.RiftDsl.okJson;
import static io.github.achirdlabs.rift.dsl.RiftDsl.on;
import static io.github.achirdlabs.rift.dsl.RiftDsl.onDelete;
import static io.github.achirdlabs.rift.dsl.RiftDsl.onGet;
import static io.github.achirdlabs.rift.dsl.RiftDsl.onHead;
import static io.github.achirdlabs.rift.dsl.RiftDsl.onOptions;
import static io.github.achirdlabs.rift.dsl.RiftDsl.onPatch;
import static io.github.achirdlabs.rift.dsl.RiftDsl.onPost;
import static io.github.achirdlabs.rift.dsl.RiftDsl.onPut;
import static io.github.achirdlabs.rift.dsl.RiftDsl.or;
import static io.github.achirdlabs.rift.dsl.RiftDsl.onRequest;
import static io.github.achirdlabs.rift.dsl.RiftDsl.path;
import static io.github.achirdlabs.rift.dsl.RiftDsl.proxyTo;
import static io.github.achirdlabs.rift.dsl.RiftDsl.query;
import static io.github.achirdlabs.rift.dsl.RiftDsl.scenario;
import static io.github.achirdlabs.rift.dsl.RiftDsl.script;
import static io.github.achirdlabs.rift.dsl.RiftDsl.startsWith;
import static io.github.achirdlabs.rift.dsl.RiftDsl.status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Targeted unit tests for each construct in the {@link RiftDsl} grammar, asserting on the built
 * typed {@code model.*} values (not just non-null) — the complement to {@link
 * CorpusExpressibilityTest}, which exercises only the constructs the six corpus fixtures happen to
 * use.
 */
class DslConstructTest {

    // ------------------------------------------------------------------
    // Matchers, bound to each field
    // ------------------------------------------------------------------

    @Test
    void equalsMatcherOnMethodAndPath() {
        Stub stub = on("GET", "/health").build();
        PredicateOperation.Equals op = (PredicateOperation.Equals) stub.predicates().get(0).operation();
        assertEquals(new JsonString("GET"), op.fields().get("method"));
        assertEquals(new JsonString("/health"), op.fields().get("path"));
    }

    @Test
    void deepEqualsMatcherOnMethod() {
        Predicate predicate = onRequest().withMethod(deepEquals("POST")).build().predicates().get(0);
        PredicateOperation.DeepEquals op = (PredicateOperation.DeepEquals) predicate.operation();
        assertEquals(new JsonString("POST"), op.fields().get("method"));
    }

    @Test
    void containsMatcherOnBody() {
        Predicate predicate = onRequest().withBody(contains(RiftDsl.json("{\"a\":1}"))).build().predicates().get(0);
        PredicateOperation.Contains op = (PredicateOperation.Contains) predicate.operation();
        assertEquals(RiftDsl.json("{\"a\":1}"), op.fields().get("body"));
    }

    @Test
    void startsWithMatcherOnHeader() {
        // withHeader binds under the "headers" request field — the shape the engine matches headers against.
        Predicate predicate = onRequest().withHeader("Authorization", startsWith("Bearer ")).build().predicates().get(0);
        PredicateOperation.StartsWith op = (PredicateOperation.StartsWith) predicate.operation();
        JsonValue headers = op.fields().get("headers");
        assertEquals(new JsonString("Bearer "), ((io.github.achirdlabs.rift.json.JsonObject) headers).get("Authorization"));
    }

    @Test
    void endsWithMatcherOnPath() {
        Predicate predicate = onRequest().withPath(endsWith("/tasks")).build().predicates().get(0);
        PredicateOperation.EndsWith op = (PredicateOperation.EndsWith) predicate.operation();
        assertEquals(new JsonString("/tasks"), op.fields().get("path"));
    }

    @Test
    void matchesMatcherOnPath() {
        Predicate predicate = onRequest().withPath(matches("/tasks/\\d+")).build().predicates().get(0);
        PredicateOperation.Matches op = (PredicateOperation.Matches) predicate.operation();
        assertEquals(new JsonString("/tasks/\\d+"), op.fields().get("path"));
    }

    @Test
    void existsMatcherOnQuery() {
        Predicate predicate = onRequest().withQuery("status", exists()).build().predicates().get(0);
        PredicateOperation.Exists op = (PredicateOperation.Exists) predicate.operation();
        JsonValue query = op.fields().get("query");
        assertEquals(JsonBool.TRUE, ((io.github.achirdlabs.rift.json.JsonObject) query).get("status"));
    }

    @Test
    void notExistsMatcherProducesExistsFalse() {
        Predicate predicate = onRequest().withQuery("status", notExists()).build().predicates().get(0);
        PredicateOperation.Exists op = (PredicateOperation.Exists) predicate.operation();
        JsonValue query = op.fields().get("query");
        assertEquals(JsonBool.FALSE, ((io.github.achirdlabs.rift.json.JsonObject) query).get("status"));
    }

    @Test
    void headerEqualsSugarStringOverload() {
        Predicate predicate = onRequest().withHeader("X-Trace", "abc").build().predicates().get(0);
        PredicateOperation.Equals op = (PredicateOperation.Equals) predicate.operation();
        JsonValue headers = op.fields().get("headers");
        assertEquals(new JsonString("abc"), ((io.github.achirdlabs.rift.json.JsonObject) headers).get("X-Trace"));
    }

    @Test
    void standaloneHeaderBinderNestsUnderHeadersField() {
        // The standalone header() field binder (for combinators) still produces the engine's real
        // nested wire shape: {"headers": {name: value}}.
        Predicate predicate = header("X-Trace", RiftDsl.equals("abc")).build();
        PredicateOperation.Equals op = (PredicateOperation.Equals) predicate.operation();
        JsonValue headers = op.fields().get("headers");
        assertEquals(new JsonString("abc"), ((io.github.achirdlabs.rift.json.JsonObject) headers).get("X-Trace"));
    }

    @Test
    void queryEqualsSugarStringOverload() {
        Predicate predicate = onRequest().withQuery("status", "OPEN").build().predicates().get(0);
        PredicateOperation.Equals op = (PredicateOperation.Equals) predicate.operation();
        JsonValue queryField = op.fields().get("query");
        assertEquals(new JsonString("OPEN"), ((io.github.achirdlabs.rift.json.JsonObject) queryField).get("status"));
    }

    @Test
    void standaloneFieldBindersMatchStubChainMethods() {
        // method(...)/path(...)/header(...)/query(...)/body(...) are usable standalone (for combinators)
        // and produce exactly the same Predicate a StubSpec.withXxx call would.
        Predicate viaBinder = method(deepEquals("GET")).build();
        Predicate viaChain = onRequest().withMethod(deepEquals("GET")).build().predicates().get(0);
        assertEquals(viaChain, viaBinder);

        Predicate pathViaBinder = path(RiftDsl.equals("/x")).build();
        assertTrue(pathViaBinder.operation() instanceof PredicateOperation.Equals);

        Predicate headerViaBinder = header("H", RiftDsl.equals("v")).build();
        Predicate queryViaBinder = query("q", RiftDsl.equals("v")).build();
        Predicate bodyViaBinder = body(RiftDsl.equals("v")).build();
        assertTrue(headerViaBinder.operation() instanceof PredicateOperation.Equals);
        assertTrue(queryViaBinder.operation() instanceof PredicateOperation.Equals);
        assertTrue(bodyViaBinder.operation() instanceof PredicateOperation.Equals);
    }

    // ------------------------------------------------------------------
    // Combinators
    // ------------------------------------------------------------------

    @Test
    void andCombinator() {
        Predicate predicate = and(method(deepEquals("GET")), path(matches("/x"))).build();
        PredicateOperation.And op = (PredicateOperation.And) predicate.operation();
        assertEquals(2, op.predicates().size());
        assertTrue(op.predicates().get(0).operation() instanceof PredicateOperation.DeepEquals);
        assertTrue(op.predicates().get(1).operation() instanceof PredicateOperation.Matches);
    }

    @Test
    void orCombinator() {
        Predicate predicate = or(path(RiftDsl.equals("/a")), path(RiftDsl.equals("/b"))).build();
        PredicateOperation.Or op = (PredicateOperation.Or) predicate.operation();
        assertEquals(2, op.predicates().size());
    }

    @Test
    void notCombinator() {
        Predicate predicate = not(header("Authorization", exists())).build();
        PredicateOperation.Not op = (PredicateOperation.Not) predicate.operation();
        assertTrue(op.predicate().operation() instanceof PredicateOperation.Exists);
    }

    // ------------------------------------------------------------------
    // Predicate parameters and selectors
    // ------------------------------------------------------------------

    @Test
    void caseSensitiveAndExceptParameters() {
        Predicate predicate = path(RiftDsl.equals("/CaseTest")).caseSensitive(false).keyCaseSensitive(true).except("^/api").build();
        assertEquals(false, predicate.parameters().caseSensitive().orElseThrow());
        assertEquals(true, predicate.parameters().keyCaseSensitive().orElseThrow());
        assertEquals("^/api", predicate.parameters().except());
    }

    @Test
    void jsonPathSelector() {
        Predicate predicate = body(exists()).jsonPath("$.name").build();
        PredicateSelector.JsonPath selector = (PredicateSelector.JsonPath) predicate.parameters().selector().orElseThrow();
        assertEquals("$.name", selector.selector());
    }

    @Test
    void xPathSelectorWithNamespaces() {
        Predicate predicate = body(exists()).xPath("//a:name", Map.of("a", "urn:example")).build();
        PredicateSelector.XPath selector = (PredicateSelector.XPath) predicate.parameters().selector().orElseThrow();
        assertEquals("//a:name", selector.selector());
        assertEquals("urn:example", selector.namespaces().orElseThrow().get("a"));
    }

    // ------------------------------------------------------------------
    // Responses
    // ------------------------------------------------------------------

    @Test
    void okResponse() {
        Response.Is response = (Response.Is) ok().build();
        assertEquals("200", response.is().statusCode());
        assertTrue(response.is().body().isEmpty());
    }

    @Test
    void okJsonResponseSetsContentTypeAndBody() {
        Response.Is response = (Response.Is) okJson("{\"a\":1}").build();
        assertEquals("200", response.is().statusCode());
        assertEquals(List.of("application/json"), response.is().headers().get("Content-Type"));
        assertEquals(RiftDsl.json("{\"a\":1}"), response.is().body().orElseThrow());
    }

    @Test
    void createdNoContentNotFoundStatus() {
        assertEquals("201", ((Response.Is) created().build()).is().statusCode());
        assertEquals("204", ((Response.Is) noContent().build()).is().statusCode());
        assertEquals("404", ((Response.Is) notFound().build()).is().statusCode());
        assertEquals("418", ((Response.Is) status(418).build()).is().statusCode());
    }

    @Test
    void headersAndBodySugar() {
        Response.Is response = (Response.Is) status(200)
                .withHeader("X-A", "1")
                .withHeader("X-B", "2")
                .withTextBody("hello")
                .build();
        assertEquals(List.of("1"), response.is().headers().get("X-A"));
        assertEquals(List.of("2"), response.is().headers().get("X-B"));
        assertEquals(new JsonString("hello"), response.is().body().orElseThrow());
    }

    @Test
    void withJsonBodyFromJsonValueDirectly() {
        JsonValue body = RiftDsl.json("[1,2,3]");
        Response.Is response = (Response.Is) ok().withJsonBody(body).build();
        assertEquals(body, response.is().body().orElseThrow());
    }

    // ------------------------------------------------------------------
    // Behaviors
    // ------------------------------------------------------------------

    @Test
    void waitFixedBehavior() {
        Response.Is response = (Response.Is) ok().waitMs(100).build();
        Behavior.Wait wait = (Behavior.Wait) response.behaviors().entries().get(0);
        WaitSpec.Fixed fixed = (WaitSpec.Fixed) wait.spec();
        assertEquals(100L, fixed.ms());
    }

    @Test
    void afterDurationBehaviorIsWaitFixed() {
        Response.Is response = (Response.Is) ok().after(Duration.ofSeconds(2)).build();
        Behavior.Wait wait = (Behavior.Wait) response.behaviors().entries().get(0);
        WaitSpec.Fixed fixed = (WaitSpec.Fixed) wait.spec();
        assertEquals(2000L, fixed.ms());
    }

    @Test
    void waitRangeBehavior() {
        Response.Is response = (Response.Is) ok().waitBetween(100, 1000).build();
        Behavior.Wait wait = (Behavior.Wait) response.behaviors().entries().get(0);
        WaitSpec.Range range = (WaitSpec.Range) wait.spec();
        assertEquals(100L, range.minMs());
        assertEquals(1000L, range.maxMs());
    }

    @Test
    void waitInjectBehavior() {
        Response.Is response = (Response.Is) ok().waitInject("function() { return 5; }").build();
        Behavior.Wait wait = (Behavior.Wait) response.behaviors().entries().get(0);
        WaitSpec.Inject waitInject = (WaitSpec.Inject) wait.spec();
        assertEquals("function() { return 5; }", waitInject.script());
    }

    @Test
    void decorateBehavior() {
        Response.Is response = (Response.Is) ok().decorate("function(req, res) { res.body = 'x'; }").build();
        Behavior.Decorate decorate = (Behavior.Decorate) response.behaviors().entries().get(0);
        assertEquals("function(req, res) { res.body = 'x'; }", decorate.script());
    }

    @Test
    void repeatBehavior() {
        Response.Is response = (Response.Is) ok().repeat(3).build();
        Behavior.Repeat repeat = (Behavior.Repeat) response.behaviors().entries().get(0);
        assertEquals(3, repeat.count());
    }

    @Test
    void templatedSetsRiftTemplatedFlag() {
        Response.Is response = (Response.Is) ok().templated().build();
        RiftResponseExtension rift = response.rift().orElseThrow();
        assertTrue(rift.templated());
    }

    // ------------------------------------------------------------------
    // Proxy
    // ------------------------------------------------------------------

    @Test
    void proxyOnce() {
        Response.Proxy proxy = (Response.Proxy) proxyTo("http://upstream").proxyOnce().build();
        assertEquals("http://upstream", proxy.proxy().to());
        assertEquals("proxyOnce", proxy.proxy().mode());
    }

    @Test
    void proxyAlwaysAndTransparent() {
        assertEquals("proxyAlways", ((Response.Proxy) proxyTo("http://u").proxyAlways().build()).proxy().mode());
        assertEquals("proxyTransparent", ((Response.Proxy) proxyTo("http://u").proxyTransparent().build()).proxy().mode());
    }

    @Test
    void proxyPredicateGeneratorInjectHeaderAndRewritePath() {
        ProxyResponse proxy = ((Response.Proxy) proxyTo("http://upstream")
                .withPredicateGenerator("{\"matches\":{\"path\":true}}")
                .injectHeader("X-Forwarded-By", "rift")
                .rewritePath("/old", "/new")
                .build()).proxy();

        assertEquals(1, proxy.predicateGenerators().size());
        assertEquals("rift", proxy.injectHeaders().get("X-Forwarded-By"));
        assertEquals("/old", proxy.pathRewrite().orElseThrow().from());
        assertEquals("/new", proxy.pathRewrite().orElseThrow().to());
    }

    // ------------------------------------------------------------------
    // Fault
    // ------------------------------------------------------------------

    @Test
    void connectionFault() {
        Response.Fault response = (Response.Fault) fault(Fault.CONNECTION_RESET_BY_PEER).build();
        assertEquals("CONNECTION_RESET_BY_PEER", response.fault());
    }

    @Test
    void randomDataThenCloseFault() {
        Response.Fault response = (Response.Fault) fault(Fault.RANDOM_DATA_THEN_CLOSE).build();
        assertEquals("RANDOM_DATA_THEN_CLOSE", response.fault());
    }

    @Test
    void latencyFaultIsRiftLatencyOnAnIsResponse() {
        Response.Is response = (Response.Is) ok().withLatencyFault(1.0, Duration.ofMillis(750)).build();
        RiftResponseExtension rift = response.rift().orElseThrow();
        assertEquals(750L, rift.fault().orElseThrow().latency().orElseThrow().ms().orElseThrow());
    }

    @Test
    void errorFaultCarriesResponseHeaders() {
        Response.Is response = (Response.Is) status(200)
                .withErrorFault(1.0, 503, "DOWN", Map.of("Retry-After", "30")).build();
        var error = response.rift().orElseThrow().fault().orElseThrow().error().orElseThrow();
        assertEquals(503, error.status());
        assertEquals("DOWN", error.body().orElseThrow());
        assertEquals("30", error.headers().get("Retry-After"));
    }

    // ------------------------------------------------------------------
    // Inject / script
    // ------------------------------------------------------------------

    @Test
    void injectResponse() {
        Response.Inject response = (Response.Inject) inject("function(req) { return {statusCode: 200}; }").build();
        assertEquals("function(req) { return {statusCode: 200}; }", response.script());
    }

    @Test
    void scriptRhaiInline() {
        Response.RiftScript response = (Response.RiftScript) script(Script.rhai("40 + 2")).build();
        assertEquals("rhai", response.rift().script().orElseThrow().engine().orElseThrow());
        assertEquals("40 + 2", response.rift().script().orElseThrow().code().orElseThrow());
    }

    @Test
    void scriptJsInline() {
        Response.RiftScript response = (Response.RiftScript) script(Script.js("40 + 2")).build();
        assertEquals("js", response.rift().script().orElseThrow().engine().orElseThrow());
    }

    @Test
    void scriptRhaiFile() {
        Response.RiftScript response = (Response.RiftScript) script(Script.rhaiFile("/scripts/a.rhai")).build();
        assertEquals("/scripts/a.rhai", response.rift().script().orElseThrow().file().orElseThrow());
    }

    @Test
    void scriptRef() {
        Response.RiftScript response = (Response.RiftScript) script(Script.ref("myScript")).build();
        assertEquals("myScript", response.rift().script().orElseThrow().ref().orElseThrow());
        assertTrue(response.rift().script().orElseThrow().engine().isEmpty());
    }

    // ------------------------------------------------------------------
    // Scenario FSM
    // ------------------------------------------------------------------

    @Test
    void scenarioFsmTransitionsCarryStates() {
        List<Stub> stubs = scenario("Checkout")
                .startingAt("empty-cart")
                .when("empty-cart", onPost("/cart").withBody(RiftDsl.equals(RiftDsl.json("{\"item\":\"book\"}"))))
                .respond(created())
                .goTo("cart-filled")
                .when("cart-filled", onPost("/checkout"))
                .respond(ok().withTextBody("placed"))
                .goTo("order-placed")
                .stubs();

        assertEquals(2, stubs.size());

        Stub first = stubs.get(0);
        assertEquals("Checkout", first.scenarioName().orElseThrow());
        assertTrue(first.requiredScenarioState().isEmpty(), "the starting state is written as no requiredScenarioState");
        assertEquals("cart-filled", first.newScenarioState().orElseThrow());

        Stub second = stubs.get(1);
        assertEquals("Checkout", second.scenarioName().orElseThrow());
        assertEquals("cart-filled", second.requiredScenarioState().orElseThrow());
        assertEquals("order-placed", second.newScenarioState().orElseThrow());
    }

    @Test
    void bareScenarioLabelSetsOnlyScenarioName() {
        Stub stub = onGet("/features").scenario("FeatureFlags-ListAll").build();
        assertEquals("FeatureFlags-ListAll", stub.scenarioName().orElseThrow());
        assertTrue(stub.requiredScenarioState().isEmpty());
        assertTrue(stub.newScenarioState().isEmpty());
    }

    // ------------------------------------------------------------------
    // Stub openers
    // ------------------------------------------------------------------

    @Test
    void allHttpMethodOpenersSeedCombinedEqualsPredicate() {
        assertMethodSeed(onGet("/x").build(), "GET");
        assertMethodSeed(onPost("/x").build(), "POST");
        assertMethodSeed(onPut("/x").build(), "PUT");
        assertMethodSeed(onDelete("/x").build(), "DELETE");
        assertMethodSeed(onPatch("/x").build(), "PATCH");
        assertMethodSeed(onHead("/x").build(), "HEAD");
        assertMethodSeed(onOptions("/x").build(), "OPTIONS");
    }

    private static void assertMethodSeed(Stub stub, String expectedMethod) {
        PredicateOperation.Equals op = (PredicateOperation.Equals) stub.predicates().get(0).operation();
        assertEquals(new JsonString(expectedMethod), op.fields().get("method"));
        assertEquals(new JsonString("/x"), op.fields().get("path"));
    }

    @Test
    void onRequestHasNoSeedPredicate() {
        assertTrue(onRequest().build().predicates().isEmpty());
    }

    @Test
    void responseCyclingAppendsMultipleResponses() {
        Stub stub = onGet("/x").willReturn(ok().withTextBody("first")).willReturn(ok().withTextBody("second")).build();
        assertEquals(2, stub.responses().size());
    }

    // ------------------------------------------------------------------
    // ImposterDefinition
    // ------------------------------------------------------------------

    @Test
    @SuppressWarnings("deprecation") // pins the wire output of a deprecated, engine-ignored builder
    void imposterBasicProperties() {
        ImposterDefinition imposter = imposter("My ImposterDefinition")
                .port(9999)
                .protocol("https")
                .record()
                .recordMatches()
                .allowCors()
                .defaultResponse(status(500))
                .stub(onGet("/x").willReturn(ok()))
                .build();

        assertEquals("My ImposterDefinition", imposter.name().orElseThrow());
        assertEquals(9999, imposter.port().orElseThrow());
        assertEquals("https", imposter.protocol());
        assertTrue(imposter.recordRequests());
        assertTrue(imposter.recordMatches());
        assertTrue(imposter.allowCors());
        assertEquals(1, imposter.stubs().size());
        IsResponse defaultResponse = imposter.defaultResponse().orElseThrow();
        assertEquals("500", defaultResponse.statusCode());
    }

    @Test
    void imposterWithNoOptionalFlagsDefaultsToFalse() {
        ImposterDefinition imposter = imposter("Plain").build();
        assertFalse(imposter.recordRequests());
        assertFalse(imposter.recordMatches());
        assertFalse(imposter.allowCors());
        assertTrue(imposter.port().isEmpty());
        assertTrue(imposter.stubs().isEmpty());
    }
}
