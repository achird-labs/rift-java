package io.github.achirdlabs.rift.dsl;

import io.github.achirdlabs.rift.json.JsonArray;
import io.github.achirdlabs.rift.json.JsonObject;
import io.github.achirdlabs.rift.json.JsonValue;
import io.github.achirdlabs.rift.model.Behavior;
import io.github.achirdlabs.rift.model.Response;
import io.github.achirdlabs.rift.model.WaitSpec;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static io.github.achirdlabs.rift.dsl.RiftDsl.copyFromQuery;
import static io.github.achirdlabs.rift.dsl.RiftDsl.inject;
import static io.github.achirdlabs.rift.dsl.RiftDsl.lookupKey;
import static io.github.achirdlabs.rift.dsl.RiftDsl.ok;
import static io.github.achirdlabs.rift.dsl.RiftDsl.proxyTo;
import static io.github.achirdlabs.rift.dsl.RiftDsl.regex;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavior chainers on every response kind the engine runs behaviors on — {@code is}, {@code proxy}
 * and {@code inject} (#215). Asserted against the literal wire JSON: the point of the feature is that
 * {@code _behaviors} lands beside {@code proxy}/{@code inject} exactly as it does beside {@code is}.
 */
class BehaviorChainTest {

    private static final String DECORATE = "function (req, res) { res.headers['X-D'] = 'y'; }";

    @Test
    void proxyEmitsBehaviorsBesideProxy() {
        Response response = proxyTo("http://up").waitMs(100).decorate(DECORATE).repeat(2).build();
        assertEquals(JsonValue.parse("""
                {"proxy": {"to": "http://up", "mode": "", "predicateGenerators": []},
                 "_behaviors": {"wait": 100, "decorate": "function (req, res) { res.headers['X-D'] = 'y'; }",
                                "repeat": 2}}
                """), response.toJsonValue());
    }

    @Test
    void injectEmitsBehaviorsBesideInject() {
        Response response = inject("function () { return {statusCode: 201}; }").waitMs(50).repeat(3).build();
        assertEquals(JsonValue.parse("""
                {"inject": "function () { return {statusCode: 201}; }",
                 "_behaviors": {"wait": 50, "repeat": 3}}
                """), response.toJsonValue());
    }

    @Test
    void proxyAndInjectWithoutBehaviorsWriteNoBehaviorsKey() {
        assertFalse(proxyTo("http://up").build().toJsonValue().has("_behaviors"));
        assertFalse(inject("function () {}").build().toJsonValue().has("_behaviors"));
    }

    @Test
    void repeatedKeyOnProxyUsesArrayForm() {
        Response response = proxyTo("http://up")
                .copy(copyFromQuery("a").into("${A}").using(regex(".*")))
                .copy(copyFromQuery("b").into("${B}").using(regex(".*")))
                .build();
        JsonObject written = response.toJsonValue();
        assertFalse(written.has("_behaviors"));
        assertEquals(2, assertInstanceOf(JsonArray.class, written.get("behaviors")).items().size(),
                "both copy entries survive, as the array form");
    }

    @Test
    void everyChainerIsAvailableOnProxyAndInject() {
        List<Behavior> expected = List.of(
                new Behavior.Wait(new WaitSpec.Fixed(7)),
                new Behavior.Wait(new WaitSpec.Fixed(1000)),
                new Behavior.Wait(new WaitSpec.Range(1, 2)),
                new Behavior.Wait(new WaitSpec.Inject("function () { return 1; }")),
                new Behavior.Wait(new WaitSpec.Script("function () { return 2; }")),
                new Behavior.Decorate("d"),
                new Behavior.Repeat(4),
                new Behavior.ShellTransform("a"),
                new Behavior.ShellTransform("b"));

        Response.Proxy proxy = assertInstanceOf(Response.Proxy.class, proxyTo("http://up")
                .waitMs(7).after(Duration.ofSeconds(1)).waitBetween(1, 2)
                .waitInject("function () { return 1; }").waitScript("function () { return 2; }")
                .decorate("d").repeat(4).shellTransform("a", "b").build());
        assertEquals(expected, proxy.behaviors().entries());

        Response.Inject injected = assertInstanceOf(Response.Inject.class, inject("function () {}")
                .waitMs(7).after(Duration.ofSeconds(1)).waitBetween(1, 2)
                .waitInject("function () { return 1; }").waitScript("function () { return 2; }")
                .decorate("d").repeat(4).shellTransform("a", "b").build());
        assertEquals(expected, injected.behaviors().entries());
    }

    @Test
    void copyAndLookupChainersAreAvailableOnProxyAndInject() {
        CopySpec copy = copyFromQuery("q").into("${Q}").using(regex(".*"));
        LookupSpec lookup = lookupKey("id").using(regex(".*")).fromCsv("data.csv", "id").into("${ROW}");
        List<Behavior> expected = List.of(
                new Behavior.Copy(List.of(copy.build())),
                new Behavior.Copy(List.of(copy.build()), true),
                new Behavior.Unknown("lookup", new JsonArray(List.of(lookup.build()))),
                new Behavior.Unknown("lookup", lookup.build()));

        Response.Proxy proxy = assertInstanceOf(Response.Proxy.class, proxyTo("http://up")
                .copy(copy).copyObject(copy).lookup(lookup).lookupObject(lookup).build());
        assertEquals(expected, proxy.behaviors().entries());

        Response.Inject injected = assertInstanceOf(Response.Inject.class, inject("function () {}")
                .copy(copy).copyObject(copy).lookup(lookup).lookupObject(lookup).build());
        assertEquals(expected, injected.behaviors().entries());
    }

    @Test
    void isSpecKeepsItsChainerSignaturesInTheClassFile() throws NoSuchMethodException {
        // Binary compatibility: code compiled against an IsSpec that declared these methods links to
        // IsSpec-returning descriptors. A default method alone would only offer ResponseSpec-returning ones.
        Object[][] chainers = {
                {"after", new Class<?>[] {Duration.class}},
                {"waitMs", new Class<?>[] {long.class}},
                {"waitBetween", new Class<?>[] {long.class, long.class}},
                {"waitInject", new Class<?>[] {String.class}},
                {"waitScript", new Class<?>[] {String.class}},
                {"decorate", new Class<?>[] {String.class}},
                {"repeat", new Class<?>[] {int.class}},
                {"copy", new Class<?>[] {CopySpec[].class}},
                {"copyObject", new Class<?>[] {CopySpec.class}},
                {"lookup", new Class<?>[] {LookupSpec[].class}},
                {"lookupObject", new Class<?>[] {LookupSpec.class}},
                {"shellTransform", new Class<?>[] {String[].class}},
        };
        for (Object[] chainer : chainers) {
            String name = (String) chainer[0];
            assertEquals(IsSpec.class, IsSpec.class.getDeclaredMethod(name, (Class<?>[]) chainer[1]).getReturnType(), name);
        }
    }

    @Test
    void proxyKnobsKeepBehaviorsWhicheverOrderTheyAreChained() {
        // ProxySpec has its own withers (mode, generators, headers, rewrite, recorded-stub knobs); a
        // behavior chained before any of them must survive it.
        Response.Proxy proxy = assertInstanceOf(Response.Proxy.class, proxyTo("http://up")
                .waitMs(10)
                .proxyAlways()
                .generateBy(RequestField.PATH)
                .injectHeader("X-A", "1")
                .rewritePath("/a", "/b")
                .addWaitBehavior()
                .decorateWith("recorded")
                .withPredicateGenerator("{\"matches\": {\"method\": true}}")
                .build());
        assertEquals(List.of(new Behavior.Wait(new WaitSpec.Fixed(10))), proxy.behaviors().entries());
        assertEquals("proxyAlways", proxy.proxy().mode());
        assertTrue(proxy.proxy().addWaitBehavior());
    }

    @Test
    void decorateAndDecorateWithAreDifferentKeys() {
        // decorate(...) is _behaviors.decorate (runs on the upstream response); decorateWith(...) is
        // proxy.addDecorateBehavior (decorates the stubs the proxy records). Neither leaks into the other.
        Response.Proxy proxy = assertInstanceOf(Response.Proxy.class,
                proxyTo("http://up").decorate("live").decorateWith("recorded").build());
        assertEquals(List.of(new Behavior.Decorate("live")), proxy.behaviors().entries());
        assertEquals("recorded", proxy.proxy().addDecorateBehavior().orElseThrow());
    }

    @Test
    void chainersReturnNewInstancesAndLeaveTheOriginalUntouched() {
        ProxySpec base = proxyTo("http://up");
        ProxySpec delayed = base.waitMs(5);
        assertNotSame(base, delayed);
        assertTrue(((Response.Proxy) base.build()).behaviors().isEmpty());

        InjectSpec plain = inject("function () {}");
        InjectSpec repeated = plain.repeat(2);
        assertNotSame(plain, repeated);
        assertTrue(((Response.Inject) plain.build()).behaviors().isEmpty());
    }

    @Test
    void isSpecChainersStillReturnIsSpec() {
        // The move onto BehaviorChain keeps IsSpec's chain fluent: a behavior chainer followed by an
        // IsSpec-only method must still compile and apply both.
        Response.Is is = assertInstanceOf(Response.Is.class, ok().waitMs(5).withTextBody("b").templated().build());
        assertEquals(List.of(new Behavior.Wait(new WaitSpec.Fixed(5))), is.behaviors().entries());
        assertTrue(is.rift().orElseThrow().templated());
    }

    @Test
    void behaviorChainIsSharedByExactlyTheThreeKinds() {
        BehaviorChain<IsSpec> is = ok();
        BehaviorChain<ProxySpec> proxy = proxyTo("http://up");
        BehaviorChain<InjectSpec> injected = inject("function () {}");
        assertEquals(List.of(IsSpec.class, ProxySpec.class, InjectSpec.class),
                List.of(BehaviorChain.class.getPermittedSubclasses()));
        assertTrue(is.withBehavior(new Behavior.Repeat(1)) instanceof IsSpec);
        assertTrue(proxy.withBehavior(new Behavior.Repeat(1)) instanceof ProxySpec);
        assertTrue(injected.withBehavior(new Behavior.Repeat(1)) instanceof InjectSpec);
    }
}
