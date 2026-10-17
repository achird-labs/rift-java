package io.github.achirdlabs.rift.model;

import io.github.achirdlabs.rift.json.JsonObject;
import io.github.achirdlabs.rift.json.JsonString;
import io.github.achirdlabs.rift.json.JsonValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * rift 0.22.0 fills an {@code is} response's unwritten {@code statusCode}, {@code headers} and
 * {@code _mode} from the imposter's {@code defaultResponse}, and a written {@code {}} or {@code "text"}
 * counts as a value (rift#1369). So the model keeps which of them were written (#302).
 */
class IsResponsePresenceTest {

    private static JsonObject written(String isJson) {
        return IsResponse.read((JsonObject) JsonValue.parse(isJson)).toJsonValue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"body\":\"x\"}",
            "{\"headers\":{}}",
            "{\"_mode\":\"text\",\"body\":\"x\"}",
            "{\"statusCode\":\"200\",\"headers\":{\"A\":\"b\"}}",
            "{}",
            "{\"statusCode\":\"404\",\"headers\":{},\"body\":\"x\",\"_mode\":\"text\"}",
    })
    void whatWasWrittenIsWrittenBackAndNothingElse(String isJson) {
        assertEquals(JsonValue.parse(isJson), written(isJson));
    }

    @Test
    void anOmittedFieldStillReadsAsTheValueTheEngineAssumes() {
        IsResponse is = IsResponse.read((JsonObject) JsonValue.parse("{\"body\":\"x\"}"));
        assertEquals("200", is.statusCode());
        assertEquals(Map.of(), is.headers());
        assertEquals(ResponseMode.TEXT, is.mode());
        assertFalse(is.isWritten(IsResponse.Field.STATUS_CODE));
        assertFalse(is.isWritten(IsResponse.Field.HEADERS));
        assertFalse(is.isWritten(IsResponse.Field.MODE));

        IsResponse explicit = IsResponse.read((JsonObject) JsonValue.parse("{\"statusCode\":200,\"headers\":{},\"_mode\":\"text\"}"));
        assertTrue(explicit.isWritten(IsResponse.Field.STATUS_CODE));
        assertTrue(explicit.isWritten(IsResponse.Field.HEADERS));
        assertTrue(explicit.isWritten(IsResponse.Field.MODE));
        assertFalse(is.equals(explicit), "presence is part of the value");
    }

    @Test
    void thePreviousConstructorsWriteWhatTheyWroteBefore() {
        assertEquals("{\"statusCode\":\"200\"}", new IsResponse("200").toJsonValue().toJson());
        assertEquals("{\"statusCode\":\"201\",\"body\":\"x\"}",
                new IsResponse("201", Map.of(), Optional.of(new JsonString("x")), ResponseMode.TEXT).toJsonValue().toJson());
        assertEquals("{\"statusCode\":\"200\",\"headers\":{\"A\":\"b\"},\"body\":\"eA==\",\"_mode\":\"binary\"}",
                new IsResponse("200", Map.of("A", List.of("b")), Optional.of(new JsonString("eA==")), ResponseMode.BINARY,
                        Map.of()).toJsonValue().toJson());
    }

    @Test
    void anOmittedFieldMustHoldTheEnginesDefault() {
        assertThrows(IllegalArgumentException.class, () -> new IsResponse("404", Map.of(), Optional.empty(),
                ResponseMode.TEXT, Map.of(), Set.of(IsResponse.Field.STATUS_CODE)));
        assertThrows(IllegalArgumentException.class, () -> new IsResponse("200", Map.of("A", List.of("b")),
                Optional.empty(), ResponseMode.TEXT, Map.of(), Set.of(IsResponse.Field.HEADERS)));
        assertThrows(IllegalArgumentException.class, () -> new IsResponse("200", Map.of(), Optional.empty(),
                ResponseMode.BINARY, Map.of(), Set.of(IsResponse.Field.MODE)));
    }

    @Test
    void withExtraAndWithoutStatusCodeKeepPresence() {
        IsResponse is = IsResponse.read((JsonObject) JsonValue.parse("{\"headers\":{}}"));
        assertEquals("{\"headers\":{},\"x\":1}", is.withExtra("x", JsonValue.parse("1")).toJsonValue().toJson());

        IsResponse unstated = new IsResponse("200").withoutStatusCode();
        assertFalse(unstated.isWritten(IsResponse.Field.STATUS_CODE));
        assertEquals("{}", unstated.toJsonValue().toJson());
        assertThrows(IllegalStateException.class, () -> new IsResponse("404").withoutStatusCode());
    }

    @Test
    void theDefaultResponsePositionOmitsAnUnwrittenStatusAndNeverWritesEmptyHeaders() {
        assertEquals("{}", IsResponse.read((JsonObject) JsonValue.parse("{\"headers\":{}}")).toJsonValue(true).toJson());
        assertEquals("{\"statusCode\":418}",
                IsResponse.read((JsonObject) JsonValue.parse("{\"statusCode\":\"418\"}")).toJsonValue(true).toJson());
    }

    @Test
    void aFlatRecordedResponseKeepsItsWrittenTextMode() {
        Stub stub = Stub.fromJson("{\"predicates\":[],\"responses\":[{\"statusCode\":200,\"_mode\":\"text\",\"body\":\"x\"}]}");
        assertTrue(stub.toJson().contains("\"_mode\":\"text\""), stub.toJson());
        assertEquals(stub, Stub.fromJson(stub.toJson()));
    }

    @Test
    void anImposterDefinitionRoundTripKeepsAnUnwrittenStatus() {
        ImposterDefinition def = ImposterDefinition.fromJson("{\"protocol\":\"http\",\"port\":4545,"
                + "\"defaultResponse\":{\"statusCode\":418},\"stubs\":[{\"predicates\":[],\"responses\":[{\"is\":{\"body\":\"hi\"}}]}]}");
        String out = def.toJson();
        assertTrue(out.contains("{\"is\":{\"body\":\"hi\"}}"), out);
        assertEquals(EnumSet.of(IsResponse.Field.STATUS_CODE, IsResponse.Field.HEADERS, IsResponse.Field.MODE),
                ((Response.Is) def.stubs().get(0).responses().get(0)).is().omitted());
    }
}
