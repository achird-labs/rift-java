# Spaces and flow state

A **space** is one imposter partitioned by a *flow id*, so concurrent tests can share a single
imposter without seeing each other's stubs, scenario state or recorded traffic. The engine resolves
the flow id from each incoming request — normally a header your system under test already
propagates, like a tenant or correlation id — and keeps everything scoped to it separate.

This is what lets a suite run in parallel against one engine instead of one imposter per test.

## Configuring the flow id

An imposter opts in by declaring where the flow id comes from:

```java
Imposter imp = rift.create(imposter("orders")
        .record()
        .flowState(inMemoryFlowState().flowIdFromHeader("X-Tenant"))
        .stub(onGet("/orders").willReturn(okJson("[]"))));
```

Without an explicit source the engine falls back to the **imposter port**, which means every request
resolves to the same flow — so space-scoped stubs would never match. The SDK warns when it sees an
imposter that uses spaces but declares no header source, because that combination is almost always a
mistake rather than a choice.

`redisFlowState(url)` swaps the in-memory store for Redis when flow state has to outlive a single
engine or be shared across several.

## Working in a space

`imposter.space(flowId)` is the flow-scoped view of the imposter:

```java
Space acme = imp.space("acme");

acme.addStub(onGet("/orders").willReturn(okJson("[{\"id\":1}]")));  // only for X-Tenant: acme
List<RecordedRequest> seen = acme.recorded();                        // only acme's traffic
acme.verify(onGet("/orders"), times(1));                             // only acme's calls
acme.delete();                                                       // drop this flow's state
```

Each `RecordedRequest` also carries the outcome the engine recorded for it on rift ≥ 0.18.0 —
`status()` and `latencyMs()` — and `summary()` renders it as `GET /orders → 200 in 3 ms`. Both are
empty for a request still in flight when the journal was read, and on older engines.

Everything on a `Space` — stubs, recorded requests, verification — is confined to that flow. A stub
added to the default view still serves every flow; a stub added to a space serves only its own.

The DSL can place a stub in a space at creation time instead:

```java
rift.create(imposter("orders")
        .flowState(inMemoryFlowState().flowIdFromHeader("X-Tenant"))
        .stub(onGet("/orders").willReturn(okJson("[]")).inSpace("acme")));
```

## Flow-scoped scenario state

Scenarios are per-flow too, so two tests can drive the same state machine to different points
concurrently:

```java
imp.scenarios().setState("checkout", "PAID", "acme");   // acme's scenario only
imp.scenarios().setState("checkout", "NEW");            // the default flow
List<Scenarios.State> acmeStates = imp.scenarios().list("acme");
```

The three-argument `setState(name, state, flowId)` and `list(flowId)` are the flow-scoped forms; the
shorter overloads operate on the default flow.

## Key/value flow state

`imposter.flowState(flowId)` reaches the same per-flow store the engine's scripts read and write:

```java
FlowState state = imp.flowState("acme");
state.put("cartId", "c-123");
Optional<JsonValue> cartId = state.get("cartId");
state.delete("cartId");
```

`get` returns the stored JSON value itself, the same on every transport.

### Writing flow state from a response

A stub can update flow state itself, without a script, by declaring writes on its `is` response
(`_rift.stateOps`, rift ≥ 0.18.0):

```java
Imposter cart = rift.create(imposter("cart")
        .stub(onGet("/cart/add").willReturn(ok()
                .templated()
                .withTextBody("items before this one: {{ state.items }}")
                .incrementState("items")                             // add 1 (or incrementState(key, by))
                .setState("lastSku", "{{ request.query.sku }}")))    // value rendered as a template
        .stub(onPost("/cart/clear").willReturn(ok().clearFlowState()))); // or deleteState(key)
```

- The writes run **after** the response is built — after templating and behaviors — in the order
  they are chained. So a templated body reading `{{ state.items }}` sees the value from *before*
  this response's writes.
- They apply to the request's flow: the header named by `flowIdFromHeader`, or, without one, a
  single flow shared by every caller and keyed by the imposter's port.
- An imposter **created** with such a stub and no `flowState(...)` gets an in-memory store. The
  engine decides this when the imposter is created, so to add stubs with writes later, declare
  `flowState(inMemoryFlowState())` up front.
