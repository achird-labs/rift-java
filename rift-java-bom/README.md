# rift-java-bom

Bill of materials for rift-java: one `<dependencyManagement>` import version-pins every
module (`rift-java-core`, `rift-java-embedded`, `rift-java-embedded-jdk21`, `rift-java-jackson`,
`rift-java-junit5`, `rift-java-spring`) plus `rift-java-natives` — both the bare coordinate and
all six per-platform classifier jars (`linux-x86_64`, `linux-aarch64`, `linux-musl-x86_64`,
`darwin-x86_64`, `darwin-aarch64`, `windows-x86_64`), and each one's `-wasm` flavour
(`darwin-aarch64-wasm`, …): `librift_ffi` with the WebAssembly engine, from rift 0.25.0. The plain
jar stays the default; load the `-wasm` one with `EmbeddedOptions.builder().wasmLibrary(true)`.

## Consumer usage

Import the BOM, then depend on whichever modules you need — no `<version>` required:

Use the latest release for `X.Y.Z` (the root [README](../README.md#installation) names it), or the
current `X.Y.Z-SNAPSHOT` from the Central Portal snapshots repository to track `master`. This file
deliberately does not hardcode either: it is not covered by the release job's README version sync,
so a literal here goes stale the moment a release lands.

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.github.achird-labs</groupId>
      <artifactId>rift-java-bom</artifactId>
      <version>X.Y.Z</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <dependency>
    <groupId>io.github.achird-labs</groupId>
    <artifactId>rift-java-core</artifactId>
  </dependency>
  <dependency>
    <groupId>io.github.achird-labs</groupId>
    <artifactId>rift-java-jackson</artifactId>
  </dependency>
  <dependency>
    <groupId>io.github.achird-labs</groupId>
    <artifactId>rift-java-junit5</artifactId>
    <scope>test</scope>
  </dependency>
</dependencies>
```

If the BOM doesn't manage a module's version, Maven fails the build with
`dependencies.dependency.version is missing` — this is enforced by
[`src/it/resolve-through-bom`](src/it/resolve-through-bom), a `maven-invoker-plugin` integration
test that resolves the whole set through the BOM alone.

> The `-SNAPSHOT` version above resolves only if you have added the Central Portal snapshots
> repository (`https://central.sonatype.com/repository/maven-snapshots/`); stable `X.Y.Z` versions
> come from Maven Central with no extra configuration. The repository snippet (Maven and Gradle) is
> in the root README's [Installation](../README.md#installation) section.

## Picking a natives classifier automatically: os-maven-plugin

`rift-java-natives` publishes one classifier jar per platform. To let the build pick the right
one instead of hardcoding it, add [`os-maven-plugin`](https://github.com/trustin/os-maven-plugin)
as a build extension and reference `${os.detected.classifier}`:

```xml
<build>
  <extensions>
    <extension>
      <groupId>kr.motd.maven</groupId>
      <artifactId>os-maven-plugin</artifactId>
      <version>1.7.1</version>
    </extension>
  </extensions>
</build>

<dependencies>
  <dependency>
    <groupId>io.github.achird-labs</groupId>
    <artifactId>rift-java-natives</artifactId>
    <!-- Not ${os.detected.classifier}: that yields osx-/aarch_64 names which don't match our
         scheme. rift-natives.classifier is derived by the OS/arch profiles shown below. -->
    <classifier>${rift-natives.classifier}</classifier>
  </dependency>
</dependencies>
```

**Property mapping note:** `rift-java-natives` classifiers use our own `<os>-<arch>` scheme
(`darwin-x86_64`, `darwin-aarch64`, `linux-x86_64`, `linux-aarch64`, `linux-musl-x86_64`,
`windows-x86_64`), which does **not** match `os-maven-plugin`'s defaults (`osx-x86_64`,
`osx-aarch_64`, `linux-x86_64`, ...). The two differ in:

| os-maven-plugin | rift-java-natives |
|---|---|
| `osx-x86_64` | `darwin-x86_64` |
| `osx-aarch_64` | `darwin-aarch64` |
| `linux-x86_64` | `linux-x86_64` |
| `linux-aarch_64` | `linux-aarch64` |

Namely: `osx` → `darwin`, and `aarch_64` → `aarch64` (no underscore). `os-maven-plugin` has no
built-in remapping for this, so translate its detected properties yourself. Because the plugin runs
as a build **extension**, `os.detected.name` (`osx`/`linux`/`windows`) and `os.detected.arch`
(`x86_64`/`aarch_64`) are set *before* profile activation — so activate on those exact values and map
each half to our scheme, then recombine:

```xml
<properties>
  <rift-natives.classifier>${rift-natives.os}-${rift-natives.arch}</rift-natives.classifier>
</properties>

<profiles>
  <profile>
    <activation><property><name>os.detected.name</name><value>osx</value></property></activation>
    <properties><rift-natives.os>darwin</rift-natives.os></properties>
  </profile>
  <profile>
    <activation><property><name>os.detected.name</name><value>linux</value></property></activation>
    <properties><rift-natives.os>linux</rift-natives.os></properties>
  </profile>
  <profile>
    <activation><property><name>os.detected.name</name><value>windows</value></property></activation>
    <properties><rift-natives.os>windows</rift-natives.os></properties>
  </profile>
  <profile>
    <activation><property><name>os.detected.arch</name><value>aarch_64</value></property></activation>
    <properties><rift-natives.arch>aarch64</rift-natives.arch></properties>
  </profile>
  <profile>
    <activation><property><name>os.detected.arch</name><value>x86_64</value></property></activation>
    <properties><rift-natives.arch>x86_64</rift-natives.arch></properties>
  </profile>
</profiles>
```

Activating on `os.detected.*` (not Maven's `<os><family>`) avoids two traps: Maven's `unix` family
also matches macOS, and its raw `${os.detected.arch}` keeps the `aarch_64` underscore. Note
`os-maven-plugin` can't distinguish glibc from musl — for `linux-musl-x86_64`, pin explicitly (below).

## Alternative: pin the classifier explicitly (recommended for CI matrices)

For CI matrices that already know which platform they're on (e.g. a GitHub Actions
`strategy.matrix.os` job), skip `os-maven-plugin` entirely and pin the classifier directly —
it's simpler, avoids the mapping mismatch above, and keeps the dependency set reproducible
per job:

```xml
<dependency>
  <groupId>io.github.achird-labs</groupId>
  <artifactId>rift-java-natives</artifactId>
  <classifier>linux-x86_64</classifier>
</dependency>
```

The version still comes from the BOM either way.
