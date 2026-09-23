package io.github.achirdlabs.rift.model;

import io.github.achirdlabs.rift.json.JsonObject;
import io.github.achirdlabs.rift.json.JsonString;
import io.github.achirdlabs.rift.json.JsonValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A stub response. Exactly one of {@code is}/{@code proxy}/{@code inject}/{@code fault} is
 * present, or the response is generated entirely by a {@code _rift} script (no {@code is} block).
 * Priority when more than one is present, matching the engine: {@code is} &gt; {@code proxy} &gt;
 * {@code inject} &gt; {@code fault} &gt; script-only. A flat/recorded form (top-level
 * {@code statusCode}/{@code headers}/{@code body}/{@code _mode}, optionally with a {@code _rift}
 * extension) reads as an {@code is} response, normalized to the canonical {@code is:{}} shape. Any
 * other object — no known kind, no flat fields — also reads as an {@code is} response, preserving
 * its unknown keys via {@link IsResponse#extra} rather than dropping them. The {@code proxy}/{@code
 * inject}/{@code fault}/script-only kinds — and the wrapped {@code is} form — each carry an {@code
 * extra} escape hatch for co-present sibling top-level keys, so no kind silently drops data.
 */
public sealed interface Response {

    /**
     * A literal ("is") response. {@code extra} carries any top-level wire keys that sat alongside a
     * <em>wrapped</em> {@code is:{}} (e.g. an unknown/future key, or a {@code proxy:null} migration-compat
     * sibling) so they survive a parse → serialize round-trip instead of being dropped. The typed
     * siblings ({@code _behaviors}/{@code behaviors}/{@code _rift}) and {@code is} itself are never in
     * {@code extra}. The flat/recorded form keeps its unknowns inside {@link IsResponse#extra} instead.
     */
    record Is(IsResponse is, Behaviors behaviors, Optional<RiftResponseExtension> rift,
            Map<String, JsonValue> extra) implements Response {
        public Is {
            Objects.requireNonNull(is, "is");
            Objects.requireNonNull(behaviors, "behaviors");
            Objects.requireNonNull(rift, "rift");
            Objects.requireNonNull(extra, "extra");
            JsonSupport.rejectModeledExtraKeys(extra, Set.of("is", "_behaviors", "behaviors", "_rift", "repeat"), "is response");
            extra = JsonSupport.orderedCopy(extra);
        }

        public Is(IsResponse is, Behaviors behaviors, Optional<RiftResponseExtension> rift) {
            this(is, behaviors, rift, Map.of());
        }
    }

    /**
     * A proxy response. {@code behaviors} run on the upstream response before it is served or
     * recorded (rift &ge; 0.18.0; older engines accept the block and drop it). {@code extra} carries
     * any other top-level wire keys that sat alongside {@code proxy} (unknown/future keys, a
     * {@code _rift} sibling) so they survive a parse → serialize round-trip instead of being dropped —
     * {@code proxy} and the behavior keys are never in {@code extra}.
     */
    record Proxy(ProxyResponse proxy, Behaviors behaviors, Map<String, JsonValue> extra) implements Response {
        public Proxy {
            Objects.requireNonNull(proxy, "proxy");
            Objects.requireNonNull(behaviors, "behaviors");
            Objects.requireNonNull(extra, "extra");
            JsonSupport.rejectModeledExtraKeys(extra, Set.of("proxy", "_behaviors", "behaviors", "repeat"), "proxy response");
            extra = JsonSupport.orderedCopy(extra);
        }

        /**
         * A proxy response whose behaviors, if any, are still spelled as raw keys in {@code extra}
         * — the only way to attach them before they were typed. They are lifted into {@link
         * #behaviors()}, exactly as a parse would read them.
         */
        public Proxy(ProxyResponse proxy, Map<String, JsonValue> extra) {
            this(proxy, liftBehaviors(extra), withoutBehaviorKeys(extra));
        }

        public Proxy(ProxyResponse proxy) {
            this(proxy, Behaviors.EMPTY, Map.of());
        }
    }

