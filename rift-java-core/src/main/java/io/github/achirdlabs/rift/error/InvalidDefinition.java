package io.github.achirdlabs.rift.error;

/**
 * A definition was rejected: either by the engine (HTTP 400), or by this SDK before it was sent,
 * when the target wire format cannot carry what the definition asks for — see
 * {@link io.github.achirdlabs.rift.Intercept#serve}, whose action is narrower than the response
 * builder it accepts.
 */
public final class InvalidDefinition extends RiftException {

    public InvalidDefinition(String message) {
        super(message);
    }

    public InvalidDefinition(String message, Throwable cause) {
        super(message, cause);
    }
}
