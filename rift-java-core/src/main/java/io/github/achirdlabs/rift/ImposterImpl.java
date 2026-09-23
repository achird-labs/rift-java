package io.github.achirdlabs.rift;

import io.github.achirdlabs.rift.dsl.ProxySpec;
import io.github.achirdlabs.rift.dsl.RequestField;
import io.github.achirdlabs.rift.dsl.RiftDsl;
import io.github.achirdlabs.rift.dsl.StubSpec;
import io.github.achirdlabs.rift.error.InvalidDefinition;
import io.github.achirdlabs.rift.error.RiftException;
import io.github.achirdlabs.rift.json.JsonArray;
import io.github.achirdlabs.rift.json.JsonValue;
import io.github.achirdlabs.rift.model.FlowStateSupport;
import io.github.achirdlabs.rift.model.ImposterDefinition;
import io.github.achirdlabs.rift.model.Stub;
import io.github.achirdlabs.rift.transport.RiftTransport;
import io.github.achirdlabs.rift.transport.StubAddress;
import io.github.achirdlabs.rift.verify.PredicateEvaluator;
import io.github.achirdlabs.rift.verify.RequestMatch;
import io.github.achirdlabs.rift.verify.VerificationException;
import io.github.achirdlabs.rift.verify.VerificationResult;
import io.github.achirdlabs.rift.verify.VerificationTimes;
import io.github.achirdlabs.rift.verify.VerifyDetail;

import java.lang.System.Logger.Level;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

final class ImposterImpl implements Imposter {

    private static final System.Logger LOG = System.getLogger(ImposterImpl.class.getName());
    private static final MatchClause[] NO_FILTERS = {};
    private static final String SAVED_REQUESTS = "savedRequests";

    private final int port;
    private final RiftTransport transport;
    private final ConnectOptions options;
    private boolean spaceConfigChecked;
    private boolean flowStateConfigChecked;

    ImposterImpl(int port, RiftTransport transport, ConnectOptions options) {
        this.port = port;
        this.transport = transport;
        this.options = options;
    }

    @Override
    public int port() {
        return port;
    }

    @Override
    public URI uri() {
        return options.hostResolver().apply(port);
    }

    @Override
    public Optional<String> name() {
        return definition().name();
    }

    @Override
    public ImposterDefinition definition() {
        return ImposterDefinition.fromJson(transport.getImposter(port).toJson());
    }

    @Override
    public StubRef addStub(StubSpec spec) {
        return addStub(JsonValue.parse(spec.build().toJson()));
    }

    @Override
    public StubRef addStub(JsonValue stub) {
        transport.addStub(port, stub);
        int index = definition().stubs().size() - 1;
        return new StubRefImpl(port, transport, new StubAddress.ByIndex(index));
    }

    @Override
    public StubRef addStub(StubSpec spec, int index) {
        return addStub(JsonValue.parse(spec.build().toJson()), index);
    }

    @Override
    public StubRef addStubFirst(StubSpec spec) {
        return addStub(spec, 0);
    }

    @Override
    public StubRef addStub(JsonValue stub, int index) {
        int size = definition().stubs().size();
        if (index < 0 || index > size) {
            throw new InvalidDefinition(
                    "stub index " + index + " out of range for imposter :" + port + " (has " + size + " stubs)");
        }
        transport.addStub(port, stub, index);
        return new StubRefImpl(port, transport, new StubAddress.ByIndex(index));
    }

    @Override
    public StubRef addStubFirst(JsonValue stub) {
        return addStub(stub, 0);
    }

    @Override
    public Recording startRecording(String originUrl) {
        return startRecording(originUrl, RecordSpec.builder().build());
    }

    @Override
    public Recording startRecording(String originUrl, RecordSpec spec) {
        JsonValue proxyStub = buildProxyStub(originUrl, spec);
        List<JsonValue> existing = definition().stubs().stream()
                .map(s -> (JsonValue) JsonValue.parse(s.toJson())).toList();
        List<JsonValue> reordered = new ArrayList<>();
        // ONCE/TRANSPARENT must match before any existing stub could shadow the proxy — prepend;
        // ALWAYS records new entries as it goes, so appending keeps existing stubs first.
        if (spec.mode() == RecordMode.ALWAYS) {
            reordered.addAll(existing);
            reordered.add(proxyStub);
        } else {
            reordered.add(proxyStub);
            reordered.addAll(existing);
        }
        transport.replaceStubs(port, new JsonArray(reordered));
        return new RecordingImpl(port, transport, originUrl, spec);
    }

