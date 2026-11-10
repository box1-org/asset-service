package org.box1.asset.consume;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.box1.asset.TestCatalog;
import org.box1.asset.domain.AlertState;
import org.box1.asset.domain.Reading;
import org.box1.asset.ingest.IngestionPipeline;
import org.box1.asset.store.TelemetryStore;
import org.junit.jupiter.api.Test;

/**
 * box1-docs#128: a replayed reading (its natural key already stored) never reaches the threshold engine, so it can
 * neither raise a second alert nor re-count an active one. In-memory store; the containers suite repeats this through
 * Kafka and PostgreSQL (it/SiteStatusTest).
 */
class DuplicateReadingTest {
    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");

    /** The store calls the projector makes, recorded in memory with the real natural-key rule. */
    static final class MemoryStore extends TelemetryStore {
        final Map<String, Double> readings = new HashMap<>();
        final Map<String, StoredState> states = new HashMap<>();
        final Map<String, Instant> siteLastReading = new HashMap<>();
        final List<String> alerts = new ArrayList<>();
        final List<UUID> outbox = new ArrayList<>();
        int alertUpdates;
        int clears;

        MemoryStore() {
            super(null);
        }

        private static String key(Reading r) {
            return r.assetId() + "/" + r.metricName() + "/" + r.measuredAt();
        }

        @Override
        public boolean insertReading(Reading reading, UUID eventId, String traceparent) {
            return readings.putIfAbsent(key(reading), reading.value()) == null;
        }

        @Override
        public Optional<Double> storedValue(Reading reading) {
            return Optional.ofNullable(readings.get(key(reading)));
        }

        @Override
        public void touchLastSeen(String assetId, String siteId) {
        }

        @Override
        public void touchSiteLastReading(String siteId, Instant measuredAt) {
            siteLastReading.merge(siteId, measuredAt, (a, b) -> a.isAfter(b) ? a : b);
        }

        @Override
        public Optional<StoredState> lockState(String assetId, String metric) {
            return Optional.ofNullable(states.get(assetId + "/" + metric));
        }

        @Override
        public void saveState(String assetId, String metric, AlertState state, Instant measuredAt) {
            states.put(assetId + "/" + metric, new StoredState(state, measuredAt));
        }

        @Override
        public void insertAlert(String alertId, Reading reading, String severity, double threshold, UUID sourceEventId, UUID eventId,
                String traceparent) {
            alerts.add(alertId);
        }

        @Override
        public void updateActiveAlert(String assetId, String metric, double value, String escalateTo, Double escalationThreshold) {
            alertUpdates++;
        }

        @Override
        public void clearAlert(String assetId, String metric, double value) {
            clears++;
        }

        @Override
        public void insertOutbox(UUID eventId, String topic, String key, byte[] body) {
            outbox.add(eventId);
        }
    }

    private static InboundReading site(int second, double value) {
        Reading reading = new Reading("asset_replicator_spacedock", "site_spacedock", "temperature", NOW.minusSeconds(60 - second), value, "C");
        return new InboundReading(IngestionPipeline.eventId(reading), "ingest:site", null, reading);
    }

    @Test
    void aReplayedBacklogRaisesNoSecondAlertAndRecountsNothing() {
        MemoryStore store = new MemoryStore();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ReadingProjector projector = new ReadingProjector(store, TestCatalog.catalog(), registry, Clock.fixed(NOW, ZoneOffset.UTC),
                TestCatalog.config(false, List.of(), null));
        // Three readings at or above warning (80 C) activate one alert; two more repeat it.
        List<InboundReading> backlog = List.of(site(0, 85), site(1, 86), site(2, 87), site(3, 88), site(4, 89));
        List<ReadingProjector.Result> first = backlog.stream().map(r -> projector.project(r, null)).toList();
        assertEquals(List.of(ReadingProjector.Result.STORED, ReadingProjector.Result.STORED, ReadingProjector.Result.ACTIVATED,
                ReadingProjector.Result.STORED, ReadingProjector.Result.STORED), first);
        assertEquals(1, store.alerts.size());
        assertEquals(1, store.outbox.size());
        assertEquals(2, store.alertUpdates, "two repeats counted on the active alert");
        Map<String, TelemetryStore.StoredState> statesBefore = new HashMap<>(store.states);

        // The site's outbox is reset and the whole window is published again, then a clearing value is replayed too.
        for (InboundReading again : backlog) {
            assertEquals(ReadingProjector.Result.DUPLICATE, projector.project(again, null));
        }
        InboundReading conflicting = new InboundReading(backlog.get(0).eventId(), "ingest:site", null,
                new Reading("asset_replicator_spacedock", "site_spacedock", "temperature", backlog.get(0).reading().measuredAt(), 20, "C"));
        assertEquals(ReadingProjector.Result.DUPLICATE, projector.project(conflicting, null), "first stored value wins");

        assertEquals(1, store.alerts.size(), "no second alert");
        assertEquals(1, store.outbox.size(), "no second threshold event");
        assertEquals(2, store.alertUpdates, "readingCount not re-counted");
        assertEquals(0, store.clears);
        assertEquals(statesBefore, store.states, "evaluation state untouched by duplicates");
        assertEquals(5, store.readings.size());
        assertEquals(6.0, registry.find("asset.consume.duplicates").counters().stream().mapToDouble(c -> c.count()).sum());
        assertEquals(NOW.minusSeconds(56), store.siteLastReading.get("site_spacedock"));
        assertEquals(null, registry.find("asset.consume.event.id.mismatch").counter(), "contract eventIds match");
    }

    @Test
    void aProducerThatIgnoresTheEventIdDerivationIsCountedAndStillDeduplicatedOnTheNaturalKey() {
        MemoryStore store = new MemoryStore();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ReadingProjector projector = new ReadingProjector(store, TestCatalog.catalog(), registry, Clock.fixed(NOW, ZoneOffset.UTC),
                TestCatalog.config(false, List.of(), null));
        Reading reading = site(0, 50).reading();
        assertEquals(ReadingProjector.Result.STORED, projector.project(new InboundReading(UUID.randomUUID(), "x", null, reading), null));
        assertEquals(ReadingProjector.Result.DUPLICATE, projector.project(new InboundReading(UUID.randomUUID(), "x", null, reading), null));
        assertEquals(2.0, registry.find("asset.consume.event.id.mismatch").counter().count());
    }

    @Test
    void aSiteClockAheadCannotMoveTheLastReadingPastNow() {
        MemoryStore store = new MemoryStore();
        ReadingProjector projector = new ReadingProjector(store, TestCatalog.catalog(), new SimpleMeterRegistry(),
                Clock.fixed(NOW, ZoneOffset.UTC), TestCatalog.config(false, List.of(), null));
        Reading ahead = new Reading("asset_litterbox_transporter_ds9", "site_ds9", "vibration", NOW.plusSeconds(240), 3, "mm/s");
        projector.project(new InboundReading(IngestionPipeline.eventId(ahead), "ingest:site", null, ahead), null);
        assertEquals(NOW, store.siteLastReading.get("site_ds9"));
    }
}
