package org.box1.asset.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

/** All runtime settings. Hard maximums live in {@link Limits}; configuration can only lower them. */
@ConfigMapping(prefix = "asset")
public interface AssetConfig {

    Ingest ingest();

    Retention retention();

    Control control();

    Chaos chaos();

    /** Per-metric policy, keyed by metric name. */
    Map<String, MetricPolicy> metrics();

    /** Overrides keyed by "siteId.metric" or "assetId.metric" (see D-008). */
    @WithName("threshold-overrides")
    Map<String, ThresholdOverride> thresholdOverrides();

    /** Clients besides asset-service whose tokens may read, with telemetry-reader only (box1-docs#80). */
    @WithName("read-clients")
    Optional<List<String>> readClients();

    /**
     * Edge site clients (box1-docs#130). Their tokens keep only {@code site-reporter}, which allows nothing but writing
     * the status of the site named in the token's {@code siteId} claim.
     */
    @WithName("site-clients")
    Optional<List<String>> siteClients();

    @WithName("stale-after")
    @WithDefault("PT2M")
    Duration staleAfter();

    /** Thresholds for a site's state, derived at read time (box1-ops-contracts SITE.md). */
    Sites sites();

    interface Sites {
        /** No status received for longer than this: OFFLINE (HEARTBEAT_STALE). */
        @WithDefault("PT90S")
        Duration offlineAfter();

        /** Online and buffer depth at least this: DEGRADED (BUFFER_DEPTH). */
        @WithDefault("200")
        long degradedBufferDepth();

        /** Online and the oldest buffered row at least this old: DEGRADED (BUFFER_AGE). */
        @WithDefault("PT5M")
        Duration degradedBufferAge();
    }

    interface Ingest {
        @WithDefault("100")
        int maxRatePerSecond();

        @WithDefault("2000")
        int queueCapacity();

        @WithDefault("PT5S")
        Duration ackTimeout();

        @WithDefault("PT5M")
        Duration maxFutureSkew();

        @WithDefault("4096")
        int maxBodyBytes();

        @WithDefault("box1.telemetry.events.v1")
        String topic();
    }

    interface Retention {
        @WithDefault("PT24H")
        Duration readings();

        @WithDefault("2000000")
        long maxRows();

        @WithDefault("5000")
        int batchSize();

        @WithDefault("P30D")
        Duration clearedAlerts();

        @WithDefault("P90D")
        Duration audit();
    }

    interface Control {
        @WithDefault("true")
        boolean enabled();

        @WithDefault("50")
        int maxRunRatePerSecond();

        @WithDefault("5")
        int maxSites();

        @WithDefault("20")
        int maxAssetsPerSite();

        @WithDefault("5")
        int maxRatePerAsset();

        @WithDefault("PT15M")
        Duration maxDuration();

        @WithDefault("PT30S")
        Duration ttlGrace();

        @WithDefault("1")
        int maxConcurrentRuns();

        @WithDefault("PT60S")
        Duration armLifetime();

        @WithDefault("PT20S")
        Duration staleHeartbeat();

        /** Real edge sites the synthetic generator must never emit (box1-docs#128). */
        @WithName("reserved-sites")
        Optional<List<String>> reservedSites();
    }

    interface Chaos {
        @WithDefault("false")
        boolean enabled();

        /** Comma-separated namespaces. Empty means chaos is allowed nowhere. */
        @WithName("namespace-allowlist")
        Optional<List<String>> namespaceAllowlist();

        /** The pod's namespace, from the downward API. Unknown means chaos is refused. */
        @WithName("pod-namespace")
        Optional<String> podNamespace();

        @WithDefault("unknown")
        String environment();

        @WithDefault("PT30S")
        Duration maxPause();

        @WithDefault("5")
        int maxBurstMultiplier();
    }

    interface MetricPolicy {
        /** Canonical unit; thresholds and bounds are in this unit. */
        String unit();

        /** Other accepted units, converted by {@link org.box1.asset.domain.Units}. */
        Optional<List<String>> alternateUnits();

        double min();

        double max();

        double warning();

        double critical();

        @WithName("clear")
        double clearBelow();

        /** Centre of generated load values. */
        double nominal();
    }

    interface ThresholdOverride {
        Optional<Double> warning();

        Optional<Double> critical();

        @WithName("clear")
        Optional<Double> clearBelow();
    }
}
