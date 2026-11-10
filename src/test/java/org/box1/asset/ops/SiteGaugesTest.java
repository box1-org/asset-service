package org.box1.asset.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import org.box1.asset.TestCatalog;
import org.box1.asset.site.SiteReport;
import org.box1.asset.site.StoredSite;
import org.box1.asset.store.SiteStore;
import org.box1.asset.store.StoreException;
import org.junit.jupiter.api.Test;

/** box1-docs#128/#130: per-site gauges, their Prometheus names and labels, and ages that grow while a site is silent. */
class SiteGaugesTest {
    private static final Instant T0 = Instant.parse("2026-11-10T12:00:00Z");

    static final class MutableClock extends Clock {
        Instant now = T0;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    static final class FakeSites extends SiteStore {
        List<StoredSite> sites = List.of();
        boolean failing;

        FakeSites() {
            super(null);
        }

        @Override
        public List<StoredSite> list() {
            if (failing) {
                throw new StoreException("list site status", new java.sql.SQLException("down"));
            }
            return sites;
        }
    }

    private static StoredSite site(String siteId, long depth, Instant received, Instant lastReading) {
        return new StoredSite(siteId, new SiteReport(received, depth, 0, 0, 0, null, null, null, "GREEN", null, Map.of()), received,
                "site-west", lastReading);
    }

    private static double gauge(PrometheusMeterRegistry registry, String name, String siteId, String... tags) {
        var search = registry.find(name).tag("siteId", siteId);
        for (int i = 0; i < tags.length; i += 2) {
            search = search.tag(tags[i], tags[i + 1]);
        }
        Gauge gauge = search.gauge();
        return gauge == null ? Double.NaN : gauge.value();
    }

    @Test
    void perSiteSeriesWithAgesFromTheServiceClock() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        MutableClock clock = new MutableClock();
        FakeSites store = new FakeSites();
        SiteGauges gauges = new SiteGauges(store, registry, clock, TestCatalog.config(false, List.of(), null));
        gauges.refresh();
        assertNull(registry.find("asset.site.heartbeat.age.seconds").gauge(), "no series before a site reports");

        store.sites = List.of(site("site_ds9", 3, T0.minusSeconds(6), T0.minusSeconds(2)), site("site_spacedock", 250, T0, null));
        gauges.refresh();
        assertEquals(6.0, gauge(registry, "asset.site.heartbeat.age.seconds", "site_ds9"));
        assertEquals(2.0, gauge(registry, "asset.site.last.reading.age.seconds", "site_ds9"));
        assertEquals(Double.POSITIVE_INFINITY, gauge(registry, "asset.site.last.reading.age.seconds", "site_spacedock"),
                "never had a reading");
        assertEquals(3.0, gauge(registry, "asset.site.buffer.depth", "site_ds9"));
        assertEquals(1.0, gauge(registry, "asset.site.state", "site_ds9", "state", "ONLINE"));
        assertEquals(0.0, gauge(registry, "asset.site.state", "site_ds9", "state", "OFFLINE"));
        assertEquals(1.0, gauge(registry, "asset.site.state", "site_spacedock", "state", "DEGRADED"));

        // No refresh: a silent site's ages keep growing, and it turns OFFLINE after 90 s.
        clock.now = T0.plusSeconds(130);
        assertEquals(136.0, gauge(registry, "asset.site.heartbeat.age.seconds", "site_ds9"));
        assertEquals(132.0, gauge(registry, "asset.site.last.reading.age.seconds", "site_ds9"));
        assertEquals(1.0, gauge(registry, "asset.site.state", "site_ds9", "state", "OFFLINE"));
        assertEquals(0.0, gauge(registry, "asset.site.state", "site_ds9", "state", "ONLINE"));

        String scrape = registry.scrape();
        for (String series : new String[] {"asset_site_heartbeat_age_seconds{siteId=\"site_ds9\",}",
            "asset_site_last_reading_age_seconds{siteId=\"site_spacedock\",} +Inf", "asset_site_buffer_depth{siteId=\"site_spacedock\",} 250.0",
            "asset_site_state{siteId=\"site_ds9\",state=\"OFFLINE\",} 1.0", "asset_site_state{siteId=\"site_ds9\",state=\"DEGRADED\",} 0.0"}) {
            assertTrue(scrape.contains(series), "scrape has " + series + "\n" + scrape);
        }

        // A database failure makes every series unknown rather than stale.
        store.failing = true;
        gauges.refresh();
        assertTrue(Double.isNaN(gauge(registry, "asset.site.heartbeat.age.seconds", "site_ds9")));
        assertTrue(Double.isNaN(gauge(registry, "asset.site.state", "site_ds9", "state", "OFFLINE")));
        store.failing = false;
        gauges.refresh();
        assertEquals(1, registry.find("asset.site.buffer.depth").tag("siteId", "site_ds9").gauges().size(), "registered once");
    }
}
