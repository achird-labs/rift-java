package io.github.achirdlabs.rift;

import io.github.achirdlabs.rift.json.JsonValue;
import io.github.achirdlabs.rift.transport.RiftTransport;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.net.URI;

import static io.github.achirdlabs.rift.dsl.RiftDsl.imposter;
import static io.github.achirdlabs.rift.dsl.RiftDsl.ok;
import static io.github.achirdlabs.rift.dsl.RiftDsl.onGet;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** A bare IPv6 host produces usable imposter and intercept URIs, as rift 0.18.0 binds one (#232). */
class Ipv6HostUriTest {

    private static RiftTransport transport() {
        return (RiftTransport) Proxy.newProxyInstance(
                RiftTransport.class.getClassLoader(), new Class<?>[] {RiftTransport.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "createImposter" -> JsonValue.parse("{\"port\": 4545}");
                    case "adminUri" -> URI.create("http://[::1]:2525");
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void anEmbeddedImposterOnABareIpv6HostHasAUsableUri() {
        Rift rift = RiftImpl.embedded(transport(),
                EmbeddedOptions.builder().adminHost("::1").versionCheck(VersionCheck.OFF).build(), () -> { });
        URI uri = rift.create(imposter("v6").stub(onGet("/").willReturn(ok()))).uri();
        assertEquals("http://[::1]:4545", uri.toString());
        assertEquals("[::1]", uri.getHost());
        assertEquals(4545, uri.getPort());
    }

    @Test
    void anAttachedInterceptOnABareIpv6HostHasAUsableUri() {
        InterceptImpl intercept = new InterceptImpl(transport(), "::1", 8888);
        assertEquals("http://[::1]:8888", intercept.uri().toString());
        assertEquals(8888, intercept.address().getPort());
    }
}
