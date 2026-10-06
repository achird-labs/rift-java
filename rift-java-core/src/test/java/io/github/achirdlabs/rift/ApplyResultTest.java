package io.github.achirdlabs.rift;

import io.github.achirdlabs.rift.error.CommunicationError;
import io.github.achirdlabs.rift.json.JsonValue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ApplyResult#read} against the report shapes the engine actually sends: port arrays (never
 * counts) on every surface, HTTP's string failures and the FFI's object ones, and the HTTP-only
 * {@code warnings} / {@code intercept} additions.
 */
class ApplyResultTest {

    @Test
    void portArraysAreReadAsPorts() {
        ApplyResult result = read("{\"message\":\"Reloaded 3 imposter(s)\",\"created\":[4545,4546],"
                + "\"replaced\":[4547],\"stubPatched\":[],\"deleted\":[4600]}");

        assertEquals(List.of(4545, 4546), result.created());
        assertEquals(List.of(4547), result.replaced());
        assertEquals(List.of(), result.stubPatched());
        assertEquals(List.of(4600), result.deleted());
        assertEquals(List.of(), result.failed());
        assertEquals(List.of(), result.warnings());
        assertEquals(Optional.empty(), result.intercept());
        assertFalse(result.changedNothing());
    }

    @Test
    void anUnchangedConfigReportsNothingChanged() {
        ApplyResult result = read("{\"created\":[],\"replaced\":[],\"stubPatched\":[],\"deleted\":[]}");

        assertTrue(result.changedNothing());
    }

    @Test
    void absentPortFieldsAreEmpty() {
        assertTrue(read("{\"message\":\"No config source configured; nothing to reload\"}").changedNothing());
    }

    @Test
    void aCountWhereTheEngineSendsPortsIsRefused() {
        // The engine has never sent a count; a number here cannot be turned into the ports it stands for.
        CommunicationError e = assertThrows(CommunicationError.class,
                () -> read("{\"created\":2,\"replaced\":[],\"stubPatched\":[],\"deleted\":[]}"));
        assertTrue(e.getMessage().contains("created"), e.getMessage());
    }

    @Test
    void aPortListHoldingSomethingOtherThanAPortIsRefused() {
        assertThrows(CommunicationError.class, () -> read("{\"created\":[\"4545\"]}"));
        assertThrows(CommunicationError.class, () -> read("{\"created\":[70000]}"));
        assertThrows(CommunicationError.class, () -> read("{\"created\":[45.5]}"));
    }

    @Test
    void httpFailuresAreReadFromTheirPortPrefix() {
        ApplyResult result = read("{\"created\":[19477],\"replaced\":[],\"stubPatched\":[],\"deleted\":[],"
                + "\"failed\":[\"19478: Address already in use\",\"auto-assign: no free port\"]}");

        assertEquals(List.of(
                new ApplyResult.ApplyFailure(OptionalInt.of(19478), "Address already in use"),
                new ApplyResult.ApplyFailure(OptionalInt.empty(), "no free port")), result.failed());
        assertEquals(List.of(19477), result.created());
    }

    @Test
    void anUnrecognisedFailureKeepsItsWholeText() {
        assertEquals(List.of(new ApplyResult.ApplyFailure(OptionalInt.empty(), "something new: went wrong")),
                read("{\"failed\":[\"something new: went wrong\"]}").failed());
    }

    @Test
    void aFailurePrefixThatCannotBeAPortKeepsTheWholeText() {
        assertEquals(List.of(
                new ApplyResult.ApplyFailure(OptionalInt.empty(), "99999999999: overflow"),
                new ApplyResult.ApplyFailure(OptionalInt.empty(), "70000: out of range"),
                new ApplyResult.ApplyFailure(OptionalInt.empty(), "abc: not a port"),
                new ApplyResult.ApplyFailure(OptionalInt.empty(), "auto-assigned")),
                read("{\"failed\":[\"99999999999: overflow\",\"70000: out of range\",\"abc: not a port\","
                        + "\"0: auto-assigned\"]}").failed());
    }

    @Test
    void eachPortFieldCountsAsAChange() {
        assertFalse(read("{\"created\":[1]}").changedNothing());
        assertFalse(read("{\"replaced\":[1]}").changedNothing());
        assertFalse(read("{\"stubPatched\":[1]}").changedNothing());
        assertFalse(read("{\"deleted\":[1]}").changedNothing());
        assertTrue(read("{\"failed\":[\"4545: bind failed\"]}").changedNothing(),
                "failures are not changes: changedNothing() says nothing about them");
    }

    @Test
    void malformedFieldsAreRefused() {
        assertThrows(CommunicationError.class, () -> read("{\"failed\":\"4545: x\"}"));
        assertThrows(CommunicationError.class, () -> read("{\"failed\":[4545]}"));
        assertThrows(CommunicationError.class, () -> read("{\"failed\":[{\"port\":4545}]}"));
        assertThrows(CommunicationError.class, () -> read("{\"failed\":[{\"error\":\"x\"}]}"));
        assertThrows(CommunicationError.class, () -> read("{\"warnings\":\"x\"}"));
        assertThrows(CommunicationError.class, () -> read("{\"warnings\":[1]}"));
        assertThrows(CommunicationError.class, () -> read("{\"intercept\":[]}"));
        assertThrows(CommunicationError.class, () -> read("{\"created\":[-1]}"));
        assertEquals(List.of(0, 65535), read("{\"created\":[0,65535]}").created());
        assertThrows(CommunicationError.class, () -> read("{\"created\":[65536]}"));
    }

    @Test
    void ffiFailuresAreReadFromTheirObjects() {
        ApplyResult result = read("{\"created\":[],\"replaced\":[],\"stubPatched\":[],\"deleted\":[],"
                + "\"failed\":[{\"port\":4545,\"error\":\"bind failed\"},{\"port\":0,\"error\":\"auto-assign failed\"}]}");

        assertEquals(List.of(
                new ApplyResult.ApplyFailure(OptionalInt.of(4545), "bind failed"),
                new ApplyResult.ApplyFailure(OptionalInt.empty(), "auto-assign failed")), result.failed());
    }

    @Test
    void warningsAreKept() {
        ApplyResult result = read("{\"created\":[],\"replaced\":[],\"stubPatched\":[],\"deleted\":[],"
                + "\"warnings\":[\"the config file's `intercept` block is applied at startup only\"]}");

        assertEquals(List.of("the config file's `intercept` block is applied at startup only"), result.warnings());
    }

    @Test
    void interceptCountsAreKept() {
        ApplyResult result = read("{\"created\":[],\"replaced\":[],\"stubPatched\":[],\"deleted\":[],"
                + "\"intercept\":{\"rulesSeeded\":2,\"rulesRuntime\":5}}");

        assertEquals(Optional.of(new ApplyResult.InterceptCounts(2, 5)), result.intercept());
    }

    @Test
    void aMalformedInterceptObjectIsRefused() {
        assertThrows(CommunicationError.class, () -> read("{\"intercept\":{\"rulesSeeded\":2}}"));
    }

    @Test
    void aBodyThatIsNotAnObjectIsRefused() {
        assertThrows(CommunicationError.class, () -> read("[]"));
    }

    private static ApplyResult read(String json) {
        return ApplyResult.read(JsonValue.parse(json));
    }
}