    /**
     * An inject (script) response. {@code behaviors} run on the script's response (rift &ge; 0.18.0;
     * a script that fails runs none). {@code extra} preserves other sibling top-level keys (see
     * {@link Proxy}).
     */
    record Inject(String script, Behaviors behaviors, Map<String, JsonValue> extra) implements Response {
        public Inject {
            Objects.requireNonNull(script, "script");
            Objects.requireNonNull(behaviors, "behaviors");
            Objects.requireNonNull(extra, "extra");
            JsonSupport.rejectModeledExtraKeys(extra, Set.of("inject", "_behaviors", "behaviors", "repeat"), "inject response");
            extra = JsonSupport.orderedCopy(extra);
        }

        /** An inject response whose raw behavior keys in {@code extra} are lifted, as for {@link Proxy}. */
        public Inject(String script, Map<String, JsonValue> extra) {
            this(script, liftBehaviors(extra), withoutBehaviorKeys(extra));
        }

        public Inject(String script) {
            this(script, Behaviors.EMPTY, Map.of());
        }
    }

    /**
     * A raw-fault response. The engine runs no behavior on it but {@code repeat} (rift &ge; 0.18.0;
     * older engines drop the block); the rest of a block is kept and written back, normalised as {@link Behaviors} reads it. {@code
     * extra} preserves other sibling top-level keys (see {@link Proxy}).
     */
    record Fault(String fault, Behaviors behaviors, Map<String, JsonValue> extra) implements Response {
        public Fault {
            Objects.requireNonNull(fault, "fault");
            Objects.requireNonNull(behaviors, "behaviors");
            Objects.requireNonNull(extra, "extra");
            JsonSupport.rejectModeledExtraKeys(extra, Set.of("fault", "_behaviors", "behaviors", "repeat"), "fault response");
            extra = JsonSupport.orderedCopy(extra);
        }

        /** A fault response whose raw behavior keys in {@code extra} are lifted, as for {@link Proxy}. */
        public Fault(String fault, Map<String, JsonValue> extra) {
            this(fault, liftBehaviors(extra), withoutBehaviorKeys(extra));
        }

        public Fault(String fault) {
            this(fault, Behaviors.EMPTY, Map.of());
        }
    }

    /**
     * Rift script-only response: no {@code is} block, the response is generated by the script. The
     * engine runs no behavior on it but {@code repeat} (rift &ge; 0.18.0; older engines drop the
     * block); the rest of a block is kept and written back, normalised as {@link Behaviors} reads it. {@code extra} carries any other
     * top-level wire keys that sat alongside {@code _rift} (unknown/future keys) so they survive a
     * parse → serialize round-trip instead of being dropped — {@code _rift} and the behavior keys
     * are never in {@code extra}.
     */
    record RiftScript(RiftResponseExtension rift, Behaviors behaviors, Map<String, JsonValue> extra) implements Response {
        public RiftScript {
            Objects.requireNonNull(rift, "rift");
            Objects.requireNonNull(behaviors, "behaviors");
            Objects.requireNonNull(extra, "extra");
            JsonSupport.rejectModeledExtraKeys(extra, Set.of("_rift", "_behaviors", "behaviors", "repeat"), "rift script");
            extra = JsonSupport.orderedCopy(extra);
        }

        /** A script response whose raw behavior keys in {@code extra} are lifted, as for {@link Proxy}. */
        public RiftScript(RiftResponseExtension rift, Map<String, JsonValue> extra) {
            this(rift, liftBehaviors(extra), withoutBehaviorKeys(extra));
        }

        public RiftScript(RiftResponseExtension rift) {
            this(rift, Behaviors.EMPTY, Map.of());
        }
    }

