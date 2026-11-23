package io.github.achirdlabs.rift.natives;

import io.github.achirdlabs.rift.json.JsonArray;
import io.github.achirdlabs.rift.json.JsonBool;
import io.github.achirdlabs.rift.json.JsonObject;
import io.github.achirdlabs.rift.json.JsonString;
import io.github.achirdlabs.rift.json.JsonValue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

/**
 * Downloads (or reads from an air-gapped mirror), SHA-256 verifies, and stages the per-platform
 * {@code librift_ffi} native libraries published alongside a pinned Rift engine release.
 *
 * <p>Staged layout under {@code stagingDir}: {@code native/<classifier>/librift_ffi.<ext>} plus a
 * {@code native/<classifier>/BUILD_INFO.json} recording the manifest entry the library came from.
 * The {@code natives-bundle} Maven profile runs {@link #main(String[])} in {@code prepare-package}
 * and then jars each classifier subtree separately.
 *
 * <p>From rift 0.25.0 (rift#1798) each platform also ships a {@code -wasm} flavour, the library with
 * the WebAssembly engine, listed as platform {@code <platform>-wasm} with {@code "wasm": true}. It is
 * staged under {@code native/<classifier>-wasm/} and jarred as the {@code <classifier>-wasm} classifier,
 * so the plain library stays the default and a consumer opts in by depending on the other jar.
 */
public final class NativesFetcher {

    private NativesFetcher() {}

    /** Every platform the SDK ships a classifier jar for; a manifest missing any of these is a hard error. */
    private static final Set<String> EXPECTED_CLASSIFIERS = Set.of(
            "darwin-aarch64", "darwin-x86_64", "linux-aarch64",
            "linux-x86_64", "linux-musl-x86_64", "windows-x86_64");

    /** The suffix the manifest and the classifier jars give the wasm flavour. */
    private static final String WASM_SUFFIX = "-wasm";

    /** The rift release convention: tags are {@code v<version>}, assets sit under that tag. */
    public static String defaultBaseUrl(String version) {
        return "https://github.com/achird-labs/rift/releases/download/v" + version + "/";
    }

    /**
     * Stages every platform artifact listed in the {@code ffi-manifest.json} for {@code version}.
     *
     * <p>Two sourcing modes, selected by {@code env}:
     *
     * <ul>
     *   <li>{@code RIFT_NATIVES_DIR} set: air-gapped/mirror mode. The manifest and every artifact's
     *       bytes are read from that local directory; no network call is made at all.
     *   <li>otherwise: the manifest and each artifact are fetched over HTTP from {@code
     *       RIFT_NATIVES_BASE_URL} (or the {@link #defaultBaseUrl(String)} derived from {@code
     *       version}). If {@code RIFT_NATIVES_AUTH} is set, every request carries an {@code
     *       Authorization: Bearer <value>} header.
     * </ul>
     *
     * Every artifact's bytes are SHA-256 verified against the manifest before staging.
     */
    public static void stageAll(String version, Path stagingDir, Map<String, String> env)
            throws IOException, InterruptedException {
        String baseUrl = env.getOrDefault("RIFT_NATIVES_BASE_URL", defaultBaseUrl(version));
        if (!baseUrl.endsWith("/")) {
            baseUrl = baseUrl + "/";
        }

        String airGapDir = env.get("RIFT_NATIVES_DIR");
        ArtifactSource source;
        String manifestJson;
        if (airGapDir != null) {
            Path dir = Path.of(airGapDir);
            manifestJson = Files.readString(dir.resolve("ffi-manifest.json"), StandardCharsets.UTF_8);
            source = file -> Files.readAllBytes(dir.resolve(file));
        } else {
            String finalBaseUrl = baseUrl;
            String authToken = env.get("RIFT_NATIVES_AUTH");
            // NORMAL (not ALWAYS) follows redirects but refuses an HTTPS->HTTP downgrade. GitHub
            // release-asset URLs 302 to a CDN host, so without this the very first fetch fails.
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            manifestJson = new String(
                    fetchBytes(client, finalBaseUrl + "ffi-manifest.json", authToken), StandardCharsets.UTF_8);
            source = file -> fetchBytes(client, finalBaseUrl + file, authToken);
        }

        stageArtifacts(manifestJson, source, stagingDir);
    }

    private static void stageArtifacts(String manifestJson, ArtifactSource source, Path stagingDir)
            throws IOException, InterruptedException {
        JsonValue root = JsonValue.parse(manifestJson);
        if (!(root instanceof JsonObject manifest)) {
            throw new IllegalStateException("ffi-manifest.json root is not a JSON object");
        }
        if (!(manifest.get("artifacts") instanceof JsonArray artifacts)) {
            throw new IllegalStateException("ffi-manifest.json is missing an 'artifacts' array");
        }

        Set<String> staged = new HashSet<>();
        for (JsonValue entry : artifacts.items()) {
            if (!(entry instanceof JsonObject artifact)) {
                throw new IllegalStateException("ffi-manifest.json artifact entry is not a JSON object");
            }
            staged.add(stageOne(artifact, source, stagingDir));
        }
        // A manifest gap (or a platform typo) would otherwise yield a silently-empty classifier jar.
        Set<String> missing = new HashSet<>(EXPECTED_CLASSIFIERS);
        missing.removeAll(staged);
        if (!missing.isEmpty()) {
            throw new IllegalStateException("ffi-manifest.json is missing expected platform(s): " + missing);
        }
        // A release before 0.25.0 has no wasm flavour at all, and its build jars none. One that has any
        // must have every one, or a -wasm classifier jar would ship empty.
        if (staged.stream().anyMatch(c -> c.endsWith(WASM_SUFFIX))) {
            Set<String> missingWasm = new HashSet<>();
            EXPECTED_CLASSIFIERS.forEach(c -> missingWasm.add(c + WASM_SUFFIX));
            missingWasm.removeAll(staged);
            if (!missingWasm.isEmpty()) {
                throw new IllegalStateException("ffi-manifest.json lists a -wasm flavour but not for platform(s): "
                        + missingWasm + " — those classifier jars would be empty");
            }
        }
    }

