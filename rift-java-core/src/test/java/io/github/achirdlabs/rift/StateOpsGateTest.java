package io.github.achirdlabs.rift;

import io.github.achirdlabs.rift.dsl.ImposterSpec;
import io.github.achirdlabs.rift.error.InvalidDefinition;
import io.github.achirdlabs.rift.json.JsonValue;
import io.github.achirdlabs.rift.transport.RiftTransport;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.achirdlabs.rift.dsl.RiftDsl.imposter;
import static io.github.achirdlabs.rift.dsl.RiftDsl.ok;
import static io.github.achirdlabs.rift.dsl.RiftDsl.onGet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code _rift.stateOps} needs rift 0.18.0; rift 0.17.0 drops the block without a word, so a counter
 * a test relies on would silently never move (#227).
 */
class StateOpsGateTest {

    private static final ImposterSpec WITH_OPS = imposter("s")
            .stub(onGet("/a").willReturn(ok()))
            .stub(onGet("/b").willReturn(ok()).willReturn(ok().incrementState("hits")));

    private final List<JsonValue> posted = new ArrayList<>();
    private final AtomicInteger buildInfoCalls = new AtomicInteger();

    @Test
    void oldEngineRejectsStateOpsBeforePostingAnything() {
        InvalidDefinition e = assertThrows(InvalidDefinition.class, () -> rift(VersionCheck.FAIL, "0.17.0").create(WITH_OPS));
        assertTrue(e.getMessage().startsWith("stateOps on an is response: needs rift >= 0.18.0; the running engine (0.17.0)"),
                e.getMessage());
        assertTrue(e.getMessage().contains("drops them silently"), e.getMessage());
        assertTrue(posted.isEmpty(), "nothing reaches the engine");
    }

    @Test
    void replaceAllIsGatedToo() {
        assertThrows(InvalidDefinition.class, () -> rift(VersionCheck.FAIL, "0.17.0").replaceAll(List.of(WITH_OPS.build())));
        assertTrue(posted.isEmpty());
    }

    @Test
    void currentEngineAccepts() {
        rift(VersionCheck.FAIL, "0.18.0").create(WITH_OPS);
        assertEquals(1, posted.size());
    }

    @Test
    void checkOffSendsIt() {
        rift(VersionCheck.OFF, "0.17.0").create(WITH_OPS);
        assertEquals(1, posted.size());
    }

    @Test
    void aStubWithoutOpsNeverAsksForTheVersion() {
        rift(VersionCheck.FAIL, "0.17.0").create(imposter("p").stub(onGet("/").willReturn(ok().templated())));
        assertEquals(0, buildInfoCalls.get());
        assertEquals(1, posted.size());
    }

    private Rift rift(VersionCheck mode, String version) {
        ConnectOptions options = ConnectOptions.builder(URI.create("http://127.0.0.1:2525")).versionCheck(mode).build();
        RiftTransport transport = (RiftTransport) Proxy.newProxyInstance(
                RiftTransport.class.getClassLoader(), new Class<?>[] {RiftTransport.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "buildInfo" -> {
                        buildInfoCalls.incrementAndGet();
                        yield JsonValue.parse("{\"version\": \"" + version + "\"}");
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
        return RiftImpl.spawned(transport, options, () -> { });
    }
}
