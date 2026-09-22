package io.github.achirdlabs.rift.dsl;

import io.github.achirdlabs.rift.model.Response;

/**
 * A stub response under construction. Sealed over the five shapes a Mountebank/Rift response can
 * take: a literal ("is") response ({@link IsSpec}), a proxy ({@link ProxySpec}), a raw connection
 * fault ({@link FaultSpec}), an inline-JavaScript response ({@link InjectSpec}), or a {@code _rift}
 * script-only response ({@link ScriptSpec}).
 *
 * <p>Chain methods follow what the engine does with each shape, so an unsupported request is a
 * compile error rather than a runtime {@code IllegalStateException}. Only {@link IsSpec} has a
 * status, headers and a body. Behaviors ({@link BehaviorChain}) are legal on {@link IsSpec}, {@link
 * ProxySpec} and {@link InjectSpec} — the engine runs them on all three (on {@code proxy}/{@code
 * inject} from rift 0.18.0). {@link ProxySpec} also has its own proxy knobs. A {@link FaultSpec} or
 * {@link ScriptSpec} is terminal.
 */
public sealed interface ResponseSpec permits IsSpec, ProxySpec, FaultSpec, InjectSpec, ScriptSpec {

    /** Builds the immutable {@link Response} this spec represents. */
    Response build();
}
