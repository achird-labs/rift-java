package io.github.achirdlabs.rift.transport;

import io.github.achirdlabs.rift.EventStream;
import io.github.achirdlabs.rift.EventStreamOptions;
import io.github.achirdlabs.rift.MatchClause;
import io.github.achirdlabs.rift.json.JsonValue;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * The internal SPI a {@code Rift} client speaks against: one call per admin-API operation, with
 * bodies/results as raw {@link JsonValue} — the caller (in {@code io.github.achirdlabs.rift})
 * owns translating to/from the typed wire model. Implementations map every failure mode to the
 * {@code io.github.achirdlabs.rift.error} hierarchy; none of these methods throw a checked
 * exception or return {@code null}.
 *
 * <p>{@link #close()} is idempotent and releases any transport-owned resources (e.g. an HTTP
 * client, a spawned engine process).
 *
 * <p>Flow ids reach this SPI <em>verbatim</em>: null/blank rejection is the facade's job (every
 * public path validates before it gets here, see {@code io.github.achirdlabs.rift.FlowIds}), so
 * these methods pass a {@code flowId} through unchanged and never re-validate it.
 */
public interface RiftTransport extends AutoCloseable {

    JsonValue createImposter(JsonValue def);

    JsonValue getImposter(int port);

    /**
     * The imposter definition, optionally exported in replayable form ({@code replayable=true})
     * and/or with any proxy responses removed ({@code removeProxies=true}). Non-mutating even when
     * both flags are set — the underlying admin-API call is a plain {@code GET}.
     */
    JsonValue getImposter(int port, boolean replayable, boolean removeProxies);

    void deleteImposter(int port);

    void deleteAll();

    JsonValue listImposters(boolean replayable, boolean removeProxies);

    void replaceAllImposters(JsonValue doc);

    /**
     * Reconciles the engine's imposters toward {@code config} ({@code {"imposters":[...]}}) and
     * returns the engine's reply: the apply report ({@code created}/{@code replaced}/{@code
     * stubPatched}/{@code toggled}/{@code deleted} port arrays, {@code failed}) where the engine produces one — a
     * partial failure included, which is a report, not an error — or, from an engine whose admin API
     * does not yet report, its imposter list.
     */
    JsonValue applyConfig(JsonValue config);

    void addStub(int port, JsonValue stub);

    /**
     * Inserts {@code stub} at {@code index} (0 = first / highest match priority). The default falls
     * back to {@link #addStub(int, JsonValue)} (ignoring the position) so existing test fakes keep
     * compiling; the real transports override with a positional insert.
     */
    default void addStub(int port, JsonValue stub, int index) {
        addStub(port, stub);
    }

    void replaceStubs(int port, JsonValue stubs);

    void replaceStub(int port, StubAddress addr, JsonValue stub);

    void deleteStub(int port, StubAddress addr);

    /** The single stub addressed by {@code addr}, as its bare (unwrapped) JSON representation. */
    default JsonValue getStub(int port, StubAddress addr) {
        throw new UnsupportedOperationException("getStub");
    }

    JsonValue recorded(int port);

    /**
     * One page of {@code savedRequests} plus its cursor metadata, still as raw JSON — the facade
     * owns the typed translation.
     *
     * @param nextIndex the {@code x-rift-next-index} cursor, or empty when the transport did not
     *                  report one (see {@link #recordedSince})
     * @param truncated the {@code x-rift-truncated} signal: retention evicted unseen entries
     */
    record RecordedSlice(JsonValue requests, OptionalLong nextIndex, boolean truncated) {}

    /**
     * Reads {@code savedRequests} with an optional journal cursor (rift#603) and optional
     * server-side {@code match=} clauses: entries strictly newer than {@code since}, or everything
     * retained when {@code since} is empty, keeping only those every clause accepts. The engine cuts
     * by cursor first and filters second, and advances the cursor past entries the filter rejected
     * so a filtered tail never re-scans them.
     *
     * <p>Baseline and {@code since} are different questions, not one with a default — a baseline read
     * never reports truncation, while {@code since=0} ("replay everything") does once anything has
     * been evicted.
     *
     * <p>The default serves the full list and reports no cursor, which is exactly what an engine
     * without cursor support does over HTTP. It is the honest answer for a transport that can
     * obtain no index at all; rift#603 expects those consumers to poll. Every transport the SDK
     * ships overrides it — the in-process FFI one included, which delegates to an admin server of
     * its own rather than answer from the cursor-less C-ABI (#175). Synthesizing an index from an
     * array offset here would re-introduce the skip-entries bug the cursor exists to remove, so an
     * implementation that cannot obtain a real cursor must leave {@code nextIndex} empty rather
     * than invent one.
     *
     * <p>Clauses get no such fallback: the default <b>refuses</b> them rather than serving an
     * unfiltered list. Widening a filter silently would hand back the very entries the caller asked
     * to exclude, which for correlated scenarios is cross-contamination — the failure the engine's
     * own 400-on-malformed-clause rule exists to prevent. Nor can it be emulated client-side, since
     * a recorded entry carries no resolved flow id.
     *
     * @throws UnsupportedOperationException if {@code match} is non-empty and this transport cannot
     *                                       express server-side filtering
     */
    default RecordedSlice recordedSince(int port, OptionalLong since, List<MatchClause> match) {
        if (!match.isEmpty()) {
            throw new UnsupportedOperationException(
                    "this transport cannot express server-side match= filters, and must not answer a "
                            + "filtered read with an unfiltered list");
        }
        return new RecordedSlice(recorded(port), OptionalLong.empty(), false);
    }