    private static String stageOne(JsonObject artifact, ArtifactSource source, Path stagingDir)
            throws IOException, InterruptedException {
        String platform = requireStringField(artifact, "platform");
        String file = requireStringField(artifact, "file");
        String expectedSha256 = requireStringField(artifact, "sha256");

        byte[] bytes = source.fetch(file);

        String actualSha256 = sha256Hex(bytes);
        if (!actualSha256.equalsIgnoreCase(expectedSha256)) {
            throw new IllegalStateException(
                    "SHA-256 mismatch for " + file + ": expected " + expectedSha256 + " but got " + actualSha256);
        }

        requireFlavourAgrees(artifact, platform);
        String classifier = classifierFor(platform);
        String ext = file.substring(file.lastIndexOf('.') + 1);
        Path dir = stagingDir.resolve("native").resolve(classifier);
        Files.createDirectories(dir);
        Files.write(dir.resolve("librift_ffi." + ext), bytes);
        // Re-serializing the parsed entry (rather than hand-building JSON) guarantees BUILD_INFO.json
        // matches the manifest verbatim, including the original platform string.
        Files.writeString(dir.resolve("BUILD_INFO.json"), artifact.toJson(), StandardCharsets.UTF_8);
        return classifier;
    }

    /**
     * "linux-x86_64-musl" is the one platform whose classifier name doesn't match the manifest's; its
     * wasm flavour "linux-x86_64-musl-wasm" maps the same way, keeping the suffix last.
     */
    static String classifierFor(String platform) {
        if (platform.endsWith(WASM_SUFFIX)) {
            return classifierFor(platform.substring(0, platform.length() - WASM_SUFFIX.length())) + WASM_SUFFIX;
        }
        return "linux-x86_64-musl".equals(platform) ? "linux-musl-x86_64" : platform;
    }

    /**
     * The manifest's {@code wasm} flag must agree with the platform's suffix: staging a wasm library as
     * the plain one (or the reverse) is the mix-up rift#1798 fixed in the engine's own assets. An entry
     * without the flag (a release before 0.25.0) is read by its suffix alone.
     */
    private static void requireFlavourAgrees(JsonObject artifact, String platform) {
        JsonValue flag = artifact.get("wasm");
        if (flag == null) {
            return;
        }
        if (!(flag instanceof JsonBool wasm)) {
            throw new IllegalStateException("ffi-manifest.json entry " + platform + " has a non-boolean 'wasm'");
        }
        if (wasm.value() != platform.endsWith(WASM_SUFFIX)) {
            throw new IllegalStateException("ffi-manifest.json entry " + platform + " says \"wasm\": " + wasm.value()
                    + ", which its platform name contradicts");
        }
    }

    private static String requireStringField(JsonObject obj, String key) {
        if (!(obj.get(key) instanceof JsonString s)) {
            throw new IllegalStateException(
                    "ffi-manifest.json artifact entry is missing required string field '" + key + "'");
        }
        return s.value();
    }

    private static byte[] fetchBytes(HttpClient client, String url, String authToken)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).GET();
        if (authToken != null) {
            request.header("Authorization", "Bearer " + authToken);
        }
        HttpResponse<byte[]> response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("unexpected HTTP " + response.statusCode() + " fetching " + url);
        }
        return response.body();
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // Every JDK ships SHA-256; this can only mean a broken/stripped-down runtime.
            throw new IllegalStateException("SHA-256 MessageDigest unavailable", e);
        }
    }

    /** Supplies an artifact's raw bytes, from either the network or an air-gapped directory. */
    @FunctionalInterface
    private interface ArtifactSource {
        byte[] fetch(String file) throws IOException, InterruptedException;
    }

    /**
     * CLI entry point run by the {@code natives-bundle} Maven profile via exec-maven-plugin.
     *
     * @param args {@code [0]} = engine version (no leading 'v'), {@code [1]} = staging directory.
     */
    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("usage: NativesFetcher <version> <stagingDir>");
            System.exit(1);
            return;
        }
        try {
            stageAll(args[0], Path.of(args[1]), System.getenv());
        } catch (Throwable t) {
            // Throwable (not just Exception) so a StackOverflowError from a pathological manifest still
            // yields the actionable message rather than a raw trace; the exec profile sets
            // blockSystemExit=true so this exit fails the goal without killing the Maven JVM.
            System.err.println("rift-java-natives: failed to stage native libraries: " + t);
            System.err.println(
                    "  Behind a proxy/air gap? Pre-stage the librift_ffi cdylibs (+ ffi-manifest.json) in a "
                            + "directory and set RIFT_NATIVES_DIR=<dir> to read them locally, or point "
                            + "RIFT_NATIVES_BASE_URL=<url> at a reachable mirror of the rift release assets.");
            System.exit(1);
        }
    }
}
