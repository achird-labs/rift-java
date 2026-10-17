package io.github.achirdlabs.rift.conformance;

import io.github.achirdlabs.rift.dsl.Fault;
import io.github.achirdlabs.rift.dsl.PredicateSpec;
import io.github.achirdlabs.rift.dsl.RiftDsl;
import io.github.achirdlabs.rift.dsl.Script;
import io.github.achirdlabs.rift.dsl.StubSpec;
import io.github.achirdlabs.rift.model.ImposterDefinition;
import io.github.achirdlabs.rift.model.Stub;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static io.github.achirdlabs.rift.dsl.RiftDsl.and;
import static io.github.achirdlabs.rift.dsl.RiftDsl.body;
import static io.github.achirdlabs.rift.dsl.RiftDsl.contains;
import static io.github.achirdlabs.rift.dsl.RiftDsl.copyFrom;
import static io.github.achirdlabs.rift.dsl.RiftDsl.copyFromHeader;
import static io.github.achirdlabs.rift.dsl.RiftDsl.copyFromQuery;
import static io.github.achirdlabs.rift.dsl.RiftDsl.deepEquals;
import static io.github.achirdlabs.rift.dsl.RiftDsl.endsWith;
import static io.github.achirdlabs.rift.dsl.RiftDsl.exists;
import static io.github.achirdlabs.rift.dsl.RiftDsl.header;
import static io.github.achirdlabs.rift.dsl.RiftDsl.imposter;
import static io.github.achirdlabs.rift.dsl.RiftDsl.inMemoryFlowState;
import static io.github.achirdlabs.rift.dsl.RiftDsl.inject;
import static io.github.achirdlabs.rift.dsl.RiftDsl.jsonPath;
import static io.github.achirdlabs.rift.dsl.RiftDsl.lookupKey;
import static io.github.achirdlabs.rift.dsl.RiftDsl.matches;
import static io.github.achirdlabs.rift.dsl.RiftDsl.method;
import static io.github.achirdlabs.rift.dsl.RiftDsl.not;
import static io.github.achirdlabs.rift.dsl.RiftDsl.notExists;
import static io.github.achirdlabs.rift.dsl.RiftDsl.okJson;
import static io.github.achirdlabs.rift.dsl.RiftDsl.onDelete;
import static io.github.achirdlabs.rift.dsl.RiftDsl.onGet;
import static io.github.achirdlabs.rift.dsl.RiftDsl.onPost;
import static io.github.achirdlabs.rift.dsl.RiftDsl.onRequest;
import static io.github.achirdlabs.rift.dsl.RiftDsl.or;
import static io.github.achirdlabs.rift.dsl.RiftDsl.path;
import static io.github.achirdlabs.rift.dsl.RiftDsl.query;
import static io.github.achirdlabs.rift.dsl.RiftDsl.regex;
import static io.github.achirdlabs.rift.dsl.RiftDsl.script;
import static io.github.achirdlabs.rift.dsl.RiftDsl.startsWith;
import static io.github.achirdlabs.rift.dsl.RiftDsl.status;

/**
 * The hand-maintained DSL-expressibility registry: fixture number → the same imposter rebuilt using
 * <em>only</em> the {@link io.github.achirdlabs.rift.dsl.RiftDsl} typed API (never {@code
 * ImposterDefinition.fromJson}). {@link DslExpressibilityTest} asserts each entry's serialized form
 * is {@code semanticEquals} to the corpus fixture (modulo the {@code _verify} annotation).
 *
 * <p>Per issue #7's "inexpressible = red build" rule, every fixture that is runnable in this lane
 * (see {@link FixtureCase#runnableInLane()}) MUST have an entry here; a missing entry fails the gate,
 * forcing the DSL to grow with the engine grammar.
 *
 * <p>{@code RiftDsl.equals(...)} is always called fully qualified — an unqualified {@code equals}
 * resolves to {@link Object#equals} instead (see {@code CorpusExpressibilityTest} in core for why).
 */
final class DslFixtures {

    private static final Map<Integer, Supplier<ImposterDefinition>> REGISTRY;

