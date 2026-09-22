package io.github.achirdlabs.rift.embedded;

import io.github.achirdlabs.rift.EmbeddedOptions;
import io.github.achirdlabs.rift.UpstreamTrust;
import io.github.achirdlabs.rift.error.EngineUnavailable;
import io.github.achirdlabs.rift.json.JsonString;
import io.github.achirdlabs.rift.json.JsonValue;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    // ---- outbound TLS trust (#209) ----

    private static final String PEM = "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n";

    /** The eight keys rift 0.17.0 advertises: none of the trust options. */
    private static final String V017_BUILD_INFO = """
            {"version": "0.17.0", "serveOptions": ["host", "port", "apiKey", "metricsPort", "configFile",
             "config", "allowInjection", "requireAdminAuth"]}
            """;
    private static final String V018_BUILD_INFO = """
            {"version": "0.18.0", "serveOptions": ["host", "port", "apiKey", "metricsPort", "configFile", "noParse",
             "config", "allowInjection", "requireAdminAuth", "upstreamCaFile", "upstreamCaPem", "upstreamTlsSkipVerify"]}
            """;

    @Test
    void caFileIsSentAsUpstreamCaFile() {
        Path ca = Path.of("/etc/corp-ca.pem").toAbsolutePath();
        String json = EmbeddedTransport.serveOptions(EmbeddedOptions.builder()
                .upstreamTrust(new UpstreamTrust.CaFile(ca)).build()).toJson();
        assertEquals("{\"host\":\"127.0.0.1\",\"port\":0,\"upstreamCaFile\":"
                + new JsonString(ca.toString()).toJson() + "}", json);
    }

    @Test
    void aBackslashInTheCaPathIsEscaped() {
        // A Windows path: the payload is JSON, so each backslash must reach the engine doubled.
        String json = EmbeddedTransport.serveOptions(EmbeddedOptions.builder()
                .upstreamTrust(new UpstreamTrust.CaFile(Path.of("certs\\corp-ca.pem"))).build()).toJson();
        assertTrue(json.contains("certs\\\\corp-ca.pem"), json);
    }

    @Test
    void theEngineIsNotAskedWhenNoTrustIsSet() {
        EmbeddedTransport.requireAdvertised(EmbeddedOptions.builder().build(), () -> {
            throw new AssertionError("buildInfo must not be read without an upstreamTrust");
        });
    }

    @Test
    void onlySkipVerifyWarns() {
        assertTrue(EmbeddedTransport.skipVerifyWarning(EmbeddedOptions.builder()
                .upstreamTrust(new UpstreamTrust.SkipVerify()).build()).orElseThrow().contains("SkipVerify"));
        assertTrue(EmbeddedTransport.skipVerifyWarning(EmbeddedOptions.builder().build()).isEmpty());
        assertTrue(EmbeddedTransport.skipVerifyWarning(EmbeddedOptions.builder()
                .upstreamTrust(new UpstreamTrust.CaPem(PEM)).build()).isEmpty());
    }

    @Test
    void caPemIsSentInline() {
        String json = EmbeddedTransport.serveOptions(EmbeddedOptions.builder()
                .upstreamTrust(new UpstreamTrust.CaPem(PEM)).build()).toJson();
        assertEquals("{\"host\":\"127.0.0.1\",\"port\":0,\"upstreamCaPem\":"
                + "\"-----BEGIN CERTIFICATE-----\\nMIIB\\n-----END CERTIFICATE-----\\n\"}", json);
    }

    @Test
    void skipVerifyIsSentAsTrue() {
        String json = EmbeddedTransport.serveOptions(EmbeddedOptions.builder()
                .upstreamTrust(new UpstreamTrust.SkipVerify()).build()).toJson();
        assertEquals("{\"host\":\"127.0.0.1\",\"port\":0,\"upstreamTlsSkipVerify\":true}", json);
    }

    @Test
    void anEngineThatDoesNotAdvertiseTheKeyIsRefused() {
        EngineUnavailable e = assertThrows(EngineUnavailable.class, () -> EmbeddedTransport.requireAdvertised(
                EmbeddedOptions.builder().upstreamTrust(new UpstreamTrust.CaPem(PEM)).build(),
                () -> JsonValue.parse(V017_BUILD_INFO)));
        assertTrue(e.getMessage().contains("upstreamCaPem"), e.getMessage());
        assertTrue(e.getMessage().contains("0.18.0"), e.getMessage());
    }

    @Test
    void anEngineWithNoServeOptionsListIsRefused() {
        // Before 0.17.0 the list did not exist at all; absence is not permission.
        assertThrows(EngineUnavailable.class, () -> EmbeddedTransport.requireAdvertised(
                EmbeddedOptions.builder().upstreamTrust(new UpstreamTrust.SkipVerify()).build(),
                () -> JsonValue.parse("{\"version\": \"0.16.0\"}")));
    }

    @Test
    void eachVariantIsCheckedForItsOwnKey() {
        String onlyFile = "{\"version\": \"0.1.0\", \"serveOptions\": [\"host\", \"upstreamCaFile\"]}";
        EmbeddedTransport.requireAdvertised(
                EmbeddedOptions.builder().upstreamTrust(new UpstreamTrust.CaFile(Path.of("/ca.pem"))).build(),
                () -> JsonValue.parse(onlyFile));
        assertThrows(EngineUnavailable.class, () -> EmbeddedTransport.requireAdvertised(
                EmbeddedOptions.builder().upstreamTrust(new UpstreamTrust.SkipVerify()).build(),
                () -> JsonValue.parse(onlyFile)));
    }

    @Test
    void anEngineThatAdvertisesTheKeyPasses() {
        for (UpstreamTrust trust : new UpstreamTrust[] {
                new UpstreamTrust.CaFile(Path.of("/ca.pem")), new UpstreamTrust.CaPem(PEM), new UpstreamTrust.SkipVerify()}) {
            EmbeddedTransport.requireAdvertised(EmbeddedOptions.builder().upstreamTrust(trust).build(),
                    () -> JsonValue.parse(V018_BUILD_INFO));
        }
    }
}
