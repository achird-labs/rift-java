package io.github.achirdlabs.rift.dsl;

/**
 * The predicate operator a proxy predicate generator records with ({@code predicateOperator}),
 * instead of the engine's default ({@code deepEquals} for a whole field). Requires rift &ge; 0.22.0.
 */
public enum GeneratorOperator {

    EQUALS("equals"),
    DEEP_EQUALS("deepEquals"),
    CONTAINS("contains"),
    STARTS_WITH("startsWith"),
    ENDS_WITH("endsWith"),
    MATCHES("matches"),
    EXISTS("exists");

    private final String wire;

    GeneratorOperator(String wire) {
        this.wire = wire;
    }

    /** The operator's wire name. */
    String wire() {
        return wire;
    }
}
