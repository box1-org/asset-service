package org.box1.asset.ops;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import org.box1.asset.config.AssetConfig;
import org.box1.asset.site.SiteStates;
import org.box1.asset.site.StoredSite;
import org.box1.asset.store.SiteStore;
import org.box1.asset.store.StoreException;

/**
 * Per-site gauges for the stale-site alert (box1-docs#128, #130), one series per site in {@code site_status}, so label
 * cardinality is bounded by the sites that can authenticate as a site client.
 * <ul>
 * <li>{@code asset_site_heartbeat_age_seconds{siteId}}: seconds since asset-service received the site's latest status.</li>
 * <li>{@code asset_site_last_reading_age_seconds{siteId}}: seconds since the measuredAt of the site's newest stored
 * reading; +Inf when the site has never had a reading stored.</li>
 * <li>{@code asset_site_buffer_depth{siteId}}: the buffer depth in the site's latest status.</li>
 * <li>{@code asset_site_state{siteId,state}}: 1 for the site's current state (ONLINE, DEGRADED, OFFLINE), else 0.</li>
 * </ul>
 * Like {@link OperationalGauges}, the database is read on the gauge schedule ({@code box1.gauges.interval}, 10 s), but
 * ages and state are computed from the service clock when Prometheus scrapes, so they keep growing while a site is
 * silent. Every value is NaN when the database could not be read, so a database outage shows "unknown" rather than
 * a stale or invented value.
 */
@ApplicationScoped
public class SiteGauges {
    static final String SITE_TAG = "siteId";

    private final SiteStore store;
    private final MeterRegistry registry;
    private final Clock clock;
    private final SiteStates.Thresholds thresholds;
    /** Null until the first successful read, and after a failed one: unknown. */
    private final AtomicReference<Map<String, StoredSite>> snapshot = new AtomicReference<>();
    private final Set<String> registered = ConcurrentHashMap.newKeySet();

    public SiteGauges(SiteStore store, MeterRegistry registry, Clock clock, AssetConfig config) {
        this.store = store;
        this.registry = registry;
        this.clock = clock;
        this.thresholds = SiteStates.Thresholds.of(config);
    }

    @Scheduled(every = "${box1.gauges.interval:10s}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void refresh() {
        List<StoredSite> sites;
        try {
            sites = store.list();
        } catch (StoreException error) {
            snapshot.set(null);
            return;
        }
        snapshot.set(sites.stream().collect(Collectors.toUnmodifiableMap(StoredSite::siteId, s -> s)));
        sites.forEach(site -> register(site.siteId()));
    }

    private void register(String siteId) {
        if (!registered.add(siteId)) {
            return;
        }
        gauge("asset.site.heartbeat.age.seconds", siteId, "Seconds since asset-service received the site's latest status",
                site -> seconds(site.receivedAt()));
        gauge("asset.site.last.reading.age.seconds", siteId,
                "Seconds since the measuredAt of the site's newest stored reading; +Inf when it has none",
                site -> site.lastReadingAt() == null ? Double.POSITIVE_INFINITY : seconds(site.lastReadingAt()));
        gauge("asset.site.buffer.depth", siteId, "Buffer depth in the site's latest status", site -> site.report().bufferDepth());
        for (SiteStates.State state : SiteStates.State.values()) {
            Gauge.builder("asset.site.state", this, g -> g.value(siteId, site -> state == g.derive(site).state() ? 1 : 0))
                    .tag(SITE_TAG, siteId).tag("state", state.name()).description("1 for the site's current state, else 0")
                    .strongReference(true).register(registry);
        }
    }

    private void gauge(String name, String siteId, String description, ToDoubleFunction<StoredSite> value) {
        Gauge.builder(name, this, g -> g.value(siteId, value)).tag(SITE_TAG, siteId).description(description).strongReference(true)
                .register(registry);
    }

    private double value(String siteId, ToDoubleFunction<StoredSite> value) {
        Map<String, StoredSite> sites = snapshot.get();
        StoredSite site = sites == null ? null : sites.get(siteId);
        return site == null ? Double.NaN : value.applyAsDouble(site);
    }

    private SiteStates.Derived derive(StoredSite site) {
        return SiteStates.derive(thresholds, site.receivedAt(), site.report(), clock.instant());
    }

    /** Fractional seconds since {@code then}, never negative. */
    private double seconds(Instant then) {
        return Math.max(0, Duration.between(then, clock.instant()).toMillis() / 1000.0);
    }
}