    static {
        Map<Integer, Supplier<ImposterDefinition>> registry = new HashMap<>();
        registry.put(1, DslFixtures::build01);
        registry.put(2, DslFixtures::build02);
        registry.put(3, DslFixtures::build03);
        registry.put(4, DslFixtures::build04);
        registry.put(5, DslFixtures::build05);
        registry.put(6, DslFixtures::build06);
        registry.put(10, DslFixtures::build10);
        registry.put(11, DslFixtures::build11);
        registry.put(13, DslFixtures::build13);
        registry.put(14, DslFixtures::build14);
        registry.put(15, DslFixtures::build15);
        registry.put(16, DslFixtures::build16);
        registry.put(17, DslFixtures::build17);
        REGISTRY = Map.copyOf(registry);
    }

    private DslFixtures() {}

    static boolean has(int fixtureNumber) {
        return REGISTRY.containsKey(fixtureNumber);
    }

    static Optional<ImposterDefinition> build(int fixtureNumber) {
        return Optional.ofNullable(REGISTRY.get(fixtureNumber)).map(Supplier::get);
    }

    /** Tags a built stub with the corpus's per-stub {@code name} label. */
    private static Stub named(String name, StubSpec stub) {
        return stub.named(name).build();
    }

    private static ImposterDefinition build01() {
        return imposter("01 · Basic REST API")
                .port(4501)
                .protocol("http")
                .record()
                .defaultResponse(status(404)
                        .withHeader("Content-Type", "application/json")
                        .withJsonBody("{\"error\":\"not found\",\"hint\":\"see README for the route list\"}"))
                .stub(List.of(
                        named("Health check",
                                onGet("/health").willReturn(status(200).withTextBody("OK"))),
                        named("List products (JSON array body)",
                                onGet("/api/products").willReturn(okJson(
                                        "[{\"id\":123,\"name\":\"Widget\",\"price\":9.99},{\"id\":456,\"name\":\"Gadget\",\"price\":19.99}]"))),
                        named("Get one product by numeric id (regex path)",
                                onRequest()
                                        .withPredicate(and(method(RiftDsl.equals("GET")), path(matches("^/api/products/\\d+$"))))
                                        .willReturn(okJson("{\"id\":123,\"name\":\"Widget\",\"price\":9.99}"))),
                        named("Create product",
                                onPost("/api/products").willReturn(status(201)
                                        .withHeader("Content-Type", "application/json")
                                        .withHeader("Location", "/api/products/999")
                                        .withJsonBody("{\"id\":999,\"message\":\"created\"}"))),
                        named("Delete product (no content)",
                                onRequest()
                                        .withPredicate(and(method(RiftDsl.equals("DELETE")), path(matches("^/api/products/\\d+$"))))
                                        .willReturn(status(204))),
                        named("Response cycling — three responses served round-robin",
                                onGet("/api/rotating")
                                        .willReturn(status(200).withJsonBody("{\"served\":\"first\"}"))
                                        .willReturn(status(200).withJsonBody("{\"served\":\"second\"}"))
                                        .willReturn(status(200).withJsonBody("{\"served\":\"third\"}")))))
                .build();
    }

