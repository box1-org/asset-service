package org.box1.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.box1.asset.config.AssetConfig;
import org.box1.asset.control.Emission;
import org.box1.asset.control.RunConfig;
import org.box1.asset.control.RunKind;
import org.box1.asset.control.RunScript;
import org.box1.asset.domain.ReadingValidator;
import org.box1.asset.domain.RejectReason;
import org.box1.asset.domain.RejectedReadingException;
import org.box1.asset.ingest.IngestionPipeline;
import org.box1.asset.ingest.ReadingPublisher;
import org.box1.asset.ingest.Source;
import org.junit.jupiter.api.Test;

/** box1-docs#128: the synthetic generator never produces data for the real edge sites. */
class ReservedSitesTest {
    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");

    @Test
    void theDefaultReservedSitesAreTheEdgeSites() {
        assertEquals(List.of("site_ds9", "site_spacedock"),
                TestCatalog.config(false, List.of(), null).control().reservedSites().orElseThrow());
    }

    @Test
    void everySiteARunCanEmitIsCheckedAgainstTheReservedList() {
        RunConfig five = new RunConfig(RunKind.CHAOS, 5, 1, List.of("power"), 1, 10);
        assertEquals(List.of("site_load_01", "site_load_02", "site_load_03", "site_load_04", "site_load_05"), five.siteIds());
        assertEquals(List.of(), five.reservedSiteIds(Set.of("site_ds9", "site_spacedock")));
        assertEquals(List.of("site_load_03"), five.reservedSiteIds(Set.of("site_load_03")));
        assertEquals(List.of(), new RunConfig(RunKind.LOAD, 2, 1, List.of("power"), 1, 10).reservedSiteIds(Set.of("site_load_03")));
    }

    @Test
    void aLongChaosRunNeverEmitsAReservedSite() {
        RunConfig config = new RunConfig(RunKind.CHAOS, 5, 2, List.of("temperature", "vibration"), 2, 600);
        RunScript script = new RunScript(42L, config, NOW, TestCatalog.catalog(), 4096, 5, Duration.ofSeconds(30));
        Set<Object> sites = new HashSet<>();
        while (script.hasNext()) {
            Emission emission = script.next();
            if (emission.fields() != null && emission.fields().get("siteId") != null) {
                sites.add(emission.fields().get("siteId"));
            }
        }
        assertEquals(Set.copyOf(config.siteIds()), sites, "only the run's own synthetic sites");
    }

    @Test
    void thePipelineRefusesLoadAndChaosReadingsForReservedSitesButNotApiReadings() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        AssetConfig config = TestCatalog.config(false, List.of(), null);
        MockProducer<String, byte[]> producer = new MockProducer<>(true, null, new StringSerializer(), new ByteArraySerializer());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        IngestionPipeline pipeline = new IngestionPipeline(new ReadingValidator(TestCatalog.catalog(), clock, config),
                new ReadingPublisher(producer, config, clock, registry), config, registry, clock);
        for (String site : List.of("site_ds9", "site_spacedock")) {
            Map<String, Object> reading = Map.of("assetId", "asset_load_01_01", "siteId", site, "metricName", "power", "measuredAt",
                    NOW.minusSeconds(1).toString(), "value", 250.0, "unit", "kW");
            for (Source source : List.of(Source.LOAD, Source.CHAOS)) {
                RejectedReadingException refused = assertThrows(RejectedReadingException.class, () -> pipeline.submit(reading, source, null));
                assertEquals(RejectReason.RESERVED_SITE, refused.reason());
            }
            // Real site readings through the API (a telemetry-writer) are not affected.
            assertTrue(pipeline.submit(reading, Source.API, null).eventId() != null);
        }
        assertEquals(4.0, registry.find("asset.ingest.rejected").tag("reason", "reserved_site").counters().stream()
                .mapToDouble(c -> c.count()).sum());
    }
}
