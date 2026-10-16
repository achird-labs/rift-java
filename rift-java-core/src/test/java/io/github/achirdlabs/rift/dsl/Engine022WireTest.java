package io.github.achirdlabs.rift.dsl;

import io.github.achirdlabs.rift.model.Stub;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.github.achirdlabs.rift.dsl.RiftDsl.copyFrom;
import static io.github.achirdlabs.rift.dsl.RiftDsl.imposter;
import static io.github.achirdlabs.rift.dsl.RiftDsl.lookupKey;
import static io.github.achirdlabs.rift.dsl.RiftDsl.ok;
import static io.github.achirdlabs.rift.dsl.RiftDsl.onGet;
import static io.github.achirdlabs.rift.dsl.RiftDsl.xPath;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The DSL wire shapes rift 0.22.0 reads or refuses: XPath {@code ns} (rift#1326), a response-less stub (rift#1372). */
class Engine022WireTest {

    @Test
    void anXPathCopyCarriesMountebanksNsMap() {
        Stub stub = onGet("/x")
                .willReturn(ok().copy(copyFrom("body").into("$NAME").using(xPath("//mb:name", Map.of("mb", "http://example.com/mb")))))
                .build();
        assertTrue(stub.toJson().contains(
                        "\"using\":{\"method\":\"xpath\",\"selector\":\"//mb:name\",\"ns\":{\"mb\":\"http://example.com/mb\"}}"),
                stub.toJson());
        assertEquals(stub, Stub.fromJson(stub.toJson()), "ns survives a read back");
    }

    @Test
    void anXPathLookupKeyCarriesMountebanksNsMap() {
        assertEquals("{\"key\":{\"from\":\"body\",\"using\":{\"method\":\"xpath\",\"selector\":\"//mb:id\",\"ns\":{\"mb\":\"urn:mb\"}}},"
                        + "\"fromDataSource\":{\"csv\":{\"path\":\"p.csv\",\"keyColumn\":\"id\"}},\"into\":\"$ROW\"}",
                lookupKey("body").using(xPath("//mb:id", Map.of("mb", "urn:mb"))).fromCsv("p.csv", "id").into("$ROW").build().toJson());
    }

    @Test
    void anXPathWithoutNamespacesWritesNoNs() {
        assertEquals("{\"method\":\"xpath\",\"selector\":\"//a\"}", xPath("//a").toJsonValue().toJson());
    }

    @Test
    void anImposterWithAResponselessStubIsRefusedAtBuild() {
        ImposterSpec spec = imposter("svc").stub(onGet("/ok").willReturn(ok())).stub(onGet("/none"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, spec::build);
        assertEquals("stubs[1]: 'responses' must be a non-empty array — add a response with willReturn(...)", e.getMessage());
    }
}