    private static ImposterDefinition build02() {
        return imposter("02 · Predicate Showcase")
                .port(4502)
                .protocol("http")
                .record()
                .defaultResponse(status(400)
                        .withHeader("Content-Type", "application/json")
                        .withJsonBody("{\"error\":\"no predicate matched\",\"hint\":\"see README §02 for matching requests\"}"))
                .stub(List.of(
                        named("equals — exact path + method",
                                onGet("/eq").willReturn(status(200).withJsonBody("{\"matched\":\"equals\"}"))),
                        named("equals caseSensitive=false — /CaSe matches /case",
                                onRequest()
                                        .withPredicate(path(RiftDsl.equals("/case")).caseSensitive(false))
                                        .willReturn(status(200).withJsonBody("{\"matched\":\"equals-caseInsensitive\"}"))),
                        named("deepEquals — query must be EXACTLY {a:1}",
                                onRequest()
                                        .withPredicate(query("a", deepEquals("1")).caseSensitive(true))
                                        .willReturn(status(200).withJsonBody("{\"matched\":\"deepEquals\"}"))),
                        named("contains — body contains 'needle'",
                                onRequest()
                                        .withBody(contains("needle"))
                                        .willReturn(status(200).withJsonBody("{\"matched\":\"contains\"}"))),
                        named("startsWith — path starts with /start",
                                onRequest()
                                        .withPath(startsWith("/start"))
                                        .willReturn(status(200).withJsonBody("{\"matched\":\"startsWith\"}"))),
                        named("endsWith — path ends with /end",
                                onRequest()
                                        .withPath(endsWith("/end"))
                                        .willReturn(status(200).withJsonBody("{\"matched\":\"endsWith\"}"))),
                        named("matches — regex path /id/<digits>",
                                onRequest()
                                        .withPath(matches("^/id/[0-9]+$"))
                                        .willReturn(status(200).withJsonBody("{\"matched\":\"matches\"}"))),
                        named("exists — Authorization header present",
                                onRequest()
                                        .withHeader("Authorization", exists())
                                        .willReturn(status(200).withJsonBody("{\"matched\":\"exists-header\"}"))),
                        named("or — path is /red OR /blue",
                                onRequest()
                                        .withPredicate(or(path(RiftDsl.equals("/red")), path(RiftDsl.equals("/blue"))))
                                        .willReturn(status(200).withJsonBody("{\"matched\":\"or\"}"))),
                        named("not — path is /open and has NO Authorization header",
                                onRequest()
                                        .withPredicate(and(path(RiftDsl.equals("/open")), not(header("Authorization", exists()))))
                                        .willReturn(status(200).withJsonBody("{\"matched\":\"not\"}"))),
                        named("jsonpath — JSON body where $.type == 'order'",
                                onRequest()
                                        .withPredicate(body(RiftDsl.equals("order")).jsonPath("$.type"))
                                        .willReturn(status(200).withJsonBody("{\"matched\":\"jsonpath\"}"))),
                        named("xpath — XML body where //user/@role == 'admin'",
                                onRequest()
                                        .withPredicate(body(RiftDsl.equals("admin")).xPath("string(//user/@role)"))
                                        .willReturn(status(200).withJsonBody("{\"matched\":\"xpath\"}"))),
                        pathAndHeaderEquals("keyCaseSensitive=true — stub key X-Tenant matches the Title-Cased header",
                                "/kcs", "X-Tenant", "acme", Optional.empty(), true, "kcs-exact"),
                        pathAndHeaderEquals("keyCaseSensitive=true — stub key x-tenant never matches (listener stores X-Tenant)",
                                "/kcs-nomatch-lower", "x-tenant", "acme", Optional.empty(), true, "kcs-lower"),
                        pathAndHeaderEquals("caseSensitive=true + keyCaseSensitive=false — key relaxed, value strict",
                                "/kcs-override", "x-tenant", "ACME", Optional.of(true), false, "kcs-override")))
                .build();
    }

    /**
     * One {@code equals} over {@code path} and a header, with matcher parameters (rift#1325's
     * {@code keyCaseSensitive} stubs): {@link RiftDsl#merge}, since parameters on an {@code and} would
     * not reach its children.
     */
    private static Stub pathAndHeaderEquals(String name, String path, String header, String value,
            Optional<Boolean> caseSensitive, boolean keyCaseSensitive, String matched) {
        PredicateSpec predicate = RiftDsl.merge(RiftDsl.path(RiftDsl.eq(path)), RiftDsl.header(header, RiftDsl.eq(value)))
                .keyCaseSensitive(keyCaseSensitive);
        if (caseSensitive.isPresent()) {
            predicate = predicate.caseSensitive(caseSensitive.get());
        }
        return named(name, onRequest().withPredicate(predicate)
                .willReturn(status(200).withJsonBody("{\"matched\":\"" + matched + "\"}")));
    }

