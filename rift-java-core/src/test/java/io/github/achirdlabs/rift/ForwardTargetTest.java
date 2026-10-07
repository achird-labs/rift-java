package io.github.achirdlabs.rift;

import io.github.achirdlabs.rift.json.JsonValue;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The forms {@code forward(host, target)} accepts, and the engine's {@code ForwardTarget} wire each
 * becomes (#262). A loopback {@code http} target keeps the port-only wire every engine reads; a named
 * host or {@code https} needs rift 0.20.0.
 */
class ForwardTargetTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "9443                  | {\"port\":9443}",
            "localhost:9443        | {\"port\":9443}",
            "127.0.0.1:9443        | {\"port\":9443}",
            "[::1]:9443            | {\"port\":9443}",
            "http://localhost:9443 | {\"port\":9443}",
            "http://127.0.0.1:1    | {\"port\":1}",
            "http://[::1]:9443     | {\"port\":9443}",
            "LOCALHOST:9443        | {\"port\":9443}",
            "0080                  | {\"port\":80}",
            "65535                 | {\"port\":65535}",
    })
    void aLoopbackHttpTargetKeepsThePortOnlyWire(String target, String wire) {
        ForwardTarget parsed = ForwardTarget.parse(target);
        assertEquals(JsonValue.parse(wire), parsed.toJson());
        assertTrue(!parsed.needsHostOrScheme(), "no engine-version gate for the port-only wire");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "mock-svc:4600                 | {\"port\":4600,\"host\":\"mock-svc\"}",
            "partner.example.com:80        | {\"port\":80,\"host\":\"partner.example.com\"}",
            "10.0.0.7:8080                 | {\"port\":8080,\"host\":\"10.0.0.7\"}",
            "[fd00::7]:4600                | {\"port\":4600,\"host\":\"[fd00::7]\"}",
            "http://mock_svc:4600          | {\"port\":4600,\"host\":\"mock_svc\"}",
            "https://mock-svc:8443         | {\"port\":8443,\"host\":\"mock-svc\",\"scheme\":\"https\"}",
            "HTTPS://mock-svc:8443         | {\"port\":8443,\"host\":\"mock-svc\",\"scheme\":\"https\"}",
    })
    void aNamedHostOrHttpsIsSentAsWritten(String target, String wire) {
        ForwardTarget parsed = ForwardTarget.parse(target);
        assertEquals(JsonValue.parse(wire), parsed.toJson());
        assertTrue(parsed.needsHostOrScheme());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            // The host is the TLS server name the upstream certificate is verified against: eliding it
            // would make the engine dial 127.0.0.1 and check a 'localhost' certificate against an IP.
            "https://localhost:9443 | {\"port\":9443,\"host\":\"localhost\",\"scheme\":\"https\"}",
            "https://127.0.0.1:9443 | {\"port\":9443,\"host\":\"127.0.0.1\",\"scheme\":\"https\"}",
            "https://[::1]:9443     | {\"port\":9443,\"host\":\"[::1]\",\"scheme\":\"https\"}",
    })
    void anHttpsLoopbackTargetKeepsItsHost(String target, String wire) {
        assertEquals(JsonValue.parse(wire), ForwardTarget.parse(target).toJson());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "mock-svc",                    // no port
            "http://mock-svc",             // no port
            "mock-svc:0",
            "mock-svc:65536",
            "mock-svc:-1",
            "mock-svc:http",
            "mock-svc:+80",
            "mock-svc:123456",
            "https://9443",                // a URL needs a host
            "http://mock-svc:4600/",
            ":4600",                       // empty host
            "bad host:1",
            "bad/host:1",
            "ftp://mock-svc:21",
            "http://mock-svc:4600/path",
            "http://mock-svc:4600?q=1",
            "http://mock-svc:4600#frag",
            "http://user@mock-svc:4600",
            "[::1:4600",                   // unclosed bracket
            "[]:4600",
            "fd00::7:4600",                // an unbracketed IPv6 literal is ambiguous
            "[fe80::1%eth0]:4600",         // the engine refuses a zone id
    })
    void aMalformedTargetIsRefused(String target) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ForwardTarget.parse(target));
        assertTrue(e.getMessage().contains("forward target"), e.getMessage());
    }
}
