package io.github.achirdlabs.rift.embedded;

import io.github.achirdlabs.rift.EmbeddedOptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The {@code rift_serve_admin} options payload (#176). Pinned without an engine because the failure
 * this guards is a silent one: the engine's {@code ServeOptions} is a {@code camelCase} serde struct
 * whose fields are all optional, so a misspelled or snake_cased key is not an error — it is simply
 * an absent setting that falls back to the default, which is exactly the "accepted and ignored"
 * behaviour #176 exists to remove.
 */
class ServeOptionsTest {

    @Test
    void carriesHostAndPortAndApiKeyUnderTheEnginesOwnNames() {
        String json = EmbeddedTransport.serveOptions(EmbeddedOptions.builder()
                .adminHost("0.0.0.0")
                .adminPort(48080)
                .apiKey("s3cret-token")
                .build()).toJson();

        assertEquals("{\"host\":\"0.0.0.0\",\"port\":48080,\"apiKey\":\"s3cret-token\"}", json);
    }

    @Test
    void omitsTheApiKeyEntirelyWhenUnset() {
        // Absent rather than `"apiKey":null`, so the payload states only what was configured. Both
        // forms parse — serde maps null to None for an Option — so this pins the idiom, not a fix.
        String json = EmbeddedTransport.serveOptions(EmbeddedOptions.builder().build()).toJson();

        assertEquals("{\"host\":\"127.0.0.1\",\"port\":0}", json);
    }

    @Test
    void aBlankApiKeyIsRejectedRatherThanSentAsAnEmptyKey() {
        // The engine used to gate on the key being *present*, then compare it against the request's
        // Authorization header defaulted to "" — so `"apiKey":""` switched auth on and then matched
        // every unauthenticated caller. Engine 0.17.0 (achird-labs/rift#844) rejects a blank key at
        // the C-ABI instead, but the builder keeps failing first so the blank never reaches the
        // payload and the caller hears about it at the configuring call.
        assertThrows(IllegalArgumentException.class, () -> EmbeddedOptions.builder().apiKey(""));
        assertThrows(IllegalArgumentException.class, () -> EmbeddedOptions.builder().apiKey("   "));
    }

    @Test
    void anOutOfRangeAdminPortIsRejectedAtTheBuilder() {
        // The engine's port is a u16; out of range is otherwise a parse error raised far from here,
        // at whichever call first starts the admin plane.
        assertThrows(IllegalArgumentException.class, () -> EmbeddedOptions.builder().adminPort(65536));
        assertThrows(IllegalArgumentException.class, () -> EmbeddedOptions.builder().adminPort(-1));
    }

    @Test
    void theDefaultsMatchTheEnginesOwnDefaults() {
        // build_admin_plane_inner does host.unwrap_or("127.0.0.1") and port.unwrap_or(0), so sending
        // these explicitly is behaviour-preserving for every caller that never set them.
        EmbeddedOptions defaults = EmbeddedOptions.builder().build();

        assertEquals("127.0.0.1", defaults.adminHost());
        assertEquals(0, defaults.adminPort());
    }
}
