package io.github.achirdlabs.rift.model;

import io.github.achirdlabs.rift.dsl.ImposterSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.github.achirdlabs.rift.dsl.RiftDsl.imposter;
import static io.github.achirdlabs.rift.dsl.RiftDsl.onGet;
import static io.github.achirdlabs.rift.dsl.RiftDsl.status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The behaviors reader accepts every shape the engine does (#234): a {@code shellTransform} array
 * of commands, and an array element holding several keys, both expanded into the engine's own step
 * list.
 */
class BehaviorsReaderTest {

    private static String written(String behaviorsBlock) {
        return Stub.fromJson("{\"predicates\":[],\"responses\":[{\"is\":{\"statusCode\":\"200\"}," + behaviorsBlock + "}]}")
                .toJson();
    }

    private static String stub(String behaviorsBlock) {
        return "{\"predicates\":[],\"responses\":[{\"is\":{\"statusCode\":\"200\"}," + behaviorsBlock + "}]}";
    }

    private static List<Behavior> entries(String behaviorsBlock) {
        Stub s = Stub.fromJson(stub(behaviorsBlock));
        return ((Response.Is) s.responses().get(0)).behaviors().entries();
    }

    @Test
    void aShellTransformArrayReadsAsOneStepPerCommand() {
        assertEquals(List.of(new Behavior.ShellTransform("./a.sh"), new Behavior.ShellTransform("./b.sh")),
                entries("\"_behaviors\":{\"shellTransform\":[\"./a.sh\",\"./b.sh\"]}"));
        // Written back as the engine echoes it: one element per command.
        assertEquals(stub("\"behaviors\":[{\"shellTransform\":\"./a.sh\"},{\"shellTransform\":\"./b.sh\"}]"),
                written("\"_behaviors\":{\"shellTransform\":[\"./a.sh\",\"./b.sh\"]}"));
    }

    @Test
    void aSingleCommandStillRoundTripsByteForByte() {
        String block = "\"_behaviors\":{\"shellTransform\":\"./a.sh\"}";
        assertEquals(stub(block), written(block));
        String oneElementArray = "\"_behaviors\":{\"shellTransform\":[\"./a.sh\"]}";
        assertEquals(List.of(new Behavior.ShellTransform("./a.sh")), entries(oneElementArray));
    }

    @Test
    void aMultiKeyArrayElementExpandsInTheEngineOrder() {
        List<Behavior> decorateThenWait = entries("\"behaviors\":[{\"decorate\":\"d\",\"wait\":100}]");
        assertEquals(List.of("wait", "decorate"), decorateThenWait.stream().map(Behavior::key).toList());
        assertEquals(new Behavior.Decorate("d"), decorateThenWait.get(1));

        List<Behavior> withUnknowns = entries("\"behaviors\":[{\"zeta\":1,\"decorate\":\"d\",\"alpha\":2,\"copy\":[]}]");
        assertEquals(List.of("copy", "decorate", "alpha", "zeta"), withUnknowns.stream().map(Behavior::key).toList());

        List<Behavior> acrossElements = entries(
                "\"behaviors\":[{\"decorate\":\"d\"},{\"shellTransform\":[\"a\",\"b\"],\"wait\":5}]");
        assertEquals(List.of("decorate", "wait", "shellTransform", "shellTransform"),
                acrossElements.stream().map(Behavior::key).toList());
    }

    @Test
    void anEmptyArrayElementIsStillRejected() {
        assertThrows(WireFormatException.class, () -> entries("\"behaviors\":[{}]"));
        assertThrows(WireFormatException.class, () -> entries("\"_behaviors\":{\"shellTransform\":[1]}"));
    }

    @Test
    void theDslWritesOneStepPerCommand() {
        assertEquals("{\"is\":{\"statusCode\":\"200\"},\"behaviors\":[{\"shellTransform\":\"a\"},{\"shellTransform\":\"b\"}]}",
                status(200).shellTransform("a", "b").build().toJsonValue().toJson());
        assertEquals("{\"is\":{\"statusCode\":\"200\"},\"_behaviors\":{\"shellTransform\":\"a\"}}",
                status(200).shellTransform("a").build().toJsonValue().toJson());
        assertThrows(IllegalArgumentException.class, () -> status(200).shellTransform());
    }

    @Test
    void dslOutputWithSeveralCommandsReadsBack() {
        ImposterSpec spec = imposter("s").stub(onGet("/").willReturn(status(200).shellTransform("a", "b")));
        String json = spec.build().toJson();
        assertEquals(json, ImposterDefinition.fromJson(json).toJson());
    }
}
