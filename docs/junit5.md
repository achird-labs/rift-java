# JUnit 5 integration (`rift-java-junit5`)

`@RiftTest` starts one `Rift` engine per test class, creates any `@RiftImposter`-declared
imposters against it, and injects `@InjectRift`/`@InjectImposter` fields and parameters. It's a
plain `ExtendWith(RiftTestExtension.class)` meta-annotation — no base class required.

```java
import io.github.achirdlabs.rift.Imposter;
import io.github.achirdlabs.rift.dsl.ImposterSpec;
import io.github.achirdlabs.rift.junit5.*;
import static io.github.achirdlabs.rift.dsl.RiftDsl.*;

@RiftTest
class UserClientTest {

    @RiftImposter
    static ImposterSpec usersSpec = imposter("users").record()
            .stub(onGet("/api/users/1").willReturn(okJson("{\"id\":1}")));

    @InjectImposter("users")
    Imposter users;

    @Test
    void fetchesUser(@InjectRift Rift rift) {
        // point your SUT at users.uri(), then:
        users.verify(onGet("/api/users/1"), times(1));
    }
}
```

## `@RiftTest`

| Attribute | Default | Meaning |
|---|---|---|
| `transport()` | `Transport.AUTO` | How the engine is obtained — see below. |
| `adminUri()` | `""` | Admin API URI for `Transport.CONNECT` (and `AUTO`, once set). Supports a `${property}` placeholder resolved against a JVM system property of the same name. |
| `reset()` | `Reset.PER_TEST` | When configured imposters are reset during the class run — see the table below. |
| `dumpRecordedOnFailure()` | `false` | On a failing test, publish each imposter's recorded requests to the JUnit report — see [Failure diagnostics](#failure-diagnostics-dumprecordedonfailure). |

`Transport` values:

- **`AUTO`** — prefers an embedded (in-process FFM) engine when `rift-java-embedded`/the native
  library is available, otherwise spawns and manages a `rift` binary for the class lifetime.
- **`CONNECT`** — connects to an already-running admin API at `adminUri()`. Requires a non-blank
  resolved `adminUri`; throws `IllegalStateException` otherwise.
- **`SPAWN`** — always spawns and manages a `rift` binary for the class lifetime.
- **`EMBEDDED`** — always runs the engine in-process (requires JDK 22+ and `rift-java-embedded`).

The `${property}` placeholder is useful when the admin URI is only known at runtime — e.g. a
`@BeforeAll`/static initializer that starts a fake or real admin server and publishes its port:

```java
@RiftTest(transport = Transport.CONNECT, adminUri = "${my.admin.uri}")
class SomeTest {
    static { System.setProperty("my.admin.uri", startFakeAdmin().toString()); }
}
```

(JUnit only guarantees a test class's static initializer has run once the class is loaded, which
`RiftTestExtension` forces before resolving `adminUri()`.)

## `@RiftImposter` static specs

`@RiftImposter` marks a `static ImposterSpec` field; `RiftTestExtension` builds and creates one
imposter per such field before any test method runs, keyed by the spec's own declared name (the
name passed to `RiftDsl.imposter(String)`, read client-side — no engine round-trip required to
echo it back). Declaring two fields with the same name is a class-level configuration error
(`IllegalStateException` at `beforeAll`), as is a null or unnamed spec.

```java
@RiftImposter
static ImposterSpec usersSpec = imposter("users").record()
        .stub(onGet("/api/users/1").willReturn(okJson("{\"id\":1}")));

@RiftImposter
static ImposterSpec paymentsSpec = imposter("payments").record();
```

## `@InjectImposter` / `@InjectRift`

Both work on instance fields and on test-method parameters:

```java
@InjectImposter("users") Imposter users;   // field

@Test
void aTest(@InjectRift Rift rift, @InjectImposter("payments") Imposter payments) {
    ...
}
```

`@InjectImposter("name")` looks up the imposter created from the `@RiftImposter` field with that
declared name; an unknown name fails parameter/field resolution. `@InjectRift` hands back the
class's single shared `Rift` client.

## Reset semantics

| `Reset` value | When it runs | What it clears |
|---|---|---|
| `PER_TEST` (default) | Before every test method | Recorded requests, scenario state, and proxy responses on every `@RiftImposter`-configured imposter. |
| `PER_CLASS` | Once, before the first test method | Same as above, but only once for the whole class. |
| `NONE` | Never (automatically) | Nothing — the test manages imposter state itself, e.g. across intentionally stateful test methods. |

