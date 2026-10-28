package io.github.achirdlabs.rift.verify;

import com.sun.net.httpserver.HttpServer;
import io.github.achirdlabs.rift.ConnectOptions;
import io.github.achirdlabs.rift.Imposter;
import io.github.achirdlabs.rift.Rift;
import io.github.achirdlabs.rift.VersionCheck;
import io.github.achirdlabs.rift.error.InvalidDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static io.github.achirdlabs.rift.dsl.RiftDsl.onGet;
import static io.github.achirdlabs.rift.dsl.RiftDsl.onPost;
import static io.github.achirdlabs.rift.verify.VerificationTimes.times;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * End-to-end verification over a fake admin server — the wiring {@code VerifyResultTest}'s fake
 * transport cannot cover: that a {@code verify} really does POST {@code /imposters/{port}/verify}
 * with the right body, and that the response envelope survives the real HTTP transport.
 *
 * <p>Verdicts come from the server here, because since #127 the engine — not this SDK — decides
 * them. What is asserted is the body sent and the facade's handling of the reply.
 */
class VerifyIntegrationTest {

    private HttpServer server;
    private final Map<String, String> routes = new ConcurrentHashMap<>(); // "METHOD /path" -> JSON body
    private final List<String> verifyBodies = new CopyOnWriteArrayList<>();
    private volatile Function<String, String> verifyResponder = body -> "{\"matched\":0,\"total\":0}";
    private Rift rift;

