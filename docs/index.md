# rift-java

Official Java SDK for [Rift](https://github.com/achird-labs/rift) — a high-performance,
Mountebank-compatible HTTP/HTTPS mock server written in Rust.

One client, three transports — **embedded** (in-process, no Docker), **connect** (any running Rift
admin endpoint), and **spawn** (a managed `rift` binary) — with the full engine surface on each:
stubs, predicates, responses, response cycling, behaviors, proxy record/playback, fault injection,
stateful scenarios, spaces/flow-state, request verification, a server-side event stream, and
TLS-MITM intercept.

```java
import static io.github.achirdlabs.rift.dsl.RiftDsl.*;

try (Rift rift = Rift.embedded()) {
    Imposter users = rift.create(
        imposter("users").record()
            .stub(onGet("/api/users/1").willReturn(okJson("{\"id\":1}"))));

    // point your SUT at users.uri(), then:
    users.verify(onGet("/api/users/1"), times(1));
}
```

## Guides

- **[Intercept (TLS-MITM)](intercept.md)** — terminate TLS for real hostnames and serve/forward/redirect.
- **[JUnit 5](junit5.md)** — `@RiftTest`, imposter and intercept injection, reset semantics.
- **[Testcontainers](testcontainers.md)** — `RiftContainer` for a Dockerized engine.
- **[Record & replay](recording.md)** — capture live traffic into replayable stubs.
- **[Spaces & flow state](spaces.md)** — per-flow stub overlays, flow-scoped reads, and flow state,
  including declarative writes from a response.
- **[Event stream](events.md)** — follow requests and imposter changes as they happen.

## Reference

- **[API design (v1)](design/sdk-api.md)** — the pinned public API surface.

See the [README on GitHub](https://github.com/achird-labs/rift-java#installation) for installation
(Maven / Gradle, release and snapshot channels) and module layout.
