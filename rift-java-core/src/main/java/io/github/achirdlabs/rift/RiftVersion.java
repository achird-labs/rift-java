package io.github.achirdlabs.rift;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/**
 * Versions resolved at build time from a filtered resource:
 * <ul>
 *   <li>{@link #get()} — the rift-java SDK's own version.</li>
 *   <li>{@link #engineVersion()} — the Rift engine version this SDK is pinned to and tested against
 *       (the {@code <rift.engine.version>} property that also drives the natives fetch, the
 *       conformance corpus, and the testcontainers proxy image). Distinct from the SDK's compatibility
 *       <em>floor</em> ({@code RiftImpl.MIN_ENGINE_VERSION}) and from the version a running engine reports.</li>
 * </ul>
 */
public final class RiftVersion {

    private static final Properties PROPS = load();

    private RiftVersion() {
    }

    /**
     * {@return the rift-java SDK version, e.g. {@code "0.1.0-SNAPSHOT"}}
     */
    public static String get() {
        return require("version");
    }

    /**
     * {@return the pinned Rift engine version, the build's {@code rift.engine.version}} — the default
     * for {@link SpawnOptions} and the testcontainers proxy image tag.
     */
    public static String engineVersion() {
        return require("engine.version");
    }

    private static String require(String key) {
        String value = PROPS.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("'" + key + "' absent from rift-version.properties");
        }
        return value;
    }

    private static Properties load() {
        try (InputStream in = RiftVersion.class.getResourceAsStream("/rift-version.properties")) {
            if (in == null) {
                throw new IllegalStateException("rift-version.properties missing from the rift-java-core jar");
            }
            Properties props = new Properties();
            props.load(in);
            return props;
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read rift-version.properties", e);
        }
    }
}
