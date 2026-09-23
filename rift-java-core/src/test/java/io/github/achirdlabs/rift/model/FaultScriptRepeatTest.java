package io.github.achirdlabs.rift.model;

import io.github.achirdlabs.rift.dsl.Fault;
import io.github.achirdlabs.rift.dsl.Script;
import io.github.achirdlabs.rift.json.JsonNumber;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.github.achirdlabs.rift.dsl.RiftDsl.fault;
import static io.github.achirdlabs.rift.dsl.RiftDsl.script;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** rift 0.18.0 honours {@code repeat} on a fault and a {@code _rift}-only response too (#229). */
class FaultScriptRepeatTest {

    private static Response first(String responsesJson) {
        return Stub.fromJson("{\"predicates\":[],\"responses\":[" + responsesJson + "]}").responses().get(0);
    }

    private static String roundTrip(String responseJson) {
        return first(responseJson).toJsonValue().toJson();
    }

    @Test
    void theDslWritesTheBlockSpelling() {
        assertEquals("{\"fault\":\"CONNECTION_RESET_BY_PEER\",\"_behaviors\":{\"repeat\":2}}",
                fault(Fault.CONNECTION_RESET_BY_PEER).repeat(2).build().toJsonValue().toJson());
        assertEquals("{\"_rift\":{\"script\":{\"engine\":\"rhai\",\"code\":\"x\"}},\"_behaviors\":{\"repeat\":3}}",
                script(Script.rhai("x")).repeat(3).build().toJsonValue().toJson());
    }

    @Test
    void withoutRepeatTheOutputIsUnchanged() {
        assertEquals("{\"fault\":\"CONNECTION_RESET_BY_PEER\"}",
                fault(Fault.CONNECTION_RESET_BY_PEER).build().toJsonValue().toJson());
        assertEquals("{\"_rift\":{\"script\":{\"engine\":\"rhai\",\"code\":\"x\"}}}",
                script(Script.rhai("x")).build().toJsonValue().toJson());
    }

    @Test
    void theEnginesResponseLevelEchoIsTyped() {
        Response.Fault f = assertInstanceOf(Response.Fault.class, first("{\"repeat\":2,\"fault\":\"CONNECTION_RESET_BY_PEER\"}"));
        assertEquals(List.of(new Behavior.Repeat(2, true)), f.behaviors().entries());
        assertEquals(Map.of(), f.extra());

        Response.RiftScript s = assertInstanceOf(Response.RiftScript.class,
                first("{\"repeat\":4,\"_rift\":{\"script\":{\"engine\":\"rhai\",\"code\":\"x\"}}}"));
        assertEquals(List.of(new Behavior.Repeat(4, true)), s.behaviors().entries());
        assertEquals(Map.of(), s.extra());
    }

    @Test
    void everySpellingRoundTripsInTheShapeItArrivedIn() {
        String faultBlock = "{\"fault\":\"CONNECTION_RESET_BY_PEER\",\"_behaviors\":{\"wait\":500,\"repeat\":2}}";
        assertEquals(faultBlock, roundTrip(faultBlock));
        String scriptArray = "{\"_rift\":{\"script\":{\"engine\":\"rhai\",\"code\":\"x\"}},\"behaviors\":[{\"wait\":500},{\"repeat\":2},{\"repeat\":3}]}";
        assertEquals(scriptArray, roundTrip(scriptArray));
        assertEquals("{\"fault\":\"CONNECTION_RESET_BY_PEER\",\"repeat\":2}",
                roundTrip("{\"repeat\":2,\"fault\":\"CONNECTION_RESET_BY_PEER\"}"));
    }

    @Test
    void anOutOfOrderBlockOnAFaultIsNormalisedLikeAnyOther() {
        assertEquals("{\"fault\":\"CONNECTION_RESET_BY_PEER\",\"_behaviors\":{\"copy\":[],\"decorate\":\"d\"}}",
                roundTrip("{\"fault\":\"CONNECTION_RESET_BY_PEER\",\"_behaviors\":{\"decorate\":\"d\",\"copy\":[]}}"));
    }

    @Test
    void behaviorKeysAreModeledNotExtra() {
        assertThrows(WireFormatException.class, () -> new Response.Fault("CONNECTION_RESET_BY_PEER", Behaviors.EMPTY,
                Map.of("repeat", JsonNumber.of(2))));
        assertThrows(WireFormatException.class, () -> new Response.RiftScript(RiftResponseExtension.EMPTY, Behaviors.EMPTY,
                Map.of("_behaviors", JsonNumber.of(2))));
        // The older two-argument constructors lift raw behavior keys, as a parse would.
        assertEquals(List.of(new Behavior.Repeat(2, true)),
                new Response.Fault("CONNECTION_RESET_BY_PEER", Map.of("repeat", JsonNumber.of(2))).behaviors().entries());
        assertEquals(Optional.of(new Behavior.Repeat(2, true)),
                new Response.RiftScript(RiftResponseExtension.EMPTY, Map.of("repeat", JsonNumber.of(2))).behaviors().effectiveRepeat());
    }
}
