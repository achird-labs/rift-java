package io.github.achirdlabs.rift.model;

/**
 * Predicates over an {@link ImposterDefinition} for flow-state / spaces configuration (#40).
 *
 * <p>The rift engine backs the flow-state and per-space APIs with a real store only when the def
 * declares one — an explicit {@code _rift.flowState}, a scenario-naming stub (rift#514), or a {@code
 * _rift.script} stub (rift#358); otherwise reads silently return {@code found:false}. Separately, a
 * space stub can only match when a header-form {@code flowIdSource} is configured, because the
 * engine's flow-id source defaults to the imposter port. These predicates let the DSL fail fast, and
 * the runtime accessors warn, on those otherwise-silent misconfigurations.
 */
public final class FlowStateSupport {

    private FlowStateSupport() {}

    /** A header-form flow-id source ({@code "header:<Name>"}) is configured. */
    public static boolean hasHeaderFlowIdSource(ImposterDefinition def) {
        return def.rift()
                .flatMap(RiftConfig::flowState)
                .flatMap(RiftFlowStateConfig::flowIdSource)
                .filter(source -> source.startsWith("header:"))
                .isPresent();
    }

    /** Any stub carries a per-space scope. */
    public static boolean hasSpaceStub(ImposterDefinition def) {
        return def.stubs().stream().anyMatch(stub -> stub.space().isPresent());
    }

    /**
     * The engine will provision a real (non-NoOp) flow store: the def has an explicit {@code
     * _rift.flowState}, a scenario-naming stub, a {@code _rift.script} stub, or an {@code is}
     * response with {@code _rift.stateOps}.
     */
    public static boolean hasStoreTrigger(ImposterDefinition def) {
        boolean explicitFlowState = def.rift().flatMap(RiftConfig::flowState).isPresent();
        return explicitFlowState || def.stubs().stream().anyMatch(FlowStateSupport::triggersStore);
    }

    private static boolean triggersStore(Stub stub) {
        boolean namesScenario = stub.scenarioName().isPresent()
                || stub.requiredScenarioState().isPresent()
                || stub.newScenarioState().isPresent();
        return namesScenario || stub.responses().stream()
                .anyMatch(r -> hasScript(r) || hasStateOps(r));
    }

    private static boolean hasStateOps(Response response) {
        return response instanceof Response.Is is
                && is.rift().map(rift -> !rift.stateOps().isEmpty()).orElse(false);
    }

    private static boolean hasScript(Response response) {
        if (response instanceof Response.Is is) {
            return is.rift().map(rift -> rift.script().isPresent()).orElse(false);
        }
        if (response instanceof Response.RiftScript riftScript) {
            return riftScript.rift().script().isPresent();
        }
        return false;
    }
}
