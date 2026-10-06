# Example: an Optimizely datafile over TLS intercept

A system under test that fetches a feature-flag datafile from a vendor CDN over HTTPS — here
`https://cdn.optimizely.com/datafiles/acct-1.json` — gets it from a local imposter instead, and the
test swaps the datafile mid-run (here revision 42 → 43; the same swap is how a test turns a flag on or
off). No real network call leaves the JVM.

```
SUT HttpClient ──CONNECT──▶ intercept listener ──TLS (rift's CA)──▶ redirect rule ──▶ imposter "optimizely-cdn"
   (proxy + trust)            cdn.optimizely.com                      redirectTo(host, cdn)     serves the datafile
```

1. An imposter serves the datafile at `/datafiles/acct-1.json`.
2. The intercept listener terminates TLS for `cdn.optimizely.com` with a certificate signed by its
   CA, and a `redirectTo` rule forwards those requests to the imposter.
3. The client trusts the intercept CA (`intercept.trust().sslContext()`) and routes through the
   listener (`intercept.proxySelector()`), exactly as a SUT would be configured.
4. `cdn.replaceStubs(...)` swaps the datafile; the next fetch gets the new revision.

## Why `redirectTo`, not `serve`

[docs/intercept.md](../../docs/intercept.md) shows the same recipe with `intercept.serve(host, okJson(...))`,
which is the shortest form when the body never changes. A `serve` body lives in the rule, so
changing it means re-issuing the rule. Redirecting to an imposter puts the body in a stub, so the
swap is one `replaceStubs` call — and with `.record()` on the imposter, `verify` can also check what
the SUT fetched.

## The GET stands in for the SDK's poll

The example fetches the datafile with a plain `HttpClient`, with no Optimizely dependency. The
Optimizely Java SDK fetches it with `HttpProjectConfigManager`, which polls on an interval (minutes,
by default), so it does not see a swap until its next poll — configure a short polling interval in
tests. Its HTTP client also needs the same two settings this example gives the JDK client — route
through the listener and trust its CA — e.g. via the JVM's `https.proxyHost`/`https.proxyPort` and a
truststore from `intercept.trust().exportTruststoreWithSystemCAs(...)`. The intercept and the imposter
are the same either way.

## Running it

JDK 22+ (the embedded engine runs in-process over Panama FFM), with `--enable-native-access=ALL-UNNAMED`
(`examples/pom.xml` sets it for the module's tests; pass it to `java` yourself). The sample takes the
engine library explicitly. From the repository root:

```sh
./mvnw -pl examples/optimizely-datafile -am install -DskipTests
java --enable-native-access=ALL-UNNAMED \
  -Drift.ffi.lib=/path/to/librift_ffi.<ext> \
  -cp "$(./mvnw -q -pl examples/optimizely-datafile dependency:build-classpath -Dmdep.outputFile=/dev/stdout):examples/optimizely-datafile/target/classes" \
  io.github.achirdlabs.rift.examples.OptimizelyDatafileExample
```

```
Intercepted datafile: {"accountId":"acct-1","featureFlags":[],"revision":"42"}
After the swap:       {"accountId":"acct-1","featureFlags":[],"revision":"43"}
```

`librift_ffi` for each platform is attached to every [rift release](https://github.com/achird-labs/rift/releases)
(e.g. `librift_ffi-darwin-aarch64.dylib`).
In your own project, add the `rift-java-natives` classifier jar for your platform instead (see the
top-level [README](../../README.md)) and call `Rift.embedded()` with no library path.

## Against a container

The same flow runs against a Dockerized engine through `rift-java-testcontainers`:
`new RiftContainer().withInterceptPort(8888)` launches the listener, `client().intercept(rift.interceptOptions())`
attaches to it, and the rest is unchanged — see `redirectAndReplaceStubsSwapsTheServedDatafile` in
[`RiftContainerInterceptIT`](../../rift-java-testcontainers/src/test/java/io/github/achirdlabs/rift/testcontainers/RiftContainerInterceptIT.java).
For a SUT in its own container, which must trust the CA before it starts, launch the listener with a
committed CA (`withInterceptCa(...)`) — see [docs/testcontainers.md](../../docs/testcontainers.md).
