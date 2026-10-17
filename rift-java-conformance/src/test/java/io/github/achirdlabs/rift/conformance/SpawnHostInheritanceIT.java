package io.github.achirdlabs.rift.conformance;

import io.github.achirdlabs.rift.Imposter;
import io.github.achirdlabs.rift.Rift;
import io.github.achirdlabs.rift.SpawnOptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.Optional;
import java.util.stream.Stream;

import static io.github.achirdlabs.rift.conformance.LiveEngine.gatedTo;
import static io.github.achirdlabs.rift.dsl.RiftDsl.imposter;
import static io.github.achirdlabs.rift.dsl.RiftDsl.ok;
import static io.github.achirdlabs.rift.dsl.RiftDsl.onGet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * From rift 0.23.0 a spawned engine's host-less imposters bind its {@code --host} (rift#1517), and
 * {@code --local-only} refuses non-loopback clients on imposters too (rift#1523). So reaching a
 * spawned imposter from off the loopback interface, as a containerised system under test does, needs
 * both {@code host("0.0.0.0")} on the imposter and {@code localOnly(false)} (#321). Probed over this
 * machine's own non-loopback IPv4 address. Needs {@code RIFT_IT=1}.
 */
class SpawnHostInheritanceIT {

    private static final String SPAWN_ONLY = "--host and --local-only are spawn-CLI flags";

    @TestFactory
    Stream<DynamicTest> aHostlessImposterIsLoopbackOnly() {
        return gatedTo(ConformanceTransport.SPAWN, SPAWN_ONLY, "a host-less imposter answers on loopback only", () -> {
            InetAddress lan = lanAddress();
            try (Rift rift = Rift.spawn(SpawnOptions.builder().build())) {
                Imposter imposter = rift.create(imposter("hostless").stub(onGet("/").willReturn(ok())));
                assertEquals(200, get("127.0.0.1", imposter.port()).orElseThrow());
                assertFalse(get(lan.getHostAddress(), imposter.port()).isPresent(), "not reachable on " + lan);
            }
        });
    }

    @TestFactory
    Stream<DynamicTest> anOffLoopbackClientNeedsAWildcardImposterAndLocalOnlyOff() {
        return gatedTo(ConformanceTransport.SPAWN, SPAWN_ONLY, "host(0.0.0.0) is reachable off loopback only with localOnly(false)", () -> {
            InetAddress lan = lanAddress();
            try (Rift localOnly = Rift.spawn(SpawnOptions.builder().build())) {
                Imposter wildcard = localOnly.create(imposter("wild").host("0.0.0.0").stub(onGet("/").willReturn(ok())));
                assertFalse(get(lan.getHostAddress(), wildcard.port()).isPresent(), "--local-only drops a non-loopback client");
            }
            try (Rift open = Rift.spawn(SpawnOptions.builder().localOnly(false).build())) {
                Imposter wildcard = open.create(imposter("wild").host("0.0.0.0").stub(onGet("/").willReturn(ok())));
                assertEquals(200, get(lan.getHostAddress(), wildcard.port()).orElseThrow());
            }
        });
    }

    /** This machine's first non-loopback, up IPv4 address; the test is skipped without one. */
    private static InetAddress lanAddress() throws SocketException {
        for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) {
                continue;
            }
            for (InetAddress address : Collections.list(nic.getInetAddresses())) {
                if (address instanceof Inet4Address && !address.isLoopbackAddress() && !address.isLinkLocalAddress()) {
                    return address;
                }
            }
        }
        assumeTrue(false, "no non-loopback IPv4 address to probe from");
        throw new IllegalStateException("unreachable");
    }

    /** The status, or empty when the connection is refused or dropped. */
    private static Optional<Integer> get(String host, int port) throws InterruptedException {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            return Optional.of(client.send(HttpRequest.newBuilder(URI.create("http://" + host + ":" + port + "/"))
                    .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode());
        } catch (IOException refusedOrDropped) {
            return Optional.empty();
        }
    }
}
