package org.box1.asset;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.box1.asset.config.AssetConfig;
import org.box1.asset.domain.MetricCatalog;

/** The production metric policy, built without CDI for pure unit tests. */
public final class TestCatalog {
    private TestCatalog() {
    }

    public static MetricCatalog catalog() {
        return new MetricCatalog(config(false, List.of(), null));
    }

    public static AssetConfig config(boolean chaosEnabled, List<String> allowlist, String namespace) {
        Map<String, AssetConfig.MetricPolicy> metrics = Map.of(
                "temperature", policy("C", List.of("F"), -50, 250, 80, 95, 75, 60),
                "vibration", policy("mm/s", null, 0, 100, 7.1, 11.2, 6.0, 3.0),
                "pressure", policy("bar", null, 0, 50, 8.0, 10.0, 7.5, 5.0),
                "power", policy("kW", null, 0, 5000, 400, 480, 380, 250));
        return new AssetConfig() {
            public Ingest ingest() {
                return new Ingest() {
                    public int maxRatePerSecond() { return 100; }
                    public int queueCapacity() { return 3; }
                    public Duration ackTimeout() { return Duration.ofSeconds(5); }
                    public Duration maxFutureSkew() { return Duration.ofMinutes(5); }
                    public int maxBodyBytes() { return 4096; }
                    public String topic() { return "box1.telemetry.events.v1"; }
                };
            }

            public Retention retention() {
                return new Retention() {
                    public Duration readings() { return Duration.ofHours(24); }
                    public long maxRows() { return 2_000_000; }
                    public int batchSize() { return 5000; }
                    public Duration clearedAlerts() { return Duration.ofDays(30); }
                    public Duration audit() { return Duration.ofDays(90); }
                };
            }

            public Control control() {
                return new Control() {
                    public boolean enabled() { return true; }
                    public int maxRunRatePerSecond() { return 50; }
                    public int maxSites() { return 5; }
                    public int maxAssetsPerSite() { return 20; }
                    public int maxRatePerAsset() { return 5; }
                    public Duration maxDuration() { return Duration.ofMinutes(15); }
                    public Duration ttlGrace() { return Duration.ofSeconds(30); }
                    public int maxConcurrentRuns() { return 1; }
                    public Duration armLifetime() { return Duration.ofSeconds(60); }
                    public Duration staleHeartbeat() { return Duration.ofSeconds(20); }
                };
            }

            public Chaos chaos() {
                return new Chaos() {
                    public boolean enabled() { return chaosEnabled; }
                    public Optional<List<String>> namespaceAllowlist() { return Optional.of(allowlist); }
                    public Optional<String> podNamespace() { return Optional.ofNullable(namespace); }
                    public String environment() { return "test"; }
                    public Duration maxPause() { return Duration.ofSeconds(30); }
                    public int maxBurstMultiplier() { return 5; }
                };
            }

            public Map<String, MetricPolicy> metrics() { return metrics; }
            public Map<String, ThresholdOverride> thresholdOverrides() {
                return Map.of("site_hot.temperature", new ThresholdOverride() {
                    public Optional<Double> warning() { return Optional.of(90.0); }
                    public Optional<Double> critical() { return Optional.empty(); }
                    public Optional<Double> clearBelow() { return Optional.of(85.0); }
                });
            }
            public Duration staleAfter() { return Duration.ofMinutes(2); }
        };
    }

    private static AssetConfig.MetricPolicy policy(String unit, List<String> alternates, double min, double max, double warning,
            double critical, double clear, double nominal) {
        return new AssetConfig.MetricPolicy() {
            public String unit() { return unit; }
            public Optional<List<String>> alternateUnits() { return Optional.ofNullable(alternates); }
            public double min() { return min; }
            public double max() { return max; }
            public double warning() { return warning; }
            public double critical() { return critical; }
            public double clearBelow() { return clear; }
            public double nominal() { return nominal; }
        };
    }
}
