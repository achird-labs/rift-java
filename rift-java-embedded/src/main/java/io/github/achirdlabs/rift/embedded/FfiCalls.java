package io.github.achirdlabs.rift.embedded;

import io.github.achirdlabs.rift.error.EngineError;
import io.github.achirdlabs.rift.error.EngineUnavailable;
import io.github.achirdlabs.rift.json.JsonBool;
import io.github.achirdlabs.rift.json.JsonNumber;
import io.github.achirdlabs.rift.json.JsonObject;
import io.github.achirdlabs.rift.json.JsonString;
import io.github.achirdlabs.rift.json.JsonValue;
import io.github.achirdlabs.rift.transport.StubAddress;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Optional;

/** Ownership + error discipline over {@link RiftFfi}, holding the live {@code RiftHandle} segment. */
final class FfiCalls {

    private final RiftFfi ffi;
    private final MemorySegment handle;
    private volatile boolean stopped;

    private FfiCalls(RiftFfi ffi, MemorySegment handle) {
        this.ffi = ffi;
        this.handle = handle;
    }

    static FfiCalls start(RiftFfi ffi) {
        MemorySegment handle = ffi.start();
        if (RiftFfi.isNull(handle)) {
            throw new EngineUnavailable("failed to start the embedded rift engine");
        }
        return new FfiCalls(ffi, handle);
    }

    /**
     * A downcall into a stopped handle (or, worse, an unloaded library) is undefined behaviour at the
     * native level — segfault, not a Java exception. Guard every call so use-after-close fails cleanly.
     */
    private void ensureLive() {
        if (stopped) {
            throw new IllegalStateException("the embedded rift engine is closed");
        }
    }

    int createImposter(JsonValue def) {
        ensureLive();
        try (Arena args = Arena.ofConfined()) {
            int port = ffi.createImposter(handle, FfmCompat.allocateCString(args, def.toJson()));
            if (port == 0) {
                throw engineError();
            }
            return port;
        }
    }

    void replaceStubs(int port, JsonValue stubs) {
        ensureLive();
        try (Arena args = Arena.ofConfined()) {
            int rc = ffi.replaceStubs(handle, port, FfmCompat.allocateCString(args, stubs.toJson()));
            if (rc != 0) {
                throw engineError();
            }
        }
    }

    void deleteImposter(int port) {
        ensureLive();
        if (ffi.deleteImposter(handle, port) != 0) {
            throw engineError();
        }
    }

    void deleteAll() {
        ensureLive();
        if (ffi.deleteAll(handle) != 0) {
            throw engineError();
        }
    }

    JsonValue recorded(int port) {
        ensureLive();
        return readJsonAndFree(ffi.recorded(handle, port));
    }

    JsonValue stubWarnings(int port) {
        ensureLive();
        return readJsonAndFree(ffi.stubWarnings(handle, port));
    }

    JsonValue verify(int port, JsonValue body) {
        ensureLive();
        try (Arena args = Arena.ofConfined()) {
            return readJsonAndFree(ffi.verify(handle, port, FfmCompat.allocateCString(args, body.toJson())));
        }
    }

    JsonValue listImposters(boolean replayable, boolean removeProxies) {
        ensureLive();
        String options = listOptionsJson(replayable, removeProxies);
        try (Arena args = Arena.ofConfined()) {
            return readJsonAndFree(ffi.listImposters(handle, FfmCompat.allocateCString(args, options)));
        }
    }

    JsonValue getImposter(int port, boolean replayable, boolean removeProxies) {
        ensureLive();
        String options = listOptionsJson(replayable, removeProxies);
        try (Arena args = Arena.ofConfined()) {
            return readJsonAndFree(ffi.getImposter(handle, port, FfmCompat.allocateCString(args, options)));
        }
    }

    private static String listOptionsJson(boolean replayable, boolean removeProxies) {
        return JsonObject.builder()
                .put("replayable", JsonBool.of(replayable))
                .put("removeProxies", JsonBool.of(removeProxies))
                .build()
                .toJson();
    }

    void addStub(int port, JsonValue stub) {
        // index < 0 appends; direct FFI never auto-assigns a position (mirrors rift_add_stub).
        addStub(port, stub, -1);
    }

