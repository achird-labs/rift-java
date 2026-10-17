package io.github.achirdlabs.rift;

import io.github.achirdlabs.rift.dsl.RequestField;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The {@link RecordSpec} builder contract (#25 Part 1). */
class RecordSpecTest {

    @Test
    void aBlankIgnoredHeaderIsRefusedAtTheBuilderWhateverIsGenerated() {
        assertThrows(IllegalArgumentException.class, () -> RecordSpec.builder().ignoreHeaders("Date", " "));
    }

    @Test
    void defaultsMatchTheDocumentedRecorder() {
        RecordSpec spec = RecordSpec.builder().build();
        assertEquals(RecordMode.ONCE, spec.mode(), "default mode is ONCE (proxyOnce)");
        assertEquals(List.of(RequestField.METHOD, RequestField.PATH), spec.generators(),
                "default predicate generators are METHOD, PATH");
        assertTrue(spec.addWaitBehavior(), "wait behavior captured by default");
        assertTrue(spec.ignoreHeaders().isEmpty(), "no ignored headers by default");
    }

    @Test
    void builderOverridesEveryField() {
        RecordSpec spec = RecordSpec.builder()
                .mode(RecordMode.ALWAYS)
                .generateBy(RequestField.METHOD, RequestField.PATH, RequestField.HEADERS)
                .addWaitBehavior(false)
                .ignoreHeaders("Date", "X-Request-Id")
                .build();
        assertEquals(RecordMode.ALWAYS, spec.mode());
        assertEquals(List.of(RequestField.METHOD, RequestField.PATH, RequestField.HEADERS), spec.generators());
        assertFalse(spec.addWaitBehavior());
        assertEquals(List.of("Date", "X-Request-Id"), spec.ignoreHeaders());
    }

    @Test
    void generatorsAndIgnoreHeadersAreDefensivelyCopied() {
        RecordSpec spec = RecordSpec.builder()
                .generateBy(RequestField.METHOD)
                .ignoreHeaders("Date")
                .build();
        assertEquals(List.of(RequestField.METHOD), spec.generators());
        assertEquals(List.of("Date"), spec.ignoreHeaders());
    }
}
