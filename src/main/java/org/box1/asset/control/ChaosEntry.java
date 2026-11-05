package org.box1.asset.control;

/** The telemetry-only chaos catalog (D-006). Nothing here touches another service, the broker or the database. */
public enum ChaosEntry {
    BURST("Multiply the run rate", "2-5x for 5-15 s, capped by the shared ingestion rate"),
    SPIKE("One reading far above critical", "1 reading at 1.2-1.5x critical"),
    SUSTAINED_BREACH("One asset above warning", "10-30 s"),
    FLAP("Values crossing the warning level", "10-30 s, alternating above warning and inside the hysteresis band"),
    STALE_ASSET("One asset goes silent", "10-30 s"),
    CLOCK_SKEW("Timestamps shifted", "5-15 s; +2 to +10 min (rejected) or -1 to -30 min (accepted)"),
    OUT_OF_ORDER("Timestamps shuffled", "5-15 s, blocks of 3-8 readings"),
    DUPLICATE("Readings re-sent verbatim", "1-10 readings"),
    INVALID_UNIT("Wrong unit for the metric", "1-5 readings, all rejected"),
    INVALID_PAYLOAD("Missing, non-finite or out-of-bounds fields", "1-5 readings, all rejected"),
    OVERSIZE_PAYLOAD("Body larger than the limit", "1-3 bodies, all rejected"),
    PRODUCER_PAUSE("Pause this service's own Kafka producer", "5-30 s, auto-resume; queue stays bounded");

    private final String description;
    private final String bounds;

    ChaosEntry(String description, String bounds) {
        this.description = description;
        this.bounds = bounds;
    }

    public String description() {
        return description;
    }

    public String bounds() {
        return bounds;
    }
}
