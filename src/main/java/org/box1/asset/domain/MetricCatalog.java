package org.box1.asset.domain;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import jakarta.enterprise.context.ApplicationScoped;
import org.box1.asset.config.AssetConfig;
import org.box1.asset.config.Limits;

/** Allowed metrics, units, bounds and thresholds, resolved from configuration once at startup. */
@ApplicationScoped
public class MetricCatalog {
    private final Map<String, AssetConfig.MetricPolicy> metrics;
    private final Map<String, AssetConfig.ThresholdOverride> overrides;

    public MetricCatalog(AssetConfig config) {
        Limits.check(config);
        this.metrics = new TreeMap<>(config.metrics());
        this.overrides = Map.copyOf(config.thresholdOverrides());
        for (String metric : metrics.keySet()) {
            defaults(metric);
        }
        for (String key : overrides.keySet()) {
            int dot = key.lastIndexOf('.');
            if (dot < 0 || !metrics.containsKey(key.substring(dot + 1))) {
                throw new IllegalStateException("Threshold override " + key + " must end with a configured metric");
            }
        }
    }

    public List<String> names() {
        return List.copyOf(metrics.keySet());
    }

    public Optional<AssetConfig.MetricPolicy> policy(String metric) {
        return Optional.ofNullable(metrics.get(metric));
    }

    public AssetConfig.MetricPolicy require(String metric) {
        return policy(metric).orElseThrow(() -> new IllegalArgumentException("Unknown metric " + metric));
    }

    public boolean acceptsUnit(String metric, String unit) {
        AssetConfig.MetricPolicy policy = metrics.get(metric);
        return policy != null && (policy.unit().equals(unit)
                || policy.alternateUnits().map(units -> units.contains(unit)).orElse(false));
    }

    /** Most specific wins: asset, then site, then the metric default (D-008). */
    public Threshold threshold(String assetId, String siteId, String metric) {
        Threshold base = defaults(metric);
        base = apply(base, overrides.get(siteId + "." + metric));
        return apply(base, overrides.get(assetId + "." + metric));
    }

    private Threshold defaults(String metric) {
        AssetConfig.MetricPolicy policy = require(metric);
        return new Threshold(policy.warning(), policy.critical(), policy.clearBelow());
    }

    private static Threshold apply(Threshold base, AssetConfig.ThresholdOverride override) {
        if (override == null) {
            return base;
        }
        return new Threshold(override.warning().orElse(base.warning()), override.critical().orElse(base.critical()),
                override.clearBelow().orElse(base.clearBelow()));
    }
}
