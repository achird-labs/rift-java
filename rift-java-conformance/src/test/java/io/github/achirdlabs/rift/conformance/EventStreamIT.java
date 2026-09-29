package io.github.achirdlabs.rift.conformance;

import io.github.achirdlabs.rift.EventStream;
import io.github.achirdlabs.rift.EventStreamOptions;
import io.github.achirdlabs.rift.Imposter;
import io.github.achirdlabs.rift.RecordedPage;
import io.github.achirdlabs.rift.RecordedRequest;
import io.github.achirdlabs.rift.Rift;
import io.github.achirdlabs.rift.RiftEvent;
import io.github.achirdlabs.rift.error.EngineUnavailable;
import io.github.achirdlabs.rift.error.ImposterNotFound;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

import static io.github.achirdlabs.rift.conformance.LiveEngine.engine;
import static io.github.achirdlabs.rift.conformance.LiveEngine.gated;
import static io.github.achirdlabs.rift.dsl.RiftDsl.imposter;
import static io.github.achirdlabs.rift.dsl.RiftDsl.okJson;
import static io.github.achirdlabs.rift.dsl.RiftDsl.onGet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The admin event stream against a real engine. The unit tests pin framing and lifecycle against a
 * fake; these pin the two claims a fake would simply agree with — that the frames the engine really
 * emits parse into these events, and that the stream's {@code index} is the same journal index the
 * polling side reports, which is the entire basis of the reconcile story.
 *
 * <p>Runs on every transport lane. {@code /events} is an admin-HTTP endpoint, but the embedded
 * transport has one — its lazily-started in-process admin server taps the same event bus the
 * FFI-driven imposters publish to (#174) — so the embedded lane proves that claim rather than
 * assuming it. Needs no corpus, just {@code RIFT_IT=1}.
 */
class EventStreamIT {

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @TestFactory
    Stream<DynamicTest> theStreamPushesWhatTheJournalRecords() {
        return gated("events arrive, and carry the journal's own index", () -> {
            try (Rift rift = engine()) {
                Imposter imp = recordingImposter(rift);

                try (EventStream events = rift.events(EventStreamOptions.builder().port(imp.port()).build())) {
                    Iterator<RiftEvent> it = events.iterator();

                    RiftEvent.Hello hello = assertInstanceOf(RiftEvent.Hello.class, it.next(),
                            "the engine opens with hello");
                    if (ConformanceTransport.selected() == ConformanceTransport.SPAWN) {
                        // The hello envelope reports the version we pinned and spawned — assert
                        // against the pin itself so this stays a drift-catcher rather than a
                        // hardcoded prefix. Not asserted on the embedded lane: there the cdylib
                        // comes from RIFT_FFI_LIB and may legitimately be a local dev build
                        // reporting the 0.1.0 placeholder — a mismatch the SDK itself demotes to a
                        // warning even in FAIL mode, so this must not be stricter than the product.
                        assertEquals(io.github.achirdlabs.rift.RiftVersion.engineVersion(), hello.engineVersion());
                    }

                    get(imp.uri() + "/pushed");

                    RiftEvent.RequestRecorded pushed = assertInstanceOf(RiftEvent.RequestRecorded.class, it.next());
                    assertEquals(imp.port(), pushed.port());
                    assertEquals("/pushed", pushed.request().path());
                    assertTrue(pushed.seq().isPresent(), "a request event carries its sequence id");

                    // The load-bearing claim: the pushed index and the polled cursor are the same
                    // number. If they were not, reconciling from a stream index would silently skip
                    // or replay, which is the bug the cursor exists to remove.
                    RecordedPage page = imp.recordedPage();
                    assertEquals(page.nextIndex(), pushed.index(),
                            "the stream's index IS the journal cursor");
                }
            }
        });
    }

    @TestFactory
    Stream<DynamicTest> aStreamForAPortWithNoImposterIsImposterNotFound() {
        // The engine refuses with a 404 — the same status an engine without /events gives — so this
        // pins that the SDK reports the wrong port, not a missing capability (#254).
        return gated("an unknown port is ImposterNotFound, not 'cannot stream'", () -> {
            try (Rift rift = engine()) {
                int nobody = 1; // privileged, so never an imposter this suite created
                ImposterNotFound e = assertThrows(ImposterNotFound.class,
                        () -> rift.events(EventStreamOptions.builder().port(nobody).build()).close());
                assertEquals(nobody, e.port());
            }
        });
    }

    @TestFactory
    Stream<DynamicTest> aBrokenStreamIsRecoveredByPollingFromTheLastIndexSeen() {
        return gated("the reconcile loop, end to end", () -> {
            try (Rift rift = engine()) {
                Imposter imp = recordingImposter(rift);
                long cursor;

                try (EventStream events = rift.events(EventStreamOptions.builder().port(imp.port()).build())) {
                    Iterator<RiftEvent> it = events.iterator();
                    assertInstanceOf(RiftEvent.Hello.class, it.next());

                    get(imp.uri() + "/seen");
                    RiftEvent.RequestRecorded seen = assertInstanceOf(RiftEvent.RequestRecorded.class, it.next());
                    cursor = seen.index().orElseThrow();
                }
                // The stream is gone. Anything recorded now is invisible to it — this is the hole a
                // lagged event or a dropped connection leaves, reproduced deterministically.
                get(imp.uri() + "/missed-1");
                get(imp.uri() + "/missed-2");

                RecordedPage missed = imp.recordedSince(cursor);

                assertEquals(List.of("/missed-1", "/missed-2"),
                        missed.requests().stream().map(RecordedRequest::path).toList(),
                        "polling from the last index seen yields exactly what the stream missed");
                assertTrue(missed.nextIndex().getAsLong() > cursor, "and hands back a cursor to resume from");
            }
        });
    }

    @TestFactory
    Stream<DynamicTest> lifecycleEventsArriveOnTheSameStream() {
        return gated("an imposter change is pushed too", () -> {
            try (Rift rift = engine()) {
                Imposter imp = recordingImposter(rift);

                try (EventStream events = rift.events(EventStreamOptions.builder()
                        .types(EventStreamOptions.EventType.LIFECYCLE)
                        .build())) {
                    Iterator<RiftEvent> it = events.iterator();
                    assertInstanceOf(RiftEvent.Hello.class, it.next());

                    imp.addStub(onGet("/added").willReturn(okJson("{}")));

                    RiftEvent.ImposterChanged changed =
                            assertInstanceOf(RiftEvent.ImposterChanged.class, it.next());
                    assertEquals(RiftEvent.ImposterChanged.Action.STUBS_CHANGED, changed.action());
                    assertEquals(imp.port(), changed.port().orElseThrow());

                    // A second, independently created imposter must surface on the SAME
                    // subscription. That is the actual claim — one event bus behind the whole data
                    // plane — and one imposter cannot distinguish it from a stream that happens to
                    // be scoped to whatever imposter existed when it was opened.
                    Imposter other = rift.create(imposter("events-2")
                            .protocol("http")
                            .stub(onGet("/other").willReturn(okJson("{}"))));

                    RiftEvent.ImposterChanged created =
                            assertInstanceOf(RiftEvent.ImposterChanged.class, it.next());
                    assertEquals(RiftEvent.ImposterChanged.Action.CREATED, created.action());
                    assertEquals(other.port(), created.port().orElseThrow());
                }
            }
        });
    }

    @TestFactory
    Stream<DynamicTest> closingTheEngineEndsAnOpenStreamLoudly() {
        return gated("an engine that goes away is reported, not just an end of iteration", () -> {
            Rift rift = engine();
            try (EventStream events = rift.events(EventStreamOptions.builder().build())) {
                Iterator<RiftEvent> it = events.iterator();
                assertInstanceOf(RiftEvent.Hello.class, it.next());

                rift.close();

                // Loud, not quiet. A tail cannot distinguish "nothing is happening" from "the
                // engine is gone", so the stream must raise rather than report exhaustion. This
                // matters most on the embedded lane, where the engine IS this process and closing
                // the Rift therefore always ends the stream — the one place Rift.events' "streams
                // outlive the client" contract cannot hold.
                assertThrows(EngineUnavailable.class, it::hasNext);
            }
        });
    }

    private static Imposter recordingImposter(Rift rift) {
        return rift.create(imposter("events")
                .protocol("http")
                .record()
                .stub(onGet("/pushed").willReturn(okJson("{\"ok\":true}"))));
    }

    private static void get(String url) throws Exception {
        HttpResponse<String> response = HTTP.send(
                HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "the request must be served, so it is recorded");
    }
}
