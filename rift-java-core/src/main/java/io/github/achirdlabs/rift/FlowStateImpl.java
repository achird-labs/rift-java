package io.github.achirdlabs.rift;

import io.github.achirdlabs.rift.json.JsonString;
import io.github.achirdlabs.rift.json.JsonValue;
import io.github.achirdlabs.rift.transport.RiftTransport;

import java.util.Optional;

final class FlowStateImpl implements FlowState {

    private final int port;
    private final String flowId;
    private final RiftTransport transport;

    FlowStateImpl(int port, String flowId, RiftTransport transport) {
        this.port = port;
        this.flowId = flowId;
        this.transport = transport;
    }

    @Override
    public Optional<JsonValue> get(String key) {
        return transport.flowStateGet(port, flowId, key);
    }

    @Override
    public void put(String key, JsonValue value) {
        transport.flowStatePut(port, flowId, key, value);
    }

    @Override
    public void put(String key, String value) {
        put(key, new JsonString(value));
    }

    @Override
    public void delete(String key) {
        transport.flowStateDelete(port, flowId, key);
    }

    @Override
    public void clear() {
        transport.flowStateClear(port, flowId);
    }
}
