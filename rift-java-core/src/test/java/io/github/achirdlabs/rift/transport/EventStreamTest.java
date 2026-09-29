package io.github.achirdlabs.rift.transport;

import io.github.achirdlabs.rift.ConnectOptions;
import io.github.achirdlabs.rift.EventStream;
import io.github.achirdlabs.rift.EventStreamOptions;
import io.github.achirdlabs.rift.MatchClause;
import io.github.achirdlabs.rift.Rift;
import io.github.achirdlabs.rift.RiftEvent;
import io.github.achirdlabs.rift.VersionCheck;
import io.github.achirdlabs.rift.error.CommunicationError;
import io.github.achirdlabs.rift.error.EngineError;
import io.github.achirdlabs.rift.error.EngineUnavailable;
import io.github.achirdlabs.rift.error.ImposterNotFound;
import io.github.achirdlabs.rift.error.InvalidDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.OptionalInt;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The admin event stream client. These drive a fake that writes real SSE frames, so they pin the
 * framing and the lifecycle — the parts a live engine can't easily be coerced into producing on
 * demand (a malformed frame, a silent connection, a 404). {@code EventStreamIT} proves the rest
 * against a real engine.
 */
@Timeout(20)
class EventStreamTest {

    private static final String HELLO =
            "event: hello\ndata: {\"engineVersion\":\"0.13.6\",\"port\":null,\"seq\":0,\"types\":[\"requests\",\"lifecycle\"]}\n\n";

    private static Rift connect(FakeAdminServer s) {
        return Rift.connect(ConnectOptions.builder(s.baseUri()).versionCheck(VersionCheck.OFF).build());
    }

    private static EventStreamOptions opts() {
        return EventStreamOptions.builder().build();
    }

    private static String requestFrame(long id, int port, String path, String index) {
        return "event: request\nid: " + id + "\ndata: {\"flowId\":\"f1\"," + index + "\"port\":" + port
                + ",\"request\":{\"method\":\"GET\",\"path\":\"" + path + "\"}}\n\n";
    }

