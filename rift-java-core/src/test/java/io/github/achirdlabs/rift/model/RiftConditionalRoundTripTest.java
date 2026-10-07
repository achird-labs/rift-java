package io.github.achirdlabs.rift.model;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@code _rift.conditional} is modeled, not carried as an unknown key, and re-serializes exactly as
 * the engine echoes it: an absent field stays absent (#263).
 */
class RiftConditionalRoundTripTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "true",
            "false",
            "{}",
            "{\"etag\":false}",
            "{\"etag\":true}",
            "{\"lastModified\":\"load\"}",
            "{\"lastModified\":\"Sat, 03 Oct 2026 12:00:00 GMT\"}",
            "{\"etag\":false,\"lastModified\":\"Sat, 03 Oct 2026 12:00:00 GMT\"}",
    })
    void everyFormRoundTripsAndIsModeled(String conditional) {
        String json = imposterWith(conditional);

        RoundTripAssertions.assertRoundTrips(json, ImposterDefinition::fromJson, ImposterDefinition::toJson);
        RiftResponseExtension rift = ((Response.Is) ImposterDefinition.fromJson(json).stubs().get(0).responses().get(0))
                .rift().orElseThrow();
        assertEquals(Map.of(), rift.extra(), "conditional is a modeled key, never an extra");
    }

    @Test
    void theShorthandsAreEnabled() {
        assertEquals(Optional.of(new RiftConditional.Enabled(true)), read("true"));
        assertEquals(Optional.of(new RiftConditional.Enabled(false)), read("false"));
    }

    @Test
    void theSpelledOutFormKeepsWhatWasAbsent() {
        assertEquals(Optional.of(new RiftConditional.Validators(Optional.empty(), Optional.empty())), read("{}"));
        assertEquals(Optional.of(new RiftConditional.Validators(Optional.of(false), Optional.of("load"))),
                read("{\"etag\":false,\"lastModified\":\"load\"}"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"yes\"", "1", "[]", "null", "{\"etag\":\"no\"}", "{\"etag\":null}", "{\"lastModified\":0}"})
    void aMalformedConditionalIsRefused(String conditional) {
        assertThrows(WireFormatException.class, () -> ImposterDefinition.fromJson(imposterWith(conditional)));
    }

    @Test
    void theFiveComponentConstructorStillBuildsWithoutAConditional() {
        RiftResponseExtension rift = new RiftResponseExtension(Optional.empty(), Optional.empty(), true,
                java.util.List.of(new StateOp.Delete("k")), Map.of("dataset", io.github.achirdlabs.rift.json.JsonValue.parse("{}")));
        assertEquals(Optional.empty(), rift.conditional());
        assertEquals(true, rift.templated());
        assertEquals(java.util.List.of(new StateOp.Delete("k")), rift.stateOps());
        assertEquals(java.util.List.of("dataset"), java.util.List.copyOf(rift.extra().keySet()));
    }

    private static Optional<RiftConditional> read(String conditional) {
        return ((Response.Is) ImposterDefinition.fromJson(imposterWith(conditional)).stubs().get(0).responses().get(0))
                .rift().orElseThrow().conditional();
    }

    private static String imposterWith(String conditional) {
        return "{\"port\":4545,\"protocol\":\"http\",\"stubs\":[{\"predicates\":[],\"responses\":["
                + "{\"is\":{\"statusCode\":\"200\"},\"_rift\":{\"conditional\":" + conditional + "}}]}]}";
    }
}
