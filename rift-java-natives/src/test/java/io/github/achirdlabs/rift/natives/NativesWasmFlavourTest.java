package io.github.achirdlabs.rift.natives;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * rift 0.25.0's {@code -wasm} flavour (rift#1798) is staged beside the plain library, under its own
 * classifier directory, so the plain one stays what a platform jar holds.
 */
class NativesWasmFlavourTest {

    private static final List<String> PLATFORMS = List.of(
            "darwin-aarch64", "darwin-x86_64", "linux-aarch64", "linux-x86_64", "linux-x86_64-musl", "windows-x86_64");

    private static String ext(String platform) {
        return platform.startsWith("darwin") ? "dylib" : platform.startsWith("windows") ? "dll" : "so";
    }

    /** One manifest entry per platform (and its wasm flavour, when asked), in the 0.25.0 shape with {@code wasm}. */
    private static void writeBundle(Path dir, List<String> platforms, boolean withWasm, Map<String, String> wasmFlagOverride)
            throws Exception {
        List<String> entries = new ArrayList<>();
        List<String> all = new ArrayList<>(platforms);
        if (withWasm) {
            platforms.forEach(p -> all.add(p + "-wasm"));
        }
        for (String platform : all) {
            String base = platform.replace("-wasm", "");
            String file = "librift_ffi-" + platform + "." + ext(base);
            byte[] bytes = ("lib:" + platform).getBytes(StandardCharsets.UTF_8);
            Files.write(dir.resolve(file), bytes);
            String wasm = wasmFlagOverride.getOrDefault(platform, Boolean.toString(platform.endsWith("-wasm")));
            entries.add("{\"platform\":\"" + platform + "\",\"file\":\"" + file + "\",\"sha256\":\""
                    + FakeReleaseServer.sha256Hex(bytes) + "\",\"url\":\"http://example.invalid/" + file
                    + "\",\"wasm\":" + wasm + "}");
        }
        Files.writeString(dir.resolve("ffi-manifest.json"),
                "{\"version\":\"v0.25.0\",\"abi\":\"v2\",\"artifacts\":[" + String.join(",", entries) + "]}");
    }

    private static void stage(Path offline, Path staging) throws Exception {
        NativesFetcher.stageAll("0.25.0", staging, Map.of("RIFT_NATIVES_DIR", offline.toString()));
    }

    @Test
    void eachFlavourIsStagedUnderItsOwnClassifier(@TempDir Path offline, @TempDir Path staging) throws Exception {
        writeBundle(offline, PLATFORMS, true, Map.of());
        stage(offline, staging);
        Path plain = staging.resolve("native/darwin-aarch64/librift_ffi.dylib");
        Path wasm = staging.resolve("native/darwin-aarch64-wasm/librift_ffi.dylib");
        assertArrayEquals("lib:darwin-aarch64".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(plain));
        assertArrayEquals("lib:darwin-aarch64-wasm".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(wasm));
        assertTrue(Files.readString(staging.resolve("native/darwin-aarch64-wasm/BUILD_INFO.json")).contains("\"wasm\":true"));
        // musl keeps its classifier remap, with the suffix last.
        assertTrue(Files.exists(staging.resolve("native/linux-musl-x86_64-wasm/librift_ffi.so")));
    }

    @Test
    void theClassifierRemapKeepsTheSuffixLast() {
        assertEquals("linux-musl-x86_64-wasm", NativesFetcher.classifierFor("linux-x86_64-musl-wasm"));
        assertEquals("linux-musl-x86_64", NativesFetcher.classifierFor("linux-x86_64-musl"));
        assertEquals("windows-x86_64-wasm", NativesFetcher.classifierFor("windows-x86_64-wasm"));
    }

    @Test
    void aReleaseWithoutTheFlavourStagesOnlyThePlainLibraries(@TempDir Path offline, @TempDir Path staging) throws Exception {
        writeBundle(offline, PLATFORMS, false, Map.of());
        stage(offline, staging);
        assertTrue(Files.exists(staging.resolve("native/linux-x86_64/librift_ffi.so")));
        assertTrue(Files.notExists(staging.resolve("native/linux-x86_64-wasm")));
    }

    @Test
    void aPartialSetOfFlavoursFailsRatherThanShipAnEmptyJar(@TempDir Path offline, @TempDir Path staging) throws Exception {
        writeBundle(offline, PLATFORMS, false, Map.of());
        // add one flavour only
        byte[] bytes = "lib:linux-x86_64-wasm".getBytes(StandardCharsets.UTF_8);
        Files.write(offline.resolve("librift_ffi-linux-x86_64-wasm.so"), bytes);
        String manifest = Files.readString(offline.resolve("ffi-manifest.json")).replace("]}",
                ",{\"platform\":\"linux-x86_64-wasm\",\"file\":\"librift_ffi-linux-x86_64-wasm.so\",\"sha256\":\""
                        + FakeReleaseServer.sha256Hex(bytes) + "\",\"url\":\"x\",\"wasm\":true}]}");
        Files.writeString(offline.resolve("ffi-manifest.json"), manifest);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> stage(offline, staging));
        assertTrue(e.getMessage().contains("darwin-aarch64-wasm"), e.getMessage());
    }

    @Test
    void aWasmFlagThatContradictsThePlatformFails(@TempDir Path offline, @TempDir Path staging) throws Exception {
        writeBundle(offline, PLATFORMS, true, Map.of("linux-aarch64", "true"));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> stage(offline, staging));
        assertTrue(e.getMessage().contains("linux-aarch64"), e.getMessage());
    }
}