    void addStub(int port, JsonValue stub, int index) {
        ensureLive();
        try (Arena args = Arena.ofConfined()) {
            int rc = ffi.addStub(handle, port, FfmCompat.allocateCString(args, stub.toJson()), index);
            if (rc != 0) {
                throw engineError();
            }
        }
    }

    JsonValue getStub(int port, StubAddress addr) {
        ensureLive();
        try (Arena args = Arena.ofConfined()) {
            return readJsonAndFree(ffi.getStub(handle, port, FfmCompat.allocateCString(args, refJson(addr))));
        }
    }

    void updateStub(int port, StubAddress addr, JsonValue stub) {
        ensureLive();
        try (Arena args = Arena.ofConfined()) {
            int rc = ffi.updateStub(handle, port, FfmCompat.allocateCString(args, refJson(addr)),
                    FfmCompat.allocateCString(args, stub.toJson()));
            if (rc != 0) {
                throw engineError();
            }
        }
    }

    void deleteStub(int port, StubAddress addr) {
        ensureLive();
        try (Arena args = Arena.ofConfined()) {
            int rc = ffi.deleteStub(handle, port, FfmCompat.allocateCString(args, refJson(addr)));
            if (rc != 0) {
                throw engineError();
            }
        }
    }

    private static String refJson(StubAddress addr) {
        if (addr instanceof StubAddress.ByIndex idx) {
            return JsonObject.builder().put("index", JsonNumber.of(idx.index())).build().toJson();
        }
        if (addr instanceof StubAddress.ById id) {
            return JsonObject.builder().put("id", new JsonString(id.id())).build().toJson();
        }
        throw new IllegalStateException("unreachable: " + addr);
    }

    void clearRecorded(int port) {
        ensureLive();
        if (ffi.clearRecorded(handle, port) != 0) {
            throw engineError();
        }
    }

    void clearProxyResponses(int port) {
        ensureLive();
        if (ffi.clearProxyRecordings(handle, port) != 0) {
            throw engineError();
        }
    }

    void setImposterEnabled(int port, boolean enabled) {
        ensureLive();
        if (ffi.setImposterEnabled(handle, port, enabled ? 1 : 0) != 0) {
            throw engineError();
        }
    }

    JsonValue scenarios(int port, Optional<String> flowId) {
        ensureLive();
        try (Arena args = Arena.ofConfined()) {
            MemorySegment flowIdSeg = flowId.isPresent() ? FfmCompat.allocateCString(args, flowId.get()) : MemorySegment.NULL;
            return readJsonAndFree(ffi.scenarios(handle, port, flowIdSeg));
        }
    }

    /** Builds the {@code rift_set_scenario_state} payload {@code {"state","flowId"?}} (flowId omitted → default flow). */
    static String scenarioStateBody(String state, Optional<String> flowId) {
        return JsonObject.builder()
                .put("state", new JsonString(state))
                .putIfPresent("flowId", flowId.map(JsonString::new))
                .build()
                .toJson();
    }

    void setScenarioState(int port, String name, String state, Optional<String> flowId) {
        ensureLive();
        String stateJson = scenarioStateBody(state, flowId);
        try (Arena args = Arena.ofConfined()) {
            int rc = ffi.setScenarioState(handle, port, FfmCompat.allocateCString(args, name),
                    FfmCompat.allocateCString(args, stateJson));
            if (rc != 0) {
                throw engineError();
            }
        }
    }

    void resetScenarios(int port) {
        ensureLive();
        if (ffi.resetScenarios(handle, port, MemorySegment.NULL) != 0) {
            throw engineError();
        }
    }

    JsonValue applyConfig(JsonValue config) {
        ensureLive();
        try (Arena args = Arena.ofConfined()) {
            return readJsonAndFree(ffi.applyConfig(handle, FfmCompat.allocateCString(args, config.toJson())));
        }
    }

    Optional<JsonValue> flowStateGet(int port, String flowId, String key) {
        ensureLive();
        try (Arena args = Arena.ofConfined()) {
            MemorySegment seg = ffi.flowStateGet(handle, port, FfmCompat.allocateCString(args, flowId),
                    FfmCompat.allocateCString(args, key));
            JsonValue envelope = readJsonAndFree(seg);
            if (envelope instanceof JsonObject obj && obj.get("found") instanceof JsonBool found && found.value()) {
                return Optional.of(obj.get("value"));
            }
            return Optional.empty();
        }
    }

