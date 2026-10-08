package io.github.achirdlabs.rift;

import io.github.achirdlabs.rift.error.CommunicationError;
import io.github.achirdlabs.rift.json.JsonValue;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterceptStatusTest {

    @Test
    void readsTheEnginesReport() {
        assertEquals(new InterceptStatus(9443, URI.create("http://0.0.0.0:9443")), InterceptStatus.fromJson(
                JsonValue.parse("{\"interceptPort\":9443,\"interceptUrl\":\"http://0.0.0.0:9443\"}"), "intercept status"));
    }

    @Test
    void refusesAReportMissingTheUrl() {
        CommunicationError e = assertThrows(CommunicationError.class,
                () -> InterceptStatus.fromJson(JsonValue.parse("{\"interceptPort\":9443}"), "intercept status"));
        assertEquals("rift engine's intercept status is missing 'interceptPort'/'interceptUrl'; it has [interceptPort]",
                e.getMessage());
    }

    @Test
    void refusesANonStringUrlAndANonObjectBody() {
        assertThrows(CommunicationError.class, () -> InterceptStatus.fromJson(
                JsonValue.parse("{\"interceptPort\":9443,\"interceptUrl\":9443}"), "intercept status"));
        assertThrows(CommunicationError.class, () -> InterceptStatus.fromJson(JsonValue.parse("[]"), "intercept status"));
    }

    @Test
    void namesTheKeysNeverTheBodyWhichCanCarryAPrivateKey() {
        CommunicationError e = assertThrows(CommunicationError.class, () -> InterceptStatus.fromJson(
                JsonValue.parse("{\"caKeyPem\":\"SECRET-KEY\"}"), "intercept start response"));
        assertTrue(e.getMessage().contains("caKeyPem"), e.getMessage());
        assertFalse(e.getMessage().contains("SECRET-KEY"), e.getMessage());
    }

    @Test
    void refusesAUrlWithNoHostOrThatIsNotAUriAndAPortOutOfRange() {
        CommunicationError noHost = assertThrows(CommunicationError.class, () -> InterceptStatus.fromJson(
                JsonValue.parse("{\"interceptPort\":9443,\"interceptUrl\":\"http:foo\"}"), "intercept status"));
        assertEquals("rift engine's intercept status has an 'interceptUrl' with no host: http:foo", noHost.getMessage());
        assertThrows(CommunicationError.class, () -> InterceptStatus.fromJson(
                JsonValue.parse("{\"interceptPort\":9443,\"interceptUrl\":\"http://bad host\"}"), "intercept status"));
        CommunicationError range = assertThrows(CommunicationError.class, () -> InterceptStatus.fromJson(
                JsonValue.parse("{\"interceptPort\":70000,\"interceptUrl\":\"http://127.0.0.1:70000\"}"), "intercept status"));
        assertEquals("rift engine's intercept status has an 'interceptPort' out of range: 70000", range.getMessage());
    }
}
