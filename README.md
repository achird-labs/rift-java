# rift-java

Official Java SDK for [Rift](https://github.com/achird-labs/rift) — a high-performance,
Mountebank-compatible HTTP/HTTPS mock server written in Rust.

📖 **Documentation site: [achird-labs.github.io/rift-java](https://achird-labs.github.io/rift-java/)**

> **Available on Maven Central.** The current release is **0.2.3**. From **0.1.3** onward rift-java
> publishes under the `io.github.achird-labs` group ID; **0.1.2 and earlier are under
> `io.github.etacassiopeia`** — see [Moving off the old group ID](#moving-off-the-old-group-id).
> The public API is documented on the
> [docs site](https://achird-labs.github.io/rift-java/) and
> [docs/design/sdk-api.md](docs/design/sdk-api.md). Stable releases go to Maven Central on each
> `vX.Y.Z` tag; development **snapshots** publish to the Central Portal snapshots repository on
> every commit to `master`. See [Installation](#installation) to add it to your build.

## What it looks like

```java
import static io.github.achirdlabs.rift.dsl.RiftDsl.*;

try (Rift rift = Rift.embedded()) {                  // or Rift.connect(uri) / Rift.spawn()
    Imposter users = rift.create(
        imposter("users").record()
            .stub(onGet("/api/users/1").willReturn(okJson("{\"id\":1}")))
            .stub(onPost("/api/users").willReturn(created())));

    // point your SUT at users.uri(), then:
    users.verify(onGet("/api/users/1"), times(1));
}
```

## Modules

| Artifact | JDK | Contents |
|---|---|---|
| `rift-java-core` | 17+ | typed wire model + fluent DSL, remote (admin API) + spawn transports, verification. Zero runtime deps. |
| `rift-java-embedded` | 22+ | in-process engine over Panama FFM (`librift_ffi` C-ABI v2) |
| `rift-java-embedded-jdk21` | 21 | same, `--enable-preview` build |
| `rift-java-natives` | — | per-platform classifier jars bundling the cdylib |
| `rift-java-junit5` | 17+ | `@RiftTest` extension, imposter injection |
| `rift-java-jackson` | 17+ | optional POJO body codec |
| `rift-java-spring` | 17+ | Spring Boot test integration: `@EnableRift`, `@ConfigureImposter`, `@InjectImposter`/`@InjectRift`. Spring is `provided`. |
| `rift-java-testcontainers` | 17+ | `RiftContainer` runs the rift proxy in Docker with the `hostResolver` seam wired for port mapping. See [docs/testcontainers.md](docs/testcontainers.md). |
| `rift-java-conformance` | 17+ | test-only parity gate: replays the engine's published conformance corpus over the remote/spawn transport (DSL ↔ engine parity). Not published. See [its README](rift-java-conformance/README.md). |
| `rift-java-bom` | — | one import that version-pins every module + the 6 natives classifiers |

One client, three transports — embedded (in-process, no Docker, OS-assigned ports),
connect (any running Rift admin endpoint), spawn (managed `rift` binary). Full feature
surface on each: stubs/predicates/responses, response cycling, behaviors, proxy
record/playback, fault injection, stateful scenarios, spaces/flow-state, request
verification, and TLS-MITM intercept with truststore/`SSLContext` helpers.

## Installation

rift-java is published under the `io.github.achird-labs` group ID (since 0.1.3 — see
[Moving off the old group ID](#moving-off-the-old-group-id)), on two channels:

| Channel | Repository | Version form | Use for |
|---|---|---|---|
| **Stable release** | Maven Central — the default, no config needed | `X.Y.Z` (e.g. `0.2.3`) | CI / regular test suites |
| **Snapshot** | [Central Portal snapshots](https://central.sonatype.com/repository/maven-snapshots/) — must be added | `X.Y.Z-SNAPSHOT` (e.g. `0.1.3-SNAPSHOT`) | trying the latest `master` |

The recommended entry point is the **BOM** (`rift-java-bom`): import it once and every module is
version-pinned, so you never repeat a version. See the [BOM README](rift-java-bom/README.md) for the
full module list and the natives-classifier setup that the embedded module needs.

### Maven

Import the BOM in `<dependencyManagement>`, then declare the modules you need with **no**
per-module `<version>`:

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.github.achird-labs</groupId>
      <artifactId>rift-java-bom</artifactId>
      <version>0.2.3</version> <!-- or X.Y.Z-SNAPSHOT for the latest master -->
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <dependency>
    <groupId>io.github.achird-labs</groupId>
    <artifactId>rift-java-core</artifactId>
    <scope>test</scope>
  </dependency>
  <dependency>
    <groupId>io.github.achird-labs</groupId>
    <artifactId>rift-java-junit5</artifactId>
    <scope>test</scope>
  </dependency>
</dependencies>
```

Stable releases resolve from Maven Central with no extra configuration. **Snapshots require adding
the Central Portal snapshots repository** — put this in your `pom.xml`, or in `~/.m2/settings.xml`
to share it across projects:

```xml
<repositories>
  <repository>
    <id>central-portal-snapshots</id>
    <url>https://central.sonatype.com/repository/maven-snapshots/</url>
    <releases><enabled>false</enabled></releases>
    <snapshots><enabled>true</enabled></snapshots>
  </repository>
</repositories>
```

### Gradle (Kotlin DSL)

```kotlin
repositories {
    mavenCentral()
    // Snapshots only — omit this block if you depend on a stable release:
    maven {
        url = uri("https://central.sonatype.com/repository/maven-snapshots/")
        mavenContent { snapshotsOnly() }
    }
}

dependencies {
    testImplementation(platform("io.github.achird-labs:rift-java-bom:0.2.3")) // or X.Y.Z-SNAPSHOT for the latest master
    testImplementation("io.github.achird-labs:rift-java-core")
    testImplementation("io.github.achird-labs:rift-java-junit5")
}
```

> **Using the embedded engine?** `rift-java-embedded` additionally needs a `rift-java-natives`
> classifier jar for your platform and the JVM flag `--enable-native-access=ALL-UNNAMED` (on JDK 21,
> use `rift-java-embedded-jdk21`, which also requires `--enable-preview`). The
> [BOM README](rift-java-bom/README.md#picking-a-natives-classifier-automatically-os-maven-plugin)
> shows how to select the right classifier automatically.
>
> Pointing at a locally-built or vendored engine? Override the library path and the version
> preflight from the launch command — no code change:
> `-Drift.ffi.lib=native/librift_ffi.dylib -Drift.versionCheck=off` (`rift.versionCheck` /
> `RIFT_VERSION_CHECK` accept `off`/`warn`/`fail`; the default is `fail`).
>
> Compatibility is checked in two steps: the loaded library must export the full C-ABI v2 symbol set
> (a too-old library is rejected with **every** missing symbol listed at once), and then the reported
> version is compared to the floor. Because the symbol set is authoritative for the embedded engine, a
> locally-built engine that reports a placeholder version (the workspace `0.1.0`) is accepted with a
> warning rather than failing — its ABI is already proven complete.

### Moving off the old group ID

rift-java moved to the [achird-labs](https://github.com/achird-labs) org. The coordinates changed
once, at **0.1.3**:

| Versions | Group ID | Java packages |
|---|---|---|
| 0.1.0 – 0.1.2 | `io.github.etacassiopeia` | `io.github.etacassiopeia.rift.*` |
| 0.1.3 and later | `io.github.achird-labs` | `io.github.achirdlabs.rift.*` |

To upgrade, change the group ID in your build and the package in your imports — the artifact IDs and
the API itself are unchanged. Note the group ID keeps the hyphen (`achird-labs`) while the Java
package drops it (`achirdlabs`), because a package name cannot contain one.

0.1.3 also publishes pom-only **relocation stubs** under the old group ID, so a build that merely
bumps its existing `io.github.etacassiopeia` dependency to 0.1.3 still resolves — Maven, Gradle and
coursier follow the relocation and warn with the new coordinates rather than failing with an
unexplained "not found". The stubs exist only at 0.1.3; past that, depend on the new group ID
directly. Builds pinned at 0.1.2 or earlier are unaffected and keep resolving as before.

## Quick starts

### Embedded

> Requires JDK 22+; pass `--enable-native-access=ALL-UNNAMED` (JDK 21: also `--enable-preview`,
> via `rift-java-embedded-jdk21`). Add a `rift-java-natives` classifier jar for your platform —
> see the [BOM README](rift-java-bom/README.md#picking-a-natives-classifier-automatically-os-maven-plugin).

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

### Connect

Against a running rift admin endpoint:

```java
import static io.github.achirdlabs.rift.dsl.RiftDsl.*;

URI adminUri = URI.create("http://localhost:2525");
try (Rift rift = Rift.connect(adminUri)) {
    Imposter users = rift.create(imposter("users").record());

    // point your SUT at users.uri(), then:
    users.verify(onGet("/api/users/1"), times(1));
}
```

If the admin host isn't reachable at the same address an imposter's own port is (Docker,
remapped ports), override `ConnectOptions.builder(adminUri).hostResolver(port -> ...)`.

### Spawn

```java
import static io.github.achirdlabs.rift.dsl.RiftDsl.*;

try (Rift rift = Rift.spawn()) {           // resolves (or downloads) and launches a rift binary
    Imposter users = rift.create(imposter("users").record());

    // point your SUT at users.uri(), then:
    users.verify(onGet("/api/users/1"), times(1));
}
```

Binary resolution order: `SpawnOptions.binaryPath(...)`, the `RIFT_BINARY_PATH` environment
variable, a `PATH` lookup, a local version cache, then a download from the release mirror. The
version defaults to the engine the SDK is pinned to and tested against (`<rift.engine.version>`),
not the compatibility floor — override with `SpawnOptions.builder().version("X.Y.Z")`.

### JUnit 5

```java
import io.github.achirdlabs.rift.junit5.*;
import static io.github.achirdlabs.rift.dsl.RiftDsl.*;

@RiftTest
class UserClientTest {
    @RiftImposter
    static ImposterSpec usersSpec = imposter("users").record()
            .stub(onGet("/api/users/1").willReturn(okJson("{\"id\":1}")));

    @InjectImposter("users") Imposter users;
    @Test
    void fetchesUser() {
        users.verify(onGet("/api/users/1"), times(1));
    }
}
```

See [docs/junit5.md](docs/junit5.md) for `Transport`/`Reset` semantics and parallel-execution notes.

### Spring Boot

```java
import io.github.achirdlabs.rift.spring.*;
import static io.github.achirdlabs.rift.dsl.RiftDsl.*;

@SpringBootTest
@EnableRift
@ConfigureImposter(name = "users", baseUrlProperty = "users.base-url")
class UserClientTest {
    @InjectImposter("users") Imposter users;    // field injection

    @Test
    void fetchesUser() {
        users.addStub(onGet("/api/users/1").willReturn(okJson("{\"id\":1}")));
        users.verify(onGet("/api/users/1"), times(1));
    }

    // or as method parameters — zero-config, no extra @ExtendWith
    @Test
    void fetchesUserByParam(@InjectImposter("users") Imposter users, @InjectRift Rift rift) {
        users.addStub(onGet("/api/users/2").willReturn(okJson("{\"id\":2}")));
    }
}
```

TLS-MITM intercept (`rift.intercept()`) is covered in [docs/intercept.md](docs/intercept.md).

Proxy **record/replay** (`imposter.startRecording(origin)`) — capture real traffic and swap it for
served stubs — is covered in [docs/recording.md](docs/recording.md).