    private static ImposterDefinition build03() {
        return imposter("03 · Behaviors (wait, repeat, decorate, copy, lookup)")
                .port(4503)
                .protocol("http")
                .record()
                .stub(List.of(
                        named("wait — fixed 1500ms delay",
                                onRequest().withPath(RiftDsl.equals("/slow"))
                                        .willReturn(status(200).withJsonBody("{\"note\":\"delayed 1.5s\"}").waitMs(1500))),
                        named("wait — random delay between 200ms and 1200ms",
                                onRequest().withPath(RiftDsl.equals("/slow-range"))
                                        .willReturn(status(200).withJsonBody("{\"note\":\"delayed 200-1200ms\"}").waitBetween(200, 1200))),
                        named("repeat — fail 503 twice, then 200 (sequence cycles)",
                                onRequest().withPath(RiftDsl.equals("/retry-me"))
                                        .willReturn(status(503).withJsonBody("{\"error\":\"try again\"}").repeat(2))
                                        .willReturn(status(200).withJsonBody("{\"ok\":true}"))),
                        named("decorate — JS post-processes the response (adds a header) [needs --allow-injection]",
                                onRequest().withPath(RiftDsl.equals("/decorated"))
                                        .willReturn(status(200)
                                                .withHeader("Content-Type", "application/json")
                                                .withJsonBody("{\"decorated\":false}")
                                                .decorate("function (request, response) { response.headers['X-Decorated-By'] = 'rift'; response.body = response.body.replace('false', 'true'); }"))),
                        named("copy — pull the numeric id out of the path into the body",
                                onRequest().withPath(matches("^/orders/\\d+$"))
                                        .willReturn(status(200)
                                                .withHeader("Content-Type", "application/json")
                                                .withHeader("X-Order-Id", "${orderId}")
                                                .withJsonBody("{\"orderId\":\"${orderId}\",\"status\":\"shipped\"}")
                                                .copyObject(copyFrom("path").into("${orderId}").using(regex("/orders/(\\d+)"))))),
                        named("copy — pull a query parameter (?user=) into the response",
                                onRequest().withPath(RiftDsl.equals("/greet"))
                                        .willReturn(status(200)
                                                .withHeader("Content-Type", "application/json")
                                                .withJsonBody("{\"greeting\":\"hello ${user}\"}")
                                                .copyObject(copyFromQuery("user").into("${user}").using(regex("(.*)"))))),
                        named("lookup — enrich the response from data/products.csv by id in the path",
                                onRequest().withPath(matches("^/catalog/\\d+$"))
                                        .willReturn(status(200)
                                                .withHeader("Content-Type", "application/json")
                                                .withJsonBody("{\"id\":\"${row}[id]\",\"name\":\"${row}[name]\",\"price\":\"${row}[price]\",\"category\":\"${row}[category]\"}")
                                                .lookupObject(lookupKey("path").using(regex("/catalog/(\\d+)")).fromCsv("data/products.csv", "id").into("${row}"))))))
                .build();
    }

    private static ImposterDefinition build04() {
        return imposter("04 · Fault Injection (_rift.fault)")
                .port(4504)
                .protocol("http")
                .record()
                .stub(List.of(
                        named("Latency — always add 500-2000ms",
                                onRequest().withPath(RiftDsl.equals("/faults/latency"))
                                        .willReturn(status(200)
                                                .withHeader("Content-Type", "application/json")
                                                .withJsonBody("{\"endpoint\":\"/faults/latency\",\"note\":\"random latency 500-2000ms\"}")
                                                .withLatencyFault(1.0, Duration.ofMillis(500), Duration.ofMillis(2000)))),
                        named("Latency — 50% chance of a fixed 1s delay",
                                onRequest().withPath(RiftDsl.equals("/faults/sometimes-slow"))
                                        .willReturn(status(200)
                                                .withHeader("Content-Type", "application/json")
                                                .withJsonBody("{\"endpoint\":\"/faults/sometimes-slow\",\"note\":\"50% chance of +1000ms\"}")
                                                .withLatencyFault(0.5, Duration.ofMillis(1000)))),
                        named("Error — 30% chance of a 503 instead of the happy body",
                                onRequest().withPath(RiftDsl.equals("/faults/flaky"))
                                        .willReturn(status(200)
                                                .withHeader("Content-Type", "application/json")
                                                .withJsonBody("{\"endpoint\":\"/faults/flaky\",\"note\":\"30% chance of 503\"}")
                                                .withErrorFault(0.3, 503, "{\"error\":\"service temporarily unavailable\",\"code\":\"FLAKY\"}"))),
                        named("Combined — 70% latency AND 20% error (chaos)",
                                onRequest().withPath(RiftDsl.equals("/faults/chaos"))
                                        .willReturn(status(200)
                                                .withHeader("Content-Type", "application/json")
                                                .withJsonBody("{\"endpoint\":\"/faults/chaos\",\"note\":\"70% latency + 20% error\"}")
                                                .withLatencyFault(0.7, Duration.ofMillis(200), Duration.ofMillis(800))
                                                .withErrorFault(0.2, 500, "{\"error\":\"internal chaos\"}"))),
                        named("TCP — reset the connection (no HTTP response at all)",
                                onRequest().withPath(RiftDsl.equals("/faults/tcp-reset"))
                                        .willReturn(status(200)
                                                .withTextBody("you will never see this")
                                                .withTcpFault(Fault.CONNECTION_RESET_BY_PEER))),
                        named("TCP — reset the connection 10% of the time (probabilistic object form)",
                                onRequest().withPath(RiftDsl.equals("/faults/tcp-flaky"))
                                        .willReturn(status(200)
                                                .withHeader("Content-Type", "application/json")
                                                .withJsonBody("{\"endpoint\":\"/faults/tcp-flaky\",\"note\":\"10% chance of a connection reset\"}")
                                                .withTcpFault(0.1, Fault.CONNECTION_RESET_BY_PEER))),
                        named("Baseline — no fault, for comparison",
                                onRequest().withPath(RiftDsl.equals("/faults/healthy"))
                                        .willReturn(status(200)
                                                .withHeader("Content-Type", "application/json")
                                                .withJsonBody("{\"status\":\"healthy\",\"note\":\"no faults injected\"}")))))
                .build();
    }

