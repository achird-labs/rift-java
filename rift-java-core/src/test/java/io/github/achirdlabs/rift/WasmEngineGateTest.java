package io.github.achirdlabs.rift;

import io.github.achirdlabs.rift.dsl.ClockSource;
import io.github.achirdlabs.rift.dsl.ImposterSpec;
import io.github.achirdlabs.rift.dsl.Script;
import io.github.achirdlabs.rift.dsl.ScriptEngine;
import io.github.achirdlabs.rift.error.InvalidDefinition;
import io.github.achirdlabs.rift.json.JsonValue;
import io.github.achirdlabs.rift.model.ImposterDefinition;
import io.github.achirdlabs.rift.model.WasmLimits;
import io.github.achirdlabs.rift.transport.RiftTransport;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.achirdlabs.rift.dsl.RiftDsl.imposter;
import static io.github.achirdlabs.rift.dsl.RiftDsl.ok;
import static io.github.achirdlabs.rift.dsl.RiftDsl.onGet;
import static io.github.achirdlabs.rift.dsl.RiftDsl.script;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rift 0.25.0 script keys are refused on an older engine (rift-java#354): one refuses a wasm
 * script or runs it as Rhai, and every one before it accepts {@code seed}, {@code rngKey} and {@code
 * clock} and ignores them, so a seeded replay would silently not be one.
 */
class WasmEngineGateTest {

    private static final String SHA = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    private final List<JsonValue> posted = new ArrayList<>();
    private final AtomicInteger buildInfoCalls = new AtomicInteger();

    @Test
    void aWasmScriptIsRefusedOnAnOlderEngineBeforeAnythingIsSent() {
        InvalidDefinition e = assertThrows(InvalidDefinition.class, () -> rift("0.24.0")
                .create(imposter("t").stub(onGet("/").willReturn(script(Script.component(SHA))))));
        assertTrue(e.getMessage().startsWith("a wasm script (engine \"wasm\""), e.getMessage());
        assertTrue(e.getMessage().contains("needs rift >= 0.25.0; the running engine (0.24.0)"), e.getMessage());
        assertTrue(posted.isEmpty());
    }

    @Test
    void everyWayToAskForWasmIsGated() {
        List<ImposterSpec> specs = List.of(
                imposter("registry").script("twin", Script.wasm(new byte[] {1})),
                imposter("default").scriptEngine(ScriptEngine.WASM, Duration.ofSeconds(1)),
                imposter("limits").wasmLimits(WasmLimits.DEFAULTS.withFuel(10)),
                imposter("file").stub(onGet("/").willReturn(script(Script.wasmFile("t.wasm")))));
        for (ImposterSpec spec : specs) {
            assertThrows(InvalidDefinition.class, () -> rift("0.24.0").create(spec));
        }
        // A raw .wasm file with no engine named is the wasm engine too, by its extension.
        ImposterDefinition byExtension = ImposterDefinition.fromJson("{\"protocol\":\"http\",\"stubs\":[{\"predicates\":[],"
                + "\"responses\":[{\"_rift\":{\"script\":{\"file\":\"twin.WASM\"}}}]}]}");
        assertThrows(InvalidDefinition.class, () -> rift("0.24.0").create(byExtension));
        assertTrue(posted.isEmpty());
    }

    @Test
    void theClockAndSeedKeysAreRefusedOnAnOlderEngineThatWouldIgnoreThem() {
        for (ImposterSpec spec : List.of(imposter("s").seed(1), imposter("c").clock(ClockSource.HEADER),
                imposter("k").seed(1).rngKey("path"))) {
            InvalidDefinition e = assertThrows(InvalidDefinition.class, () -> rift("0.24.0").create(spec));
            assertTrue(e.getMessage().contains("ignores them silently"), e.getMessage());
        }
        assertThrows(InvalidDefinition.class, () -> rift("0.24.0").replaceAll(List.of(imposter("s").seed(1).build())));
        assertTrue(posted.isEmpty());
    }

    @Test
    void the025EngineTakesThemAll() {
        rift("0.25.0").create(imposter("t").seed(9).rngKey("method").clock(ClockSource.WALL)
                .stub(onGet("/").willReturn(script(Script.component(SHA)))));
        assertEquals(1, posted.size());
    }

    @Test
    void aDefinitionWithoutThemNeverAsksForTheVersion() {
        rift("0.24.0").create(imposter("p").scriptEngine(ScriptEngine.JAVASCRIPT, Duration.ofSeconds(1))
                .script("x", Script.js("1")).stub(onGet("/").willReturn(ok())));
        assertEquals(0, buildInfoCalls.get());
        assertEquals(1, posted.size());
    }

    private Rift rift(String version) {
        ConnectOptions options = ConnectOptions.builder(URI.create("http://127.0.0.1:2525"))
                .versionCheck(VersionCheck.FAIL).build();
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
