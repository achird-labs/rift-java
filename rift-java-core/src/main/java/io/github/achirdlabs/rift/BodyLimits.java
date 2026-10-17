package io.github.achirdlabs.rift;

import java.util.OptionalLong;

/**
 * The checks rift 0.22.0 applies to its body and journal limits (rift#1439, rift#1441), run where
 * the SDK takes them so a bad combination fails at the builder rather than at engine start.
 */
final class BodyLimits {

    static final String SINCE = "0.22.0";

    /** The engine's {@code maxBodySize} when none is set: 512 MiB. */
    static final long DEFAULT_MAX_BODY_SIZE = 512L << 20;

    /** The engine's {@code bodyMemoryBudget} when none is set: 1 GiB. */
    static final long DEFAULT_BUDGET = 1L << 30;

    private BodyLimits() {
    }

    static long maxBodySize(long bytes) {
        if (bytes < 1) {
            throw new IllegalArgumentException("maxBodySize must be at least 1 byte, was " + bytes);
        }
        return bytes;
    }

    static long bodyMemoryBudget(long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("bodyMemoryBudget must be 0 (unbounded) or more bytes, was " + bytes);
        }
        return bytes;
    }

    static long journalMaxBytes(long bytes) {
        if (bytes < 1) {
            throw new IllegalArgumentException("journalMaxBytes must be at least 1 byte, was " + bytes);
        }
        return bytes;
    }

    /**
     * The engine refuses a non-zero budget below the body ceiling, and fills in whichever of the two
     * is unset with its own default (512 MiB ceiling, 1 GiB budget) before comparing them.
     */
    static void requireBudgetCoversCeiling(OptionalLong maxBodySize, OptionalLong bodyMemoryBudget) {
        if (bodyMemoryBudget.isPresent()) {
            long budget = bodyMemoryBudget.getAsLong();
            long ceiling = maxBodySize.orElse(DEFAULT_MAX_BODY_SIZE);
            if (budget != 0 && budget < ceiling) {
                throw new IllegalArgumentException(maxBodySize.isPresent()
                        ? "bodyMemoryBudget (" + budget + ") is below maxBodySize (" + ceiling
                                + "), which the engine refuses; raise it, or set 0 for no budget"
                        : "bodyMemoryBudget (" + budget + ") is below the engine's default maxBodySize (" + ceiling
                                + "), which the engine refuses; raise it, lower maxBodySize, or set 0 for no budget");
            }
        } else if (maxBodySize.isPresent() && maxBodySize.getAsLong() > DEFAULT_BUDGET) {
            throw new IllegalArgumentException("maxBodySize (" + maxBodySize.getAsLong() + ") is above the engine's "
                    + "default bodyMemoryBudget (" + DEFAULT_BUDGET + "), which the engine refuses; set bodyMemoryBudget too");
        }
    }
}
