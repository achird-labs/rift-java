package io.github.achirdlabs.rift.verify;

import io.github.achirdlabs.rift.RecordedRequest;
import io.github.achirdlabs.rift.json.JsonString;
import io.github.achirdlabs.rift.json.JsonValue;
import io.github.achirdlabs.rift.model.Predicate;
import io.github.achirdlabs.rift.model.PredicateOperation;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Thrown by {@code Imposter.verify}/{@code Space.verify} when the recorded traffic does not
 * satisfy the expected {@link VerificationTimes}. Extends {@link AssertionError} (rather than the
 * {@code io.github.achirdlabs.rift.error} hierarchy) so test runners report it as a failed
 * assertion, matching WireMock's {@code VerificationException}.
 *
 * <p>The message shows a near-miss diff in the style of WireMock's verification failures. When the
 * failure came from {@code verify}, {@link #result()} carries the engine's structured verdict so a
 * downstream reporter can render its own diff from values instead of scraping this message.
 */
public final class VerificationException extends AssertionError {

    private static final long serialVersionUID = 1L;

    private static final int MAX_DIFF_LINES = 10;

    // transient: VerificationResult transitively holds JsonValue, which is not Serializable — a test
    // runner serializing this exception across a worker boundary would otherwise fail outright.
    private final transient Optional<VerificationResult> result;

    /** Raised by {@code verify}: the engine's structured verdict, including its closest non-match. */
    public VerificationException(
            int port,
            Optional<String> name,
            RequestMatch match,
            VerificationTimes times,
            VerificationResult result) {
        super(buildMessage(port, name, match, times, result));
        this.result = Optional.of(result);
    }

    /**
     * Raised by {@code verifyNoInteractions}, which asserts emptiness rather than a predicate match
     * and so has no engine verdict to carry — {@code recorded} is listed most recent first.
     */
    public VerificationException(
            int port,
            Optional<String> name,
            RequestMatch match,
            VerificationTimes times,
            int actualCount,
            List<RecordedRequest> recorded) {
        super(buildMessage(port, name, match, times, actualCount, recorded));
        this.result = Optional.empty();
    }

    /**
     * The engine's structured verdict, or empty when this came from {@code verifyNoInteractions}
     * (or when this exception was deserialized — {@link #result} does not survive the wire).
     */
    public Optional<VerificationResult> result() {
        return result == null ? Optional.empty() : result;
    }

    private static String buildMessage(
            int port,
            Optional<String> name,
            RequestMatch match,
            VerificationTimes times,
            VerificationResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append(header(port, name, match.predicates(), times, result.matched()));
        if (result.total() == 0) {
            sb.append("0 recorded requests.");
            return sb.toString();
        }
        sb.append(result.total()).append(" recorded requests.");
        result.closest().ifPresent(closest -> {
            sb.append("\nClosest miss:\n  ✗ ").append(requestLine(closest.request()));
            for (FailedPredicate failed : closest.failedPredicates()) {
                sb.append("\n      failed ").append(failed.predicate().toJson())
                        .append("  —  actual ").append(failed.actual().toJson());
            }
        });
        return sb.toString();
    }

    private static String header(
            int port, Optional<String> name, List<Predicate> predicates, VerificationTimes times, int actualCount) {
        return "Verification failed for imposter :" + port + " (\"" + name.orElse("") + "\")\n"
                + "Expected: " + requestSummary(predicates) + "  —  " + times.describe()
                + ", but was " + actualCount + ".\n\n";
    }

    private static String requestLine(RecordedRequest request) {
        return request.summary();
    }

    private static String buildMessage(
            int port,
            Optional<String> name,
            RequestMatch match,
            VerificationTimes times,
            int actualCount,
            List<RecordedRequest> recorded) {
        List<Predicate> predicates = match.predicates();
        StringBuilder sb = new StringBuilder(header(port, name, predicates, times, actualCount));

        if (recorded.isEmpty()) {
            sb.append("0 recorded requests.");
            return sb.toString();
        }

        sb.append(recorded.size()).append(" recorded requests, most recent first:\n");
        int shown = Math.min(MAX_DIFF_LINES, recorded.size());
        for (int i = 0; i < shown; i++) {
            sb.append("  ✗ ").append(requestLine(recorded.get(recorded.size() - 1 - i)));
            if (i < shown - 1) {
                sb.append("\n");
            }
        }
        if (recorded.size() > shown) {
            sb.append("\n  … and ").append(recorded.size() - shown).append(" more");
        }
        return sb.toString();
    }

    /** The expected method+path from a simple {@code equals} predicate, if determinable; else a generic label. */
    private static String requestSummary(List<Predicate> predicates) {
        String method = null;
        String path = null;
        for (Predicate predicate : predicates) {
            Map<String, JsonValue> fields = equalsFieldsOf(predicate.operation());
            if (fields.get("method") instanceof JsonString s) {
                method = s.value();
            }
            if (fields.get("path") instanceof JsonString s) {
                path = s.value();
            }
        }
        if (method == null && path == null) {
            return "the given request";
        }
        return (method == null ? "*" : method) + " " + (path == null ? "*" : path);
    }

    private static Map<String, JsonValue> equalsFieldsOf(PredicateOperation op) {
        return op instanceof PredicateOperation.Equals e ? e.fields() : Map.of();
    }
}
