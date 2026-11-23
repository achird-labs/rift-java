package io.github.achirdlabs.rift;

import io.github.achirdlabs.rift.error.CommunicationError;
import io.github.achirdlabs.rift.json.JsonNumber;
import io.github.achirdlabs.rift.json.JsonObject;
import io.github.achirdlabs.rift.json.JsonString;
import io.github.achirdlabs.rift.json.JsonValue;
import io.github.achirdlabs.rift.transport.RiftTransport;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

final class ImposterClockImpl implements ImposterClock {

    private final int port;
    private final RiftTransport transport;

    ImposterClockImpl(int port, RiftTransport transport) {
        this.port = port;
        this.transport = transport;
    }

    @Override
    public Reading get() {
        return read(transport.imposterClock(port));
    }

    @Override
    public Reading set(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        long nowMs;
        try {
            nowMs = instant.toEpochMilli();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("instant " + instant + " is outside the range the engine's clock holds", e);
        }
        return read(transport.setImposterClock(port, JsonObject.builder().put("nowMs", JsonNumber.of(nowMs)).build()));
    }

    @Override
    public Reading advance(Duration by) {
        Objects.requireNonNull(by, "by");
        if (by.isNegative()) {
            throw new IllegalArgumentException("advance(" + by + "): a clock is stepped forward only; freeze it at an"
                    + " earlier instant with set(Instant)");
        }
        return read(transport.setImposterClock(port, JsonObject.builder().put("advanceMs", JsonNumber.of(by.toMillis())).build()));
    }

    @Override
    public Reading reset() {
        return read(transport.resetImposterClock(port));
    }

    /** Reads {@code {"mode", "nowMs"}}; anything else is a broken engine, not a clock state. Package-private for tests. */
    static Reading read(JsonValue body) {
        if (body instanceof JsonObject obj && obj.get("mode") instanceof JsonString mode
                && obj.get("nowMs") instanceof JsonNumber now) {
            Mode parsed = switch (mode.value()) {
                case "wall" -> Mode.WALL;
                case "frozen" -> Mode.FROZEN;
                default -> throw new CommunicationError("rift imposter clock reports an unknown mode \""
                        + mode.value() + "\": " + body.toJson());
            };
            try {
                return new Reading(parsed, Instant.ofEpochMilli(now.asLong()));
            } catch (NumberFormatException e) {
                throw new CommunicationError("rift imposter clock reports a non-integer nowMs: " + body.toJson(), e);
            }
        }
        throw new CommunicationError("rift imposter clock answered without {mode, nowMs}: " + body.toJson());
    }
}
