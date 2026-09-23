package io.github.achirdlabs.rift;

import io.github.achirdlabs.rift.json.JsonValue;
import io.github.achirdlabs.rift.transport.RiftTransport;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.Optional;
import java.util.function.Supplier;

import static io.github.achirdlabs.rift.dsl.RiftDsl.imposter;
import static io.github.achirdlabs.rift.dsl.RiftDsl.ok;
import static io.github.achirdlabs.rift.dsl.RiftDsl.onGet;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An imposter bound to its own host is reached there, not at the admin host, on an engine the SDK
 * runs locally (#243). The engine binds an imposter on its {@code host} field (default {@code
 * 0.0.0.0}) whatever interface the admin API uses.
 */
class ImposterUriHostTest {

    private static RiftTransport transport(Supplier<JsonValue> getImposter, Supplier<JsonValue> list) {
        return (RiftTransport) Proxy.newProxyInstance(
                RiftTransport.class.getClassLoader(), new Class<?>[] {RiftTransport.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "createImposter" -> JsonValue.parse("{\"port\": 4545}");
                    // A local engine asks for the replayable shape, the only one carrying the host.
                    case "listImposters" -> {
                        if (!(Boolean) args[0]) {
                            throw new AssertionError("expected a replayable listImposters");
                        }
                        yield list.get();
                    }
                    case "adminUri" -> URI.create("http://127.0.0.1:2525");
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static Rift spawned() {
        ConnectOptions options = ConnectOptions.builder(URI.create("http://127.0.0.1:2525")).versionCheck(VersionCheck.OFF).build();
        return RiftImpl.spawned(transport(() -> null,
                () -> JsonValue.parse("{\"imposters\":[{\"port\":4545,\"host\":\"::1\"},{\"port\":4546}]}")), options, () -> { });
    }

    private static Rift embedded(String adminHost) {
        return RiftImpl.embedded(transport(() -> JsonValue.parse("{\"port\":4545}"), () -> JsonValue.parse("{\"imposters\":[]}")),
                EmbeddedOptions.builder().adminHost(adminHost).versionCheck(VersionCheck.OFF).build(), () -> { });
    }

    private static URI created(Rift rift, String host) {
        return rift.create(imposter("i").host(host).stub(onGet("/").willReturn(ok()))).uri();
    }

    @Test
    void aConcreteImposterHostWinsOnASpawnedEngine() {
        assertEquals("http://127.0.0.2:4545", created(spawned(), "127.0.0.2").toString());
        assertEquals("http://[::1]:4545", created(spawned(), "::1").toString());
        assertEquals("http://localhost:4545", created(spawned(), "localhost").toString());
    }

    @Test
    void aConcreteImposterHostWinsOnAnEmbeddedEngine() {
        assertEquals("http://127.0.0.1:4545", created(embedded("::1"), "127.0.0.1").toString());
        assertEquals("http://[::1]:4545", created(embedded("127.0.0.1"), "[::1]").toString());
    }

    @Test
    void aWildcardOrAbsentHostFallsBackToTheAdminHost() {
        assertEquals("http://127.0.0.1:4545", created(spawned(), "0.0.0.0").toString());
        assertEquals("http://127.0.0.1:4545", created(spawned(), "::").toString());
        assertEquals("http://127.0.0.1:4545", created(spawned(), "[::]").toString());
        assertEquals("http://127.0.0.1:4545", created(spawned(), "0:0:0:0:0:0:0:0").toString());
        assertEquals("http://127.0.0.1:4545", created(spawned(), "[0:0:0:0:0:0:0:0]").toString());
        assertEquals("http://127.0.0.1:4545",
                spawned().create(imposter("i").stub(onGet("/").willReturn(ok()))).uri().toString());
        assertEquals("http://[::1]:4545",
                embedded("::1").create(imposter("i").stub(onGet("/").willReturn(ok()))).uri().toString());
    }

    @Test
    void lookupsReadTheHostFromTheReplayableShape() {
        // A single GET never carries the host, so a local engine's lookup reads the replayable list.
        assertEquals("http://[::1]:4545", spawned().imposter(4545).orElseThrow().uri().toString());
        assertEquals(Optional.empty(), spawned().imposter(9999));
        var listed = spawned().imposters();
        assertEquals("http://[::1]:4545", listed.get(0).uri().toString());
        assertEquals("http://127.0.0.1:4546", listed.get(1).uri().toString());
    }
}