    private static ImposterDefinition build05() {
        return imposter("05 · Scripting Engines (rhai, javascript)")
                .port(4505)
                .protocol("http")
                .record()
                .flowState(inMemoryFlowState().ttl(Duration.ofSeconds(600)))
                .stub(List.of(
                        named("Rhai — stateful counter (ctx.state)",
                                onGet("/rhai/counter").willReturn(script(Script.rhai(
                                        "fn respond(ctx) { let count = ctx.state.incr_by(\"count\", 1); http(200, #{engine: \"rhai\", count: count}) }")))),
                        named("JavaScript inject — counter [needs --allow-injection]",
                                onGet("/js/counter").willReturn(inject(
                                        "function (config, state) { state.count = (state.count || 0) + 1; return { statusCode: 200, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ engine: 'javascript', count: state.count }) }; }"))),
                        named("JavaScript inject — echo the request [needs --allow-injection]",
                                onPost("/js/echo").willReturn(inject(
                                        "function (config, state) { var req = config.request || config; return { statusCode: 200, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ engine: 'javascript', method: req.method, path: req.path, body: req.body }) }; }"))),
                        named("Health — lists the engines this imposter exercises",
                                onGet("/health").willReturn(okJson("{\"status\":\"ok\",\"engines\":[\"rhai\",\"javascript\"]}")))))
                .build();
    }

    private static ImposterDefinition build06() {
        return imposter("06 · Stateful Retry Simulation (flow state)")
                .port(4506)
                .protocol("http")
                .record()
                .flowState(inMemoryFlowState().flowIdFromHeader("X-Flow-Id"))
                .stub(List.of(
                        named("Fail the first 2 attempts with 503, succeed on the 3rd (keyed by X-Flow-Id)",
                                onGet("/api/resource").willReturn(script(Script.rhai(
                                        "fn respond(ctx) { let attempts = ctx.state.incr_by(\"attempts\", 1); if attempts <= 2 { http(503, #{error: \"service unavailable\", attempt: attempts}).header(\"Retry-After\", \"1\").header(\"X-Attempt\", `${attempts}`) } else { http(200, #{ok: true, succeededOnAttempt: attempts}).header(\"X-Attempt\", `${attempts}`) } }")))),
                        named("Inspect the current attempt count for a flow",
                                onGet("/api/attempts").willReturn(script(Script.rhai(
                                        "fn respond(ctx) { let flow_id = ctx.request.header(\"x-flow-id\"); if flow_id == () { flow_id = \"default\"; }; let attempts = ctx.state.get_or(\"attempts\", 0); http(200, #{flowId: flow_id, attempts: attempts}) }")))),
                        named("Reset the attempt counter for a flow",
                                onDelete("/api/reset").willReturn(script(Script.rhai(
                                        "fn respond(ctx) { let flow_id = ctx.request.header(\"x-flow-id\"); if flow_id == () { flow_id = \"default\"; }; ctx.state.delete(\"attempts\"); http(200, #{message: \"reset\", flowId: flow_id}) }"))))))
                .build();
    }

