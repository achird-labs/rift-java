package io.github.achirdlabs.rift.dsl;

import io.github.achirdlabs.rift.json.JsonValue;
import io.github.achirdlabs.rift.model.ImposterDefinition;
import io.github.achirdlabs.rift.model.RiftScriptEngineConfig;
import io.github.achirdlabs.rift.model.WasmLimits;
import io.github.achirdlabs.rift.model.WireFormatException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Base64;
import java.util.OptionalLong;

import static io.github.achirdlabs.rift.dsl.RiftDsl.imposter;
import static io.github.achirdlabs.rift.dsl.RiftDsl.onGet;
import static io.github.achirdlabs.rift.dsl.RiftDsl.script;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The rift 0.25.0 script keys (rift#1749): wasm scripts, and the clock, seed and wasm limits of {@code _rift.scriptEngine}. */
class WasmScriptWireTest {

    private static final String SHA = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    private static String scriptJson(Script s) {
        return onGet("/x").willReturn(script(s)).build().toJson();
    }

    private static String riftJson(ImposterSpec spec) {
        return JsonValue.parse(spec.build().toJson()) instanceof io.github.achirdlabs.rift.json.JsonObject o
                ? o.get("_rift").toJson()
                : "";
    }

    @Test
    void aComponentIsNamedByItsPrefixedHashWhicheverSpellingIsGiven() {
        String expected = "\"_rift\":{\"script\":{\"engine\":\"wasm\",\"component\":\"sha256:" + SHA + "\"}}";
        assertTrue(scriptJson(Script.component(SHA)).contains(expected), scriptJson(Script.component(SHA)));
        assertTrue(scriptJson(Script.component("sha256:" + SHA)).contains(expected));
    }

    @Test
    void aComponentHashThatIsNotSixtyFourLowercaseHexDigitsIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> Script.component(SHA.toUpperCase()));
        assertThrows(IllegalArgumentException.class, () -> Script.component(SHA.substring(1)));
        assertThrows(IllegalArgumentException.class, () -> Script.component("sha1:" + SHA));
    }

    @Test
    void componentBytesTravelAsBase64Code() {
        byte[] bytes = {0, 'a', 's', 'm', 13, 0, 1, 0};
        String json = scriptJson(Script.wasm(bytes));
        assertTrue(json.contains("\"script\":{\"engine\":\"wasm\",\"code\":\"" + Base64.getEncoder().encodeToString(bytes) + "\"}"),
                json);
        assertThrows(IllegalArgumentException.class, () -> Script.wasm(new byte[0]));
    }

    @Test
    void aWasmFileNamesTheEngine() {
        assertTrue(scriptJson(Script.wasmFile("twin.wasm")).contains("\"script\":{\"engine\":\"wasm\",\"file\":\"twin.wasm\"}"));
    }

    @Test
    void exportKnobsAndInstanceAreWrittenInSchemaOrder() {
        Script s = Script.component(SHA)
                .instance(WasmInstanceScope.IMPOSTER)
                .knobs(JsonValue.parse("{\"rate\":0.5}"))
                .export(WasmExport.HTTP);
        assertTrue(scriptJson(s).contains("{\"engine\":\"wasm\",\"component\":\"sha256:" + SHA
                + "\",\"export\":\"http\",\"knobs\":{\"rate\":0.5},\"instance\":\"imposter\"}"), scriptJson(s));
    }

    @Test
    void wasmOnlySettingsAreRefusedOnOtherScripts() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> Script.js("1").export(WasmExport.RIFT));
        assertTrue(e.getMessage().contains("\"js\""), e.getMessage());
        assertThrows(IllegalStateException.class, () -> Script.rhai("1").knobs(JsonValue.parse("{}")));
        assertThrows(IllegalStateException.class, () -> Script.ref("shared").instance(WasmInstanceScope.REQUEST));
    }

    @Test
    void wasmScriptsRoundTripThroughTheModel() {
        ImposterDefinition def = imposter("twin")
                .stub(onGet("/a").willReturn(script(Script.component(SHA).export(WasmExport.RIFT)
                        .knobs(JsonValue.parse("[1,2]")).instance(WasmInstanceScope.REQUEST))))
                .script("shared", Script.wasm(new byte[] {1, 2, 3}))
                .build();
        assertEquals(def, ImposterDefinition.fromJson(def.toJson()));
        assertEquals(def.toJson(), ImposterDefinition.fromJson(def.toJson()).toJson());
    }

    @Test
    void aSeedAloneKeepsTheEnginesDefaultDeadline() {
        assertEquals("{\"scriptEngine\":{\"defaultEngine\":\"rhai\",\"timeoutMs\":5000,\"seed\":42}}",
                riftJson(imposter("s").seed(42)));
    }

    @Test
    void aSeedIsUnsignedOnTheWire() {
        assertTrue(riftJson(imposter("s").seed(-1L)).contains("\"seed\":18446744073709551615"));
        assertTrue(riftJson(imposter("s").seed(Long.MIN_VALUE)).contains("\"seed\":9223372036854775808"));
        ImposterDefinition def = imposter("s").seed(-1L).build();
        RiftScriptEngineConfig read = ImposterDefinition.fromJson(def.toJson()).rift().orElseThrow().scriptEngine().orElseThrow();
        assertEquals(OptionalLong.of(-1L), read.unsignedSeed());
    }

    @Test
    void aSeedThatIsNotAnUnsigned64BitIntegerIsCarriedNotRefused() {
        // The engine admits it and runs unseeded; a definition read back from it must still parse.
        for (String odd : new String[] {"-1", "18446744073709551616", "1.5", "\"abc\""}) {
            ImposterDefinition def = ImposterDefinition.fromJson("{\"protocol\":\"http\",\"stubs\":[],"
                    + "\"_rift\":{\"scriptEngine\":{\"defaultEngine\":\"rhai\",\"timeoutMs\":5000,\"seed\":" + odd + "}}}");
            RiftScriptEngineConfig config = def.rift().orElseThrow().scriptEngine().orElseThrow();
            assertEquals(OptionalLong.empty(), config.unsignedSeed(), odd);
            assertTrue(def.toJson().contains("\"seed\":" + odd), def.toJson());
        }
    }

    @Test
    void clockRngKeyAndWasmLimitsSurviveScriptEngineInEitherOrder() {
        ImposterSpec before = imposter("s").seed(7).rngKey("header:X-Rift-Rng-Key", "method").clock(ClockSource.HEADER)
                .wasmLimits(WasmLimits.DEFAULTS.withFuel(1_000_000).withMaxMemoryMiB(16))
                .scriptEngine(ScriptEngine.WASM, Duration.ofSeconds(2));
        ImposterSpec after = imposter("s").scriptEngine(ScriptEngine.WASM, Duration.ofSeconds(2))
                .seed(7).rngKey("header:X-Rift-Rng-Key", "method").clock(ClockSource.HEADER)
                .wasmLimits(WasmLimits.DEFAULTS.withFuel(1_000_000).withMaxMemoryMiB(16));
        String expected = "{\"scriptEngine\":{\"defaultEngine\":\"wasm\",\"timeoutMs\":2000,"
                + "\"wasm\":{\"fuel\":1000000,\"maxMemoryMiB\":16},\"clock\":\"header\",\"seed\":7,"
                + "\"rngKey\":[\"header:X-Rift-Rng-Key\",\"method\"]}}";
        assertEquals(expected, riftJson(before));
        assertEquals(expected, riftJson(after));
        ImposterDefinition def = after.build();
        assertEquals(def, ImposterDefinition.fromJson(def.toJson()));
    }

    @Test
    void anRngKeyWithoutASeedIsRefusedAtBuild() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> imposter("s").rngKey("path").build());
        assertTrue(e.getMessage().contains("seed"), e.getMessage());
    }

    @Test
    void anRngKeyPartOutsideTheGrammarIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> imposter("s").rngKey());
        assertThrows(IllegalArgumentException.class, () -> imposter("s").rngKey("header:"));
        assertThrows(IllegalArgumentException.class, () -> imposter("s").rngKey("cookie:id"));
        assertThrows(IllegalArgumentException.class, () -> imposter("s").rngKey("body:id"));
        imposter("s").seed(1).rngKey("query:id", "body:/order/id", "body:", "path").build();
    }

    @Test
    void wasmLimitsOutsideTheEnginesRangeAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> WasmLimits.DEFAULTS.withFuel(-1));
        assertThrows(IllegalArgumentException.class, () -> WasmLimits.DEFAULTS.withMaxTables(1L << 32));
        WireFormatException e = assertThrows(WireFormatException.class, () -> ImposterDefinition.fromJson(
                "{\"protocol\":\"http\",\"stubs\":[],\"_rift\":{\"scriptEngine\":{\"defaultEngine\":\"rhai\","
                        + "\"timeoutMs\":5000,\"wasm\":{\"maxTables\":-1}}}}"));
        assertTrue(e.getMessage().contains("maxTables"), e.getMessage());
    }

    @Test
    void theOlderScriptEngineShapeIsUnchanged() {
        assertEquals("{\"scriptEngine\":{\"defaultEngine\":\"js\",\"timeoutMs\":1000}}",
                riftJson(imposter("s").scriptEngine(ScriptEngine.JAVASCRIPT, Duration.ofSeconds(1))));
    }
}
