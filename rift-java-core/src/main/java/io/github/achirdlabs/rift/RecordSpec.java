package io.github.achirdlabs.rift;

import io.github.achirdlabs.rift.dsl.RequestField;

import java.util.List;
import java.util.Objects;

/**
 * How {@link Imposter#startRecording(String, RecordSpec)} configures a proxy recording: the mode
 * to record in, which request fields seed the recorded stubs' predicates, whether to capture the
 * upstream's response latency as a {@code wait} behavior, and which headers to strip from the
 * recorded predicates so a replayed request matches regardless of that header's value.
 *
 * <p>Instances are immutable, built via {@link #builder()}.
 */
public final class RecordSpec {

    private final RecordMode mode;
    private final List<RequestField> generators;
    private final boolean addWaitBehavior;
    private final List<String> ignoreHeaders;

    private RecordSpec(RecordMode mode, List<RequestField> generators, boolean addWaitBehavior, List<String> ignoreHeaders) {
        this.mode = mode;
        this.generators = generators;
        this.addWaitBehavior = addWaitBehavior;
        this.ignoreHeaders = ignoreHeaders;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The proxy mode to record in. Default {@link RecordMode#ONCE}. */
    public RecordMode mode() {
        return mode;
    }

    /** The request fields that seed the recorded stubs' predicate generator. Default {@code [METHOD, PATH]}. */
    public List<RequestField> generators() {
        return generators;
    }

    /** Whether the recorded stub captures a {@code wait} behavior for the upstream's response latency. Default {@code true}. */
    public boolean addWaitBehavior() {
        return addWaitBehavior;
    }

    /**
     * Header names (case-insensitive) left out of recorded predicates. Default empty. When {@link
     * RequestField#HEADERS} is generated, the engine leaves them out while recording (rift &ge; 0.22.0,
     * rift#1446), so requests that differ only in them are one recording and reach the origin once;
     * they are also stripped from the exported stubs, which is what covers an older engine.
     */
    public List<String> ignoreHeaders() {
        return ignoreHeaders;
    }

    public static final class Builder {

        private RecordMode mode = RecordMode.ONCE;
        private List<RequestField> generators = List.of(RequestField.METHOD, RequestField.PATH);
        private boolean addWaitBehavior = true;
        private List<String> ignoreHeaders = List.of();

        private Builder() {
        }

        public Builder mode(RecordMode mode) {
            this.mode = Objects.requireNonNull(mode, "mode");
            return this;
        }

        public Builder generateBy(RequestField... fields) {
            this.generators = List.of(fields);
            return this;
        }

        public Builder addWaitBehavior(boolean addWaitBehavior) {
            this.addWaitBehavior = addWaitBehavior;
            return this;
        }

        /** @throws IllegalArgumentException if a name is blank */
        public Builder ignoreHeaders(String... headers) {
            for (String header : headers) {
                if (header != null && header.isBlank()) {
                    throw new IllegalArgumentException("an ignored header name must not be blank");
                }
            }
            this.ignoreHeaders = List.of(headers);
            return this;
        }

        public RecordSpec build() {
            return new RecordSpec(mode, generators, addWaitBehavior, ignoreHeaders);
        }
    }
}
