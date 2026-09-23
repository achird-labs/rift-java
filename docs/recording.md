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

## Recording an origin behind a private CA

The engine verifies an origin's certificate against the OS trust store. An origin issued by a
private or corporate CA fails with `invalid peer certificate: UnknownIssuer` unless the engine is
told to trust that CA. Set `upstreamTrust` on the options for the engine you start (rift ≥ 0.18.0):

```java
// Embedded: a PEM file, or the same certificate inline
Rift rift = Rift.embedded(EmbeddedOptions.builder()
        .upstreamTrust(new UpstreamTrust.CaFile(Path.of("/etc/pki/corp-ca.pem")))
        .build());

// Spawned: a PEM file (the rift CLI has no inline form, so UpstreamTrust.CaPem is rejected)
Rift rift = Rift.spawn(SpawnOptions.builder()
        .upstreamTrust(new UpstreamTrust.CaFile(Path.of("/etc/pki/corp-ca.pem")))
        .build());

// Testcontainers: a PEM file or an inline PEM, written into the container by the transport
RiftContainer rift = new RiftContainer()
        .withUpstreamTrust(new UpstreamTrust.CaPem(corpCaPem));
```

| `UpstreamTrust` | Engine option | Transports |
|---|---|---|
| `CaFile(Path)` | `upstreamCaFile` / `--upstream-ca-file` (`RIFT_UPSTREAM_CA_FILE`) | embedded, spawn, testcontainers |
| `CaPem(String)` | `upstreamCaPem`; in a container, a file the transport writes | embedded, testcontainers |
| `SkipVerify()` | `upstreamTlsSkipVerify` / `--upstream-tls-skip-verify` (`RIFT_UPSTREAM_TLS_SKIP_VERIFY`) | embedded, spawn, testcontainers |

- **The CA is appended to the OS trust store**, so public origins keep working. Don't reach for
  `SSL_CERT_FILE` instead: the engine honours it, but it *replaces* the trust store, so pointing it
  at a lone private CA quietly breaks every public origin.
- **`SkipVerify` is for development only.** A recording proxy that accepts any certificate will
  faithfully record a man-in-the-middle's traffic. The SDK logs a warning whenever it is used.
- **One engine, one policy.** Trust is process-wide for that engine, and it covers `proxy` stubs and
  the intercept listener's origin leg.
- **Older engines are refused, not ignored.** An embedded engine must advertise the option in its
  `serveOptions` (`Rift.info().serveOptions()`), or `Rift.embedded` fails with `EngineUnavailable`.
  A spawned engine is checked against `SpawnOptions.version` when the options are built, and a
  container against its image tag when `withUpstreamTrust` is called (a tag that is not a version,
  such as `latest`, cannot be checked).
- **In a container, the CA is read when the container starts**, copied to
  `/etc/rift/upstream-ca.pem`, and named to the engine through its environment; an unreadable
  `CaFile` fails the start.
- **A connected engine** (`Rift.connect`) is configured by whoever started it: pass
  `--upstream-ca-file` to `rift` there.
