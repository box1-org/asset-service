package org.box1.asset.site;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.box1.asset.config.AssetConfig;

/**
 * Derives a site's state at read time (box1-ops-contracts SITE.md, box1-docs#130). Pure: the caller passes the clock's
 * instant, so the boundaries are unit-tested.
 * <ul>
 * <li>{@code OFFLINE} (HEARTBEAT_STALE): no status received for longer than {@code offlineAfter}.</li>
 * <li>{@code DEGRADED}: online and buffer depth at least {@code degradedBufferDepth} (BUFFER_DEPTH), or the oldest
 * buffered row at least {@code degradedBufferAge} old (BUFFER_AGE), or greenboot {@code RED} (GREENBOOT_RED).</li>
 * <li>{@code ONLINE} otherwise, with no reasons.</li>
 * </ul>
 * An offline site reports only HEARTBEAT_STALE: its last buffer figures are no longer current.
 */
public final class SiteStates {
    private SiteStates() {
    }

    public enum State { ONLINE, DEGRADED, OFFLINE }

    /** Named for OpenAPI, where it is the {@code stateReasons} item schema. */
    @org.eclipse.microprofile.openapi.annotations.media.Schema(name = "SiteStateReason")
    public enum Reason { HEARTBEAT_STALE, BUFFER_DEPTH, BUFFER_AGE, GREENBOOT_RED }

    public record Thresholds(Duration offlineAfter, long degradedBufferDepth, Duration degradedBufferAge) {
        public static Thresholds of(AssetConfig config) {
            AssetConfig.Sites sites = config.sites();
            return new Thresholds(sites.offlineAfter(), sites.degradedBufferDepth(), sites.degradedBufferAge());
        }
    }

    public record Derived(State state, List<Reason> reasons) {
        public Derived {
            reasons = List.copyOf(reasons);
        }
    }

    public static Derived derive(Thresholds thresholds, Instant receivedAt, SiteReport report, Instant now) {
        if (Duration.between(receivedAt, now).compareTo(thresholds.offlineAfter()) > 0) {
            return new Derived(State.OFFLINE, List.of(Reason.HEARTBEAT_STALE));
        }
        List<Reason> reasons = new ArrayList<>(3);
        if (report.bufferDepth() >= thresholds.degradedBufferDepth()) {
            reasons.add(Reason.BUFFER_DEPTH);
        }
        if (report.bufferOldestAgeSeconds() >= thresholds.degradedBufferAge().toSeconds()) {
            reasons.add(Reason.BUFFER_AGE);
        }
        if ("RED".equals(report.greenbootStatus())) {
            reasons.add(Reason.GREENBOOT_RED);
        }
        return new Derived(reasons.isEmpty() ? State.ONLINE : State.DEGRADED, reasons);
    }

    /** Whole seconds since {@code then}, never negative. */
    public static long ageSeconds(Instant then, Instant now) {
        return Math.max(0, Duration.between(then, now).toSeconds());
    }
}
