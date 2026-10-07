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
            // (https://… for an imposter created with .https(cert, key))
        }
    }
}
```

## Gateway mode

`withGateway()` needs no pre-exposed imposter ports: traffic routes through the single admin port
via the engine's `/__rift/:port` gateway, and `imposter.uri()` carries that prefix. The trade-off:
one exposed port, but the `/__rift/:port` prefix is visible to the app under test. The gateway is
served by the admin listener, so `imposter.uri()` is `http://` in this mode even for an `https`
imposter: the app speaks plain HTTP to the gateway, which hands the request to the imposter.

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

To launch that listener with a **committed CA** — one a SUT container already trusts before it
starts — add `withInterceptCa(certPem, keyPem)` (paths, read when the container starts, or PEM
text). The pair is copied into the container and named to the engine; `interceptOptions()` carries
it, so the attached handle's `caMaterial()` hands it back after checking it against the CA the
listener serves:

```java
@Container
static final RiftContainer rift = new RiftContainer()
        .withInterceptPort(8888)
        .withInterceptCa(Path.of("intercept/ca-cert.pem"), Path.of("intercept/ca-key.pem"));
```

`withInterceptCa` needs `withInterceptPort`: the engine reads the CA only when it launches a listener.
See [docs/intercept.md](intercept.md#sharing-one-ca-with-a-containerized-sut) for the SUT side.

To start the listener at runtime instead — with your own committed CA, for instance — use
`withExposedInterceptPort(port)`: the port is exposed but no listener is launched (one launched
listener would refuse every runtime start with a `409`, so the two modes cannot be combined). Start
it from the client bound to the container's interface; `client()` maps the handle to Docker's port
whatever the imposter mode, gateway included:

```java
@Container
static final RiftContainer rift = new RiftContainer().withExposedInterceptPort(8889);

@Test
void mocksAnHttpsDependencyWithMyCa() throws Exception {
    try (Rift client = rift.client()) {
        Intercept intercept = client.intercept(InterceptOptions.builder()
                .host("0.0.0.0")                 // the container's interface, not its loopback
                .port(8889)                      // the exposed port; 0 or an unexposed one is refused
                .ca(certPem, keyPem)
                .build());
        // intercept.address() is the mapped host:port; engineAddress() holds http://0.0.0.0:8889
    }
}
```

### Forwarding to another container

From rift 0.20.0 a `forward` rule can name its target's host, so the intercept listener can hand a
host to a mock running in a second container on the same network, by its alias:

```java
static final Network NETWORK = Network.newNetwork();

@Container
static final RiftContainer rift = new RiftContainer().withInterceptPort(8888).withNetwork(NETWORK);

@Container
static final RiftContainer partner = new RiftContainer().withNetwork(NETWORK).withNetworkAliases("partner-mock");

// ... with an imposter on port 4600 in `partner`:
intercept.forward("api.partner.com", "partner-mock:4600");     // or "https://partner-mock:8443"
```

The imposter receives the client's original `Host` (`api.partner.com`), not the alias. See
[forward targets](intercept.md#forward-targets) for the accepted forms.

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
