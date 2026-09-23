package io.github.achirdlabs.rift.model;

import io.github.achirdlabs.rift.json.JsonObject;
import io.github.achirdlabs.rift.json.JsonValue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The {@code _rift.stateOps} wire shape rift 0.18.0 reads (#227). */
class StateOpTest {

    private static StateOp read(String json) {
        return StateOp.read(JsonValue.parse(json));
    }

    @Test
    void readsEachOp() {
        assertEquals(new StateOp.Set("last", "{{ request.query.id }}"),
                read("{\"op\":\"set\",\"key\":\"last\",\"value\":\"{{ request.query.id }}\"}"));
        assertEquals(new StateOp.Increment("hits", 5), read("{\"op\":\"increment\",\"key\":\"hits\",\"by\":5}"));
        assertEquals(new StateOp.Increment("hits", -2), read("{\"op\":\"increment\",\"key\":\"hits\",\"by\":-2}"));
        assertEquals(new StateOp.Delete("k"), read("{\"op\":\"delete\",\"key\":\"k\"}"));
        assertEquals(new StateOp.ClearFlow(), read("{\"op\":\"clearFlow\"}"));
    }

    @Test
    void incrementDefaultsByToOne() {
        assertEquals(new StateOp.Increment("hits", 1), read("{\"op\":\"increment\",\"key\":\"hits\"}"));
        assertEquals(1, new StateOp.Increment("hits").by());
    }

    @Test
    void writesEachOpInTheEngineShape() {
        assertEquals("{\"op\":\"set\",\"key\":\"k\",\"value\":\"v\"}", new StateOp.Set("k", "v").toJsonValue().toJson());
        assertEquals("{\"op\":\"increment\",\"key\":\"hits\",\"by\":1}", new StateOp.Increment("hits").toJsonValue().toJson());
        assertEquals("{\"op\":\"delete\",\"key\":\"k\"}", new StateOp.Delete("k").toJsonValue().toJson());
        assertEquals("{\"op\":\"clearFlow\"}", new StateOp.ClearFlow().toJsonValue().toJson());
    }

    @Test
    void anUnknownOpIsCarriedVerbatim() {
        String json = "{\"op\":\"expire\",\"key\":\"k\",\"ttlSeconds\":5}";
        StateOp op = read(json);
        assertEquals(new StateOp.Unknown("expire", (JsonObject) JsonValue.parse(json)), op);
        assertEquals(json, op.toJsonValue().toJson());
    }

    @Test
    void malformedOpsAreRejected() {
        assertThrows(WireFormatException.class, () -> read("\"set\""));
        assertThrows(WireFormatException.class, () -> read("{\"key\":\"k\"}"));
        assertThrows(WireFormatException.class, () -> read("{\"op\":7}"));
        assertThrows(WireFormatException.class, () -> read("{\"op\":\"set\",\"value\":\"v\"}"));
        assertThrows(WireFormatException.class, () -> read("{\"op\":\"set\",\"key\":\"k\",\"value\":3}"));
        assertThrows(WireFormatException.class, () -> read("{\"op\":\"set\",\"key\":\"k\"}"));
        assertThrows(WireFormatException.class, () -> read("{\"op\":\"increment\",\"key\":\"k\",\"by\":\"2\"}"));
        assertThrows(WireFormatException.class, () -> read("{\"op\":\"increment\",\"key\":\"k\",\"by\":1.5}"));
        assertThrows(WireFormatException.class, () -> read("{\"op\":\"delete\"}"));
    }

    @Test
    void componentsAreRequired() {
        assertThrows(NullPointerException.class, () -> new StateOp.Set(null, "v"));
        assertThrows(NullPointerException.class, () -> new StateOp.Set("k", null));
        assertThrows(NullPointerException.class, () -> new StateOp.Increment(null));
        assertThrows(NullPointerException.class, () -> new StateOp.Delete(null));
    }
}
