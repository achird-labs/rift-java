package io.github.achirdlabs.rift.testcontainers;

import io.github.achirdlabs.rift.ConnectOptions;
import io.github.achirdlabs.rift.InterceptOptions;
import io.github.achirdlabs.rift.Rift;
import io.github.achirdlabs.rift.RiftVersion;
import io.github.achirdlabs.rift.transport.HostAuthority;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;

/**
 * A Testcontainers container for the {@code rift-proxy} engine, for teams that run their mocks in
 * Docker (CI without native-lib or binary access). {@link #client()} returns a {@link Rift} wired
 * through the {@code hostResolver} seam so {@code imposter.uri()} is correct through Docker's port
 * remapping — either fixed pre-exposed ports ({@link #withImposterPorts(int...)}) or the single-port
 * {@link #withGateway() gateway}.
 *
 * <pre>{@code
 * @Testcontainers
 * class MyTest {
 *     @Container static RiftContainer rift = new RiftContainer().withImposterPorts(4545);
 *
 *     @Test void t() {
 *         Imposter users = rift.client().create(imposter("users").port(4545)
 *                 .stub(onGet("/u/1").willReturn(okJson("{\"id\":1}"))));
 *         // users.uri() == mapped host:port
 *     }
 * }
 * }</pre>
 */
public final class RiftContainer extends GenericContainer<RiftContainer> {

    /** The pinned rift engine version, single-sourced from core's {@code <rift.engine.version>} resource. */
    public static final String ENGINE_VERSION = RiftVersion.engineVersion();

    private static final String IMAGE = "zainalpour/rift-proxy";
    private static final int ADMIN_PORT = 2525;

    private final DockerImageName imageName;
    private Optional<String> apiKey = Optional.empty();
    private boolean gateway = false;
    private Integer interceptPort;

    /** Uses {@code zainalpour/rift-proxy:v}{@link #ENGINE_VERSION}. */
    public RiftContainer() {
        this(DockerImageName.parse(IMAGE + ":v" + ENGINE_VERSION));
    }

    public RiftContainer(DockerImageName image) {
        super(image);
        this.imageName = image;
        addExposedPort(ADMIN_PORT);
        // 401 also means the admin API is up — it's just apiKey-gated (see withApiKey); treating
        // only 200 as ready would hang startup whenever a key is configured.
        waitingFor(Wait.forHttp("/imposters").forPort(ADMIN_PORT)
                .forStatusCodeMatching(code -> code == 200 || code == 401));
    }

    /**
     * The configured image name, resolved without contacting Docker (unlike {@link
     * #getDockerImageName()}, which pulls). Package-private: it exists for Docker-free unit tests.
     */
    String configuredImageName() {
        return imageName.asCanonicalNameString();
    }

    /**
     * Requires the given admin API key on control-plane requests (engine {@code MB_APIKEY}); the
     * {@link #client()} authenticates with the same key. The gateway data plane is not gated by it.
     */
    public RiftContainer withApiKey(String key) {
        this.apiKey = Optional.of(Objects.requireNonNull(key, "key"));
        withEnv("MB_APIKEY", key);
        return self();
    }

    /**
     * Pre-exposes fixed imposter ports so imposters bound to them are reachable through Docker's
     * port mapping; {@code imposter.uri()} then resolves to the mapped host:port.
     */
    public RiftContainer withImposterPorts(int... ports) {
        for (int port : ports) {
            addExposedPort(port);
        }
        return self();
    }

    /**
     * Routes imposter traffic through the single admin port via the {@code /__rift/:port} gateway
     * instead of pre-exposed per-imposter ports — one exposed port, at the cost of a URL prefix
     * visible to the app under test.
     */
    public RiftContainer withGateway() {
        this.gateway = true;
        return self();
    }

    /**
     * Starts the engine's TLS-MITM intercept listener on {@code port} (via {@code RIFT_INTERCEPT_PORT})
     * and exposes it. Obtain the client-side handle with {@code client().intercept(interceptOptions())}
     * once the container is running.
     */
    public RiftContainer withInterceptPort(int port) {
        this.interceptPort = port;
        addExposedPort(port);
        withEnv("RIFT_INTERCEPT_PORT", String.valueOf(port));
        return self();
    }

    /**
     * Attach options for the intercept listener started by {@link #withInterceptPort(int)}, pointed at
     * the mapped host:port. Pass to {@code client().intercept(...)}. Valid only once the container is started.
     */
    public InterceptOptions interceptOptions() {
        if (interceptPort == null) {
            throw new IllegalStateException("no intercept listener configured — call withInterceptPort(...) first");
        }
        return InterceptOptions.attach(getHost(), getMappedPort(interceptPort));
    }

    /** The mapped admin API URI. Valid only once the container is started. */
    public URI adminUri() {
        return HostAuthority.httpUri(getHost(), getMappedPort(ADMIN_PORT));
    }

    /**
     * A {@link Rift} client wired to this container: the {@code hostResolver} seam is set so
     * {@code imposter.uri()} resolves through Docker's port mapping (fixed-port mode) or the gateway
     * prefix (gateway mode) with zero user code. Valid only once the container is started. Each call
     * returns a new client; the caller owns it and must {@link Rift#close() close} it.
     */
    public Rift client() {
        URI admin = adminUri();
        ConnectOptions.Builder options = ConnectOptions.builder(admin);
        apiKey.ifPresent(options::apiKey);
        options.hostResolver(gateway
                ? port -> URI.create(admin + "/__rift/" + port)
                : port -> HostAuthority.httpUri(getHost(), getMappedPort(port)));
        return Rift.connect(options.build());
    }

}
