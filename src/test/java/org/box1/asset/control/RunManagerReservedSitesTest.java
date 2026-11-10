package org.box1.asset.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.box1.asset.TestCatalog;
import org.junit.jupiter.api.Test;

/** box1-docs#128: arming or previewing a run that would emit a reserved (real edge) site is refused and audited. */
class RunManagerReservedSitesTest {
    static final class AuditOnly extends RunStore {
        final List<String> audits = new ArrayList<>();

        AuditOnly() {
            super(null);
        }

        @Override
        public void audit(String runId, String actor, String action, String detail) {
            audits.add(action + " " + detail);
        }
    }

    @Test
    void aRunThatWouldEmitAReservedSiteIsRefused() {
        AuditOnly runs = new AuditOnly();
        // site_load_02 stands in for a real site, so the refusal is reachable with today's generator naming.
        RunManager manager = new RunManager(runs, null, null, TestCatalog.catalog(), null, null,
                TestCatalog.config(false, List.of(), null, List.of("site_ds9", "site_spacedock", "site_load_02")), new SimpleMeterRegistry(),
                null);
        RunManager.RunRequest two = new RunManager.RunRequest(RunKind.LOAD, 2, 1, List.of("power"), 1, 10, null);
        ControlException refused = assertThrows(ControlException.class, () -> manager.validate("op", two));
        assertEquals(422, refused.status());
        assertEquals("RESERVED_SITE", refused.code());
        assertEquals(List.of("DENIED {\"code\":\"RESERVED_SITE\"}"), runs.audits);
        RunManager.RunRequest one = new RunManager.RunRequest(RunKind.LOAD, 1, 1, List.of("power"), 1, 10, null);
        assertEquals(List.of("site_load_01"), manager.validate("op", one).siteIds());
    }

    @Test
    void theDefaultReservedSitesDoNotLimitTodaysRuns() {
        RunManager manager = new RunManager(new AuditOnly(), null, null, TestCatalog.catalog(), null, null,
                TestCatalog.config(false, List.of(), null), new SimpleMeterRegistry(), null);
        RunManager.RunRequest max = new RunManager.RunRequest(RunKind.CHAOS, 5, 2, List.of("power"), 1, 10, null);
        assertEquals(5, manager.validate("op", max).siteIds().size());
    }
}
