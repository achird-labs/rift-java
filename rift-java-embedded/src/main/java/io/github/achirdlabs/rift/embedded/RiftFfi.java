package io.github.achirdlabs.rift.embedded;

import io.github.achirdlabs.rift.error.EngineUnavailable;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Raw FFM binding to the rift C-ABI v2 ({@code librift_ffi}). One downcall handle per symbol. */
final class RiftFfi {

    private final MethodHandle start;
    private final MethodHandle stop;
    private final MethodHandle createImposter;
    private final MethodHandle replaceStubs;
    private final MethodHandle deleteImposter;
    private final MethodHandle deleteAll;
    private final MethodHandle recorded;
    private final MethodHandle stubWarnings;
    private final MethodHandle verify;
    private final MethodHandle listImposters;
    private final MethodHandle getImposter;
    private final MethodHandle addStub;
    private final MethodHandle getStub;
    private final MethodHandle updateStub;
    private final MethodHandle deleteStub;
    private final MethodHandle clearRecorded;
    private final MethodHandle clearProxyRecordings;
    private final MethodHandle setImposterEnabled;
    private final MethodHandle scenarios;
    private final MethodHandle setScenarioState;
    private final MethodHandle resetScenarios;
    private final MethodHandle applyConfig;
    private final MethodHandle flowStateGet;
    private final MethodHandle flowStatePut;
    private final MethodHandle flowStateDelete;
    private final MethodHandle spaceAddStub;
    private final MethodHandle spaceListStubs;
    private final MethodHandle spaceDelete;
    private final MethodHandle spaceRecorded;
    private final MethodHandle serveAdmin;
    private final MethodHandle buildInfo;
    private final MethodHandle lastError;
    private final MethodHandle free;
    private final MethodHandle startIntercept;
    private final MethodHandle interceptAddRules;
    private final MethodHandle interceptClearRules;
    private final MethodHandle interceptListRules;
    private final MethodHandle interceptCaPem;
    private final MethodHandle stopIntercept;
    /** rift &ge; 0.20.0 only; empty on an older library. */
    private final Optional<MethodHandle> interceptReplaceRules;
    private final Optional<MethodHandle> flowStateClear;

