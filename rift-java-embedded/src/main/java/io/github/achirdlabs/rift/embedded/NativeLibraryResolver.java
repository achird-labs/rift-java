package io.github.achirdlabs.rift.embedded;

import io.github.achirdlabs.rift.EmbeddedOptions;
import io.github.achirdlabs.rift.error.EngineUnavailable;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

/**
 * Resolves the {@code librift_ffi} native library to load for {@link EmbeddedTransport#open}, in
 * order: an explicit {@link EmbeddedOptions#libraryPath()}, the {@code rift.ffi.lib} system
 * property, the {@code RIFT_FFI_LIB} environment variable, a {@code native/<classifier>/librift_ffi.<ext>}
 * classpath resource (as shipped by the {@code rift-java-natives} classifier jars, extracted to a
 * temp file), or — if none resolve — a precise {@link EngineUnavailable}. With {@link
 * EmbeddedOptions#wasmLibrary()} the classpath resource is the {@code -wasm} flavour's,
 * {@code native/<classifier>-wasm/librift_ffi.<ext>} from the {@code <classifier>-wasm} jar; the explicit
 * sources are loaded as they are.
 */
final class NativeLibraryResolver {

    private NativeLibraryResolver() {
    }

    /** A resolved native library: its path, and whether it is a temp file this process extracted (and owns). */
    record ResolvedLibrary(Path path, boolean temporary) {
    }

    static ResolvedLibrary resolve(EmbeddedOptions options) {
        return resolve(options, System.getenv(), systemPropertiesView(), currentClassifier(),
                NativeLibraryResolver.class.getClassLoader());
    }

    static ResolvedLibrary resolve(
            EmbeddedOptions options, Map<String, String> env, Map<String, String> sysProps,
            String classifier, ClassLoader loader) {
        if (options.libraryPath().isPresent()) {
            return new ResolvedLibrary(requireRegularFile(options.libraryPath().get(), "EmbeddedOptions.libraryPath"), false);
        }
        String fromProp = sysProps.get("rift.ffi.lib");
        if (fromProp != null && !fromProp.isBlank()) {
            return new ResolvedLibrary(requireRegularFile(Path.of(fromProp), "-Drift.ffi.lib"), false);
        }
        String fromEnv = env.get("RIFT_FFI_LIB");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return new ResolvedLibrary(requireRegularFile(Path.of(fromEnv), "$RIFT_FFI_LIB"), false);
        }
        String ext = extFor(classifier);
        String resource = resourceFor(options, classifier);
        try (InputStream in = loader.getResourceAsStream(resource)) {
            if (in != null) {
                Path temp = Files.createTempFile("librift_ffi", "." + ext);
                temp.toFile().deleteOnExit();
                Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
                return new ResolvedLibrary(temp, true);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to extract classpath resource " + resource, e);
        }
        String jar = options.wasmLibrary() ? classifier + "-wasm" : classifier;
        throw new EngineUnavailable("no " + (options.wasmLibrary() ? "-wasm flavour of the " : "")
                + "librift_ffi native library found for " + classifier
                + ": add io.github.achird-labs:rift-java-natives:" + jar
                + " to the classpath, or set -Drift.ffi.lib / RIFT_FFI_LIB / EmbeddedOptions.libraryPath"
                + (options.wasmLibrary() ? "" : " (the " + classifier + "-wasm jar needs EmbeddedOptions.wasmLibrary(true))"));
    }

    /** Non-extracting availability probe (unlike {@link #resolve}, never writes a temp file). */
    static boolean resolvable(EmbeddedOptions options) {
        return resolvable(options, System.getenv(), systemPropertiesView(), currentClassifier(),
                NativeLibraryResolver.class.getClassLoader());
    }

    static boolean resolvable(
            EmbeddedOptions options, Map<String, String> env, Map<String, String> sysProps,
            String classifier, ClassLoader loader) {
        if (options.libraryPath().isPresent()) {
            return Files.isRegularFile(options.libraryPath().get());
        }
        String fromProp = sysProps.get("rift.ffi.lib");
        if (fromProp != null && !fromProp.isBlank()) {
            return Files.isRegularFile(Path.of(fromProp));
        }
        String fromEnv = env.get("RIFT_FFI_LIB");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return Files.isRegularFile(Path.of(fromEnv));
        }
        // getResource (not getResourceAsStream) — probe presence without opening/extracting.
        return loader.getResource(resourceFor(options, classifier)) != null;
    }

    /** The classpath resource a natives jar ships the library at: the plain one, or the {@code -wasm} flavour's. */
    static String resourceFor(EmbeddedOptions options, String classifier) {
        String dir = options.wasmLibrary() ? classifier + "-wasm" : classifier;
        return "native/" + dir + "/librift_ffi." + extFor(classifier);
    }

    private static Path requireRegularFile(Path path, String source) {
        if (!Files.isRegularFile(path)) {
            throw new EngineUnavailable("librift_ffi at " + path + " (from " + source
                    + ") does not exist or is not a regular file");
        }
        return path;
    }

    static String currentClassifier() {
        return classifierFor(System.getProperty("os.name", ""), System.getProperty("os.arch", ""),
                Files.exists(Path.of("/etc/alpine-release")));
    }

    static String classifierFor(String osName, String osArch, boolean musl) {
        String os = osName.toLowerCase();
        String arch = normalizeArch(osArch);
        String osPart;
        if (os.contains("mac") || os.contains("darwin")) {
            osPart = "darwin";
        } else if (os.contains("win")) {
            osPart = "windows";
        } else {
            osPart = "linux";
        }
        if (musl && osPart.equals("linux") && arch.equals("x86_64")) {
            return "linux-musl-x86_64";
        }
        return osPart + "-" + arch;
    }

    private static String normalizeArch(String arch) {
        String a = arch.toLowerCase();
        if (a.equals("amd64") || a.equals("x86_64")) {
            return "x86_64";
        }
        if (a.equals("aarch64") || a.equals("arm64")) {
            return "aarch64";
        }
        return a;
    }

    private static String extFor(String classifier) {
        if (classifier.startsWith("darwin")) {
            return "dylib";
        }
        if (classifier.startsWith("windows")) {
            return "dll";
        }
        return "so";
    }

    private static Map<String, String> systemPropertiesView() {
        Map<String, String> view = new java.util.HashMap<>();
        System.getProperties().forEach((k, v) -> view.put(String.valueOf(k), String.valueOf(v)));
        return view;
    }
}
