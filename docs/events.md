# Event stream (`EventStream`)

`rift.events(...)` opens a server-sent-events tail of what the engine is doing — every request an
imposter records, and every imposter created, replaced or deleted — pushed as it happens instead of
polled for. It's obtained from a running `Rift` client:

```java
try (EventStream events = rift.events(EventStreamOptions.builder()
        .types(EventStreamOptions.EventType.REQUESTS)
        .build())) {
    for (RiftEvent event : events) {
        // ...
    }
}
```

The stream is an `Iterable<RiftEvent>` and an `AutoCloseable`. Iterating blocks until the next event
arrives, so a tail normally runs on its own thread; closing the stream ends the iteration.

This works on every transport, the in-process embedded engine included — see
[Transports](#transport-support) below.

## Choosing what arrives

```java
EventStreamOptions options = EventStreamOptions.builder()
        .types(EventStreamOptions.EventType.REQUESTS, EventStreamOptions.EventType.LIFECYCLE)
        .port(4545)                                    // only this imposter
        .match(MatchClause.header("X-Tenant", "acme"))  // only requests carrying this header
        .build();
```

| Option | Effect |
|---|---|
| `types(...)` | Which families to receive: `REQUESTS` (an imposter recorded a request), `LIFECYCLE` (an imposter was created/replaced/deleted). Both by default. |
| `port(int)` | Restrict to one imposter. Omit for every imposter on the engine. |
| `match(MatchClause...)` | Server-side filter, applied to **request events only** — a lifecycle event carries no request to match against. Same clause vocabulary as the recorded-request cursor; see [Filtering with `MatchClause`](spaces.md#filtering-with-matchclause). |

Filtering happens engine-side, so a narrow stream costs only what it delivers rather than being
filtered after the fact in your process.

## The events

`RiftEvent` is a sealed interface with four variants:

```java
for (RiftEvent event : events) {
    if (event instanceof RiftEvent.Hello hello) {
        System.out.println("connected to engine " + hello.engineVersion());
    } else if (event instanceof RiftEvent.RequestRecorded recorded) {
        System.out.println(recorded.port() + " " + recorded.request().path());
    } else if (event instanceof RiftEvent.ImposterChanged changed) {
        System.out.println(changed.action() + " " + changed.port().orElse(-1));
    } else if (event instanceof RiftEvent.Lagged lagged) {
        System.out.println("dropped " + lagged.missed() + " events");
    }
}
```

`rift-java-core` targets **JDK 17**, so the samples here use `instanceof` patterns, which work there.
On JDK 21+ the same dispatch can be an exhaustive `switch` over the sealed type, and the compiler
will then tell you when a new variant appears:

```java
// JDK 21+
switch (event) {
    case RiftEvent.Hello hello -> { /* ... */ }
    case RiftEvent.RequestRecorded recorded -> { /* ... */ }
    case RiftEvent.ImposterChanged changed -> { /* ... */ }
    case RiftEvent.Lagged lagged -> { /* ... */ }
}
```

**`RiftEvent.Hello`** — always the first event on a fresh connection. Carries the engine version, the
journal sequence at connect time (`seqAtConnect`), the event types the server actually enabled, and
the port filter if one was set. `seqAtConnect` is the join point: anything recorded before it is not
on this stream, so a tail that must not miss earlier traffic reads the journal up to that sequence
first (see [Reconciling with the journal](#reconciling-with-the-journal)).

**`RiftEvent.RequestRecorded`** — an imposter recorded a request. Carries the imposter `port()`, the
`request()` itself, its journal `index()` where the engine reports one, the resolved `flowId()` when
the imposter has flow state configured, and the stream `seq()`. Only imposters that record requests
(`.record()`) produce it. The event is pushed as the request arrives, before it is answered, so its
`request()` never has a `status()` or `latencyMs()`; read the journal for those.

**`RiftEvent.ImposterChanged`** — an imposter was created, replaced or deleted (`action()`). Its
`port()` is an `OptionalInt` because the delete-all case names no single port.

**`RiftEvent.Lagged`** — **the one you must not ignore.** The engine buffers per connection; a
consumer slower than the producer overflows that buffer and the engine drops events rather than
blocking the imposters. `missed()` is how many. Seeing it means your view has a hole: re-baseline
from the journal rather than assuming the tail is complete.

## Reconciling with the journal

The stream is a *tail*, not a transcript — it starts at `seqAtConnect` and can drop under lag. When
you need certainty about everything an imposter has seen, the cursor API is the authority and the
stream is the low-latency signal on top of it:

```java
try (EventStream events = rift.events(options)) {
    RiftEvent.Hello hello = (RiftEvent.Hello) events.iterator().next();

    // everything already recorded before the stream joined
    RecordedPage baseline = imposter.recordedPage();
    long cursor = baseline.nextIndex().orElse(0);

    for (RiftEvent event : events) {
        if (event instanceof RiftEvent.Lagged) {
            // the tail has a hole — re-read from the cursor instead of trusting it
            RecordedPage caughtUp = imposter.recordedSince(cursor);
            cursor = caughtUp.nextIndex().orElse(cursor);
        }
    }
}
```

`RecordedPage.nextIndex()` is opaque — pass it back verbatim; never synthesize one from a list
offset. See [Cursor reads](spaces.md#cursor-reads-over-the-journal).

This recipe works on every transport, embedded included — its cursor reads are delegated to the same
in-process admin server the stream comes from, so both halves of the loop report the same journal
index.

## Timeouts and reconnection

A stream that has gone quiet is indistinguishable from a stream whose connection died, so the engine
sends heartbeats and the client applies an **idle timeout**: if nothing at all arrives — not even a
heartbeat — for the configured window, the iteration ends rather than blocking on a dead socket. A
heartbeat resets that clock, so a genuinely idle-but-healthy stream stays open indefinitely.

Reconnection is deliberately **not** automatic. A new connection gets a new `Hello` with a new
`seqAtConnect`, and everything recorded during the gap is missing from it — silently resubscribing
would paper over that hole. Reconnect explicitly and re-baseline from the cursor, as in
[Reconciling with the journal](#reconciling-with-the-journal).

## Transport support

| Transport | Event stream |
|---|---|
| `Rift.connect(uri)` | Yes |
| `Rift.spawn()` | Yes |
| `Rift.embedded()` | Yes — the in-process engine starts an admin server of its own on demand and streams from that. The events are the same ones: the stream taps the engine's event bus, which the in-process imposters publish to whether they are driven over HTTP or through the FFI data plane. The [reconcile loop](#reconciling-with-the-journal) works here too — `recordedSince` is delegated to the same admin server, so its cursor is the stream's index |

The first call on an embedded engine that needs that admin server pays its start-up — `events()`, and
equally the cursor reads the reconcile loop below opens with. Build the engine with
`EmbeddedOptions.serveAdminEagerly(true)` to pay it at startup instead — useful when the first event
matters to within milliseconds.

One lifecycle difference is worth knowing. On the connected and spawned transports a stream outlives
the `Rift` that opened it, because it holds its own connection to a separate process. On the embedded
transport the engine *is* this process, so closing the `Rift` stops the engine and ends the stream
with it — iteration then throws `EngineUnavailable`, the same loud disconnect it reports for a
spawned engine that goes away. Close streams before closing the engine.

What still refuses is an engine too old to serve `/events`: that throws `UnsupportedOperationException`
rather than returning an empty stream that would look like "nothing is happening". Same principle the
filtered cursor reads follow — a connection that cannot answer the question says so, instead of
returning a plausible-looking answer that is wrong.
