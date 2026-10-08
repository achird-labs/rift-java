package io.github.achirdlabs.rift.junit5;

import io.github.achirdlabs.rift.TruststoreFormat;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Starts a TLS-MITM intercept listener for a {@code @RiftTest} class. The listener and its CA live
 * for the class, and the listener is stopped when the class ends (an {@link #attach attached} one is
 * left running); only its rules reset per test (per the {@code @RiftTest} {@link Reset} policy).
 * Declare rules with a {@link RiftInterceptRules} method and get the live handle with
 * {@link InjectIntercept}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
public @interface RiftIntercept {

    /**
     * Attach to a listener the engine started at launch ({@code --intercept-port}) instead of starting
     * one, which such an engine refuses. With {@link #port} set, {@link #host} and {@link #port} are
     * where that listener is <em>reached</em>, used verbatim. Without it the listener is discovered
     * ({@code GET /intercept}, {@code InterceptOptions.attach()}) and reached through the engine's own
     * report and the connection's {@code interceptAddress} mapping; a {@link #host} given without a
     * port is refused, as it would be ignored. The listener already has its CA, so {@link #caCert},
     * {@link #caKey} and {@link #inlineCa} are refused.
     */
    boolean attach() default false;

    /**
     * Read {@link #caCert}/{@link #caKey} here and send the PEM in the start request (rift &ge;
     * 0.13.4), so an engine on another machine or in a container needs no file at those paths.
     * Without it the paths are handed to the engine, which reads them itself.
     */
    boolean inlineCa() default false;

    /**
     * Bind port; {@code 0} = OS-assigned. Fix it for a container SUT that points at a stable port.
     * With {@link #attach}, the port the running listener is reached on; {@code 0} discovers it.
     */
    int port() default 0;

    /**
     * Bind host; an IP literal ({@code "0.0.0.0"} to reach it from another container). With {@link
     * #attach}, the host the running listener is reached on, which may be a name.
     */
    String host() default "127.0.0.1";

    /** Committed CA cert PEM path (with {@link #caKey}); {@code ${property}} placeholders are resolved. Empty = ephemeral CA. */
    String caCert() default "";

    /** Committed CA key PEM path (with {@link #caCert}). */
    String caKey() default "";

    /** When set, a truststore is written here during {@code beforeAll} (for a container to mount). {@code ${property}} resolved. */
    String exportTruststore() default "";

    /** Format for {@link #exportTruststore}. */
    TruststoreFormat exportFormat() default TruststoreFormat.PKCS12;

    /** Password for {@link #exportTruststore}. */
    String exportPassword() default "changeit";
}
