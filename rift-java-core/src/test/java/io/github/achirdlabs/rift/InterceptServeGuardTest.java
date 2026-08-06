package io.github.achirdlabs.rift;

import io.github.achirdlabs.rift.dsl.Fault;
import io.github.achirdlabs.rift.dsl.IsSpec;
import io.github.achirdlabs.rift.error.InvalidDefinition;
import io.github.achirdlabs.rift.json.JsonString;
import io.github.achirdlabs.rift.json.JsonValue;
import io.github.achirdlabs.rift.model.Behaviors;
import io.github.achirdlabs.rift.model.IsResponse;
import io.github.achirdlabs.rift.model.Response;
import io.github.achirdlabs.rift.model.ResponseMode;
import io.github.achirdlabs.rift.model.RiftFaultConfig;
import io.github.achirdlabs.rift.model.RiftResponseExtension;
import io.github.achirdlabs.rift.model.RiftScriptConfig;
import io.github.achirdlabs.rift.transport.RiftTransport;
import io.github.achirdlabs.rift.transport.StubAddress;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.lang.reflect.RecordComponent;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static io.github.achirdlabs.rift.dsl.RiftDsl.copyFromQuery;
import static io.github.achirdlabs.rift.dsl.RiftDsl.lookupKey;
import static io.github.achirdlabs.rift.dsl.RiftDsl.okJson;
import static io.github.achirdlabs.rift.dsl.RiftDsl.onGet;
import static io.github.achirdlabs.rift.dsl.RiftDsl.regex;
import static io.github.achirdlabs.rift.dsl.RiftDsl.status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Intercept {@code serve} rejects what its wire action cannot deliver (#207).
 *
 * <p>The engine's intercept {@code ServeStub} carries only {@code statusCode}, <em>single-valued</em>
 * {@code headers} and {@code body} — and its deserializer does not use {@code deny_unknown_fields},
 * so anything extra the SDK posted would be accepted with a {@code 200} and then silently ignored.
 * That is the failure this guard exists to prevent: a fault-injection test written against a
 * {@code serve} rule looked green while asserting on a success response the author never asked for.
 */
class InterceptServeGuardTest {

    private CapturingTransport transport;

    private InterceptImpl intercept() {
        transport = new CapturingTransport();
        return new InterceptImpl(transport, "127.0.0.1", 9000);
    }

    private InvalidDefinition rejected(IsSpec response) {
        InterceptImpl intercept = intercept();
        InvalidDefinition thrown =
                assertThrows(InvalidDefinition.class, () -> intercept.serve("example.com", response));
        assertTrue(transport.rules.isEmpty(),
                "a rejected serve rule must never reach the engine, but " + transport.rules.size() + " was registered");
        assertTrue(thrown.getMessage().contains("redirectTo(imposter)"),
                "the message must point at the full-fidelity alternative: " + thrown.getMessage());
        return thrown;
    }

    // --- AC1: every construct in the issue's reproduction table is rejected, and registers nothing ---

    @Test
    void rejectsTcpFault() {
        // The row confirmed behaviourally against a live engine: it answered 200 with the body, no reset.
        assertTrue(rejected(status(200).withTextBody("b").withTcpFault(Fault.CONNECTION_RESET_BY_PEER))
                .getMessage().contains("_rift.fault.tcp"));
    }

    @Test
    void rejectsLatencyFault() {
        assertTrue(rejected(status(200).withTextBody("b").withLatencyFault(1.0, Duration.ofMillis(500)))
                .getMessage().contains("_rift.fault.latency"));
    }

    @Test
    void rejectsErrorFault() {
        assertTrue(rejected(status(200).withTextBody("b").withErrorFault(1.0, 503))
                .getMessage().contains("_rift.fault.error"));
    }

    @Test
    void rejectsTemplated() {
        assertTrue(rejected(status(200).withTextBody("b").templated())
                .getMessage().contains("_rift.templated"));
    }

    @Test
    void rejectsWaitBehavior() {
        assertTrue(rejected(status(200).withTextBody("b").after(Duration.ofMillis(50)))
                .getMessage().contains("_behaviors.wait"));
    }

    @Test
    void rejectsDecorateBehavior() {
        assertTrue(rejected(status(200).withTextBody("b").decorate("function () {}"))
                .getMessage().contains("_behaviors.decorate"));
    }

    @Test
    void rejectsRepeatBehavior() {
        assertTrue(rejected(status(200).withTextBody("b").repeat(3))
                .getMessage().contains("_behaviors.repeat"));
    }

    @Test
    void rejectsShellTransformBehavior() {
        assertTrue(rejected(status(200).withTextBody("b").shellTransform("cat"))
                .getMessage().contains("_behaviors.shellTransform"));
    }

    @Test
    void rejectsCopyBehavior() {
        assertTrue(rejected(status(200).withTextBody("b")
                .copy(copyFromQuery("id").using(regex("(.+)")).into("${id}")))
                .getMessage().contains("_behaviors.copy"));
    }

    @Test
    void rejectsLookupBehavior() {
        assertTrue(rejected(status(200).withTextBody("b")
                .lookup(lookupKey("path").using(regex("(.+)")).fromCsv("/tmp/x.csv", "id").into("${row}")))
                .getMessage().contains("_behaviors.lookup"));
    }

    @Test
    void rejectsBinaryBody() {
        // Without the guard this reached the client as the body's base64 *text*, not the bytes.
        assertTrue(rejected(status(200).withBinaryBody("bytes".getBytes(StandardCharsets.UTF_8)))
                .getMessage().contains("binary body"));
    }

    @Test
    void rejectsMultiValuedHeader() {
        // Without the guard withHeader(name, a, b) silently became `name: a`.
        assertTrue(rejected(status(200).withHeader("Set-Cookie", "a=1", "b=2").withTextBody("b"))
                .getMessage().contains("'Set-Cookie'"));
    }

    @Test
    void rejectsThroughTheRuleBuilderToo() {
        // serve(host, response) and rule()...serve(response) must share the guard.
        InterceptImpl intercept = intercept();
        InvalidDefinition thrown = assertThrows(InvalidDefinition.class,
                () -> intercept.rule().host("example.com").when(onGet("/health"))
                        .serve(status(200).withTcpFault(Fault.CONNECTION_RESET_BY_PEER)));
        assertTrue(thrown.getMessage().contains("_rift.fault.tcp"), thrown.getMessage());
        assertTrue(transport.rules.isEmpty(), "a rejected builder rule must never reach the engine");
    }

    // --- AC2: one exception naming every offender, not one round-trip per construct ---

    @Test
    void namesEveryOffendingConstructInOneMessage() {
        String message = rejected(status(200)
                .withHeader("Set-Cookie", "a=1", "b=2")
                .withBinaryBody("bytes".getBytes(StandardCharsets.UTF_8))
                .after(Duration.ofMillis(50))
                .repeat(3)
                .templated()
                .withTcpFault(Fault.CONNECTION_RESET_BY_PEER))
                .getMessage();

        for (String expected : List.of(
                "_behaviors.wait", "_behaviors.repeat", "_rift.templated", "_rift.fault.tcp",
                "binary body", "'Set-Cookie'")) {
            assertTrue(message.contains(expected), "missing '" + expected + "' in: " + message);
        }
    }

    /**
     * The guard is total over {@link Response.Is}, not just over what {@link IsSpec} can currently
     * build: {@code _rift.script} and unknown top-level keys have no DSL entry point today, so they
     * are driven through the guard directly rather than left as an untested branch.
     */
    @Test
    void rejectsConstructsTheDslCannotYetBuild() {
        Response.Is withScript = new Response.Is(
                new IsResponse("200", Map.of(), Optional.of(new JsonString("b")), ResponseMode.TEXT),
                Behaviors.EMPTY,
                Optional.of(new RiftResponseExtension(
                        Optional.empty(),
                        Optional.of(new RiftScriptConfig(
                                Optional.empty(), Optional.of("return 1;"), Optional.empty(), Optional.empty())),
                        false)));
        assertTrue(assertThrows(InvalidDefinition.class, () -> InterceptImpl.requireDeliverable(withScript))
                .getMessage().contains("_rift.script"));

        Response.Is withIsExtra = new Response.Is(
                new IsResponse("200", Map.of(), Optional.of(new JsonString("b")), ResponseMode.TEXT,
                        Map.of("_futureKnob", new JsonString("x"))),
                Behaviors.EMPTY,
                Optional.empty());
        assertTrue(assertThrows(InvalidDefinition.class, () -> InterceptImpl.requireDeliverable(withIsExtra))
                .getMessage().contains("is response key '_futureKnob'"));

        // The top-level sibling-key escape hatch, distinct from the one inside `is` above: reachable
        // by feeding a rule read back from an engine that grew a new response-level key.
        Response.Is withResponseExtra = new Response.Is(
                new IsResponse("200", Map.of(), Optional.of(new JsonString("b")), ResponseMode.TEXT),
                Behaviors.EMPTY,
                Optional.empty(),
                Map.of("_futureSibling", new JsonString("x")));
        assertTrue(assertThrows(InvalidDefinition.class, () -> InterceptImpl.requireDeliverable(withResponseExtra))
                .getMessage().contains("response key '_futureSibling'"));
    }

    /**
     * Structural backstop for the guard's positive enumeration (#207).
     *
     * <p>{@code requireDeliverable} hand-lists what to reject, so a new component on any of these
     * model types would be neither delivered by {@code toServeStub} nor rejected by the guard — it
     * would just be dropped, silently reopening this very bug with nothing failing. Pinning the
     * component sets turns that into a build failure that names the method to update.
     */
    @Test
    void guardCoversEveryComponentOfTheModelItInspects() {
        assertComponents(Response.Is.class, "is", "behaviors", "rift", "extra");
        assertComponents(IsResponse.class, "statusCode", "headers", "body", "mode", "extra");
        assertComponents(RiftResponseExtension.class, "fault", "script", "templated");
        assertComponents(RiftFaultConfig.class, "latency", "error", "tcp");
        assertComponents(Behaviors.class, "entries");
        // The guard tests `mode() == BINARY`, so a third mode would pass through as if it were text.
        assertEquals(List.of("TEXT", "BINARY"),
                Stream.of(ResponseMode.values()).map(Enum::name).toList(),
                "ResponseMode gained a value — InterceptImpl.requireDeliverable must classify it (#207)");
    }

    private static void assertComponents(Class<?> record, String... expected) {
        assertEquals(List.of(expected),
                Stream.of(record.getRecordComponents()).map(RecordComponent::getName).toList(),
                record.getSimpleName() + " changed shape — every component must be either emitted by"
                        + " InterceptImpl.toServeStub or rejected by requireDeliverable (#207)");
    }

    // --- AC3: the accepted set is unchanged, byte for byte ---

    @Test
    void acceptsPlainServeRuleWithUnchangedWireFormat() {
        InterceptImpl intercept = intercept();
        intercept.serve("example.com", status(201).withHeader("Content-Type", "text/plain").withTextBody("b"));

        assertEquals(1, transport.rules.size());
        assertEquals(
                "{\"host\":\"example.com\",\"action\":{\"serve\":{\"statusCode\":201,"
                        + "\"headers\":{\"Content-Type\":\"text/plain\"},\"body\":\"b\"}}}",
                transport.rules.get(0).toJson());
    }

    @Test
    void acceptsJsonBodyAndSeveralDistinctSingleValuedHeaders() {
        InterceptImpl intercept = intercept();
        intercept.serve("example.com", okJson("{\"a\":1}").withHeader("X-One", "1").withHeader("X-Two", "2"));

        assertEquals(1, transport.rules.size());
        String json = transport.rules.get(0).toJson();
        assertTrue(json.contains("\"X-One\":\"1\""), json);
        assertTrue(json.contains("\"X-Two\":\"2\""), json);
        assertTrue(json.contains("\"body\":\"{\\\"a\\\":1}\""), json);
    }

    @Test
    void acceptsAHeaderWithNoValues() {
        // Pre-existing behaviour: an empty value list emits no header entry, matching
        // IsResponse.writeHeaders. It is not "more than one value", so the guard must leave it alone.
        InterceptImpl intercept = intercept();
        intercept.serve("example.com", status(200).withHeader("X-Empty"));

        String json = transport.rules.get(0).toJson();
        assertFalse(json.contains("X-Empty"), json);
    }

    @Test
    void acceptsAPlainRuleWithNoHeadersAtAll() {
        InterceptImpl intercept = intercept();
        intercept.serve("example.com", status(200));
        assertEquals("{\"host\":\"example.com\",\"action\":{\"serve\":{\"statusCode\":200}}}",
                transport.rules.get(0).toJson());
    }

    // --- AC6: the forward/redirect actions are untouched ---

    @Test
    void forwardIsUnaffected() {
        InterceptImpl intercept = intercept();
        intercept.forward("payments.internal", "localhost:9443");
        assertEquals("{\"host\":\"payments.internal\",\"action\":{\"forward\":{\"port\":9443}}}",
                transport.rules.get(0).toJson());
    }

    @Test
    void redirectToIsUnaffectedEvenForAResponseTheGuardWouldReject() {
        // redirectTo is the alternative the rejection message points at, so it must not acquire the
        // guard: it reaches a real imposter, which has full stub fidelity.
        InterceptImpl intercept = intercept();
        intercept.redirectTo("api.partner.com", imposterOnPort(7070));
        assertEquals("{\"host\":\"api.partner.com\",\"action\":{\"forward\":{\"port\":7070}}}",
                transport.rules.get(0).toJson());
    }

    /**
     * {@link Imposter} has ~40 methods and {@code redirectTo} only ever reads {@code port()}, so a
     * proxy is the honest stub here: any other call fails loudly instead of silently returning null.
     */
    private static Imposter imposterOnPort(int port) {
        return (Imposter) Proxy.newProxyInstance(
                Imposter.class.getClassLoader(),
                new Class<?>[] {Imposter.class},
                (proxy, method, args) -> {
                    if ("port".equals(method.getName())) {
                        return port;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    /** Records every rule handed to the transport, so "a rejected rule registers nothing" is observable. */
    private static final class CapturingTransport extends ThrowingTransport {
        final List<JsonValue> rules = new ArrayList<>();

        @Override
        public void interceptAddRules(JsonValue rule) {
            rules.add(rule);
        }
    }

    private static class ThrowingTransport implements RiftTransport {
        @Override public JsonValue createImposter(JsonValue def) { throw new UnsupportedOperationException(); }
        @Override public JsonValue getImposter(int port) { throw new UnsupportedOperationException(); }
        @Override public JsonValue getImposter(int port, boolean replayable, boolean removeProxies) { throw new UnsupportedOperationException(); }
        @Override public void deleteImposter(int port) { throw new UnsupportedOperationException(); }
        @Override public void deleteAll() { throw new UnsupportedOperationException(); }
        @Override public JsonValue listImposters(boolean r, boolean p) { throw new UnsupportedOperationException(); }
        @Override public void replaceAllImposters(JsonValue doc) { throw new UnsupportedOperationException(); }
        @Override public JsonValue applyConfig(JsonValue config) { throw new UnsupportedOperationException(); }
        @Override public void addStub(int port, JsonValue stub) { throw new UnsupportedOperationException(); }
        @Override public void replaceStubs(int port, JsonValue stubs) { throw new UnsupportedOperationException(); }
        @Override public void replaceStub(int port, StubAddress a, JsonValue s) { throw new UnsupportedOperationException(); }
        @Override public void deleteStub(int port, StubAddress a) { throw new UnsupportedOperationException(); }
        @Override public JsonValue recorded(int port) { throw new UnsupportedOperationException(); }
        @Override public void clearRecorded(int port) { throw new UnsupportedOperationException(); }
        @Override public void clearProxyResponses(int port) { throw new UnsupportedOperationException(); }
        @Override public void enable(int port) { throw new UnsupportedOperationException(); }
        @Override public void disable(int port) { throw new UnsupportedOperationException(); }
        @Override public JsonValue scenarios(int port, Optional<String> f) { throw new UnsupportedOperationException(); }
        @Override public void setScenarioState(int port, String n, String s, Optional<String> f) { throw new UnsupportedOperationException(); }
        @Override public void resetScenarios(int port) { throw new UnsupportedOperationException(); }
        @Override public Optional<JsonValue> flowStateGet(int port, String f, String k) { throw new UnsupportedOperationException(); }
        @Override public void flowStatePut(int port, String f, String k, JsonValue v) { throw new UnsupportedOperationException(); }
        @Override public void flowStateDelete(int port, String f, String k) { throw new UnsupportedOperationException(); }
        @Override public void spaceAddStub(int port, String f, JsonValue s) { throw new UnsupportedOperationException(); }
        @Override public JsonValue spaceListStubs(int port, String f) { throw new UnsupportedOperationException(); }
        @Override public JsonValue spaceRecorded(int port, String f) { throw new UnsupportedOperationException(); }
        @Override public void spaceDelete(int port, String f) { throw new UnsupportedOperationException(); }
        @Override public JsonValue buildInfo() { throw new UnsupportedOperationException(); }
        @Override public URI adminUri() { throw new UnsupportedOperationException(); }
        @Override public JsonValue startIntercept(JsonValue o) { throw new UnsupportedOperationException(); }
        @Override public void interceptAddRules(JsonValue r) { throw new UnsupportedOperationException(); }
        @Override public JsonValue interceptListRules() { throw new UnsupportedOperationException(); }
        @Override public void interceptClearRules() { throw new UnsupportedOperationException(); }
        @Override public String interceptCaPem() { throw new UnsupportedOperationException(); }
        @Override public void close() { }
    }
}
