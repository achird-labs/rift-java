# Proxy record/replay (`Recording`)

`imposter.startRecording(origin)` fronts an imposter with a proxy to a real upstream, records the
traffic that flows through it, then swaps the proxy for the recorded stubs — so the imposter serves
the captured responses with the upstream gone. It's sugar over the engine's proxy record/replay.

```java
Imposter proxy = rift.create(imposter("users").record());

try (Recording recording = proxy.startRecording("https://api.real-service.com")) {
    // drive your SUT through proxy.uri() — requests are proxied to the origin and recorded
    List<Stub> recorded = recording.stop();   // swaps the proxy for the recorded stubs
}

// proxy now serves the recorded responses; the origin is no longer touched
```

## `RecordSpec`

`startRecording(origin, spec)` tunes what is captured:

```java
RecordSpec spec = RecordSpec.builder()
        .mode(RecordMode.ONCE)                                  // ONCE (default) | ALWAYS | TRANSPARENT
        .generateBy(RequestField.METHOD, RequestField.PATH)     // which fields become match predicates
        .addWaitBehavior(true)                                  // capture realistic latency (default true)
        .ignoreHeaders("Date", "X-Request-Id")                  // strip volatile headers from predicates
        .build();
```

| Setting | Default | Meaning |
|---|---|---|
| `mode` | `ONCE` | `ONCE` = proxy first request per predicate, then replay (`proxyOnce`); `ALWAYS` = always proxy, recording each unique request (`proxyAlways`); `TRANSPARENT` = forward without recording (`proxyTransparent`). |
| `generateBy(RequestField...)` | `METHOD, PATH` | The request fields turned into the recorded stub's match predicates. |
| `addWaitBehavior(boolean)` | `true` | Capture the upstream's observed latency as a wait behavior. |
| `ignoreHeaders(String...)` | none | When `generateBy` includes `HEADERS`, drop these (case-insensitive) volatile headers from the generated predicates so recordings are stable run-to-run. |

## `Recording`

`Recording` is `AutoCloseable`, so a try-with-resources block stops it on exit.

| Method | Effect |
|---|---|
| `List<Stub> stop()` | Fetch the recorded stubs (proxy removed), swap them onto the imposter, and return them. Idempotent. |
| `List<Stub> snapshot()` | Return what's recorded so far; the proxy stays in place (keep recording). |
| `void persist(Path file)` | `stop()`, then write the replayable imposter JSON to `file`. |
| `void close()` | `stop()`. |

`persist(file)` writes the engine's **replayable imposter definition** — the same format loadable by
`rift --configfile`, rift-node, and rift-scala — so a captured golden file is portable across SDKs.

To replay it from rift-java, read the file yourself and hand the JSON to the engine:

```java
Imposter replayed = rift.create(Files.readString(file));
```

rift-java has no `configFile` option of its own: the engine's `--configfile` (and its `noParse`
switch) is for the CLI, where the file may carry EJS templates. Config the SDK loads is never
run through EJS, so a literal `<%` in a recorded body is safe as-is.
