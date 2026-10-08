package io.github.achirdlabs.rift.embedded;

import io.github.achirdlabs.rift.EmbeddedOptions;
import io.github.achirdlabs.rift.Rift;
import io.github.achirdlabs.rift.error.RiftException;
import io.github.achirdlabs.rift.model.ImposterDefinition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The engine launch options {@link EmbeddedOptions} passes to {@code rift_serve_admin} (#287), against the real engine. */
class LaunchOptionsIT {

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /**
     * An imposter whose response is a script. {@code replaceAll} is one of the calls the embedded
     * transport routes through its admin plane, so the plane's injection gate decides it.
     */
    private static final List<ImposterDefinition> INJECTING = List.of(ImposterDefinition.fromJson(
            "{\"protocol\":\"http\",\"stubs\":[{\"responses\":[{\"inject\":\"function (config) { return { statusCode: 299 }; }\"}]}]}"));

    private static Path lib;

    @BeforeAll
    static void requireLibrary() {
        lib = EmbeddedTestLibrary.require();
    }

    private static EmbeddedOptions.Builder options() {
        return EmbeddedOptions.builder().libraryPath(lib);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    void metricsPortServesThePrometheusRegistryFromStartup() throws Exception {
        int port = freePort();
        try (Rift rift = Rift.embedded(options().metricsPort(port).build())) {
            // Nothing has touched the admin plane: the metrics listener is up because the option was set.
            HttpResponse<String> metrics = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/metrics"))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, metrics.statusCode());
            assertTrue(metrics.body().contains("# TYPE"), metrics.body());
        }
    }

    @Test
    void anInjectingStubThroughTheAdminPlaneIsRefusedByDefault() {
        try (Rift rift = Rift.embedded(options().build())) {
            RiftException e = assertThrows(RiftException.class, () -> rift.replaceAll(INJECTING));
            assertTrue(e.getMessage().toLowerCase().contains("inject"), e.getMessage());
        }
    }

    @Test
    void allowInjectionLetsTheAdminPlaneAcceptAnInjectingStub() {
        try (Rift rift = Rift.embedded(options().allowInjection(true).build())) {
            rift.replaceAll(INJECTING);
            assertEquals(1, rift.imposters().size());
        }
    }

    @Test
    void aMetricsPortThatIsTakenFailsTheEmbeddedStartItself() throws Exception {
        // Bound to loopback explicitly: on BSD a wildcard holder does not stop a loopback bind.
        try (ServerSocket taken = new ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())) {
            RiftException e = assertThrows(RiftException.class,
                    () -> Rift.embedded(options().metricsPort(taken.getLocalPort()).build()).close());
            assertTrue(e.getMessage().contains("metrics bind"), e.getMessage());
        }
    }
}
