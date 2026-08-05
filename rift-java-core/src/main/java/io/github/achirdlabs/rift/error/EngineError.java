package io.github.achirdlabs.rift.error;

/**
 * Any engine failure not covered by a more specific leaf, thrown from two places that a caller
 * tells apart by {@link #code()}:
 *
 * <ul>
 *   <li>a non-2xx admin API response — {@code code()} is that HTTP status;
 *   <li>a call into the embedded engine's C ABI that failed or returned an unusable reply —
 *       {@code code()} is {@link #NO_HTTP_STATUS}, because no HTTP exchange took place.
 * </ul>
 */
public final class EngineError extends RiftException {

    /**
     * The {@link #code()} of an engine failure that never had an HTTP status: the embedded transport
     * reaches the engine through a C ABI rather than HTTP, so no status exists to carry — a failure
     * there surfaces as a message only (via {@code rift_last_error}). Callers that read
     * {@code code()} as an HTTP status must treat this value as "not an HTTP status" rather than as
     * a status of its own.
     */
    public static final int NO_HTTP_STATUS = -1;

    private final int code;

    public EngineError(int code, String message) {
        super(message);
        this.code = code;
    }

    /** The HTTP status of the failing admin response, or {@link #NO_HTTP_STATUS} on the embedded C-ABI path. */
    public int code() {
        return code;
    }
}
