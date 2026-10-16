package io.github.achirdlabs.rift.model;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ImposterDefinition-level {@code _rift} configuration block: flow state,
 * script engine defaults, the named script registry, metrics, and proxy configuration. None of
 * this appears in any corpus fixture; hand-written spec-derived round-trip test (G2 + G3).
 */
class RiftConfigRoundTripTest {

    private static final String JSON = """
            {
              "port": 6000,
              "protocol": "http",
              "stubs": [],
              "_rift": {
                "flowState": {
                  "backend": "inmemory",
                  "ttlSeconds": 600,
                  "flowIdSource": "X-Flow-Id",
                  "redis": {"url": "redis://localhost:6379", "poolSize": 20, "keyPrefix": "myapp:"}
                },
                "scriptEngine": {"defaultEngine": "rhai", "timeoutMs": 5000},
                "scripts": {
                  "greet": {"code": "response.body = 'hi';"},
                  "shared": {"file": "scripts/shared.rhai"}
                },
                "metrics": {"enabled": true, "port": 9191},
                "proxy": {
                  "upstream": {"host": "backend.internal", "port": 8443, "protocol": "https"},
                  "connectionPool": {"maxIdlePerHost": 50, "idleTimeoutSecs": 120}
                }
              }
            }
            """;

    @Test
    void riftConfigRoundTrips() {
        RoundTripAssertions.assertRoundTrips(JSON, ImposterDefinition::fromJson, ImposterDefinition::toJson);
    }

    @Test
    void riftConfigFieldsAreTyped() {
        ImposterDefinition imposter = ImposterDefinition.fromJson(JSON);
        RiftConfig rift = imposter.rift().orElseThrow();

        RiftFlowStateConfig flowState = rift.flowState().orElseThrow();
        assertEquals("inmemory", flowState.backend());
        assertEquals(600L, flowState.ttlSeconds());
        assertEquals("X-Flow-Id", flowState.flowIdSource().orElseThrow());
        // a legacy redis block is not modeled: it is carried through extra untouched
        assertEquals(Set.of("redis"), flowState.extra().keySet());

        RiftScriptEngineConfig scriptEngine = rift.scriptEngine().orElseThrow();
        assertEquals("rhai", scriptEngine.defaultEngine());
        assertEquals(5000L, scriptEngine.timeoutMs());

        assertEquals(2, rift.scripts().size());
        assertEquals("response.body = 'hi';", rift.scripts().get("greet").code().orElseThrow());
        assertEquals("scripts/shared.rhai", rift.scripts().get("shared").file().orElseThrow());

        RiftMetricsConfig metrics = rift.metrics().orElseThrow();
        assertTrue(metrics.enabled());
        assertEquals(9191, metrics.port());

        RiftProxyConfig proxy = rift.proxy().orElseThrow();
        RiftUpstreamConfig upstream = proxy.upstream().orElseThrow();
        assertEquals("backend.internal", upstream.host());
        assertEquals(8443, upstream.port());
        assertEquals("https", upstream.protocol());
        RiftConnectionPoolConfig pool = proxy.connectionPool().orElseThrow();
        assertEquals(50, pool.maxIdlePerHost());
        assertEquals(120L, pool.idleTimeoutSecs());
    }
}
