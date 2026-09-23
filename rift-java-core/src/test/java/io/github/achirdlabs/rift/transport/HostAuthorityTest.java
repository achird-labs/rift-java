package io.github.achirdlabs.rift.transport;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Building an {@code http://host:port} URI for a host rift 0.18.0 binds, including a bare IPv6
 * literal, which {@link URI} cannot parse unbracketed (#232).
 */
class HostAuthorityTest {

    @Test
    void bracketsABareIpv6Literal() {
        assertEquals("[::1]", HostAuthority.bracketed("::1"));
        assertEquals("[::]", HostAuthority.bracketed("::"));
        assertEquals("[fe80::1%2]", HostAuthority.bracketed("fe80::1%2"));
        assertEquals("[::ffff:127.0.0.1]", HostAuthority.bracketed("::ffff:127.0.0.1"));
    }

    @Test
    void leavesEverythingElseAlone() {
        assertEquals("[::1]", HostAuthority.bracketed("[::1]"));
        assertEquals("127.0.0.1", HostAuthority.bracketed("127.0.0.1"));
        assertEquals("localhost", HostAuthority.bracketed("localhost"));
        assertEquals("0.0.0.0", HostAuthority.bracketed("0.0.0.0"));
    }

    @Test
    void theUriHasAHostAndPortForEverySpelling() {
        URI bare = HostAuthority.httpUri("::1", 4545);
        assertEquals("http://[::1]:4545", bare.toString());
        assertEquals("[::1]", bare.getHost());
        assertEquals(4545, bare.getPort());

        assertEquals("http://[::1]:4545", HostAuthority.httpUri("[::1]", 4545).toString());
        assertEquals("http://127.0.0.1:2525", HostAuthority.httpUri("127.0.0.1", 2525).toString());
        assertEquals("http://localhost:80", HostAuthority.httpUri("localhost", 80).toString());
        assertEquals("[fe80::1%2]", HostAuthority.httpUri("fe80::1%2", 1).getHost());
    }

    @Test
    void nullIsRejected() {
        assertThrows(NullPointerException.class, () -> HostAuthority.bracketed(null));
    }
}
