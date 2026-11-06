package org.box1.asset.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.time.Instant;

import org.box1.asset.domain.Reading;
import org.junit.jupiter.api.Test;

/** Review MSG-4: a retried or duplicated reading keeps its eventId. */
class EventIdTest {
    @Test
    void sameNaturalKeyGivesTheSameEventId() {
        Instant at = Instant.parse("2026-11-05T12:00:00Z");
        Reading a = new Reading("asset_a", "site_a", "power", at, 1.0, "kW");
        Reading retry = new Reading("asset_a", "site_a", "power", at, 1.0, "kW");
        assertEquals(IngestionPipeline.eventId(a), IngestionPipeline.eventId(retry));
        assertNotEquals(IngestionPipeline.eventId(a), IngestionPipeline.eventId(new Reading("asset_a", "site_a", "power", at.plusMillis(1), 1.0, "kW")));
    }
}
