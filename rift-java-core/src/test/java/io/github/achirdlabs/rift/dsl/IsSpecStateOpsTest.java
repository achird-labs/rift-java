package io.github.achirdlabs.rift.dsl;

import io.github.achirdlabs.rift.model.Response;
import io.github.achirdlabs.rift.model.StateOp;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.github.achirdlabs.rift.dsl.RiftDsl.imposter;
import static io.github.achirdlabs.rift.dsl.RiftDsl.ok;
import static io.github.achirdlabs.rift.dsl.RiftDsl.onGet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code IsSpec}'s declarative flow-state writes, written as {@code _rift.stateOps} (#227). */
class IsSpecStateOpsTest {

    @Test
    void chainersAppendOpsInOrder() {
        Response.Is is = (Response.Is) ok()
                .incrementState("hits")
                .incrementState("score", 10)
                .setState("last", "{{ request.query.id }}")
                .deleteState("stale")
                .clearFlowState()
                .build();
        assertEquals(List.of(
                        new StateOp.Increment("hits", 1),
                        new StateOp.Increment("score", 10),
                        new StateOp.Set("last", "{{ request.query.id }}"),
                        new StateOp.Delete("stale"),
                        new StateOp.ClearFlow()),
                is.rift().orElseThrow().stateOps());
    }

    @Test
    void wireShapeSitsInTheResponseLevelRiftBlock() {
        String json = imposter("s").stub(onGet("/").willReturn(ok()
                        .templated()
                        .withTextBody("hits={{ state.hits }}")
                        .incrementState("hits")
                        .setState("last", "{{ request.query.id }}")))
                .build().toJson();
        assertTrue(json.contains("\"_rift\":{\"templated\":true,\"stateOps\":["
                + "{\"op\":\"increment\",\"key\":\"hits\",\"by\":1},"
                + "{\"op\":\"set\",\"key\":\"last\",\"value\":\"{{ request.query.id }}\"}]}"), json);
    }

    @Test
    void noOpsWritesNoRiftBlock() {
        assertFalse(imposter("s").stub(onGet("/").willReturn(ok())).build().toJson().contains("_rift"));
        assertTrue(((Response.Is) ok().build()).rift().isEmpty());
    }

    @Test
    void opsSurviveOtherChainers() {
        Response.Is is = (Response.Is) ok().incrementState("n").templated().withHeader("x", "y")
                .withTcpFault(Fault.CONNECTION_RESET_BY_PEER).waitMs(5).build();
        assertEquals(List.of(new StateOp.Increment("n", 1)), is.rift().orElseThrow().stateOps());

        List<StateOp> one = List.of(new StateOp.Delete("k"));
        assertEquals(one, ((Response.Is) ok().deleteState("k").withJsonBody("{}").build()).rift().orElseThrow().stateOps());
        assertEquals(one, ((Response.Is) ok().deleteState("k").withTextBody("t").build()).rift().orElseThrow().stateOps());
        assertEquals(one, ((Response.Is) ok().deleteState("k").withBinaryBody(new byte[] {1}).build()).rift().orElseThrow().stateOps());
    }

    @Test
    void argumentsAreRequired() {
        assertThrows(NullPointerException.class, () -> ok().setState(null, "v"));
        assertThrows(NullPointerException.class, () -> ok().setState("k", null));
        assertThrows(NullPointerException.class, () -> ok().incrementState(null));
        assertThrows(NullPointerException.class, () -> ok().deleteState(null));
    }
}