Reset only touches imposters declared via `@RiftImposter`; imposters created ad hoc inside a test
method (`rift.create(...)`) are the test's own responsibility to clean up.

```java
@RiftTest(reset = Reset.PER_CLASS)
class SharedStateTest {
    @RiftImposter
    static ImposterSpec usersSpec = imposter("users").record();

    @InjectImposter("users") Imposter users;

    @Test
    @Order(1)
    void firstCallIsRecorded() {
        // ... drive the SUT ...
        users.verify(onGet("/api/users/1"), times(1));
    }

    @Test
    @Order(2)
    void recordingPersistsAcrossMethods() {
        // still 1, not reset between methods under PER_CLASS
        users.verify(onGet("/api/users/1"), times(1));
    }
}
```

## Parallel execution

`RiftTestExtension` starts exactly one engine per test class (in `beforeAll`) and tears it down
in `afterAll`. That makes test **classes** safe to run in parallel with each other under JUnit
5's parallel execution — each gets its own engine, its own imposters, and its own admin
connection, with no shared mutable state between classes. Test **methods within the same class**
share that one engine and its `@RiftImposter` imposters, so:

- Don't enable method-level parallelism within a `@RiftTest` class unless your test methods only
  read/verify disjoint imposters or paths — concurrent methods hitting the same imposter's
  recorded requests will race against each other's traffic and against `PER_TEST` resets.
- Prefer class-level parallelism (JUnit 5's default granularity) and keep `Reset.PER_TEST` (the
  default) so each method starts from a clean slate.

## Tier-2: programmatic `@RegisterExtension` builder

When transport or imposters are computed at runtime (or you want to avoid the class annotation),
build the extension programmatically and register it as a `static` field with
`@RegisterExtension`. It supports exactly the same field/parameter injection and reset semantics
as the annotation:

```java
class UserClientTest {
    static final FakeAdmin admin = FakeAdmin.start();

    @RegisterExtension
    static final RiftTestExtension rift = RiftTestExtension.newInstance()
            .transport(Transport.CONNECT)
            .adminUri(admin.uri().toString())      // computed at runtime
            .imposter(imposter("users").record())  // repeatable; one per call
            .reset(Reset.PER_TEST)
            .dumpRecordedOnFailure(true)
            .build();

    @Test
    void fetchesUser(@InjectRift Rift r, @InjectImposter("users") Imposter users) { ... }
}
```

`newInstance()` defaults match the annotation (`Transport.AUTO`, `Reset.PER_TEST`,
`dumpRecordedOnFailure=false`). `adminUri(...)` also honours the `${property}` placeholder.

## Configuring the engine

There are two ways to configure the engine the extension constructs, matching the two tiers:

**Annotation tier — launch properties.** `@RiftTest` stays deliberately small; point the embedded
engine at a specific library and relax the version preflight from the launch command (no code
change), which is exactly what a dev/vendored engine needs:

```
-Drift.ffi.lib=native/librift_ffi.dylib -Drift.versionCheck=off
```

(`rift.ffi.lib`/`RIFT_FFI_LIB` set the library path; `rift.versionCheck`/`RIFT_VERSION_CHECK` accept
`off`/`warn`/`fail`.)

**Builder tier — options objects.** For full programmatic control, hand the builder the options for
the transport it will use. Each is validated against the selected transport (`AUTO` may resolve to
embedded or spawn, never connect):

```java
@RegisterExtension
static final RiftTestExtension rift = RiftTestExtension.newInstance()
        .transport(Transport.EMBEDDED)
        .embeddedOptions(EmbeddedOptions.builder()
                .libraryPath(Path.of("native/librift_ffi.dylib"))
                .versionCheck(VersionCheck.OFF)
                .build())
        .build();
// .connectOptions(ConnectOptions.builder(uri)...) for CONNECT (carries its own adminUri)
// .spawnOptions(SpawnOptions.builder()...)        for SPAWN / AUTO
```

A mismatched pairing (e.g. `transport(SPAWN)` with `embeddedOptions(...)`) fails fast at `build()`.

## Failure diagnostics (`dumpRecordedOnFailure`)

`@RiftTest(dumpRecordedOnFailure = true)` (or `.dumpRecordedOnFailure(true)` on the builder)
makes a **failing** test publish each imposter's recorded requests to the JUnit report as an
entry keyed `rift.recorded.<name>`, one `METHOD path` per line (followed by `→ status in N ms` on
rift ≥ 0.18.0, which records the outcome), capped at 20 requests per
imposter (with a `… N more` note when truncated). Nothing is published for a passing test or when
the flag is off. It's a fast way to see what traffic actually reached a mock when a test fails:

