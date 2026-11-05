package org.box1.asset.store;

import java.time.Instant;
import java.util.UUID;

/** A stored reading as the API returns it. */
public record ReadingView(String assetId, String siteId, String metricName, Instant measuredAt, double value, String unit,
        UUID sourceEventId, String traceparent) {
}
