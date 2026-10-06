package io.github.achirdlabs.rift.testcontainers;

import io.github.achirdlabs.rift.ConnectOptions;
import io.github.achirdlabs.rift.HostResolver;
import io.github.achirdlabs.rift.InterceptOptions;
import io.github.achirdlabs.rift.Rift;
import io.github.achirdlabs.rift.RiftVersion;
import io.github.achirdlabs.rift.UpstreamTrust;
import io.github.achirdlabs.rift.transport.HostAuthority;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

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
    private static final Logger LOG = System.getLogger(RiftContainer.class.getName());

    /** Where the upstream CA PEM is written inside the container. Package-private for tests. */
    static final String UPSTREAM_CA_PATH = "/etc/rift/upstream-ca.pem";
    private static final String UPSTREAM_CA_FILE_ENV = "RIFT_UPSTREAM_CA_FILE";
    private static final String UPSTREAM_TLS_SKIP_VERIFY_ENV = "RIFT_UPSTREAM_TLS_SKIP_VERIFY";
    /** An image tag that declares an engine version ({@code v0.18.0}, {@code 0.18.0-static}), as opposed to {@code latest}. */
    private static final Pattern VERSION_TAG = Pattern.compile("[vV]?\\d+(\\.\\d+){0,2}([-+].*)?");

    private final DockerImageName imageName;
    private Optional<String> apiKey = Optional.empty();
    private boolean gateway = false;
    private Optional<InterceptListener> interceptListener = Optional.empty();
    private Optional<UpstreamTrust> upstreamTrust = Optional.empty();

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
     *
     * @throws IllegalArgumentException if {@link #withExposedInterceptPort(int)} was called: an engine
     *         runs one intercept listener, so one started at launch refuses every runtime start
     */
    public RiftContainer withInterceptPort(int port) {
        useInterceptListener(new InterceptListener(port, true));
        addExposedPort(port);
        withEnv("RIFT_INTERCEPT_PORT", String.valueOf(port));
        return self();
    }

    /**
     * Exposes {@code port} for an intercept listener started at runtime, without starting one at launch.
     * Start it from the {@link #client()} once the container is running, binding the container's
     * interface so Docker's port mapping reaches it, with your own CA if you have one:
     *
     * <pre>{@code
     * client.intercept(InterceptOptions.builder().host("0.0.0.0").port(port).ca(certPem, keyPem).build());
     * }</pre>
     *
     * <p>The returned handle's {@code address()} and {@code proxySelector()} are the mapped host and
     * port. Use {@link #withInterceptPort(int)} instead for a listener the engine starts at launch.
     *
     * @throws IllegalArgumentException if {@link #withInterceptPort(int)} was called: an engine runs
     *         one intercept listener, so one started at launch refuses every runtime start
     */
    public RiftContainer withExposedInterceptPort(int port) {
        useInterceptListener(new InterceptListener(port, false));
        addExposedPort(port);
        return self();
    }

    /** The engine's one intercept listener: on {@code port}, started at launch or at runtime. */
    private record InterceptListener(int port, boolean atLaunch) { }

    private void useInterceptListener(InterceptListener listener) {
        if (interceptListener.isPresent() && interceptListener.get().atLaunch() != listener.atLaunch()) {
            throw new IllegalArgumentException("withInterceptPort (a listener started at launch) and"
                    + " withExposedInterceptPort (one started at runtime) cannot be combined: the engine runs"
                    + " one intercept listener");
        }
        this.interceptListener = Optional.of(listener);
    }

    /**
     * Where the {@link #client()} reaches an intercept listener the engine bound on {@code port}: the
     * mapped host and port, whatever the imposter mode, because a {@code CONNECT} proxy cannot ride the
     * gateway. Refuses a port that is not exposed, which the client asks before starting the listener.
     * Package-private for tests.
     */
    InetSocketAddress interceptAddressFor(int port) {
        if (!getExposedPorts().contains(port)) {
            throw new IllegalArgumentException("intercept port " + port + " is not exposed on this rift container"
                    + " (Docker maps only ports exposed before it starts): call withExposedInterceptPort("
                    + (port == 0 ? "<port>" : port) + ") and start the listener on that port");
        }
        return new InetSocketAddress(getHost(), getMappedPort(port));
    }

    /**
     * Attach options for the intercept listener started by {@link #withInterceptPort(int)}, pointed at
     * the mapped host:port. Pass to {@code client().intercept(...)}. Valid only once the container is started.
     */
    public InterceptOptions interceptOptions() {
        InterceptListener listener = interceptListener.filter(InterceptListener::atLaunch).orElseThrow(() ->
                new IllegalStateException("no intercept listener configured — call withInterceptPort(...) first"));
        return InterceptOptions.attach(getHost(), getMappedPort(listener.port()));
    }

    /**
     * What the engine trusts when a {@code proxy} stub (or the intercept listener) dials a real origin
     * over TLS, for recording an origin behind a private or corporate CA. Unset by default: the
     * image's trust store. A later call replaces an earlier one; one engine has one policy.
     *
     * <ul>
     *   <li>{@link UpstreamTrust.CaFile}: the host file is read when the container starts (an
     *       unreadable one fails the start), copied into the container, and named to the engine
     *       ({@code RIFT_UPSTREAM_CA_FILE}, the env form of {@code --upstream-ca-file}).</li>
     *   <li>{@link UpstreamTrust.CaPem}: written into the container the same way. Unlike a spawned
     *       engine, a container takes an inline PEM, because this transport writes the file.</li>
     *   <li>{@link UpstreamTrust.SkipVerify}: {@code RIFT_UPSTREAM_TLS_SKIP_VERIFY}, the env form of
     *       {@code --upstream-tls-skip-verify}, and a warning is logged at start. Development only.</li>
     * </ul>
     *
     * <p>Requires a rift engine &ge; 0.18.0, checked here against the image tag ({@code v0.17.0} is
     * refused). A tag that is not a version ({@code latest}, a custom build) cannot be checked and is
     * accepted; an engine older than 0.18.0 behind such a tag ignores the setting.
     *
     * @throws IllegalArgumentException if the image tag is a version older than 0.18.0
     */
    public RiftContainer withUpstreamTrust(UpstreamTrust upstreamTrust) {
        Objects.requireNonNull(upstreamTrust, "upstreamTrust");
        String tag = imageName.getVersionPart();
        if (VERSION_TAG.matcher(tag).matches() && !UpstreamTrust.supportedBy(tag)) {
            throw new IllegalArgumentException("upstreamTrust needs a rift engine >= " + UpstreamTrust.MIN_ENGINE_VERSION
                    + ", but the image tag is " + tag + " (the older engine has no upstream TLS options)");
        }
        this.upstreamTrust = Optional.of(upstreamTrust);
        return self();
    }

    /**
     * Applies {@link #withUpstreamTrust(UpstreamTrust)} as the container starts, so a replaced policy
     * leaves nothing behind and a {@link UpstreamTrust.CaFile} is read when the engine starts, as on
     * the other transports.
     */
    @Override
    protected void configure() {
        super.configure();
        skipVerifyWarning().ifPresent(warning -> LOG.log(Level.WARNING, warning));
        upstreamCaCopy().ifPresent(pem -> {
            withCopyToContainer(pem, UPSTREAM_CA_PATH);
            withEnv(UPSTREAM_CA_FILE_ENV, UPSTREAM_CA_PATH);
        });
        if (upstreamTrust.orElse(null) instanceof UpstreamTrust.SkipVerify) {
            withEnv(UPSTREAM_TLS_SKIP_VERIFY_ENV, "true");
        }
    }

    /** The CA PEM to copy to {@link #UPSTREAM_CA_PATH}, if the policy names one. Package-private for tests. */
    Optional<Transferable> upstreamCaCopy() {
        UpstreamTrust trust = upstreamTrust.orElse(null);
        if (trust instanceof UpstreamTrust.CaPem pem) {
            return Optional.of(Transferable.of(pem.pem()));
        }
        if (trust instanceof UpstreamTrust.CaFile file) {
            try {
                return Optional.of(Transferable.of(Files.readAllBytes(file.pem())));
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read the upstream CA file " + file.pem()
                        + " to copy into the rift container: " + e.getMessage(), e);
            }
        }
        return Optional.empty();
    }

    /**
     * The warning to log when the policy turns upstream verification off. The engine logs one too, but
     * into the container's output, which nobody reads unless the start fails.
     */
    Optional<String> skipVerifyWarning() {
        if (!(upstreamTrust.orElse(null) instanceof UpstreamTrust.SkipVerify)) {
            return Optional.empty();
        }
        return Optional.of("the rift container is accepting any upstream TLS certificate (UpstreamTrust.SkipVerify): "
                + "proxy stubs can record a man-in-the-middle's traffic. "
                + "Development only; prefer UpstreamTrust.CaFile or CaPem.");
    }

    /** The mapped admin API URI. Valid only once the container is started. */
    public URI adminUri() {
        return HostAuthority.httpUri(getHost(), getMappedPort(ADMIN_PORT));
    }

    /**
     * A {@link Rift} client wired to this container: the {@code hostResolver} seam is set so
     * {@code imposter.uri()} resolves through Docker's port mapping (fixed-port mode) or the gateway
     * prefix (gateway mode) with zero user code, and an intercept started at runtime on a port exposed
     * with {@link #withExposedInterceptPort(int)} is reached through Docker's mapping. Valid only once the container is started. Each call
     * returns a new client; the caller owns it and must {@link Rift#close() close} it.
     */
    public Rift client() {
        return Rift.connect(connectOptions(adminUri()));
    }

    /** The {@link #client()}'s options against the admin API at {@code admin}. Package-private for tests. */
    ConnectOptions connectOptions(URI admin) {
        ConnectOptions.Builder options = ConnectOptions.builder(admin);
        apiKey.ifPresent(options::apiKey);
        // Gateway traffic reaches the imposter through the admin listener, so it takes the admin
        // listener's scheme whatever the imposter speaks; direct traffic takes the imposter's protocol.
        HostResolver resolver = gateway
                ? (protocol, port) -> URI.create(admin + "/__rift/" + port)
                : (protocol, port) -> HostAuthority.uri(protocol, getHost(), getMappedPort(port));
        options.hostResolver(resolver);
        options.interceptAddress(this::interceptAddressFor);
        return options.build();
    }

}
