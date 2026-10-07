package io.github.achirdlabs.rift.dsl;

import io.github.achirdlabs.rift.json.JsonArray;
import io.github.achirdlabs.rift.json.JsonObject;
import io.github.achirdlabs.rift.json.JsonValue;
import io.github.achirdlabs.rift.model.Response;
import io.github.achirdlabs.rift.model.RiftConditional;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

import static io.github.achirdlabs.rift.dsl.RiftDsl.imposter;
import static io.github.achirdlabs.rift.dsl.RiftDsl.ok;
import static io.github.achirdlabs.rift.dsl.RiftDsl.onGet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code IsSpec}'s declarative conditional GET, written as {@code _rift.conditional} (rift 0.20.0, #263). */
class IsSpecConditionalTest {

    private static final Instant JAN_5 = Instant.parse("2026-01-05T08:09:10Z");

    @Test
    void conditionalIsTheShorthandTrue() {
        assertEquals("{\"conditional\":true}", rift(ok().conditional()));
    }

    @Test
    void aFixedLastModifiedIsAnImfFixdateWithATwoDigitDay() {
        // RFC 7231 7.1.1.1: senders emit the two-digit day; the engine serves this string verbatim.
        assertEquals("{\"conditional\":{\"lastModified\":\"Mon, 05 Jan 2026 08:09:10 GMT\"}}",
                rift(ok().conditional(JAN_5)));
    }

    @Test
    void subSecondPrecisionIsDropped() {
        assertEquals("{\"conditional\":{\"lastModified\":\"Mon, 05 Jan 2026 08:09:10 GMT\"}}",
                rift(ok().conditional(Instant.parse("2026-01-05T08:09:10.999Z"))));
    }

    @Test
    void midnightIsZeroPadded() {
        assertEquals("{\"conditional\":{\"lastModified\":\"Thu, 01 Jan 1970 00:00:00 GMT\"}}",
                rift(ok().conditional(Instant.EPOCH)));
    }

    @Test
    void anOffsetIsRenderedInGmt() {
        assertEquals("{\"conditional\":{\"lastModified\":\"Sun, 04 Jan 2026 23:30:00 GMT\"}}",
                rift(ok().conditional(Instant.parse("2026-01-05T01:30:00+02:00"))));
    }

    @Test
    void theDateIsEnglishWhateverTheDefaultLocale() {
        Locale saved = Locale.getDefault();
        Locale.setDefault(Locale.GERMANY);
        try {
            assertEquals("{\"conditional\":{\"lastModified\":\"Mon, 05 Jan 2026 08:09:10 GMT\"}}",
                    rift(ok().conditional(JAN_5)), "not 'Mo., 05 Jan.': the engine parses the English names");
        } finally {
            Locale.setDefault(saved);
        }
    }

    @Test
    void withoutEtagTurnsOnlyTheEtagOff() {
        assertEquals("{\"conditional\":{\"etag\":false}}", rift(ok().conditionalWithoutEtag()));
        assertEquals("{\"conditional\":{\"etag\":false,\"lastModified\":\"Mon, 05 Jan 2026 08:09:10 GMT\"}}",
                rift(ok().conditionalWithoutEtag(JAN_5)));
    }

    @Test
    void theLastConditionalCallWins() {
        assertEquals("{\"conditional\":true}", rift(ok().conditionalWithoutEtag(JAN_5).conditional()));
    }

    @Test
    void itSitsBesideTheOtherResponseLevelKeysAndSurvivesOtherChainers() {
        String json = imposter("s").stub(onGet("/").willReturn(ok()
                        .templated()
                        .conditional()
                        .incrementState("hits")
                        .withHeader("Cache-Control", "max-age=60")
                        .waitMs(5)))
                .build().toJson();
        assertTrue(json.contains("\"_rift\":{\"templated\":true,\"stateOps\":[{\"op\":\"increment\",\"key\":\"hits\",\"by\":1}],"
                + "\"conditional\":true}"), json);
        Response.Is is = (Response.Is) ok().conditional().withTextBody("x").build();
        assertEquals(Optional.of(new RiftConditional.Enabled(true)), is.rift().orElseThrow().conditional());
    }

    @Test
    void noConditionalWritesNoRiftBlock() {
        assertFalse(imposter("s").stub(onGet("/").willReturn(ok())).build().toJson().contains("conditional"));
        assertTrue(((Response.Is) ok().build()).rift().isEmpty());
    }

    @Test
    void aNullInstantIsRefused() {
        assertThrows(NullPointerException.class, () -> ok().conditional(null));
        assertThrows(NullPointerException.class, () -> ok().conditionalWithoutEtag(null));
    }

    /** The response's {@code _rift} block as it goes over the wire. */
    private static String rift(IsSpec spec) {
        JsonObject imposter = (JsonObject) JsonValue.parse(imposter("s").stub(onGet("/").willReturn(spec)).build().toJson());
        JsonObject stub = (JsonObject) ((JsonArray) imposter.get("stubs")).items().get(0);
        JsonObject response = (JsonObject) ((JsonArray) stub.get("responses")).items().get(0);
        return response.get("_rift").toJson();
    }
}
