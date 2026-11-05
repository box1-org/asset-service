package org.box1.asset.store;

import java.time.Instant;
import java.util.UUID;

/** An alert episode as the API returns it. */
public record AlertView(String alertId, String assetId, String siteId, String metricName, String unit, String status,
        String severity, double threshold, double firstValue, double peakValue, double lastValue, int readingCount,
        Instant activatedAt, Instant activatedMeasuredAt, Instant escalatedAt, Instant clearedAt, UUID sourceEventId,
        UUID eventId, String traceparent) {
}