    @Test
    void everyEventFamilyIsParsed() throws Exception {
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.stream("GET /events", 200, sink -> {
                sink.write(HELLO);
                sink.write(": ping\n\n");
                sink.write(requestFrame(1, 4545, "/a", "\"index\":7,"));
                sink.write("event: imposter\nid: 2\ndata: {\"action\":\"stubsChanged\",\"port\":4545}\n\n");
                sink.write("event: lagged\ndata: {\"missed\":7}\n\n");
                // AllDeleted names no single port — the engine omits it rather than inventing one.
                sink.write("event: imposter\nid: 3\ndata: {\"action\":\"allDeleted\"}\n\n");
            });
            try (Rift rift = connect(s); EventStream stream = rift.events(opts())) {
                List<RiftEvent> events = drain(stream, 5);

                RiftEvent.Hello hello = assertInstanceOf(RiftEvent.Hello.class, events.get(0));
                assertEquals("0.13.6", hello.engineVersion());
                assertEquals(0, hello.seqAtConnect());
                assertEquals(List.of("requests", "lifecycle"), hello.types());
                assertEquals(OptionalInt.empty(), hello.port());
                assertEquals(OptionalLong.empty(), hello.seq(), "hello is not a position in the sequence");

                RiftEvent.RequestRecorded req = assertInstanceOf(RiftEvent.RequestRecorded.class, events.get(1));
                assertEquals(OptionalLong.of(1), req.seq());
                assertEquals(4545, req.port());
                assertEquals(OptionalLong.of(7), req.index(), "the journal index a tail reconciles from");
                assertEquals("f1", req.flowId().orElseThrow());
                assertEquals("/a", req.request().path());

                RiftEvent.ImposterChanged changed = assertInstanceOf(RiftEvent.ImposterChanged.class, events.get(2));
                assertEquals(RiftEvent.ImposterChanged.Action.STUBS_CHANGED, changed.action());
                assertEquals(OptionalInt.of(4545), changed.port());
                assertEquals(OptionalLong.of(2), changed.seq());

                RiftEvent.Lagged lagged = assertInstanceOf(RiftEvent.Lagged.class, events.get(3));
                assertEquals(7, lagged.missed());

                RiftEvent.ImposterChanged all = assertInstanceOf(RiftEvent.ImposterChanged.class, events.get(4));
                assertEquals(RiftEvent.ImposterChanged.Action.ALL_DELETED, all.action());
                assertEquals(OptionalInt.empty(), all.port(), "an engine-wide event has no port to report");
            }
        }
    }

    @Test
    void aHeartbeatIsConsumedNotSurfaced() throws Exception {
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.stream("GET /events", 200, sink -> {
                sink.write(": ping\n\n");
                sink.write(": ping\n\n");
                sink.write(HELLO);
            });
            try (Rift rift = connect(s); EventStream stream = rift.events(opts())) {
                // A consumer must never see the keepalive; it exists to prove the socket is alive.
                assertInstanceOf(RiftEvent.Hello.class, drain(stream, 1).get(0));
            }
        }
    }

    @Test
    void aMultiLineDataFieldIsConcatenated() throws Exception {
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.stream("GET /events", 200, sink -> sink.write(
                    "event: hello\ndata: {\"engineVersion\":\"0.13.6\",\n"
                            + "data: \"port\":null,\"seq\":3,\"types\":[]}\n\n"));
            try (Rift rift = connect(s); EventStream stream = rift.events(opts())) {
                RiftEvent.Hello hello = assertInstanceOf(RiftEvent.Hello.class, drain(stream, 1).get(0));

                assertEquals(3, hello.seqAtConnect(), "the frame's data spans two lines and is one JSON object");
            }
        }
    }

    @Test
    void anUnknownEventTypeIsSkippedButAMalformedKnownOneIsLoud() throws Exception {
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.stream("GET /events", 200, sink -> {
                // Forward compatibility: the engine may grow families this client predates.
                sink.write("event: somethingNew\nid: 9\ndata: {\"whatever\":true}\n\n");
                sink.write(HELLO);
            });
            try (Rift rift = connect(s); EventStream stream = rift.events(opts())) {
                assertInstanceOf(RiftEvent.Hello.class, drain(stream, 1).get(0));
            }
        }
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.stream("GET /events", 200, sink ->
                    sink.write("event: request\nid: 1\ndata: {not json at all\n\n"));
            try (Rift rift = connect(s); EventStream stream = rift.events(opts())) {
                Iterator<RiftEvent> it = stream.iterator();

                // A family we DO know, whose payload we cannot read, is a broken engine or proxy —
                // skipping it would silently drop an event the caller is counting on.
                assertThrows(CommunicationError.class, it::hasNext);
            }
        }
    }

    @Test
    void closeEndsIterationGracefully() throws Exception {
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.stream("GET /events", 200, sink -> {
                sink.write(HELLO);
                // Hold the stream open until the client leaves, so close() is what ends it.
                while (!sink.clientGone()) {
                    Thread.sleep(20);
                    sink.write(": ping\n\n");
                }
            });
            try (Rift rift = connect(s)) {
                EventStream stream = rift.events(opts());
                Iterator<RiftEvent> it = stream.iterator();
                assertInstanceOf(RiftEvent.Hello.class, it.next());

                stream.close();

                assertFalse(it.hasNext(), "a closed stream ends; it does not throw");
                stream.close();  // idempotent
            }
        }
    }

    @Test
    void aServerThatDisconnectsIsLoudNotAQuietEnd() throws Exception {
        try (FakeAdminServer s = new FakeAdminServer()) {
            // The handler returns, so the engine drops the connection while the client still wants it.
            s.stream("GET /events", 200, sink -> sink.write(HELLO));
            try (Rift rift = connect(s); EventStream stream = rift.events(opts())) {
                Iterator<RiftEvent> it = stream.iterator();
                assertInstanceOf(RiftEvent.Hello.class, it.next());

                // Ending quietly here would be indistinguishable from "nothing is happening", and a
                // tail cannot tell those apart — so a dropped stream throws and the caller reconciles.
                assertThrows(EngineUnavailable.class, it::hasNext);
            }
        }
    }

    @Test
    void aHeartbeatKeepsAnEventlessStreamAlivePastTheIdleTimeout() throws Exception {
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.stream("GET /events", 200, sink -> {
                sink.write(HELLO);
                // Alive, but with nothing to say — the steady state of a tail on an imposter nobody
                // is calling. Only the heartbeat separates it from a connection that died.
                for (int i = 0; i < 30 && !sink.clientGone(); i++) {
                    Thread.sleep(50);
                    sink.write(": ping\n\n");
                }
                sink.write(requestFrame(1, 4545, "/finally", ""));
            });
            try (Rift rift = connect(s);
                 EventStream stream = rift.events(
                         EventStreamOptions.builder().idleTimeout(Duration.ofSeconds(1)).build())) {
                Iterator<RiftEvent> it = stream.iterator();
                assertInstanceOf(RiftEvent.Hello.class, it.next());

                // Two numbers matter, in opposite directions. The quiet span (~1.5s of pings) must
                // exceed the 1s timeout, or an events-only clock would survive and the test would
                // prove nothing. Each ping gap (50ms) must stay far under it, or a loaded runner
                // stalling between writes looks like a dead connection — which it did at 50ms
                // against a 200ms timeout. 20x of headroom buys the second without losing the first.
                assertInstanceOf(RiftEvent.RequestRecorded.class, it.next());
            }
        }
    }

    @Test
    void aFieldOfTheWrongTypeIsLoudNotDefaulted() throws Exception {
        try (FakeAdminServer s = new FakeAdminServer()) {
            // port is how a consumer routes the event; silently reading it as 0 would attribute the
            // request to an imposter that does not exist, and nothing downstream could tell.
            s.stream("GET /events", 200, sink -> sink.write(
                    "event: request\nid: 1\ndata: {\"port\":\"not-a-number\","
                            + "\"request\":{\"method\":\"GET\",\"path\":\"/a\"}}\n\n"));
            try (Rift rift = connect(s); EventStream stream = rift.events(opts())) {
                Iterator<RiftEvent> it = stream.iterator();

                CommunicationError e = assertThrows(CommunicationError.class, it::hasNext);
                assertTrue(e.getMessage().contains("port"), e.getMessage());
            }
        }
        try (FakeAdminServer s = new FakeAdminServer()) {
            // A lagged event whose count defaulted to 0 would report a hole with nothing in it.
            s.stream("GET /events", 200, sink -> sink.write("event: lagged\ndata: {}\n\n"));
            try (Rift rift = connect(s); EventStream stream = rift.events(opts())) {
                assertThrows(CommunicationError.class, () -> stream.iterator().hasNext());
            }
        }
    }

    @Test
    void silenceBeyondTheIdleTimeoutIsADeadConnection() throws Exception {
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.stream("GET /events", 200, sink -> {
                sink.write(HELLO);
                Thread.sleep(5_000);   // no data, and no heartbeat either
            });
            try (Rift rift = connect(s);
                 EventStream stream = rift.events(
                         EventStreamOptions.builder().idleTimeout(Duration.ofMillis(300)).build())) {
                Iterator<RiftEvent> it = stream.iterator();
                assertInstanceOf(RiftEvent.Hello.class, it.next());

                assertThrows(EngineUnavailable.class, it::hasNext);
            }
        }
    }

    @Test
    void a404MeansThisEngineCannotStreamAtAll() {
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.respond("GET /events", 404, "{\"errors\":[{\"code\":\"404\",\"message\":\"Not Found\"}]}");
            try (Rift rift = connect(s)) {
                // Indistinguishable from the embedded transport's answer on purpose: both mean poll.
                assertThrows(UnsupportedOperationException.class, () -> rift.events(opts()));
            }
        }
    }

    private static EventStreamOptions onPort(int port) {
        return EventStreamOptions.builder().port(port).build();
    }

    private static final String IMPOSTER_NOT_FOUND =
            "{\"errors\":[{\"code\":\"404\",\"type\":\"no such resource\",\"message\":\"Imposter not found on port 4545\"}]}";

    @Test
    void anUnknownPortIsImposterNotFoundNotAMissingStream() {
        // rift <= 0.18.1: a bare {"error"} body from /events, the canonical envelope from the imposter route.
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.respond("GET /events", 404, "{\"error\":\"no imposter on port 4545\"}");
            s.respond("GET /imposters/4545", 404, IMPOSTER_NOT_FOUND);
            try (Rift rift = connect(s)) {
                ImposterNotFound e = assertThrows(ImposterNotFound.class, () -> rift.events(onPort(4545)));
                assertEquals(4545, e.port());
                assertEquals("Imposter not found on port 4545", e.getMessage());
            }
        }
    }

    @Test
    void anUnknownPortIsImposterNotFoundUnderTheEnvelopeToo() {
        // rift#1226: /events answers the same envelope an engine without the route would, so only the
        // imposter lookup can tell them apart.
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.respond("GET /events", 404, IMPOSTER_NOT_FOUND);
            s.respond("GET /imposters/4545", 404, IMPOSTER_NOT_FOUND);
            try (Rift rift = connect(s)) {
                ImposterNotFound e = assertThrows(ImposterNotFound.class, () -> rift.events(onPort(4545)));
                assertEquals(4545, e.port());
            }
        }
    }

    @Test
    void a404ForAPortThatExistsStillMeansThisEngineCannotStream() {
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.respond("GET /events", 404, "{\"errors\":[{\"code\":\"404\",\"message\":\"Not Found\"}]}");
            s.respond("GET /imposters/4545", 200, "{\"protocol\":\"http\",\"port\":4545}");
            try (Rift rift = connect(s)) {
                assertThrows(UnsupportedOperationException.class, () -> rift.events(onPort(4545)));
            }
        }
    }

    @Test
    void aBareErrorBodyStillCarriesItsMessage() {
        // rift <= 0.18.1 refuses a bad filter with {"error": "..."}; the message must not be the raw JSON.
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.respond("GET /events", 400, "{\"error\":\"unknown types value 'bogus' (expected requests|lifecycle)\"}");
            try (Rift rift = connect(s)) {
                InvalidDefinition e = assertThrows(InvalidDefinition.class, () -> rift.events(opts()));
                assertEquals("unknown types value 'bogus' (expected requests|lifecycle)", e.getMessage());
            }
        }
    }

    @Test
    void aRejectedConnectFailsUpFrontNotMidIteration() {
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.respond("GET /events", 401, "{\"errors\":[{\"code\":\"401\",\"message\":\"bad api key\"}]}");
            try (Rift rift = connect(s)) {
                EngineError e = assertThrows(EngineError.class, () -> rift.events(opts()));
                assertTrue(e.getMessage().contains("bad api key"), e.getMessage());
            }
        }
    }

    @Test
    void theFiltersAreRenderedOntoTheConnectUrl() throws Exception {
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.stream("GET /events", 200, sink -> sink.write(HELLO));
            try (Rift rift = connect(s);
                 EventStream stream = rift.events(EventStreamOptions.builder()
                         .types(EventStreamOptions.EventType.REQUESTS)
                         .port(4545)
                         .match(MatchClause.flowId("tenant-a"))
                         .build())) {
                drain(stream, 1);

                String path = s.received().stream().map(FakeAdminServer.Received::path)
                        .filter(p -> p.startsWith("/events")).findFirst().orElseThrow();
                assertEquals("/events?types=requests&port=4545&match=flow_id%3Dtenant-a", path);
            }
        }
    }

    @Test
    void theMethodAndPathFiltersAreRenderedOntoTheConnectUrlToo() throws Exception {
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.stream("GET /events", 200, sink -> sink.write(HELLO));
            try (Rift rift = connect(s);
                 EventStream stream = rift.events(EventStreamOptions.builder()
                         .types(EventStreamOptions.EventType.REQUESTS)
                         .match(MatchClause.method("POST"), MatchClause.path("/orders"))
                         .build())) {
                drain(stream, 1);

                String path = s.received().stream().map(FakeAdminServer.Received::path)
                        .filter(p -> p.startsWith("/events")).findFirst().orElseThrow();
                assertEquals("/events?types=requests&match=method%3DPOST&match=path%3D%2Forders", path);
            }
        }
    }

    @Test
    void theStreamIsSingleUseAndRefusesToBeReopened() throws Exception {
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.stream("GET /events", 200, sink -> {
                sink.write(HELLO);
                while (!sink.clientGone()) {
                    Thread.sleep(20);
                    sink.write(": ping\n\n");
                }
            });
            try (Rift rift = connect(s)) {
                EventStream stream = rift.events(opts());
                assertTrue(stream.iterator() == stream.iterator(), "a live stream has no start to return to");
                stream.close();
                assertThrows(IllegalStateException.class, stream::iterator);
            }
        }
    }

    private static List<RiftEvent> drain(EventStream stream, int count) {
        List<RiftEvent> out = new ArrayList<>();
        Iterator<RiftEvent> it = stream.iterator();
        while (out.size() < count && it.hasNext()) {
            out.add(it.next());
        }
        assertEquals(count, out.size(), "expected " + count + " events, got " + out);
        return out;
    }
}
