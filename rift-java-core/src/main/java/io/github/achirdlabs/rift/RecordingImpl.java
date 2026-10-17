package io.github.achirdlabs.rift;

import io.github.achirdlabs.rift.error.CommunicationError;
import io.github.achirdlabs.rift.json.JsonArray;
import io.github.achirdlabs.rift.json.JsonObject;
import io.github.achirdlabs.rift.json.JsonValue;
import io.github.achirdlabs.rift.model.ImposterDefinition;
import io.github.achirdlabs.rift.model.Predicate;
import io.github.achirdlabs.rift.model.PredicateOperation;
import io.github.achirdlabs.rift.model.PredicateParameters;
import io.github.achirdlabs.rift.model.Stub;
import io.github.achirdlabs.rift.transport.RiftTransport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link Recording} over a {@link RiftTransport}. Stopping (and persisting) exports the
 * imposter's current definition with the proxy removed — a non-mutating {@code GET
 * /imposters/{port}?replayable=true&removeProxies=true} — strips {@link RecordSpec#ignoreHeaders()}
 * from the recorded predicates, and swaps the live proxy stub for the (stripped) recorded stubs
 * with a {@code PUT /imposters/{port}/stubs}. The export itself is safe to repeat (the engine call
 * is non-mutating); only the swap is guarded so a second {@link #stop()}/{@link #persist} does not
 * re-apply it.
 */
final class RecordingImpl implements Recording {

    private final int port;
    private final RiftTransport transport;
    private final String originUrl;
    private final RecordSpec spec;
    private final Set<String> ignoreHeadersLower;
    private final AtomicBoolean swapped = new AtomicBoolean(false);

    RecordingImpl(int port, RiftTransport transport, String originUrl, RecordSpec spec) {
        this.port = port;
        this.transport = transport;
        this.originUrl = originUrl;
        this.spec = spec;
        Set<String> lower = new HashSet<>();
        for (String header : spec.ignoreHeaders()) {
            lower.add(header.toLowerCase(Locale.ROOT));
        }
        this.ignoreHeadersLower = lower;
    }

    @Override
    public List<Stub> stop() {
        Export export = export();
        swap(export);
        return export.stubs();
    }

    @Override
    public List<Stub> snapshot() {
        return export().stubs();
    }

    @Override
    public void persist(Path path) {
        Export export = export();
        swap(export);
        try {
            Files.writeString(path, export.fullDefinitionJson().toJson());
        } catch (IOException e) {
            throw new CommunicationError(
                    "failed to persist recording for imposter " + port + " to " + path, e);
        }
    }

    private void swap(Export export) {
        // Act-then-flag: mark the swap done only after replaceStubs succeeds, so a retry after a
        // transient transport failure actually re-attempts the swap rather than silently skipping it.
        if (swapped.get()) {
            return;
        }
        transport.replaceStubs(port, export.stubsJson());
        swapped.set(true);
    }

    /** Fetches the current (proxy-removed) definition and strips {@code ignoreHeaders} from every recorded predicate. */
    private Export export() {
        JsonValue raw = transport.getImposter(port, true, true);
        if (!(raw instanceof JsonObject rawObj)) {
            throw new CommunicationError(
                    "rift admin API returned a non-object imposter export for imposter " + port + ": " + raw.toJson());
        }
        List<Stub> rawStubs = ImposterDefinition.fromJson(raw.toJson()).stubs();
        List<Stub> stripped = rawStubs.stream().map(this::stripStub).toList();
        JsonArray stubsJson = new JsonArray(stripped.stream().map(s -> (JsonValue) JsonValue.parse(s.toJson())).toList());

        JsonObject.Builder full = JsonObject.builder();
        rawObj.fields().forEach((key, value) -> full.put(key, key.equals("stubs") ? stubsJson : value));
        return new Export(stripped, stubsJson, full.build());
    }

    private Stub stripStub(Stub stub) {
        if (ignoreHeadersLower.isEmpty()) {
            return stub;
        }
        List<Predicate> predicates = stub.predicates().stream().map(this::stripPredicate).toList();
        return new Stub(stub.name(), stub.scenarioName(), stub.requiredScenarioState(), stub.newScenarioState(), stub.space(),
                stub.id(), stub.routePattern(), predicates, stub.responses(), stub.recordedFrom(), stub.verify(),
                stub.extra());
    }

    private Predicate stripPredicate(Predicate predicate) {
        if (predicate.operation() instanceof PredicateOperation.DeepEquals e) {
            Map<String, JsonValue> stripped = stripHeaders(e.fields());
            if (stripped != e.fields()) {
                return withKeptHeadersAsEquals(predicate.parameters(), stripped);
            }
        }
        return new Predicate(predicate.parameters(), stripOperation(predicate.operation()));
    }

    /**
     * A {@code deepEquals} over {@code headers} demands the request's exact header set, so a request
     * still carrying an ignored header would never match it. Since rift 0.22.0 a {@code headers}
     * generator records exactly that (rift#1329), so the kept headers move to an {@code equals}, the
     * shape the engine's own generator {@code ignore} records; the other fields stay {@code deepEquals}.
     */
    private static Predicate withKeptHeadersAsEquals(PredicateParameters parameters, Map<String, JsonValue> stripped) {
        Map<String, JsonValue> rest = new LinkedHashMap<>(stripped);
        JsonValue keptHeaders = rest.remove("headers");
        List<Predicate> parts = new ArrayList<>();
        if (!rest.isEmpty()) {
            parts.add(new Predicate(parameters, new PredicateOperation.DeepEquals(rest)));
        }
        if (keptHeaders != null) {
            parts.add(new Predicate(parameters, new PredicateOperation.Equals(Map.of("headers", keptHeaders))));
        }
        if (parts.size() == 1) {
            return parts.get(0);
        }
        if (parts.isEmpty()) {
            // every recorded header was ignored, and nothing else was recorded: the predicate constrains nothing
            return new Predicate(parameters, new PredicateOperation.Equals(Map.of()));
        }
        return new Predicate(PredicateParameters.EMPTY, new PredicateOperation.And(parts));
    }

    private PredicateOperation stripOperation(PredicateOperation operation) {
        if (operation instanceof PredicateOperation.Equals e) {
            return new PredicateOperation.Equals(stripHeaders(e.fields()));
        }
        if (operation instanceof PredicateOperation.DeepEquals e) {
            return new PredicateOperation.DeepEquals(stripHeaders(e.fields()));
        }
        if (operation instanceof PredicateOperation.Contains e) {
            return new PredicateOperation.Contains(stripHeaders(e.fields()));
        }
        if (operation instanceof PredicateOperation.StartsWith e) {
            return new PredicateOperation.StartsWith(stripHeaders(e.fields()));
        }
        if (operation instanceof PredicateOperation.EndsWith e) {
            return new PredicateOperation.EndsWith(stripHeaders(e.fields()));
        }
        if (operation instanceof PredicateOperation.Matches e) {
            return new PredicateOperation.Matches(stripHeaders(e.fields()));
        }
        if (operation instanceof PredicateOperation.Exists e) {
            return new PredicateOperation.Exists(stripHeaders(e.fields()));
        }
        if (operation instanceof PredicateOperation.Not n) {
            return new PredicateOperation.Not(stripPredicate(n.predicate()));
        }
        if (operation instanceof PredicateOperation.Or o) {
            return new PredicateOperation.Or(o.predicates().stream().map(this::stripPredicate).toList());
        }
        if (operation instanceof PredicateOperation.And a) {
            return new PredicateOperation.And(a.predicates().stream().map(this::stripPredicate).toList());
        }
        return operation;
    }

    /** Removes {@link #ignoreHeadersLower} entries from a predicate field map's {@code headers} sub-object, if present. */
    private Map<String, JsonValue> stripHeaders(Map<String, JsonValue> fields) {
        if (!(fields.get("headers") instanceof JsonObject headersObj)) {
            return fields;
        }
        JsonObject.Builder kept = JsonObject.builder();
        boolean changed = false;
        for (Map.Entry<String, JsonValue> entry : headersObj.fields().entrySet()) {
            if (ignoreHeadersLower.contains(entry.getKey().toLowerCase(Locale.ROOT))) {
                changed = true;
            } else {
                kept.put(entry.getKey(), entry.getValue());
            }
        }
        if (!changed) {
            return fields;
        }
        JsonObject keptHeaders = kept.build();
        Map<String, JsonValue> next = new LinkedHashMap<>(fields);
        if (keptHeaders.fields().isEmpty()) {
            // An empty {"headers": {}} would still require a headers object to be present on the
            // request, which is not what "ignore this header" means — drop the key entirely.
            next.remove("headers");
        } else {
            next.put("headers", keptHeaders);
        }
        return next;
    }

    private record Export(List<Stub> stubs, JsonArray stubsJson, JsonObject fullDefinitionJson) {
    }
}
