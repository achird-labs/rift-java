package io.github.achirdlabs.rift;

import io.github.achirdlabs.rift.dsl.Fault;
import io.github.achirdlabs.rift.dsl.ImposterSpec;
import io.github.achirdlabs.rift.dsl.Script;
import io.github.achirdlabs.rift.error.InvalidDefinition;
import io.github.achirdlabs.rift.json.JsonValue;
import io.github.achirdlabs.rift.transport.RiftTransport;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.achirdlabs.rift.dsl.RiftDsl.fault;
import static io.github.achirdlabs.rift.dsl.RiftDsl.imposter;
import static io.github.achirdlabs.rift.dsl.RiftDsl.ok;
import static io.github.achirdlabs.rift.dsl.RiftDsl.onGet;
import static io.github.achirdlabs.rift.dsl.RiftDsl.script;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** rift 0.17.0 drops a {@code repeat} on a fault/script response, so it is refused there (#229). */
class FaultScriptRepeatGateTest {

    private static final ImposterSpec FAULT_REPEAT = imposter("f")
            .stub(onGet("/").willReturn(fault(Fault.CONNECTION_RESET_BY_PEER).repeat(2)).willReturn(ok()));
    private static final ImposterSpec SCRIPT_REPEAT = imposter("s")
            .stub(onGet("/").willReturn(script(Script.rhai("x")).repeat(2)));

    private final List<JsonValue> posted = new ArrayList<>();
    private final AtomicInteger buildInfoCalls = new AtomicInteger();

    @Test
    void oldEngineRejectsBoth() {
        InvalidDefinition e = assertThrows(InvalidDefinition.class, () -> rift(VersionCheck.FAIL, "0.17.0").create(FAULT_REPEAT));
        assertTrue(e.getMessage().startsWith("repeat on a fault/script response: needs rift >= 0.18.0; the running engine (0.17.0)"),
                e.getMessage());
        assertTrue(e.getMessage().contains("remove the repeat"), e.getMessage());
        assertThrows(InvalidDefinition.class, () -> rift(VersionCheck.FAIL, "0.17.0").create(SCRIPT_REPEAT));
        assertThrows(InvalidDefinition.class,
                () -> rift(VersionCheck.FAIL, "0.17.0").replaceAll(List.of(FAULT_REPEAT.build())));
        assertTrue(posted.isEmpty());
    }

    @Test
    void currentEngineAccepts() {
        rift(VersionCheck.FAIL, "0.18.0").create(FAULT_REPEAT);
        rift(VersionCheck.FAIL, "0.18.0").create(SCRIPT_REPEAT);
        assertEquals(2, posted.size());
    }

    @Test
    void aBlockWithoutRepeatIsNotGated() {
        // A wait on a fault runs on no engine, so an older engine dropping it loses nothing.
        io.github.achirdlabs.rift.model.ImposterDefinition def = io.github.achirdlabs.rift.model.ImposterDefinition.fromJson("""
                {"port":4545,"protocol":"http","stubs":[{"predicates":[],"responses":[
                  {"fault":"CONNECTION_RESET_BY_PEER","_behaviors":{"wait":1}}]}]}""");
        rift(VersionCheck.FAIL, "0.17.0").create(def);
        assertEquals(1, posted.size());
        assertEquals(0, buildInfoCalls.get(), "nothing in it needs a version check");
    }

    @Test
    void aBareFaultNeverAsksForTheVersion() {
        rift(VersionCheck.FAIL, "0.17.0").create(imposter("b").stub(onGet("/").willReturn(fault(Fault.CONNECTION_RESET_BY_PEER))));
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
