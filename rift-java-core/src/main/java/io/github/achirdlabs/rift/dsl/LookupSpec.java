package io.github.achirdlabs.rift.dsl;

import io.github.achirdlabs.rift.json.JsonNumber;
import io.github.achirdlabs.rift.json.JsonObject;
import io.github.achirdlabs.rift.json.JsonString;
import io.github.achirdlabs.rift.json.JsonValue;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * A single {@code lookup} behavior entry under construction, produced by {@link
 * RiftDsl#lookupKey(String)}: resolves a key extracted from the request against a data source (a
 * CSV file, currently the only source) and injects the matching row into the response.
 *
 * <p>There is no typed {@code model.Lookup} — {@link IsSpec#lookup(LookupSpec...)} embeds this
 * spec's built {@link #build()} JSON directly into a {@code Behavior.Unknown} array, so it
 * round-trips losslessly while still emitting the correct wire shape.
 *
 * <p>Instances are immutable: every chain method returns a new {@code LookupSpec}.
 */
public final class LookupSpec {

    /** The bare-string {@code from} ({@code "body"}, {@code "path"}, …) or the object form ({@code {"headers": name}}). */
    private final JsonValue keyFrom;
    private final Optional<ExtractionSpec> using;
    private final OptionalInt index;
    private final Optional<String> csvPath;
    private final Optional<String> csvKeyColumn;
    private final Optional<Character> csvDelimiter;
    private final Optional<String> into;

    private LookupSpec(
            JsonValue keyFrom,
            Optional<ExtractionSpec> using,
            OptionalInt index,
            Optional<String> csvPath,
            Optional<String> csvKeyColumn,
            Optional<Character> csvDelimiter,
            Optional<String> into) {
        this.keyFrom = keyFrom;
        this.using = using;
        this.index = index;
        this.csvPath = csvPath;
        this.csvKeyColumn = csvKeyColumn;
        this.csvDelimiter = csvDelimiter;
        this.into = into;
    }

    private static LookupSpec keyed(JsonValue from) {
        return new LookupSpec(from, Optional.empty(), OptionalInt.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty());
    }

    static LookupSpec key(String from) {
        return keyed(new JsonString(Objects.requireNonNull(from, "from")));
    }

    /** Keys the lookup on a named query parameter — the object {@code from} form {@code {"query": name}}. */
    static LookupSpec keyFromQuery(String name) {
        return keyed(JsonObject.builder().put("query", new JsonString(Objects.requireNonNull(name, "name"))).build());
    }

    /** Keys the lookup on a named request header — the object {@code from} form {@code {"headers": name}}. */
    static LookupSpec keyFromHeader(String name) {
        return keyed(JsonObject.builder().put("headers", new JsonString(Objects.requireNonNull(name, "name"))).build());
    }

    /** Sets the extraction method (regex/jsonpath/xpath) applied to the key field. */
    public LookupSpec using(ExtractionSpec extraction) {
        return new LookupSpec(keyFrom, Optional.of(extraction), index, csvPath, csvKeyColumn, csvDelimiter, into);
    }

    /**
     * Which part of the extraction keys the row — {@code key.index}, with Mountebank's meaning: for a
     * {@link RiftDsl#regex regex}, {@code 0} is the whole match and {@code n} the {@code n}th capture
     * group; for {@link RiftDsl#jsonPath jsonPath} / {@link RiftDsl#xPath xPath}, the {@code n}th
     * selected value (0-based). Unset, the engine takes the first capture group (or the whole match
     * when the pattern has none) and the first selected value.
     *
     * @throws IllegalArgumentException if {@code index} is negative
     */
    public LookupSpec index(int index) {
        if (index < 0) {
            throw new IllegalArgumentException("lookup key index must be >= 0, was " + index);
        }
        return new LookupSpec(keyFrom, using, OptionalInt.of(index), csvPath, csvKeyColumn, csvDelimiter, into);
    }

    /** Resolves the key against the given CSV file, matching rows on {@code keyColumn}. */
    public LookupSpec fromCsv(String path, String keyColumn) {
        return new LookupSpec(keyFrom, using, index, Optional.of(path), Optional.of(keyColumn), Optional.empty(), into);
    }

    /**
     * {@link #fromCsv(String, String)} for a file whose fields are separated by {@code delimiter}
     * rather than the engine's default {@code ,} — {@code csv.delimiter}.
     *
     * @throws IllegalArgumentException if {@code delimiter} is half of a surrogate pair: it has no
     *                                  encoding on its own, so it would reach the engine as {@code ?}
     */
    public LookupSpec fromCsv(String path, String keyColumn, char delimiter) {
        if (Character.isSurrogate(delimiter)) {
            throw new IllegalArgumentException(String.format(
                    "csv delimiter must be a whole character, not half of a surrogate pair (U+%04X)", (int) delimiter));
        }
        return new LookupSpec(keyFrom, using, index, Optional.of(path), Optional.of(keyColumn), Optional.of(delimiter), into);
    }

    /** Names the {@code ${token}} placeholder the matched row is injected under. */
    public LookupSpec into(String token) {
        return new LookupSpec(keyFrom, using, index, csvPath, csvKeyColumn, csvDelimiter, Optional.of(token));
    }

    /**
     * Builds this lookup entry's raw wire JSON: {@code {"key":..., "fromDataSource":..., "into":...}}.
     * {@code key.index} and {@code csv.delimiter} appear only when set, so an entry without them is
     * byte-identical to what the two-argument form always produced.
     */
    JsonValue build() {
        String named = "lookupKey(" + keyFrom.toJson() + ")";
        ExtractionSpec extraction = using.orElseThrow(() -> new IllegalStateException(named + " requires .using(...)"));
        String path = csvPath.orElseThrow(() -> new IllegalStateException(named + " requires .fromCsv(...)"));
        String keyColumn = csvKeyColumn.orElseThrow();
        String token = into.orElseThrow(() -> new IllegalStateException(named + " requires .into(...)"));

        JsonObject.Builder key = JsonObject.builder()
                .put("from", keyFrom)
                .put("using", extraction.toJsonValue());
        index.ifPresent(i -> key.put("index", JsonNumber.of(i)));
        JsonObject.Builder csv = JsonObject.builder()
                .put("path", new JsonString(path))
                .put("keyColumn", new JsonString(keyColumn));
        csvDelimiter.ifPresent(d -> csv.put("delimiter", new JsonString(String.valueOf(d))));
        JsonObject fromDataSource = JsonObject.builder().put("csv", csv.build()).build();

        return JsonObject.builder()
                .put("key", key.build())
                .put("fromDataSource", fromDataSource)
                .put("into", new JsonString(token))
                .build();
    }
}
