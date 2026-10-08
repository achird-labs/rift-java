package io.github.achirdlabs.rift.transport;

import io.github.achirdlabs.rift.ConnectOptions;
import io.github.achirdlabs.rift.Imposter;
import io.github.achirdlabs.rift.MatchClause;
import io.github.achirdlabs.rift.Rift;
import io.github.achirdlabs.rift.VersionCheck;
import io.github.achirdlabs.rift.error.InvalidDefinition;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.OptionalLong;

import static io.github.achirdlabs.rift.dsl.RiftDsl.imposter;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Server-side {@code match=} filter clauses. The engine's grammar is four forms AND-ed
 * ({@code header:<Name>=<Value>}, {@code flow_id=<Value>}, {@code method=<Verb>}, {@code path=<Path>}),
 * and it rejects a malformed clause with a 400 rather than falling back to returning everything — so
 * the SDK's job is to make an invalid clause unrepresentable and to encode the valid ones exactly.
 *
 * <p>Encoding is the sharp edge. The engine splits a query pair on its <em>first</em> {@code =} and
 * only then percent-decodes the value, so the clause must be percent-encoded — an unencoded
 * {@code %} in a header value would be decoded into something else, and an unencoded {@code &} would
 * split the pair. Its decoder is a URI decoder, not a form decoder: {@code +} stays a literal
 * {@code +}, so a space must go over the wire as {@code %20}.
 */
class MatchClauseTest {

    private static final String IMP = "{\"port\":4545,\"protocol\":\"http\",\"stubs\":[]}";

    private static Rift connect(FakeAdminServer s) {
        return Rift.connect(ConnectOptions.builder(s.baseUri()).versionCheck(VersionCheck.OFF).build());
    }

    private static Imposter created(FakeAdminServer s, Rift rift) {
        s.respond("POST /imposters", 201, IMP);
        return rift.create(imposter("x").port(4545));
    }

