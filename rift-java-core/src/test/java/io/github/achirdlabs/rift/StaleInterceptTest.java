package io.github.achirdlabs.rift;

import io.github.achirdlabs.rift.json.JsonValue;
import io.github.achirdlabs.rift.transport.RiftTransport;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A listener stopped or replaced outside its handle (the admin plane, another client) no longer
 * blocks a new {@code intercept()}, and the stale handle never stops or clears a listener it does
 * not hold (#316).
 */
class StaleInterceptTest {

    /** A transport whose intercept slot the test sets directly, as the admin plane or another client would. */
    private static final class Engine {
        final AtomicReference<Optional<Integer>> slot = new AtomicReference<>(Optional.empty());
        final AtomicInteger starts = new AtomicInteger();
        final AtomicInteger stops = new AtomicInteger();
        final AtomicInteger clears = new AtomicInteger();
        final AtomicInteger nextPort = new AtomicInteger(9000);
        boolean reportsStatus = true;
        boolean statusFails;

        RiftTransport transport() {
            return (RiftTransport) Proxy.newProxyInstance(RiftTransport.class.getClassLoader(), new Class<?>[] {RiftTransport.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "startIntercept" -> {
                            if (slot.get().isPresent()) {
                                throw new IllegalStateException("409: an intercept listener is already running");
                            }
                            starts.incrementAndGet();
                            int port = nextPort.getAndIncrement();
                            slot.set(Optional.of(port));
                            yield status(port);
                        }
                        case "interceptStatus" -> {
                            if (statusFails) {
                                throw new IllegalStateException("engine unreachable");
                            }
                            if (!reportsStatus) {
                                throw new UnsupportedOperationException("interceptStatus");
                            }
                            yield slot.get().map(Engine::status);
                        }
                        case "stopIntercept" -> {
                            stops.incrementAndGet();
                            slot.set(Optional.empty());
                            yield null;
                        }
                        case "interceptClearRules" -> {
                            clears.incrementAndGet();
                            yield null;
                        }
                        case "interceptListRules" -> JsonValue.parse("[]");
                        case "close" -> null;
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }

        static JsonValue status(int port) {
            return JsonValue.parse("{\"interceptPort\":" + port + ",\"interceptUrl\":\"http://127.0.0.1:" + port + "\"}");
        }
    }

    private static Rift rift(Engine engine) {
        return RiftImpl.spawned(engine.transport(),
                ConnectOptions.builder(URI.create("http://127.0.0.1:2525")).versionCheck(VersionCheck.OFF).build(), () -> { });
    }

    private static InterceptOptions start() {
        return InterceptOptions.builder().build();
    }

    @Test
    void aListenerStoppedOutsideTheHandleFreesTheEngineAndTheStaleHandleStopsNothing() {
        Engine engine = new Engine();
        try (Rift rift = rift(engine)) {
            Intercept stale = rift.intercept(start());
            engine.slot.set(Optional.empty()); // DELETE /intercept through the admin plane

            Intercept fresh = rift.intercept(start());
            assertEquals(2, engine.starts.get());
            assertEquals("intercept listener was stopped outside this handle",
                    assertThrows(IllegalStateException.class, stale::rules).getMessage());
            stale.close();
            assertEquals(0, engine.stops.get(), "the stale handle never stops the new listener");
            assertEquals(Optional.of(9001), engine.slot.get());

            fresh.close();
            assertEquals(1, engine.stops.get());
        }
    }

    @Test
    void aListenerReplacedOnAnotherPortIsNotTheHandlesAndTheEnginesRefusalSurfaces() {
        Engine engine = new Engine();
        try (Rift rift = rift(engine)) {
            Intercept stale = rift.intercept(start());
            engine.slot.set(Optional.of(9500)); // another client stopped it and started its own

            IllegalStateException refused = assertThrows(IllegalStateException.class, () -> rift.intercept(start()));
            assertEquals("409: an intercept listener is already running", refused.getMessage());
            stale.close();
            assertEquals(0, engine.stops.get(), "the stale handle never stops the other client's listener");
            assertEquals(Optional.of(9500), engine.slot.get());
        }
    }

    @Test
    void aListenerStillRunningIsRefusedBeforeAnyStart() {
        Engine engine = new Engine();
        try (Rift rift = rift(engine)) {
            Intercept open = rift.intercept(start());
            assertEquals("intercept already started for this engine",
                    assertThrows(IllegalStateException.class, () -> rift.intercept(start())).getMessage());
            assertEquals(1, engine.starts.get());
            open.close();
            assertEquals(1, engine.stops.get(), "a normal close stops once and frees the engine");
            rift.intercept(start()).close();
            assertEquals(2, engine.starts.get());
        }
    }

    @Test
    void aTransportThatCannotReportTheSlotKeepsRefusing() {
        Engine engine = new Engine();
        engine.reportsStatus = false;
        try (Rift rift = rift(engine)) {
            Intercept open = rift.intercept(start());
            engine.slot.set(Optional.empty());
            assertThrows(IllegalStateException.class, () -> rift.intercept(start()));
            open.close();
            assertEquals(1, engine.stops.get(), "with no status to read, close stops as it always has");
        }
    }

    @Test
    void anEngineThatCannotBeAskedStillGetsTheDocumentedRefusal() {
        Engine engine = new Engine();
        try (Rift rift = rift(engine)) {
            rift.intercept(start());
            engine.statusFails = true;
            IllegalStateException refused = assertThrows(IllegalStateException.class, () -> rift.intercept(start()));
            assertEquals("intercept already started for this engine", refused.getMessage());
            assertEquals("engine unreachable", refused.getCause().getMessage());
            assertEquals(1, engine.starts.get());
        }
    }

    @Test
    void aStaleAttachedHandleClearsNoRules() {
        Engine engine = new Engine();
        engine.slot.set(Optional.of(7000)); // started at engine launch
        try (Rift rift = rift(engine)) {
            Intercept attached = rift.intercept(InterceptOptions.attach("127.0.0.1", 7000));
            engine.slot.set(Optional.empty());
            attached.close();
            assertEquals(0, engine.clears.get(), "a stale attached handle leaves the slot alone");
        }
    }
}
