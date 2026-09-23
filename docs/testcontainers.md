# Testcontainers integration (`rift-java-testcontainers`)

`RiftContainer` runs the `rift-proxy` engine in Docker — for teams standardized on
[Testcontainers](https://testcontainers.org), or CI without native-lib/binary access. It extends
`GenericContainer`, so it composes with `@Testcontainers`/`@Container` like any other container,
and `client()` hands back a `Rift` with the `hostResolver` seam wired so `imposter.uri()` is
correct through Docker's port remapping — with zero user-side rewriting.

```xml
<dependency>
  <groupId>io.github.achird-labs</groupId>
  <artifactId>rift-java-testcontainers</artifactId>
  <scope>test</scope>
</dependency>
```

The default image is `zainalpour/rift-proxy:v<engine-version>`, pinned to the same engine version
as the rest of rift-java (`RiftContainer.ENGINE_VERSION`). Pass a `DockerImageName` to override it.

## Fixed-port mode (default)

Pre-expose the imposter ports you'll bind. `imposter.uri()` then resolves to the mapped host:port:

```java
@Testcontainers
class OrdersTest {
    @Container
    static RiftContainer rift = new RiftContainer().withImposterPorts(4545);

    @Test
    void ordersCallUsers() {
        try (Rift client = rift.client()) {
            Imposter users = client.create(imposter("users").port(4545)
                    .stub(onGet("/u/1").willReturn(okJson("{\"id\":1}"))));
            // users.uri() == http://<host>:<mappedPort(4545)> — point your SUT at it
        }
    }
}
```

## Gateway mode

`withGateway()` needs no pre-exposed imposter ports: traffic routes through the single admin port
via the engine's `/__rift/:port` gateway, and `imposter.uri()` carries that prefix. The trade-off:
one exposed port, but the `/__rift/:port` prefix is visible to the app under test.

```java
@Container
static RiftContainer rift = new RiftContainer().withGateway();
// imposter.uri() == http://<host>:<mappedAdminPort>/__rift/<port>
```

## API key

`withApiKey(key)` sets the engine's `MB_APIKEY` (so the admin control plane requires it) and makes
`client()` authenticate with the same key. The gateway data plane is not gated by the key.

## TLS-MITM intercept

`withInterceptPort(port)` starts the engine's intercept listener at launch (via `RIFT_INTERCEPT_PORT`)
and exposes the port; attach the client to the mapped endpoint with `interceptOptions()`:

```java
@Container
static final RiftContainer rift = new RiftContainer().withInterceptPort(8888);

@Test
void mocksAnHttpsDependency() throws Exception {
    try (Rift client = rift.client()) {
        Intercept intercept = client.intercept(rift.interceptOptions());   // attach to the mapped port
        intercept.serve("api.partner.com", okJson("{\"ok\":true}"));

        HttpClient http = HttpClient.newBuilder()
                .sslContext(intercept.trust().sslContext())      // trust the container's CA
                .proxy(intercept.proxySelector())
                .build();
        // point the SUT (or this client) at https://api.partner.com/… — served by rift
    }
}
```

See [docs/intercept.md](intercept.md) for rules, trust material, and shared-CA setups.

## Outbound TLS trust (proxying an origin behind a private CA)

`withUpstreamTrust(...)` sets what the engine trusts when a `proxy` stub, or the intercept
listener's origin leg, dials a real HTTPS origin (rift ≥ 0.18.0). A `CaFile` or an inline `CaPem`
is written into the container and named to the engine (`RIFT_UPSTREAM_CA_FILE`); `SkipVerify` sets
`RIFT_UPSTREAM_TLS_SKIP_VERIFY` and logs a warning:

```java
@Container
static RiftContainer rift = new RiftContainer()
        .withImposterPorts(4545)
        .withUpstreamTrust(new UpstreamTrust.CaFile(Path.of("/etc/pki/corp-ca.pem")));
```

An image tagged with a version older than 0.18.0 is refused when `withUpstreamTrust` is called.
See [docs/recording.md](recording.md#recording-an-origin-behind-a-private-ca) for the details.

## Using it with the Spring module

Publish the container's admin URI as a property and point `@EnableRift(transport = CONNECT)` at it:

```java
@SpringBootTest
@EnableRift(transport = Transport.CONNECT, adminUri = "${rift.container.admin}")
@ConfigureImposter(name = "users", baseUrlProperty = "user-client.base-url")
@Testcontainers
class UserServiceIT {

    @Container
    static RiftContainer rift = new RiftContainer().withImposterPorts(4545);

    @DynamicPropertySource
    static void riftProps(DynamicPropertyRegistry registry) {
        registry.add("rift.container.admin", () -> rift.adminUri().toString());
    }

    // @InjectImposter("users") / @InjectRift work exactly as in any @EnableRift test
}
```

## Running the integration tests

`RiftContainer`'s own round-trip tests are gated on the `RIFT_IT` environment variable (like
rift-conformance), so they skip cleanly where Docker isn't available. Run them with a Docker daemon
present:

```sh
RIFT_IT=1 ./mvnw -pl rift-java-testcontainers -am test
```
