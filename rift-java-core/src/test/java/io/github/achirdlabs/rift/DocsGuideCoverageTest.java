package io.github.achirdlabs.rift;

import io.github.achirdlabs.rift.dsl.StubSpec;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Guide-documentation gate (#168).
 *
 * <p>Five shipped features had drifted into a state where they existed only in the dense
 * {@code docs/design/sdk-api.md} reference — no guide page, and for the event stream not even a nav
 * entry, so a reader browsing the site could not find them at all. Prose cannot be unit-tested, but
 * the two ways this actually goes wrong can be:
 *
 * <ol>
 *   <li><b>The page stops being reachable</b> — deleted, renamed, or never added to the hand-maintained
 *       {@code mkdocs.yml} nav. Checked both directions, so an orphan page and a dead nav entry each fail.</li>
 *   <li><b>The page names an API that does not exist</b> — the failure mode a docs audit found
 *       elsewhere in this repo. The symbols the guides tell a reader to copy are looked up
 *       reflectively, so a guide that documents a renamed or imagined method fails here rather than
 *       in that reader's build.</li>
 * </ol>
 *
 * <p>It deliberately does not grade the writing, and it is not a proof: prose can still be wrong in
 * ways no assertion sees. It grades reachability and the truthfulness of the copyable API surface,
 * which are the parts a regression can silently take away.
 *
 * <p>The behavioural claim about blank flow ids is <em>not</em> re-asserted here — {@link
 * FlowIdValidationTest} already covers all six call sites, and with a stronger assertion than this
 * file would add.
 */
class DocsGuideCoverageTest {

    /** Module basedir is rift-java-core under surefire; the docs live at the repo root. */
    private static final Path REPO = Path.of("..");
    private static final Path DOCS = REPO.resolve("docs");
    private static final Path MKDOCS = REPO.resolve("mkdocs.yml");

    /** A nav line's markdown target, e.g. {@code - JUnit 5: junit5.md}. */
    private static final Pattern NAV_TARGET = Pattern.compile(":\\s*\"?([A-Za-z0-9._/-]+\\.md)\"?\\s*$");

    /**
     * Guide page → the API strings it must contain for its feature to count as documented. Chosen to
     * be exact call-site spellings a reader would copy, not loose prose words, so a page that merely
     * name-drops a feature does not satisfy them. Receiver-agnostic on purpose ({@code .events(}, not
     * {@code rift.events(}) so renaming a variable in a sample is not a build break.
     */
    private static final List<Map.Entry<String, List<String>>> REQUIRED = List.of(
            Map.entry("events.md", List.of(
                    "events(EventStreamOptions", "EventStreamOptions", "RiftEvent.Hello",
                    "RiftEvent.RequestRecorded", "RiftEvent.ImposterChanged", "RiftEvent.Lagged")),
            Map.entry("spaces.md", List.of(
                    // spaces + flow-scoped cursor reads (#149)
                    ".space(", "recordedPage(", "recordedSince(", "inSpace(",
                    // the MatchClause vocabulary and its engine floor (#148)
                    "MatchClause.header(", "MatchClause.flowId(", "MatchClause.method(",
                    "MatchClause.path(", "0.15.0",
                    // flow-scoped scenario state (#151)
                    "setState(",
                    // blank flow id is rejected, with the reason (#153)
                    "IllegalArgumentException", "blank")),
            Map.entry("wasm.md", List.of(
                    // the rift 0.25.0 surface (rift-java#354) and its floor
                    "0.25.0", ".clock()", ".set(", ".advance(", ".reset()", "ClockSource.HEADER", "X-Rift-Now",
                    ".seed(", ".rngKey(", "unsignedSeed()", "Date.now()", "Math.random()", "{{NOW}}",
                    "Script.wasmFile(", "Script.wasm(", "Script.component(", ".export(", ".knobs(", ".instance(",
                    ".components()", ".wasmInstances()", ".wasmSnapshot(", ".allowWasm(", ".strictWasm(",
                    "withAllowWasm(", ".wasmLibrary(true)", "-wasm")));

    @TestFactory
    Stream<DynamicTest> everyGuidePageDocumentsItsFeature() {
        return REQUIRED.stream().map(e -> DynamicTest.dynamicTest(e.getKey(), () -> {
            Path page = DOCS.resolve(e.getKey());
            assertTrue(Files.exists(page), "missing guide page " + page.toAbsolutePath()
                    + " — #168 asks for a task-oriented guide, not another section in sdk-api.md");
            String body = read(page);
            List<String> absent = e.getValue().stream().filter(s -> !body.contains(s)).toList();
            if (!absent.isEmpty()) {
                fail(e.getKey() + " never mentions " + absent + " — the feature is not actually"
                        + " documented for a reader who lands on this page (#168).");
            }
        }));
    }

    @Test
    void everyDocsPageIsReachableFromTheNav() {
        Set<String> targets = navTargets();
        List<Path> pages = markdownPages();
        // A silent empty scan would make this vacuously green if docs/ ever moved.
        assertTrue(pages.size() >= 5, "expected the docs tree at " + DOCS.toAbsolutePath()
                + " but found " + pages.size() + " pages");

        for (Path page : pages) {
            String rel = DOCS.relativize(page).toString().replace('\\', '/');
            assertTrue(targets.contains(rel),
                    "docs/" + rel + " exists but no mkdocs.yml nav entry points at it — it would be"
                            + " published as an orphan the site menu never links (#168).");
        }
    }

    @Test
    void everyNavEntryPointsAtAPageThatExists() {
        Set<String> targets = navTargets();
        for (String target : targets) {
            assertTrue(Files.exists(DOCS.resolve(target)),
                    "mkdocs.yml nav points at " + target + " which does not exist under docs/");
        }
    }

    /**
     * The API surface the guides hand a reader to copy. Reflection, not prose-matching: a renamed or
     * imagined method fails here instead of in that reader's build. Signatures are asserted, not just
     * names, because an arity change is exactly the drift that leaves a sample looking right.
     */
    @Test
    void theApiTheGuidesDocumentReallyExists() throws Exception {
        // events.md
        Rift.class.getMethod("events", EventStreamOptions.class);
        Rift.class.getMethod("connect", java.net.URI.class);
        Rift.class.getMethod("spawn");
        Rift.class.getMethod("embedded");
        EventStreamOptions.class.getMethod("builder");
        Class<?> builder = Class.forName(EventStreamOptions.class.getName() + "$Builder");
        builder.getMethod("types", EventStreamOptions.EventType[].class);
        builder.getMethod("port", int.class);
        builder.getMethod("match", MatchClause[].class);
        builder.getMethod("build");
        for (String variant : List.of("Hello", "RequestRecorded", "ImposterChanged", "Lagged")) {
            Class.forName(RiftEvent.class.getName() + "$" + variant);
        }

        // spaces.md
        Imposter.class.getMethod("space", String.class);
        Imposter.class.getMethod("flowState", String.class);
        Imposter.class.getMethod("recordedPage");
        Imposter.class.getMethod("recordedPage", MatchClause[].class);
        Imposter.class.getMethod("recordedSince", long.class);
        Imposter.class.getMethod("recordedSince", long.class, MatchClause[].class);
        Imposter.class.getMethod("clearRecorded", MatchClause[].class);
        Space.class.getMethod("recordedPage", MatchClause[].class);
        Space.class.getMethod("recordedSince", long.class, MatchClause[].class);
        Space.class.getMethod("delete");
        StubSpec.class.getMethod("inSpace", String.class);
        FlowState.class.getMethod("get", String.class);
        FlowState.class.getMethod("put", String.class, String.class);
        FlowState.class.getMethod("delete", String.class);
        Scenarios.class.getMethod("setState", String.class, String.class, String.class);
        Scenarios.class.getMethod("list", String.class);
        RecordedPage.class.getMethod("nextIndex");
        RecordedPage.class.getMethod("truncated");

        MatchClause.class.getMethod("header", String.class, String.class);
        MatchClause.class.getMethod("flowId", String.class);
        MatchClause.class.getMethod("method", String.class);
        MatchClause.class.getMethod("path", String.class);

        // wasm.md
        Imposter.class.getMethod("clock");
        Imposter.class.getMethod("wasmInstances");
        Imposter.class.getMethod("wasmSnapshot");
        Imposter.class.getMethod("wasmSnapshot", String.class);
        ImposterClock.class.getMethod("set", java.time.Instant.class);
        ImposterClock.class.getMethod("advance", java.time.Duration.class);
        ImposterClock.class.getMethod("reset");
        ImposterClock.class.getMethod("get");
        Rift.class.getMethod("components");
        Components.class.getMethod("upload", java.nio.file.Path.class);
        Components.class.getMethod("upload", byte[].class);
        Components.class.getMethod("get", String.class);
        Components.class.getMethod("delete", String.class);
        ComponentInfo.class.getMethod("reference");
        Class<?> imposterSpec = io.github.achirdlabs.rift.dsl.ImposterSpec.class;
        imposterSpec.getMethod("seed", long.class);
        imposterSpec.getMethod("rngKey", String[].class);
        imposterSpec.getMethod("clock", io.github.achirdlabs.rift.dsl.ClockSource.class);
        imposterSpec.getMethod("wasmLimits", io.github.achirdlabs.rift.model.WasmLimits.class);
        Class<?> script = io.github.achirdlabs.rift.dsl.Script.class;
        script.getMethod("wasmFile", String.class);
        script.getMethod("wasm", byte[].class);
        script.getMethod("component", String.class);
        script.getMethod("export", io.github.achirdlabs.rift.dsl.WasmExport.class);
        script.getMethod("knobs", io.github.achirdlabs.rift.json.JsonValue.class);
        script.getMethod("instance", io.github.achirdlabs.rift.dsl.WasmInstanceScope.class);
        io.github.achirdlabs.rift.model.RiftScriptEngineConfig.class.getMethod("unsignedSeed");
        io.github.achirdlabs.rift.model.WasmLimits.class.getMethod("withFuel", long.class);
        io.github.achirdlabs.rift.model.WasmLimits.class.getMethod("withMaxMemoryMiB", long.class);
        Class.forName(SpawnOptions.class.getName() + "$Builder").getMethod("allowWasm", boolean.class);
        Class.forName(SpawnOptions.class.getName() + "$Builder").getMethod("strictWasm", java.nio.file.Path.class);
        Class.forName(EmbeddedOptions.class.getName() + "$Builder").getMethod("allowWasm", boolean.class);
        Class.forName(EmbeddedOptions.class.getName() + "$Builder").getMethod("strictWasm", java.nio.file.Path.class);
        Class.forName(EmbeddedOptions.class.getName() + "$Builder").getMethod("wasmLibrary", boolean.class);
    }

    /** Every {@code *.md} the nav declares, as docs-relative paths. */
    private static Set<String> navTargets() {
        Set<String> targets = new LinkedHashSet<>();
        for (String line : read(MKDOCS).lines().toList()) {
            Matcher m = NAV_TARGET.matcher(line);
            if (m.find()) {
                targets.add(m.group(1));
            }
        }
        // Without this, a nav whose formatting drifted past the pattern would make both nav checks
        // pass having compared nothing at all.
        assertTrue(targets.size() >= 5,
                "parsed only " + targets.size() + " nav targets from " + MKDOCS.toAbsolutePath()
                        + " — has the nav format changed?");
        return targets;
    }

    private static List<Path> markdownPages() {
        try (Stream<Path> tree = Files.walk(DOCS)) {
            return tree.filter(p -> p.getFileName().toString().endsWith(".md")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot walk the docs tree at " + DOCS, e);
        }
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + p, e);
        }
    }
}
