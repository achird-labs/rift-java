package io.github.achirdlabs.rift.embedded;

import io.github.achirdlabs.rift.error.EngineUnavailable;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An incompatible library is diagnosed by probing the full C-ABI v2 symbol set at {@code bind} time.
 * A pre-v2 (or wrong) library lacks some symbols, and binding must fail fast with a single precise
 * message naming <em>all</em> of them — not one per rebuild. Uses a fake {@link SymbolLookup} so no
 * real v1 lib is needed.
 */
class FfiProbeTest {

    @Test
    void emptyLibraryReportsUnsupportedLibrary() {
        SymbolLookup noSymbols = name -> Optional.empty();

        EngineUnavailable ex = assertThrows(EngineUnavailable.class,
                () -> RiftFfi.bind(noSymbols, Linker.nativeLinker()));

        String msg = ex.getMessage();
        assertTrue(msg.contains("0.13.1") || msg.toLowerCase().contains("rift_build_info"),
                "message should name the required version or a missing symbol: " + msg);
    }

    @Test
    void reportsAllMissingSymbolsAtOnceWithSource() {
        // A library that has everything except two symbols — the message must list both, not stop at the first.
        Set<String> absent = Set.of("rift_add_stub", "rift_verify");
        SymbolLookup partial = name -> absent.contains(name) ? Optional.empty() : Optional.of(MemorySegment.NULL);

        EngineUnavailable ex = assertThrows(EngineUnavailable.class,
                () -> RiftFfi.bind(partial, Linker.nativeLinker(), "/path/to/librift_ffi.dylib"));

        String msg = ex.getMessage();
        assertTrue(msg.contains("rift_add_stub") && msg.contains("rift_verify"),
                "message must list every missing symbol: " + msg);
        assertTrue(msg.contains("missing 2 of"), "message must report the count: " + msg);
        assertTrue(msg.contains("/path/to/librift_ffi.dylib"), "message must name the source library: " + msg);
    }

    @Test
    void stopInterceptIsRequiredAndTheUnusedTruststoreExportIsNot() {
        // rift_stop_intercept backs Intercept.close(); the SDK writes truststores itself, so a library
        // without rift_intercept_export_truststore must not be refused for it.
        Set<String> absent = Set.of("rift_stop_intercept", "rift_intercept_export_truststore");
        SymbolLookup partial = name -> absent.contains(name) ? Optional.empty() : Optional.of(MemorySegment.NULL);

        EngineUnavailable ex = assertThrows(EngineUnavailable.class,
                () -> RiftFfi.bind(partial, Linker.nativeLinker(), "/path/to/librift_ffi.dylib"));

        String msg = ex.getMessage();
        assertTrue(msg.contains("rift_stop_intercept"), msg);
        assertFalse(msg.contains("rift_intercept_export_truststore"), msg);
        assertTrue(msg.contains("missing 1 of"), msg);
    }
}