    private static JsonValue buildProxyStub(String originUrl, RecordSpec spec) {
        ProxySpec proxy = RiftDsl.proxyTo(originUrl);
        proxy = switch (spec.mode()) {
            case ONCE -> proxy.proxyOnce();
            case ALWAYS -> proxy.proxyAlways();
            case TRANSPARENT -> proxy.proxyTransparent();
        };
        proxy = proxy.generateBy(spec.generators().toArray(new RequestField[0]));
        if (spec.addWaitBehavior()) {
            proxy = proxy.addWaitBehavior();
        }
        Stub stub = RiftDsl.onRequest().willReturn(proxy).build();
        return JsonValue.parse(stub.toJson());
    }

    @Override
    public void replaceStubs(List<StubSpec> specs) {
        replaceStubs(new JsonArray(specs.stream().map(s -> (JsonValue) JsonValue.parse(s.build().toJson())).toList()));
    }

    @Override
    public void replaceStubs(JsonValue stubs) {
        if (!(stubs instanceof JsonArray)) {
            throw new InvalidDefinition("replaceStubs expects a JSON array of stubs for imposter :" + port
                    + " — got " + (stubs == null ? "null" : stubs.getClass().getSimpleName()));
        }
        transport.replaceStubs(port, stubs);
    }

    @Override
    public StubRef stub(String id) {
        return new StubRefImpl(port, transport, new StubAddress.ById(id));
    }

    @Override
    public List<Stub> stubs() {
        return definition().stubs();
    }

    @Override
    public List<RecordedRequest> recorded() {
        return RecordedRequests.readAll(transport.recorded(port), SAVED_REQUESTS);
    }

    @Override
    public RecordedPage recordedPage() {
        return recordedPage(NO_FILTERS);
    }

    @Override
    public RecordedPage recordedPage(MatchClause... filters) {
        return page(transport.recordedSince(port, OptionalLong.empty(), List.of(filters)));
    }

    @Override
    public RecordedPage recordedSince(long cursor) {
        return recordedSince(cursor, NO_FILTERS);
    }

    @Override
    public RecordedPage recordedSince(long cursor, MatchClause... filters) {
        return page(transport.recordedSince(port, OptionalLong.of(cursor), List.of(filters)));
    }

    private static RecordedPage page(RiftTransport.RecordedSlice slice) {
        return new RecordedPage(RecordedRequests.readAll(slice.requests(), SAVED_REQUESTS),
                slice.nextIndex(), slice.truncated());
    }

    @Override
    public List<RecordedRequest> recorded(RequestMatch match) {
        return recorded().stream().filter(r -> PredicateEvaluator.matches(r, match.predicates())).toList();
    }

    @Override
    public void clearRecorded() {
        transport.clearRecorded(port);
    }

    @Override
    public void clearRecorded(MatchClause... filters) {
        transport.clearRecorded(port, List.of(filters));
    }

    @Override
    public void clearProxyResponses() {
        transport.clearProxyResponses(port);
    }

    @Override
    public Scenarios scenarios() {
        return new ScenariosImpl(port, transport);
    }

    @Override
    public Space space(String flowId) {
        FlowIds.require(flowId);
        warnIfSpacesUnusable();
        return new SpaceImpl(port, flowId, transport);
    }

    @Override
    public FlowState flowState(String flowId) {
        FlowIds.require(flowId);
        warnIfFlowStateUnusable();
        return new FlowStateImpl(port, flowId, transport);
    }

