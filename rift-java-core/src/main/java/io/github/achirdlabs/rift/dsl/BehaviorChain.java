package io.github.achirdlabs.rift.dsl;

import io.github.achirdlabs.rift.json.JsonArray;
import io.github.achirdlabs.rift.model.Behavior;
import io.github.achirdlabs.rift.model.Behaviors;
import io.github.achirdlabs.rift.model.CopyEntry;
import io.github.achirdlabs.rift.model.WaitSpec;

import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.List;

/**
 * Behavior chainers ({@code wait}/{@code decorate}/{@code repeat}/{@code copy}/{@code lookup}/{@code
 * shellTransform}), shared by every response kind the engine runs behaviors on: {@link IsSpec},
 * {@link ProxySpec} and {@link InjectSpec}. Each chainer returns a new spec of the same kind, so a
 * behavior can sit anywhere in a chain.
 *
 * <p>On a {@code proxy} response the behaviors run on the upstream response before it is served
 * and before it is recorded; on an {@code inject} response they run on what the script returned.
 * Behaviors on {@code proxy}/{@code inject} require a rift engine &ge; 0.18.0 — older engines accept
 * the block and drop it without a word, so {@link io.github.achirdlabs.rift.Rift#create(
 * io.github.achirdlabs.rift.model.ImposterDefinition)} refuses such a definition with {@link
 * io.github.achirdlabs.rift.error.InvalidDefinition} when it can see the engine is older. Only the
 * typed imposter-definition paths ({@code create}, {@code replaceAll}) are checked; stubs added to a
 * running imposter ({@code addStub}, {@code replaceStubs}, {@code StubRef.replace}) are sent as-is.
 *
 * <p>Calling the same chainer twice appends two entries, and both are written — as a {@code
 * behaviors} array, the only wire shape that can carry a repeated key (see {@link Behaviors}).
 *
 * <p>Chain order is run order on rift &ge; 0.18.0 and on Mountebank: when {@code lookup}, {@code
 * copy}, {@code shellTransform} and {@code decorate} are chained out of the engine's fixed object
 * order, the SDK writes the {@code behaviors} array, which runs in the order written. rift 0.17.0
 * runs its own fixed order whatever the form.
 *
 * @param <S> the spec kind each chainer returns
 */
public sealed interface BehaviorChain<S extends ResponseSpec & BehaviorChain<S>>
        permits IsSpec, ProxySpec, InjectSpec {

    /** Appends {@code behavior} to this response's behaviors. Every other chainer is built on this one. */
    S withBehavior(Behavior behavior);

    /** Delays the response by the given duration (a {@code wait} behavior with a fixed delay). */
    default S after(Duration duration) {
        return waitMs(duration.toMillis());
    }

    /**
     * Delays the response by a fixed number of milliseconds (a {@code wait} behavior).
     *
     * <p>Named {@code waitMs} rather than {@code wait}: {@code wait(long)} would silently attempt to
     * override the {@code final} {@link Object#wait(long)} and fail to compile.
     */
    default S waitMs(long milliseconds) {
        return withBehavior(new Behavior.Wait(new WaitSpec.Fixed(milliseconds)));
    }

    /** Delays the response by a random duration in {@code [minMs, maxMs]} (a {@code wait} behavior with a range). */
    default S waitBetween(long minMs, long maxMs) {
        return withBehavior(new Behavior.Wait(new WaitSpec.Range(minMs, maxMs)));
    }

    /**
     * Delays the response by a duration computed by the given script (an {@code inject}ed {@code wait}
     * value) — rift's object spelling, a superset not portable to Mountebank (rift#608).
     *
     * <p>A function wait is an injection surface in either spelling: the engine must run with
     * {@code --allowInjection} or it rejects the imposter with a 400 ({@link
     * io.github.achirdlabs.rift.error.InvalidDefinition}). {@link #waitMs} and {@link #waitBetween}
     * are unaffected.
     */
    default S waitInject(String script) {
        return withBehavior(new Behavior.Wait(new WaitSpec.Inject(script)));
    }

    /**
     * Delays the response by a bare-string {@code wait} (a function body / named latency), round-tripped
     * verbatim — the Mountebank-compatible spelling of {@link #waitInject}, and equally an injection
     * surface: it needs the engine's {@code --allowInjection} for the same reason (rift#610).
     */
    default S waitScript(String source) {
        return withBehavior(new Behavior.Wait(new WaitSpec.Script(source)));
    }

    /**
     * Post-processes the response with the given decorator script (a {@code decorate} behavior). An
     * injection surface: the engine must run with {@code --allowInjection}.
     */
    default S decorate(String script) {
        return withBehavior(new Behavior.Decorate(script));
    }

    /**
     * Serves this response only for the first {@code count} matches, after which the stub's next
     * response takes over (a {@code repeat} behavior).
     */
    default S repeat(int count) {
        return withBehavior(new Behavior.Repeat(count));
    }

    /** Adds a {@code copy} behavior entry per {@link CopySpec} given (the array wire form). */
    default S copy(CopySpec... copies) {
        List<CopyEntry> entries = Arrays.stream(copies).map(CopySpec::build).toList();
        return withBehavior(new Behavior.Copy(entries));
    }

    /** Adds a single {@code copy} entry in the engine's object wire form (not wrapped in an array). */
    default S copyObject(CopySpec copy) {
        return withBehavior(new Behavior.Copy(List.of(copy.build()), true));
    }

    /**
     * Adds a {@code lookup} behavior: an array of lookup entries, each keyed by a request extraction
     * and resolved against a data source. There is no typed {@code Behavior.Lookup} — this rides
     * {@link Behavior.Unknown} so it round-trips losslessly while still emitting the correct wire
     * shape.
     */
    default S lookup(LookupSpec... lookups) {
        JsonArray array = new JsonArray(Arrays.stream(lookups).map(LookupSpec::build).toList());
        return withBehavior(new Behavior.Unknown("lookup", array));
    }

    /** Adds a single {@code lookup} entry in the engine's object wire form (not wrapped in an array). */
    default S lookupObject(LookupSpec lookup) {
        return withBehavior(new Behavior.Unknown("lookup", lookup.build()));
    }

    /**
     * Adds a {@code shellTransform} step per command, run in order, each receiving the previous
     * one's output. One command is written as {@code "shellTransform": "cmd"}; several are written
     * as one {@code behaviors} array element per command, the shape the engine itself echoes. An
     * injection surface: the engine must run with {@code --allowInjection}.
     *
     * @throws IllegalArgumentException if no command is given
     */
    default S shellTransform(String... commands) {
        if (commands.length == 0) {
            throw new IllegalArgumentException("shellTransform needs at least one command");
        }
        S next = null;
        for (String command : commands) {
            Behavior step = new Behavior.ShellTransform(Objects.requireNonNull(command, "command"));
            next = next == null ? withBehavior(step) : next.withBehavior(step);
        }
        return next;
    }
}
