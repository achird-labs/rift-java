package io.github.achirdlabs.rift;

import io.github.achirdlabs.rift.dsl.ImposterSpec;
import io.github.achirdlabs.rift.error.InvalidDefinition;
import io.github.achirdlabs.rift.json.JsonValue;
import io.github.achirdlabs.rift.model.ImposterDefinition;
import io.github.achirdlabs.rift.transport.RiftTransport;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.achirdlabs.rift.dsl.RiftDsl.imposter;
import static io.github.achirdlabs.rift.dsl.RiftDsl.onRequest;
import static io.github.achirdlabs.rift.dsl.RiftDsl.proxyTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A proxy's {@code cert}/{@code key}/{@code passphrase} need rift 0.24.0. An older engine drops all
 * three without a word, so the upstream's mTLS handshake fails at request time with nothing pointing
 * at the cause; a definition carrying them is refused there rather than sent (#342).
 */
class ProxyClientCertificateGateTest {

    private static final String CERT = "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n";
    private static final String KEY = "-----BEGIN PRIVATE KEY-----\nMIGH\n-----END PRIVATE KEY-----\n";
    private static final ImposterSpec MTLS_PROXY = imposter("p")
            .stub(onRequest().willReturn(proxyTo("https://up").clientCertificate(CERT, KEY)));

    private final List<JsonValue> posted = new ArrayList<>();
    private final AtomicInteger buildInfoCalls = new AtomicInteger();

    @Test
    void anOlderEngineIsRefusedBeforeAnythingIsSent() {
        InvalidDefinition e = assertThrows(InvalidDefinition.class, () -> rift(VersionCheck.FAIL, "0.23.0").create(MTLS_PROXY));
        assertTrue(e.getMessage().contains("0.24.0"), e.getMessage());
        assertTrue(e.getMessage().contains("client certificate on a proxy"), e.getMessage());
        assertTrue(e.getMessage().contains("drops them silently"), "says what the old engine would do: " + e.getMessage());
        assertTrue(posted.isEmpty());
    }

    @Test
    void aRawProxyResponseCarryingTheKeysIsCheckedToo() {
        // Raw JSON, without the DSL setter: the gate reads the wire keys, so this path is covered as well.
        ImposterDefinition raw = ImposterDefinition.fromJson("{\"protocol\":\"http\",\"stubs\":[{\"responses\":"
                + "[{\"proxy\":{\"to\":\"https://up\",\"key\":\"k\"}}]}]}");
        assertThrows(InvalidDefinition.class, () -> rift(VersionCheck.FAIL, "0.23.0").create(raw));
        assertTrue(posted.isEmpty());
    }

    @Test
    void replaceAllIsCheckedToo() {
        assertThrows(InvalidDefinition.class,
                () -> rift(VersionCheck.FAIL, "0.23.0").replaceAll(List.of(MTLS_PROXY.build())));
        assertTrue(posted.isEmpty());
    }

    @Test
    void aCurrentEngineAccepts() {
        rift(VersionCheck.FAIL, "0.24.0").create(MTLS_PROXY);
        assertEquals(1, posted.size());
    }

    @Test
    void aProxyWithoutACertificateNeverAsksTheEngine() {
        rift(VersionCheck.FAIL, "0.23.0").create(imposter("plain").stub(onRequest().willReturn(proxyTo("https://up"))));
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
