package io.github.achirdlabs.rift.conformance;

import io.github.achirdlabs.rift.json.JsonArray;
import io.github.achirdlabs.rift.json.JsonBool;
import io.github.achirdlabs.rift.json.JsonNumber;
import io.github.achirdlabs.rift.json.JsonObject;
import io.github.achirdlabs.rift.json.JsonString;
import io.github.achirdlabs.rift.json.JsonValue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Locates and loads the engine-canonical SDK conformance corpus
 * ({@code sdk-conformance-<version>.tar.gz}, extracted).
 *
 * <p>Resolution order for the corpus home (the directory holding {@code manifest.json} and the
 * {@code corpus/} payload):
 * <ol>
 *   <li>the {@code rift.corpus.root} system property,</li>
 *   <li>the {@code RIFT_CORPUS_ROOT} environment variable,</li>
 *   <li>{@code target/corpus} under the module (where CI extracts the release asset).</li>
 * </ol>
 * If the resolved directory has no {@code manifest.json} but contains a single extracted
 * {@code sdk-conformance-*} child, that child is used — so pointing at a directory of tarballs or an
 * un-stripped extraction both work.
 *
 * <p>The corpus is version-locked to the engine: {@link #engineVersion()} comes from the manifest
 * and is the version the replay lane must spawn (contract §5).
 */
final class Corpus {

    private static final Set<String> CAPABILITIES = Set.of("injection", "proxy", "redis", "https", "shell");
    private static final Pattern FIXTURE_NUMBER = Pattern.compile("(\\d+)");

    private final Path home;
    private final JsonObject manifest;

    private Corpus(Path home, JsonObject manifest) {
        this.home = home;
        this.manifest = manifest;
    }

    /** The corpus home if one is resolvable and holds a readable {@code manifest.json}, else empty. */
    static Optional<Corpus> locate() {
        return tryLoad(resolveHome());
    }

    /**
     * Loads the corpus, or aborts/fails: when {@code RIFT_IT=1} (the CI conformance lane) a missing
     * corpus is a hard failure (the download step is broken); otherwise the calling test is skipped.
     */
    static Corpus loadOrSkip() {
        Path home = resolveHome();
        Optional<Corpus> corpus = tryLoad(home);
        if (corpus.isPresent()) {
            return corpus.get();
        }
        if (ciLaneRequiresCorpus()) {
            throw new IllegalStateException(
                    "conformance corpus not found at " + home + " but RIFT_IT=1 requires it — the corpus "
                            + "download step is broken (or rift.corpus.root / RIFT_CORPUS_ROOT points at the wrong place).");
        }
        org.junit.jupiter.api.Assumptions.abort(
                "conformance corpus not found at " + home + "; set -Drift.corpus.root=<extracted sdk-conformance dir> to run it");
        throw new AssertionError("unreachable");
    }

    /** The engine version this corpus is locked to (from the manifest; leading {@code v} stripped). */
    String engineVersion() {
        String v = string(manifest.get("engineVersion"));
        return v.startsWith("v") ? v.substring(1) : v;
    }

    /**
     * The engine's imposter JSON Schema ({@code schema/imposter.schema.json}), shipped in the
     * sdk-conformance tarball from rift 0.22.0 (rift#1342). Detected by presence, not by version.
     */
    Optional<Path> schema() {
        Path schema = home.resolve("schema").resolve("imposter.schema.json");
        return Files.isRegularFile(schema) ? Optional.of(schema) : Optional.empty();
    }

    /** The {@code corpus/} payload directory — the replayer's working directory (contract: cwd = corpus/). */
    Path payloadRoot() {
        return home.resolve("corpus");
    }

    /**
     * Absolutizes a fixture's relative {@code fromDataSource.csv.path} against {@code payloadRoot}.
     *
     * <p>The spawn transport sets the engine's working directory to {@code corpus/}, so a fixture's
     * {@code "path": "data/products.csv"} resolves. The embedded engine runs in-process and inherits
     * the JVM working directory instead, so the same relative path would not resolve — this rewrites
     * it to an absolute path under the corpus root. The walk is typed (it only touches a {@code path}
     * inside a {@code fromDataSource.csv} object) and leaves already-absolute paths and lookalike
     * strings elsewhere untouched.
     */
    static JsonValue rewriteForEmbedded(JsonValue imposter, Path payloadRoot) {
        return rewriteNode(imposter, payloadRoot);
    }

    private static JsonValue rewriteNode(JsonValue node, Path payloadRoot) {
        if (node instanceof JsonArray arr) {
            return JsonArray.of(arr.items().stream().map(item -> rewriteNode(item, payloadRoot)).toList());
        }
        if (node instanceof JsonObject obj) {
            Map<String, JsonValue> fields = new java.util.LinkedHashMap<>();
            obj.fields().forEach((key, value) -> fields.put(key,
                    key.equals("fromDataSource") ? rewriteDataSource(value, payloadRoot) : rewriteNode(value, payloadRoot)));
            return new JsonObject(fields);
        }
        return node;
    }

    private static JsonValue rewriteDataSource(JsonValue dataSource, Path payloadRoot) {
        if (!(dataSource instanceof JsonObject ds) || !(ds.get("csv") instanceof JsonObject csv)
                || !(csv.get("path") instanceof JsonString pathValue)) {
            return rewriteNode(dataSource, payloadRoot);
        }
        Path path = Path.of(pathValue.value());
        if (path.isAbsolute()) {
            return dataSource;
        }
        Map<String, JsonValue> csvFields = new java.util.LinkedHashMap<>(csv.fields());
        csvFields.put("path", new JsonString(payloadRoot.resolve(path).toString()));
        Map<String, JsonValue> dsFields = new java.util.LinkedHashMap<>(ds.fields());
        dsFields.put("csv", new JsonObject(csvFields));
        return new JsonObject(dsFields);
    }

    /** The fixtures declared by the manifest, in declared order. */
    List<FixtureCase> fixtures() {
        JsonValue fixtures = manifest.get("fixtures");
        if (!(fixtures instanceof JsonArray arr)) {
            throw new IllegalStateException("manifest.json has no fixtures array");
        }
        List<FixtureCase> out = new ArrayList<>();
        for (JsonValue entry : arr.items()) {
            out.add(toFixture((JsonObject) entry));
        }
        return List.copyOf(out);
    }

    private FixtureCase toFixture(JsonObject entry) {
        String file = string(entry.get("file"));
        Path path = home.resolve(file);
        String raw = readString(path);
        Set<String> requires = new LinkedHashSet<>();
        if (entry.get("requires") instanceof JsonArray reqs) {
            for (JsonValue r : reqs.items()) {
                String cap = string(r);
                if (!CAPABILITIES.contains(cap)) {
                    throw new IllegalStateException("fixture " + file + " requires unknown capability '" + cap + "'");
                }
                requires.add(cap);
            }
        }
        boolean hasVerify = entry.get("hasVerify") instanceof JsonBool b && b.value();
        int port = entry.get("port") instanceof JsonNumber n ? Integer.parseInt(n.raw().trim()) : 0;
        return new FixtureCase(numberOf(file), string(entry.get("name")), port,
                Set.copyOf(requires), hasVerify, path, raw);
    }

    private static int numberOf(String file) {
        String name = Path.of(file).getFileName().toString();
        Matcher m = FIXTURE_NUMBER.matcher(name);
        if (!m.find()) {
            throw new IllegalStateException("fixture file has no leading number: " + file);
        }
        return Integer.parseInt(m.group(1));
    }

    static boolean ciLaneRequiresCorpus() {
        String it = System.getenv("RIFT_IT");
        return it != null && !it.isBlank() && !it.equals("0");
    }

    private static Path resolveHome() {
        String prop = System.getProperty("rift.corpus.root");
        if (prop != null && !prop.isBlank()) {
            return descend(Path.of(prop));
        }
        String env = System.getenv("RIFT_CORPUS_ROOT");
        if (env != null && !env.isBlank()) {
            return descend(Path.of(env));
        }
        return descend(Path.of("target", "corpus"));
    }

    /** If {@code dir} has no {@code manifest.json} but a single {@code sdk-conformance-*} child does, descend. */
    private static Path descend(Path dir) {
        if (Files.isRegularFile(dir.resolve("manifest.json"))) {
            return dir;
        }
        if (!Files.isDirectory(dir)) {
            return dir;
        }
        try (Stream<Path> children = Files.list(dir)) {
            List<Path> extracted = children
                    .filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().startsWith("sdk-conformance-"))
                    .filter(p -> Files.isRegularFile(p.resolve("manifest.json")))
                    .toList();
            return extracted.size() == 1 ? extracted.get(0) : dir;
        } catch (IOException e) {
            // Listing failed for a reason unrelated to "corpus absent" (permissions, a flaky mount).
            // Surface it so a broken lookup isn't misreported downstream as a missing download.
            System.err.println("conformance corpus: could not list " + dir + " while resolving the corpus home: " + e);
            return dir;
        }
    }

    private static Optional<Corpus> tryLoad(Path home) {
        Path manifestFile = home.resolve("manifest.json");
        if (!Files.isRegularFile(manifestFile)) {
            return Optional.empty();
        }
        JsonValue parsed = JsonValue.parse(readString(manifestFile));
        if (!(parsed instanceof JsonObject obj)) {
            throw new IllegalStateException("manifest.json is not a JSON object: " + manifestFile);
        }
        return Optional.of(new Corpus(home, obj));
    }

    private static String readString(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + path, e);
        }
    }

    private static String string(JsonValue v) {
        if (v instanceof JsonString s) {
            return s.value();
        }
        throw new IllegalStateException("expected a JSON string, got: " + (v == null ? "null" : v.toJson()));
    }
}
