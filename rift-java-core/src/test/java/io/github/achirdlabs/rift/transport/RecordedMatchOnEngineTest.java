package io.github.achirdlabs.rift.transport;

import io.github.achirdlabs.rift.ConnectOptions;
import io.github.achirdlabs.rift.Imposter;
import io.github.achirdlabs.rift.RecordedRequest;
import io.github.achirdlabs.rift.Rift;
import io.github.achirdlabs.rift.VersionCheck;
import io.github.achirdlabs.rift.error.CommunicationError;
import io.github.achirdlabs.rift.json.JsonValue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.github.achirdlabs.rift.dsl.RiftDsl.imposter;
import static io.github.achirdlabs.rift.dsl.RiftDsl.onGet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code recorded(match)} asks the engine which recorded requests match (#337), the way {@code verify}
 * does (#127), instead of re-evaluating Mountebank's predicate rules in the JVM: one {@code verify}
 * call with {@code includeRequests}, and no journal fetch.
 */
class RecordedMatchOnEngineTest {

    private static final String IMP = "{\"port\":4545,\"protocol\":\"http\",\"recordRequests\":true,\"stubs\":[]}";
    private static final String REPLY = "{\"matched\":2,\"total\":3,\"requests\":["
            + "{\"method\":\"GET\",\"path\":\"/b\"},{\"method\":\"GET\",\"path\":\"/a\"}]}";

    private static Rift connect(FakeAdminServer s) {
        return Rift.connect(ConnectOptions.builder(s.baseUri()).versionCheck(VersionCheck.OFF).build());
    }

    private static Imposter created(FakeAdminServer s, Rift rift) {
        s.respond("POST /imposters", 201, IMP);
        return rift.create(imposter("x").port(4545).record());
    }

    private static List<FakeAdminServer.Received> verifyCalls(FakeAdminServer s) {
        return s.received().stream()
                .filter(r -> r.method().equals("POST") && r.path().equals("/imposters/4545/verify"))
                .toList();
    }

    private static boolean fetchedJournal(FakeAdminServer s) {
        return s.received().stream().anyMatch(r -> r.method().equals("GET") && r.path().contains("savedRequests"));
    }

    @Test
    void anImposterAsksTheEngineForTheMatchingRequests() {
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.respond("POST /imposters/4545/verify", 200, REPLY);
            try (Rift rift = connect(s)) {
                List<RecordedRequest> hits = created(s, rift).recorded(onGet("/a"));

                assertEquals(List.of("/b", "/a"), hits.stream().map(RecordedRequest::path).toList(),
                        "the engine's matches, in the engine's (journal) order");
                assertEquals(1, verifyCalls(s).size());
                assertEquals(
                        JsonValue.parse("{\"predicates\":[{\"equals\":{\"method\":\"GET\",\"path\":\"/a\"}}],"
                                + "\"includeRequests\":true}"),
                        JsonValue.parse(verifyCalls(s).get(0).body()));
                assertTrue(!fetchedJournal(s), "no client-side filtering over the whole journal: " + s.received());
            }
        }
    }

    @Test
    void aSpaceScopesTheEngineQueryToItsFlowId() {
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.respond("POST /imposters/4545/verify", 200, REPLY);
            try (Rift rift = connect(s)) {
                List<RecordedRequest> hits = created(s, rift).space("alice").recorded(onGet("/a"));

                assertEquals(List.of("/b", "/a"), hits.stream().map(RecordedRequest::path).toList());
                assertEquals(
                        JsonValue.parse("{\"predicates\":[{\"equals\":{\"method\":\"GET\",\"path\":\"/a\"}}],"
                                + "\"flowId\":\"alice\",\"includeRequests\":true}"),
                        JsonValue.parse(verifyCalls(s).get(0).body()));
                assertTrue(!fetchedJournal(s), "no client-side filtering over the journal: " + s.received());
            }
        }
    }

    @Test
    void noMatchesIsAnEmptyList() {
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.respond("POST /imposters/4545/verify", 200, "{\"matched\":0,\"total\":3,\"requests\":[]}");
            try (Rift rift = connect(s)) {
                assertEquals(List.of(), created(s, rift).recorded(onGet("/zzz")));
            }
        }
    }

    @Test
    void aReplyWithoutTheRequestedRequestsIsAnErrorNotAnEmptyResult() {
        // An engine that ignored includeRequests would otherwise read as "nothing matched" while
        // its own count says two did.
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.respond("POST /imposters/4545/verify", 200, "{\"matched\":2,\"total\":3}");
            try (Rift rift = connect(s)) {
                Imposter imp = created(s, rift);
                CommunicationError e = assertThrows(CommunicationError.class, () -> imp.recorded(onGet("/a")));
                assertTrue(e.getMessage().contains("requests"), e.getMessage());
            }
        }
    }
}
