package org.box1.asset.domain;

/** Effective thresholds for one asset and metric, in the canonical unit. */
public record Threshold(double warning, double critical, double clearBelow) {
    public Threshold {
        if (!(clearBelow < warning && warning <= critical)) {
            throw new IllegalArgumentException("Thresholds need clear < warning <= critical");
        }
    }
}
