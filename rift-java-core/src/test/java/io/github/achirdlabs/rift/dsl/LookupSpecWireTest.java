package io.github.achirdlabs.rift.dsl;

import org.junit.jupiter.api.Test;

import static io.github.achirdlabs.rift.dsl.RiftDsl.jsonPath;
import static io.github.achirdlabs.rift.dsl.RiftDsl.lookupKey;
import static io.github.achirdlabs.rift.dsl.RiftDsl.lookupKeyFromHeader;
import static io.github.achirdlabs.rift.dsl.RiftDsl.lookupKeyFromQuery;
import static io.github.achirdlabs.rift.dsl.RiftDsl.regex;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The {@code lookup} grammar the engine accepts (#290): {@code key.index}, {@code csv.delimiter}, an object {@code key.from}. */
class LookupSpecWireTest {

    @Test
    void theTwoArgumentFormIsUnchanged() {
        assertEquals("{\"key\":{\"from\":\"body\",\"using\":{\"method\":\"jsonpath\",\"selector\":\"$.code\"}},"
                        + "\"fromDataSource\":{\"csv\":{\"path\":\"data/products.csv\",\"keyColumn\":\"code\"}},\"into\":\"$ROW\"}",
                lookupKey("body").using(jsonPath("$.code")).fromCsv("data/products.csv", "code").into("$ROW").build().toJson());
    }

    @Test
    void indexIsEmittedUnderTheKey() {
        assertEquals("{\"key\":{\"from\":\"path\",\"using\":{\"method\":\"regex\",\"selector\":\"\\\\d+\"},\"index\":1},"
                        + "\"fromDataSource\":{\"csv\":{\"path\":\"p.csv\",\"keyColumn\":\"id\"}},\"into\":\"$ROW\"}",
                lookupKey("path").using(regex("\\d+")).index(1).fromCsv("p.csv", "id").into("$ROW").build().toJson());
    }

    @Test
    void aNegativeIndexIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> lookupKey("path").index(-1));
        assertEquals("lookup key index must be >= 0, was -1", e.getMessage());
    }

    @Test
    void aDelimiterIsSentOnlyWhenGiven() {
        assertEquals("{\"key\":{\"from\":\"body\",\"using\":{\"method\":\"jsonpath\",\"selector\":\"$.code\"}},"
                        + "\"fromDataSource\":{\"csv\":{\"path\":\"p.csv\",\"keyColumn\":\"code\",\"delimiter\":\";\"}},\"into\":\"$ROW\"}",
                lookupKey("body").using(jsonPath("$.code")).fromCsv("p.csv", "code", ';').into("$ROW").build().toJson());
    }

    @Test
    void theKeyCanComeFromAHeaderOrAQueryParameter() {
        assertEquals("{\"key\":{\"from\":{\"headers\":\"X-Tenant\"},\"using\":{\"method\":\"regex\",\"selector\":\".+\"}},"
                        + "\"fromDataSource\":{\"csv\":{\"path\":\"t.csv\",\"keyColumn\":\"tenant\"}},\"into\":\"$T\"}",
                lookupKeyFromHeader("X-Tenant").using(regex(".+")).fromCsv("t.csv", "tenant").into("$T").build().toJson());
        assertEquals("{\"key\":{\"from\":{\"query\":\"sku\"},\"using\":{\"method\":\"regex\",\"selector\":\".+\"}},"
                        + "\"fromDataSource\":{\"csv\":{\"path\":\"t.csv\",\"keyColumn\":\"sku\"}},\"into\":\"$T\"}",
                lookupKeyFromQuery("sku").using(regex(".+")).fromCsv("t.csv", "sku").into("$T").build().toJson());
    }

    @Test
    void theMissingPartErrorNamesTheKeyWhateverItsForm() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> lookupKeyFromHeader("X-Tenant").fromCsv("t.csv", "tenant").into("$T").build());
        assertEquals("lookupKey({\"headers\":\"X-Tenant\"}) requires .using(...)", e.getMessage());
    }

    @Test
    void aHalfSurrogateDelimiterIsRefusedRatherThanSentAsAQuestionMark() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> lookupKey("body").fromCsv("p.csv", "code", '\uD83D'));
        assertEquals("csv delimiter must be a whole character, not half of a surrogate pair (U+D83D)", e.getMessage());
    }

    @Test
    void theTwoArgumentFromCsvClearsAnEarlierDelimiter() {
        assertEquals(lookupKey("body").using(jsonPath("$.code")).fromCsv("p.csv", "code").into("$ROW").build().toJson(),
                lookupKey("body").using(jsonPath("$.code")).fromCsv("p.csv", "code", ';').fromCsv("p.csv", "code")
                        .into("$ROW").build().toJson());
    }

    @Test
    void aMultiCharacterDelimiterIsSentVerbatim() {
        assertEquals("{\"key\":{\"from\":\"body\",\"using\":{\"method\":\"jsonpath\",\"selector\":\"$.code\"}},"
                        + "\"fromDataSource\":{\"csv\":{\"path\":\"p.csv\",\"keyColumn\":\"code\",\"delimiter\":\"||\"}},\"into\":\"$ROW\"}",
                lookupKey("body").using(jsonPath("$.code")).fromCsv("p.csv", "code", "||").into("$ROW").build().toJson());
    }

    @Test
    void aOneCharacterStringDelimiterIsTheSameAsTheCharForm() {
        assertEquals(lookupKey("body").using(jsonPath("$.code")).fromCsv("p.csv", "code", ';').into("$ROW").build().toJson(),
                lookupKey("body").using(jsonPath("$.code")).fromCsv("p.csv", "code", ";").into("$ROW").build().toJson());
    }

    @Test
    void anEmptyDelimiterIsRefusedSinceTheTwoArgumentFormAlreadyMeansTheDefault() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> lookupKey("body").fromCsv("p.csv", "code", ""));
        assertEquals("csv delimiter must not be empty; use fromCsv(path, keyColumn) for the engine's default ','",
                e.getMessage());
    }

    @Test
    void aNullDelimiterIsRefused() {
        assertThrows(NullPointerException.class, () -> lookupKey("body").fromCsv("p.csv", "code", (String) null));
    }

    @Test
    void anUnpairedSurrogateInAStringDelimiterIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> lookupKey("body").fromCsv("p.csv", "code", "|\uD83D"));
        assertEquals("csv delimiter must be a whole character, not half of a surrogate pair (U+D83D)", e.getMessage());
    }

    @Test
    void aSupplementaryCharacterDelimiterIsAccepted() {
        assertTrue(lookupKey("body").using(jsonPath("$.code")).fromCsv("p.csv", "code", "\uD83D\uDE00")
                .into("$ROW").build().toJson().contains("\"delimiter\":\"\uD83D\uDE00\""));
    }
}
