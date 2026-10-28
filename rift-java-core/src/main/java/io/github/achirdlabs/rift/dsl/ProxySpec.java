package io.github.achirdlabs.rift.dsl;

import io.github.achirdlabs.rift.json.JsonBool;
import io.github.achirdlabs.rift.json.JsonObject;
import io.github.achirdlabs.rift.json.JsonString;
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
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
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
    private final Optional<ClientCertificate> clientCertificate;

    private ProxySpec(
            String to,
            String mode,
            List<JsonValue> predicateGenerators,
            boolean addWaitBehavior,
            Map<String, String> injectHeaders,
            Optional<String> addDecorateBehavior,
            Optional<PathRewrite> pathRewrite,
            List<Behavior> behaviors,
            Optional<ClientCertificate> clientCertificate) {
        this.to = to;
        this.mode = mode;
        this.predicateGenerators = predicateGenerators;
        this.addWaitBehavior = addWaitBehavior;
        this.injectHeaders = injectHeaders;
        this.addDecorateBehavior = addDecorateBehavior;
        this.pathRewrite = pathRewrite;
        this.behaviors = behaviors;
        this.clientCertificate = clientCertificate;
    }

    /**
     * A fresh proxy targeting {@code url} that sends no {@code mode}, so the engine's default applies:
     * {@code proxyOnce} from rift 0.21.0 (Mountebank's default). An older engine forwarded every request
     * when the proxy had no {@code predicateGenerators}; call {@link #proxyTransparent()} for that.
     */
    static ProxySpec to(String url) {
        return new ProxySpec(url, "", List.of(), false, Map.of(), Optional.empty(), Optional.empty(), List.of(), Optional.empty());
    }

    /**
     * Forwards the first matching request and records its response as a stub, which answers every
     * later match: record once, then replay.
     */
    public ProxySpec proxyOnce() {
        return withMode("proxyOnce");
    }

    /** Forwards every matching request live and records each response, for a later replay. */
    public ProxySpec proxyAlways() {
        return withMode("proxyAlways");
    }

    /** Forwards every matching request live and records nothing. */
    public ProxySpec proxyTransparent() {
        return withMode("proxyTransparent");
    }

    private ProxySpec withMode(String newMode) {
        return new ProxySpec(to, newMode, predicateGenerators, addWaitBehavior, injectHeaders, addDecorateBehavior, pathRewrite, behaviors, clientCertificate);
    }

    /** Adds a predicate generator, controlling which parts of a proxied request become a recorded predicate. */
    public ProxySpec withPredicateGenerator(JsonValue generator) {
        List<JsonValue> next = Stream.concat(predicateGenerators.stream(), Stream.of(generator)).toList();
        return new ProxySpec(to, mode, next, addWaitBehavior, injectHeaders, addDecorateBehavior, pathRewrite, behaviors, clientCertificate);
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
        return new ProxySpec(to, mode, predicateGenerators, addWaitBehavior, next, addDecorateBehavior, pathRewrite, behaviors, clientCertificate);
    }

    /** Rewrites the {@code from} substring of the proxied request's path to {@code to}. */
    public ProxySpec rewritePath(String from, String to) {
        return new ProxySpec(this.to, mode, predicateGenerators, addWaitBehavior, injectHeaders, addDecorateBehavior, Optional.of(new PathRewrite(from, to)), behaviors, clientCertificate);
    }

    /**
     * Records the upstream's latency on each stub this proxy records, as a {@code wait} behavior on
     * that stub (Mountebank's {@code proxy.addWaitBehavior}; only meaningful when this proxy
     * records). Not the same as {@link #waitMs}, which delays this proxy's own response.
     */
    public ProxySpec addWaitBehavior() {
        return new ProxySpec(to, mode, predicateGenerators, true, injectHeaders, addDecorateBehavior, pathRewrite, behaviors, clientCertificate);
    }

    /**
     * Attaches {@code script} as a {@code decorate} behavior to each stub this proxy records
     * (Mountebank's {@code proxy.addDecorateBehavior}), so it runs when a recording is replayed.
     * Not the same as {@link #decorate}, which rewrites the live upstream response before it is
     * served and before it is recorded (rift &ge; 0.18.0).
     */
    public ProxySpec decorateWith(String script) {
        return new ProxySpec(to, mode, predicateGenerators, addWaitBehavior, injectHeaders, Optional.of(script), pathRewrite, behaviors, clientCertificate);
    }

    /**
     * Presents this client certificate to an upstream that asks for one (mutual TLS) — the proxy's
     * {@code cert} and {@code key}, as Mountebank's proxy takes them. Both PEM; the key unencrypted
     * PKCS#8, PKCS#1 or SEC1. Needs rift &ge; 0.24.0 (rift#1609): an older engine drops both keys
     * without a word, so creating the imposter there fails with
     * {@link io.github.achirdlabs.rift.error.InvalidDefinition} when the engine's version can be
     * checked. A key that does not match the certificate is refused by the engine with {@code 400}.
     *
     * <p>Which upstream certificates the proxy trusts is separate: {@code SpawnOptions.upstreamTrust}
     * / {@code EmbeddedOptions.upstreamTrust}. As in Mountebank, the engine shows {@code cert},
     * {@code key} and {@code passphrase} in {@code GET /imposters} and its {@code --datadir} files,
     * so protect the admin API; it never logs them. A later call replaces an earlier one.
     *
     * @throws IllegalArgumentException if {@code certPem} holds no {@code -----BEGIN CERTIFICATE-----}
     *                                  block or {@code keyPem} no {@code -----BEGIN ... PRIVATE KEY-----}
     *                                  block
     */
    public ProxySpec clientCertificate(String certPem, String keyPem) {
        return withClientCertificate(new ClientCertificate(certPem, keyPem, Optional.empty()));
    }

    /**
     * {@link #clientCertificate(String, String)} for a key in encrypted PKCS#8, decrypted with
     * {@code passphrase} (the proxy's {@code passphrase}).
     *
     * @throws IllegalArgumentException as {@link #clientCertificate(String, String)}, or if
     *                                  {@code passphrase} is empty
     */
    public ProxySpec clientCertificate(String certPem, String keyPem, String passphrase) {
        Objects.requireNonNull(passphrase, "passphrase");
        if (passphrase.isEmpty()) {
            throw new IllegalArgumentException(
                    "passphrase must not be empty; use clientCertificate(certPem, keyPem) for an unencrypted key");
        }
        return withClientCertificate(new ClientCertificate(certPem, keyPem, Optional.of(passphrase)));
    }

    private ProxySpec withClientCertificate(ClientCertificate certificate) {
        return new ProxySpec(to, mode, predicateGenerators, addWaitBehavior, injectHeaders, addDecorateBehavior, pathRewrite, behaviors, Optional.of(certificate));
    }

    @Override
    public ProxySpec withBehavior(Behavior behavior) {
        List<Behavior> next = Stream.concat(behaviors.stream(), Stream.of(behavior)).toList();
        return new ProxySpec(to, mode, predicateGenerators, addWaitBehavior, injectHeaders, addDecorateBehavior, pathRewrite, next, clientCertificate);
    }

    /** Builds the immutable {@link Response.Proxy} this spec represents. */
    @Override
    public Response build() {
        ProxyResponse proxy = new ProxyResponse(to, mode, predicateGenerators, addWaitBehavior, injectHeaders, addDecorateBehavior, pathRewrite);
        if (clientCertificate.isPresent()) {
            ClientCertificate c = clientCertificate.get();
            proxy = proxy.withExtra("cert", new JsonString(c.certPem())).withExtra("key", new JsonString(c.keyPem()));
            if (c.passphrase().isPresent()) {
                proxy = proxy.withExtra("passphrase", new JsonString(c.passphrase().get()));
            }
        }
        return new Response.Proxy(
                proxy,
                new Behaviors(behaviors),
                Map.of());
    }

    /** The proxy's {@code cert}/{@code key}/{@code passphrase}; a pair by construction, as the engine requires. */
    private record ClientCertificate(String certPem, String keyPem, Optional<String> passphrase) {
        ClientCertificate {
            Objects.requireNonNull(certPem, "certPem");
            Objects.requireNonNull(keyPem, "keyPem");
            if (!certPem.contains("-----BEGIN CERTIFICATE-----")) {
                throw new IllegalArgumentException(
                        "client certificate PEM contains no certificate: expected a -----BEGIN CERTIFICATE----- block");
            }
            if (!PRIVATE_KEY_BLOCK.matcher(keyPem).find()) {
                throw new IllegalArgumentException(
                        "client key PEM contains no private key: expected a -----BEGIN ... PRIVATE KEY----- block");
            }
        }

        /** PKCS#8 ({@code PRIVATE KEY}, {@code ENCRYPTED PRIVATE KEY}), PKCS#1 ({@code RSA}) and SEC1 ({@code EC}). */
        private static final Pattern PRIVATE_KEY_BLOCK =
                Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----");
    }
}
