package org.box1.asset.api;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Documentation-only shapes for OpenAPI; the service parses the raw ingestion bytes itself. */
public final class ApiDocumentation {
    private ApiDocumentation() {
    }

    /** The ingestion body, for documentation; the service parses the raw bytes itself. */
    @Schema(name = "ReadingInput", requiredProperties = {"assetId", "siteId", "metricName", "measuredAt", "value", "unit"},
            additionalProperties = Schema.False.class)
    public record ReadingInput(
            @Schema(pattern = "^asset_[A-Za-z0-9_-]{1,64}$", examples = "asset_press_7") String assetId,
            @Schema(pattern = "^site_[A-Za-z0-9_-]{1,64}$", examples = "site_nyc") String siteId,
            @Schema(enumeration = {"temperature", "vibration", "pressure", "power"}) String metricName,
            @Schema(format = "date-time", examples = "2026-11-05T12:00:00Z") String measuredAt,
            @Schema(examples = "72.4") double value,
            @Schema(description = "C or F for temperature, mm/s for vibration, bar for pressure, kW for power", examples = "C") String unit) {
    }

    /** The 202 body. */
    public record Accepted(String eventId, @Schema(enumeration = "PUBLISHED") String status, String traceparent) {
    }

    /** The edge site heartbeat body (box1-docs#130); the service parses the raw bytes itself (SiteReports). */
    @Schema(name = "SiteStatusReport", requiredProperties = {"reportedAt", "bufferDepth", "siteEventBacklog", "bufferOldestAgeSeconds",
        "readingsDropped", "greenbootStatus", "appVersions"}, additionalProperties = Schema.False.class)
    public record SiteStatusReport(
            @Schema(format = "date-time", description = "The site's clock when it sent the report", examples = "2026-11-10T12:00:00Z")
            String reportedAt,
            @Schema(minimum = "0", description = "Unpublished outbox rows (readings and site events)") long bufferDepth,
            @Schema(minimum = "0", description = "Unpublished site events, which are never dropped") long siteEventBacklog,
            @Schema(minimum = "0", description = "Age of the oldest unpublished row; 0 when empty") long bufferOldestAgeSeconds,
            @Schema(minimum = "0", description = "Readings dropped by the buffer bound since the gateway started") long readingsDropped,
            @Schema(pattern = "^sha256:[a-f0-9]{64}$", nullable = true,
                description = "The OS image digest the site booted (bootc status), or null when unknown") String bootedImageDigest,
            @Schema(pattern = "^sha256:[a-f0-9]{64}$", nullable = true, description = "An OS image staged for the next boot, if any")
            String stagedImageDigest,
            @Schema(pattern = "^sha256:[a-f0-9]{64}$", nullable = true, description = "The OS image bootc would roll back to, if any")
            String rollbackImageDigest,
            @Schema(enumeration = {"GREEN", "RED", "UNKNOWN"}, description = "Result of the boot's greenboot health checks")
            String greenbootStatus,
            @Schema(pattern = "^sha256:[a-f0-9]{64}$", nullable = true,
                description = "The site-gateway bundle digest built into the booted image, or null when unknown") String bundleDigest,
            @Schema(maxProperties = 8, additionalProperties = String.class,
                description = "Application name (^[a-z][a-z0-9-]{0,31}$) to version (1 to 64 characters)")
            java.util.Map<String, String> appVersions) {
    }
}
