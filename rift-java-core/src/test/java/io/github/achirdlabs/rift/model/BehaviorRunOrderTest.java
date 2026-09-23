package io.github.achirdlabs.rift.model;

import org.junit.jupiter.api.Test;

import static io.github.achirdlabs.rift.dsl.RiftDsl.copyFrom;
import static io.github.achirdlabs.rift.dsl.RiftDsl.regex;
import static io.github.achirdlabs.rift.dsl.RiftDsl.status;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * rift 0.18.0 runs a {@code _behaviors} object in Mountebank's fixed order (wait, lookup, copy,
 * shellTransform, decorate) but a {@code behaviors} array in the order written. When the
 * transforming behaviors are declared out of that order, the array form is written so the declared
 * order is the order that runs (#230).
 */
class BehaviorRunOrderTest {

    private static final String COPY = "{\"from\":\"path\",\"into\":\"${id}\",\"using\":{\"method\":\"regex\",\"selector\":\"x\"}}";
    private static final String LOOKUP = "{\"key\":{\"from\":\"path\"},\"fromDataSource\":{\"csv\":{\"path\":\"u.csv\",\"keyColumn\":\"id\"}},\"into\":\"${row}\"}";

    private static String written(String behaviorsBlock) {
        String stub = "{\"predicates\":[],\"responses\":[{\"is\":{\"statusCode\":\"200\"}," + behaviorsBlock + "}]}";
        return Stub.fromJson(stub).toJson();
    }

    private static String expected(String behaviorsBlock) {
        return "{\"predicates\":[],\"responses\":[{\"is\":{\"statusCode\":\"200\"}," + behaviorsBlock + "}]}";
    }

    @Test
    void anOutOfOrderArrayKeepsItsOrder() {
        // An engine GET echoes an array in the order it was written, so this is also the read-back of DSL output.
        String decorateFirst = "\"behaviors\":[{\"decorate\":\"d\"},{\"shellTransform\":\"cat\"}]";
        assertEquals(expected(decorateFirst), written(decorateFirst));
        String copyFirst = "\"behaviors\":[{\"copy\":[" + COPY + "]},{\"lookup\":[" + LOOKUP + "]}]";
        assertEquals(expected(copyFirst), written(copyFirst));
        String waitThenDecorateThenCopy = "\"behaviors\":[{\"wait\":100},{\"decorate\":\"d\"},{\"copy\":[" + COPY + "]}]";
        assertEquals(expected(waitThenDecorateThenCopy), written(waitThenDecorateThenCopy));
    }

    @Test
    void anOutOfOrderObjectIsReadInTheOrderItRuns() {
        // An engine runs this object copy-then-decorate; a read then write must keep that meaning.
        assertEquals(expected("\"_behaviors\":{\"copy\":[" + COPY + "],\"decorate\":\"d\"}"),
                written("\"_behaviors\":{\"decorate\":\"d\",\"copy\":[" + COPY + "]}"));
        // Only the transforming behaviors move; wait, repeat and unknown keys keep their slots.
        assertEquals(expected("\"_behaviors\":{\"wait\":100,\"lookup\":[" + LOOKUP + "],\"repeat\":2,"
                        + "\"futureKnob\":1,\"shellTransform\":\"cat\",\"decorate\":\"d\"}"),
                written("\"_behaviors\":{\"wait\":100,\"decorate\":\"d\",\"repeat\":2,"
                        + "\"futureKnob\":1,\"shellTransform\":\"cat\",\"lookup\":[" + LOOKUP + "]}"));
    }

    @Test
    void canonicalOrderKeepsTheObjectForm() {
        String block = "\"_behaviors\":{\"lookup\":[" + LOOKUP + "],\"copy\":[" + COPY + "],"
                + "\"shellTransform\":\"cat\",\"decorate\":\"d\"}";
        assertEquals(expected(block), written(block));
    }

    @Test
    void waitRepeatAndUnknownKeysDoNotCountTowardsOrder() {
        String waitLast = "\"_behaviors\":{\"decorate\":\"d\",\"wait\":100}";
        assertEquals(expected(waitLast), written(waitLast));
        String repeatBetween = "\"_behaviors\":{\"copy\":[" + COPY + "],\"repeat\":2,\"decorate\":\"d\"}";
        assertEquals(expected(repeatBetween), written(repeatBetween));
        String unknownFirst = "\"_behaviors\":{\"futureKnob\":1,\"decorate\":\"d\"}";
        assertEquals(expected(unknownFirst), written(unknownFirst));
    }

    @Test
    void anOutOfOrderDslChainKeepsItsUnknownAndRepeatEntriesInPlace() {
        assertEquals("{\"is\":{\"statusCode\":\"200\"},\"behaviors\":[{\"decorate\":\"d\"},{\"repeat\":2},"
                        + "{\"lookup\":[" + LOOKUP + "]}]}",
                status(200).decorate("d").repeat(2)
                        .withBehavior(new Behavior.Unknown("lookup", io.github.achirdlabs.rift.json.JsonValue.parse("[" + LOOKUP + "]")))
                        .build().toJsonValue().toJson());
    }

    @Test
    void theDslChainOrderIsTheWrittenOrder() {
        assertEquals("{\"is\":{\"statusCode\":\"200\"},\"behaviors\":[{\"decorate\":\"d\"},{\"shellTransform\":\"cat\"}]}",
                status(200).decorate("d").shellTransform("cat").build().toJsonValue().toJson());
        assertEquals("{\"is\":{\"statusCode\":\"200\"},\"_behaviors\":{\"shellTransform\":\"cat\",\"decorate\":\"d\"}}",
                status(200).shellTransform("cat").decorate("d").build().toJsonValue().toJson());
        assertEquals("{\"is\":{\"statusCode\":\"200\"},\"_behaviors\":{\"copy\":[{\"from\":\"path\",\"into\":\"${a}\","
                        + "\"using\":{\"method\":\"regex\",\"selector\":\"a\"}}],\"decorate\":\"d\"}}",
                status(200).copy(copyFrom("path").into("${a}").using(regex("a"))).decorate("d").build().toJsonValue().toJson());
    }
}
