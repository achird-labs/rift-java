package io.github.achirdlabs.rift.dsl;

import io.github.achirdlabs.rift.json.JsonObject;
import io.github.achirdlabs.rift.json.JsonValue;
import io.github.achirdlabs.rift.model.ImposterDefinition;
import io.github.achirdlabs.rift.model.Response;
import io.github.achirdlabs.rift.model.ResponseMode;
import io.github.achirdlabs.rift.model.Stub;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static io.github.achirdlabs.rift.dsl.RiftDsl.copyFrom;
import static io.github.achirdlabs.rift.dsl.RiftDsl.equalTo;
import static io.github.achirdlabs.rift.dsl.RiftDsl.imposter;
import static io.github.achirdlabs.rift.dsl.RiftDsl.inMemoryFlowState;
import static io.github.achirdlabs.rift.dsl.RiftDsl.jsonPath;
import static io.github.achirdlabs.rift.dsl.RiftDsl.lookupKey;
import static io.github.achirdlabs.rift.dsl.RiftDsl.ok;
import static io.github.achirdlabs.rift.dsl.RiftDsl.onGet;
import static io.github.achirdlabs.rift.dsl.RiftDsl.onRequest;
import static io.github.achirdlabs.rift.dsl.RiftDsl.proxyTo;
import static io.github.achirdlabs.rift.dsl.RiftDsl.redisFlowState;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-output gate for the DSL v2 surface. Where the model is typed (stub fields, imposter config,
 * error/latency faults) we assert on the built model; where a behavior has no typed model variant
 * and rides {@code Behavior.Unknown} (lookup, multi-command shellTransform) we assert the serialized
 * wire JSON shape.
 */
class DslV2WireTest {

    // ---- IsSpec: binary body + templated + faults ----

    @Test
    void binaryBodyEmitsBase64AndBinaryMode() {
        Response.Is is = (Response.Is) ok().withBinaryBody(new byte[]{1, 2, 3}).build();
        assertEquals(ResponseMode.BINARY, is.is().mode());
        assertEquals("AQID", ((io.github.achirdlabs.rift.json.JsonString) is.is().body().orElseThrow()).value());
    }

    @Test
    void templatedSetsRiftTemplated() {
        Response.Is is = (Response.Is) ok().templated().build();
        assertTrue(is.rift().orElseThrow().templated());
    }

    @Test
    void errorFaultEmitsProbabilityAndStatus() {
        Response.Is is = (Response.Is) ok().withErrorFault(0.25, 503).build();
        var error = is.rift().orElseThrow().fault().orElseThrow().error().orElseThrow();
        assertEquals(0.25, error.probability());
        assertEquals(503, error.status());
    }

    @Test
    void latencyFaultEmitsProbabilityAndFixedMs() {
        Response.Is is = (Response.Is) ok().withLatencyFault(1.0, Duration.ofMillis(50)).build();
        var latency = is.rift().orElseThrow().fault().orElseThrow().latency().orElseThrow();
        assertEquals(1.0, latency.probability());
        assertEquals(50L, latency.ms().orElseThrow());
    }

    @Test
    void tcpFaultEmitsFaultName() {
        Response.Is is = (Response.Is) ok().withTcpFault(Fault.CONNECTION_RESET_BY_PEER).build();
        var tcp = is.rift().orElseThrow().fault().orElseThrow().tcp().orElseThrow();
        assertInstanceOf(io.github.achirdlabs.rift.model.RiftTcpFault.Bare.class, tcp, "withTcpFault(Fault) is the bare form");
        assertEquals("CONNECTION_RESET_BY_PEER", tcp.type());
    }

    @Test
    void probabilisticTcpFaultEmitsObjectForm() {
        Response.Is is = (Response.Is) ok().withTcpFault(0.1, Fault.CONNECTION_RESET_BY_PEER).build();
        var tcp = is.rift().orElseThrow().fault().orElseThrow().tcp().orElseThrow();
        var p = assertInstanceOf(io.github.achirdlabs.rift.model.RiftTcpFault.Probabilistic.class, tcp);
        assertEquals(0.1, p.probability());
        assertEquals("CONNECTION_RESET_BY_PEER", p.type());
    }

    // ---- IsSpec: behaviors ----

    @Test
    void copyBehaviorEmitsCopyEntry() {
        Stub stub = onGet("/x")
                .willReturn(ok().copy(copyFrom("path").into("$TOKEN").using(jsonPath("$.id"))))
                .build();
        JsonObject behaviors = behaviorsOf(stub);
        io.github.achirdlabs.rift.json.JsonArray copy =
                (io.github.achirdlabs.rift.json.JsonArray) behaviors.get("copy");
        JsonObject entry = (JsonObject) copy.items().get(0);
        assertEquals("path", ((io.github.achirdlabs.rift.json.JsonString) entry.get("from")).value());
        assertEquals("$TOKEN", ((io.github.achirdlabs.rift.json.JsonString) entry.get("into")).value());
        JsonObject using = (JsonObject) entry.get("using");
        assertEquals("jsonpath", ((io.github.achirdlabs.rift.json.JsonString) using.get("method")).value());
    }

