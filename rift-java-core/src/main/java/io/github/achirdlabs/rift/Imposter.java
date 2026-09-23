package io.github.achirdlabs.rift;

import io.github.achirdlabs.rift.dsl.StubSpec;
import io.github.achirdlabs.rift.json.JsonValue;
import io.github.achirdlabs.rift.model.ImposterDefinition;
import io.github.achirdlabs.rift.model.Stub;
import io.github.achirdlabs.rift.verify.RequestMatch;
import io.github.achirdlabs.rift.verify.VerificationResult;
import io.github.achirdlabs.rift.verify.VerificationTimes;
import io.github.achirdlabs.rift.verify.VerifyDetail;

import java.net.URI;
import java.util.List;
import java.util.Optional;

/** A thin, transport-backed handle to a live imposter — no local caching; every call round-trips to the engine. */
public interface Imposter {

    int port();

    /**
     * This imposter's own network address. On an engine the SDK runs itself (spawned or embedded;
     * not testcontainers, which connects), an imposter bound to a concrete host ({@code
     * ImposterSpec.host}) is reached there; otherwise, and
     * for any connected engine, the address comes from {@code ConnectOptions.hostResolver}, which
     * by default reuses the admin host. The engine binds an imposter with no host on {@code
     * 0.0.0.0}, IPv4 only, so with an IPv6 admin host set {@code ImposterSpec.host("::1")} too.
     */
    URI uri();

    Optional<String> name();

    /** The imposter's current definition, fetched fresh from the engine. */
    ImposterDefinition definition();

    StubRef addStub(StubSpec spec);

    /**
     * Inserts a stub at {@code index} (0 = first, highest match priority — first-match-wins). Since
     * matching is first-match-wins, {@code index 0} temporarily overrides an existing stub; delete the
     * returned {@link StubRef} to revert. {@code index} must be in {@code [0, stubCount]}.
     */
    StubRef addStub(StubSpec spec, int index);

    /** Inserts a stub at the front (highest priority) — sugar for {@code addStub(spec, 0)}; the overlay idiom. */
    StubRef addStubFirst(StubSpec spec);

    StubRef addStub(JsonValue stub);

    /**
     * Raw-JSON form of {@link #addStub(StubSpec, int)} — the escape hatch for callers holding a
     * fully-formed stub (an SDK bridge, a golden file). {@code stub} is passed to the engine as-is:
     * fields the DSL cannot express ({@code extra}, {@code _rift}) survive, and the engine — not this
     * SDK — validates the content. {@code index} must be in {@code [0, stubCount]}.
     */
    StubRef addStub(JsonValue stub, int index);

    /** Raw-JSON form of {@link #addStubFirst(StubSpec)} — sugar for {@code addStub(stub, 0)}. */
    StubRef addStubFirst(JsonValue stub);

    /** Starts a proxy recording to {@code originUrl} with the default {@link RecordSpec}. */
    Recording startRecording(String originUrl);

    /** Starts a proxy recording to {@code originUrl}, configured by {@code spec}. */
    Recording startRecording(String originUrl, RecordSpec spec);

    void replaceStubs(List<StubSpec> specs);

    /**
     * Raw-JSON form of {@link #replaceStubs(List)} — {@code stubs} must be a {@code JsonArray} of stub
     * objects, passed to the engine as-is (see {@link #addStub(JsonValue, int)} for the pass-through
     * contract). Takes a single {@code JsonValue} rather than a {@code List<JsonValue>} because the
     * latter would erase to the same signature as {@link #replaceStubs(List)}.
     *
     * @throws io.github.achirdlabs.rift.error.InvalidDefinition if {@code stubs} is not a JSON array
     */
    void replaceStubs(JsonValue stubs);

    StubRef stub(String id);

    List<Stub> stubs();

    List<RecordedRequest> recorded();

    /** Recorded requests filtered client-side against {@code match}'s predicates (equals on method/path only; see {@code ImposterImpl}). */
    List<RecordedRequest> recorded(RequestMatch match);

    /**
     * {@return everything currently retained, plus the cursor to resume from} The baseline of a
     * request tail; it asks "what is retained?", which the engine can always answer in full, so it
     * never reports {@link RecordedPage#truncated()}.
     *
     * @see RecordedPage
     */
    RecordedPage recordedPage();

    /**
     * {@return everything retained that every clause accepts, plus the cursor to resume from}
     * Filtering happens engine-side, so the page costs only what it returns.
     *
     * @throws UnsupportedOperationException if this engine connection cannot filter server-side — it
     *                                       refuses rather than answering with the entries you asked
     *                                       to exclude. Every transport the SDK ships can, embedded
     *                                       included; only a custom one need not.
     * @see MatchClause
     */
    RecordedPage recordedPage(MatchClause... filters);

