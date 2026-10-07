package io.github.achirdlabs.rift;

import io.github.achirdlabs.rift.dsl.ImposterSpec;
import io.github.achirdlabs.rift.error.InvalidDefinition;
import io.github.achirdlabs.rift.json.JsonValue;
import io.github.achirdlabs.rift.model.ImposterDefinition;
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
 * {@code _rift.conditional} needs rift 0.20.0; an older engine ignores the key and serves a 200
 * without validators forever, so a client's revalidation would never be exercised (#263).
 */
class ConditionalGateTest {

    private static final ImposterSpec WITH_CONDITIONAL = imposter("cdn")
            .stub(onGet("/a").willReturn(ok()))
            .stub(onGet("/datafile").willReturn(ok().withTextBody("{}").conditional()));

    private final List<JsonValue> posted = new ArrayList<>();
    private final AtomicInteger buildInfoCalls = new AtomicInteger();

    @Test
    void oldEngineRefusesConditionalBeforePostingAnything() {
        InvalidDefinition e = assertThrows(InvalidDefinition.class,
                () -> rift(VersionCheck.FAIL, "0.19.0").create(WITH_CONDITIONAL));
        assertTrue(e.getMessage().startsWith("conditional on an is response: needs rift >= 0.20.0; the running engine (0.19.0)"),
                e.getMessage());
        assertTrue(e.getMessage().contains("ignores it"), e.getMessage());
        assertTrue(posted.isEmpty(), "nothing reaches the engine");
    }

    @Test
    void replaceAllIsGatedToo() {
        assertThrows(InvalidDefinition.class,
                () -> rift(VersionCheck.FAIL, "0.19.0").replaceAll(List.of(WITH_CONDITIONAL.build())));
        assertTrue(posted.isEmpty());
    }

    @Test
    void currentEngineAccepts() {
        rift(VersionCheck.FAIL, "0.20.0").create(WITH_CONDITIONAL);
        assertEquals(1, posted.size());
    }

    @Test
    void anExplicitlyOffConditionalNeverAsksForTheVersion() {
        // conditional:false is the engine's off state, read back from a stored config: it asks for nothing.
        ImposterDefinition off = ImposterDefinition.fromJson("{\"port\":4545,\"protocol\":\"http\",\"stubs\":[{"
                + "\"predicates\":[],\"responses\":[{\"is\":{\"statusCode\":\"200\"},\"_rift\":{\"conditional\":false}}]}]}");
        rift(VersionCheck.FAIL, "0.19.0").create(off);
        assertEquals(0, buildInfoCalls.get());
        assertEquals(1, posted.size());
    }

    @Test
    void theSpelledOutFormIsGatedLikeTheShorthand() {
        ImposterDefinition validators = ImposterDefinition.fromJson("{\"port\":4545,\"protocol\":\"http\",\"stubs\":[{"
                + "\"predicates\":[],\"responses\":[{\"is\":{\"statusCode\":\"200\"},\"_rift\":{\"conditional\":{}}}]}]}");
        assertThrows(InvalidDefinition.class, () -> rift(VersionCheck.FAIL, "0.19.0").create(validators));
    }

    @Test
    void aStubWithoutConditionalNeverAsksForTheVersion() {
        rift(VersionCheck.FAIL, "0.19.0").create(imposter("p").stub(onGet("/").willReturn(ok().templated())));
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
