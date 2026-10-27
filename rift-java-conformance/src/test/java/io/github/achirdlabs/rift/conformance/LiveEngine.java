package io.github.achirdlabs.rift.conformance;

import io.github.achirdlabs.rift.Rift;
import io.github.achirdlabs.rift.SpawnOptions;
import org.junit.jupiter.api.DynamicTest;

import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Shared scaffolding for the corpus-free live-engine ITs (#179): how to start an engine for the
 * selected lane, and the two gate shapes those suites use. Each suite used to carry its own copy,
 * so the lane switch had to be kept in step by hand across seven files.
 *
 * <p>Deliberately not folded into {@link ConformanceTransport}. That enum's own {@code engine(Corpus)}
 * is parameterised by the corpus and is what {@code CorpusReplayIT} needs; the suites here need an
 * engine with no corpus at all. Keeping the two apart is why {@code CorpusReplayIT} starts its own
 * engine rather than calling {@link #engine()} — it still shares {@link #integrationEnabled()},
 * which is only an environment read.
 */
final class LiveEngine {

    private static final String NOT_ENABLED = "set RIFT_IT=1 to run the live-engine lane";

    private LiveEngine() {}

    /** The engine for the selected lane. Exhaustive on purpose: a new transport is a compile error here, once. */
    static Rift engine() {
        return switch (ConformanceTransport.selected()) {
            case SPAWN -> Rift.spawn(SpawnOptions.builder().build());
            case EMBEDDED -> Rift.embedded();
        };
    }

    /**
     * The engine for the selected lane, reading lookup CSVs and modules under {@code scriptsDir}. A
     * spawned engine needs it as {@code --scripts-dir} from rift 0.24.0 (rift#1617); the embedded
     * host calls are not confined, so that lane ignores it.
     */
    static Rift engine(Path scriptsDir) {
        return switch (ConformanceTransport.selected()) {
            case SPAWN -> Rift.spawn(SpawnOptions.builder().scriptsDir(scriptsDir).build());
            case EMBEDDED -> Rift.embedded();
        };
    }

    /**
     * A claim that holds on every transport: gated only on the live-engine lane being enabled and
     * the selected one being startable here.
     *
     * <p>The two conditions are separate {@code assumeTrue}s so a lane that silently lost
     * {@code RIFT_IT} is distinguishable from one whose engine is genuinely unavailable — telling
     * those apart is the whole point of reporting them separately.
     */
    static Stream<DynamicTest> gated(String name, Executable body) {
        return Stream.of(DynamicTest.dynamicTest(name, () -> {
            assumeTrue(integrationEnabled(), NOT_ENABLED);
            requireLane(ConformanceTransport.selected());
            body.run();
        }));
    }

    /**
     * A claim that exists on one transport only.
     *
     * @param lane   the only transport this claim is about
     * @param reason why it is that transport only — suite-specific and load-bearing, so each caller
     *               states its own rather than sharing a generic one
     */
    static Stream<DynamicTest> gatedTo(ConformanceTransport lane, String reason, String name, Executable body) {
        return Stream.of(DynamicTest.dynamicTest(name, () -> {
            assumeTrue(integrationEnabled(), NOT_ENABLED);
            assumeTrue(ConformanceTransport.selected() == lane, reason);
            requireLane(lane);
            body.run();
        }));
    }

    private static void requireLane(ConformanceTransport lane) {
        assumeTrue(lane.isAvailable(),
                "the " + lane + " lane cannot start an engine here (embedded needs a librift_ffi)");
    }

    /** True when the live-engine lanes are switched on ({@code RIFT_IT} set to anything but {@code 0}). */
    static boolean integrationEnabled() {
        return integrationEnabled(System.getenv("RIFT_IT"));
    }

    /**
     * The rule itself, over a raw value — split out so it can be tested without an environment, the
     * same reason {@link ConformanceTransport#resolve} takes its two sources as parameters. Every
     * live-engine suite now gates on this one function, so the branches are worth pinning.
     */
    static boolean integrationEnabled(String rawRiftIt) {
        return rawRiftIt != null && !rawRiftIt.isBlank() && !rawRiftIt.equals("0");
    }

    @FunctionalInterface
    interface Executable {
        void run() throws Exception;
    }
}
