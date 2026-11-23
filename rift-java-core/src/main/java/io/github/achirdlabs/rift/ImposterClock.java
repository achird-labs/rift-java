package io.github.achirdlabs.rift;

import java.time.Duration;
import java.time.Instant;

/**
 * An imposter's own clock (rift &ge; 0.25.0): wall time until a test freezes or steps it, so a test on
 * a fake clock moves the mock's time with it. The built-in flow store's TTLs, a wasm component's
 * {@code wasi:clocks}, JavaScript {@code Date.now()}, Rhai {@code timestamp_header()} and the template
 * {@code now} read it. It is runtime state: never persisted, and back on wall time after a restart or
 * when the imposter is recreated. Obtained from {@link Imposter#clock()}.
 *
 * <p>Instants run from year 1 to year 9999; the engine refuses one outside that with {@code 400}
 * ({@link io.github.achirdlabs.rift.error.InvalidDefinition}). On an engine before 0.25.0, which has no
 * clock route, every method throws {@link UnsupportedOperationException}.
 */
public interface ImposterClock {

    /** Whether the clock follows wall time or stands where a test put it. */
    enum Mode {
        /** Wall time. */
        WALL,
        /** Frozen at an instant: by {@link #set(Instant)}, or by {@link #advance(Duration)} from wall time. */
        FROZEN
    }

    /** What the clock reads: its mode and the instant it shows now, to the millisecond. */
    record Reading(Mode mode, Instant now) {
        public Reading {
            java.util.Objects.requireNonNull(mode, "mode");
            java.util.Objects.requireNonNull(now, "now");
        }

        /** {@link #now()} as milliseconds since the Unix epoch, the engine's unit. */
        public long nowMs() {
            return now.toEpochMilli();
        }
    }

    /** {@code GET /imposters/{port}/clock}. */
    Reading get();

    /** Freezes the clock at {@code instant}, earlier or later than now ({@code PUT … {"nowMs": …}}). */
    Reading set(Instant instant);

    /**
     * Steps the clock forward ({@code PUT … {"advanceMs": …}}): a frozen clock moves by {@code by}; a
     * wall clock freezes at wall time plus {@code by}.
     *
     * @throws IllegalArgumentException if {@code by} is negative: the engine never moves a clock back
     *                                  by a step (freeze it at an earlier instant with {@link #set(Instant)})
     */
    Reading advance(Duration by);

    /** Back to wall time ({@code DELETE /imposters/{port}/clock}). */
    Reading reset();
}
