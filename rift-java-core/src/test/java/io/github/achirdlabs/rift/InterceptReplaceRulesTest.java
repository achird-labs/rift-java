package io.github.achirdlabs.rift;

import io.github.achirdlabs.rift.error.InvalidDefinition;
import io.github.achirdlabs.rift.json.JsonArray;
import io.github.achirdlabs.rift.json.JsonObject;
import io.github.achirdlabs.rift.json.JsonValue;
import io.github.achirdlabs.rift.transport.RiftTransport;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.achirdlabs.rift.dsl.RiftDsl.onGet;
import static io.github.achirdlabs.rift.dsl.RiftDsl.status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Intercept#replaceRules} swaps the whole rule set in one engine call ({@code PUT
 * /intercept/rules} / {@code rift_intercept_replace_rules}, rift 0.20.0), {@link
 * Intercept#removeRule} is built on it, and a forward to a named host or over {@code https} is
 * refused on an engine that would ignore the target (#262).
 */
class InterceptReplaceRulesTest {

    private final List<JsonValue> added = new ArrayList<>();
    private final List<JsonValue> replaced = new ArrayList<>();
    private final AtomicReference<JsonValue> listed = new AtomicReference<>(JsonValue.parse("[]"));
    private final AtomicInteger listCalls = new AtomicInteger();

    @Test
    void replaceRulesStagesEveryRuleAndSendsOneReplace() {
        Intercept intercept = intercept("0.20.0");

        List<InterceptRule> installed = intercept.replaceRules(set -> {
            set.serve("a.example", status(200).withTextBody("a"));
            set.rule().host("b.example").when(onGet("/health")).forward("9443");
        });

        assertEquals(List.of(), added, "staged rules are never added one at a time");
        assertEquals(1, replaced.size());
        assertEquals(JsonValue.parse("[{\"host\":\"a.example\",\"action\":{\"serve\":{\"statusCode\":200,\"body\":\"a\"}}},"
                + "{\"host\":\"b.example\",\"predicates\":[{\"equals\":{\"method\":\"GET\",\"path\":\"/health\"}}],"
                + "\"action\":{\"forward\":{\"port\":9443}}}]"), replaced.get(0));
        assertEquals(List.of(RuleKind.SERVE, RuleKind.FORWARD), installed.stream().map(InterceptRule::kind).toList());
        assertEquals("a.example", installed.get(0).host());
    }

    @Test
    void aStagedRedirectKeepsItsKind() {
        Imposter imposter = (Imposter) Proxy.newProxyInstance(Imposter.class.getClassLoader(),
                new Class<?>[] {Imposter.class}, (p, m, a) -> m.getName().equals("port") ? 4600 : null);

        List<InterceptRule> installed = intercept("0.20.0").replaceRules(set -> set.redirectTo("a.example", imposter));

        assertEquals(RuleKind.REDIRECT, installed.get(0).kind(), "the engine would echo it back as FORWARD");
        assertEquals(JsonValue.parse("[{\"host\":\"a.example\",\"action\":{\"forward\":{\"port\":4600}}}]"), replaced.get(0));
    }

    @Test
    void anEmptyReplaceClearsTheSet() {
        assertEquals(List.of(), intercept("0.20.0").replaceRules(set -> { }));
        assertEquals(List.of(JsonValue.parse("[]")), replaced);
    }

    @Test
    void anUndeliverableStagedServeSendsNothing() {
        Intercept intercept = intercept("0.20.0");
        assertThrows(InvalidDefinition.class, () -> intercept.replaceRules(set -> {
            set.serve("a.example", status(200));
            set.serve("b.example", status(200).templated());
        }));
        assertEquals(List.of(), replaced, "the old set stays: nothing is sent when staging fails");
        assertEquals(List.of(), added);
    }

    @Test
    void replaceRulesReinstallsAListInOrder() {
        Intercept intercept = intercept("0.20.0");
        InterceptRule a = new InterceptRule("a.example", RuleKind.SERVE,
                JsonValue.parse("{\"host\":\"a.example\",\"action\":{\"serve\":{\"statusCode\":200}}}"));
        InterceptRule b = new InterceptRule("b.example", RuleKind.FORWARD,
                JsonValue.parse("{\"host\":\"b.example\",\"action\":{\"forward\":{\"port\":1}}}"));

        assertEquals(List.of(b, a), intercept.replaceRules(List.of(b, a)));
        assertEquals(List.of(JsonValue.parse("[" + b.raw().toJson() + "," + a.raw().toJson() + "]")), replaced);
    }

    @Test
    void anOldEngineRefusesAReplaceBeforeRunningTheRules() {
        Intercept intercept = intercept("0.19.0");
        List<String> ran = new ArrayList<>();

        InvalidDefinition e = assertThrows(InvalidDefinition.class,
                () -> intercept.replaceRules(set -> ran.add("staged")));

        assertTrue(e.getMessage().startsWith("intercept rule replace: needs rift >= 0.20.0; the running engine (0.19.0)"),
                e.getMessage());
        assertEquals(List.of(), ran, "the gate fires before the caller's rules run");
        assertEquals(List.of(), replaced);
        assertThrows(InvalidDefinition.class, () -> intercept.replaceRules(List.of()));
        assertThrows(InvalidDefinition.class, () -> intercept.removeRule(
                new InterceptRule("a", RuleKind.SERVE, JsonValue.parse("{\"host\":\"a\"}"))));
        assertEquals(0, listCalls.get(), "removeRule is refused before it reads the rules");
    }

    @Test
    void removeRuleMatchesTheEngineEcho() {
        Intercept intercept = intercept("0.20.0");
        InterceptRule served = intercept.serve("a.example", status(200).withTextBody("a"));
        InterceptRule scoped = intercept.rule().when(onGet("/x")).forward("9443");
        // What GET /intercept/rules echoes: serde defaults rendered, predicates re-serialized.
        listed.set(JsonValue.parse("["
                + "{\"host\":\"a.example\",\"predicates\":[],\"action\":{\"serve\":{\"statusCode\":200,\"headers\":{},\"body\":\"a\"}}},"
                + "{\"host\":null,\"predicates\":[{\"equals\":{\"path\":\"/x\",\"method\":\"GET\"}}],\"action\":{\"forward\":{\"port\":9443}}},"
                + "{\"host\":\"c.example\",\"predicates\":[],\"action\":{\"serve\":{\"statusCode\":204,\"headers\":{},\"body\":null}}}]"));

        assertTrue(intercept.removeRule(served));

        assertEquals(1, replaced.size());
        JsonArray remaining = (JsonArray) replaced.get(0);
        assertEquals(2, remaining.items().size(), "only the matching rule goes: " + remaining.toJson());
        assertEquals(JsonValue.parse("{\"forward\":{\"port\":9443}}"), ((JsonObject) remaining.items().get(0)).get("action"));
        assertEquals(new io.github.achirdlabs.rift.json.JsonString("c.example"),
                ((JsonObject) remaining.items().get(1)).get("host"));

        assertTrue(intercept.removeRule(scoped), "a catch-all echoed with host:null still matches");
    }

    @Test
    void removeRuleTellsApartRulesOnTheSameHost() {
        Intercept intercept = intercept("0.20.0");
        InterceptRule health = intercept.rule().host("a.example").when(onGet("/health")).serve(status(200));
        listed.set(JsonValue.parse("["
                + "{\"host\":\"a.example\",\"predicates\":[{\"equals\":{\"method\":\"GET\",\"path\":\"/ready\"}}],"
                + "\"action\":{\"serve\":{\"statusCode\":200,\"headers\":{},\"body\":null}}},"
                + "{\"host\":\"a.example\",\"predicates\":[{\"equals\":{\"method\":\"GET\",\"path\":\"/health\"}}],"
                + "\"action\":{\"serve\":{\"statusCode\":200,\"headers\":{},\"body\":null}}},"
                + "{\"host\":\"a.example\",\"predicates\":[{\"equals\":{\"method\":\"GET\",\"path\":\"/health\"}}],"
                + "\"action\":{\"serve\":{\"statusCode\":503,\"headers\":{},\"body\":null}}}]"));

        assertTrue(intercept.removeRule(health));

        JsonArray remaining = (JsonArray) replaced.get(0);
        assertEquals(2, remaining.items().size(), remaining.toJson());
        assertTrue(remaining.toJson().contains("/ready"), "a different predicate stays: " + remaining.toJson());
        assertTrue(remaining.toJson().contains("503"), "a different action stays: " + remaining.toJson());
    }

    @Test
    void removeRuleTakesOutEveryCopy() {
        Intercept intercept = intercept("0.20.0");
        String copy = "{\"host\":\"a.example\",\"predicates\":[],\"action\":{\"forward\":{\"port\":1}}}";
        listed.set(JsonValue.parse("[" + copy + "," + copy + "]"));

        assertTrue(intercept.removeRule(intercept.forward("a.example", "1")));

        assertEquals(List.of(JsonValue.parse("[]")), replaced);
    }

    @Test
    void removingARuleThatIsNotInstalledWritesNothing() {
        Intercept intercept = intercept("0.20.0");
        listed.set(JsonValue.parse("[{\"host\":\"c.example\",\"predicates\":[],\"action\":{\"forward\":{\"port\":1}}}]"));

        assertFalse(intercept.removeRule(new InterceptRule("a.example", RuleKind.FORWARD,
                JsonValue.parse("{\"host\":\"a.example\",\"action\":{\"forward\":{\"port\":1}}}"))));
        assertEquals(List.of(), replaced);
    }

    @Test
    void forwardToANamedHostIsRefusedOnAnOldEngine() {
        Intercept intercept = intercept("0.19.0");

        InvalidDefinition e = assertThrows(InvalidDefinition.class,
                () -> intercept.forward("api.partner.com", "partner-mock:8443"));

        assertTrue(e.getMessage().startsWith("a forward target host or scheme: needs rift >= 0.20.0"), e.getMessage());
        assertTrue(e.getMessage().contains("ignores it and forwards to 127.0.0.1"), e.getMessage());
        assertEquals(List.of(), added);
        assertThrows(InvalidDefinition.class, () -> intercept.rule().forward("https://localhost:8443"));
    }

    @Test
    void aLoopbackForwardNeverAsksTheEngineVersion() {
        intercept("0.19.0").forward("api.partner.com", "localhost:9443");
        assertEquals(List.of(JsonValue.parse("{\"host\":\"api.partner.com\",\"action\":{\"forward\":{\"port\":9443}}}")), added);
    }

    @Test
    void forwardToANamedHostSendsItOnACurrentEngine() {
        intercept("0.20.0").forward("api.partner.com", "https://partner-mock:8443");
        assertEquals(List.of(JsonValue.parse("{\"host\":\"api.partner.com\",\"action\":{\"forward\":"
                + "{\"port\":8443,\"host\":\"partner-mock\",\"scheme\":\"https\"}}}")), added);
    }

    @Test
    void aClosedHandleRefusesReplaceAndRemove() {
        Intercept intercept = intercept("0.20.0");
        intercept.close();
        assertThrows(IllegalStateException.class, () -> intercept.replaceRules(set -> { }));
        assertThrows(IllegalStateException.class, () -> intercept.replaceRules(List.of()));
        assertThrows(IllegalStateException.class, () -> intercept.removeRule(
                new InterceptRule("a", RuleKind.SERVE, JsonValue.parse("{}"))));
    }

    @Test
    void aSetCapturedByAReplaceCannotAddAfterIt() {
        Intercept intercept = intercept("0.20.0");
        AtomicReference<InterceptRuleSet> leaked = new AtomicReference<>();
        intercept.replaceRules(leaked::set);

        assertThrows(IllegalStateException.class, () -> leaked.get().serve("late.example", status(200)));
        assertThrows(IllegalStateException.class, () -> leaked.get().forward("late.example", "1"));
        assertThrows(IllegalStateException.class, () -> leaked.get().rule());
        assertEquals(List.of(JsonValue.parse("[]")), replaced, "the late rule reached nothing");
        assertEquals(List.of(), added);
    }

    private Intercept intercept(String version) {
        ConnectOptions options = ConnectOptions.builder(URI.create("http://127.0.0.1:2525"))
                .versionCheck(VersionCheck.FAIL).build();
        RiftTransport transport = (RiftTransport) Proxy.newProxyInstance(
                RiftTransport.class.getClassLoader(), new Class<?>[] {RiftTransport.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "buildInfo" -> JsonValue.parse("{\"version\": \"" + version + "\"}");
                    case "startIntercept" -> JsonValue.parse(
                            "{\"interceptPort\": 9000, \"interceptUrl\": \"http://127.0.0.1:9000\"}");
                    case "interceptAddRules" -> {
                        added.add((JsonValue) args[0]);
                        yield null;
                    }
                    case "interceptReplaceRules" -> {
                        replaced.add((JsonValue) args[0]);
                        yield null;
                    }
                    case "interceptListRules" -> {
                        listCalls.incrementAndGet();
                        yield listed.get();
                    }
                    case "stopIntercept", "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return RiftImpl.spawned(transport, options, () -> { }).intercept();
    }
}
