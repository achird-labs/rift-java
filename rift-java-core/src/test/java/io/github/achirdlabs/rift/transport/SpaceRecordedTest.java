package io.github.achirdlabs.rift.transport;

import io.github.achirdlabs.rift.ConnectOptions;
import io.github.achirdlabs.rift.Imposter;
import io.github.achirdlabs.rift.RecordedRequest;
import io.github.achirdlabs.rift.Rift;
import io.github.achirdlabs.rift.VersionCheck;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.github.achirdlabs.rift.dsl.RiftDsl.imposter;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a space reads its slice of the journal. There is no space-scoped recorded-requests endpoint:
 * the engine's admin router registers only {@code spaces/{flowId}} and {@code spaces/{flowId}/stubs},
 * and a space's traffic is read through {@code savedRequests} with a {@code flow_id} filter
 * ({@code docs/features/spaces.md}).
 *
 * <p>The SDK previously invented {@code spaces/{flowId}/recorded}, which 404s on every real engine.
 * It survived because the only tests that touched it were fakes told to serve the invented route —
 * a fake can only confirm what you told it. These assert the route the engine actually has; the
 * live proof that it works is {@code SpaceRecordedIT}.
 */
class SpaceRecordedTest {

    private static final String IMP = "{\"port\":4545,\"protocol\":\"http\",\"stubs\":[]}";
    private static final String TWO = "[{\"method\":\"GET\",\"path\":\"/a\"},{\"method\":\"GET\",\"path\":\"/b\"}]";

    private static Rift connect(FakeAdminServer s) {
        return Rift.connect(ConnectOptions.builder(s.baseUri()).versionCheck(VersionCheck.OFF).build());
    }

    private static Imposter created(FakeAdminServer s, Rift rift) {
        s.respond("POST /imposters", 201, IMP);
        return rift.create(imposter("x").port(4545));
    }

    private static String lastGet(FakeAdminServer s) {
        return s.received().stream()
                .filter(r -> r.method().equals("GET") && r.path().startsWith("/imposters/4545/savedRequests"))
                .map(FakeAdminServer.Received::path)
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("no savedRequests GET was issued; requests seen: " + s.received()));
    }

    @Test
    void aSpaceReadsTheJournalThroughItsFlowIdFilter() {
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.respond("GET /imposters/4545/savedRequests", 200, TWO);
            try (Rift rift = connect(s)) {
                List<RecordedRequest> recorded = created(s, rift).space("alice").recorded();

                assertEquals(List.of("/a", "/b"), recorded.stream().map(RecordedRequest::path).toList());
                assertEquals("/imposters/4545/savedRequests?match=flow_id%3Dalice", lastGet(s));
            }
        }
    }

    @Test
    void theInventedSpaceRecordedRouteIsNeverRequested() {
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.respond("GET /imposters/4545/savedRequests", 200, "[]");
            try (Rift rift = connect(s)) {
                created(s, rift).space("alice").recorded();

                // The regression guard: this path does not exist on any engine, so asking for it at
                // all is the bug — a fake that answers it would hide that, which is how it shipped.
                assertFalse(s.received().stream().anyMatch(r -> r.path().contains("/spaces/")),
                        "no space-scoped route should be touched: " + s.received());
            }
        }
    }

    @Test
    void theFlowIdIsPercentEncodedIntoTheClause() {
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.respond("GET /imposters/4545/savedRequests", 200, "[]");
            try (Rift rift = connect(s)) {
                created(s, rift).space("a b&c/é").recorded();

                // A flow id is caller data: unencoded, `&` would split the query and a space would
                // not survive at all. It rides inside the clause, so the clause encoding covers it.
                String path = lastGet(s);
                assertTrue(path.startsWith("/imposters/4545/savedRequests?match=flow_id%3D"), path);
                assertFalse(path.contains("+"), "a space must ride as %20, not + : " + path);
                assertTrue(path.contains("%20") && path.contains("%26") && path.contains("%C3%A9"), path);
            }
        }
    }
}
