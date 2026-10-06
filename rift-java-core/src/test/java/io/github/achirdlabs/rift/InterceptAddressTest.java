package io.github.achirdlabs.rift;

import io.github.achirdlabs.rift.json.JsonValue;
import io.github.achirdlabs.rift.transport.RiftTransport;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Where a runtime-started intercept is reached on an engine the SDK runs itself (spawn, embedded):
 * the engine's own bind address, whatever client-side mapping is configured. The connected-engine
 * case, where the bind address is remapped, is covered over a real admin API in {@code
 * RemoteInterceptTest}.
 */
class InterceptAddressTest {

    private final AtomicInteger interceptAddressCalls = new AtomicInteger();

    @Test
    void aLocalEngineKeepsTheEngineAddressWhateverTheClientSideMapping() {
        try (Rift rift = spawned()) {
            Intercept intercept = rift.intercept(InterceptOptions.builder().build());

            assertEquals("127.0.0.1", intercept.address().getHostString());
            assertEquals(9000, intercept.address().getPort());
            assertEquals(URI.create("http://127.0.0.1:9000"), intercept.uri());
            assertEquals(0, interceptAddressCalls.get(), "a local engine's listener is never remapped");
        }
    }

    @Test
    void aLocalEngineOnLoopbackDoesNotWarn() {
        // Loopback is exactly right for an engine the SDK runs itself: the warning is for connected ones.
        Logger jul = Logger.getLogger(RiftImpl.class.getName());
        List<LogRecord> warnings = new CopyOnWriteArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel() == Level.WARNING) {
                    warnings.add(record);
                }
            }

            @Override
            public void flush() { }

            @Override
            public void close() { }
        };
        jul.addHandler(handler);
        try (Rift rift = spawned()) {
            rift.intercept(InterceptOptions.builder().build());
        } finally {
            jul.removeHandler(handler);
        }
        assertEquals(List.of(), warnings);
    }

    @Test
    void engineAddressIsTheBindAddressTheEngineReported() {
        try (Rift rift = spawned()) {
            Intercept intercept = rift.intercept(InterceptOptions.builder().build());

            assertEquals(Optional.of(URI.create("http://127.0.0.1:9000")), intercept.engineAddress());
        }
    }

    @Test
    void anAttachedListenerHasNoEngineAddress() {
        try (Rift rift = spawned()) {
            Intercept intercept = rift.intercept(InterceptOptions.attach("127.0.0.1", 9443));

            assertEquals(Optional.empty(), intercept.engineAddress());
            assertEquals(9443, intercept.address().getPort());
            assertEquals(0, interceptAddressCalls.get(), "an attached endpoint is never remapped");
        }
    }

    @Test
    void aTransportThatCannotStopClearsTheRulesAndKeepsTheEngineClaimed() {
        // A third-party transport predating stopIntercept: close() must still work as it did — clear
        // the rules — and, the listener still running, keep a second start from being attempted.
        AtomicInteger clears = new AtomicInteger();
        RiftTransport transport = (RiftTransport) Proxy.newProxyInstance(
                RiftTransport.class.getClassLoader(), new Class<?>[] {RiftTransport.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "startIntercept" -> JsonValue.parse(
                            "{\"interceptPort\": 9000, \"interceptUrl\": \"http://127.0.0.1:9000\"}");
                    case "interceptClearRules" -> {
                        clears.incrementAndGet();
                        yield null;
                    }
                    case "close" -> null;
                    // stopIntercept falls here, like the SPI's default for a transport that lacks it.
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        try (Rift rift = RiftImpl.spawned(transport,
                ConnectOptions.builder(URI.create("http://127.0.0.1:2525")).versionCheck(VersionCheck.OFF).build(), () -> { })) {
            Intercept intercept = rift.intercept(InterceptOptions.builder().build());
            intercept.close();
            intercept.close();

            assertEquals(1, clears.get());
            assertThrows(IllegalStateException.class, intercept::rules);
            assertThrows(IllegalStateException.class, () -> rift.intercept(InterceptOptions.builder().build()),
                    "the listener could not be stopped, so the engine is still taken");
        }
    }

    /**
     * A spawned engine whose client also carries imposter and intercept mappings, so a test can tell
     * whether either was (wrongly) applied to the intercept listener.
     */
    private Rift spawned() {
        ConnectOptions options = ConnectOptions.builder(URI.create("http://127.0.0.1:2525"))
                .versionCheck(VersionCheck.OFF)
                .hostResolver((protocol, port) -> URI.create("http://mapped.example:" + (port + 10000)))
                .interceptAddress(port -> {
                    interceptAddressCalls.incrementAndGet();
                    return InetSocketAddress.createUnresolved("mapped.example", port + 10000);
                })
                .build();
        RiftTransport transport = (RiftTransport) Proxy.newProxyInstance(
                RiftTransport.class.getClassLoader(), new Class<?>[] {RiftTransport.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "startIntercept" -> JsonValue.parse(
                            "{\"interceptPort\": 9000, \"interceptUrl\": \"http://127.0.0.1:9000\"}");
                    case "interceptListRules" -> JsonValue.parse("[]");
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return RiftImpl.spawned(transport, options, () -> { });
    }
}
