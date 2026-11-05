package org.box1.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.box1.asset.control.ChaosGate;
import org.junit.jupiter.api.Test;

/** TEL-GATE-001: flag AND allowlisted namespace (D-015). */
class ChaosGateTest {
    @Test
    void closedUnlessEnabledAndAllowlisted() {
        assertEquals("CHAOS_DISABLED", new ChaosGate(TestCatalog.config(false, List.of("demo"), "demo")).decide().code());
        assertEquals("NAMESPACE_NOT_ALLOWLISTED", new ChaosGate(TestCatalog.config(true, List.of("demo"), "asset-service-dev")).decide().code());
        assertEquals("NAMESPACE_UNKNOWN", new ChaosGate(TestCatalog.config(true, List.of("demo"), null)).decide().code());
        assertEquals("NAMESPACE_NOT_ALLOWLISTED", new ChaosGate(TestCatalog.config(true, List.of(), "demo")).decide().code());
        assertFalse(new ChaosGate(TestCatalog.config(true, List.of(" "), "")).decide().open());
        assertTrue(new ChaosGate(TestCatalog.config(true, List.of("other", " demo "), "demo")).decide().open());
    }
}
