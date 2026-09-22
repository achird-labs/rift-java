package io.github.achirdlabs.rift.dsl;

import io.github.achirdlabs.rift.json.JsonBool;
import io.github.achirdlabs.rift.json.JsonObject;
import io.github.achirdlabs.rift.json.JsonValue;
import io.github.achirdlabs.rift.model.Behavior;
import io.github.achirdlabs.rift.model.Behaviors;
import io.github.achirdlabs.rift.model.PathRewrite;
import io.github.achirdlabs.rift.model.ProxyResponse;
import io.github.achirdlabs.rift.model.Response;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * A proxy response under construction, produced by {@link RiftDsl#proxyTo(String)}: forwards the
 * matched request to an upstream URL and, optionally, records the exchange as a new stub.
 *
 * <p>The {@link BehaviorChain} chainers ({@link #waitMs}, {@link #decorate}, {@link #repeat}, ...)
 * run on the upstream response before it is served and before it is recorded; they need a rift
 * engine &ge; 0.18.0. {@link #addWaitBehavior()} and {@link #decorateWith(String)} are different
 * knobs: they configure the stubs this proxy <em>records</em>.
 *
 * <p>Instances are immutable: every chain method returns a new {@code ProxySpec}. The terminal
 * {@link #build()} produces the {@link Response.Proxy} model value.
 */
public final class ProxySpec implements ResponseSpec, BehaviorChain<ProxySpec> {

    private final String to;
    private final String mode;
    private final List<JsonValue> predicateGenerators;
    private final boolean addWaitBehavior;
    private final Map<String, String> injectHeaders;
    private final Optional<String> addDecorateBehavior;
    private final Optional<PathRewrite> pathRewrite;
    private final List<Behavior> behaviors;

    private ProxySpec(
            String to,
            String mode,
            List<JsonValue> predicateGenerators,
            boolean addWaitBehavior,
            Map<String, String> injectHeaders,
            Optional<String> addDecorateBehavior,
            Optional<PathRewrite> pathRewrite,
            List<Behavior> behaviors) {
        this.to = to;
        this.mode = mode;
        this.predicateGenerators = predicateGenerators;
        this.addWaitBehavior = addWaitBehavior;
        this.injectHeaders = injectHeaders;
        this.addDecorateBehavior = addDecorateBehavior;
        this.pathRewrite = pathRewrite;
        this.behaviors = behaviors;
    }

    /** A fresh proxy targeting {@code url}, with the engine's default proxy mode. */
    static ProxySpec to(String url) {
        return new ProxySpec(url, "", List.of(), false, Map.of(), Optional.empty(), Optional.empty(), List.of());
    }

    /** Proxies each matching request and records only the first response as a permanent stub. */
    public ProxySpec proxyOnce() {
        return withMode("proxyOnce");
    }

    /** Proxies every matching request, always forwarding live (no recording). */
    public ProxySpec proxyAlways() {
        return withMode("proxyAlways");
    }

    /** Proxies every matching request without ever recording a new stub. */
    public ProxySpec proxyTransparent() {
        return withMode("proxyTransparent");
    }

    private ProxySpec withMode(String newMode) {
        return new ProxySpec(to, newMode, predicateGenerators, addWaitBehavior, injectHeaders, addDecorateBehavior, pathRewrite, behaviors);
    }

    /** Adds a predicate generator, controlling which parts of a proxied request become a recorded predicate. */
    public ProxySpec withPredicateGenerator(JsonValue generator) {
        List<JsonValue> next = Stream.concat(predicateGenerators.stream(), Stream.of(generator)).toList();
        return new ProxySpec(to, mode, next, addWaitBehavior, injectHeaders, addDecorateBehavior, pathRewrite, behaviors);
    }

    /** Adds a predicate generator, parsing {@code jsonText} as its JSON definition. */
    public ProxySpec withPredicateGenerator(String jsonText) {
        return withPredicateGenerator(RiftDsl.json(jsonText));
    }

    /** Adds a predicate generator matching the given plain request fields (e.g. {@code method}, {@code path}). */
    public ProxySpec generateBy(RequestField... fields) {
        JsonObject.Builder matches = JsonObject.builder();
        for (RequestField field : Arrays.asList(fields)) {
            matches.put(field.wire(), JsonBool.TRUE);
        }
        JsonObject generator = JsonObject.builder().put("matches", matches.build()).build();
        return withPredicateGenerator(generator);
    }

    /** Adds a predicate generator built from a {@link PredicateGeneratorSpec} (fields plus case-sensitivity/jsonpath knobs). */
    public ProxySpec generateBy(PredicateGeneratorSpec generator) {
        return withPredicateGenerator(generator.build());
    }

    /** Injects the given header into the proxied (upstream) request. Repeatable. */
    public ProxySpec injectHeader(String name, String value) {
        Map<String, String> next = new LinkedHashMap<>(injectHeaders);
        next.put(name, value);
        return new ProxySpec(to, mode, predicateGenerators, addWaitBehavior, next, addDecorateBehavior, pathRewrite, behaviors);
    }

    /** Rewrites the {@code from} substring of the proxied request's path to {@code to}. */
    public ProxySpec rewritePath(String from, String to) {
        return new ProxySpec(this.to, mode, predicateGenerators, addWaitBehavior, injectHeaders, addDecorateBehavior, Optional.of(new PathRewrite(from, to)), behaviors);
    }

    /**
     * Records the upstream's latency on each stub this proxy records, as a {@code wait} behavior on
     * that stub (Mountebank's {@code proxy.addWaitBehavior}; only meaningful when this proxy
     * records). Not the same as {@link #waitMs}, which delays this proxy's own response.
     */
    public ProxySpec addWaitBehavior() {
        return new ProxySpec(to, mode, predicateGenerators, true, injectHeaders, addDecorateBehavior, pathRewrite, behaviors);
    }

    /**
     * Attaches {@code script} as a {@code decorate} behavior to each stub this proxy records
     * (Mountebank's {@code proxy.addDecorateBehavior}), so it runs when a recording is replayed.
     * Not the same as {@link #decorate}, which rewrites the live upstream response before it is
     * served and before it is recorded (rift &ge; 0.18.0).
     */
    public ProxySpec decorateWith(String script) {
        return new ProxySpec(to, mode, predicateGenerators, addWaitBehavior, injectHeaders, Optional.of(script), pathRewrite, behaviors);
    }

    @Override
    public ProxySpec withBehavior(Behavior behavior) {
        List<Behavior> next = Stream.concat(behaviors.stream(), Stream.of(behavior)).toList();
        return new ProxySpec(to, mode, predicateGenerators, addWaitBehavior, injectHeaders, addDecorateBehavior, pathRewrite, next);
    }

    /** Builds the immutable {@link Response.Proxy} this spec represents. */
    @Override
    public Response build() {
        return new Response.Proxy(
                new ProxyResponse(to, mode, predicateGenerators, addWaitBehavior, injectHeaders, addDecorateBehavior, pathRewrite),
                new Behaviors(behaviors),
                Map.of());
    }
}
