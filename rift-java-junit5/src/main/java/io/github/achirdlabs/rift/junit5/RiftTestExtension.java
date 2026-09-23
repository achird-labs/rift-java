package io.github.achirdlabs.rift.junit5;

import io.github.achirdlabs.rift.ConnectOptions;
import io.github.achirdlabs.rift.EmbeddedOptions;
import io.github.achirdlabs.rift.Imposter;
import io.github.achirdlabs.rift.Intercept;
import io.github.achirdlabs.rift.InterceptOptions;
import io.github.achirdlabs.rift.RecordedRequest;
import io.github.achirdlabs.rift.Recording;
import io.github.achirdlabs.rift.Rift;
import io.github.achirdlabs.rift.SpawnOptions;
import io.github.achirdlabs.rift.TruststoreFormat;
import io.github.achirdlabs.rift.dsl.ImposterSpec;
import io.github.achirdlabs.rift.json.JsonValue;
import io.github.achirdlabs.rift.model.ImposterDefinition;
import io.github.achirdlabs.rift.model.Stub;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.TestInstancePostProcessor;
import org.junit.jupiter.api.extension.TestWatcher;
import org.junit.platform.commons.support.AnnotationSupport;
import org.junit.platform.commons.support.HierarchyTraversalMode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The {@code @RiftTest} JUnit 5 extension: starts one {@link Rift} engine per test class, creates
 * the configured imposters, injects {@link InjectRift}/{@link InjectImposter} fields and parameters,
 * and drives the {@link Reset} policy between test methods.
 *
 * <p>Two configuration modes, identical lifecycle:
 * <ul>
 *   <li><b>Annotation</b> — {@code @RiftTest} on the class supplies transport/adminUri/reset and
 *       {@code static @RiftImposter ImposterSpec} fields supply the imposters (JUnit constructs this
 *       extension via the {@code @ExtendWith} meta-annotation).</li>
 *   <li><b>Programmatic</b> (Tier-2) — {@link #newInstance()} builds a configured instance for use
 *       as a {@code @RegisterExtension static} field, when transport/imposters are computed.</li>
 * </ul>
 *
 * <p>With {@code dumpRecordedOnFailure}, a failing test publishes each imposter's recorded requests
 * to the JUnit report as {@code rift.recorded.<name>} (capped at {@value #MAX_DUMP} per imposter).
 */
public final class RiftTestExtension implements BeforeAllCallback, BeforeEachCallback, AfterAllCallback,
        TestInstancePostProcessor, ParameterResolver, TestWatcher {

    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(RiftTestExtension.class);
    private static final String STORE_KEY = "riftTestContext";
    static final int MAX_DUMP = 20;

    /** Programmatic configuration (Tier-2); {@code null} in annotation mode. */
    private final Config config;

    /** Public no-arg constructor for the {@code @ExtendWith(RiftTestExtension.class)} path. */
    public RiftTestExtension() {
        this.config = null;
    }

    private RiftTestExtension(Config config) {
        this.config = config;
    }

    /** Starts a Tier-2 programmatic builder for use as a {@code @RegisterExtension static} field. */
    public static Builder newInstance() {
        return new Builder();
    }

    @Override
    public void beforeAll(ExtensionContext context) {
        ResolvedConfig resolved = resolve(context);
        Rift rift = buildRift(resolved.transport(), resolved.adminUri(), resolved.engineOptions());
        try {
            Map<String, Imposter> impostersByName = createImposters(rift, resolved.imposters());
            RiftTestContext riftTestContext =
                    new RiftTestContext(rift, impostersByName, resolved.reset(), resolved.dumpRecordedOnFailure());
            applyGolden(context.getRequiredTestClass(), impostersByName, riftTestContext);
            startInterceptIfConfigured(context.getRequiredTestClass(), rift, riftTestContext);
            if (resolved.reset() == Reset.PER_CLASS) {
                riftTestContext.resetConfiguredImposters();
            }
            // Stored only after the (possibly-throwing) PER_CLASS reset — so a failure here closes the
            // engine exactly once (via the catch below) rather than also via afterAll's stored context.
            context.getStore(NAMESPACE).put(STORE_KEY, riftTestContext);
        } catch (RuntimeException e) {
            try {
                rift.close();
            } catch (RuntimeException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
    }

    /**
     * Wires {@code @RiftGolden} (if present on {@code testClass}) into the target imposter: REPLAY
     * loads the persisted stubs and installs them directly (no network); CAPTURE starts a {@link
     * Recording} against {@link RiftGolden#origin()} and hands it to {@code riftTestContext} so
     * {@link RiftTestContext#close()} persists it once the class's tests have run.
     */
    private static void applyGolden(Class<?> testClass, Map<String, Imposter> impostersByName,
            RiftTestContext riftTestContext) {
        RiftGolden golden = AnnotationSupport.findAnnotation(testClass, RiftGolden.class).orElse(null);
        if (golden == null) {
            return;
        }
        Imposter imposter = goldenImposter(golden, impostersByName);
        boolean recapture = "recapture".equals(System.getProperty("rift.golden"));
        Path file = Path.of(golden.file());
        if (Files.exists(file) && !recapture) {
            replayGolden(imposter, file);
        } else {
            riftTestContext.setGoldenCapture(imposter.startRecording(golden.origin()), file);
        }
    }

    private static Imposter goldenImposter(RiftGolden golden, Map<String, Imposter> impostersByName) {
        if (!golden.imposter().isBlank()) {
            Imposter imposter = impostersByName.get(golden.imposter());
            if (imposter == null) {
                throw new IllegalStateException("@RiftGolden(imposter = \"" + golden.imposter()
                        + "\") not found; configured imposters: " + impostersByName.keySet());
            }
            return imposter;
        }
        if (impostersByName.size() != 1) {
            throw new IllegalStateException(
                    "@RiftGolden without imposter() requires exactly one configured imposter, found "
                            + impostersByName.keySet() + "; set @RiftGolden(imposter = \"...\") to disambiguate");
        }
        return impostersByName.values().iterator().next();
    }

    private static void replayGolden(Imposter imposter, Path file) {
        String content;
        try {
            content = Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read golden file " + file, e);
        }
        List<Stub> stubs = ImposterDefinition.fromJson(content).stubs();
        if (stubs.isEmpty()) {
            // A present-but-stubless golden file (empty, truncated, or a capture that recorded
            // nothing) would replay as a live imposter that serves nothing — fail loudly instead.
            throw new IllegalStateException("golden file " + file + " has no stubs; delete it to "
                    + "re-capture, or run with -Drift.golden=recapture");
        }
        for (Stub stub : stubs) {
            imposter.addStub(JsonValue.parse(stub.toJson()));
        }
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        RiftTestContext riftTestContext = riftTestContext(context);
        if (riftTestContext.reset() == Reset.PER_TEST) {
            riftTestContext.resetConfiguredImposters();
            if (riftTestContext.intercept() != null) {
                riftTestContext.intercept().clearRules();
                applyInterceptRules(context.getRequiredTestClass(), riftTestContext);
            }
        }
    }

    @Override
    public void postProcessTestInstance(Object testInstance, ExtensionContext context) throws Exception {
        RiftTestContext riftTestContext = riftTestContext(context);
        for (Field field : AnnotationSupport.findAnnotatedFields(
                testInstance.getClass(), InjectRift.class, f -> true, HierarchyTraversalMode.TOP_DOWN)) {
            setField(field, testInstance, riftTestContext.rift());
        }
        for (Field field : AnnotationSupport.findAnnotatedFields(
                testInstance.getClass(), InjectImposter.class, f -> true, HierarchyTraversalMode.TOP_DOWN)) {
            InjectImposter injectImposter = field.getAnnotation(InjectImposter.class);
            setField(field, testInstance, riftTestContext.imposter(injectImposter.value()));
        }
        for (Field field : AnnotationSupport.findAnnotatedFields(
                testInstance.getClass(), InjectIntercept.class, f -> true, HierarchyTraversalMode.TOP_DOWN)) {
            setField(field, testInstance, requireIntercept(riftTestContext));
        }
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        return parameterContext.isAnnotated(InjectRift.class)
                || parameterContext.isAnnotated(InjectImposter.class)
                || parameterContext.isAnnotated(InjectIntercept.class);
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        RiftTestContext riftTestContext = riftTestContext(extensionContext);
        if (parameterContext.isAnnotated(InjectRift.class)) {
            return riftTestContext.rift();
        }
        if (parameterContext.isAnnotated(InjectIntercept.class)) {
            return requireIntercept(riftTestContext);
        }
        InjectImposter injectImposter = parameterContext.findAnnotation(InjectImposter.class)
                .orElseThrow(() -> new ParameterResolutionException("expected @InjectImposter on parameter " + parameterContext.getParameter()));
        return riftTestContext.imposter(injectImposter.value());
    }

    /**
     * On a failing test, publishes each imposter's recorded requests to the JUnit report when {@code
     * dumpRecordedOnFailure} is set. Reads the context via the (hierarchical) store rather than the
     * throwing accessor: a diagnostics hook must never mask the actual test failure with its own.
     */
    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        RiftTestContext riftTestContext = context.getStore(NAMESPACE).get(STORE_KEY, RiftTestContext.class);
        if (riftTestContext == null || !riftTestContext.dumpRecordedOnFailure()) {
            return;
        }
        riftTestContext.forEachImposter((name, imposter) -> {
            List<RecordedRequest> recorded;
            try {
                recorded = imposter.recorded();
            } catch (RuntimeException e) {
                context.publishReportEntry("rift.recorded." + name, "could not fetch recorded requests: " + e);
                return;
            }
            if (!recorded.isEmpty()) {
                context.publishReportEntry("rift.recorded." + name, formatRecordedDump(recorded));
            }
        });
    }

    @Override
    public void afterAll(ExtensionContext context) {
        RiftTestContext riftTestContext = context.getStore(NAMESPACE).get(STORE_KEY, RiftTestContext.class);
        if (riftTestContext != null) {
            riftTestContext.close();
        }
    }

    /**
     * Formats a compact per-imposter dump, one {@link RecordedRequest#summary()} per line, capped at {@value
     * #MAX_DUMP} with a trailing count of the requests omitted. Package-private for direct testing.
     */
    static String formatRecordedDump(List<RecordedRequest> recorded) {
        int total = recorded.size();
        int shown = Math.min(total, MAX_DUMP);
        StringBuilder sb = new StringBuilder();
        sb.append(total).append(" recorded request(s)");
        if (total > shown) {
            sb.append(" (showing first ").append(shown).append(')');
        }
        sb.append(':');
        for (int i = 0; i < shown; i++) {
            RecordedRequest request = recorded.get(i);
            sb.append('\n').append(request.summary());
        }
        if (total > shown) {
            sb.append("\n… ").append(total - shown).append(" more");
        }
        return sb.toString();
    }

    private ResolvedConfig resolve(ExtensionContext context) {
        if (config != null) {
            return new ResolvedConfig(config.transport(), resolveAdminUri(config.adminUri()),
                    config.imposters(), config.reset(), config.dumpRecordedOnFailure(), config.engineOptions());
        }
        Class<?> testClass = context.getRequiredTestClass();

        // The test class's static initializer commonly publishes the admin-URI system property
        // @RiftTest.adminUri()'s ${...} placeholder resolves against (see RiftExtensionIT); JUnit
        // only guarantees that runs once the class is loaded, which isn't otherwise guaranteed to
        // have happened yet when this callback fires. Force it now, before resolving adminUri.
        try {
            Class.forName(testClass.getName(), true, testClass.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("failed to force-initialize test class " + testClass.getName(), e);
        }

        RiftTest riftTest = AnnotationSupport.findAnnotation(testClass, RiftTest.class)
                .orElseThrow(() -> new IllegalStateException("@RiftTest not found on " + testClass.getName()));
        return new ResolvedConfig(riftTest.transport(), resolveAdminUri(riftTest.adminUri()),
                collectSpecFields(testClass), riftTest.reset(), riftTest.dumpRecordedOnFailure(), EngineOptions.NONE);
    }

    private static String resolveAdminUri(String adminUri) {
        if (adminUri.startsWith("${") && adminUri.endsWith("}")) {
            String propertyName = adminUri.substring(2, adminUri.length() - 1);
            String value = System.getProperty(propertyName);
            return value == null ? "" : value;
        }
        return adminUri;
    }

    private static Rift buildRift(Transport transport, String resolvedAdminUri, EngineOptions engineOptions) {
        return switch (transport) {
            case CONNECT -> {
                if (engineOptions.connect() != null) {
                    yield Rift.connect(engineOptions.connect());
                }
                if (resolvedAdminUri.isBlank()) {
                    throw new IllegalStateException(
                            "CONNECT transport requires a non-blank adminUri (set it on @RiftTest, the builder, "
                                    + "or via connectOptions(...))");
                }
                yield Rift.connect(URI.create(resolvedAdminUri));
            }
            case SPAWN -> engineOptions.spawn() != null ? Rift.spawn(engineOptions.spawn()) : Rift.spawn();
            case EMBEDDED -> embedded(engineOptions);
            case AUTO -> Rift.isEmbeddedAvailable() ? embedded(engineOptions)
                    : (engineOptions.spawn() != null ? Rift.spawn(engineOptions.spawn()) : Rift.spawn());
        };
    }

    private static Rift embedded(EngineOptions engineOptions) {
        return engineOptions.embedded() != null ? Rift.embedded(engineOptions.embedded()) : Rift.embedded();
    }

    /**
     * Reads each {@code static} {@link ImposterSpec} field annotated {@link RiftImposter} (annotation
     * mode), in declaration order. Name assignment/deduplication happens uniformly in {@link
     * #createImposters}.
     */
    private static List<ImposterSpec> collectSpecFields(Class<?> testClass) {
        List<ImposterSpec> specs = new ArrayList<>();
        for (Field field : AnnotationSupport.findAnnotatedFields(
                testClass, RiftImposter.class,
                f -> Modifier.isStatic(f.getModifiers()) && ImposterSpec.class.isAssignableFrom(f.getType()),
                HierarchyTraversalMode.TOP_DOWN)) {
            field.trySetAccessible();
            ImposterSpec spec;
            try {
                spec = (ImposterSpec) field.get(null);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("cannot read @RiftImposter field " + field, e);
            }
            if (spec == null) {
                throw new IllegalStateException("@RiftImposter field " + field + " is null");
            }
            specs.add(spec);
        }
        return specs;
    }

    /**
     * Builds and creates every imposter, keyed by the spec's own declared name (via {@link
     * ImposterDefinition#name()}) rather than the name reported back by the engine: a minimal admin
     * API is not required to echo a {@code name} field on {@code GET}/{@code POST /imposters} (it is
     * client-side bookkeeping, not wire-required), so relying on {@code Imposter#name()} here would
     * break against such engines.
     */
    private static Map<String, Imposter> createImposters(Rift rift, List<ImposterSpec> specs) {
        Map<String, Imposter> impostersByName = new LinkedHashMap<>();
        for (ImposterSpec spec : specs) {
            ImposterDefinition definition = spec.build();
            String name = definition.name().orElseThrow(() -> new IllegalStateException(
                    "imposter has no name; every @RiftTest/builder imposter must be named"));
            if (impostersByName.containsKey(name)) {
                throw new IllegalStateException("duplicate imposter name '" + name + "'");
            }
            impostersByName.put(name, rift.create(definition));
        }
        return impostersByName;
    }

    private static RiftTestContext riftTestContext(ExtensionContext context) {
        RiftTestContext riftTestContext = context.getStore(NAMESPACE).get(STORE_KEY, RiftTestContext.class);
        if (riftTestContext == null) {
            throw new IllegalStateException("no RiftTestContext found; is @RiftTest present on the test class?");
        }
        return riftTestContext;
    }

    private static void setField(Field field, Object testInstance, Object value) throws IllegalAccessException {
        field.trySetAccessible();
        field.set(testInstance, value);
    }

    // -- @RiftIntercept ---------------------------------------------------------------------------

    private static void startInterceptIfConfigured(Class<?> testClass, Rift rift, RiftTestContext ctx) {
        RiftIntercept config = AnnotationSupport.findAnnotation(testClass, RiftIntercept.class).orElse(null);
        if (config == null) {
            return;
        }
        InterceptOptions.Builder options = InterceptOptions.builder().host(config.host()).port(config.port());
        String caCert = resolvePlaceholder(config.caCert());
        String caKey = resolvePlaceholder(config.caKey());
        if (!caCert.isEmpty() || !caKey.isEmpty()) {
            options.ca(caCert.isEmpty() ? null : Path.of(caCert), caKey.isEmpty() ? null : Path.of(caKey));
        }
        Intercept intercept = rift.intercept(options.build());
        ctx.setIntercept(intercept);

        String export = resolvePlaceholder(config.exportTruststore());
        if (!export.isEmpty()) {
            intercept.trust().exportTruststore(config.exportFormat(), config.exportPassword(), Path.of(export));
        }
        applyInterceptRules(testClass, ctx);
    }

    private static void applyInterceptRules(Class<?> testClass, RiftTestContext ctx) {
        for (Method method : AnnotationSupport.findAnnotatedMethods(
                testClass, RiftInterceptRules.class, HierarchyTraversalMode.TOP_DOWN)) {
            if (!Modifier.isStatic(method.getModifiers())) {
                throw new IllegalStateException("@RiftInterceptRules method " + method + " must be static");
            }
            Object[] args = resolveRulesArgs(method, ctx);
            method.trySetAccessible();
            try {
                method.invoke(null, args);
            } catch (ReflectiveOperationException e) {
                Throwable cause = e instanceof java.lang.reflect.InvocationTargetException ite ? ite.getCause() : e;
                throw new IllegalStateException("failed to apply @RiftInterceptRules method " + method.getName()
                        + ": " + cause.getMessage(), cause);
            }
        }
    }

    private static Object[] resolveRulesArgs(Method method, RiftTestContext ctx) {
        Parameter[] params = method.getParameters();
        Object[] args = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            Parameter p = params[i];
            if (p.getType() == Intercept.class) {
                args[i] = requireIntercept(ctx);
            } else if (p.getType() == Rift.class) {
                args[i] = ctx.rift();
            } else if (p.isAnnotationPresent(InjectImposter.class)) {
                args[i] = ctx.imposter(p.getAnnotation(InjectImposter.class).value());
            } else {
                throw new IllegalStateException("@RiftInterceptRules parameter " + p
                        + " is not resolvable (expected Intercept, Rift, or @InjectImposter Imposter)");
            }
        }
        return args;
    }

    private static Intercept requireIntercept(RiftTestContext ctx) {
        Intercept intercept = ctx.intercept();
        if (intercept == null) {
            throw new ParameterResolutionException(
                    "@InjectIntercept requires @RiftIntercept on the test class");
        }
        return intercept;
    }

    /** Resolves a {@code ${property}} value against a system property (empty if unset); otherwise verbatim. */
    private static String resolvePlaceholder(String value) {
        if (value.startsWith("${") && value.endsWith("}")) {
            String resolved = System.getProperty(value.substring(2, value.length() - 1));
            return resolved == null ? "" : resolved;
        }
        return value;
    }

    /**
     * The transport-specific options a Tier-2 builder may supply for the engine it constructs; each is
     * {@code null} unless set. The annotation tier does not use these (it configures the engine via the
     * {@code rift.ffi.lib} / {@code rift.versionCheck} launch properties), so it always passes {@link #NONE}.
     */
    private record EngineOptions(EmbeddedOptions embedded, ConnectOptions connect, SpawnOptions spawn) {
        static final EngineOptions NONE = new EngineOptions(null, null, null);
    }

    /** Immutable Tier-2 configuration produced by {@link Builder}. */
    private record Config(Transport transport, String adminUri, List<ImposterSpec> imposters,
                          Reset reset, boolean dumpRecordedOnFailure, EngineOptions engineOptions) {
    }

    /** Configuration resolved for a run, from either the builder or the {@code @RiftTest} annotation. */
    private record ResolvedConfig(Transport transport, String adminUri, List<ImposterSpec> imposters,
                                  Reset reset, boolean dumpRecordedOnFailure, EngineOptions engineOptions) {
    }

    /** Fluent builder for the Tier-2 programmatic {@code @RegisterExtension} path. */
    public static final class Builder {
        private Transport transport = Transport.AUTO;
        private String adminUri = "";
        private final List<ImposterSpec> imposters = new ArrayList<>();
        private Reset reset = Reset.PER_TEST;
        private boolean dumpRecordedOnFailure = false;
        private EmbeddedOptions embeddedOptions;
        private ConnectOptions connectOptions;
        private SpawnOptions spawnOptions;

        private Builder() {
        }

        public Builder transport(Transport transport) {
            this.transport = Objects.requireNonNull(transport, "transport");
            return this;
        }

        public Builder adminUri(String adminUri) {
            this.adminUri = Objects.requireNonNull(adminUri, "adminUri");
            return this;
        }

        /** Adds an imposter; may be called repeatedly. */
        public Builder imposter(ImposterSpec spec) {
            this.imposters.add(Objects.requireNonNull(spec, "spec"));
            return this;
        }

        public Builder reset(Reset reset) {
            this.reset = Objects.requireNonNull(reset, "reset");
            return this;
        }

        public Builder dumpRecordedOnFailure(boolean dumpRecordedOnFailure) {
            this.dumpRecordedOnFailure = dumpRecordedOnFailure;
            return this;
        }

        /** Options for an {@code EMBEDDED} (or {@code AUTO}-resolves-to-embedded) engine — e.g. a dev library path. */
        public Builder embeddedOptions(EmbeddedOptions embeddedOptions) {
            this.embeddedOptions = Objects.requireNonNull(embeddedOptions, "embeddedOptions");
            return this;
        }

        /** Options for a {@code CONNECT} engine; carries its own {@code adminUri}. */
        public Builder connectOptions(ConnectOptions connectOptions) {
            this.connectOptions = Objects.requireNonNull(connectOptions, "connectOptions");
            return this;
        }

        /** Options for a {@code SPAWN} (or {@code AUTO}-falls-back-to-spawn) engine. */
        public Builder spawnOptions(SpawnOptions spawnOptions) {
            this.spawnOptions = Objects.requireNonNull(spawnOptions, "spawnOptions");
            return this;
        }

        public RiftTestExtension build() {
            // Options must match the selected transport; AUTO may resolve to embedded or spawn (never connect).
            if (connectOptions != null && transport != Transport.CONNECT) {
                throw new IllegalStateException(
                        "connectOptions(...) requires transport(CONNECT), but transport is " + transport);
            }
            if (embeddedOptions != null && transport != Transport.EMBEDDED && transport != Transport.AUTO) {
                throw new IllegalStateException(
                        "embeddedOptions(...) requires transport(EMBEDDED) or transport(AUTO), but transport is "
                                + transport);
            }
            if (spawnOptions != null && transport != Transport.SPAWN && transport != Transport.AUTO) {
                throw new IllegalStateException(
                        "spawnOptions(...) requires transport(SPAWN) or transport(AUTO), but transport is " + transport);
            }
            return new RiftTestExtension(new Config(transport, adminUri, List.copyOf(imposters), reset,
                    dumpRecordedOnFailure, new EngineOptions(embeddedOptions, connectOptions, spawnOptions)));
        }
    }
}
