package io.github.achirdlabs.rift;

import io.github.achirdlabs.rift.json.JsonValue;
import org.junit.jupiter.api.Test;

import java.util.OptionalInt;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The response outcome rift 0.18.0 attaches to a journal entry: {@code status} and {@code latencyMs} (#228). */
class RecordedRequestOutcomeTest {

    private static RecordedRequest read(String json) {
        return RecordedRequest.read(JsonValue.parse(json));
    }

    @Test
    void readsBothWhenPresent() {
        RecordedRequest r = read("{\"method\":\"GET\",\"path\":\"/x\",\"status\":503,\"latencyMs\":42,\"node\":\"rift-2\"}");
        assertEquals(OptionalInt.of(503), r.status());
        assertEquals(OptionalLong.of(42), r.latencyMs());
    }

    @Test
    void aZeroLatencyIsARealReading() {
        assertEquals(OptionalLong.of(0), read("{\"status\":200,\"latencyMs\":0}").latencyMs());
    }

    @Test
    void absentOnAnOlderEngineOrAnUnansweredRequest() {
        RecordedRequest r = read("{\"method\":\"GET\",\"path\":\"/x\"}");
        assertEquals(OptionalInt.empty(), r.status());
        assertEquals(OptionalLong.empty(), r.latencyMs());
    }

    @Test
    void mistypedOrOutOfRangeValuesReadAsAbsentWithoutThrowing() {
        RecordedRequest strings = read("{\"status\":\"200\",\"latencyMs\":\"3\"}");
        assertEquals(OptionalInt.empty(), strings.status());
        assertEquals(OptionalLong.empty(), strings.latencyMs());

        RecordedRequest nulls = read("{\"status\":null,\"latencyMs\":null}");
        assertEquals(OptionalInt.empty(), nulls.status());
        assertEquals(OptionalLong.empty(), nulls.latencyMs());

        RecordedRequest fractional = read("{\"status\":200.5,\"latencyMs\":1.5}");
        assertEquals(OptionalInt.empty(), fractional.status());
        assertEquals(OptionalLong.empty(), fractional.latencyMs());

        // The engine saturates an unrepresentable elapsed time at u64::MAX.
        assertEquals(OptionalLong.empty(), read("{\"latencyMs\":18446744073709551615}").latencyMs());
        assertEquals(OptionalInt.empty(), read("{\"status\":99999999999}").status());
        assertEquals(OptionalInt.empty(), read("{\"status\":70000}").status());
        assertEquals(OptionalInt.empty(), read("{\"status\":-1}").status());
        assertEquals(OptionalLong.empty(), read("{\"latencyMs\":-5}").latencyMs());
        assertEquals(OptionalLong.empty(), read("{\"latencyMs\":1e3}").latencyMs());
    }

    @Test
    void summaryRendersEachShape() {
        assertEquals("GET /x → 503 in 42 ms", read("{\"method\":\"GET\",\"path\":\"/x\",\"status\":503,\"latencyMs\":42}").summary());
        assertEquals("GET /x → 200", read("{\"method\":\"GET\",\"path\":\"/x\",\"status\":200}").summary());
        assertEquals("GET /x", read("{\"method\":\"GET\",\"path\":\"/x\",\"latencyMs\":4}").summary());
        assertEquals("? /", read("{}").summary());
        assertEquals("? /", read("[1,2]").summary());
    }

    @Test
    void aNonObjectElementHasNoOutcome() {
        RecordedRequest r = read("[1,2]");
        assertEquals(OptionalInt.empty(), r.status());
        assertEquals(OptionalLong.empty(), r.latencyMs());
    }
}
