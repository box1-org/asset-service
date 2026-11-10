package org.box1.asset.config;

import java.time.Duration;

/** Compiled-in ceilings (D-004). Startup fails if configuration asks for more. */
public final class Limits {
    public static final int MAX_INGEST_RATE = 200;
    public static final int MAX_QUEUE = 5000;
    public static final int MAX_RUN_RATE = 50;
    public static final int MAX_SITES = 5;
    public static final int MAX_ASSETS_PER_SITE = 20;
    public static final int MAX_RATE_PER_ASSET = 5;
    public static final int MAX_METRICS = 4;
    public static final Duration MAX_DURATION = Duration.ofMinutes(15);
    public static final Duration MAX_TTL_GRACE = Duration.ofSeconds(30);
    public static final int MAX_CONCURRENT_RUNS = 2;
    public static final Duration MAX_PAUSE = Duration.ofSeconds(30);
    public static final int MAX_BURST = 5;
    public static final int MAX_BODY_BYTES = 4096;

    private Limits() {
    }

    /** Throws when a configured value exceeds its ceiling. */
    public static void check(AssetConfig config) {
        require(config.ingest().maxRatePerSecond(), 1, MAX_INGEST_RATE, "asset.ingest.max-rate-per-second");
        require(config.ingest().queueCapacity(), 1, MAX_QUEUE, "asset.ingest.queue-capacity");
        require(config.ingest().maxBodyBytes(), 256, MAX_BODY_BYTES, "asset.ingest.max-body-bytes");
        AssetConfig.Control control = config.control();
        require(control.maxRunRatePerSecond(), 1, MAX_RUN_RATE, "asset.control.max-run-rate-per-second");
        require(control.maxSites(), 1, MAX_SITES, "asset.control.max-sites");
        require(control.maxAssetsPerSite(), 1, MAX_ASSETS_PER_SITE, "asset.control.max-assets-per-site");
        require(control.maxRatePerAsset(), 1, MAX_RATE_PER_ASSET, "asset.control.max-rate-per-asset");
        require(control.maxConcurrentRuns(), 1, MAX_CONCURRENT_RUNS, "asset.control.max-concurrent-runs");
        requireDuration(control.maxDuration(), MAX_DURATION, "asset.control.max-duration");
        requireDuration(control.ttlGrace(), MAX_TTL_GRACE, "asset.control.ttl-grace");
        requireDuration(config.chaos().maxPause(), MAX_PAUSE, "asset.chaos.max-pause");
        require(config.chaos().maxBurstMultiplier(), 1, MAX_BURST, "asset.chaos.max-burst-multiplier");
        requireDuration(config.sites().offlineAfter(), Duration.ofHours(1), "asset.sites.offline-after");
        require(config.sites().degradedBufferDepth(), 1, Long.MAX_VALUE, "asset.sites.degraded-buffer-depth");
        requireDuration(config.sites().degradedBufferAge(), Duration.ofDays(1), "asset.sites.degraded-buffer-age");
        if (config.metrics().isEmpty() || config.metrics().size() > MAX_METRICS) {
            throw new IllegalStateException("asset.metrics must define 1 to " + MAX_METRICS + " metrics");
        }
    }

    private static void require(long value, long min, long max, String name) {
        if (value < min || value > max) {
            throw new IllegalStateException(name + " must be between " + min + " and " + max + ", was " + value);
        }
    }

    private static void requireDuration(Duration value, Duration max, String name) {
        if (value.isNegative() || value.isZero() || value.compareTo(max) > 0) {
            throw new IllegalStateException(name + " must be positive and at most " + max + ", was " + value);
        }
    }
}
