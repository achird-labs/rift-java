package io.github.achirdlabs.rift;

import io.github.achirdlabs.rift.json.JsonValue;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The outbound TLS trust value and its plumbing through the two transports' options (#209). */
class UpstreamTrustTest {

    private static final String PEM = "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n";

    @Test
    void caPemMustContainACertificateBlock() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new UpstreamTrust.CaPem("not a certificate"));
        assertTrue(e.getMessage().contains("BEGIN CERTIFICATE"), e.getMessage());
        // A private key is PEM too, and the likeliest wrong file to paste.
        assertThrows(IllegalArgumentException.class,
                () -> new UpstreamTrust.CaPem("-----BEGIN PRIVATE KEY-----\nMIIB\n-----END PRIVATE KEY-----\n"));
        assertEquals(PEM, new UpstreamTrust.CaPem(PEM).pem());
    }

    @Test
    void variantsRejectNull() {
        assertThrows(NullPointerException.class, () -> new UpstreamTrust.CaFile(null));
        assertThrows(NullPointerException.class, () -> new UpstreamTrust.CaPem(null));
    }

    @Test
    void embeddedOptionsCarryOneTrustSetting() {
        assertTrue(EmbeddedOptions.builder().build().upstreamTrust().isEmpty());
        UpstreamTrust trust = new UpstreamTrust.CaFile(Path.of("/etc/ca.pem"));
        assertEquals(trust, EmbeddedOptions.builder().upstreamTrust(trust).build().upstreamTrust().orElseThrow());
        // A later call replaces the earlier one: file + PEM, or skip + CA, cannot both be set.
        EmbeddedOptions replaced = EmbeddedOptions.builder()
                .upstreamTrust(trust).upstreamTrust(new UpstreamTrust.SkipVerify()).build();
        assertEquals(new UpstreamTrust.SkipVerify(), replaced.upstreamTrust().orElseThrow());
    }

    @Test
    void spawnAcceptsCaFileAndSkipVerify() {
        assertTrue(SpawnOptions.builder().build().upstreamTrust().isEmpty());
        UpstreamTrust file = new UpstreamTrust.CaFile(Path.of("/etc/ca.pem"));
        assertEquals(file, SpawnOptions.builder().upstreamTrust(file).build().upstreamTrust().orElseThrow());
        SpawnOptions skip = SpawnOptions.builder().upstreamTrust(new UpstreamTrust.SkipVerify()).build();
        assertEquals(new UpstreamTrust.SkipVerify(), skip.upstreamTrust().orElseThrow());
        SpawnOptions replaced = SpawnOptions.builder()
                .upstreamTrust(new UpstreamTrust.SkipVerify()).upstreamTrust(file).build();
        assertEquals(file, replaced.upstreamTrust().orElseThrow(), "a later call replaces the earlier one");
    }

    @Test
    void aRelativeCaFileIsResolvedAgainstThisJvmsWorkingDirectory() {
        // A spawned engine may run in SpawnOptions.workingDir; the path it is handed must not depend on that.
        Path relative = Path.of("certs", "ca.pem");
        Path pem = new UpstreamTrust.CaFile(relative).pem();
        assertTrue(pem.isAbsolute(), pem.toString());
        assertEquals(Path.of("").toAbsolutePath().resolve(relative), pem);
    }

    @Test
    void spawnRejectsInlinePemBecauseTheCliHasNoFlagForIt() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SpawnOptions.builder().upstreamTrust(new UpstreamTrust.CaPem(PEM)).build());
        assertTrue(e.getMessage().contains("CaFile"), "points at the alternative: " + e.getMessage());
    }

    @Test
    void spawnRejectsTrustOnAnEngineOlderThan018() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SpawnOptions.builder().version("0.17.0").upstreamTrust(new UpstreamTrust.SkipVerify()).build());
        assertTrue(e.getMessage().contains("0.18.0"), e.getMessage());
        SpawnOptions.builder().version("0.17.0").build();
        SpawnOptions.builder().version("v0.18.0").upstreamTrust(new UpstreamTrust.SkipVerify()).build();
    }

    @Test
    void engineInfoReadsServeOptions() {
        EngineInfo info = EngineInfo.read(JsonValue.parse("""
                {"version": "0.18.0", "features": [], "serveOptions": ["host", "upstreamCaPem", 7]}
                """));
        assertEquals(Set.of("host", "upstreamCaPem"), info.serveOptions(), "non-string entries are ignored");
    }

    @Test
    void engineInfoWithoutServeOptionsHasAnEmptySet() {
        EngineInfo info = EngineInfo.read(JsonValue.parse("{\"version\": \"0.16.0\"}"));
        assertTrue(info.serveOptions().isEmpty());
        // The pre-#209 constructor still exists for callers that build one by hand.
        assertTrue(new EngineInfo("0.18.0", "", Set.of("f")).serveOptions().isEmpty());
        assertEquals(List.of(), List.copyOf(new EngineInfo("0.18.0", "", Set.of()).serveOptions()));
    }
}
