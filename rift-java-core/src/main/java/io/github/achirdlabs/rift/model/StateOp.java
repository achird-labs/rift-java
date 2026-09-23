package io.github.achirdlabs.rift.model;

import io.github.achirdlabs.rift.json.JsonNumber;
import io.github.achirdlabs.rift.json.JsonObject;
import io.github.achirdlabs.rift.json.JsonString;
import io.github.achirdlabs.rift.json.JsonValue;

import java.util.Objects;

/**
 * One declarative flow-state write from a response's {@code _rift.stateOps} list (rift &ge; 0.18.0).
 *
 * <p>The engine runs the list after the response is built — after templating and behaviors — in
 * order, against the flow the request resolves to. A templated body that reads {@code {{ state.x }}}
 * therefore sees the value from before this response's ops. Ops run on {@code is} responses only,
 * and not when a {@code _rift} fault fires. An imposter created with ops and no {@code
 * _rift.flowState} gets an in-memory store.
 */
public sealed interface StateOp {

    /** Stores {@code value}, rendered as a template against the request, under {@code key}. */
    record Set(String key, String value) implements StateOp {
        public Set {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
        }
    }

    /** Adds {@code by} to the integer stored under {@code key}, treating an absent value as 0. */
    record Increment(String key, long by) implements StateOp {
        public Increment {
            Objects.requireNonNull(key, "key");
        }

        /** Adds 1, the engine's default. */
        public Increment(String key) {
            this(key, 1);
        }
    }

    /** Removes {@code key} from the flow. */
    record Delete(String key) implements StateOp {
        public Delete {
            Objects.requireNonNull(key, "key");
        }
    }

    /** Removes every key in the flow. */
    record ClearFlow() implements StateOp {
    }

    /**
     * An op this SDK does not model, read from a newer engine and written back verbatim so a
     * read-then-write keeps it.
     */
    record Unknown(String op, JsonObject raw) implements StateOp {
        public Unknown {
            Objects.requireNonNull(op, "op");
            Objects.requireNonNull(raw, "raw");
        }
    }

    static StateOp read(JsonValue value) {
        JsonObject obj = JsonSupport.requireObject(value, "stateOps[]");
        String op = JsonSupport.requireString(obj.get("op"), "stateOps[].op");
        return switch (op) {
            case "set" -> new Set(key(obj), JsonSupport.requireString(obj.get("value"), "stateOps[].value"));
            case "increment" -> new Increment(key(obj), by(obj));
            case "delete" -> new Delete(key(obj));
            case "clearFlow" -> new ClearFlow();
            default -> new Unknown(op, obj);
        };
    }

    private static String key(JsonObject obj) {
        return JsonSupport.requireString(obj.get("key"), "stateOps[].key");
    }

    private static long by(JsonObject obj) {
        try {
            return JsonSupport.optLong(obj, "by", 1);
        } catch (NumberFormatException e) {
            throw new WireFormatException("stateOps[].by: expected an integer, got " + obj.get("by").toJson());
        }
    }

    /** The engine's shape; {@code by} is always written, as the engine echoes it. */
    default JsonObject toJsonValue() {
        if (this instanceof Unknown unknown) {
            return unknown.raw();
        }
        JsonObject.Builder builder = JsonObject.builder();
        if (this instanceof Set set) {
            builder.put("op", new JsonString("set")).put("key", new JsonString(set.key()))
                    .put("value", new JsonString(set.value()));
        } else if (this instanceof Increment inc) {
            builder.put("op", new JsonString("increment")).put("key", new JsonString(inc.key()))
                    .put("by", JsonNumber.of(inc.by()));
        } else if (this instanceof Delete delete) {
            builder.put("op", new JsonString("delete")).put("key", new JsonString(delete.key()));
        } else {
            builder.put("op", new JsonString("clearFlow"));
        }
        return builder.build();
    }
}
