package io.github.achirdlabs.rift.model;

import io.github.achirdlabs.rift.json.JsonNumber;
import io.github.achirdlabs.rift.json.JsonObject;
import io.github.achirdlabs.rift.json.JsonString;
import io.github.achirdlabs.rift.json.JsonValue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behaviors on {@code proxy} and {@code inject} responses are typed, not opaque {@code extra} (#215).
 * The engine runs them from v0.18.0, so they are read into {@link Behaviors} and written back through
 * the same writer {@code is} uses — including the engine's own {@code GET} shape, which is the
 * {@code behaviors} array plus a response-level {@code repeat}.
 */
class ProxyInjectBehaviorsTest {

    @Test
    void proxyObjectFormIsTyped() {
        Response.Proxy proxy = assertInstanceOf(Response.Proxy.class, first("""
                {"proxy": {"to": "http://up"}, "_behaviors": {"wait": 100, "decorate": "d"}}
                """));
        assertEquals(List.of(new Behavior.Wait(new WaitSpec.Fixed(100)), new Behavior.Decorate("d")),
                proxy.behaviors().entries());
        assertTrue(proxy.extra().isEmpty(), "a typed block is not also carried in extra: " + proxy.extra());
    }

    @Test
    void injectObjectFormIsTyped() {
        Response.Inject inject = assertInstanceOf(Response.Inject.class, first("""
                {"inject": "function () {}", "_behaviors": {"repeat": 3}}
                """));
        assertEquals(List.of(new Behavior.Repeat(3)), inject.behaviors().entries());
        assertTrue(inject.extra().isEmpty());
    }

    @Test
    void engineGetShapeOnProxyIsTypedAndWrittenBackUnmoved() {
        // What rift >= 0.18.0 returns from GET /imposters: the array form, and repeat beside proxy.
        String json = """
                {"proxy": {"to": "http://up", "mode": "proxyOnce", "predicateGenerators": []},
                 "behaviors": [{"wait": 5}, {"decorate": "d"}], "repeat": 2}
                """;
        Response.Proxy proxy = assertInstanceOf(Response.Proxy.class, first(json));
        assertEquals(List.of(
                        new Behavior.Wait(new WaitSpec.Fixed(5)),
                        new Behavior.Decorate("d"),
                        new Behavior.Repeat(2, true)),
                proxy.behaviors().entries());
        assertTrue(proxy.extra().isEmpty());

        assertEquals(JsonValue.parse("""
                {"proxy": {"to": "http://up", "mode": "proxyOnce", "predicateGenerators": []},
                 "repeat": 2, "_behaviors": {"wait": 5, "decorate": "d"}}
                """), proxy.toJsonValue(), "repeat stays response-level; the block keeps both entries");
    }

    @Test
    void engineGetShapeOnInjectIsTyped() {
        Response.Inject inject = assertInstanceOf(Response.Inject.class, first("""
                {"inject": "function () {}", "behaviors": [{"wait": 5}], "repeat": 4}
                """));
        assertEquals(List.of(new Behavior.Wait(new WaitSpec.Fixed(5)), new Behavior.Repeat(4, true)),
                inject.behaviors().entries());
        JsonObject written = inject.toJsonValue();
        assertEquals(JsonNumber.of(4), written.get("repeat"));
        assertEquals(JsonValue.parse("{\"wait\": 5}"), written.get("_behaviors"));
    }

    @Test
    void siblingUnknownKeysStayInExtraBesideTypedBehaviors() {
        Response.Proxy proxy = assertInstanceOf(Response.Proxy.class, first("""
                {"proxy": {"to": "http://up"}, "_behaviors": {"wait": 1}, "someFutureKey": "v", "_rift": {"templated": true}}
                """));
        assertEquals(List.of(new Behavior.Wait(new WaitSpec.Fixed(1))), proxy.behaviors().entries());
        assertEquals(Map.of("someFutureKey", new JsonString("v"), "_rift", JsonValue.parse("{\"templated\": true}")),
                proxy.extra());
    }

    @Test
    void faultTypesItsBehaviorsBlock() {
        // The engine runs only repeat on a fault response, but keeps the whole block (#229); the
        // model types it the same way, so a wait there round-trips instead of hiding in extra.
        Response.Fault fault = assertInstanceOf(Response.Fault.class, first("""
                {"fault": "CONNECTION_RESET_BY_PEER", "_behaviors": {"wait": 1}}
                """));
        assertEquals(List.of(new Behavior.Wait(new WaitSpec.Fixed(1))), fault.behaviors().entries());
        assertEquals(Map.of(), fault.extra());
    }

    @Test
    void shortConstructorsCarryNoBehaviors() {
        assertTrue(new Response.Proxy(new ProxyResponse("http://up")).behaviors().isEmpty());
        assertTrue(new Response.Proxy(new ProxyResponse("http://up"), Map.of()).behaviors().isEmpty());
        assertTrue(new Response.Inject("function () {}").behaviors().isEmpty());
        assertTrue(new Response.Inject("function () {}", Map.of()).behaviors().isEmpty());
        assertFalse(new Response.Inject("function () {}").toJsonValue().has("_behaviors"));
    }

    @Test
    void legacyExtraConstructorsLiftBehaviorKeys() {
        // Before #215 the extra map was the documented way to put behaviors on these kinds.
        Response.Proxy proxy = new Response.Proxy(new ProxyResponse("http://up"), Map.of(
                "_behaviors", JsonValue.parse("{\"wait\": 5}"), "repeat", JsonNumber.of(2), "someFutureKey", new JsonString("v")));
        assertEquals(List.of(new Behavior.Wait(new WaitSpec.Fixed(5)), new Behavior.Repeat(2, true)),
                proxy.behaviors().entries());
        assertEquals(Map.of("someFutureKey", new JsonString("v")), proxy.extra());

        Response.Inject inject = new Response.Inject("function () {}", Map.of(
                "behaviors", JsonValue.parse("[{\"decorate\": \"d\"}]")));
        assertEquals(List.of(new Behavior.Decorate("d")), inject.behaviors().entries());
        assertTrue(inject.extra().isEmpty());
    }

    @Test
    void behaviorKeysAreRejectedInExtraBesideTypedBehaviors() {
        for (String key : List.of("_behaviors", "behaviors", "repeat")) {
            Map<String, JsonValue> extra = Map.of(key, JsonNumber.of(1));
            assertThrows(WireFormatException.class,
                    () -> new Response.Proxy(new ProxyResponse("http://up"), Behaviors.EMPTY, extra), key);
            assertThrows(WireFormatException.class,
                    () -> new Response.Inject("function () {}", Behaviors.EMPTY, extra), key);
        }
    }

    private static Response first(String responseJson) {
        return Stub.fromJson("{\"predicates\": [], \"responses\": [" + responseJson + "]}").responses().get(0);
    }
}
