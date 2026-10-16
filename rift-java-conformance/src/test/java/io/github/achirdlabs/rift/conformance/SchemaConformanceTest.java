package io.github.achirdlabs.rift.conformance;

import com.networknt.schema.InputFormat;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.github.achirdlabs.rift.json.JsonObject;
import io.github.achirdlabs.rift.json.JsonValue;
import io.github.achirdlabs.rift.model.ImposterDefinition;
import io.github.achirdlabs.rift.model.PredicateOperation;
import io.github.achirdlabs.rift.model.WireFormatException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The schema gate: the engine's grammar. rift 0.22.0 ships the imposter document's JSON Schema (draft
 * 2020-12, generated from the engine's serde types, rift#1342) in the sdk-conformance tarball. Every
 * object in it is closed ({@code additionalProperties: false}) except the two provider maps, so a
 * document that validates carries no key the engine never reads.
 *
 * <ul>
 *   <li>what the typed DSL builds for each fixture validates;</li>
 *   <li>what the codec writes back after reading each fixture validates;</li>
 *   <li>every key the model claims as its own ({@code MODELED_KEYS}) is a property the schema
 *       declares for that type (rift#1463's {@code backend} was that drift), and the predicate
 *       parameter set is exactly the schema's non-operator predicate keys (rift#1325's
 *       {@code keyCaseSensitive} was missing from it).</li>
 * </ul>
 *
 * <p>An older corpus without {@code schema/} skips these checks: the file's presence decides (#300).
 * The CI lane (RIFT_IT=1) fails instead when a corpus for rift 0.22.0 or later lacks it, since that
 * means a broken tarball, not an old engine.
 */
class SchemaConformanceTest {

    private static final Set<String> PREDICATE_OPERATORS = Set.of(
            "equals", "deepEquals", "contains", "startsWith", "endsWith", "matches", "exists", "not", "or", "and", "inject");

    /** Model type → the schema definition its wire object is ({@code ""} is the root, the imposter). */
    private static final Map<String, String> MODELED_KEYS_DEFS = new LinkedHashMap<>();

    static {
        MODELED_KEYS_DEFS.put("ImposterDefinition", "");
        MODELED_KEYS_DEFS.put("Stub", "Stub");
        MODELED_KEYS_DEFS.put("IsResponse", "IsResponse");
        MODELED_KEYS_DEFS.put("ProxyResponse", "ProxyResponse");
        MODELED_KEYS_DEFS.put("RiftConfig", "RiftConfig");
        MODELED_KEYS_DEFS.put("RiftFlowStateConfig", "RiftFlowStateConfig");
        MODELED_KEYS_DEFS.put("RiftResponseExtension", "RiftResponseExtension");
    }

    @TestFactory
    Stream<DynamicTest> dslOutputValidates() {
        Corpus corpus = Corpus.loadOrSkip();
        ImposterSchema schema = schemaOrSkip(corpus);
        return corpus.fixtures().stream().map(fx -> DynamicTest.dynamicTest(
                "schema(dsl): " + fx.name(),
                () -> {
                    if (!DslFixtures.has(fx.number())) {
                        Assumptions.abort("fixture " + fx.number() + " has no DSL builder (DslExpressibilityTest reports why)");
                    }
                    ImposterDefinition built = DslFixtures.build(fx.number()).orElseThrow();
                    assertValid(schema, "DSL-built fixture " + fx.file().getFileName(), built.toJson());
                }));
    }

    @TestFactory
    Stream<DynamicTest> codecOutputValidates() {
        Corpus corpus = Corpus.loadOrSkip();
        ImposterSchema schema = schemaOrSkip(corpus);
        return corpus.fixtures().stream().map(fx -> DynamicTest.dynamicTest(
                "schema(codec): " + fx.name(),
                () -> {
                    String written;
                    try {
                        written = fx.imposter().toJson();
                    } catch (WireFormatException e) {
                        // only a tracked gap may fail to parse; ParseFidelityTest holds it to that
                        if (!KnownFidelityGaps.contains(fx.number())) {
                            throw e;
                        }
                        Assumptions.abort("fixture " + fx.number() + " is a known fidelity gap: " + e.getMessage());
                        return;
                    }
                    assertValid(schema, "fromJson→toJson of " + fx.file().getFileName(), written);
                }));
    }

    @Test
    void modeledKeysAreKeysTheEngineReads() throws ReflectiveOperationException {
        ImposterSchema schema = schemaOrSkip(Corpus.loadOrSkip());
        Map<String, Set<String>> unread = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : MODELED_KEYS_DEFS.entrySet()) {
            Set<String> modeled = modeledKeys(entry.getKey());
            Set<String> stray = new TreeSet<>(modeled);
            stray.removeAll(schema.properties(entry.getValue()));
            if (!stray.isEmpty()) {
                unread.put(entry.getKey(), stray);
            }
        }
        assertEquals(Map.of(), unread, "keys the model claims that the engine's schema does not declare");
    }

    @Test
    void predicateParametersAreExactlyTheSchemasNonOperatorKeys() {
        // An unreserved parameter would be read as a second operation key and refused, so both
        // directions matter here, unlike MODELED_KEYS, where an unmodeled key rides in extra.
        ImposterSchema schema = schemaOrSkip(Corpus.loadOrSkip());
        Set<String> parameters = new TreeSet<>(schema.properties("Predicate"));
        parameters.removeAll(PREDICATE_OPERATORS);
        assertEquals(parameters, new TreeSet<>(PredicateOperation.RESERVED_KEYS));
    }

    @Test
    void theValidatorRejectsAKeyTheEngineNeverReads() {
        ImposterSchema schema = schemaOrSkip(Corpus.loadOrSkip());
        String valid = "{\"protocol\":\"http\",\"port\":4545,\"stubs\":[{\"predicates\":[{\"equals\":{\"path\":\"/a\"}}],"
                + "\"responses\":[{\"is\":{\"statusCode\":200,\"body\":\"ok\"}}]}]}";
        assertEquals(List.of(), schema.validate(valid), "a minimal imposter is valid");

        List<String> typo = schema.validate(valid.replace("\"predicates\"", "\"predicate\""));
        assertTrue(typo.stream().anyMatch(e -> e.startsWith("$.stubs[0]") && e.contains("'predicate'")),
                () -> "an unread stub key is refused: " + typo);

        // reaches a closed definition through $ref ($defs/RiftConfig), and checks types, not only key names
        List<String> nested = schema.validate(valid.replace("\"port\":4545,", "\"port\":4545,\"_rift\":{\"flowStat\":{}},"));
        assertTrue(nested.stream().anyMatch(e -> e.startsWith("$._rift") && e.contains("'flowStat'")),
                () -> "an unread _rift key is refused: " + nested);
        List<String> mistyped = schema.validate(valid.replace("\"statusCode\":200", "\"statusCode\":true"));
        assertTrue(mistyped.stream().anyMatch(e -> e.contains("statusCode")), () -> "a boolean statusCode is refused: " + mistyped);

        List<String> misplaced = schema.validate(valid.replace("\"body\":\"ok\"", "\"body\":\"ok\",\"allowCORS\":true"));
        assertTrue(misplaced.stream().anyMatch(e -> e.contains("allowCORS")),
                () -> "an imposter key inside an is response is refused: " + misplaced);
    }

    private static void assertValid(ImposterSchema schema, String what, String json) {
        List<String> errors = schema.validate(json);
        assertEquals(List.of(), errors, what + " does not match the engine's imposter schema");
    }

    private static ImposterSchema schemaOrSkip(Corpus corpus) {
        Path path = corpus.schema().orElse(null);
        if (path == null && Corpus.ciLaneRequiresCorpus() && shipsSchema(corpus.engineVersion())) {
            throw new IllegalStateException("the rift " + corpus.engineVersion() + " corpus has no "
                    + "schema/imposter.schema.json, which every tarball from 0.22.0 carries — the download is broken");
        }
        Assumptions.assumeTrue(path != null,
                "this corpus has no schema/imposter.schema.json (it ships from rift 0.22.0, rift#1342)");
        return ImposterSchema.load(path);
    }

    private static boolean shipsSchema(String engineVersion) {
        String[] parts = engineVersion.split("[.-]");
        int major = Integer.parseInt(parts[0]);
        int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
        return major > 0 || minor >= 22;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> modeledKeys(String modelType) throws ReflectiveOperationException {
        Field field = Class.forName("io.github.achirdlabs.rift.model." + modelType).getDeclaredField("MODELED_KEYS");
        field.setAccessible(true);
        return (Set<String>) field.get(null);
    }

    /** The compiled schema plus the property names it declares per definition. */
    private record ImposterSchema(JsonSchema compiled, JsonObject document) {

        static ImposterSchema load(Path path) {
            String text;
            try {
                text = Files.readString(path);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read " + path, e);
            }
            JsonSchema compiled = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(text);
            return new ImposterSchema(compiled, (JsonObject) JsonValue.parse(text));
        }

        /** Every violation, each prefixed with the instance path it is at; empty when the document is valid. */
        List<String> validate(String json) {
            Set<ValidationMessage> messages = compiled.validate(json, InputFormat.JSON);
            return messages.stream().map(m -> m.getInstanceLocation() + ": " + m.getMessage()).sorted().toList();
        }

        /** The properties {@code def} declares ({@code ""} for the root object). */
        Set<String> properties(String def) {
            JsonObject node = def.isEmpty() ? document : (JsonObject) ((JsonObject) document.get("$defs")).get(def);
            if (node == null || !(node.get("properties") instanceof JsonObject props)) {
                throw new IllegalStateException("the schema has no properties for " + (def.isEmpty() ? "the root" : def));
            }
            return props.fields().keySet();
        }
    }
}
