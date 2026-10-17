package io.github.achirdlabs.rift.dsl;

import io.github.achirdlabs.rift.json.JsonArray;
import io.github.achirdlabs.rift.json.JsonObject;
import io.github.achirdlabs.rift.json.JsonValue;
import io.github.achirdlabs.rift.model.IsResponse;
import org.junit.jupiter.api.Test;

import static io.github.achirdlabs.rift.dsl.RiftDsl.imposter;
import static io.github.achirdlabs.rift.dsl.RiftDsl.ok;
import static io.github.achirdlabs.rift.dsl.RiftDsl.onGet;
import static io.github.achirdlabs.rift.dsl.RiftDsl.response;
import static io.github.achirdlabs.rift.dsl.RiftDsl.status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code RiftDsl.response()} names no status, so the imposter's {@code defaultResponse} supplies it (#302). */
class UnstatedResponseTest {

    @Test
    void responseWritesOnlyWhatItWasGiven() {
        assertEquals("{\"predicates\":[{\"equals\":{\"method\":\"GET\",\"path\":\"/x\"}}],\"responses\":[{\"is\":{\"body\":\"hi\"}}]}",
                onGet("/x").willReturn(response().withTextBody("hi")).build().toJson());
        assertEquals("{\"headers\":{\"A\":\"b\"}}", isJson(response().withHeader("A", "b")));
        assertEquals("{}", isJson(response()));
    }

    @Test
    void theStatusOpenersStillWriteTheirStatus() {
        assertEquals("{\"statusCode\":\"200\",\"body\":\"hi\"}", isJson(ok().withTextBody("hi")));
        assertEquals("{\"statusCode\":\"404\"}", isJson(status(404)));
        assertTrue(response().buildIsResponse().statusCode().equals(IsResponse.DEFAULT_STATUS_CODE),
                "an unstated status still reads as the engine's 200");
        assertFalse(response().buildIsResponse().isWritten(IsResponse.Field.STATUS_CODE));
    }

    @Test
    void aDefaultResponseBuiltWithResponseWritesNoStatus() {
        String json = imposter("d").port(4545).defaultResponse(response().withHeader("X-D", "1")).build().toJson();
        assertTrue(json.contains("\"defaultResponse\":{\"headers\":{\"X-D\":\"1\"}}"), json);
    }

    private static String isJson(IsSpec spec) {
        String stub = RiftDsl.onRequest().willReturn(spec).build().toJson();
        return ((JsonObject) ((JsonObject) ((JsonArray) ((JsonObject) JsonValue.parse(stub)).get("responses")).items().get(0)).get("is")).toJson();
    }
}
