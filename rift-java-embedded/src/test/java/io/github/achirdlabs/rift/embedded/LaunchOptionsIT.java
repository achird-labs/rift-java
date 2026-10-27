package io.github.achirdlabs.rift.embedded;

import io.github.achirdlabs.rift.EmbeddedOptions;
import io.github.achirdlabs.rift.Imposter;
import io.github.achirdlabs.rift.RecordedRequest;
import io.github.achirdlabs.rift.Rift;
import io.github.achirdlabs.rift.error.RiftException;
import io.github.achirdlabs.rift.model.ImposterDefinition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static io.github.achirdlabs.rift.dsl.RiftDsl.imposter;
import static io.github.achirdlabs.rift.dsl.RiftDsl.ok;
import static io.github.achirdlabs.rift.dsl.RiftDsl.onRequest;
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
    @Test
    void aBodyOverMaxBodySizeIsRefusedWith413OnAnImposterCreatedAfterStart() throws Exception {
        // the engine applies the limit only to imposters created after it is set: the plane must be up first
        try (Rift rift = Rift.embedded(options().maxBodySize(1024).build())) {
            int port = rift.create(imposter("capped").stub(onRequest().willReturn(ok()))).port();
            HttpResponse<Void> response = post(port, 2048);
            assertEquals(413, response.statusCode());
            assertEquals(200, post(port, 512).statusCode(), "a body within the limit is served");
        }
    }

    @Test
    void aBodyLargerThanTheJournalBudgetIsRecordedWithoutIt() throws Exception {
        try (Rift rift = Rift.embedded(options().journalMaxBytes(4096).build())) {
            Imposter imposter = rift.create(imposter("journal").record().stub(onRequest().willReturn(ok())));
            assertEquals(200, post(imposter.port(), 16_384).statusCode());
            RecordedRequest recorded = imposter.recorded().get(0);
            assertTrue(recorded.bodyOmitted(), "recorded with its body (" + recorded.body().length() + " bytes)");
            assertEquals("", recorded.body());
        }
    }

    /**
     * {@code replaceAll} goes through the admin plane, which from rift 0.24.0 reads a lookup CSV only
     * under {@code scriptsDir}, a relative path from there (rift#1617, #343).
     */
    @Test
    void scriptsDirLetsTheAdminPlaneReadALookupCsvUnderIt(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("rows.csv"), "id,name\n7,seven\n");
        int port = freePort();
        try (Rift rift = Rift.embedded(options().scriptsDir(dir).build())) {
            rift.replaceAll(List.of(lookupImposter(port, "rows.csv")));
            HttpResponse<String> answer = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/7"))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals("seven", answer.body(), "the relative path resolved against scriptsDir");
        }
    }

    @Test
    void withoutScriptsDirTheAdminPlaneRefusesALookup() throws Exception {
        try (Rift rift = Rift.embedded(options().build())) {
            RiftException e = assertThrows(RiftException.class,
                    () -> rift.replaceAll(List.of(lookupImposter(freePort(), "rows.csv"))));
            assertTrue(e.getMessage().contains("no --scripts-dir is configured"), e.getMessage());
        }
    }

    @Test
    void aLookupCsvOutsideScriptsDirIsRefused(@TempDir Path root) throws Exception {
        Path dir = Files.createDirectory(root.resolve("scripts"));
        Path outside = Files.writeString(root.resolve("outside.csv"), "id,name\n7,seven\n");
        try (Rift rift = Rift.embedded(options().scriptsDir(dir).build())) {
            RiftException e = assertThrows(RiftException.class,
                    () -> rift.replaceAll(List.of(lookupImposter(freePort(), outside.toString()))));
            assertTrue(e.getMessage().contains("outside --scripts-dir"), e.getMessage());
        }
    }

    private static ImposterDefinition lookupImposter(int port, String csvPath) {
        return ImposterDefinition.fromJson("""
                {"port": %d, "protocol": "http", "stubs": [{"responses": [{
                  "is": {"body": "${row}[name]"},
                  "_behaviors": {"lookup": [{
                    "key": {"from": "path", "using": {"method": "regex", "selector": "/(\\\\d+)"}, "index": 1},
                    "fromDataSource": {"csv": {"path": "%s", "keyColumn": "id"}},
                    "into": "${row}"}]}}]}]}
                """.formatted(port, csvPath.replace("\\", "\\\\")));
    }

    private static HttpResponse<Void> post(int port, int bodyBytes) throws Exception {
        // a fresh client each time: the engine closes the connection after a 413
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/upload"))
                        .timeout(Duration.ofSeconds(5)).POST(HttpRequest.BodyPublishers.ofString("x".repeat(bodyBytes))).build(),
                HttpResponse.BodyHandlers.discarding());
    }
}