    private static ImposterDefinition build10() {
        return imposter("10 · Correlated isolation (one port, partitioned by a header)")
                .port(4510)
                .protocol("http")
                .record()
                .flowState(inMemoryFlowState().flowIdFromHeader("X-Mock-Space"))
                .stub(List.of(
                        named("space=alice — only matches requests with X-Mock-Space: alice",
                                onRequest().withPath(RiftDsl.equals("/data")).inSpace("alice")
                                        .willReturn(status(200).withJsonBody("{\"owner\":\"alice\",\"items\":[1,2]}"))),
                        named("space=bob — isolated from alice",
                                onRequest().withPath(RiftDsl.equals("/data")).inSpace("bob")
                                        .willReturn(status(200).withJsonBody("{\"owner\":\"bob\",\"items\":[9]}"))),
                        named("global (no space) — matches any caller",
                                onRequest().withPath(RiftDsl.equals("/health"))
                                        .willReturn(status(200).withTextBody("OK")))))
                .build();
    }

    private static ImposterDefinition build11() {
        return imposter("11 · Multi-value headers, date templates, defaultResponse/defaultForward, CORS")
                .port(4511)
                .protocol("http")
                .allowCors()
                .defaultForward("http://localhost:4501")
                .stub(List.of(
                        named("multi-value headers — two Set-Cookie lines on the wire (#238)",
                                onRequest().withPath(RiftDsl.equals("/cookies"))
                                        .willReturn(status(200)
                                                .withHeader("Set-Cookie", "sessionId=abc", "theme=dark")
                                                .withHeader("X-Single", "one")
                                                .withTextBody("two cookies set"))),
                        named("serve-time date templates — {{DAYS+N}}/{{MONTHS+N}}/{{NOW}} (#195)",
                                onRequest().withPath(RiftDsl.equals("/token"))
                                        .willReturn(status(200)
                                                .withHeader("Content-Type", "application/json")
                                                .withTextBody("{\"issued\":\"{{NOW}}\",\"expires\":\"{{DAYS+30}}\",\"renewal\":\"{{MONTHS+12}}\"}")))))
                .build();
    }

    private static ImposterDefinition build13() {
        return imposter("13 · decorate conventions — Mountebank JS `config =>` and Rhai (#191)")
                .port(4513)
                .protocol("http")
                .stub(List.of(
                        named("Mountebank JS arrow: config => { config.response... }",
                                onRequest().withPath(RiftDsl.equals("/js-arrow"))
                                        .willReturn(status(200).withTextBody("{\"v\":1}")
                                                .decorate("config => { config.response.headers['X-Decorated-By'] = 'config-arrow'; config.response.statusCode = 202; }"))),
                        named("Mountebank JS function(config) form — reads config.request",
                                onRequest().withPath(RiftDsl.equals("/js-fn"))
                                        .willReturn(status(200).withTextBody("replaced")
                                                .decorate("function(config) { config.response.body = 'method-was-' + config.request.method; }"))),
                        named("Rhai decorate (Rift-native) — unchanged",
                                onRequest().withPath(RiftDsl.equals("/rhai"))
                                        .willReturn(status(200).withTextBody("orig")
                                                .decorate("response.body = \"rhai-\" + request.path;")))))
                .build();
    }

    private static ImposterDefinition build14() {
        return imposter("14 · _verify annotations (rift-verify --verify-dynamic)")
                .port(4514)
                .protocol("http")
                .stub(List.of(
                        named("repeat:2 cycle, asserted by a _verify sequence",
                                onRequest().withPath(RiftDsl.equals("/retry"))
                                        .willReturn(status(503).withTextBody("unavailable").repeat(2))
                                        .willReturn(status(200).withTextBody("ok"))),
                        named("copy (request-derived) decorate, asserted by _verify",
                                onRequest().withPath(matches("^/orders/[0-9]+$"))
                                        .willReturn(status(200).withTextBody("id=${ID}")
                                                .copyObject(copyFrom("path").into("${ID}").using(regex("/orders/([0-9]+)")))))))
                .build();
    }

