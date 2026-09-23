package io.github.achirdlabs.rift;

import io.github.achirdlabs.rift.dsl.ImposterSpec;
import io.github.achirdlabs.rift.error.InvalidDefinition;
import io.github.achirdlabs.rift.json.JsonValue;
import io.github.achirdlabs.rift.model.CaCertificates;
import io.github.achirdlabs.rift.model.ImposterDefinition;
import io.github.achirdlabs.rift.transport.RiftTransport;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.achirdlabs.rift.dsl.RiftDsl.imposter;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code mutualAuth} needs rift 0.18.0. An older engine drops the key and serves a listener that
 * accepts every client, so a typed definition asking for it is refused there rather than sent (#210).
 */
class ClientAuthGateTest {

    private static final ImposterSpec MTLS = imposter("m").protocol("https").requireClientCertificate();

    private final List<JsonValue> posted = new ArrayList<>();
    private final AtomicInteger buildInfoCalls = new AtomicInteger();

    @Test
    void anOlderEngineIsRefusedBeforeAnythingIsSent() {
        InvalidDefinition e = assertThrows(InvalidDefinition.class, () -> rift(VersionCheck.FAIL, "0.17.0").create(MTLS));
        assertTrue(e.getMessage().contains("mutualAuth"), e.getMessage());
        assertTrue(e.getMessage().contains("every client"), "says what the old engine would do: " + e.getMessage());
        assertTrue(e.getMessage().contains("remove requireClientCertificate"), e.getMessage());
        assertTrue(posted.isEmpty());
    }

    @Test
    void rejectUnauthorizedOrCaAloneIsCheckedToo() {
        // A hand-built definition the DSL cannot produce; an older engine drops this key as well.
        ImposterDefinition bare = imposter("b").protocol("https").build();
        ImposterDefinition caOnly = new ImposterDefinition(bare.port(), bare.host(), bare.protocol(), bare.cert(),
                bare.key(), bare.name(), false, false, bare.stubs(), bare.defaultResponse(), bare.defaultForward(),
                false, false, bare.serviceName(), bare.serviceInfo(), bare.rift(), false, false,
                Optional.of(new CaCertificates(List.of("PEM"), true)), Map.of());
        assertThrows(InvalidDefinition.class, () -> rift(VersionCheck.FAIL, "0.17.0").create(caOnly));
        assertTrue(posted.isEmpty());
    }

    @Test
    void aVersionThatCannotBeJudgedIsSentUnchecked() {
        // Documented trade-off, shared with the proxy/inject behaviors check: a locally built engine
        // reports a placeholder below the floor, and refusing it would break every such embedder.
        rift(VersionCheck.FAIL, "0.1.0").create(MTLS);
        assertEquals(1, posted.size());
    }

    @Test
    void warnModeStillRefusesAKnownOlderEngine() {
        assertThrows(InvalidDefinition.class, () -> rift(VersionCheck.WARN, "0.17.0").create(MTLS));
    }

    @Test
    void replaceAllIsCheckedToo() {
        assertThrows(InvalidDefinition.class, () -> rift(VersionCheck.FAIL, "0.17.0").replaceAll(List.of(MTLS.build())));
        assertTrue(posted.isEmpty());
    }

    @Test
    void aCurrentEngineAccepts() {
        rift(VersionCheck.FAIL, "0.18.0").create(MTLS);
        assertEquals(1, posted.size());
    }

    @Test
    void offSendsItUnchecked() {
        rift(VersionCheck.OFF, "0.17.0").create(MTLS);
        assertEquals(1, posted.size());
        assertEquals(0, buildInfoCalls.get());
    }

    @Test
    void anHttpsImposterWithoutClientAuthNeverAsksTheEngine() {
        rift(VersionCheck.FAIL, "0.17.0").create(imposter("h").protocol("https"));
        assertEquals(1, posted.size());
        assertEquals(0, buildInfoCalls.get());
    }

    private Rift rift(VersionCheck mode, String version) {
        RiftTransport transport = (RiftTransport) Proxy.newProxyInstance(
                RiftTransport.class.getClassLoader(), new Class<?>[] {RiftTransport.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "buildInfo" -> {
                        buildInfoCalls.incrementAndGet();
                        yield JsonValue.parse("{\"version\": \"" + version + "\"}");
                    }
                    case "createImposter" -> {
                        posted.add((JsonValue) args[0]);
                        yield JsonValue.parse("{\"port\": 4545}");
                    }
                    case "replaceAllImposters" -> {
                        posted.add((JsonValue) args[0]);
                        yield null;
                    }
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        ConnectOptions options = ConnectOptions.builder(URI.create("http://127.0.0.1:2525")).versionCheck(mode).build();
        return RiftImpl.spawned(transport, options, () -> { });
    }
}