    @Test
    void lookupBehaviorEmitsLookupArray() {
        Stub stub = onGet("/x")
                .willReturn(ok().lookup(lookupKey("code").using(jsonPath("$.code"))
                        .fromCsv("data/products.csv", "code").into("$ROW")))
                .build();
        JsonObject behaviors = behaviorsOf(stub);
        io.github.achirdlabs.rift.json.JsonArray lookup =
                (io.github.achirdlabs.rift.json.JsonArray) behaviors.get("lookup");
        JsonObject entry = (JsonObject) lookup.items().get(0);
        assertTrue(entry.has("key"));
        assertTrue(entry.has("fromDataSource"));
        assertEquals("$ROW", ((io.github.achirdlabs.rift.json.JsonString) entry.get("into")).value());
    }

    @Test
    void shellTransformEmitsCommandArray() {
        Stub stub = onGet("/x").willReturn(ok().shellTransform("./a.sh", "./b.sh")).build();
        JsonObject behaviors = behaviorsOf(stub);
        io.github.achirdlabs.rift.json.JsonArray cmds =
                (io.github.achirdlabs.rift.json.JsonArray) behaviors.get("shellTransform");
        assertEquals(2, cmds.items().size());
    }

    // ---- StubSpec: new fields ----

    @Test
    void stubSpaceIdRouteAndScenarioSugar() {
        Stub stub = onGet("/users/:id")
                .inSpace("flow-1").withId("s1").withRoute("/users/:id")
                .inScenario("cart").whenScenarioState("empty").willSetScenarioState("filled")
                .willReturn(ok()).build();
        assertEquals("flow-1", stub.space().orElseThrow());
        assertEquals("s1", stub.id().orElseThrow());
        assertEquals("/users/:id", stub.routePattern().orElseThrow());
        assertEquals("cart", stub.scenarioName().orElseThrow());
        assertEquals("empty", stub.requiredScenarioState().orElseThrow());
        assertEquals("filled", stub.newScenarioState().orElseThrow());
    }

    // ---- ImposterSpec: config + _rift ----

    @Test
    void imposterHostForwardStrictServiceName() {
        ImposterDefinition def = imposter("svc").host("127.0.0.1").defaultForward("http://up")
                .strictBehaviors().serviceName("payments").build();
        assertEquals("127.0.0.1", def.host().orElseThrow());
        assertEquals("http://up", def.defaultForward().orElseThrow());
        assertTrue(def.strictBehaviors());
        assertEquals("payments", def.serviceName().orElseThrow());
    }

    @Test
    @SuppressWarnings("deprecation") // pins the wire output of a deprecated, engine-ignored builder
    void imposterFlowStateAndMetrics() {
        ImposterDefinition def = imposter("svc")
                .flowState(inMemoryFlowState().ttl(Duration.ofSeconds(120)))
                .metrics(9090).build();
        var rift = def.rift().orElseThrow();
        assertEquals("inmemory", rift.flowState().orElseThrow().backend());
        assertEquals(120L, rift.flowState().orElseThrow().ttlSeconds());
        assertEquals(9090, rift.metrics().orElseThrow().port());
        assertTrue(rift.metrics().orElseThrow().enabled());
    }

    // ---- ProxySpec: typed predicate generators + knobs ----

    @Test
    void proxyGenerateByFieldsAndKnobs() {
        Response.Proxy proxy = (Response.Proxy) proxyTo("http://up").proxyOnce()
                .generateBy(RequestField.METHOD, RequestField.PATH)
                .addWaitBehavior().decorateWith("fn(){}").build();
        assertEquals("proxyOnce", proxy.proxy().mode());
        assertTrue(proxy.proxy().addWaitBehavior());
        assertEquals("fn(){}", proxy.proxy().addDecorateBehavior().orElseThrow());
        JsonObject gen = (JsonObject) proxy.proxy().predicateGenerators().get(0);
        JsonObject matches = (JsonObject) gen.get("matches");
        assertTrue(((io.github.achirdlabs.rift.json.JsonBool) matches.get("method")).value());
        assertTrue(((io.github.achirdlabs.rift.json.JsonBool) matches.get("path")).value());
    }

    // ---- matcher alias ----

    @Test
    void equalToIsAnEqualsMatcher() {
        // equalTo is an alias of equals/eq — it yields an Equals operation (here on a flat body field).
        Stub a = onGet("/x").withBody(equalTo("hi")).willReturn(ok()).build();
        var op = (io.github.achirdlabs.rift.model.PredicateOperation.Equals)
                a.predicates().get(a.predicates().size() - 1).operation();
        assertEquals("hi", ((io.github.achirdlabs.rift.json.JsonString) op.fields().get("body")).value());
    }

