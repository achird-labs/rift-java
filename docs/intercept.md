# Intercept (TLS-MITM)

`Intercept` turns a rift engine into a forward proxy that terminates TLS for arbitrary
hostnames — answering some requests inline, forwarding others to a real backend or to one of
this SDK's own imposters — without changing anything about the traffic's actual destination
host name. It's obtained from a running `Rift` client:

```java
Intercept intercept = rift.intercept();               // default options
Intercept intercept = rift.intercept(InterceptOptions.builder().port(8443).build());
```

One intercept listener runs per engine at a time. A second `rift.intercept()` call on the same
`Rift` while one is open throws `IllegalStateException` before touching the engine; a different
`Rift` handle to the same engine is refused by the engine itself (HTTP 409, surfaced as an
`EngineError`). `intercept.close()` stops the listener and frees the engine for another
`rift.intercept(...)` — with a different CA, say. A call that fails to start (e.g. a bad committed
CA — see below) leaves intercept available to retry; it does not poison the engine.

Works on every transport: embedded starts the listener over FFM, and a connected/spawned engine
starts it over the admin API (rift ≥ 0.13.3). The bind `host` must be an **IP literal** (e.g.
`127.0.0.1` or `0.0.0.0`) — a hostname is rejected client-side.

### Where the listener is reached

The engine reports the address it **bound** — `intercept.engineAddress()` keeps it. For an engine
the SDK runs itself (spawn, embedded) that is also where the client dials. For a **connected**
engine it is an address on the engine's machine: `address()`, `uri()` and `proxySelector()` keep
it, except that a wildcard bind (`0.0.0.0`, `[::]`) is dialled at the admin host. Where the
engine's ports are remapped — a container, a port-forward — set
`ConnectOptions.Builder.interceptAddress(port -> …)`; `RiftContainer.client()` sets it for you.

- A runtime start binds `127.0.0.1` unless told otherwise, which answers only on the engine's own
  machine. For an engine in a container or on another host, bind its interface:
  `InterceptOptions.builder().host("0.0.0.0")`. The SDK logs a warning when a connected engine
  reports a loopback bind.
- The mapping is asked about the requested port **before** the listener starts, so a mapping that
  cannot reach it (an unexposed container port, or port `0`) refuses with nothing started. Should it
  fail *after* the start, the listener is stopped and the error names it, so a retry can start
  another; only if that stop fails too does the listener stay, and a retry is refused.
- `interceptAddress` is separate from the imposter `hostResolver`: the listener is a `CONNECT`
  proxy, never an imposter's HTTP base URI, so a gateway-style imposter mapping does not apply.
- A listener the engine starts **at launch** (`--intercept-port`) binds the admin interface, which
  is why attaching to one (below) works without a bind host.

### Attaching to a listener started at engine launch

When the engine already started a listener at launch — via `--intercept-port` /
`RIFT_INTERCEPT_PORT`, e.g. a containerized engine with a fixed, exposed port — **attach** to it
instead of starting a new one:

```java
Intercept intercept = rift.intercept(InterceptOptions.attach(host, port));
```

`attach` binds to that endpoint (probing it, not starting), so `host`/`port` are the reachable
address — a mapped Docker port, say. An IPv6 host may be given bare (`::1`) or bracketed
(`[::1]`); `intercept.uri()` brackets it either way. With `rift-java-testcontainers` this is one call:

```java
RiftContainer rift = new RiftContainer().withInterceptPort(8888);   // engine launches the listener
// … after start …
Intercept intercept = rift.client().intercept(rift.interceptOptions());   // attach to the mapped port
```

To start the listener at runtime instead — with your own CA, say — expose the port without
launching a listener, then start it bound to the container's interface. The handle is mapped to
Docker's port:

```java
RiftContainer rift = new RiftContainer().withExposedInterceptPort(8889);  // exposed, not started
// … after start …
Intercept intercept = rift.client().intercept(InterceptOptions.builder()
        .host("0.0.0.0").port(8889).ca(certPem, keyPem).build());
```

## Rules: what happens to an intercepted host

Every rule is scoped to one hostname and decides what happens to requests the client sends to
that host through the intercept proxy:

```java
// Answer inline — the real host is never contacted.
intercept.serve("example.com", RiftDsl.status(418));

// Forward to a plain host:port on localhost, still terminating TLS at the intercept.
intercept.forward("payments.internal", "localhost:9443");

// Forward to one of this SDK's own imposters, by port.
intercept.redirectTo("api.partner.com", partnerImposter);
```

