package org.box1.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicLong;

import org.box1.asset.ingest.TokenBucket;
import org.junit.jupiter.api.Test;

/** D-004: the shared ingestion rate. */
class TokenBucketTest {
    @Test
    void admitsTheRateAndNoMore() {
        AtomicLong now = new AtomicLong();
        TokenBucket bucket = new TokenBucket(10, now::get);
        int admitted = 0;
        for (int i = 0; i < 50; i++) {
            admitted += bucket.tryAcquire() ? 1 : 0;
        }
        assertEquals(10, admitted, "one second of burst");
        now.addAndGet(500_000_000L);
        admitted = 0;
        for (int i = 0; i < 50; i++) {
            admitted += bucket.tryAcquire() ? 1 : 0;
        }
        assertEquals(5, admitted, "half a second refills half the rate");
    }
}
