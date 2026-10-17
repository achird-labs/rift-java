package io.github.achirdlabs.rift;

import io.github.achirdlabs.rift.json.JsonValue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code _bodyOmitted}: rift 0.22.0 records a body larger than the whole journal budget without it (rift#1441). */
class RecordedRequestBodyOmittedTest {

    @Test
    void anOmittedBodyIsFlaggedAndReadsEmpty() {
        RecordedRequest r = RecordedRequest.read(JsonValue.parse(
                "{\"method\":\"POST\",\"path\":\"/big\",\"_bodyOmitted\":true}"));
        assertTrue(r.bodyOmitted());
        assertEquals("", r.body());
    }

    @Test
    void aRecordedBodyIsNotFlagged() {
        assertFalse(RecordedRequest.read(JsonValue.parse("{\"method\":\"POST\",\"path\":\"/a\",\"body\":\"x\"}")).bodyOmitted());
        assertFalse(RecordedRequest.read(JsonValue.parse("{\"method\":\"POST\",\"_bodyOmitted\":false}")).bodyOmitted());
    }

    @Test
    void aNonBooleanFlagIsNotTakenAsOmitted() {
        assertFalse(RecordedRequest.read(JsonValue.parse("{\"method\":\"POST\",\"_bodyOmitted\":\"true\"}")).bodyOmitted());
        assertFalse(RecordedRequest.read(JsonValue.parse("[1]")).bodyOmitted());
    }
}
