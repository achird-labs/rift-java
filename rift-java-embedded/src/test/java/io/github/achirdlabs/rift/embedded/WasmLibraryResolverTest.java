package io.github.achirdlabs.rift.embedded;

import io.github.achirdlabs.rift.EmbeddedOptions;
import io.github.achirdlabs.rift.error.EngineUnavailable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code -wasm} flavour of {@code librift_ffi} (rift#1798) is opt-in: the {@code <classifier>-wasm}
 * natives jar ships it at {@code native/<classifier>-wasm/}, and only {@code wasmLibrary(true)} reads it.
 */
class WasmLibraryResolverTest {

    private static final EmbeddedOptions WASM = EmbeddedOptions.builder().wasmLibrary(true).build();
    private static final EmbeddedOptions PLAIN = EmbeddedOptions.builder().build();

    private static ClassLoader withOnlyTheWasmJar(Path root) throws Exception {
        Path dir = Files.createDirectories(root.resolve("native/linux-x86_64-wasm"));
        Files.writeString(dir.resolve("librift_ffi.so"), "WASM-FLAVOUR");
        return new URLClassLoader(new URL[] {root.toUri().toURL()}, null);
    }

    @Test
    void theResourceNamesTheFlavourDirectory() {
        assertEquals("native/darwin-aarch64/librift_ffi.dylib", NativeLibraryResolver.resourceFor(PLAIN, "darwin-aarch64"));
        assertEquals("native/darwin-aarch64-wasm/librift_ffi.dylib", NativeLibraryResolver.resourceFor(WASM, "darwin-aarch64"));
        assertEquals("native/windows-x86_64-wasm/librift_ffi.dll", NativeLibraryResolver.resourceFor(WASM, "windows-x86_64"));
    }

    @Test
    void wasmLibraryLoadsTheFlavourFromItsJar(@TempDir Path root) throws Exception {
        NativeLibraryResolver.ResolvedLibrary r = NativeLibraryResolver.resolve(WASM, Map.of(), Map.of(), "linux-x86_64",
                withOnlyTheWasmJar(root));
        assertTrue(r.temporary());
        assertArrayEquals("WASM-FLAVOUR".getBytes(), Files.readAllBytes(r.path()));
        Files.deleteIfExists(r.path());
    }

    @Test
    void theDefaultNeverPicksTheFlavourAndSaysHowTo(@TempDir Path root) throws Exception {
        ClassLoader loader = withOnlyTheWasmJar(root);
        assertFalse(NativeLibraryResolver.resolvable(PLAIN, Map.of(), Map.of(), "linux-x86_64", loader));
        EngineUnavailable e = assertThrows(EngineUnavailable.class,
                () -> NativeLibraryResolver.resolve(PLAIN, Map.of(), Map.of(), "linux-x86_64", loader));
        assertTrue(e.getMessage().contains("wasmLibrary(true)"), e.getMessage());
        assertTrue(NativeLibraryResolver.resolvable(WASM, Map.of(), Map.of(), "linux-x86_64", loader));
    }

    @Test
    void aMissingFlavourNamesItsJar(@TempDir Path root) throws Exception {
        ClassLoader empty = new URLClassLoader(new URL[] {root.toUri().toURL()}, null);
        EngineUnavailable e = assertThrows(EngineUnavailable.class,
                () -> NativeLibraryResolver.resolve(WASM, Map.of(), Map.of(), "darwin-aarch64", empty));
        assertTrue(e.getMessage().contains("rift-java-natives:darwin-aarch64-wasm"), e.getMessage());
    }

    @Test
    void anExplicitLibraryIsLoadedAsItIs(@TempDir Path dir) throws Exception {
        Path lib = Files.writeString(dir.resolve("librift_ffi-darwin-aarch64-wasm.dylib"), "x");
        NativeLibraryResolver.ResolvedLibrary r = NativeLibraryResolver.resolve(WASM, Map.of("RIFT_FFI_LIB", lib.toString()),
                Map.of(), "darwin-aarch64", ClassLoader.getSystemClassLoader());
        assertEquals(lib, r.path());
        assertFalse(r.temporary());
    }
}