    /**
     * {@return the entries recorded strictly after {@code cursor}, plus the next cursor} The poll
     * step of a request tail: pass back the previous page's {@link RecordedPage#nextIndex()}
     * verbatim. A cursor at the tip yields an empty page whose cursor is still present — that is
     * "nothing new", not "unsupported".
     *
     * <p>Unlike {@link #recordedPage()} this may report {@link RecordedPage#truncated()}: asking for
     * everything after a cursor is answerable only if nothing you had not seen was evicted.
     *
     * @param cursor a cursor previously returned in {@link RecordedPage#nextIndex()}; the engine
     *               rejects a negative one
     * @see RecordedPage
     */
    RecordedPage recordedSince(long cursor);

    /**
     * {@return the entries after {@code cursor} that every clause accepts, plus the next cursor} The
     * filtered tail poll.
     *
     * <p>The engine cuts by cursor first and filters second, and the returned cursor advances past
     * the entries the filter rejected — so a page can come back empty while its cursor still moves,
     * and the tail never re-scans a range it has already judged.
     *
     * @throws UnsupportedOperationException if this engine connection cannot filter server-side
     * @see MatchClause
     */
    RecordedPage recordedSince(long cursor, MatchClause... filters);

    void clearRecorded();

    /**
     * Clears only the recorded requests every clause accepts, leaving the rest — e.g. dropping one
     * tenant's traffic from a journal shared by several.
     *
     * <p>Journal indices are not reset by a clear, so a cursor held across this one stays valid.
     *
     * @throws UnsupportedOperationException if this engine connection cannot scope a clear
     *                                       server-side — it refuses rather than widening the clear
     *                                       to delete everything
     * @see MatchClause
     */
    void clearRecorded(MatchClause... filters);

    void clearProxyResponses();

    Scenarios scenarios();

    /**
     * A per-space (correlated-isolation) view. Spaces require the imposter to declare a header-form
     * {@code flowIdSource} ({@code flowIdFromHeader}); the engine's flow-id source otherwise defaults
     * to the port and space stubs never match. A missing {@code flowIdSource} logs one advisory
     * warning rather than throwing (admin-only list/delete workflows remain valid).
     *
     * @throws IllegalArgumentException if {@code flowId} is blank — a blank id is never the default
     *     flow but a distinct, silently-wrong partition, so it is rejected rather than sent verbatim
     */
    Space space(String flowId);

    /**
     * The runtime flow-state store for a flow id. The engine backs this with a real store only when
     * the def declares one — an explicit {@code _rift.flowState}, a scenario stub, a {@code
     * _rift.script} stub, or an {@code is} response with {@code _rift.stateOps}; otherwise reads
     * return empty (a no-op store). This accessor logs one advisory warning if no such trigger is
     * present.
     *
     * @throws IllegalArgumentException if {@code flowId} is blank — a blank id is never the default
     *     flow but a distinct, silently-wrong partition, so it is rejected rather than sent verbatim
     */
    FlowState flowState(String flowId);

    /** Verifies at least one recorded request matched {@code match}'s predicates. */
    void verify(RequestMatch match);

    /** Verifies the number of recorded requests matching {@code match}'s predicates satisfies {@code times}. */
    void verify(RequestMatch match, VerificationTimes times);

    /**
     * The same verification as {@link #verify(RequestMatch)} as a value rather than a pass/throw —
     * {@code satisfied} reflects "at least once". See {@link #verifyResult(RequestMatch,
     * VerificationTimes, VerifyDetail...)} for the full contract.
     */
    VerificationResult verifyResult(RequestMatch match, VerifyDetail... details);

    /**
     * Counts recorded requests matching {@code match}'s predicates, without throwing — the typed
     * query behind {@link #verify(RequestMatch, VerificationTimes)}, for callers that want the
     * near-miss as data (an SDK bridge rendering its own assertion failures).
     *
     * <p>Matching is evaluated by the <em>engine</em>, so the verdict is the same one the request
     * hot path applies — {@code inject}/{@code xpath} predicates are honoured rather than rejected.
     * Each {@link VerifyDetail} is opt-in because it costs work and wire bytes; with none, only the
     * counts are fetched.
     *
     * @throws io.github.achirdlabs.rift.error.InvalidDefinition if this imposter does not record
     *     requests — the engine would otherwise count {@code 0} of {@code 0} indistinguishably from
     *     genuinely-no-traffic
     * @throws UnsupportedOperationException if the underlying transport does not implement
     *     verification (custom {@code RiftTransport} implementations predating this API)
     */
    VerificationResult verifyResult(RequestMatch match, VerificationTimes times, VerifyDetail... details);

    /** Verifies this imposter recorded no requests at all. */
    void verifyNoInteractions();

    void enable();

    void disable();

    void delete();
}