`serve`'s response is an `IsSpec` — the same response builder the DSL uses for stub responses
(`RiftDsl.ok()`, `okJson(...)`, `status(code)`, and so on) — but the engine's serve action is
narrower than that builder. It carries **only** a numeric status code, headers, and a text body.
A repeated header — `withHeader("Set-Cookie", "a=1", "b=2")` — goes out as one header line per
value on rift ≥ 0.18.0; an older engine would reject the rule, so the SDK refuses it there first
(unless the version check is off).

A response using anything beyond that is **rejected** with an `InvalidDefinition`, and the rule is
not registered:

- any behavior — `after`/`waitMs`, `decorate`, `repeat`, `copy`, `lookup`, `shellTransform`;
- any `_rift` extension — `templated()`, a script, a fault (`withLatencyFault`,
  `withErrorFault`, `withTcpFault`), or flow-state writes (`setState`, `incrementState`,
  `deleteState`, `clearFlowState`);
- a binary body (`withBinaryBody`), which the serve action can only carry as its base64 text.

```java
// Throws InvalidDefinition: the serve action has no fault concept, so without this the rule
// would register happily and then answer a plain 200.
intercept.serve("example.com", status(200).withTextBody("b").withTcpFault(Fault.CONNECTION_RESET_BY_PEER));

// Point at an imposter instead — it is a real stub, so every construct above works there.
intercept.redirectTo("example.com", faultyImposter);
```

`redirectTo` rides the same
wire-level `forward` action as `forward` itself, pointed at `imposter.port()`; a rule read back
from `intercept.rules()` therefore only ever reports `RuleKind.SERVE` or `RuleKind.FORWARD` — the
engine has no way to echo back that a `forward` action originated from `redirectTo`.

### Predicate-scoped rules and catch-alls

The three methods above key on the whole host. For finer control, `intercept.rule()` builds a rule
with **request predicates** (the same DSL stubs use — path, method, headers, body) and an **optional
host** — the engine's full `(host?, predicates, action)` rule shape:

```java
// Serve /health inline, but let everything else on this host fall through.
intercept.rule().host("example.com").when(onGet("/health")).serve(ok());

// A catch-all (no host) matching a path across every intercepted host.
intercept.rule().when(onPost("/api/**")).redirectTo(partnerImposter);

// Header/method scoping is just more predicates.
intercept.rule().host("payments.internal").when(onGet("/status")).forward("localhost:9443");
```

A rule read back via `intercept.rules()` exposes its predicates through `InterceptRule.predicates()`.

```java
List<InterceptRule> rules = intercept.rules();   // in the order they were added
intercept.clearRules();                          // removes every rule; the listener stays up
```

`intercept.close()` **stops** a listener it started (rift ≥ 0.13.0) — rules and CA go with it — and
frees the engine for another `rift.intercept(...)`. A restart without a supplied CA mints a **new**
CA, so take `trust()` from the new handle; a closed handle refuses `trust()` and rule calls rather
than hand out the old anchor. Closing an **attached** handle only clears its rules: the listener
belongs to whoever launched it (a container, `--intercept-port`). `close()` is idempotent, and a
failed stop leaves the handle open to retry.

## Trusting the intercept's CA

With no CA supplied, the engine mints a fresh ephemeral CA per listener and signs a leaf
certificate per intercepted hostname on the fly. A client has to be told to trust that CA, or
its TLS handshake fails. `Intercept.trust()` returns an `InterceptTrust` with three ways to do
that:

### 1. In-memory `SSLContext` — recommended for tests

No files, nothing written to disk — built entirely from the CA's PEM text in memory:

```java
SSLContext ssl = intercept.trust().sslContext();

HttpClient client = HttpClient.newBuilder()
        .sslContext(ssl)
        .proxy(intercept.proxySelector())
        .build();
```

This is the right default for test code: it needs no cleanup, leaves nothing behind, and works
identically whether the CA is ephemeral or committed.

`sslContext()` trusts **only** the intercept CA — right for a fully hermetic SUT. If the same SUT
also makes real HTTPS calls (say it exports telemetry over TLS while its mocked dependency is
intercepted), use `sslContextWithSystemCAs()` instead, which trusts the intercept CA **and** the
JVM's default trust anchors:

```java
SSLContext ssl = intercept.trust().sslContextWithSystemCAs();   // intercepted hosts + real HTTPS
```