    private static final String RECORDING_IMPOSTER = "{\"port\":4545,\"protocol\":\"http\",\"recordRequests\":true,\"stubs\":[]}";
    private static final String THREE_REQUESTS = "{\"requests\":["
            + "{\"method\":\"GET\",\"path\":\"/api/users/2\"},"
            + "{\"method\":\"POST\",\"path\":\"/api/users/1\"},"
            + "{\"method\":\"GET\",\"path\":\"/health\"}]}";

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String key = exchange.getRequestMethod() + " " + path;
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String body;
            if (path.endsWith("/verify")) {
                verifyBodies.add(requestBody);
                body = verifyResponder.apply(requestBody);
            } else {
                body = routes.getOrDefault(key, "{}");
            }
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, out.length);
            try (var os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
        // an imposter that records; three recorded requests
        routes.put("GET /imposters/4545", RECORDING_IMPOSTER);
        routes.put("GET /imposters/4545/savedRequests", THREE_REQUESTS);
        rift = Rift.connect(ConnectOptions.builder(
                java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
                .versionCheck(VersionCheck.OFF).build());
    }

    @AfterEach
    void tearDown() {
        rift.close();
        server.stop(0);
    }

    private Imposter recordingImposter() {
        return rift.imposter(4545).orElseThrow();
    }

    @Test
    void verifyPassesWhenCountMatches() {
        verifyResponder = body -> "{\"matched\":1,\"total\":3}";
        assertDoesNotThrow(() -> recordingImposter().verify(onGet("/api/users/2"), times(1)));
        assertDoesNotThrow(() -> recordingImposter().verify(onGet("/health")));  // default atLeastOnce
    }

    @Test
    void verifySendsPredicatesAndAsksForClosestOverHttp() {
        verifyResponder = body -> "{\"matched\":1,\"total\":3}";
        recordingImposter().verify(onGet("/api/users/2"), times(1));

        assertEquals(1, verifyBodies.size(), "verify is one POST to /imposters/{port}/verify");
        String sent = verifyBodies.get(0);
        assertTrue(sent.contains("/api/users/2"), "the predicates go over the wire: " + sent);
        assertTrue(sent.contains("\"includeClosest\":true"), "verify asks for the diff up front: " + sent);
        assertFalse(sent.contains("\"includeRequests\""), "verify does not ship the journal: " + sent);
    }

    @Test
    void verifyFailsWithEngineClosestMissDiff() {
        // The engine picks and explains the near-miss now; the facade renders what it is given.
        verifyResponder = body -> "{\"matched\":0,\"total\":3,\"closest\":{"
                + "\"request\":{\"method\":\"GET\",\"path\":\"/api/users/2\"},"
                + "\"failedPredicates\":[{\"predicate\":{\"equals\":{\"path\":\"/api/users/1\"}},"
                + "\"actual\":{\"path\":\"/api/users/2\"}}]}}";

        VerificationException ex = assertThrows(VerificationException.class,
                () -> recordingImposter().verify(onGet("/api/users/1"), times(1)));

        String msg = ex.getMessage();
        assertTrue(msg.contains("Verification failed"), msg);
        assertTrue(msg.contains("but was 0"), msg);
        assertTrue(msg.contains("/api/users/2"), "the engine's closest request is rendered: " + msg);
        assertTrue(msg.contains("/api/users/1"), "the failed clause is rendered: " + msg);
        assertEquals(0, ex.result().orElseThrow().matched(), "the structured verdict survives the HTTP transport");
    }

    @Test
    void verifyNoInteractions() {
        routes.put("GET /imposters/6000", "{\"port\":6000,\"recordRequests\":true,\"stubs\":[]}");
        routes.put("GET /imposters/6000/savedRequests", "{\"requests\":[]}");
        assertDoesNotThrow(() -> rift.imposter(6000).orElseThrow().verifyNoInteractions());
        assertThrows(VerificationException.class, () -> recordingImposter().verifyNoInteractions());
    }

    @Test
    void recordedWithMatchAsksTheEngine() {
        // #337: the engine picks the matches (verify + includeRequests); the SDK no longer filters.
        verifyResponder = body -> "{\"matched\":1,\"total\":3,\"requests\":[{\"method\":\"GET\",\"path\":\"/health\"}]}";
        List<io.github.achirdlabs.rift.RecordedRequest> hits = recordingImposter().recorded(onGet("/health"));
        assertEquals(1, hits.size());
        assertEquals("/health", hits.get(0).path());
        assertTrue(verifyBodies.get(0).contains("\"includeRequests\":true"), verifyBodies.get(0));
    }

    @Test
    void verifyOnNonRecordingImposterThrowsInvalidDefinition() {
        routes.put("GET /imposters/7000", "{\"port\":7000,\"protocol\":\"http\",\"stubs\":[]}"); // no recordRequests
        InvalidDefinition ex = assertThrows(InvalidDefinition.class,
                () -> rift.imposter(7000).orElseThrow().verify(onGet("/x")));
        assertTrue(ex.getMessage().toLowerCase().contains("record"), ex.getMessage());
    }

    @Test
    void spaceScopedVerifyIsIsolated() {
        // A space scopes by flowId in the verify body, not by a distinct route — so the server
        // answering per flowId is exactly what proves the scoping reached the wire.
        verifyResponder = body -> body.contains("\"flowId\":\"flow-A\"")
                ? "{\"matched\":1,\"total\":1}"
                : "{\"matched\":0,\"total\":0}";

        assertDoesNotThrow(() -> recordingImposter().space("flow-A").verify(onGet("/only-in-A"), times(1)));
        assertThrows(VerificationException.class,
                () -> recordingImposter().space("flow-B").verify(onGet("/only-in-A"), times(1)));
    }

    @Test
    void verifyWithoutTimesFailsWhenZeroMatches() {
        // verify(match) with no times == atLeastOnce → fails when nothing matched
        verifyResponder = body -> "{\"matched\":0,\"total\":3}";
        assertThrows(VerificationException.class, () -> recordingImposter().verify(onGet("/never-called")));
    }

    @Test
    void verifyNoInteractionsListsRecordedRequestsMostRecentFirstCappedAtTen() {
        // verifyNoInteractions asserts emptiness rather than a predicate match, so it keeps listing
        // the journal client-side — the one caller left for the capped, client-rendered list.
        StringBuilder reqs = new StringBuilder("{\"requests\":[");
        for (int i = 0; i < 12; i++) {
            reqs.append(i == 0 ? "" : ",").append("{\"method\":\"GET\",\"path\":\"/miss/").append(i).append("\"}");
        }
        reqs.append("]}");
        routes.put("GET /imposters/8100", "{\"port\":8100,\"protocol\":\"http\",\"recordRequests\":true,\"stubs\":[]}");
        routes.put("GET /imposters/8100/savedRequests", reqs.toString());

        VerificationException ex = assertThrows(VerificationException.class,
                () -> rift.imposter(8100).orElseThrow().verifyNoInteractions());
        String message = ex.getMessage();

        // The header must describe what the list actually is. Nothing is ranked here (verifyNoInteractions
        // has no predicates to rank against), so "closest match first" would be a claim the code cannot back.
        assertTrue(message.contains("12 recorded requests, most recent first:"),
                "the header must state the real ordering:\n" + message);
        assertFalse(message.contains("closest match first"), "nothing is ranked:\n" + message);
        // "(matches)" on a verification *failure* is self-contradictory, and was unreachable-by-design output.
        assertFalse(message.contains("(matches)"), "a failed assertion must not claim its requests matched:\n" + message);

        List<String> listed = message.lines().filter(line -> line.startsWith("  ✗ ")).toList();
        assertEquals(10, listed.size(), "the list caps at MAX_DIFF_LINES:\n" + message);
        assertEquals("  ✗ GET /miss/11", listed.get(0), "most recent first — /miss/11 was recorded last:\n" + message);
        assertEquals("  ✗ GET /miss/2", listed.get(9), "the 10th line is the 10th-newest:\n" + message);
        assertTrue(message.contains("… and 2 more"),
                "12 recorded requests must be capped with a '… and k more' note:\n" + message);
        assertTrue(ex.result().isEmpty(), "verifyNoInteractions has no engine verdict to carry");
    }

    @Test
    void spaceRecordedWithMatchAsksTheEngineInItsFlow() {
        verifyResponder = body -> body.contains("\"flowId\":\"flow-A\"")
                ? "{\"matched\":1,\"total\":2,\"requests\":[{\"method\":\"GET\",\"path\":\"/a\"}]}"
                : "{\"matched\":0,\"total\":0,\"requests\":[]}";
        List<io.github.achirdlabs.rift.RecordedRequest> hits = recordingImposter().space("flow-A").recorded(onGet("/a"));
        assertEquals(1, hits.size());
        assertEquals("/a", hits.get(0).path());
    }

    @Test
    void injectPredicateIsSentToTheEngineRatherThanRejected() {
        // Behavior delta of #127: an inject predicate used to be rejected client-side because this
        // SDK could not evaluate it. The engine can, so it is now forwarded and the engine decides.
        verifyResponder = body -> "{\"matched\":1,\"total\":1}";

        assertDoesNotThrow(() -> recordingImposter()
                .verify(onPost("/x").withPredicateInject("function(){return true;}")));

        assertTrue(verifyBodies.get(0).contains("inject"),
                "the inject predicate is forwarded verbatim: " + verifyBodies.get(0));
    }
}
