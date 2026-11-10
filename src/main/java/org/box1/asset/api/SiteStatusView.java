package org.box1.asset.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.box1.asset.site.SiteReport;
import org.box1.asset.site.SiteStates;
import org.box1.asset.site.StoredSite;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** The contract's {@code SiteStatus}: a site's latest report plus its state, derived when it is read. */
@Schema(name = "SiteStatus", requiredProperties = {"siteId", "state", "stateReasons", "lastSeenAt", "lastSeenAgeSeconds", "reportedAt",
    "bufferDepth", "siteEventBacklog", "bufferOldestAgeSeconds", "readingsDropped", "greenbootStatus", "appVersions"})
public record SiteStatusView(
        @Schema(pattern = "^site_[A-Za-z0-9_-]{1,64}$", examples = "site_spacedock") String siteId,
        @Schema(enumeration = {"ONLINE", "DEGRADED", "OFFLINE"}) String state,
        @Schema(description = "Why the site is not ONLINE; empty when it is") List<SiteStates.Reason> stateReasons,
        @Schema(implementation = String.class, format = "date-time", description = "When asset-service received the latest status")
        Instant lastSeenAt,
        @Schema(minimum = "0") long lastSeenAgeSeconds,
        @Schema(implementation = String.class, format = "date-time", nullable = true,
            description = "measuredAt of the site's latest stored reading") Instant lastReadingAt,
        @Schema(implementation = String.class, format = "date-time") Instant reportedAt,
        @Schema(minimum = "0") long bufferDepth,
        @Schema(minimum = "0") long siteEventBacklog,
        @Schema(minimum = "0") long bufferOldestAgeSeconds,
        @Schema(minimum = "0") long readingsDropped,
        @Schema(pattern = "^sha256:[a-f0-9]{64}$", nullable = true) String bootedImageDigest,
        @Schema(pattern = "^sha256:[a-f0-9]{64}$", nullable = true) String stagedImageDigest,
        @Schema(pattern = "^sha256:[a-f0-9]{64}$", nullable = true) String rollbackImageDigest,
        @Schema(enumeration = {"GREEN", "RED", "UNKNOWN"}) String greenbootStatus,
        @Schema(pattern = "^sha256:[a-f0-9]{64}$", nullable = true) String bundleDigest,
        @Schema(additionalProperties = String.class) Map<String, String> appVersions) {

    public static SiteStatusView of(StoredSite site, SiteStates.Thresholds thresholds, Instant now) {
        SiteReport report = site.report();
        SiteStates.Derived derived = SiteStates.derive(thresholds, site.receivedAt(), report, now);
        return new SiteStatusView(site.siteId(), derived.state().name(), derived.reasons(),
                site.receivedAt(), SiteStates.ageSeconds(site.receivedAt(), now), site.lastReadingAt(), report.reportedAt(),
                report.bufferDepth(), report.siteEventBacklog(), report.bufferOldestAgeSeconds(), report.readingsDropped(),
                report.bootedImageDigest(), report.stagedImageDigest(), report.rollbackImageDigest(), report.greenbootStatus(),
                report.bundleDigest(), report.appVersions());
    }

    /** The list response. */
    @Schema(name = "SiteStatusList", requiredProperties = "items")
    public record Page(List<SiteStatusView> items) {
    }
}
