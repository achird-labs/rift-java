package io.github.achirdlabs.rift;

import io.github.achirdlabs.rift.error.CommunicationError;
import io.github.achirdlabs.rift.json.JsonArray;
import io.github.achirdlabs.rift.json.JsonNumber;
import io.github.achirdlabs.rift.json.JsonObject;
import io.github.achirdlabs.rift.json.JsonString;
import io.github.achirdlabs.rift.json.JsonValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The outcome of {@link Rift#applyConfig(JsonValue)}: the ports of the imposters the reconcile
 * created, replaced, patched in place, paused or resumed, or deleted; the ones it could not apply; and
 * what the engine reported deliberately not applying.
 *
 * @param created     ports of imposters created
 * @param replaced    ports of imposters replaced wholesale
 * @param stubPatched ports of imposters whose stubs were patched in place, runtime state kept
 * @param toggled     ports of imposters whose only change was {@code enabled} (paused or resumed);
 *                    rift &ge; 0.20.0, always empty from an older engine
 * @param deleted     ports of imposters deleted because the config no longer declares them
 * @param failed      imposters the engine could not apply; the other ports are reconciled regardless
 * @param warnings    parts of the request the engine deliberately did not apply
 * @param intercept   how many intercept rules a reload kept, when the engine reports it
 */
public record ApplyResult(List<Integer> created, List<Integer> replaced, List<Integer> stubPatched,
                          List<Integer> toggled, List<Integer> deleted, List<ApplyFailure> failed,
                          List<String> warnings, Optional<InterceptCounts> intercept) {

    /** {@code "<port>: <message>"}, the engine's rendering of one failure over HTTP. */
    private static final Pattern PORT_PREFIX = Pattern.compile("(\\d{1,5}): (.*)", Pattern.DOTALL);
    private static final int MAX_PORT = 65535;
    /** {@code "auto-assign: <message>"}, a failure of a config that declared no port. */
    private static final String AUTO_ASSIGN_PREFIX = "auto-assign: ";

    public ApplyResult {
        created = List.copyOf(created);
        replaced = List.copyOf(replaced);
        stubPatched = List.copyOf(stubPatched);
        toggled = List.copyOf(toggled);
        deleted = List.copyOf(deleted);
        failed = List.copyOf(failed);
        warnings = List.copyOf(warnings);
        intercept = Objects.requireNonNull(intercept, "intercept");
    }

    /**
     * The shape before {@code toggled} (rift-java 0.3.5), kept for source and binary compatibility;
     * {@code toggled} is empty.
     */
    public ApplyResult(List<Integer> created, List<Integer> replaced, List<Integer> stubPatched,
                       List<Integer> deleted, List<ApplyFailure> failed, List<String> warnings,
                       Optional<InterceptCounts> intercept) {
        this(created, replaced, stubPatched, List.of(), deleted, failed, warnings, intercept);
    }

    /**
     * One imposter the engine could not apply.
     *
     * @param port    the imposter's port; empty when none can be attributed — a config that declared
     *                no port (so had one auto-assigned), or a failure the engine rendered in a form this
     *                SDK does not read, whose whole text is then the message
     * @param message the engine's reason
     */
    public record ApplyFailure(OptionalInt port, String message) {
        public ApplyFailure {
            Objects.requireNonNull(port, "port");
            Objects.requireNonNull(message, "message");
        }
    }

    /** How many file-seeded and runtime intercept rules survived a reload (rift &gt; 0.19.0, admin API only). */
    public record InterceptCounts(int rulesSeeded, int rulesRuntime) { }

    /**
     * Whether the reconcile created, replaced, patched, toggled and deleted nothing. Says nothing about {@link
     * #failed()}: a config whose every imposter failed also changed nothing.
     */
    public boolean changedNothing() {
        return created.isEmpty() && replaced.isEmpty() && stubPatched.isEmpty() && toggled.isEmpty()
                && deleted.isEmpty();
    }

    /**
     * Reads the engine's apply report. The five port fields are arrays of ports on every surface; an
     * absent one is empty ({@code toggled} is absent before rift 0.20.0). Failures are {@code
     * "<port>: <message>"} strings over HTTP and {@code {port, error}} objects over the C-ABI, port
     * {@code 0} meaning an auto-assigned config.
     *
     * @throws CommunicationError if the body is not a JSON object, or a field is not the shape the
     *         engine sends — a count where it sends ports, say: no all-zeros result may stand in for a
     *         report that could not be read
     */
    public static ApplyResult read(JsonValue value) {
        if (!(value instanceof JsonObject obj)) {
            throw new CommunicationError("rift engine's apply report was not a JSON object: " + value.toJson());
        }
        return new ApplyResult(ports(obj, "created"), ports(obj, "replaced"), ports(obj, "stubPatched"),
                ports(obj, "toggled"), ports(obj, "deleted"), failures(obj), warnings(obj), interceptCounts(obj));
    }

    private static List<Integer> ports(JsonObject obj, String key) {
        JsonValue field = obj.get(key);
        if (field == null) {
            return List.of();
        }
        if (!(field instanceof JsonArray array)) {
            throw malformed(key, "an array of ports", field);
        }
        List<Integer> ports = new ArrayList<>();
        for (JsonValue item : array.items()) {
            ports.add(port(key, item));
        }
        return ports;
    }

    private static int port(String key, JsonValue item) {
        if (item instanceof JsonNumber n) {
            double value = n.asDouble();
            if (value == Math.rint(value) && value >= 0 && value <= MAX_PORT) {
                return (int) value;
            }
        }
        throw malformed(key, "a port number", item);
    }

    private static List<ApplyFailure> failures(JsonObject obj) {
        JsonValue field = obj.get("failed");
        if (field == null) {
            return List.of();
        }
        if (!(field instanceof JsonArray array)) {
            throw malformed("failed", "an array", field);
        }
        List<ApplyFailure> failures = new ArrayList<>();
        for (JsonValue item : array.items()) {
            failures.add(failure(item));
        }
        return failures;
    }

    private static ApplyFailure failure(JsonValue item) {
        if (item instanceof JsonString text) {
            String value = text.value();
            Matcher prefixed = PORT_PREFIX.matcher(value);
            if (prefixed.matches() && Integer.parseInt(prefixed.group(1)) <= MAX_PORT) {
                return new ApplyFailure(portOrAutoAssigned(Integer.parseInt(prefixed.group(1))), prefixed.group(2));
            }
            if (value.startsWith(AUTO_ASSIGN_PREFIX)) {
                return new ApplyFailure(OptionalInt.empty(), value.substring(AUTO_ASSIGN_PREFIX.length()));
            }
            // A rendering this SDK does not know: keep the whole reason rather than lose it.
            return new ApplyFailure(OptionalInt.empty(), value);
        }
        if (item instanceof JsonObject failure && failure.get("error") instanceof JsonString error) {
            return new ApplyFailure(portOrAutoAssigned(port("failed", failure.get("port"))), error.value());
        }
        throw malformed("failed", "a failure string or {port, error} object", item);
    }

    private static OptionalInt portOrAutoAssigned(int port) {
        return port == 0 ? OptionalInt.empty() : OptionalInt.of(port);
    }

    private static List<String> warnings(JsonObject obj) {
        JsonValue field = obj.get("warnings");
        if (field == null) {
            return List.of();
        }
        if (!(field instanceof JsonArray array)) {
            throw malformed("warnings", "an array of strings", field);
        }
        List<String> warnings = new ArrayList<>();
        for (JsonValue item : array.items()) {
            if (!(item instanceof JsonString text)) {
                throw malformed("warnings", "a string", item);
            }
            warnings.add(text.value());
        }
        return warnings;
    }

    private static Optional<InterceptCounts> interceptCounts(JsonObject obj) {
        JsonValue field = obj.get("intercept");
        if (field == null) {
            return Optional.empty();
        }
        if (field instanceof JsonObject counts
                && counts.get("rulesSeeded") instanceof JsonNumber seeded
                && counts.get("rulesRuntime") instanceof JsonNumber runtime) {
            return Optional.of(new InterceptCounts(seeded.asInt(), runtime.asInt()));
        }
        throw malformed("intercept", "{rulesSeeded, rulesRuntime}", field);
    }

    private static CommunicationError malformed(String key, String expected, JsonValue actual) {
        return new CommunicationError("rift engine's apply report has '" + key + "' that is not " + expected
                + ": " + (actual == null ? "absent" : actual.toJson()));
    }
}
