package io.github.achirdlabs.rift;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineVersionTest {

    @Test
    void comparesComponentsNumericallyNotLexically() {
        assertEquals(1, Integer.signum(EngineVersion.compare("0.18.0", "0.17.9")));
        assertEquals(1, Integer.signum(EngineVersion.compare("0.10.0", "0.9.0")));
        assertEquals(-1, Integer.signum(EngineVersion.compare("0.17.0", "0.18.0")));
        assertEquals(-1, Integer.signum(EngineVersion.compare("0.18.0", "1.0.0")));
        assertEquals(0, EngineVersion.compare("0.18.0", "0.18.0"));
    }

    @Test
    void ignoresALeadingVAndAPreReleaseSuffix() {
        assertEquals(0, EngineVersion.compare("v0.18.0", "0.18.0"));
        assertEquals(0, EngineVersion.compare("V0.18.0", "0.18.0"));
        assertEquals(0, EngineVersion.compare("0.18.0-rc.1", "0.18.0"));
    }

    @Test
    void missingComponentsReadAsZero() {
        assertEquals(0, EngineVersion.compare("0.18", "0.18.0"));
        assertEquals(-1, Integer.signum(EngineVersion.compare("", "0.0.1")));
    }

    @Test
    void atLeastIsInclusive() {
        assertTrue(EngineVersion.atLeast("0.18.0", "0.18.0"));
        assertTrue(EngineVersion.atLeast("0.18.1", "0.18.0"));
        assertFalse(EngineVersion.atLeast("0.17.99", "0.18.0"));
    }
}
