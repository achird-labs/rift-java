package io.github.achirdlabs.rift;

import io.github.achirdlabs.rift.error.InvalidDefinition;
import io.github.achirdlabs.rift.json.JsonObject;
import io.github.achirdlabs.rift.json.JsonValue;
import io.github.achirdlabs.rift.transport.RiftTransport;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.achirdlabs.rift.dsl.RiftDsl.status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A repeated header on an intercept {@code serve} rule needs rift 0.18.0; rift 0.17.0 rejects the
 * rule's array value, so the SDK refuses it first, with a message that says why (#231).
 */
class InterceptMultiValueHeaderGateTest {

    private final List<JsonValue> rules = new ArrayList<>();
    private final AtomicInteger buildInfoCalls = new AtomicInteger();

    @Test
    void oldEngineRefusesARepeatedHeaderBeforeSendingTheRule() {
        Intercept intercept = rift(VersionCheck.FAIL, "0.17.0").intercept();
        InvalidDefinition e = assertThrows(InvalidDefinition.class,
                () -> intercept.serve("example.com", status(200).withHeader("Set-Cookie", "a=1", "b=2")));
        assertTrue(e.getMessage().startsWith("multi-value intercept serve headers: needs rift >= 0.18.0; the running engine (0.17.0)"),
                e.getMessage());
        assertTrue(e.getMessage().contains("collapse the header to one value"), e.getMessage());
        assertTrue(rules.isEmpty(), "nothing reaches the engine");
    }

    @Test
    void currentEngineGetsTheArray() {
        rift(VersionCheck.FAIL, "0.18.0").intercept()
                .serve("example.com", status(200).withHeader("Set-Cookie", "a=1", "b=2"));
        assertEquals(1, rules.size());
        JsonObject serve = (JsonObject) ((JsonObject) ((JsonObject) rules.get(0)).get("action")).get("serve");
        assertEquals(JsonValue.parse("{\"Set-Cookie\":[\"a=1\",\"b=2\"]}"), serve.get("headers"));
    }

    @Test
    void checkOffSendsIt() {
        rift(VersionCheck.OFF, "0.17.0").intercept()
                .serve("example.com", status(200).withHeader("Set-Cookie", "a=1", "b=2"));
        assertEquals(1, rules.size());
    }

    @Test
    void warnModeStillRefusesAKnownOldEngine() {
        Intercept intercept = rift(VersionCheck.WARN, "0.17.0").intercept();
        assertThrows(InvalidDefinition.class,
                () -> intercept.serve("example.com", status(200).withHeader("Set-Cookie", "a=1", "b=2")));
        assertTrue(rules.isEmpty());
    }

    @Test
    void aPlaceholderVersionBelowTheFloorIsSentUnchecked() {
        // What a locally built engine reports: it says nothing about support, so the rule is sent.
        rift(VersionCheck.FAIL, "0.0.0").intercept()
                .serve("example.com", status(200).withHeader("Set-Cookie", "a=1", "b=2"));
        assertEquals(1, rules.size());
    }

    @Test
    void singleValuedHeadersNeverAskForTheVersion() {
        rift(VersionCheck.FAIL, "0.17.0").intercept()
                .serve("example.com", status(200).withHeader("Content-Type", "text/plain"));
        assertEquals(1, rules.size());
        assertEquals(0, buildInfoCalls.get());
    }

    private Rift rift(VersionCheck mode, String version) {
        ConnectOptions options = ConnectOptions.builder(URI.create("http://127.0.0.1:2525")).versionCheck(mode).build();
        RiftTransport transport = (RiftTransport) Proxy.newProxyInstance(
                RiftTransport.class.getClassLoader(), new Class<?>[] {RiftTransport.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "buildInfo" -> {
                        buildInfoCalls.incrementAndGet();
                        yield JsonValue.parse("{\"version\": \"" + version + "\"}");
                    }
                    case "startIntercept" -> JsonValue.parse(
                            "{\"interceptPort\": 9000, \"interceptUrl\": \"http://127.0.0.1:9000\"}");
                    case "interceptAddRules" -> {
                        rules.add((JsonValue) args[0]);
                        yield null;
                    }
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return RiftImpl.spawned(transport, options, () -> { });
    }
}