- They run on `is` responses only, and not when a `_rift` fault fires. An intercept `serve` rule
  rejects them.
- `create` and `replaceAll` refuse them on an engine older than 0.18.0, which would drop them
  silently.

## Cursor reads over the journal

Polling `recorded()` re-reads everything every time. The cursor API reads only what is new:

```java
RecordedPage page = imp.recordedPage();          // baseline: everything retained, plus a cursor
long cursor = page.nextIndex().orElse(0);

RecordedPage next = imp.recordedSince(cursor);   // only what arrived since
cursor = next.nextIndex().orElse(cursor);
```

`nextIndex()` is **opaque** — pass it back verbatim and never derive one from a list offset. An empty
`nextIndex()` means the engine cannot offer a cursor; keep the one you hold and poll again. A present
`0` is a real cursor meaning "nothing recorded yet", not an absent one.

`truncated()` says the retention cap evicted entries you had not seen — a hole in *your* view, so
re-baseline with `recordedPage()`. Deleting traffic you asked to delete is not a hole, so a cursor
held across a `clearRecorded(...)` is never flagged.

Spaces get the same cursor, pre-scoped to their flow:

```java
Space acme = imp.space("acme");
RecordedPage acmePage = acme.recordedPage();
RecordedPage acmeNext  = acme.recordedSince(acmePage.nextIndex().orElse(0));
```

The index is still the **imposter's** journal index, so a space cursor and an imposter cursor are the
same number and interchangeable. A space tail's cursor therefore advances even while only *other*
flows are recording — the engine moves it past entries the filter rejected, so a filtered tail never
re-scans a range it has already judged, and an empty page with a moved cursor is normal.

## Filtering with `MatchClause`

`MatchClause` filters a journal read **engine-side**, so the page costs only what it returns:

```java
imp.recordedPage(MatchClause.method("POST"), MatchClause.path("/orders"));
imp.recordedSince(cursor, MatchClause.header("X-Tenant", "acme"));
imp.clearRecorded(MatchClause.flowId("acme"));      // scoped delete
```

| Clause | Matches | Notes |
|---|---|---|
| `MatchClause.header(name, value)` | a request header | name case-insensitive, value exact |
| `MatchClause.flowId(id)` | the engine-resolved flow id | what a `Space` injects for you |
| `MatchClause.method(verb)` | the request method | **case-sensitive** — `method("get")` does not match a `GET` |
| `MatchClause.path(path)` | the bare request path | exact, still percent-encoded, and **without** the query string |

`method` and `path` require a rift engine **≥ 0.15.0**. Against an older engine the clause is rejected
with a `400` surfaced as `InvalidDefinition` — it never quietly returns an unfiltered page, because a
filter that silently widened would mix in exactly the traffic it was meant to separate.

Clauses **AND** together, and they are applied *after* the cursor cut. The grammar is closed on
purpose: only the four forms above are constructible, so a clause that the engine would reject — or
one that could never match, like a `path` carrying a query string — fails at construction rather than
becoming a filter that quietly returns nothing forever.

Filtered reads need server-side filtering, and every transport can reach it — the in-process embedded
transport delegates them to its own admin server rather than evaluating clauses client-side. A
transport that could do neither would refuse with `UnsupportedOperationException` rather than answer
with the entries you asked to exclude; widening a filter is never the fallback.

## Flow ids are never blank

Every flow-scoped call rejects a blank flow id with `IllegalArgumentException` — `space("")`,
`flowState("  ")`, `scenarios().setState(name, state, "")`, `scenarios().list("")`,
`MatchClause.flowId("")` and `.inSpace("")` alike.

A blank id is not "the default flow". It is a distinct, silently-wrong partition: stubs written there
never match real traffic, reads come back mysteriously empty, and on the flow-state delete path it
would target the wrong partition destructively. Because the failure is quiet and looks like "the
feature doesn't work", it is rejected at the facade boundary rather than sent to the wire.

## See also

- [Event stream](events.md) — push notification of the same recorded requests, with `flowId` on each event.
- [API design](design/sdk-api.md) — the full reference for `Space`, `MatchClause` and the cursor contract.
