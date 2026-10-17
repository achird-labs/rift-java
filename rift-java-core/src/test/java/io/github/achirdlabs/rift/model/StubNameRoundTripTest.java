package io.github.achirdlabs.rift.model;

import io.github.achirdlabs.rift.json.JsonString;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A stub's {@code name} is a typed label the engine keeps and prints first (#310, rift#1362). */
class StubNameRoundTripTest {

    @Test
    void aNameParsesIntoTheTypedComponentAndPrintsFirst() {
        Stub stub = Stub.fromJson("{\"predicates\":[],\"name\":\"s1\",\"responses\":[]}");
        assertEquals(Optional.of("s1"), stub.name());
        assertFalse(stub.extra().containsKey("name"), "name is modeled, not extra: " + stub.extra());
        assertTrue(stub.toJson().startsWith("{\"name\":\"s1\""), stub.toJson());
    }

    @Test
    void anUnnamedStubEmitsNoName() {
        Stub stub = Stub.fromJson("{\"predicates\":[],\"responses\":[]}");
        assertEquals(Optional.empty(), stub.name());
        assertFalse(stub.toJson().contains("\"name\""), stub.toJson());
    }

    @Test
    void nameIsAModeledKeySoExtraRefusesIt() {
        Stub stub = new Stub(List.of(), List.of());
        assertThrows(WireFormatException.class, () -> stub.withExtra("name", new JsonString("s1")));
    }

    @Test
    void withNameLabelsACopy() {
        Stub stub = new Stub(List.of(), List.of()).withName("s1");
        assertEquals(Optional.of("s1"), stub.name());
        assertEquals(Optional.of("s2"), stub.withName("s2").name());
    }

    @Test
    void thePreviousCanonicalConstructorStillBuildsAnUnnamedStub() {
        Stub stub = new Stub(Optional.of("sc"), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of("id1"), Optional.empty(), List.of(), List.of(), Optional.empty(), Optional.empty(), Map.of());
        assertEquals(Optional.empty(), stub.name());
        assertEquals(Optional.of("sc"), stub.scenarioName());
        assertEquals(Optional.of("id1"), stub.id());
    }
}