### 2. Exported truststore — for a JVM-wide `-Djavax.net.ssl.trustStore`

When the client under test is out of your control (a third-party HTTP client, a subprocess, a
whole JVM configured only via system properties), export a truststore file instead:

```java
Path truststore = Path.of("build/tmp/intercept-trust.p12");
intercept.trust().exportTruststore(TruststoreFormat.PKCS12, "changeit", truststore);
```

```
-Djavax.net.ssl.trustStore=build/tmp/intercept-trust.p12
-Djavax.net.ssl.trustStorePassword=changeit
-Djavax.net.ssl.trustStoreType=PKCS12
```

`TruststoreFormat.JKS` is also available. A `null` password defaults to `"changeit"`. This is a
plain Java `KeyStore` write with no engine round-trip — same as `sslContext()`, just persisted.

### 3. A committed CA — shared across engine instances

By default every listener gets a fresh, ephemeral CA, which is fine for a single test process
but means two independently-started engines don't trust each other's intercepted traffic and a
CA can't be pinned once and reused across runs. Supply your own instead:

```java
InterceptOptions options = InterceptOptions.builder()
        .ca(Path.of("ca-cert.pem"), Path.of("ca-key.pem"))
        .build();
Intercept intercept = rift.intercept(options);
```

Both paths are required, or neither — a half-supplied pair is rejected client-side by
`InterceptOptions.Builder.ca(...)` (`IllegalArgumentException`) rather than surfacing later as a
confusing engine-side error. With a committed CA, every engine instance that loads it produces
byte-identical trust material, so one exported truststore (or one `sslContext()` derived from the
same PEM) works across all of them.

**CA material in memory (no caller-side file).** When the CA comes from a secret store rather than a
file, pass it directly — as PEM text, bytes, or a `KeyStore`:

```java
.ca(certPem, keyPem)            // String PEM (e.g. from an env var / Vault)
.ca(certBytes, keyBytes)        // byte[] PEM
.ca(keyStore, password)         // a JKS/PKCS12 holding the CA's cert + key
```

The PEM is shipped **inline** in the start request (rift ≥ 0.13.4), so no file is staged and the
engine needs nothing on its own filesystem — this works for a **containerized remote** engine
without mounting anything (unlike a file-path `ca(Path, Path)`, which the engine reads from its own
filesystem).

**Let rift generate a shareable CA.** Instead of supplying one, have the engine mint a fresh CA and
hand back its cert **and** key (rift ≥ 0.13.4) to persist and redistribute:

```java
Intercept intercept = rift.intercept(InterceptOptions.builder().generateCa().build());
Intercept.CaMaterial ca = intercept.caMaterial().orElseThrow();
Files.writeString(Path.of("ca-cert.pem"), ca.certPem());
Files.writeString(Path.of("ca-key.pem"), ca.keyPem());   // feed both to other services / a committed CA
```

### Sharing one CA with a containerized SUT

The in-memory `sslContext()` above assumes the client lives in the **same JVM** as the test. When
the system under test runs in its **own container** (or any process started before the test), it
must already trust the intercept CA at startup — so the ephemeral per-listener CA won't do. Commit
one CA, have the interceptor load it, and hand the container a truststore built from the same CA.
Both sides then share a single trust anchor and the TLS handshake between them succeeds.

**1. Generate the CA once** and commit `ca-cert.pem` + `ca-key.pem`:

```sh
openssl req -x509 -newkey rsa:2048 -nodes -keyout ca-key.pem -out ca-cert.pem \
  -days 3650 -subj "/CN=rift-intercept-ca"
```

**2. The interceptor loads that committed CA on a fixed, container-reachable port:**

```java
import static io.github.achirdlabs.rift.dsl.RiftDsl.*;

Intercept intercept = rift.intercept(InterceptOptions.builder()
        .host("0.0.0.0")               // reachable from the container, not just loopback
        .port(8888)                    // fixed — matches the container's proxyPort
        .ca(Path.of("intercept/ca-cert.pem"), Path.of("intercept/ca-key.pem"))
        .build());

intercept.serve("cdn.optimizely.com", okJson(datafileJson));

// Write the truststore the container will mount. Because it is derived from the committed CA, it
// grants exactly the same trust as sslContext() would in-JVM.
intercept.trust().exportTruststore(
        TruststoreFormat.JKS, "changeit", Path.of("intercept/rift-truststore.jks"));
```

