package io.github.achirdlabs.rift.embedded;

import io.github.achirdlabs.rift.EmbeddedOptions;
import io.github.achirdlabs.rift.error.EngineUnavailable;
import io.github.achirdlabs.rift.json.JsonValue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The body and journal limits in the {@code rift_serve_admin} payload, and their advertisement gate (#309). */
class ServeOptionsBodyLimitsTest {

    private static final String V021_BUILD_INFO = "{\"version\": \"0.21.0\", \"serveOptions\": "
            + "[\"host\", \"port\", \"apiKey\", \"allowInjection\", \"requireAdminAuth\", \"metricsPort\", "
            + "\"upstreamCaFile\", \"upstreamCaPem\", \"upstreamTlsSkipVerify\"]}";
    private static final String V022_BUILD_INFO = "{\"version\": \"0.22.0\", \"serveOptions\": "
            + "[\"host\", \"port\", \"maxBodySize\", \"bodyMemoryBudget\", \"journalMaxBytes\"]}";

    @Test
    void theLimitsAreSentUnderTheEnginesNamesAsPlainNumbers() {
        String json = EmbeddedTransport.serveOptions(EmbeddedOptions.builder()
                .maxBodySize(3_000_000_000L).bodyMemoryBudget(0).journalMaxBytes(4096).build()).toJson();
        assertEquals("{\"host\":\"127.0.0.1\",\"port\":0,\"maxBodySize\":3000000000,\"bodyMemoryBudget\":0,"
                + "\"journalMaxBytes\":4096}", json);
    }

    @Test
    void unsetLimitsAreAbsent() {
        String json = EmbeddedTransport.serveOptions(EmbeddedOptions.builder().build()).toJson();
        assertFalse(json.contains("maxBodySize") || json.contains("bodyMemoryBudget") || json.contains("journalMaxBytes"), json);
    }

    @Test
    void anEngineThatDoesNotAdvertiseALimitIsRefusedNamingTheSetter() {
        EngineUnavailable e = assertThrows(EngineUnavailable.class, () -> EmbeddedTransport.requireAdvertised(
                EmbeddedOptions.builder().journalMaxBytes(4096).build(), () -> JsonValue.parse(V021_BUILD_INFO)));
        // the advertised set is unordered, so only the fixed parts of the message are pinned
        assertTrue(e.getMessage().startsWith("journalMaxBytes needs a rift engine >= 0.22.0; this engine advertises serveOptions ["),
                e.getMessage());
        assertTrue(e.getMessage().endsWith("]. Upgrade the engine, or drop EmbeddedOptions.journalMaxBytes."), e.getMessage());
    }

    @Test
    void eachLimitIsCheckedForItsOwnKey() {
        for (EmbeddedOptions options : new EmbeddedOptions[] {
                EmbeddedOptions.builder().maxBodySize(1024).build(),
                EmbeddedOptions.builder().bodyMemoryBudget(0).build(),
                EmbeddedOptions.builder().journalMaxBytes(4096).build()}) {
            EngineUnavailable e = assertThrows(EngineUnavailable.class,
                    () -> EmbeddedTransport.requireAdvertised(options, () -> JsonValue.parse(V021_BUILD_INFO)));
            String key = options.maxBodySize().isPresent() ? "maxBodySize"
                    : options.bodyMemoryBudget().isPresent() ? "bodyMemoryBudget" : "journalMaxBytes";
            assertTrue(e.getMessage().startsWith(key + " needs a rift engine >= 0.22.0"), e.getMessage());
            assertTrue(e.getMessage().endsWith("drop EmbeddedOptions." + key + "."), e.getMessage());
        }
    }

    @Test
    void theAdminPlaneStartsWithTheEngineWhenAnyLimitIsSet() {
        // the engine applies the limits only to imposters created after rift_serve_admin
        assertTrue(EmbeddedProvider.needsAdminPlaneAtStart(EmbeddedOptions.builder().maxBodySize(1024).build()));
        assertTrue(EmbeddedProvider.needsAdminPlaneAtStart(EmbeddedOptions.builder().bodyMemoryBudget(0).build()));
        assertTrue(EmbeddedProvider.needsAdminPlaneAtStart(EmbeddedOptions.builder().journalMaxBytes(4096).build()));
        assertFalse(EmbeddedProvider.needsAdminPlaneAtStart(EmbeddedOptions.builder().build()));
    }

    @Test
    void anEngineThatAdvertisesTheLimitsAcceptsThem() {
        EmbeddedTransport.requireAdvertised(EmbeddedOptions.builder()
                .maxBodySize(1024).bodyMemoryBudget(0).journalMaxBytes(4096).build(), () -> JsonValue.parse(V022_BUILD_INFO));
    }
}
