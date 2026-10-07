package io.github.achirdlabs.rift.embedded;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The {@code librift_ffi} the embedded integration tests run against, found the way the SDK finds
 * it ({@code NativeLibraryResolver}): {@code -Drift.ffi.lib}, then {@code $RIFT_FFI_LIB}. The tests
 * pass it as an explicit {@code libraryPath}, so a classpath natives jar never stands in for it.
 */
final class EmbeddedTestLibrary {

    private EmbeddedTestLibrary() {
    }

    /** The library the ITs run against: {@code -Drift.ffi.lib}, else {@code $RIFT_FFI_LIB}; empty when neither is set. */
    static Optional<Path> locate(Map<String, String> sysProps, Map<String, String> env) {
        return named(sysProps.get("rift.ffi.lib")).or(() -> named(env.get("RIFT_FFI_LIB"))).map(Path::of);
    }

    /** {@link #require(Map, Map)} over this JVM's system properties and environment, for a {@code @BeforeAll}. */
    static Path require() {
        return require(Map.of("rift.ffi.lib", System.getProperty("rift.ffi.lib", "")), System.getenv());
    }

    /**
     * The library, as a JUnit gate. Skips only when no library is named and no live engine was
     * promised. A library that is named but missing fails, as {@code Rift.embedded()} would; so does a
     * lane that set {@code RIFT_IT} and named none — an assumption failing in {@code @BeforeAll} drops
     * the whole class from the count ({@code Tests run: 0}), which reads as green.
     */
    static Path require(Map<String, String> sysProps, Map<String, String> env) {
        Optional<Path> lib = locate(sysProps, env);
        if (lib.isEmpty()) {
            if (liveEngineExpected(env)) {
                fail("RIFT_IT is set but no librift_ffi is named: set -Drift.ffi.lib or RIFT_FFI_LIB to the cdylib");
            }
            assumeTrue(false, "set -Drift.ffi.lib or RIFT_FFI_LIB to a librift_ffi cdylib to run the embedded integration tests");
        }
        Path path = lib.get();
        if (!Files.isRegularFile(path)) {
            String knob = named(sysProps.get("rift.ffi.lib")).isPresent() ? "-Drift.ffi.lib" : "RIFT_FFI_LIB";
            fail(knob + " names " + path + ", which is not a file");
        }
        return path;
    }

    private static Optional<String> named(String value) {
        return Optional.ofNullable(value).filter(v -> !v.isBlank());
    }

    private static boolean liveEngineExpected(Map<String, String> env) {
        String value = env.get("RIFT_IT");
        return value != null && (value.equals("1") || value.equalsIgnoreCase("true"));
    }
}
