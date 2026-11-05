package org.box1.asset.ingest;

import java.util.function.LongSupplier;

/** Shared admission rate for API, load and chaos readings (D-004). Burst capacity is one second of rate. */
public final class TokenBucket {
    private final double ratePerNano;
    private final double capacity;
    private final LongSupplier nanoTime;
    private double tokens;
    private long last;

    public TokenBucket(int ratePerSecond, LongSupplier nanoTime) {
        this.ratePerNano = ratePerSecond / 1_000_000_000.0;
        this.capacity = ratePerSecond;
        this.nanoTime = nanoTime;
        this.tokens = ratePerSecond;
        this.last = nanoTime.getAsLong();
    }

    public synchronized boolean tryAcquire() {
        long now = nanoTime.getAsLong();
        tokens = Math.min(capacity, tokens + (now - last) * ratePerNano);
        last = now;
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return true;
        }
        return false;
    }
}
