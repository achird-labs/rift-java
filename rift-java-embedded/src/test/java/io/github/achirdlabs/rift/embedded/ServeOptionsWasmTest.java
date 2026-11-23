package io.github.achirdlabs.rift.embedded;

import io.github.achirdlabs.rift.EmbeddedOptions;
import io.github.achirdlabs.rift.error.EngineUnavailable;
import io.github.achirdlabs.rift.json.JsonBool;
import io.github.achirdlabs.rift.json.JsonObject;
import io.github.achirdlabs.rift.json.JsonString;
import io.github.achirdlabs.rift.json.JsonValue;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code allowWasm}, {@code strictWasm} and the {@code -wasm} library on the embedded engine (rift-java#354). */
class ServeOptionsWasmTest {

    private static final String V024_BUILD_INFO = "{\"version\": \"0.24.0\", \"serveOptions\": "
            + "[\"host\", \"port\", \"allowInjection\", \"scriptsDir\"]}";
    private static final String V025_BUILD_INFO = "{\"version\": \"0.25.0\", \"features\": [\"javascript\"], "
            + "\"serveOptions\": [\"host\", \"port\", \"allowInjection\", \"allowWasm\", \"strictWasm\"]}";

    @Test
    void bothAreSentUnderTheEnginesNames() {
        Path list = Path.of("/srv/trusted.txt");
        JsonObject payload = EmbeddedTransport.serveOptions(EmbeddedOptions.builder().allowWasm(false).strictWasm(list).build());
        assertEquals(JsonBool.of(false), payload.get("allowWasm"));
        assertEquals(new JsonString(list.toString()), payload.get("strictWasm"));
        assertEquals(4, payload.fields().size(), payload.toJson());
    }

    @Test
    void unsetIsAbsent() {
        JsonObject payload = EmbeddedTransport.serveOptions(EmbeddedOptions.builder().build());
        assertNull(payload.get("allowWasm"));
        assertNull(payload.get("strictWasm"));
    }

    @Test
    void anEngineThatDoesNotAdvertiseThemIsRefusedNamingTheSetter() {
        EngineUnavailable e = assertThrows(EngineUnavailable.class, () -> EmbeddedTransport.requireAdvertised(
                EmbeddedOptions.builder().allowWasm(true).build(), () -> JsonValue.parse(V024_BUILD_INFO)));
        assertTrue(e.getMessage().startsWith("allowWasm needs a rift engine >= 0.25.0"), e.getMessage());
        assertTrue(e.getMessage().endsWith("drop EmbeddedOptions.allowWasm."), e.getMessage());
        assertThrows(EngineUnavailable.class, () -> EmbeddedTransport.requireAdvertised(
                EmbeddedOptions.builder().strictWasm(Path.of("t")).build(), () -> JsonValue.parse(V024_BUILD_INFO)));
        EmbeddedTransport.requireAdvertised(EmbeddedOptions.builder().allowWasm(true).strictWasm(Path.of("t")).build(),
                () -> JsonValue.parse(V025_BUILD_INFO));
    }

    @Test
    void aTrustListServesThePlaneBeforeAnyImposterExists() {
        // The engine refuses a strictWasm on a handle that already holds an unlisted component.
        assertTrue(EmbeddedProvider.needsAdminPlaneAtStart(EmbeddedOptions.builder().strictWasm(Path.of("t")).build()));
        assertFalse(EmbeddedProvider.needsAdminPlaneAtStart(EmbeddedOptions.builder().allowWasm(false).build()));
    }

    @Test
    void askingForTheWasmLibraryRefusesOneWithoutTheWasmFeature() {
        EngineUnavailable e = assertThrows(EngineUnavailable.class,
                () -> EmbeddedProvider.requireWasmFeature(JsonValue.parse(V025_BUILD_INFO)));
        assertTrue(e.getMessage().contains("without \"wasm\""), e.getMessage());
        EmbeddedProvider.requireWasmFeature(JsonValue.parse("{\"version\":\"0.25.0\",\"features\":[\"javascript\",\"wasm\"]}"));
    }

    @Test
    void theWasmLibraryIsOffByDefault() {
        assertFalse(EmbeddedOptions.builder().build().wasmLibrary());
        assertTrue(EmbeddedOptions.builder().wasmLibrary(true).build().wasmLibrary());
    }
}
