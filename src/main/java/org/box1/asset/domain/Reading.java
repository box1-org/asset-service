package org.box1.asset.domain;

import java.time.Instant;

/** A validated reading in its canonical unit. */
public record Reading(String assetId, String siteId, String metricName, Instant measuredAt, double value, String unit) {
}
