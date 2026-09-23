package io.github.achirdlabs.rift;

import io.github.achirdlabs.rift.dsl.ImposterSpec;
import io.github.achirdlabs.rift.error.CommunicationError;
import io.github.achirdlabs.rift.error.InvalidDefinition;
import io.github.achirdlabs.rift.json.JsonValue;
import io.github.achirdlabs.rift.transport.RiftTransport;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static io.github.achirdlabs.rift.dsl.RiftDsl.imposter;
import static io.github.achirdlabs.rift.dsl.RiftDsl.inject;
import static io.github.achirdlabs.rift.dsl.RiftDsl.ok;
import static io.github.achirdlabs.rift.dsl.RiftDsl.onGet;
import static io.github.achirdlabs.rift.dsl.RiftDsl.proxyTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behaviors on a {@code proxy}/{@code inject} response need rift 0.18.0. Older engines accept the
 * block and drop it without a word, so the SDK refuses a typed definition that carries one to such an
 * engine rather than letting a test pass against a response the author never got (#215).
 */
class ProxyInjectBehaviorsGateTest {

    private static final ImposterSpec PROXY_WITH_BEHAVIORS =
            imposter("p").stub(onGet("/").willReturn(proxyTo("http://up").waitMs(10)));
    private static final ImposterSpec INJECT_WITH_BEHAVIORS =
            imposter("i").stub(onGet("/").willReturn(inject("function () {}").repeat(2)));

    private final List<JsonValue> posted = new ArrayList<>();
    private final AtomicInteger buildInfoCalls = new AtomicInteger();

    @Test
    void oldEngineRejectsProxyBehaviorsBeforePostingAnything() {
        InvalidDefinition e = assertThrows(InvalidDefinition.class,
                () -> rift(VersionCheck.FAIL, "0.17.0").create(PROXY_WITH_BEHAVIORS));
        assertTrue(e.getMessage().contains("0.18.0"), e.getMessage());
        assertTrue(e.getMessage().contains("0.17.0"), "names the running engine: " + e.getMessage());
        assertTrue(e.getMessage().startsWith("behaviors on a proxy/inject response: needs rift >= 0.18.0"),
                e.getMessage());
        assertTrue(e.getMessage().contains("remove the behaviors"), e.getMessage());
        assertTrue(posted.isEmpty(), "nothing reaches the engine");
    }

    @Test
    void oldEngineRejectsInjectBehaviors() {
        assertThrows(InvalidDefinition.class, () -> rift(VersionCheck.FAIL, "0.17.0").create(INJECT_WITH_BEHAVIORS));
        assertTrue(posted.isEmpty());
    }

    @Test
    void warnModeStillRejectsAKnownOldEngine() {
        // WARN relaxes an unverifiable version, not a verified one that will drop the block.
        assertThrows(InvalidDefinition.class, () -> rift(VersionCheck.WARN, "v0.17.0").create(PROXY_WITH_BEHAVIORS));
    }

    @Test
    void replaceAllIsGatedToo() {
        assertThrows(InvalidDefinition.class,
                () -> rift(VersionCheck.FAIL, "0.17.0").replaceAll(List.of(PROXY_WITH_BEHAVIORS.build())));
        assertTrue(posted.isEmpty());
    }

    @Test
    void currentEngineAccepts() {
        rift(VersionCheck.FAIL, "0.18.0").create(PROXY_WITH_BEHAVIORS);
        assertEquals(1, posted.size());
    }

    @Test
    void behaviorsOnALaterStubOrResponseAreFound() {
        ImposterSpec def = imposter("m")
                .stub(onGet("/a").willReturn(ok()))
                .stub(onGet("/b").willReturn(ok()).willReturn(proxyTo("http://up").repeat(1)));
        assertThrows(InvalidDefinition.class, () -> rift(VersionCheck.FAIL, "0.17.0").create(def));
        assertTrue(posted.isEmpty());
    }

    @Test
    void embeddedReusesTheVersionItsPreflightRead() {
        Rift rift = RiftImpl.embedded(transport(() -> JsonValue.parse("{\"version\": \"0.18.0\"}")),
                EmbeddedOptions.builder().versionCheck(VersionCheck.FAIL).build(), () -> { });
        rift.create(PROXY_WITH_BEHAVIORS);
        assertEquals(1, buildInfoCalls.get(), "the preflight's read is the only one");
    }

    @Test
    void offSkipsTheCheckEntirely() {
        rift(VersionCheck.OFF, "0.17.0").create(PROXY_WITH_BEHAVIORS);
        assertEquals(1, posted.size());
        assertEquals(0, buildInfoCalls.get(), "OFF never asks the engine");
    }

    @Test
    void definitionsWithoutProxyOrInjectBehaviorsNeverAskTheEngine() {
        Rift rift = rift(VersionCheck.FAIL, "0.17.0");
        rift.create(imposter("a").stub(onGet("/").willReturn(ok().waitMs(5))));
        rift.create(imposter("b").stub(onGet("/").willReturn(proxyTo("http://up"))));
        assertEquals(2, posted.size());
        assertEquals(0, buildInfoCalls.get(), "no extra round-trip for definitions the gate cannot affect");
    }

    @Test
    void placeholderVersionBelowTheSdkFloorIsNotTreatedAsOld() {
        // A locally built engine reports the workspace placeholder (0.1.0); that says nothing about
        // what it supports, the same reasoning the embedded preflight applies.
        rift(VersionCheck.FAIL, "0.1.0").create(PROXY_WITH_BEHAVIORS);
        assertEquals(1, posted.size());
    }

    @Test
    void engineVersionIsReadOncePerHandle() {
        Rift rift = rift(VersionCheck.FAIL, "0.18.0");
        rift.create(PROXY_WITH_BEHAVIORS);
        rift.create(INJECT_WITH_BEHAVIORS);
        assertEquals(1, buildInfoCalls.get());
    }

    @Test
    void unreadableVersionFailsInFailMode() {
        Rift rift = rift(VersionCheck.FAIL, () -> {
            throw new CommunicationError("no version");
        });
        assertThrows(CommunicationError.class, () -> rift.create(PROXY_WITH_BEHAVIORS));
        assertTrue(posted.isEmpty());
    }

    @Test
    void unreadableVersionPassesInWarnMode() {
        Rift rift = rift(VersionCheck.WARN, () -> {
            throw new CommunicationError("no version");
        });
        rift.create(PROXY_WITH_BEHAVIORS);
        assertEquals(1, posted.size());
    }

    @Test
    void rawJsonCreateIsNotInspected() {
        rift(VersionCheck.FAIL, "0.17.0").create(JsonValue.parse(PROXY_WITH_BEHAVIORS.build().toJson()));
        assertEquals(1, posted.size());
        assertEquals(0, buildInfoCalls.get());
    }

    @Test
    void embeddedHandleGatesWithTheCallersVersionCheck() {
        // The embedded handle rewrites its ConnectOptions internally; the gate must still follow the
        // mode the caller put on EmbeddedOptions, not a hard-coded OFF.
        Rift rift = RiftImpl.embedded(transport(() -> JsonValue.parse("{\"version\": \"0.17.0\"}")),
                EmbeddedOptions.builder().versionCheck(VersionCheck.FAIL).build(), () -> { });
        assertThrows(InvalidDefinition.class, () -> rift.create(PROXY_WITH_BEHAVIORS));

        Rift off = RiftImpl.embedded(transport(() -> JsonValue.parse("{\"version\": \"0.17.0\"}")),
                EmbeddedOptions.builder().versionCheck(VersionCheck.OFF).build(), () -> { });
        off.create(PROXY_WITH_BEHAVIORS);
        assertEquals(1, posted.size());
    }

    private Rift rift(VersionCheck mode, String version) {
        return rift(mode, () -> JsonValue.parse("{\"version\": \"" + version + "\"}"));
    }

    private Rift rift(VersionCheck mode, Supplier<JsonValue> buildInfo) {
        ConnectOptions options = ConnectOptions.builder(URI.create("http://127.0.0.1:2525")).versionCheck(mode).build();
        return RiftImpl.spawned(transport(buildInfo), options, () -> { });
    }

    private RiftTransport transport(Supplier<JsonValue> buildInfo) {
        return (RiftTransport) Proxy.newProxyInstance(
                RiftTransport.class.getClassLoader(), new Class<?>[] {RiftTransport.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "buildInfo" -> {
                        buildInfoCalls.incrementAndGet();
                        yield buildInfo.get();
                    }
                    case "createImposter" -> {
                        posted.add((JsonValue) args[0]);
                        yield JsonValue.parse("{\"port\": 4545}");
                    }
                    case "replaceAllImposters" -> {
                        posted.add((JsonValue) args[0]);
                        yield null;
                    }
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