    static Response read(JsonObject obj) {
        if (obj.get("is") != null) {
            return new Is(
                    IsResponse.read(JsonSupport.requireObject(obj.get("is"), "is")),
                    readAllBehaviors(obj),
                    readRift(obj),
                    JsonSupport.extraFields(obj, Set.of("is", "_behaviors", "behaviors", "_rift", "repeat")));
        }
        if (obj.get("proxy") != null) {
            return new Proxy(
                    ProxyResponse.read(JsonSupport.requireObject(obj.get("proxy"), "proxy")),
                    readAllBehaviors(obj),
                    JsonSupport.extraFields(obj, Set.of("proxy", "_behaviors", "behaviors", "repeat")));
        }
        if (obj.get("inject") != null) {
            return new Inject(
                    JsonSupport.requireString(obj, "inject"),
                    readAllBehaviors(obj),
                    JsonSupport.extraFields(obj, Set.of("inject", "_behaviors", "behaviors", "repeat")));
        }
        if (obj.get("fault") != null) {
            return new Fault(
                    JsonSupport.requireString(obj, "fault"),
                    readAllBehaviors(obj),
                    JsonSupport.extraFields(obj, Set.of("fault", "_behaviors", "behaviors", "repeat")));
        }
        boolean hasFlatIsFields = obj.has("statusCode") || obj.has("headers") || obj.has("body") || obj.has("_mode");
        // A script-only response is "no is block" (per this interface's contract): a bare _rift with
        // no flat is-fields. A _rift ALONGSIDE flat is-fields is the flat equivalent of the wrapped
        // {is:{}, _rift:{}} form, so it falls through to the Is branch below (which keeps both) rather
        // than being read as script-only, which would drop the is-fields.
        if (obj.get("_rift") != null && !hasFlatIsFields) {
            return new RiftScript(
                    RiftResponseExtension.read(JsonSupport.requireObject(obj.get("_rift"), "_rift")),
                    readAllBehaviors(obj),
                    JsonSupport.extraFields(obj, Set.of("_rift", "_behaviors", "behaviors", "repeat")));
        }
        // Flat/recorded form: a bare "is" response with no wrapper — top-level statusCode/headers/
        // body/_mode, as recorded stubs look. Read it as an Is (never lose the fields); it writes
        // back in the canonical is:{} shape. Behaviors and _rift are response-level siblings, so they
        // are read separately and stripped from the is-content; any genuinely unknown is-key is still
        // preserved through IsResponse.extra. An object naming no known kind and no flat fields lands
        // here too, so its unknown keys are preserved via IsResponse.extra rather than dropped.
        Behaviors behaviors = readAllBehaviors(obj);
        JsonObject isContent = JsonSupport.withoutKeys(obj, "_behaviors", "behaviors", "_rift", "repeat");
        return new Is(IsResponse.read(isContent), behaviors, readRift(obj));
    }

    /**
     * Reads behaviors from either shape: the {@code _behaviors} object every fixture uses, or the
     * {@code behaviors} array-of-single-key-objects the engine itself emits in {@code GET /imposters}
     * output. Accepting the array form keeps consuming engine output from silently dropping them.
     */
    private static Behaviors readBehaviors(JsonObject obj) {
        JsonValue underscore = obj.get("_behaviors");
        if (underscore != null) {
            return Behaviors.read(JsonSupport.requireObject(underscore, "_behaviors"));
        }
        JsonValue array = obj.get("behaviors");
        if (array != null) {
            return Behaviors.readArray(JsonSupport.requireArray(array, "behaviors"));
        }
        return Behaviors.EMPTY;
    }

    /**
     * Writes the behaviors block under the key whose shape preserves every entry and its run order:
     * {@code behaviors} (the array form) when a key repeats or the transforming behaviors are out of
     * the engine's fixed object order, else the {@code _behaviors} object every fixture uses.
     *
     * <p>Each {@code repeat} is written back in the spelling it arrived in — a response-level one
     * beside {@code is}, a block one inside the block — so a read followed by a write never moves
     * it. That matters because the two spellings are not interchangeable across engine versions:
     * rift 0.17.0 and earlier honour only the block one and silently ignore a response-level field.
     */
    private static void writeBehaviors(JsonObject.Builder builder, Behaviors behaviors) {
        behaviors.responseLevelRepeat().ifPresent(repeat -> builder.put("repeat", repeat.value()));
        Behaviors block = behaviors.withoutResponseLevelRepeat();
        if (block.isEmpty()) {
            return;
        }
        if (block.requiresArrayForm()) {
            builder.put("behaviors", block.toJsonArray());
        } else {
            builder.put("_behaviors", block.toJsonValue());
        }
    }

