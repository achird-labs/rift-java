package io.github.achirdlabs.rift.error;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The error hierarchy is a cross-SDK contract (shared verbatim with rift-node / rift-scala): a sealed
 * {@code RiftException} over exactly five unchecked leaves, no {@code -Exception} suffix on the leaves.
 */
class ErrorHierarchyTest {

    @Test
    void riftExceptionIsSealedOverTheFiveLeaves() {
        Class<?> base = RiftException.class;
        assertTrue(base.isSealed(), "RiftException must be sealed");
        assertTrue(RuntimeException.class.isAssignableFrom(base), "RiftException must be unchecked");
        Set<String> leaves = Arrays.stream(base.getPermittedSubclasses())
                .map(Class::getSimpleName).collect(Collectors.toSet());
        assertEquals(Set.of("InvalidDefinition", "EngineUnavailable", "CommunicationError",
                "ImposterNotFound", "EngineError"), leaves);
    }

    @Test
    void leavesCarryTheirContract() {
        RiftException notFound = new ImposterNotFound(4545, "missing");
        assertEquals(4545, ((ImposterNotFound) notFound).port());
        RiftException engineError = new EngineError(503, "overloaded");
        assertEquals(503, ((EngineError) engineError).code());
        assertTrue(engineError instanceof RiftException);
    }

    /**
     * {@code -1} is the value the embedded transport publishes as {@code code()} for a failure that
     * never had an HTTP status. Pinning the literal makes a renumber a test failure rather than a
     * silent change to what callers already see; a rename fails compilation instead.
     */
    @Test
    void engineErrorPinsItsNonHttpSentinel() {
        assertEquals(-1, EngineError.NO_HTTP_STATUS);
    }
}
