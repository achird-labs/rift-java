package io.github.achirdlabs.rift.conformance;

import io.github.achirdlabs.rift.Imposter;
import io.github.achirdlabs.rift.Rift;
import io.github.achirdlabs.rift.dsl.Fault;
import io.github.achirdlabs.rift.dsl.Script;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.stream.Stream;

import static io.github.achirdlabs.rift.conformance.LiveEngine.engine;
import static io.github.achirdlabs.rift.conformance.LiveEngine.gated;
import static io.github.achirdlabs.rift.conformance.LiveEngine.gatedTo;
import static io.github.achirdlabs.rift.dsl.RiftDsl.fault;
import static io.github.achirdlabs.rift.dsl.RiftDsl.imposter;
import static io.github.achirdlabs.rift.dsl.RiftDsl.onGet;
import static io.github.achirdlabs.rift.dsl.RiftDsl.script;
import static io.github.achirdlabs.rift.dsl.RiftDsl.status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** rift 0.18.0 honours {@code repeat} on a fault and a script-only response (#229). Needs {@code RIFT_IT=1}. */
class FaultScriptRepeatIT {

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @TestFactory
    Stream<DynamicTest> aRepeatedFaultFiresThatManyTimes() {
        return gated("a connection reset repeated twice fires twice, then the next response serves", () -> {
            try (Rift rift = engine()) {
                Imposter imposter = rift.create(imposter("flaky").protocol("http")
                        .stub(onGet("/").willReturn(fault(Fault.CONNECTION_RESET_BY_PEER).repeat(2))
                                .willReturn(status(200).withTextBody("next"))));

                // Raw sockets: the JDK HttpClient retries a GET on a reset, which would hide the first fault.
                assertThrows(SocketException.class, () -> rawGet(imposter.port()));
                assertThrows(SocketException.class, () -> rawGet(imposter.port()));
                assertTrue(rawGet(imposter.port()).endsWith("next"), "the third request reaches the next response");

                // The engine echoes the repeat at the response level; it reads back as the effective one.
                io.github.achirdlabs.rift.model.Response.Fault echoed = (io.github.achirdlabs.rift.model.Response.Fault)
                        imposter.definition().stubs().get(0).responses().get(0);
                assertEquals(2, echoed.behaviors().effectiveRepeat().orElseThrow().count());
            }
        });
    }

    @TestFactory
    Stream<DynamicTest> aRepeatedScriptServesThatManyTimes() {
        return gatedTo(ConformanceTransport.SPAWN,
                "a _rift.script response is an injection surface; --allow-injection is a spawn-CLI flag",
                "a script response repeated twice serves twice, then the next response serves", () -> {
            try (Rift rift = engine()) {
                Imposter imposter = rift.create(imposter("scripted").protocol("http")
                        .stub(onGet("/").willReturn(script(Script.rhai("fn respond(ctx) { http(200, \"x\") }")).repeat(2))
                                .willReturn(status(200).withTextBody("next"))));

                assertEquals("x", get(imposter.uri() + "/"));
                assertEquals("x", get(imposter.uri() + "/"));
                assertEquals("next", get(imposter.uri() + "/"));
            }
        });
    }

    private static String rawGet(int port) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            OutputStream out = socket.getOutputStream();
            out.write("GET / HTTP/1.1\r\nHost: rift\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String get(String url) throws IOException, InterruptedException {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        return response.body();
    }
}