    /**
     * Clears only the recorded requests every clause accepts, leaving the rest.
     *
     * <p>The default refuses a scoped clear rather than falling back to {@link #clearRecorded(int)}:
     * here the silent-widening failure is destructive, deleting exactly the entries the caller was
     * trying to keep.
     *
     * @throws UnsupportedOperationException if {@code match} is non-empty and this transport cannot
     *                                       express server-side filtering
     */
    default void clearRecorded(int port, List<MatchClause> match) {
        if (!match.isEmpty()) {
            throw new UnsupportedOperationException(
                    "this transport cannot express a scoped clear, and must not widen it to clear everything");
        }
        clearRecorded(port);
    }

    void clearRecorded(int port);

    void clearProxyResponses(int port);

    void enable(int port);

    void disable(int port);

    JsonValue scenarios(int port, Optional<String> flowId);

    /**
     * Forces the named scenario into {@code state}. {@code flowId} scopes the write to a single
     * flow/space; {@link Optional#empty()} targets the imposter's default flow. The engine partitions
     * scenario state by {@code (flowId, name)}, so this is the write mirror of
     * {@link #scenarios(int, Optional)}.
     */
    void setScenarioState(int port, String name, String state, Optional<String> flowId);

    void resetScenarios(int port);

    Optional<JsonValue> flowStateGet(int port, String flowId, String key);

    void flowStatePut(int port, String flowId, String key, JsonValue value);

    void flowStateDelete(int port, String flowId, String key);

    void spaceAddStub(int port, String flowId, JsonValue stub);

    JsonValue spaceListStubs(int port, String flowId);

    JsonValue spaceRecorded(int port, String flowId);

    void spaceDelete(int port, String flowId);

    /**
     * Opens a live subscription to the engine's admin event stream.
     *
     * <p>Unlike the rest of this SPI, this returns a typed {@link EventStream} rather than raw
     * {@link JsonValue}: a long-lived stream has no JSON envelope to hand back, and re-framing SSE
     * through a raw layer only to re-parse it above would buy nothing.
     *
     * <p>The default refuses. Streaming is an admin-HTTP capability, so a transport that can reach
     * no admin server has no stream to emulate; rift#461 expects those consumers to poll. That is
     * the same answer an engine too old to serve {@code /events} gives, and both collapse to one
     * signal because the caller's move is identical: poll instead. Every transport the SDK ships
     * overrides this — the in-process FFI transport included, since it can start an admin server of
     * its own and delegate (#174).
     *
     * @throws UnsupportedOperationException if this transport cannot stream
     */
    default EventStream events(EventStreamOptions options) {
        throw new UnsupportedOperationException(
                "this transport has no admin event stream; poll recordedSince(...) instead");
    }

    JsonValue buildInfo();

    URI adminUri();

    /**
     * Counts recorded requests matching a predicate set server-side: {@code body} is the same
     * {@code POST /imposters/{port}/verify} request body, {@code {"predicates":[…],"flowId"?,
     * "includeRequests"?,"includeClosest"?}}; the result is the {@code {"matched","total",
     * "requests"?,"closest"?}} envelope.
     */
    default JsonValue verify(int port, JsonValue body) {
        throw new UnsupportedOperationException("verify");
    }

    /** The stub-overlap analysis warnings (duplicate/shadowed/catch-all stubs) for {@code port}, as a JSON array. */
    default JsonValue stubWarnings(int port) {
        throw new UnsupportedOperationException("stubWarnings");
    }

    /**
     * Starts the intercept (TLS-MITM) listener with the given {@code {host,port,caCertPath,
     * caKeyPath}} options, returning {@code {interceptPort,interceptUrl}}. One running intercept
     * per engine; the caller (see {@code RiftImpl}) enforces that invariant before this is ever
     * called, so a transport need not guard against a second call itself.
     */
    JsonValue startIntercept(JsonValue options);

    /**
     * Stops the intercept listener; the engine drops its rules and CA with it, and a later {@link
     * #startIntercept} may start another. Stopping a stopped listener succeeds.
     */
    default void stopIntercept() {
        throw new UnsupportedOperationException("this transport cannot stop an intercept listener (stopIntercept)");
    }

    /** Adds one ({@code JsonObject}) or many ({@code JsonArray}) intercept rules. */
    void interceptAddRules(JsonValue rules);

    /** The current intercept rules, as a JSON array. */
    JsonValue interceptListRules();

    void interceptClearRules();

    /** The intercept CA certificate, PEM-encoded. */
    String interceptCaPem();

    @Override
    void close();
}
