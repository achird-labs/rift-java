# Clock, seed & WebAssembly

Rift 0.25.0 gives every imposter its own clock and a seedable random stream, and runs
WebAssembly components as `_rift.script`s. This page covers the Java surface for all three. Each
needs a rift engine **0.25.0 or newer**: on an older engine `Rift.create` and `replaceAll` refuse
the definition with `InvalidDefinition` before anything is sent, since an older engine would ignore
`seed`, `rngKey` and `clock` without a word. With `versionCheck(OFF)` it is sent unchecked.

## The imposter clock

An imposter's clock is wall time until a test freezes or steps it. The built-in flow store's TTLs,
a wasm component's `wasi:clocks`, and (from 0.25.0, rift#1756) the clocks scripts read all follow it:

| Read | Follows the imposter clock |
|:-----|:---------------------------|
| JavaScript `Date.now()`, `new Date()` | in a `_rift.script`, and in Mountebank `inject`, `decorate` and `wait` functions |
| Rhai `timestamp_header()` | the RFC 1123 date of the imposter clock |
| Template `{{now}}`, `{{NOW}}`, `{{DAYS±N}}` | yes |
| A wasm component's `wasi:clocks` | yes |
| `Last-Modified`, the journal's `timestamp`, proxy latency, a guest's `sleep` | no — real time |

```java
Imposter cache = rift.create(imposter("cache")
        .stub(onGet("/now").willReturn(script(Script.js(
                "function respond(ctx) { return http(200, String(Date.now())); }")))));

ImposterClock clock = cache.clock();
clock.set(Instant.parse("2026-01-01T00:00:00Z"));   // frozen there
clock.advance(Duration.ofHours(24));                // a 24-hour TTL expires, now
ImposterClock.Reading reading = clock.get();        // mode FROZEN, now 2026-01-02T00:00:00Z
clock.reset();                                      // back on wall time
```

`advance` never moves a clock back (a negative `Duration` is an `IllegalArgumentException`); freeze
it at an earlier instant with `set` instead. The clock is runtime state: never persisted, and back
on wall time after a restart or a recreate.

### A request's own instant

`clock(ClockSource.HEADER)` lets each request name its instant in `X-Rift-Now` (milliseconds since
the epoch), ahead of the imposter clock — parallel tests then need no admin calls at all. A
malformed header is refused with `400`.

```java
rift.create(imposter("billing").clock(ClockSource.HEADER) /* … stubs … */);
// GET /invoice  with  X-Rift-Now: 1767225600000
```

## Seeded randomness

`seed(long)` seeds the imposter's random stream. `_rift.fault` rolls, the template `randomInt` and
`uuid`, JavaScript `Math.random()` and a component's `wasi:random` all draw from it, from the seed
again at every create, recreate and restart. With a frozen clock as well, a sequence of requests
replays byte for byte.

```java
rift.create(imposter("flaky").seed(42) /* … */);
```

The engine reads an **unsigned** 64-bit seed. Java has no such primitive, so `seed` takes its bit
pattern: a negative `long` is a seed of 2<sup>63</sup> or more, and it is written unsigned
(`seed(-1L)` sends `18446744073709551615`). Spell a large one as
`seed(Long.parseUnsignedLong("18446744073709551615"))`. Reading a definition back,
`RiftScriptEngineConfig.unsignedSeed()` returns the bit pattern, or empty for a value the engine
admitted but does not use as a seed (it runs unseeded and logs it).

Concurrent requests draw from one stream in whatever order they arrive. `rngKey(...)` gives each
request a stream of its own, picked by `header:<name>`, `query:<name>`, `method`, `path` or
`body:<RFC 6901 pointer>`; it needs `seed`, and `build()` refuses it without one:

```java
imposter("payments").seed(42).rngKey("header:X-Rift-Rng-Key")
// each caller sends X-Rift-Rng-Key: <correlation id>-<attempt>
```

## WebAssembly components

