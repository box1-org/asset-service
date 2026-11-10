package org.box1.asset.domain;

/** Stable reason codes for rejected readings. They appear in metrics, problem responses and dead-letter headers. */
public enum RejectReason {
    MALFORMED,
    MISSING_FIELD,
    UNKNOWN_FIELD,
    INVALID_ID,
    UNKNOWN_METRIC,
    INVALID_UNIT,
    NOT_FINITE,
    OUT_OF_BOUNDS,
    FUTURE_TIMESTAMP,
    STALE_TIMESTAMP,
    OVERSIZE,
    RATE_LIMITED,
    BACKPRESSURE,
    /** A load or chaos reading for a real edge site (asset.control.reserved-sites); never from the API. */
    RESERVED_SITE
}
