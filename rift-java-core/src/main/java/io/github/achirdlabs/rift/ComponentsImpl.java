package io.github.achirdlabs.rift;

import io.github.achirdlabs.rift.transport.RiftTransport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

final class ComponentsImpl implements Components {

    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    private final RiftTransport transport;

    ComponentsImpl(RiftTransport transport) {
        this.transport = transport;
    }

    @Override
    public ComponentInfo upload(byte[] component) {
        Objects.requireNonNull(component, "component");
        if (component.length == 0) {
            throw new IllegalArgumentException("a wasm component cannot be empty");
        }
        return ComponentInfo.read(transport.uploadComponent(component));
    }

    @Override
    public ComponentInfo upload(Path wasmFile) {
        Objects.requireNonNull(wasmFile, "wasmFile");
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(wasmFile);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the wasm component " + wasmFile + ": " + e.getMessage(), e);
        }
        return upload(bytes);
    }

    @Override
    public List<ComponentInfo> list() {
        return ComponentInfo.readList(transport.listComponents());
    }

    @Override
    public Optional<ComponentInfo> get(String sha256) {
        return transport.getComponent(hex(sha256)).map(ComponentInfo::read);
    }

    @Override
    public void delete(String sha256) {
        transport.deleteComponent(hex(sha256));
    }

    /**
     * The route's path segment: 64 lowercase hex digits. Checked here so a typo is an argument error
     * rather than a 404 read as "not held", which would make {@link #get} answer empty and {@link
     * #delete} succeed for a component that was never named.
     */
    static String hex(String sha256) {
        Objects.requireNonNull(sha256, "sha256");
        String hex = sha256.startsWith("sha256:") ? sha256.substring("sha256:".length()) : sha256;
        if (!SHA256_HEX.matcher(hex).matches()) {
            throw new IllegalArgumentException("a component is named by 64 lowercase hex digits (optionally prefixed"
                    + " \"sha256:\"), got \"" + sha256 + "\"");
        }
        return hex;
    }
}