> Because `-Djavax.net.ssl.trustStore` **replaces** the container's whole truststore, a SUT that also
> makes real HTTPS calls (telemetry, other live upstreams) would then fail those handshakes. Export
> with `exportTruststoreWithSystemCAs(...)` instead — same file, but it also carries the JVM's default
> trust anchors, so real endpoints keep working alongside the intercepted ones.

**3. The container trusts the CA and proxies its HTTPS through the host.** Under Docker the
interceptor is reachable at `host.docker.internal`; mount the truststore and set the JVM's trust +
proxy properties:

```yaml
services:
  sut:
    environment:
      JDK_JAVA_OPTIONS: >-
        -Dhttps.proxyHost=host.docker.internal
        -Dhttps.proxyPort=8888
        -Djavax.net.ssl.trustStore=/intercept-certs/rift-truststore.jks
        -Djavax.net.ssl.trustStorePassword=changeit
    volumes:
      - "./intercept:/intercept-certs"
```

Because the CA is committed, the container can be built and started **before** the test runs — it
trusts the anchor up front, and the interceptor signs each intercepted leaf with the matching key.
For a non-JVM SUT, point its own trust mechanism at `ca-cert.pem` instead (e.g.
`NODE_EXTRA_CA_CERTS`, `SSL_CERT_FILE`, or the OS trust store).

**With `rift-java-testcontainers`, the rift container itself can launch with the committed CA** —
no runtime start, and the SUT container trusts the same file:

```java
@Container
static final RiftContainer rift = new RiftContainer()
        .withInterceptPort(8888)                                                    // launched with the engine
        .withInterceptCa(Path.of("intercept/ca-cert.pem"), Path.of("intercept/ca-key.pem"));

Intercept intercept = rift.client().intercept(rift.interceptOptions());            // attach, carrying the CA
intercept.caMaterial();   // the committed pair, checked against the CA the listener serves
```

`interceptOptions()` attaches with `InterceptOptions.attach(host, port, caMaterial)`: the attach
compares the listener's served certificate with the given one and refuses a mismatch, rather than
letting the SUT fail its handshake later. Outside Testcontainers, pass your pair to
`InterceptOptions.attach(host, port, ca)` yourself for the same check and `caMaterial()`.

> **A file-path CA (`ca(Path, Path)`) is read by the engine from its own filesystem** — fine for the
> embedded engine, but a **separate rift container** reached via `Rift.connect(...)` resolves those
> paths *inside the container*, so mount the CA there. To avoid the mount entirely, pass the CA
> **in memory** (`ca(String/byte[]/KeyStore)`, shipped inline, rift ≥ 0.13.4) or have rift
> **generate** one (`generateCa()`) — neither needs anything on the engine's filesystem.

## Wiring an `HttpClient`

The two pieces a `java.net.http.HttpClient` needs are the trusted `SSLContext` and a
`ProxySelector` that routes traffic through the intercept listener — `Intercept` hands back both
directly:

```java
HttpClient client = HttpClient.newBuilder()
        .sslContext(intercept.trust().sslContext())
        .proxy(intercept.proxySelector())
        .build();

HttpResponse<String> response = client.send(
        HttpRequest.newBuilder(URI.create("https://example.com/")).GET().build(),
        HttpResponse.BodyHandlers.ofString());
```

`intercept.address()` (an `InetSocketAddress`) is also available directly, for clients configured
via `http.proxyHost`/`http.proxyPort`-style properties rather than a `ProxySelector`.

## Caveats

- **One intercept per engine.** If your test suite spins up more than one `Rift` engine (e.g. one
  per test class), each gets its own intercept and its own CA (unless you commit one — see
  above). Don't assume trust material from one engine's intercept applies to another's.
- **Corporate/CI proxies.** If the process under test already goes through an upstream
  HTTP(S) proxy (a corporate proxy, a CI sidecar), that proxy and the intercept's `ProxySelector`
  compete for the same client configuration — `HttpClient.newBuilder().proxy(...)` only accepts
  one. Either route through the intercept exclusively for the duration of the test, or configure
  the intercept's own upstream via `forward`/`redirectTo` rules so it, not the client, talks to
  the real proxy.
- **No engine-side echo of `REDIRECT`.** As noted above, rules fetched back via
  `intercept.rules()` can't distinguish a `redirectTo` rule from a plain `forward` rule — both
  report `RuleKind.FORWARD`. Track the association client-side if your test needs it.
