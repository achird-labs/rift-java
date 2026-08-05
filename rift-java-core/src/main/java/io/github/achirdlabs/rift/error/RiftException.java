package io.github.achirdlabs.rift.error;

/**
 * The root of the rift-java error hierarchy: a sealed, unchecked exception with exactly five
 * leaves. This shape is a cross-SDK contract shared verbatim with rift-node / rift-scala, so the
 * leaf set and their names are fixed — no {@code -Exception} suffix on the leaves, unlike this
 * base class.
 *
 * <ul>
 *   <li>{@link InvalidDefinition} — the engine rejected a definition (HTTP 400).
 *   <li>{@link EngineUnavailable} — the engine could not be reached at all (connection refused,
 *       spawn failure, a failed version preflight).
 *   <li>{@link CommunicationError} — the engine answered successfully but the response body could
 *       not be parsed.
 *   <li>{@link ImposterNotFound} — an operation referenced a port with no such imposter.
 *   <li>{@link EngineError} — any other engine failure: a non-2xx response, or an embedded C-ABI
 *       call that yielded no usable result.
 * </ul>
 */
public abstract sealed class RiftException extends RuntimeException
        permits InvalidDefinition, EngineUnavailable, CommunicationError, ImposterNotFound, EngineError {

    protected RiftException(String message) {
        super(message);
    }

    protected RiftException(String message, Throwable cause) {
        super(message, cause);
    }
}
