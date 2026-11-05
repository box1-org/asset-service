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
}