A component built for `wasm32-wasip2` against the `rift:mock` world (or a standard
`wasi:http/incoming-handler`) answers requests in a sandbox. Three ways to name one:

```java
Script.wasmFile("twins/counter.wasm")        // read by the engine; under --scripts-dir on the admin API
Script.wasm(Files.readAllBytes(path))        // the bytes, sent as base64 `code`: no file on the engine's host
Script.component(info.reference())           // one uploaded to the engine's registry, by its sha256
```

and three settings, for a wasm script only (any other script throws `IllegalStateException`):

```java
Script.component(sha)
        .export(WasmExport.HTTP)                 // wasi:http/incoming-handler; RIFT is rift:mock/handler
        .knobs(JsonValue.parse("{\"rate\": 0.2}"))  // the component's configuration
        .instance(WasmInstanceScope.IMPOSTER)    // keep one instance: its memory is the twin's state
```

Per-call limits go on the imposter: `wasmLimits(WasmLimits.DEFAULTS.withFuel(1_000_000).withMaxMemoryMiB(16))`.
The deadline is `scriptEngine(engine, timeout)`'s, as for every engine. A wasm script needs no
`allowInjection`.

### The component registry

`Rift.components()` uploads a component once, so any stub runs it without a file on the engine's host:

```java
ComponentInfo info = rift.components().upload(Path.of("target/wasm32-wasip2/release/twin.wasm"));
rift.create(imposter("twin").stub(onGet("/orders").willReturn(script(Script.component(info.reference())))));

rift.components().list();
rift.components().get(info.sha256());    // Optional.empty() when the engine does not hold it
rift.components().delete(info.sha256()); // EngineError 409 while an imposter references it
```

Uploading bytes the engine already holds answers the same component. A component the engine refuses
(not a component, an import Rift does not link, under `strictWasm` one not on the list) is an
`InvalidDefinition` naming the problem.

### Kept instances and their state

Under `instance(WasmInstanceScope.IMPOSTER)` the imposter keeps one instance per component, export
and knobs:

```java
List<WasmInstanceInfo> kept = twin.wasmInstances();   // id, sha256, calls, memoryBytes, …
String state = twin.wasmSnapshot();                   // the guest's rift:mock/introspect JSON text
String one = twin.wasmSnapshot(kept.get(0).id());     // when several are live
```

A snapshot is not a call: it does not count, cannot change the state, and never instantiates. A
`409 EngineError` means nothing is instantiated yet; a `404` one that the component has no
`rift:mock/introspect` export.

## Engine options

| | Spawn | Embedded | Testcontainers |
|:--|:--|:--|:--|
| Refuse components | `SpawnOptions.builder().allowWasm(false)` | `EmbeddedOptions.builder().allowWasm(false)` | `withAllowWasm(false)` |
| Trusted components only | `.strictWasm(Path)` | `.strictWasm(Path)` | `withStrictWasm(Path)` |

`allowWasm` is on by default in the engine. `strictWasm` names a file of lowercase SHA-256 hashes,
one per line. Each is checked against the engine version (spawn, container tag) or its advertised
`serveOptions` (embedded).

### The embedded engine needs the `-wasm` library

The plain `librift_ffi` has **no** wasm engine. Its `-wasm` flavour does, at about 12–18 MB more,
shipped as its own `rift-java-natives` classifier:

```xml
<dependency>
  <groupId>io.github.achird-labs</groupId>
  <artifactId>rift-java-natives</artifactId>
  <classifier>darwin-aarch64-wasm</classifier>
</dependency>
```

```java
Rift rift = Rift.embedded(EmbeddedOptions.builder().wasmLibrary(true).build());
```

`wasmLibrary(true)` loads `native/<platform>-wasm/` from the classpath, and fails `Rift.embedded`
with `EngineUnavailable` if the loaded library does not report the `wasm` feature (for example a
plain library named through `RIFT_FFI_LIB`). The clock and seed work on either library; uploading
or running a component needs the flavour (the plain one refuses it with `400`). The spawned binary
and the Docker image carry wasm.
