package io.github.achirdlabs.rift.model;

import io.github.achirdlabs.rift.json.JsonArray;
import io.github.achirdlabs.rift.json.JsonNumber;
import io.github.achirdlabs.rift.json.JsonObject;
import io.github.achirdlabs.rift.json.JsonString;
import io.github.achirdlabs.rift.json.JsonValue;

import java.util.List;

/**
 * One entry of a response's {@code _behaviors} object. Known behavior names get a typed shape;
 * anything else round-trips losslessly through {@link Unknown} so an unrecognized (or
 * future/engine-specific) behavior is never dropped.
 */
public sealed interface Behavior {

    String key();

    record Wait(WaitSpec spec) implements Behavior {
        public String key() { return "wait"; }
    }

    record Decorate(String script) implements Behavior {
        public String key() { return "decorate"; }
    }

    /**
     * A {@code copy} behavior. The engine accepts either a single entry object ({@code objectForm})
     * or an array of them; the form is preserved so a parse → serialize round-trip is byte-faithful.
     */
    record Copy(List<CopyEntry> entries, boolean objectForm) implements Behavior {
        public Copy {
            entries = List.copyOf(entries);
            if (objectForm && entries.size() != 1) {
                throw new IllegalArgumentException("object-form copy must have exactly one entry, got " + entries.size());
            }
        }
        public Copy(List<CopyEntry> entries) { this(entries, false); }
        public String key() { return "copy"; }
    }

    /**
     * A {@code repeat}. Two spellings reach it: a response-level field beside {@code is} —
     * Mountebank's canonical one, and what its {@code save} writes — and a {@code repeat} inside
     * the behaviors block. {@code responseLevel} records which arrived so the write puts it back
     * where it came from; a response-level one wins over a block one, as it does in the engine.
     */
    record Repeat(int count, boolean responseLevel) implements Behavior {
        public Repeat(int count) { this(count, false); }
        public String key() { return "repeat"; }
    }

    record ShellTransform(String command) implements Behavior {
        public String key() { return "shellTransform"; }
    }

    record Unknown(String key, JsonValue raw) implements Behavior {}

    /**
     * Reads one key of a behaviors block into the steps the engine runs for it. A {@code
     * shellTransform} holding an array of commands is one step per command, as the engine runs and
     * echoes it; every other key is exactly one step.
     */
    static List<Behavior> readAll(String key, JsonValue value) {
        if (key.equals("shellTransform") && value instanceof JsonArray commands) {
            return commands.items().stream()
                    .<Behavior>map(c -> new ShellTransform(JsonSupport.requireString(c, "'shellTransform[]'")))
                    .toList();
        }
        return List.of(read(key, value));
    }

    static Behavior read(String key, JsonValue value) {
        return switch (key) {
            case "wait" -> new Wait(WaitSpec.read(value));
            case "decorate" -> new Decorate(JsonSupport.requireString(value, "'decorate'"));
            case "copy" -> readCopy(value);
            case "repeat" -> new Repeat(numberOf(value, "repeat").asInt());
            case "shellTransform" -> new ShellTransform(JsonSupport.requireString(value, "'shellTransform'"));
            default -> new Unknown(key, value);
        };
    }

    private static Copy readCopy(JsonValue value) {
        if (value instanceof JsonObject obj) {
            return new Copy(List.of(CopyEntry.read(obj)), true);
        }
        List<CopyEntry> entries = JsonSupport.requireArray(value, "'copy'").items().stream()
                .map(el -> CopyEntry.read(JsonSupport.requireObject(el, "'copy[]'")))
                .toList();
        return new Copy(entries, false);
    }

    private static JsonNumber numberOf(JsonValue value, String context) {
        if (value instanceof JsonNumber n) {
            return n;
        }
        throw new WireFormatException("'" + context + "': expected a number, got " + JsonSupport.typeName(value));
    }

    /**
     * This behavior's wire value. Uses an {@code instanceof} chain rather than a pattern-matching
     * {@code switch}: the latter is still a preview feature at the Java 17 release level this
     * module compiles against (finalized only in Java 21).
     */
    default JsonValue value() {
        if (this instanceof Wait wait) {
            return wait.spec().toJsonValue();
        }
        if (this instanceof Decorate decorate) {
            return new JsonString(decorate.script());
        }
        if (this instanceof Copy copy) {
            if (copy.objectForm()) {
                return copy.entries().get(0).toJsonValue();
            }
            return new JsonArray(copy.entries().stream().map(CopyEntry::toJsonValue).map(v -> (JsonValue) v).toList());
        }
        if (this instanceof Repeat repeat) {
            return JsonNumber.of(repeat.count());
        }
        if (this instanceof ShellTransform shellTransform) {
            return new JsonString(shellTransform.command());
        }
        if (this instanceof Unknown unknown) {
            return unknown.raw();
        }
        throw new IllegalStateException("unreachable: " + this);
    }
}