    private static ImposterDefinition build15() {
        return imposter("15 · Predicate modifiers (issue #254 §D1)")
                .port(4515)
                .protocol("http")
                .record()
                .defaultResponse(status(404)
                        .withHeader("Content-Type", "application/json")
                        .withJsonBody("{\"error\":\"no predicate matched\"}"))
                .stub(List.of(
                        named("contains — body contains 'needle'",
                                onRequest().withBody(contains("needle"))
                                        .willReturn(status(200).withTextBody("contains"))),
                        named("endsWith — path ends with /end",
                                onRequest().withPath(endsWith("/end"))
                                        .willReturn(status(200).withTextBody("endsWith"))),
                        named("except — strip digits before comparing path to /v",
                                onRequest().withPredicate(path(RiftDsl.equals("/v")).except("[0-9]+"))
                                        .willReturn(status(200).withTextBody("except"))),
                        named("deepEquals — query must be EXACTLY {a:1} (extra keys rejected)",
                                onRequest().withPredicate(query("a", deepEquals("1")))
                                        .willReturn(status(200).withTextBody("deep"))),
                        named("exists:false — header X-Skip must be ABSENT",
                                onRequest().withPredicate(header("X-Skip", notExists())).withPath(RiftDsl.equals("/noskip"))
                                        .willReturn(status(200).withTextBody("absent")))))
                .build();
    }

    private static ImposterDefinition build16() {
        return imposter("16 · Advanced behaviors (issue #254 §D3)")
                .port(4516)
                .protocol("http")
                .record()
                .stub(List.of(
                        named("copy — from a request header into the body",
                                onRequest().withPath(RiftDsl.equals("/from-header"))
                                        .willReturn(status(200)
                                                .withHeader("Content-Type", "application/json")
                                                .withJsonBody("{\"requestId\":\"${rid}\"}")
                                                .copyObject(copyFromHeader("X-Request-Id").into("${rid}").using(regex(".*"))))),
                        named("copy — extract a JSON field from the body via jsonpath",
                                onRequest().withPath(RiftDsl.equals("/from-json"))
                                        .willReturn(status(200).withTextBody("who=${who}")
                                                .copyObject(copyFrom("body").into("${who}").using(jsonPath("$.user.name"))))),
                        named("lookup — enrich the response from products.csv by id",
                                onRequest().withPath(matches("^/catalog/[0-9]+$"))
                                        .willReturn(status(200)
                                                .withHeader("Content-Type", "application/json")
                                                .withJsonBody("{\"name\":\"${row}[name]\",\"price\":\"${row}[price]\"}")
                                                .lookupObject(lookupKey("path").using(regex("/catalog/([0-9]+)")).fromCsv("data/products.csv", "id").into("${row}")))),
                        named("wait — JS function returning a fixed delay",
                                onRequest().withPath(RiftDsl.equals("/slow-fn"))
                                        .willReturn(status(200).withTextBody("waited").waitScript("function(){ return 400; }"))),
                        named("decorate — Rhai native, mutate response body in place",
                                onRequest().withPath(RiftDsl.equals("/decorate-rhai"))
                                        .willReturn(status(200).withTextBody("orig")
                                                .decorate("response.body = \"rhai-decorated\";")))))
                .build();
    }

    private static ImposterDefinition build17() {
        return imposter("17 · Extended faults — error body/headers, latency range (issue #254 §D5)")
                .port(4517)
                .protocol("http")
                .record()
                .stub(List.of(
                        named("error fault — custom status, body and headers",
                                onRequest().withPath(RiftDsl.equals("/err"))
                                        .willReturn(status(200).withErrorFault(1.0, 503, "DOWN", Map.of("Retry-After", "30")))),
                        named("latency fault — random range 300-600ms",
                                onRequest().withPath(RiftDsl.equals("/slow"))
                                        .willReturn(status(200).withTextBody("slow")
                                                .withLatencyFault(1.0, Duration.ofMillis(300), Duration.ofMillis(600))))))
                .build();
    }
}