    /**
     * Warn (once) if this imposter uses spaces but declares no header-form {@code flowIdSource}: the
     * engine's flow-id source defaults to the port, so a space stub can never match (#40). Advisory —
     * the def is fetched once and a fetch failure is swallowed to DEBUG rather than break the accessor.
     */
    private synchronized void warnIfSpacesUnusable() {
        if (spaceConfigChecked) {
            return;
        }
        spaceConfigChecked = true;
        try {
            if (!FlowStateSupport.hasHeaderFlowIdSource(definition())) {
                LOG.log(Level.WARNING, "imposter on port " + port + " uses spaces but declares no header "
                        + "flow-id source; space stubs can never match (engine flow-id default is imposter_port). "
                        + "Declare flowState(inMemoryFlowState().flowIdFromHeader(\"X-Your-Header\")).");
            }
        } catch (RiftException e) {
            // Advisory only: the SPI's declared def-fetch failures (engine unavailable, not found,
            // unparseable body) downgrade to DEBUG rather than break the accessor. A non-RiftException
            // (a bug, or misuse like a closed transport) is left to propagate loudly.
            LOG.log(Level.DEBUG, "could not evaluate flow-state config for port " + port, e);
        }
    }

    /**
     * Warn (once) if the flow-state API is used on an imposter with no store trigger (an explicit
     * {@code _rift.flowState}, a scenario stub, a {@code _rift.script} stub, or {@code
     * _rift.stateOps}): the engine uses a no-op store, so reads return empty (#40). Advisory, same
     * fetch-once/swallow policy as above.
     */
    private synchronized void warnIfFlowStateUnusable() {
        if (flowStateConfigChecked) {
            return;
        }
        flowStateConfigChecked = true;
        try {
            if (!FlowStateSupport.hasStoreTrigger(definition())) {
                LOG.log(Level.WARNING, "imposter on port " + port + " uses the flow-state API but declares no "
                        + "store trigger (_rift.flowState, a scenario stub, a _rift.script stub, or _rift.stateOps); "
                        + "reads return empty (engine uses a no-op store). Declare flowState(inMemoryFlowState()).");
            }
        } catch (RiftException e) {
            // Advisory only: the SPI's declared def-fetch failures (engine unavailable, not found,
            // unparseable body) downgrade to DEBUG rather than break the accessor. A non-RiftException
            // (a bug, or misuse like a closed transport) is left to propagate loudly.
            LOG.log(Level.DEBUG, "could not evaluate flow-state config for port " + port, e);
        }
    }

    @Override
    public void enable() {
        transport.enable(port);
    }

    @Override
    public void disable() {
        transport.disable(port);
    }

    @Override
    public void delete() {
        transport.deleteImposter(port);
    }

    @Override
    public void verify(RequestMatch match) {
        verify(match, VerificationTimes.atLeast(1));
    }

    @Override
    public void verify(RequestMatch match, VerificationTimes times) {
        // One engine call: the verdict and the diff that explains it come from the same journal
        // snapshot, so a request arriving mid-verify can't make them contradict each other.
        VerificationResult result = verifyResult(match, times, VerifyDetail.CLOSEST);
        if (!result.satisfied()) {
            throw new VerificationException(port, name(), match, times, result);
        }
    }

    @Override
    public VerificationResult verifyResult(RequestMatch match, VerifyDetail... details) {
        return verifyResult(match, VerificationTimes.atLeast(1), details);
    }

    @Override
    public VerificationResult verifyResult(RequestMatch match, VerificationTimes times, VerifyDetail... details) {
        requireRecording();
        return VerificationResult.read(
                transport.verify(port, VerifyBody.build(match, Optional.empty(), details)), times);
    }

    /**
     * The engine counts {@code {0,0}} for a non-recording imposter, which reads as "no traffic"
     * when the truth is "recording is off" — reject up front rather than return a silent always-zero.
     */
    private void requireRecording() {
        if (!definition().recordRequests()) {
            throw new InvalidDefinition("imposter :" + port + " does not record requests — add .record()");
        }
    }

    @Override
    public void verifyNoInteractions() {
        requireRecording();
        List<RecordedRequest> all = recorded();
        if (!all.isEmpty()) {
            RequestMatch noPredicates = List::of;
            throw new VerificationException(port, name(), noPredicates, VerificationTimes.never(), all.size(), all);
        }
    }
}