    @Test
    void latencyFaultRangeEmitsMinMax() {
        Response.Is is = (Response.Is) ok().withLatencyFault(0.5, Duration.ofMillis(10), Duration.ofMillis(30)).build();
        var latency = is.rift().orElseThrow().fault().orElseThrow().latency().orElseThrow();
        assertEquals(0.5, latency.probability());
        assertEquals(10L, latency.minMs());
        assertEquals(30L, latency.maxMs());
        assertTrue(latency.ms().isEmpty());
    }

    @Test
    void errorFaultWithBody() {
        Response.Is is = (Response.Is) ok().withErrorFault(1.0, 500, "boom").build();
        var error = is.rift().orElseThrow().fault().orElseThrow().error().orElseThrow();
        assertEquals(500, error.status());
        assertEquals("boom", error.body().orElseThrow());
    }

    @Test
    void multipleFaultSettersCompose() {
        Response.Is is = (Response.Is) ok()
                .withErrorFault(0.1, 503).withTcpFault(Fault.CONNECTION_RESET_BY_PEER).build();
        var fault = is.rift().orElseThrow().fault().orElseThrow();
        assertEquals(503, fault.error().orElseThrow().status());
        assertEquals("CONNECTION_RESET_BY_PEER", fault.tcp().orElseThrow().type());
    }

    @Test
    void predicateInjectAddsInjectPredicate() {
        Stub stub = onRequest().withPredicateInject("function(req){return true;}").willReturn(ok()).build();
        var last = stub.predicates().get(stub.predicates().size() - 1).operation();
        assertTrue(last instanceof io.github.achirdlabs.rift.model.PredicateOperation.Inject);
    }

    @Test
    void httpsSetsProtocolAndCertKey() {
        ImposterDefinition def = imposter("svc").https("CERT", "KEY").build();
        assertEquals("https", def.protocol());
        assertEquals("CERT", def.cert().orElseThrow());
        assertEquals("KEY", def.key().orElseThrow());
    }

    @Test
    void serviceInfoIsPreserved() {
        JsonValue info = RiftDsl.json("{\"team\":\"payments\"}");
        ImposterDefinition def = imposter("svc").serviceInfo(info).build();
        assertEquals(info, def.serviceInfo().orElseThrow());
    }

    @Test
    void redisFlowStateWiring() {
        ImposterDefinition def = imposter("svc")
                .flowState(redisFlowState("redis://localhost:6379").poolSize(20).keyPrefix("rift:").ttl(Duration.ofSeconds(60)))
                .build();
        var fs = def.rift().orElseThrow().flowState().orElseThrow();
        assertEquals("redis", fs.backend());
        assertEquals(60L, fs.ttlSeconds());
        assertEquals("redis://localhost:6379", fs.redis().orElseThrow().url());
        assertEquals(20, fs.redis().orElseThrow().poolSize());
    }

    @Test
    @SuppressWarnings("deprecation") // pins the wire output of a deprecated, engine-ignored builder
    void imposterScriptEngineScriptsAndProxyPool() {
        ImposterDefinition def = imposter("svc")
                .scriptEngine(ScriptEngine.RHAI, Duration.ofSeconds(5))
                .script("greet", Script.rhai("respond()"))
                .proxyPool(50, Duration.ofSeconds(30))
                .build();
        var rift = def.rift().orElseThrow();
        assertEquals("rhai", rift.scriptEngine().orElseThrow().defaultEngine());
        assertEquals(5000L, rift.scriptEngine().orElseThrow().timeoutMs());
        assertTrue(rift.scripts().containsKey("greet"));
        assertEquals(50, rift.proxy().orElseThrow().connectionPool().orElseThrow().maxIdlePerHost());
        assertEquals(30L, rift.proxy().orElseThrow().connectionPool().orElseThrow().idleTimeoutSecs());
    }

    @Test
    void proxyGenerateByPredicateGeneratorSpec() {
        Response.Proxy proxy = (Response.Proxy) proxyTo("http://up")
                .generateBy(PredicateGeneratorSpec.create().matching(RequestField.PATH).caseSensitive(true).jsonPath("$.id"))
                .build();
        JsonObject gen = (JsonObject) proxy.proxy().predicateGenerators().get(0);
        assertTrue(((JsonObject) gen.get("matches")).has("path"));
        assertTrue(((io.github.achirdlabs.rift.json.JsonBool) gen.get("caseSensitive")).value());
    }

    private static JsonObject behaviorsOf(Stub stub) {
        JsonObject stubJson = (JsonObject) JsonValue.parse(stub.toJson());
        io.github.achirdlabs.rift.json.JsonArray responses =
                (io.github.achirdlabs.rift.json.JsonArray) stubJson.get("responses");
        JsonObject response = (JsonObject) responses.items().get(0);
        return (JsonObject) response.get("_behaviors");
    }
}