    private RiftFfi(SymbolLookup lookup, Linker linker) {
        this.start = handle(lookup, linker, "rift_start",
                FunctionDescriptor.of(ValueLayout.ADDRESS));
        this.stop = handle(lookup, linker, "rift_stop",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        this.createImposter = handle(lookup, linker, "rift_create_imposter",
                FunctionDescriptor.of(ValueLayout.JAVA_SHORT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.replaceStubs = handle(lookup, linker, "rift_replace_stubs",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_SHORT, ValueLayout.ADDRESS));
        this.deleteImposter = handle(lookup, linker, "rift_delete_imposter",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_SHORT));
        this.deleteAll = handle(lookup, linker, "rift_delete_all",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        this.recorded = handle(lookup, linker, "rift_recorded",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_SHORT));
        this.stubWarnings = handle(lookup, linker, "rift_stub_warnings",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_SHORT));
        this.verify = handle(lookup, linker, "rift_verify",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_SHORT, ValueLayout.ADDRESS));
        this.listImposters = handle(lookup, linker, "rift_list_imposters",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.getImposter = handle(lookup, linker, "rift_get_imposter",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_SHORT, ValueLayout.ADDRESS));
        this.addStub = handle(lookup, linker, "rift_add_stub",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_SHORT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        this.getStub = handle(lookup, linker, "rift_get_stub",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_SHORT, ValueLayout.ADDRESS));
        this.updateStub = handle(lookup, linker, "rift_update_stub",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_SHORT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.deleteStub = handle(lookup, linker, "rift_delete_stub",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_SHORT, ValueLayout.ADDRESS));
        this.clearRecorded = handle(lookup, linker, "rift_clear_recorded",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_SHORT));
        this.clearProxyRecordings = handle(lookup, linker, "rift_clear_proxy_recordings",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_SHORT));
        this.setImposterEnabled = handle(lookup, linker, "rift_set_imposter_enabled",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_SHORT, ValueLayout.JAVA_INT));
        this.scenarios = handle(lookup, linker, "rift_scenarios",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_SHORT, ValueLayout.ADDRESS));
        this.setScenarioState = handle(lookup, linker, "rift_set_scenario_state",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_SHORT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.resetScenarios = handle(lookup, linker, "rift_reset_scenarios",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_SHORT, ValueLayout.ADDRESS));
        this.applyConfig = handle(lookup, linker, "rift_apply_config",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.flowStateGet = handle(lookup, linker, "rift_flow_state_get",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_SHORT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.flowStatePut = handle(lookup, linker, "rift_flow_state_put",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_SHORT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.flowStateDelete = handle(lookup, linker, "rift_flow_state_delete",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_SHORT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.spaceAddStub = handle(lookup, linker, "rift_space_add_stub",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_SHORT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.spaceListStubs = handle(lookup, linker, "rift_space_list_stubs",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_SHORT, ValueLayout.ADDRESS));
        this.spaceDelete = handle(lookup, linker, "rift_space_delete",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_SHORT, ValueLayout.ADDRESS));
        this.spaceRecorded = handle(lookup, linker, "rift_space_recorded",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_SHORT, ValueLayout.ADDRESS));
        this.serveAdmin = handle(lookup, linker, "rift_serve_admin",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.buildInfo = handle(lookup, linker, "rift_build_info",
                FunctionDescriptor.of(ValueLayout.ADDRESS));
        this.lastError = handle(lookup, linker, "rift_last_error",
                FunctionDescriptor.of(ValueLayout.ADDRESS));
        this.free = handle(lookup, linker, "rift_free",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        this.startIntercept = handle(lookup, linker, "rift_start_intercept",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.interceptAddRules = handle(lookup, linker, "rift_intercept_add_rules",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.interceptClearRules = handle(lookup, linker, "rift_intercept_clear_rules",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        this.interceptListRules = handle(lookup, linker, "rift_intercept_list_rules",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.interceptCaPem = handle(lookup, linker, "rift_intercept_ca_pem",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.stopIntercept = handle(lookup, linker, "rift_stop_intercept",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        this.interceptReplaceRules = optionalHandle(lookup, linker, "rift_intercept_replace_rules",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.flowStateClear = optionalHandle(lookup, linker, "rift_flow_state_clear",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_SHORT, ValueLayout.ADDRESS));
    }

    /**
     * A symbol newer than the C-ABI v2 floor: absent from an older library, which must still load for
     * everything else. Calling it on such a library throws {@link EngineUnavailable} (see {@link #require}).
     */
    private static Optional<MethodHandle> optionalHandle(SymbolLookup lookup, Linker linker, String name,
            FunctionDescriptor descriptor) {
        return lookup.find(name).map(symbol -> linker.downcallHandle(symbol, descriptor));
    }

    private static MethodHandle require(Optional<MethodHandle> handle, String operation, String since, String symbol) {
        return handle.orElseThrow(() -> new EngineUnavailable(
                operation + " requires rift >= " + since + ": the loaded native library is missing " + symbol));
    }

    private static MethodHandle handle(SymbolLookup lookup, Linker linker, String name, FunctionDescriptor descriptor) {
        return linker.downcallHandle(
                lookup.find(name).orElseThrow(() -> new EngineUnavailable(
                        "rift-java-embedded requires rift >= 0.13.1 (C-ABI v2): the loaded native library is missing " + name)),
                descriptor);
    }

    /**
     * Every C-ABI v2 symbol {@link #bind} requires. Kept in sync with the constructor's downcalls; a
     * real-library bind test transitively verifies the constructor binds each of these.
     */
    private static final List<String> REQUIRED_SYMBOLS = List.of(
            "rift_start", "rift_stop", "rift_create_imposter", "rift_replace_stubs", "rift_delete_imposter",
            "rift_delete_all", "rift_recorded", "rift_stub_warnings", "rift_verify", "rift_list_imposters",
            "rift_get_imposter", "rift_add_stub", "rift_get_stub", "rift_update_stub", "rift_delete_stub",
            "rift_clear_recorded", "rift_clear_proxy_recordings", "rift_set_imposter_enabled", "rift_scenarios",
            "rift_set_scenario_state", "rift_reset_scenarios", "rift_apply_config", "rift_flow_state_get",
            "rift_flow_state_put", "rift_flow_state_delete", "rift_space_add_stub", "rift_space_list_stubs",
            "rift_space_delete", "rift_space_recorded", "rift_serve_admin", "rift_build_info", "rift_last_error",
            "rift_free", "rift_start_intercept", "rift_intercept_add_rules", "rift_intercept_clear_rules",
            "rift_intercept_list_rules", "rift_intercept_ca_pem", "rift_stop_intercept");

    static RiftFfi bind(SymbolLookup lookup, Linker linker) {
        return bind(lookup, linker, "the loaded native library");
    }

    /**
     * Binds every C-ABI v2 symbol against {@code lookup}. Probes the full required set first and, if
     * any are absent, fails with a single message naming <em>all</em> of them (and {@code source}) —
     * so an incompatible library is diagnosed in one shot rather than one symbol per rebuild.
     */
    static RiftFfi bind(SymbolLookup lookup, Linker linker, String source) {
        List<String> missing = new ArrayList<>();
        for (String name : REQUIRED_SYMBOLS) {
            if (lookup.find(name).isEmpty()) {
                missing.add(name);
            }
        }
        if (!missing.isEmpty()) {
            throw new EngineUnavailable("rift-java-embedded requires rift >= 0.13.1 (C-ABI v2), but " + source
                    + " is missing " + missing.size() + " of " + REQUIRED_SYMBOLS.size()
                    + " required symbols: " + String.join(", ", missing) + ". Update or rebuild the engine.");
        }
        return new RiftFfi(lookup, linker);
    }

    static RiftFfi load(Path lib, Arena libArena) {
        return bind(SymbolLookup.libraryLookup(lib, libArena), Linker.nativeLinker(), lib.toString());
    }

    private static Object invoke(MethodHandle handle, Object... args) {
        try {
            return handle.invokeWithArguments(args);
        } catch (Throwable t) {
            throw new RuntimeException("FFM downcall failed: " + t.getMessage(), t);
        }
    }

    MemorySegment start() {
        return (MemorySegment) invoke(start);
    }

    void stop(MemorySegment handle) {
        invoke(stop, handle);
    }

    int createImposter(MemorySegment handle, MemorySegment json) {
        return (short) invoke(createImposter, handle, json) & 0xFFFF;
    }

    int replaceStubs(MemorySegment handle, int port, MemorySegment json) {
        return (int) invoke(replaceStubs, handle, (short) port, json);
    }

    int deleteImposter(MemorySegment handle, int port) {
        return (int) invoke(deleteImposter, handle, (short) port);
    }

    int deleteAll(MemorySegment handle) {
        return (int) invoke(deleteAll, handle);
    }

    MemorySegment recorded(MemorySegment handle, int port) {
        return (MemorySegment) invoke(recorded, handle, (short) port);
    }

    MemorySegment stubWarnings(MemorySegment handle, int port) {
        return (MemorySegment) invoke(stubWarnings, handle, (short) port);
    }

    MemorySegment verify(MemorySegment handle, int port, MemorySegment bodyJson) {
        return (MemorySegment) invoke(verify, handle, (short) port, bodyJson);
    }

    MemorySegment listImposters(MemorySegment handle, MemorySegment optionsJson) {
        return (MemorySegment) invoke(listImposters, handle, optionsJson);
    }

    MemorySegment getImposter(MemorySegment handle, int port, MemorySegment optionsJson) {
        return (MemorySegment) invoke(getImposter, handle, (short) port, optionsJson);
    }

    int addStub(MemorySegment handle, int port, MemorySegment stubJson, int index) {
        return (int) invoke(addStub, handle, (short) port, stubJson, index);
    }

    MemorySegment getStub(MemorySegment handle, int port, MemorySegment refJson) {
        return (MemorySegment) invoke(getStub, handle, (short) port, refJson);
    }

    int updateStub(MemorySegment handle, int port, MemorySegment refJson, MemorySegment stubJson) {
        return (int) invoke(updateStub, handle, (short) port, refJson, stubJson);
    }

    int deleteStub(MemorySegment handle, int port, MemorySegment refJson) {
        return (int) invoke(deleteStub, handle, (short) port, refJson);
    }

    int clearRecorded(MemorySegment handle, int port) {
        return (int) invoke(clearRecorded, handle, (short) port);
    }

    int clearProxyRecordings(MemorySegment handle, int port) {
        return (int) invoke(clearProxyRecordings, handle, (short) port);
    }

    int setImposterEnabled(MemorySegment handle, int port, int enabled) {
        return (int) invoke(setImposterEnabled, handle, (short) port, enabled);
    }

    MemorySegment scenarios(MemorySegment handle, int port, MemorySegment flowId) {
        return (MemorySegment) invoke(scenarios, handle, (short) port, flowId);
    }

    int setScenarioState(MemorySegment handle, int port, MemorySegment name, MemorySegment stateJson) {
        return (int) invoke(setScenarioState, handle, (short) port, name, stateJson);
    }

    int resetScenarios(MemorySegment handle, int port, MemorySegment flowId) {
        return (int) invoke(resetScenarios, handle, (short) port, flowId);
    }

    MemorySegment applyConfig(MemorySegment handle, MemorySegment json) {
        return (MemorySegment) invoke(applyConfig, handle, json);
    }

    MemorySegment flowStateGet(MemorySegment handle, int port, MemorySegment flowId, MemorySegment key) {
        return (MemorySegment) invoke(flowStateGet, handle, (short) port, flowId, key);
    }

    int flowStatePut(MemorySegment handle, int port, MemorySegment flowId, MemorySegment key, MemorySegment valueJson) {
        return (int) invoke(flowStatePut, handle, (short) port, flowId, key, valueJson);
    }

    int flowStateDelete(MemorySegment handle, int port, MemorySegment flowId, MemorySegment key) {
        return (int) invoke(flowStateDelete, handle, (short) port, flowId, key);
    }

    int spaceAddStub(MemorySegment handle, int port, MemorySegment flowId, MemorySegment stubJson) {
        return (int) invoke(spaceAddStub, handle, (short) port, flowId, stubJson);
    }

    MemorySegment spaceListStubs(MemorySegment handle, int port, MemorySegment flowId) {
        return (MemorySegment) invoke(spaceListStubs, handle, (short) port, flowId);
    }

    int spaceDelete(MemorySegment handle, int port, MemorySegment flowId) {
        return (int) invoke(spaceDelete, handle, (short) port, flowId);
    }

    MemorySegment spaceRecorded(MemorySegment handle, int port, MemorySegment flowId) {
        return (MemorySegment) invoke(spaceRecorded, handle, (short) port, flowId);
    }

    MemorySegment serveAdmin(MemorySegment handle, MemorySegment optionsJson) {
        return (MemorySegment) invoke(serveAdmin, handle, optionsJson);
    }

    /** The static build-identity string. Its pointer must never be freed. */
    MemorySegment buildInfo() {
        return (MemorySegment) invoke(buildInfo);
    }

    MemorySegment lastError() {
        return (MemorySegment) invoke(lastError);
    }

    void free(MemorySegment p) {
        invoke(free, p);
    }

    MemorySegment startIntercept(MemorySegment handle, MemorySegment optionsJson) {
        return (MemorySegment) invoke(startIntercept, handle, optionsJson);
    }

    int interceptAddRules(MemorySegment handle, MemorySegment rulesJson) {
        return (int) invoke(interceptAddRules, handle, rulesJson);
    }

    int interceptClearRules(MemorySegment handle) {
        return (int) invoke(interceptClearRules, handle);
    }

    MemorySegment interceptListRules(MemorySegment handle) {
        return (MemorySegment) invoke(interceptListRules, handle);
    }

    MemorySegment interceptCaPem(MemorySegment handle) {
        return (MemorySegment) invoke(interceptCaPem, handle);
    }

    int stopIntercept(MemorySegment handle) {
        return (int) invoke(stopIntercept, handle);
    }

    int interceptReplaceRules(MemorySegment handle, MemorySegment rulesJson) {
        MethodHandle replace = require(interceptReplaceRules, "intercept rule replace", "0.20.0", "rift_intercept_replace_rules");
        return (int) invoke(replace, handle, rulesJson);
    }

    int flowStateClear(MemorySegment handle, int port, MemorySegment flowId) {
        MethodHandle clear = require(flowStateClear, "FlowState.clear", "0.22.0", "rift_flow_state_clear");
        return (int) invoke(clear, handle, (short) port, flowId);
    }

    /** Reads a returned C string. Delegates to {@link FfmCompat} for the two-JDK method rename. */
    static String readString(MemorySegment segment) {
        return FfmCompat.readCString(segment);
    }

    static boolean isNull(MemorySegment segment) {
        return segment.address() == 0;
    }
}