    /**
     * Folds a response-level {@code repeat} into the typed behaviors. It is Mountebank's canonical
     * spelling and the one the engine's save format writes, but it used to land in {@code extra} —
     * round-tripped, yet invisible to anything reading the behaviors (issue #216).
     *
     * <p>A {@code repeat} already inside the block is <em>kept</em>, not replaced. Which one applies
     * depends on the engine — the response-level one wins where it is honoured, and rift
     * 0.17.0 and earlier ignore it and honour the block one — so discarding either here would change how the
     * imposter behaves after nothing more than a read and a write. {@link Behaviors#effectiveRepeat()}
     * reports precedence instead.
     */
    private static Behaviors foldResponseLevelRepeat(Behaviors behaviors, JsonObject obj) {
        JsonValue value = obj.get("repeat");
        if (value == null) {
            return behaviors;
        }
        // Read through Behavior.read so a non-numeric value fails exactly as the block spelling does.
        int count = ((Behavior.Repeat) Behavior.read("repeat", value)).count();
        List<Behavior> entries = new ArrayList<>(behaviors.entries());
        entries.add(new Behavior.Repeat(count, true));
        return new Behaviors(entries);
    }

    /** The behaviors block in either shape, plus a response-level {@code repeat} beside it. */
    private static Behaviors readAllBehaviors(JsonObject obj) {
        return foldResponseLevelRepeat(readBehaviors(obj), obj);
    }

    private static Behaviors liftBehaviors(Map<String, JsonValue> extra) {
        return readAllBehaviors(new JsonObject(extra));
    }

    private static Map<String, JsonValue> withoutBehaviorKeys(Map<String, JsonValue> extra) {
        return JsonSupport.withoutKeys(new JsonObject(extra), "_behaviors", "behaviors", "repeat").fields();
    }

    private static Optional<RiftResponseExtension> readRift(JsonObject obj) {
        JsonValue rift = obj.get("_rift");
        return Optional.ofNullable(rift).map(v -> RiftResponseExtension.read(JsonSupport.requireObject(v, "_rift")));
    }

    /**
     * Writes this response. Uses an {@code instanceof} chain rather than a pattern-matching
     * {@code switch}: the latter is still a preview feature at the Java 17 release level this
     * module compiles against (finalized only in Java 21).
     */
    default JsonObject toJsonValue() {
        JsonObject.Builder builder = JsonObject.builder();
        if (this instanceof Is is) {
            builder.put("is", is.is().toJsonValue());
            writeBehaviors(builder, is.behaviors());
            is.rift().ifPresent(v -> builder.put("_rift", v.toJsonValue()));
            is.extra().forEach(builder::put);
        } else if (this instanceof Proxy proxy) {
            builder.put("proxy", proxy.proxy().toJsonValue());
            writeBehaviors(builder, proxy.behaviors());
            proxy.extra().forEach(builder::put);
        } else if (this instanceof Inject inject) {
            builder.put("inject", new JsonString(inject.script()));
            writeBehaviors(builder, inject.behaviors());
            inject.extra().forEach(builder::put);
        } else if (this instanceof Fault fault) {
            builder.put("fault", new JsonString(fault.fault()));
            writeBehaviors(builder, fault.behaviors());
            fault.extra().forEach(builder::put);
        } else if (this instanceof RiftScript riftScript) {
            builder.put("_rift", riftScript.rift().toJsonValue());
            writeBehaviors(builder, riftScript.behaviors());
            riftScript.extra().forEach(builder::put);
        } else {
            throw new IllegalStateException("unreachable: " + this);
        }
        return builder.build();
    }
}