    /** The last savedRequests request line the fake saw, raw (undecoded) so encoding is observable. */
    private static String lastPath(FakeAdminServer s, String method) {
        return s.received().stream()
                .filter(r -> r.method().equals(method) && r.path().startsWith("/imposters/4545/savedRequests"))
                .map(FakeAdminServer.Received::path)
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("no " + method + " savedRequests was issued"));
    }

    @Test
    void aHeaderClauseIsRenderedInTheEnginesGrammar() {
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.respond("GET /imposters/4545/savedRequests", 200, "[]");
            try (Rift rift = connect(s)) {
                created(s, rift).recordedPage(MatchClause.header("X-Tenant", "acme"));

                assertEquals("/imposters/4545/savedRequests?match=header%3AX-Tenant%3Dacme", lastPath(s, "GET"));
            }
        }
    }

    @Test
    void aFlowIdClauseIsRenderedInTheEnginesGrammar() {
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.respond("GET /imposters/4545/savedRequests", 200, "[]");
            try (Rift rift = connect(s)) {
                created(s, rift).recordedPage(MatchClause.flowId("tenant-a"));

                assertEquals("/imposters/4545/savedRequests?match=flow_id%3Dtenant-a", lastPath(s, "GET"));
            }
        }
    }

    @Test
    void clausesAndTogetherAsRepeatedMatchParameters() {
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.respond("GET /imposters/4545/savedRequests", 200, "[]");
            try (Rift rift = connect(s)) {
                created(s, rift).recordedPage(MatchClause.flowId("a"), MatchClause.header("X-K", "v"));

                // The engine AND-s every `match` pair it finds; repetition is the conjunction.
                assertEquals("/imposters/4545/savedRequests?match=flow_id%3Da&match=header%3AX-K%3Dv",
                        lastPath(s, "GET"));
            }
        }
    }

    @Test
    void theCursorIsCutFirstAndTheFilterSecond() {
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.on("GET /imposters/4545/savedRequests",
                    (r, v) -> FakeAdminServer.Response.ok("[]").withHeader("x-rift-next-index", "9"));
            try (Rift rift = connect(s)) {
                assertEquals(OptionalLong.of(9),
                        created(s, rift).recordedSince(12, MatchClause.flowId("a")).nextIndex());

                assertEquals("/imposters/4545/savedRequests?since=12&match=flow_id%3Da", lastPath(s, "GET"));
            }
        }
    }

    @Test
    void encodingSurvivesTheCharactersThatWouldOtherwiseCorruptTheClause() {
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.respond("GET /imposters/4545/savedRequests", 200, "[]");
            try (Rift rift = connect(s)) {
                // Every character here breaks a different way if mis-encoded: a space becomes `+`
                // under form-encoding (the engine's URI decoder would keep it literal), `=` and `&`
                // would split the pair or the clause, `%` would be decoded a second time, and
                // non-ASCII needs UTF-8 octets.
                String value = "a b=c&d%20e é";
                created(s, rift).recordedPage(MatchClause.header("X-T", value));

                String query = lastPath(s, "GET").substring(lastPath(s, "GET").indexOf('?') + 1);
                assertFalse(query.contains("+"), "a space must ride as %20, not + — the engine decodes URIs, not forms: " + query);
                assertTrue(query.contains("%20"), query);
                assertTrue(query.contains("%C3%A9"), "unicode goes over as UTF-8 octets: " + query);

                // The round-trip is what actually matters: what the engine decodes must be the exact
                // clause we meant, including the literal "%20" that must NOT become a space.
                String decoded = URLDecoder.decode(query.substring("match=".length()), StandardCharsets.UTF_8);
                assertEquals("header:X-T=" + value, decoded);
            }
        }
    }

    @Test
    void aScopedClearSendsTheSameClauses() {
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.respond("DELETE /imposters/4545/savedRequests", 200, "");
            try (Rift rift = connect(s)) {
                created(s, rift).clearRecorded(MatchClause.flowId("tenant-a"));

                assertEquals("/imposters/4545/savedRequests?match=flow_id%3Dtenant-a", lastPath(s, "DELETE"));
            }
        }
    }

    @Test
    void anUnfilteredCallStillSendsNoMatchParameter() {
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.respond("GET /imposters/4545/savedRequests", 200, "[]");
            try (Rift rift = connect(s)) {
                created(s, rift).recordedPage();

                assertEquals("/imposters/4545/savedRequests", lastPath(s, "GET"));
            }
        }
    }

    @Test
    void aMethodClauseIsRenderedInTheEnginesGrammar() {
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.respond("GET /imposters/4545/savedRequests", 200, "[]");
            try (Rift rift = connect(s)) {
                created(s, rift).recordedPage(MatchClause.method("GET"));

                assertEquals("/imposters/4545/savedRequests?match=method%3DGET", lastPath(s, "GET"));
            }
        }
    }

    @Test
    void aPathClauseIsRenderedInTheEnginesGrammar() {
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.respond("GET /imposters/4545/savedRequests", 200, "[]");
            try (Rift rift = connect(s)) {
                created(s, rift).recordedPage(MatchClause.path("/orders"));

                assertEquals("/imposters/4545/savedRequests?match=path%3D%2Forders", lastPath(s, "GET"));
            }
        }
    }

    @Test
    void aMethodIsSentVerbatimRatherThanCaseCoerced() {
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.respond("GET /imposters/4545/savedRequests", 200, "[]");
            try (Rift rift = connect(s)) {
                // The engine compares methods case-sensitively. Upper-casing a caller's "get" here
                // would turn a filter that legitimately matches nothing into one that matches
                // everything — a silently different question, so the value rides verbatim.
                created(s, rift).recordedPage(MatchClause.method("get"));

                assertEquals("/imposters/4545/savedRequests?match=method%3Dget", lastPath(s, "GET"));
            }
        }
    }

    @Test
    void anAlreadyEncodedPathIsNotDecodedOnTheWayOut() {
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.respond("GET /imposters/4545/savedRequests", 200, "[]");
            try (Rift rift = connect(s)) {
                // The engine compares against the RAW recorded path, so "/a%20b" must arrive as the
                // five characters %,2,0 included — i.e. the '%' itself must be encoded. Sending it
                // unencoded would have the engine decode it to "/a b" and match a different request.
                created(s, rift).recordedPage(MatchClause.path("/a%20b"));

                String query = lastPath(s, "GET").substring(lastPath(s, "GET").indexOf('?') + 1);
                assertEquals("match=path%3D%2Fa%2520b", query);
                assertEquals("path=/a%20b",
                        URLDecoder.decode(query.substring("match=".length()), StandardCharsets.UTF_8));
            }
        }
    }

    @Test
    void allFourClauseFormsAndTogetherAfterTheCursor() {
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.respond("GET /imposters/4545/savedRequests", 200, "[]");
            try (Rift rift = connect(s)) {
                created(s, rift).recordedSince(7, MatchClause.flowId("a"), MatchClause.header("X-K", "v"),
                        MatchClause.method("POST"), MatchClause.path("/orders"));

                assertEquals("/imposters/4545/savedRequests?since=7&match=flow_id%3Da&match=header%3AX-K%3Dv"
                        + "&match=method%3DPOST&match=path%3D%2Forders", lastPath(s, "GET"));
            }
        }
    }

    @Test
    void aScopedClearAcceptsTheNewClausesToo() {
        try (FakeAdminServer s = new FakeAdminServer()) {
            s.respond("DELETE /imposters/4545/savedRequests", 200, "");
            try (Rift rift = connect(s)) {
                // Mixes a pre-existing clause with the new ones: the scoped clear is the destructive
                // direction, so a clause dropped here deletes traffic the caller meant to keep.
                created(s, rift).clearRecorded(MatchClause.flowId("tenant-a"), MatchClause.method("DELETE"),
                        MatchClause.path("/cart"));

                assertEquals("/imposters/4545/savedRequests?match=flow_id%3Dtenant-a"
                        + "&match=method%3DDELETE&match=path%3D%2Fcart", lastPath(s, "DELETE"));
            }
        }
    }

    @Test
    void aMethodThatCouldNeverHaveBeenRecordedIsUnrepresentable() {
        // A method is an HTTP token. A non-token can appear in no recorded request — the engine
        // could never have parsed such a request line — so the clause would silently match nothing
        // forever rather than fail, which is the failure mode this grammar is closed to prevent.
        assertThrows(NullPointerException.class, () -> MatchClause.method(null));
        assertThrows(IllegalArgumentException.class, () -> MatchClause.method(""));
        assertThrows(IllegalArgumentException.class, () -> MatchClause.method("   "));
        assertThrows(IllegalArgumentException.class, () -> MatchClause.method("GE T"));
        assertThrows(IllegalArgumentException.class, () -> MatchClause.method("GET,POST"));
        // ...while a legitimate extension method (WebDAV, PATCH, and friends) still works.
        assertEquals("PROPFIND", ((MatchClause.Method) MatchClause.method("PROPFIND")).value());
    }

    @Test
    void aPathThatCouldNeverMatchIsUnrepresentable() {
        assertThrows(NullPointerException.class, () -> MatchClause.path(null));
        // The engine compares the BARE path; a clause carrying a query string or fragment would be
        // compared whole and never match anything, silently.
        assertThrows(IllegalArgumentException.class, () -> MatchClause.path("/orders?id=1"));
        assertThrows(IllegalArgumentException.class, () -> MatchClause.path("/orders#top"));
        // A recorded path always starts with '/', so anything else is likewise a never-matcher.
        assertThrows(IllegalArgumentException.class, () -> MatchClause.path("orders"));
        assertThrows(IllegalArgumentException.class, () -> MatchClause.path(""));
        assertThrows(IllegalArgumentException.class, () -> MatchClause.path("   "));
        assertThrows(IllegalArgumentException.class, () -> MatchClause.path("http://host/orders"));
        // A request target cannot carry these at all, so the engine could never have recorded a path
        // containing one — the likeliest version of this mistake being a path typed with a space.
        assertThrows(IllegalArgumentException.class, () -> MatchClause.path("/a b"));
        assertThrows(IllegalArgumentException.class, () -> MatchClause.path("/a\tb"));
        assertThrows(IllegalArgumentException.class, () -> MatchClause.path("/a<b"));
        assertThrows(IllegalArgumentException.class, () -> MatchClause.path("/a>b"));
        assertThrows(IllegalArgumentException.class, () -> MatchClause.path("/a`b"));
        // ...while the root and ordinary paths work.
        assertEquals("/", ((MatchClause.Path) MatchClause.path("/")).value());
        assertEquals("/a/b", ((MatchClause.Path) MatchClause.path("/a/b")).value());
    }

    @Test
    void aPathTheEngineCouldRecordStaysExpressible() {
        // The rejection above must not overshoot. Every character here is one the engine's URI parser
        // accepts in a request target — real clients send them and they land in the journal verbatim —
        // so refusing them would make a legitimate filter unconstructible, which is its own silent
        // failure: the caller cannot express the tail they can actually observe.
        assertEquals("/a[1]", ((MatchClause.Path) MatchClause.path("/a[1]")).value());
        assertEquals("/a{b}", ((MatchClause.Path) MatchClause.path("/a{b}")).value());
        // The parser allows a bare quote "for parity" with clients that embed JSON in the path.
        assertEquals("/a\"b", ((MatchClause.Path) MatchClause.path("/a\"b")).value());
        assertEquals("/a|b", ((MatchClause.Path) MatchClause.path("/a|b")).value());
        assertEquals("/café", ((MatchClause.Path) MatchClause.path("/café")).value());
        assertEquals("/100%", ((MatchClause.Path) MatchClause.path("/100%")).value());
        // `=` and `&` carry no structural meaning in this clause, unlike a header name: the engine
        // strips the "path=" prefix and takes the rest verbatim rather than re-splitting it.
        assertEquals("/a=b&c", ((MatchClause.Path) MatchClause.path("/a=b&c")).value());
    }

    @Test
    void anInvalidClauseIsUnrepresentable() {
        // The engine 400s an empty header name; the type refuses to build one, so that round trip
        // cannot happen from this API at all.
        assertThrows(NullPointerException.class, () -> MatchClause.header(null, "v"));
        assertThrows(NullPointerException.class, () -> MatchClause.header("X", null));
        assertThrows(NullPointerException.class, () -> MatchClause.flowId(null));
        assertThrows(IllegalArgumentException.class, () -> MatchClause.header("", "v"));
        assertThrows(IllegalArgumentException.class, () -> MatchClause.header("   ", "v"));
    }

    @Test
    void aHeaderNameThatWouldSilentlyReSplitTheClauseIsRejected() {
        // header:X=Y=v would be split by the engine on its FIRST '=' into name "X", value "Y=v" —
        // no error, just a filter nobody asked for. That is the one input that could make a clause
        // mean something else, so it must not be constructible.
        assertThrows(IllegalArgumentException.class, () -> MatchClause.header("X=Y", "v"));
        assertThrows(IllegalArgumentException.class, () -> MatchClause.header("has space", "v"));
        assertThrows(IllegalArgumentException.class, () -> MatchClause.header("X:Y", "v"));
        // ...while the punctuation HTTP genuinely allows in a header name still works.
        assertEquals("X-Tenant_id.v1", ((MatchClause.Header) MatchClause.header("X-Tenant_id.v1", "v")).name());
    }

    @Test
    void anEngineRejectionOfAClauseSurfacesAsInvalidDefinition() {
        try (FakeAdminServer s = new FakeAdminServer()) {
            // Defensive: unreachable from the sealed type today, but the engine owns this contract
            // and must not be mistaken for an empty journal if it ever rejects a clause we build.
            s.respond("GET /imposters/4545/savedRequests", 400,
                    "{\"errors\":[{\"code\":\"bad request\",\"message\":\"unsupported match clause 'nope'\"}]}");
            try (Rift rift = connect(s)) {
                Imposter imp = created(s, rift);

                InvalidDefinition e = assertThrows(InvalidDefinition.class,
                        () -> imp.recordedPage(MatchClause.flowId("a")));
                assertTrue(e.getMessage().contains("match"), e.getMessage());
            }
        }
    }

    @Test
    void aTransportThatCannotFilterRefusesRatherThanReturningEverything() {
        RiftTransport cursorless = new NoFilterTransport();

        // Serving the full list here would hand back exactly the entries the caller asked to
        // exclude — for correlated scenarios that is cross-contamination, silently.
        assertThrows(UnsupportedOperationException.class,
                () -> cursorless.recordedSince(4545, OptionalLong.empty(), List.of(MatchClause.flowId("a"))));
        // ...while the unfiltered read still falls back honestly (the #130 capability probe).
        assertEquals(OptionalLong.empty(), cursorless.recordedSince(4545, OptionalLong.empty(), List.of()).nextIndex());
    }

    @Test
    void aTransportThatCannotScopeACleaRefusesRatherThanClearingEverything() {
        RiftTransport cursorless = new NoFilterTransport();

        // The destructive direction: falling back would delete the entries the caller wanted kept.
        assertThrows(UnsupportedOperationException.class,
                () -> cursorless.clearRecorded(4545, List.of(MatchClause.flowId("a"))));
    }

    /** A transport with no server-side filtering — the SPI default's shape, e.g. a custom transport. */
    private static final class NoFilterTransport implements RiftTransport {

        @Override public io.github.achirdlabs.rift.json.JsonValue recorded(int port) {
            return io.github.achirdlabs.rift.json.JsonValue.parse("[]");
        }
        @Override public void clearRecorded(int port) { /* the unscoped clear is supported */ }
        @Override public void close() {}

        // --- unused operations ---
        @Override public io.github.achirdlabs.rift.json.JsonValue createImposter(io.github.achirdlabs.rift.json.JsonValue def) { throw new UnsupportedOperationException(); }
        @Override public io.github.achirdlabs.rift.json.JsonValue getImposter(int port) { throw new UnsupportedOperationException(); }
        @Override public io.github.achirdlabs.rift.json.JsonValue getImposter(int port, boolean r, boolean p) { throw new UnsupportedOperationException(); }
        @Override public void deleteImposter(int port) { throw new UnsupportedOperationException(); }
        @Override public void deleteAll() { throw new UnsupportedOperationException(); }
        @Override public io.github.achirdlabs.rift.json.JsonValue listImposters(boolean r, boolean p) { throw new UnsupportedOperationException(); }
        @Override public void replaceAllImposters(io.github.achirdlabs.rift.json.JsonValue doc) { throw new UnsupportedOperationException(); }
        @Override public io.github.achirdlabs.rift.json.JsonValue applyConfig(io.github.achirdlabs.rift.json.JsonValue config) { throw new UnsupportedOperationException(); }
        @Override public void addStub(int port, io.github.achirdlabs.rift.json.JsonValue stub) { throw new UnsupportedOperationException(); }
        @Override public void replaceStubs(int port, io.github.achirdlabs.rift.json.JsonValue stubs) { throw new UnsupportedOperationException(); }
        @Override public void replaceStub(int port, StubAddress a, io.github.achirdlabs.rift.json.JsonValue s) { throw new UnsupportedOperationException(); }
        @Override public void deleteStub(int port, StubAddress a) { throw new UnsupportedOperationException(); }
        @Override public void clearProxyResponses(int port) { throw new UnsupportedOperationException(); }
        @Override public void enable(int port) { throw new UnsupportedOperationException(); }
        @Override public void disable(int port) { throw new UnsupportedOperationException(); }
        @Override public io.github.achirdlabs.rift.json.JsonValue scenarios(int port, java.util.Optional<String> f) { throw new UnsupportedOperationException(); }
        @Override public void setScenarioState(int port, String n, String s, java.util.Optional<String> f) { throw new UnsupportedOperationException(); }
        @Override public void resetScenarios(int port, java.util.Optional<String> flowId) { throw new UnsupportedOperationException(); }
        @Override public java.util.Optional<io.github.achirdlabs.rift.json.JsonValue> flowStateGet(int port, String f, String k) { throw new UnsupportedOperationException(); }
        @Override public void flowStatePut(int port, String f, String k, io.github.achirdlabs.rift.json.JsonValue v) { throw new UnsupportedOperationException(); }
        @Override public void flowStateDelete(int port, String f, String k) { throw new UnsupportedOperationException(); }
        @Override public void spaceAddStub(int port, String f, io.github.achirdlabs.rift.json.JsonValue s) { throw new UnsupportedOperationException(); }
        @Override public io.github.achirdlabs.rift.json.JsonValue spaceListStubs(int port, String f) { throw new UnsupportedOperationException(); }
        @Override public io.github.achirdlabs.rift.json.JsonValue spaceRecorded(int port, String f) { throw new UnsupportedOperationException(); }
        @Override public void spaceDelete(int port, String f) { throw new UnsupportedOperationException(); }
        @Override public io.github.achirdlabs.rift.json.JsonValue buildInfo() { throw new UnsupportedOperationException(); }
        @Override public java.net.URI adminUri() { throw new UnsupportedOperationException(); }
        @Override public io.github.achirdlabs.rift.json.JsonValue startIntercept(io.github.achirdlabs.rift.json.JsonValue options) { throw new UnsupportedOperationException(); }
        @Override public void interceptAddRules(io.github.achirdlabs.rift.json.JsonValue rules) { throw new UnsupportedOperationException(); }
        @Override public io.github.achirdlabs.rift.json.JsonValue interceptListRules() { throw new UnsupportedOperationException(); }
        @Override public void interceptClearRules() { throw new UnsupportedOperationException(); }
        @Override public String interceptCaPem() { throw new UnsupportedOperationException(); }
    }
}
