package io.github.achirdlabs.rift;

import io.github.achirdlabs.rift.dsl.StubSpec;
import io.github.achirdlabs.rift.json.JsonValue;
import io.github.achirdlabs.rift.model.Stub;
import io.github.achirdlabs.rift.verify.RequestMatch;
import io.github.achirdlabs.rift.verify.VerificationResult;
import io.github.achirdlabs.rift.verify.VerificationTimes;
import io.github.achirdlabs.rift.verify.VerifyDetail;

import java.util.List;

/** A correlated-isolation "space": a flow-scoped view of an imposter's stubs and recorded traffic. */
public interface Space {

    String flowId();

    StubRef addStub(StubSpec spec);

    /**
     * Raw-JSON form of {@link #addStub(StubSpec)} — {@code stub} is added to this space as-is,
     * preserving fields the DSL cannot express ({@code extra}, {@code _rift}); the engine validates
     * the content.
     */
    StubRef addStub(JsonValue stub);

    List<Stub> stubs();

    List<RecordedRequest> recorded();

    /**
     * The recorded requests that satisfy every predicate in {@code match}, in journal order, within this space (its {@code flowId}). The
     * engine evaluates them ({@code POST /imposters/{port}/verify} with {@code includeRequests}, one
     * call), so the result follows the running engine's rules exactly: from rift 0.24.0 Mountebank's
     * array rules for a repeated query key or header and for JSON bodies, and {@code inject}
     * predicates too. A malformed predicate fails with the engine's error, as {@code verify} does.
     * An owning imposter that does not record answers an empty list.
     */
    List<RecordedRequest> recorded(RequestMatch match);

    /**
     * {@return everything retained for this space, plus the cursor to resume from} The space-scoped
     * {@link Imposter#recordedPage(MatchClause...)}: the imposter journal cut engine-side by this
     * space's {@code flow_id} clause, AND-ed with {@code filters}.
     *
     * <p>The cursor is the <em>imposter's</em> journal index, not a per-space count — a cursor from
     * this space and one from {@link Imposter#recordedPage()} are the same number and
     * interchangeable. {@link RecordedPage}'s contract is unchanged.
     *
     * @throws IllegalArgumentException      if {@code filters} contains a {@code flow_id} clause —
     *                                       the space itself is the flow scope, and clauses AND
     *                                       together, so a second {@code flow_id} either duplicates
     *                                       it or silently selects nothing
     * @throws UnsupportedOperationException if this engine connection cannot filter server-side —
     *                                       the {@code flow_id} clause is always present, so every
     *                                       space cursor read is a filtered one. Every transport the
     *                                       SDK ships can filter, embedded included.
     * @see RecordedPage
     */
    RecordedPage recordedPage(MatchClause... filters);

    /**
     * {@return the entries recorded for this space strictly after {@code cursor}, plus the next
     * cursor} The poll step of a space-scoped request tail: pass back the previous page's
     * {@link RecordedPage#nextIndex()} verbatim, re-baselining with {@link #recordedPage(MatchClause...)}
     * when {@link RecordedPage#truncated()} is set.
     *
     * <p>{@code cursor} is a journal index, not a timestamp. The engine cuts by cursor first and
     * filters second, and the returned cursor advances past entries other flows recorded — so a
     * space's page can come back empty while its cursor still moves, and the tail never re-scans a
     * range it has already judged.
     *
     * @param cursor a cursor previously returned in {@link RecordedPage#nextIndex()} — by this
     *               space's reads or the imposter's; they share one index
     * @throws IllegalArgumentException      if {@code filters} contains a {@code flow_id} clause
     * @throws UnsupportedOperationException if this engine connection cannot filter server-side
     * @see RecordedPage
     */
    RecordedPage recordedSince(long cursor, MatchClause... filters);

    /** Verifies at least one request recorded within this space matched {@code match}'s predicates. */
    void verify(RequestMatch match);

    /** Verifies the number of requests recorded within this space matching {@code match}'s predicates satisfies {@code times}. */
    void verify(RequestMatch match, VerificationTimes times);

    /** Space-scoped {@link Imposter#verifyResult(RequestMatch, VerifyDetail...)}. */
    VerificationResult verifyResult(RequestMatch match, VerifyDetail... details);

    /**
     * Space-scoped {@link Imposter#verifyResult(RequestMatch, VerificationTimes, VerifyDetail...)}:
     * the engine counts only this space's traffic. Unlike the imposter form there is no
     * record-requests precondition — recording is configured on the owning imposter.
     */
    VerificationResult verifyResult(RequestMatch match, VerificationTimes times, VerifyDetail... details);

    void delete();
}