```java
@RiftTest(dumpRecordedOnFailure = true)
class OrderFlowTest {
    @RiftImposter static ImposterSpec users = imposter("users").record();

    @Test
    void placesOrder(@InjectImposter("users") Imposter users) {
        // ... drive the SUT ...
        assertEquals(200, response.status());   // on failure, the report shows what hit `users`
    }
}
```

Most IDEs and the Surefire/Gradle reports surface report entries next to the failed test; only
imposters that actually recorded a request produce an entry.

## Golden files (`@RiftGolden`)

`@RiftGolden` records a real upstream once and replays it forever after — Hoverfly-style
auto-capture with zero mode-switching. Add it to a `@RiftTest` class alongside the imposter to
record:

```java
@RiftTest
@RiftGolden(origin = "https://api.real-service.com",
            file = "src/test/resources/golden/users-api.json")
class UsersGoldenTest {
    @RiftImposter
    static ImposterSpec users = imposter("users").record();

    @InjectImposter("users") Imposter usersMock;

    @Test void fetchesUser() { /* drive your SUT through usersMock.uri() */ }
}
```

- **file missing → CAPTURE**: the imposter proxies to `origin`, records the traffic, and the
  recorded stubs are written to `file` when the class finishes.
- **file present → REPLAY**: the recorded stubs are loaded from `file` and served directly — no
  network, so CI (which has the committed golden file) never touches the origin.
- `-Drift.golden=recapture` forces CAPTURE even when the file exists (refresh the recording).

Commit the golden file. The persisted format is the engine's replayable imposter JSON — portable
across the rift SDKs and loadable by `rift --configfile`. `@RiftGolden` targets the sole
`@RiftImposter` on the class, or the one named by `@RiftGolden(imposter = "...")` when there is more
than one. It builds on the core [`Recording` API](recording.md).

## Intercept (`@RiftIntercept`)

`@RiftIntercept` starts a TLS-MITM intercept listener for the test class. The listener and its CA
live for the class; only its rules reset per test (per the `@RiftTest` `Reset` policy). Declare rules
with a `@RiftInterceptRules` static method and get the live handle with `@InjectIntercept`:

```java
@RiftTest(transport = Transport.EMBEDDED)
@RiftIntercept(port = 18888, caCert = "certs/ca.pem", caKey = "certs/ca-key.pem",
               exportTruststore = "target/rift-truststore.jks", exportFormat = TruststoreFormat.JKS)
class FeatureFlagTest {

    @RiftImposter static ImposterSpec partner = imposter("partner").record()
            .stub(onGet("/api/quote").willReturn(okJson("{\"px\":1}")));

    @RiftInterceptRules
    static void rules(Intercept intercept, @InjectImposter("partner") Imposter partner) {
        intercept.serve("cdn.optimizely.com", okJson(DATAFILE));
        intercept.redirectTo("api.partner.com", partner);
    }

    @InjectIntercept Intercept intercept;

    @Test
    void resolvesFlag() {
        HttpClient sut = HttpClient.newBuilder()
                .sslContext(intercept.trust().sslContext())   // or sslContextWithSystemCAs()
                .proxy(intercept.proxySelector())
                .build();
        // … drive the SUT; its intercepted HTTPS calls are served by the rules above …
    }
}
```

- `@RiftIntercept` attributes: `port`/`host` (bind; host is an IP literal), `caCert`/`caKey` (committed
  CA, else ephemeral; `${property}` placeholders resolved), and `exportTruststore`/`exportFormat`/
  `exportPassword` (written during `beforeAll`, for a containerized SUT to mount — see
  [docs/intercept.md](intercept.md#sharing-one-ca-with-a-containerized-sut)).
- `@RiftInterceptRules` — a `static void` method invoked once the listener starts and re-invoked after
  each per-test rules reset. Its parameters resolve like test parameters: `Intercept`, `@InjectRift
  Rift`, `@InjectImposter Imposter`.
- `@InjectIntercept` — injects the live `Intercept` into a field or test parameter. Present without
  `@RiftIntercept` on the class → a clear resolution error.

The listener and CA are stable across a class's tests, so trust material handed to a SUT in one test
stays valid in the next; only the rules reset.
