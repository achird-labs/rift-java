package io.github.achirdlabs.rift.conformance;

import io.github.achirdlabs.rift.EmbeddedOptions;
import io.github.achirdlabs.rift.Imposter;
import io.github.achirdlabs.rift.Rift;
import io.github.achirdlabs.rift.SpawnOptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.stream.Stream;

import static io.github.achirdlabs.rift.conformance.LiveEngine.gatedTo;
import static io.github.achirdlabs.rift.dsl.RiftDsl.imposter;
import static io.github.achirdlabs.rift.dsl.RiftDsl.onGet;
import static io.github.achirdlabs.rift.dsl.RiftDsl.status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * rift 0.18.0 binds a bare IPv6 host; the SDK must build admin and imposter URIs it can use (#232).
 * Skips where IPv6 loopback is unavailable. Needs {@code RIFT_IT=1}.
 */
class Ipv6HostIT {

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @TestFactory
    Stream<DynamicTest> aSpawnedEngineOnABareIpv6Host() {
        return gatedTo(ConformanceTransport.SPAWN, "host() is the spawn CLI's --host",
                "a spawned engine bound to ::1 is reachable through its admin and imposter URIs", () -> {
            assumeTrue(ipv6LoopbackAvailable(), "no IPv6 loopback here");
            try (Rift rift = Rift.spawn(SpawnOptions.builder().host("::1").localOnly(false).build())) {
                assertEquals("[::1]", rift.adminUri().getHost());
                assertServes(rift);
            }
        });
    }

    @TestFactory
    Stream<DynamicTest> anEmbeddedEngineOnABareIpv6Host() {
        return gatedTo(ConformanceTransport.EMBEDDED, "adminHost() is the embedded admin server's bind host",
                "an embedded engine bound to ::1 is reachable through its admin and imposter URIs", () -> {
            assumeTrue(ipv6LoopbackAvailable(), "no IPv6 loopback here");
            try (Rift rift = Rift.embedded(EmbeddedOptions.builder().adminHost("::1").build())) {
                assertEquals("[::1]", rift.adminUri().getHost());
                assertServes(rift);
            }
        });
    }

    private static void assertServes(Rift rift) throws Exception {
        // The engine binds an imposter on 0.0.0.0 unless the imposter names its own host, whatever
        // the admin host is, so bind this one to ::1 too: uri() reports the admin host (#243).
        Imposter imposter = rift.create(imposter("v6").protocol("http").host("::1")
                .stub(onGet("/").willReturn(status(200).withTextBody("v6"))));
        assertEquals("[::1]", imposter.uri().getHost());
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(imposter.uri().resolve("/")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals("v6", response.body());
    }

    private static boolean ipv6LoopbackAvailable() {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("::1"))) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
