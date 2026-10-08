package io.github.achirdlabs.rift;

import io.github.achirdlabs.rift.error.CommunicationError;
import io.github.achirdlabs.rift.json.JsonNumber;
import io.github.achirdlabs.rift.json.JsonObject;
import io.github.achirdlabs.rift.json.JsonString;
import io.github.achirdlabs.rift.json.JsonValue;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;

/**
 * The intercept listener an engine reports running ({@code GET /intercept}).
 *
 * @param port      the port the listener is bound to, on the engine's side
 * @param engineUrl the listener's URL as the engine reports it — its own bind address, which can be
 *                  a wildcard ({@code 0.0.0.0}) or a container-internal port, so not necessarily
 *                  where this client reaches it; {@link Intercept#address()} is that
 */
public record InterceptStatus(int port, URI engineUrl) {

    public InterceptStatus {
        Objects.requireNonNull(engineUrl, "engineUrl");
    }

    /**
     * Reads an engine's {@code {interceptPort, interceptUrl}} report. {@code what} names the
     * response in the error, which lists the keys and never the body: a start response can carry a
     * generated CA's private key.
     */
    static InterceptStatus fromJson(JsonValue report, String what) {
        if (!(report instanceof JsonObject obj)
                || !(obj.get("interceptPort") instanceof JsonNumber port)
                || !(obj.get("interceptUrl") instanceof JsonString url)) {
            throw new CommunicationError("rift engine's " + what + " is missing 'interceptPort'/'interceptUrl'; it has "
                    + (report instanceof JsonObject o ? o.fields().keySet() : report.getClass().getSimpleName()));
        }
        URI engineUrl;
        try {
            engineUrl = new URI(url.value());
        } catch (URISyntaxException e) {
            throw new CommunicationError("rift engine's " + what + " has an 'interceptUrl' that is not a URI: " + e.getMessage(), e);
        }
        if (engineUrl.getHost() == null) {
            throw new CommunicationError("rift engine's " + what + " has an 'interceptUrl' with no host: " + engineUrl);
        }
        int listenerPort = port.asInt();
        if (listenerPort < 1 || listenerPort > 65_535) {
            throw new CommunicationError("rift engine's " + what + " has an 'interceptPort' out of range: " + listenerPort);
        }
        return new InterceptStatus(listenerPort, engineUrl);
    }
}
