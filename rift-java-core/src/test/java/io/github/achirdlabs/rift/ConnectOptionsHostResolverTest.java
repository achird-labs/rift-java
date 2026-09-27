package io.github.achirdlabs.rift;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The imposter's protocol, not the admin listener's scheme, is an imposter URI's scheme (#250). */
class ConnectOptionsHostResolverTest {

    @Test
    void theDefaultResolverUsesTheImposterProtocolOnTheAdminHost() {
        HostResolver resolver = ConnectOptions.builder(URI.create("http://10.0.0.5:2525")).build().hostResolver();
        assertEquals("https://10.0.0.5:4545", resolver.resolve("https", 4545).toString());
        assertEquals("http://10.0.0.5:4545", resolver.resolve("http", 4545).toString());
    }

    @Test
    void anHttpsAdminListenerDoesNotMakeAnHttpImposterHttps() {
        HostResolver resolver = ConnectOptions.builder(URI.create("https://admin.test:2525")).build().hostResolver();
        assertEquals("http://admin.test:4545", resolver.resolve("http", 4545).toString());
    }

    @Test
    void theDefaultResolverKeepsAnIpv6AdminHostBracketed() {
        HostResolver resolver = ConnectOptions.builder(URI.create("http://[::1]:2525")).build().hostResolver();
        assertEquals("https://[::1]:4545", resolver.resolve("https", 4545).toString());
    }

    @Test
    void aProtocolAwareResolverIsToldTheProtocol() {
        List<String> seen = new ArrayList<>();
        HostResolver resolver = ConnectOptions.builder(URI.create("http://127.0.0.1:2525"))
                .hostResolver((protocol, port) -> {
                    seen.add(protocol + ":" + port);
                    return URI.create(protocol + "://sut-host:" + (port + 10000));
                })
                .build().hostResolver();
        assertEquals("https://sut-host:14545", resolver.resolve("https", 4545).toString());
        assertEquals(List.of("https:4545"), seen);
    }

    @Test
    void aPortOnlyResolverIsUsedVerbatimSchemeIncluded() {
        IntFunction<URI> portOnly = port -> URI.create("http://tls-terminator:" + port);
        HostResolver resolver = ConnectOptions.builder(URI.create("http://127.0.0.1:2525"))
                .hostResolver(portOnly).build().hostResolver();
        assertEquals("http://tls-terminator:4545", resolver.resolve("https", 4545).toString());
    }

    @Test
    void aNullResolverIsRejected() {
        ConnectOptions.Builder builder = ConnectOptions.builder(URI.create("http://127.0.0.1:2525"));
        assertThrows(NullPointerException.class, () -> builder.hostResolver((HostResolver) null));
        assertThrows(NullPointerException.class, () -> builder.hostResolver((IntFunction<URI>) null));
    }
}
