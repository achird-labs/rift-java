package io.github.achirdlabs.rift.embedded;

import io.github.achirdlabs.rift.EmbeddedOptions;
import io.github.achirdlabs.rift.error.EngineUnavailable;
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

/** {@code scriptsDir} in the {@code rift_serve_admin} payload, and its advertisement gate (#343, rift#1617). */
class ServeOptionsScriptsDirTest {

    private static final String V023_BUILD_INFO = "{\"version\": \"0.23.0\", \"serveOptions\": "
            + "[\"host\", \"port\", \"allowInjection\", \"maxBodySize\"]}";
    private static final String V024_BUILD_INFO = "{\"version\": \"0.24.0\", \"serveOptions\": "
            + "[\"host\", \"port\", \"allowInjection\", \"scriptsDir\"]}";

    @Test
    void scriptsDirIsSentUnderTheEnginesName() {
        Path dir = Path.of("/srv/rift-scripts");
        JsonObject payload = EmbeddedTransport.serveOptions(EmbeddedOptions.builder().scriptsDir(dir).build());
        assertEquals(new JsonString(dir.toString()), payload.get("scriptsDir"));
        assertEquals(3, payload.fields().size(), "host, port and scriptsDir only: " + payload.toJson());
    }

    @Test
    void anUnsetScriptsDirIsAbsent() {
        EmbeddedOptions options = EmbeddedOptions.builder().build();
        assertTrue(options.scriptsDir().isEmpty());
        assertNull(EmbeddedTransport.serveOptions(options).get("scriptsDir"));
    }

    @Test
    void anEngineThatDoesNotAdvertiseScriptsDirIsRefusedNamingTheSetter() {
        EngineUnavailable e = assertThrows(EngineUnavailable.class, () -> EmbeddedTransport.requireAdvertised(
                EmbeddedOptions.builder().scriptsDir(Path.of("/srv/rift-scripts")).build(),
                () -> JsonValue.parse(V023_BUILD_INFO)));
        assertTrue(e.getMessage().startsWith("scriptsDir needs a rift engine >= 0.24.0; this engine advertises serveOptions ["),
                e.getMessage());
        assertTrue(e.getMessage().endsWith("]. Upgrade the engine, or drop EmbeddedOptions.scriptsDir."), e.getMessage());
    }

    @Test
    void anEngineThatAdvertisesScriptsDirAcceptsIt() {
        EmbeddedTransport.requireAdvertised(EmbeddedOptions.builder().scriptsDir(Path.of("/srv/rift-scripts")).build(),
                () -> JsonValue.parse(V024_BUILD_INFO));
    }

    @Test
    void scriptsDirDoesNotStartTheAdminPlaneEagerly() {
        // it governs only imposters posted to the plane, and the plane takes it whenever it is served
        assertFalse(EmbeddedProvider.needsAdminPlaneAtStart(EmbeddedOptions.builder().scriptsDir(Path.of("/srv")).build()));
    }

    @Test
    void aNullScriptsDirIsRefused() {
        assertThrows(NullPointerException.class, () -> EmbeddedOptions.builder().scriptsDir(null));
    }
}