    void flowStatePut(int port, String flowId, String key, JsonValue value) {
        ensureLive();
        try (Arena args = Arena.ofConfined()) {
            int rc = ffi.flowStatePut(handle, port, FfmCompat.allocateCString(args, flowId), FfmCompat.allocateCString(args, key),
                    FfmCompat.allocateCString(args, value.toJson()));
            if (rc != 0) {
                throw engineError();
            }
        }
    }

    void flowStateDelete(int port, String flowId, String key) {
        ensureLive();
        try (Arena args = Arena.ofConfined()) {
            int rc = ffi.flowStateDelete(handle, port, FfmCompat.allocateCString(args, flowId),
                    FfmCompat.allocateCString(args, key));
            if (rc != 0) {
                throw engineError();
            }
        }
    }

    void spaceAddStub(int port, String flowId, JsonValue stub) {
        ensureLive();
        try (Arena args = Arena.ofConfined()) {
            int rc = ffi.spaceAddStub(handle, port, FfmCompat.allocateCString(args, flowId),
                    FfmCompat.allocateCString(args, stub.toJson()));
            if (rc != 0) {
                throw engineError();
            }
        }
    }

    JsonValue spaceListStubs(int port, String flowId) {
        ensureLive();
        try (Arena args = Arena.ofConfined()) {
            return readJsonAndFree(ffi.spaceListStubs(handle, port, FfmCompat.allocateCString(args, flowId)));
        }
    }

    void spaceDelete(int port, String flowId) {
        ensureLive();
        try (Arena args = Arena.ofConfined()) {
            int rc = ffi.spaceDelete(handle, port, FfmCompat.allocateCString(args, flowId));
            if (rc != 0) {
                throw engineError();
            }
        }
    }

    JsonValue spaceRecorded(int port, String flowId) {
        ensureLive();
        try (Arena args = Arena.ofConfined()) {
            return readJsonAndFree(ffi.spaceRecorded(handle, port, FfmCompat.allocateCString(args, flowId)));
        }
    }

    JsonValue buildInfo() {
        ensureLive();
        // The static rift_build_info() pointer is never freed.
        return JsonValue.parse(RiftFfi.readString(ffi.buildInfo()));
    }

    JsonValue serveAdmin(JsonValue options) {
        ensureLive();
        try (Arena args = Arena.ofConfined()) {
            return readJsonAndFree(ffi.serveAdmin(handle, FfmCompat.allocateCString(args, options.toJson())));
        }
    }

    JsonValue startIntercept(JsonValue options) {
        ensureLive();
        try (Arena args = Arena.ofConfined()) {
            return readJsonAndFree(ffi.startIntercept(handle, FfmCompat.allocateCString(args, options.toJson())));
        }
    }

    void interceptAddRules(JsonValue rules) {
        ensureLive();
        try (Arena args = Arena.ofConfined()) {
            if (ffi.interceptAddRules(handle, FfmCompat.allocateCString(args, rules.toJson())) != 0) {
                throw engineError();
            }
        }
    }

    JsonValue interceptListRules() {
        ensureLive();
        return readJsonAndFree(ffi.interceptListRules(handle));
    }

    void interceptClearRules() {
        ensureLive();
        if (ffi.interceptClearRules(handle) != 0) {
            throw engineError();
        }
    }

    String interceptCaPem() {
        ensureLive();
        MemorySegment seg = ffi.interceptCaPem(handle);
        if (RiftFfi.isNull(seg)) {
            throw engineError();
        }
        String pem = RiftFfi.readString(seg);
        ffi.free(seg);
        return pem;
    }

    void stop() {
        if (stopped) {
            return;
        }
        stopped = true;
        ffi.stop(handle);
    }

    private JsonValue readJsonAndFree(MemorySegment segment) {
        if (RiftFfi.isNull(segment)) {
            throw engineError();
        }
        // Copy the string out of native memory before freeing the original pointer.
        String json = RiftFfi.readString(segment);
        ffi.free(segment);
        return JsonValue.parse(json);
    }

    private RuntimeException engineError() {
        MemorySegment errSeg = ffi.lastError();
        if (RiftFfi.isNull(errSeg)) {
            return new EngineError(EngineError.NO_HTTP_STATUS, "rift engine call failed with no error message");
        }
        String message = RiftFfi.readString(errSeg);
        ffi.free(errSeg);
        return new EngineError(EngineError.NO_HTTP_STATUS, message);
    }
}
