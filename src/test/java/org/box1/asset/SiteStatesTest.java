package org.box1.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.box1.asset.site.SiteReport;
import org.box1.asset.site.SiteStates;
import org.box1.asset.site.SiteStates.Reason;
import org.box1.asset.site.SiteStates.State;
import org.junit.jupiter.api.Test;

/** box1-docs#130: the state boundaries with the default thresholds (PT90S, 200 rows, PT5M). */
class SiteStatesTest {
    private static final SiteStates.Thresholds DEFAULTS = SiteStates.Thresholds.of(TestCatalog.config(false, List.of(), null));
    private static final Instant RECEIVED = Instant.parse("2026-11-10T12:00:00Z");

    private static SiteReport report(long depth, long oldestAge, String greenboot) {
        return new SiteReport(RECEIVED, depth, 0, oldestAge, 0, null, null, null, greenboot, null, Map.of());
    }

    private static SiteStates.Derived at(Duration sinceReceived, SiteReport report) {
        return SiteStates.derive(DEFAULTS, RECEIVED, report, RECEIVED.plus(sinceReceived));
    }

    @Test
    void defaultsAreTheContractsThresholds() {
        assertEquals(Duration.ofSeconds(90), DEFAULTS.offlineAfter());
        assertEquals(200, DEFAULTS.degradedBufferDepth());
        assertEquals(Duration.ofMinutes(5), DEFAULTS.degradedBufferAge());
    }

    @Test
    void offlineOnlyAfterMoreThanNinetySecondsWithoutAStatus() {
        SiteReport healthy = report(0, 0, "GREEN");
        assertEquals(new SiteStates.Derived(State.ONLINE, List.of()), at(Duration.ZERO, healthy));
        assertEquals(State.ONLINE, at(Duration.ofSeconds(90), healthy).state(), "exactly 90 s is still online");
        assertEquals(new SiteStates.Derived(State.OFFLINE, List.of(Reason.HEARTBEAT_STALE)), at(Duration.ofMillis(90_001), healthy));
        // Offline reports only the stale heartbeat: the last buffer figures are no longer current.
        assertEquals(List.of(Reason.HEARTBEAT_STALE), at(Duration.ofMinutes(10), report(500, 900, "RED")).reasons());
        // A status received "in the future" (clock adjustment) is online with age 0.
        assertEquals(State.ONLINE, at(Duration.ofSeconds(-5), healthy).state());
        assertEquals(0, SiteStates.ageSeconds(RECEIVED.plusSeconds(5), RECEIVED));
        assertEquals(90, SiteStates.ageSeconds(RECEIVED, RECEIVED.plusMillis(90_999)));
    }

    @Test
    void degradedAtTheBufferDepthThreshold() {
        assertEquals(State.ONLINE, at(Duration.ZERO, report(199, 0, "GREEN")).state());
        assertEquals(new SiteStates.Derived(State.DEGRADED, List.of(Reason.BUFFER_DEPTH)), at(Duration.ZERO, report(200, 0, "GREEN")));
    }

    @Test
    void degradedAtTheBufferAgeThreshold() {
        assertEquals(State.ONLINE, at(Duration.ZERO, report(0, 299, "GREEN")).state());
        assertEquals(new SiteStates.Derived(State.DEGRADED, List.of(Reason.BUFFER_AGE)), at(Duration.ZERO, report(0, 300, "GREEN")));
    }

    @Test
    void degradedWhenGreenbootIsRedButNotWhenUnknown() {
        assertEquals(new SiteStates.Derived(State.DEGRADED, List.of(Reason.GREENBOOT_RED)), at(Duration.ZERO, report(0, 0, "RED")));
        assertEquals(State.ONLINE, at(Duration.ZERO, report(0, 0, "UNKNOWN")).state());
    }

    @Test
    void everyReasonIsListedInContractOrder() {
        assertEquals(List.of(Reason.BUFFER_DEPTH, Reason.BUFFER_AGE, Reason.GREENBOOT_RED),
                at(Duration.ofSeconds(90), report(1000, 1000, "RED")).reasons());
    }

    @Test
    void thresholdsAreConfigurable() {
        SiteStates.Thresholds strict = new SiteStates.Thresholds(Duration.ofSeconds(30), 10, Duration.ofSeconds(60));
        assertEquals(State.OFFLINE, SiteStates.derive(strict, RECEIVED, report(0, 0, "GREEN"), RECEIVED.plusSeconds(31)).state());
        assertEquals(State.DEGRADED, SiteStates.derive(strict, RECEIVED, report(10, 0, "GREEN"), RECEIVED).state());
        assertEquals(State.DEGRADED, SiteStates.derive(strict, RECEIVED, report(0, 60, "GREEN"), RECEIVED).state());
    }
}
