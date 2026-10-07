package io.github.achirdlabs.rift.embedded;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.AssertionFailedError;
import org.opentest4j.TestAbortedException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How the embedded ITs find {@code librift_ffi}: the SDK's own order ({@code -Drift.ffi.lib}, then
 * {@code $RIFT_FFI_LIB}), skipping only when nothing is named and no live engine was promised — a
 * lane that set {@code RIFT_IT}, or named a library that is not there, fails instead of reporting
 * {@code Tests run: 0}.
 */
class EmbeddedTestLibraryTest {

    @Test
    void theSystemPropertyWinsOverTheEnvironment(@TempDir Path dir) throws Exception {
        Path fromProp = Files.createFile(dir.resolve("prop.dylib"));
        Path fromEnv = Files.createFile(dir.resolve("env.dylib"));

        assertEquals(Optional.of(fromProp), EmbeddedTestLibrary.locate(
                Map.of("rift.ffi.lib", fromProp.toString()), Map.of("RIFT_FFI_LIB", fromEnv.toString())));
    }

    @Test
    void aBlankSystemPropertyFallsThroughToTheEnvironment(@TempDir Path dir) throws Exception {
        // The pom hands surefire an empty rift.ffi.lib unless -D is passed: that must not hide the env var.
        Path fromEnv = Files.createFile(dir.resolve("env.dylib"));

        assertEquals(Optional.of(fromEnv), EmbeddedTestLibrary.locate(
                Map.of("rift.ffi.lib", "  "), Map.of("RIFT_FFI_LIB", fromEnv.toString())));
    }

    @Test
    void nothingNamedIsEmpty() {
        assertEquals(Optional.empty(), EmbeddedTestLibrary.locate(Map.of("rift.ffi.lib", ""), Map.of()));
    }

    @Test
    void aLocalRunWithNothingNamedSkips() {
        assertThrows(TestAbortedException.class, () -> EmbeddedTestLibrary.require(Map.of(), Map.of()));
    }

    @Test
    void aLiveLaneWithNothingNamedFails() {
        for (String promised : new String[] {"1", "true", "TRUE"}) {
            AssertionFailedError e = assertThrows(AssertionFailedError.class,
                    () -> EmbeddedTestLibrary.require(Map.of("rift.ffi.lib", ""), Map.of("RIFT_IT", promised)));
            assertTrue(e.getMessage().contains("-Drift.ffi.lib") && e.getMessage().contains("RIFT_FFI_LIB"),
                    e.getMessage());
        }
    }

    @Test
    void onlyOneOrTrueIsALiveLane() {
        for (String notPromised : new String[] {"", "0", "false", "no", "yes", " 1"}) {
            assertThrows(TestAbortedException.class,
                    () -> EmbeddedTestLibrary.require(Map.of(), Map.of("RIFT_IT", notPromised)),
                    "RIFT_IT=" + notPromised + " is not a live lane, so it skips");
        }
    }

    @Test
    void aMissingPropertyPathFailsRatherThanFallingBackToTheEnvironment(@TempDir Path dir) throws Exception {
        // The operator asked for one library; running against another would test the wrong engine.
        Path missing = dir.resolve("asked-for.dylib");
        Path other = Files.createFile(dir.resolve("other.dylib"));

        AssertionFailedError e = assertThrows(AssertionFailedError.class, () -> EmbeddedTestLibrary.require(
                Map.of("rift.ffi.lib", missing.toString()), Map.of("RIFT_FFI_LIB", other.toString())));
        assertTrue(e.getMessage().contains("-Drift.ffi.lib") && e.getMessage().contains("asked-for.dylib"),
                e.getMessage());
    }

    @Test
    void aDirectoryIsNotALibrary(@TempDir Path dir) {
        AssertionFailedError e = assertThrows(AssertionFailedError.class,
                () -> EmbeddedTestLibrary.require(Map.of(), Map.of("RIFT_FFI_LIB", dir.toString())));
        assertTrue(e.getMessage().contains("not a file"), e.getMessage());
    }

    @Test
    void aLiveLaneNamingAMissingLibraryReportsTheMissingPath(@TempDir Path dir) {
        AssertionFailedError e = assertThrows(AssertionFailedError.class, () -> EmbeddedTestLibrary.require(
                Map.of(), Map.of("RIFT_IT", "1", "RIFT_FFI_LIB", dir.resolve("gone.so").toString())));
        assertTrue(e.getMessage().contains("gone.so"), "the path, not 'no library named': " + e.getMessage());
    }

    @Test
    void aNamedLibraryThatIsMissingFailsEvenOffALiveLane(@TempDir Path dir) {
        Path missing = dir.resolve("gone.dylib");

        AssertionFailedError fromEnv = assertThrows(AssertionFailedError.class,
                () -> EmbeddedTestLibrary.require(Map.of(), Map.of("RIFT_FFI_LIB", missing.toString())));
        assertTrue(fromEnv.getMessage().contains("RIFT_FFI_LIB") && fromEnv.getMessage().contains("gone.dylib"),
                fromEnv.getMessage());

        AssertionFailedError fromProp = assertThrows(AssertionFailedError.class,
                () -> EmbeddedTestLibrary.require(Map.of("rift.ffi.lib", missing.toString()), Map.of()));
        assertTrue(fromProp.getMessage().contains("-Drift.ffi.lib") && fromProp.getMessage().contains("gone.dylib"),
                fromProp.getMessage());
    }

    @Test
    void aNamedLibraryThatExistsIsReturned(@TempDir Path dir) throws Exception {
        Path lib = Files.createFile(dir.resolve("librift_ffi.dylib"));

        assertEquals(lib, EmbeddedTestLibrary.require(Map.of(), Map.of("RIFT_FFI_LIB", lib.toString(), "RIFT_IT", "1")));
    }
}
