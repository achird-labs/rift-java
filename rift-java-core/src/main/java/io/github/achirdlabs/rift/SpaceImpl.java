package io.github.achirdlabs.rift;

import io.github.achirdlabs.rift.dsl.StubSpec;
import io.github.achirdlabs.rift.json.JsonArray;
import io.github.achirdlabs.rift.json.JsonObject;
import io.github.achirdlabs.rift.json.JsonValue;
import io.github.achirdlabs.rift.model.Stub;
import io.github.achirdlabs.rift.transport.RiftTransport;
import io.github.achirdlabs.rift.transport.StubAddress;
import io.github.achirdlabs.rift.verify.RequestMatch;
import io.github.achirdlabs.rift.verify.VerificationException;
import io.github.achirdlabs.rift.verify.VerificationResult;
import io.github.achirdlabs.rift.verify.VerificationTimes;
import io.github.achirdlabs.rift.verify.VerifyDetail;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

final class SpaceImpl implements Space {

    private final int port;
    private final String flowId;
    private final RiftTransport transport;

    SpaceImpl(int port, String flowId, RiftTransport transport) {
        this.port = port;
        this.flowId = flowId;
        this.transport = transport;
    }

    @Override
    public String flowId() {
        return flowId;
    }

    @Override
    public StubRef addStub(StubSpec spec) {
        return addStub(JsonValue.parse(spec.build().toJson()));
    }

    @Override
    public StubRef addStub(JsonValue stub) {
        transport.spaceAddStub(port, flowId, stub);
        int index = stubs().size() - 1;
        return new StubRefImpl(port, transport, new StubAddress.ByIndex(index));
    }

    @Override
    public List<Stub> stubs() {
        JsonValue result = transport.spaceListStubs(port, flowId);
        List<Stub> out = new ArrayList<>();
        collectStubs(result, out);
        return List.copyOf(out);
    }

    @Override
    public List<RecordedRequest> recorded() {
        return RecordedRequests.readAll(transport.spaceRecorded(port, flowId), journalContext());
    }

    @Override
    public List<RecordedRequest> recorded(RequestMatch match) {
        return RecordedRequests.readMatched(
                transport.verify(port, VerifyBody.build(match, Optional.of(flowId), VerifyDetail.REQUESTS)));
    }

    @Override
    public RecordedPage recordedPage(MatchClause... filters) {
        return page(transport.recordedSince(port, OptionalLong.empty(), scoped(filters)));
    }

    @Override
    public RecordedPage recordedSince(long cursor, MatchClause... filters) {
        return page(transport.recordedSince(port, OptionalLong.of(cursor), scoped(filters)));
    }

    /**
     * The space's {@code flow_id} clause first, then the caller's filters. A caller-supplied
     * {@code flow_id} is rejected up front: clauses AND server-side, so a second one either
     * duplicates the scope or silently selects nothing — and silent-empty is the loss mode the
     * cursor API exists to remove.
     */
    private List<MatchClause> scoped(MatchClause[] filters) {
        List<MatchClause> clauses = new ArrayList<>(filters.length + 1);
        clauses.add(MatchClause.flowId(flowId));
        for (MatchClause filter : List.of(filters)) {
            if (filter instanceof MatchClause.FlowId) {
                throw new IllegalArgumentException(
                        "this space already scopes its reads to flow_id=" + flowId
                                + "; clauses AND together, so a second flow_id clause either duplicates it"
                                + " or silently selects nothing");
            }
            clauses.add(filter);
        }
        return clauses;
    }

    private RecordedPage page(RiftTransport.RecordedSlice slice) {
        return new RecordedPage(RecordedRequests.readAll(slice.requests(), journalContext()),
                slice.nextIndex(), slice.truncated());
    }

    /** The diagnostic context for journal reads — the route shape this space's traffic rides on. */
    private String journalContext() {
        return "savedRequests?match=flow_id=" + flowId;
    }

    @Override
    public void verify(RequestMatch match) {
        verify(match, VerificationTimes.atLeast(1));
    }

    @Override
    public void verify(RequestMatch match, VerificationTimes times) {
        VerificationResult result = verifyResult(match, times, VerifyDetail.CLOSEST);
        if (!result.satisfied()) {
            throw new VerificationException(port, Optional.empty(), match, times, result);
        }
    }

    @Override
    public VerificationResult verifyResult(RequestMatch match, VerifyDetail... details) {
        return verifyResult(match, VerificationTimes.atLeast(1), details);
    }

    @Override
    public VerificationResult verifyResult(RequestMatch match, VerificationTimes times, VerifyDetail... details) {
        // Unlike Imposter.verifyResult, a space is not itself an imposter definition, so there is no
        // recordRequests() flag to check here — recording is configured on the owning imposter.
        return VerificationResult.read(
                transport.verify(port, VerifyBody.build(match, Optional.of(flowId), details)), times);
    }

    @Override
    public void delete() {
        transport.spaceDelete(port, flowId);
    }

    private static void collectStubs(JsonValue result, List<Stub> out) {
        if (result instanceof JsonArray arr) {
            for (JsonValue v : arr.items()) {
                out.add(Stub.fromJson(v.toJson()));
            }
        } else if (result instanceof JsonObject obj && obj.get("stubs") instanceof JsonArray arr) {
            for (JsonValue v : arr.items()) {
                out.add(Stub.fromJson(v.toJson()));
            }
        }
    }

}
