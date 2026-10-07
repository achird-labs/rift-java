package io.github.achirdlabs.rift.junit5;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@code static void} method that declares the intercept rules for a {@link RiftIntercept}
 * class. It is invoked once the listener starts and re-invoked after each per-test rules reset.
 * Its parameters are resolved like test parameters: an {@link io.github.achirdlabs.rift.InterceptRuleSet}
 * to declare the rules on, an {@link io.github.achirdlabs.rift.Intercept} (the live handle), an
 * {@link InjectRift} {@code Rift}, and {@link InjectImposter} {@code Imposter}s.
 *
 * <p>Prefer {@code InterceptRuleSet}. When no rules method of the class takes an {@code Intercept},
 * a per-test reset swaps the whole set in one step (rift &ge; 0.20.0), so a request the system under
 * test has in flight meets either the old rules or the new ones. A method that takes the live
 * {@code Intercept} — or an older engine — makes the reset clear the rules and add them again, which
 * leaves a moment with no rules at all:
 *
 * <pre>{@code
 * @RiftInterceptRules
 * static void rules(InterceptRuleSet rules, @InjectImposter("cdn") Imposter cdn) {
 *     rules.redirectTo("cdn.example.com", cdn);
 * }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RiftInterceptRules {
}
