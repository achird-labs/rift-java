package io.github.achirdlabs.rift.model;

import io.github.achirdlabs.rift.json.JsonValue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keys rift 0.18.0 added to the two {@code _rift} blocks ({@code stateOps}, {@code dataset},
 * {@code sequencing}) and provider options under {@code _rift.flowState} are carried verbatim
 * through a read-then-write instead of being dropped (issue #226).
 */
class RiftExtraKeysPreservationTest {

    private static final String RESPONSE_LEVEL = """
            {
              "port": 4545,
              "protocol": "http",
              "stubs": [
                {
                  "predicates": [],
                  "responses": [
                    {
                      "is": {"statusCode": "200", "body": "hits={{ state.hits }}"},
                      "_rift": {
                        "templated": true,
                        "stateOps": [{"op": "increment", "key": "hits", "by": 1}, {"op": "clearFlow"}],
                        "dataset": {"name": "users", "select": "row"}
                      }
                    },
                    {"_rift": {"script": {"engine": "rhai", "code": "fn respond(ctx) { http(200, \\"x\\") }"}, "dataset": {"name": "d"}}},
                    {"is": {"statusCode": "204"}, "_rift": {"stateOps": [{"op": "delete", "key": "k"}]}}
                  ]
                }
              ]
            }
            """;

    private static final String IMPOSTER_LEVEL = """
            {
              "port": 4546,
              "protocol": "http",
              "stubs": [],
              "_rift": {
                "flowState": {"backend": "dynamo", "ttlSeconds": 60, "tableName": "flows", "region": "eu-west-1"},
                "sequencing": {"mode": "shared", "peekCacheMs": 5}
              }
            }
            """;

    @Test
    void responseLevelUnknownKeysRoundTrip() {
        RoundTripAssertions.assertRoundTrips(RESPONSE_LEVEL, ImposterDefinition::fromJson, ImposterDefinition::toJson);
    }

    @Test
    void responseLevelUnknownKeysLandInExtra() {
        List<Response> responses = ImposterDefinition.fromJson(RESPONSE_LEVEL).stubs().get(0).responses();

        RiftResponseExtension withIs = ((Response.Is) responses.get(0)).rift().orElseThrow();
        assertTrue(withIs.templated());
        assertEquals(List.of("dataset"), List.copyOf(withIs.extra().keySet()));
        assertEquals(List.of(new StateOp.Increment("hits", 1), new StateOp.ClearFlow()), withIs.stateOps());
        assertEquals(JsonValue.parse("{\"name\": \"users\", \"select\": \"row\"}"), withIs.extra().get("dataset"));

        RiftResponseExtension scriptOnly = ((Response.RiftScript) responses.get(1)).rift();
        assertTrue(scriptOnly.script().isPresent());
        assertEquals(Map.of("dataset", JsonValue.parse("{\"name\": \"d\"}")), scriptOnly.extra());

        RiftResponseExtension onlyUnknown = ((Response.Is) responses.get(2)).rift().orElseThrow();
        assertEquals(Optional.empty(), onlyUnknown.fault());
        assertFalse(onlyUnknown.templated());
        assertEquals(List.of(new StateOp.Delete("k")), onlyUnknown.stateOps());
        assertEquals(Map.of(), onlyUnknown.extra());
    }

    @Test
    void imposterLevelUnknownKeysRoundTrip() {
        RoundTripAssertions.assertRoundTrips(IMPOSTER_LEVEL, ImposterDefinition::fromJson, ImposterDefinition::toJson);
    }

    @Test
    void imposterLevelUnknownKeysLandInExtra() {
        RiftConfig rift = ImposterDefinition.fromJson(IMPOSTER_LEVEL).rift().orElseThrow();
        assertEquals(Map.of("sequencing", JsonValue.parse("{\"mode\": \"shared\", \"peekCacheMs\": 5}")), rift.extra());

        RiftFlowStateConfig flowState = rift.flowState().orElseThrow();
        assertEquals("dynamo", flowState.backend());
        assertEquals(60L, flowState.ttlSeconds());
        assertEquals(List.of("tableName", "region"), List.copyOf(flowState.extra().keySet()));
        assertEquals(JsonValue.parse("\"flows\""), flowState.extra().get("tableName"));
        assertEquals(JsonValue.parse("\"eu-west-1\""), flowState.extra().get("region"));
    }

    @Test
    void extraIsWrittenAfterTheModeledKeys() {
        RiftResponseExtension ext = new RiftResponseExtension(
                Optional.empty(), Optional.empty(), true, Map.of("dataset", JsonValue.parse("{\"name\":\"d\"}")));
        assertEquals("{\"templated\":true,\"dataset\":{\"name\":\"d\"}}", ext.toJsonValue().toJson());

        RiftFlowStateConfig flowState = new RiftFlowStateConfig(
                "dynamo", 60, Optional.empty(), Optional.empty(), Map.of("tableName", JsonValue.parse("\"flows\"")));
        assertEquals("{\"backend\":\"dynamo\",\"ttlSeconds\":60,\"tableName\":\"flows\"}", flowState.toJsonValue().toJson());

        RiftConfig config = new RiftConfig(Optional.of(flowState), Optional.empty(), Optional.empty(), Optional.empty(),
                Map.of(), Map.of("sequencing", JsonValue.parse("{\"mode\":\"shared\"}")));
        assertEquals("{\"flowState\":{\"backend\":\"dynamo\",\"ttlSeconds\":60,\"tableName\":\"flows\"},"
                + "\"sequencing\":{\"mode\":\"shared\"}}", config.toJsonValue().toJson());
    }

    @Test
    void constructorsRejectAModeledKeyInExtra() {
        JsonValue v = JsonValue.parse("true");
        assertThrows(WireFormatException.class,
                () -> new RiftResponseExtension(Optional.empty(), Optional.empty(), false, Map.of("templated", v)));
        assertThrows(WireFormatException.class,
                () -> new RiftResponseExtension(Optional.empty(), Optional.empty(), false, Map.of("stateOps", v)));
        assertThrows(WireFormatException.class,
                () -> new RiftConfig(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Map.of(),
                        Map.of("scripts", v)));
        assertThrows(WireFormatException.class,
                () -> new RiftFlowStateConfig("inmemory", 300, Optional.empty(), Optional.empty(), Map.of("flowIdSource", v)));
    }

    @Test
    void withExtraAddsAKeyAndRejectsAModeledOne() {
        JsonValue dataset = JsonValue.parse("{\"name\":\"d\"}");
        assertEquals(Map.of("dataset", dataset), RiftResponseExtension.EMPTY.withExtra("dataset", dataset).extra());
        assertEquals(Map.of("sequencing", dataset), RiftConfig.EMPTY.withExtra("sequencing", dataset).extra());
        RiftResponseExtension twice = RiftResponseExtension.EMPTY.withExtra("dataset", dataset).withExtra("futureKnob", dataset);
        assertEquals(List.of("dataset", "futureKnob"), List.copyOf(twice.extra().keySet()));
        assertThrows(WireFormatException.class, () -> RiftResponseExtension.EMPTY.withExtra("fault", dataset));
        assertThrows(WireFormatException.class, () -> RiftConfig.EMPTY.withExtra("proxy", dataset));
        RiftFlowStateConfig flowState = new RiftFlowStateConfig("inmemory", 300, Optional.empty(), Optional.empty());
        assertEquals(Map.of("tableName", dataset), flowState.withExtra("tableName", dataset).extra());
        assertThrows(WireFormatException.class, () -> flowState.withExtra("redis", dataset));
    }

    @Test
    void shortConstructorsKeepAnEmptyExtra() {
        assertEquals(Map.of(), new RiftResponseExtension(Optional.empty(), Optional.empty(), true).extra());
        assertEquals(Map.of(), new RiftConfig(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Map.of()).extra());
        assertEquals(Map.of(), new RiftFlowStateConfig("inmemory", 300, Optional.empty(), Optional.empty()).extra());
        assertEquals(Map.of(), RiftResponseExtension.EMPTY.extra());
        assertEquals(Map.of(), RiftConfig.EMPTY.extra());
    }

    @Test
    void riftConfigWithOnlyExtraIsNotEmpty() {
        assertTrue(RiftConfig.EMPTY.isEmpty());
        assertFalse(RiftConfig.EMPTY.withExtra("sequencing", JsonValue.parse("{}")).isEmpty());
    }
}
