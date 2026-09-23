package io.github.achirdlabs.rift.junit5;

import io.github.achirdlabs.rift.Imposter;
import io.github.achirdlabs.rift.RecordedRequest;
import io.github.achirdlabs.rift.dsl.ImposterSpec;
import io.github.achirdlabs.rift.json.JsonObject;
import io.github.achirdlabs.rift.json.JsonValue;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.reporting.ReportEntry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.github.achirdlabs.rift.dsl.RiftDsl.imposter;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.testkit.engine.EngineTestKit.engine;

/**
 * Failure diagnostics (#45): {@code @RiftTest(dumpRecordedOnFailure=true)} publishes each imposter's
 * recorded requests to the JUnit report as {@code rift.recorded.<name>} on test failure only, capped
 * at 20 requests. The end-to-end cases run the fixture classes below via {@code EngineTestKit} so a
 * deliberately-failing test does not fail this suite.
 */
class RiftDumpRecordedTest {

    @Test
    void dumpsCappedRecordedRequestsOnFailure() {
        Map<String, String> entries = publishedEntries(FailingWithRecords.class);
        assertTrue(entries.containsKey("rift.recorded.users"),
                "dump entry published on failure: " + entries.keySet());
        String dump = entries.get("rift.recorded.users");
        assertTrue(dump.contains("GET /u/0"), dump);
        assertTrue(dump.contains("GET /u/19"), dump);
        assertFalse(dump.contains("GET /u/20"), "capped at 20 requests: " + dump);
        assertTrue(dump.contains("25") && dump.contains("more"), "notes truncation of total: " + dump);

        // a second imposter is dumped independently under its own key (not truncated)
        assertTrue(entries.containsKey("rift.recorded.orders"), "each imposter dumped: " + entries.keySet());
        String ordersDump = entries.get("rift.recorded.orders");
        assertTrue(ordersDump.contains("GET /o/0") && ordersDump.contains("GET /o/1"), ordersDump);
        assertFalse(ordersDump.contains("more"), "orders had only 2 requests, no truncation: " + ordersDump);
    }

    @Test
    void noDumpWhenTestPasses() {
        Map<String, String> entries = publishedEntries(PassingCase.class);
        assertFalse(entries.containsKey("rift.recorded.users"), "no dump when test passes: " + entries);
    }

    @Test
    void noDumpWhenDisabled() {
        Map<String, String> entries = publishedEntries(DisabledDump.class);
        assertFalse(entries.containsKey("rift.recorded.users"), "no dump when flag off: " + entries);
    }

    @Test
    void formatCapsAtTwentyAndNotesTotal() {
        List<RecordedRequest> reqs = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            reqs.add(new RecordedRequest("GET", "/u/" + i, Map.of(), Map.of(), "",
                    Optional.empty(), Optional.empty(), Optional.empty(), Map.of(), JsonObject.of()));
        }
        String dump = RiftTestExtension.formatRecordedDump(reqs);
        assertTrue(dump.contains("GET /u/19"), dump);
        assertFalse(dump.contains("/u/20"), "capped at 20: " + dump);
        assertTrue(dump.contains("25"), "total noted: " + dump);
        assertTrue(dump.contains("more"), "truncation noted: " + dump);
    }

    @Test
    void formatShowsTheRecordedOutcomeWhenPresent() {
        List<RecordedRequest> reqs = List.of(
                RecordedRequest.read(JsonValue.parse("{\"method\":\"POST\",\"path\":\"/a\",\"status\":201,\"latencyMs\":3}")),
                RecordedRequest.read(JsonValue.parse("{\"method\":\"GET\",\"path\":\"/b\"}")));
        assertEquals("2 recorded request(s):\nPOST /a → 201 in 3 ms\nGET /b", RiftTestExtension.formatRecordedDump(reqs));
    }

    private static Map<String, String> publishedEntries(Class<?> fixture) {
        Map<String, String> all = new LinkedHashMap<>();
        engine("junit-jupiter")
                .selectors(selectClass(fixture))
                .execute()
                .allEvents()
                .reportingEntryPublished()
                .stream()
                .forEach(event -> event.getPayload(ReportEntry.class)
                        .ifPresent(entry -> all.putAll(entry.getKeyValuePairs())));
        return all;
    }

    // ---- EngineTestKit fixtures (not discovered by surefire: names lack Test/IT suffix) ----

    @RiftTest(transport = Transport.CONNECT, adminUri = "${rift.dump.fail}", dumpRecordedOnFailure = true)
    static class FailingWithRecords {
        static final FakeRiftAdmin ADMIN = new FakeRiftAdmin();

        static {
            System.setProperty("rift.dump.fail", ADMIN.baseUri().toString());
        }

        @RiftImposter
        static ImposterSpec users = imposter("users").record();

        @RiftImposter
        static ImposterSpec orders = imposter("orders").record();

        @InjectImposter("users")
        Imposter usersField;

        @InjectImposter("orders")
        Imposter ordersField;

        @Test
        void hitsThenFails() {
            for (int i = 0; i < 25; i++) {
                ADMIN.pushRecorded(usersField.port(), "GET", "/u/" + i);
            }
            for (int i = 0; i < 2; i++) {
                ADMIN.pushRecorded(ordersField.port(), "GET", "/o/" + i);
            }
            fail("intentional failure to trigger the recorded dump");
        }
    }

    @RiftTest(transport = Transport.CONNECT, adminUri = "${rift.dump.pass}", dumpRecordedOnFailure = true)
    static class PassingCase {
        static final FakeRiftAdmin ADMIN = new FakeRiftAdmin();

        static {
            System.setProperty("rift.dump.pass", ADMIN.baseUri().toString());
        }

        @RiftImposter
        static ImposterSpec users = imposter("users").record();

        @InjectImposter("users")
        Imposter usersField;

        @Test
        void hitsThenPasses() {
            for (int i = 0; i < 3; i++) {
                ADMIN.pushRecorded(usersField.port(), "GET", "/u/" + i);
            }
            assertEquals(2, 1 + 1);
        }
    }

    @RiftTest(transport = Transport.CONNECT, adminUri = "${rift.dump.disabled}")
    static class DisabledDump {
        static final FakeRiftAdmin ADMIN = new FakeRiftAdmin();

        static {
            System.setProperty("rift.dump.disabled", ADMIN.baseUri().toString());
        }

        @RiftImposter
        static ImposterSpec users = imposter("users").record();

        @InjectImposter("users")
        Imposter usersField;

        @Test
        void hitsThenFails() {
            for (int i = 0; i < 5; i++) {
                ADMIN.pushRecorded(usersField.port(), "GET", "/u/" + i);
            }
            fail("intentional failure; dump disabled so nothing is published");
        }
    }
}
