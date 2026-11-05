package org.box1.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.box1.asset.config.AssetConfig;
import org.box1.asset.domain.ReadingValidator;
import org.box1.asset.ingest.IngestionPipeline;
import org.box1.asset.ingest.ReadingPublisher;
import org.box1.asset.ingest.Source;
import org.box1.asset.ingest.TestAccess;
import org.junit.jupiter.api.Test;

/** TEL-CONTRACT-001: the bytes the pipeline publishes validate against the pinned box1-ops-contracts commit. */
class ContractTest {
    @Test
    void publishedReadingMatchesTheSharedContract() throws Exception {
        Instant now = Instant.parse("2026-11-05T12:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        AssetConfig config = TestCatalog.config(false, List.of(), null);
        MockProducer<String, byte[]> producer = new MockProducer<>(true, null, new StringSerializer(), new ByteArraySerializer());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ReadingPublisher publisher = new ReadingPublisher(producer, config, clock, registry);
        IngestionPipeline pipeline = new IngestionPipeline(new ReadingValidator(TestCatalog.catalog(), clock, config), publisher, config,
                registry, clock);
        pipeline.submit(Map.of("assetId", "asset_press_7", "siteId", "site_nyc", "metricName", "temperature", "measuredAt",
                "2026-11-05T11:59:00Z", "value", 72.4, "unit", "C"), Source.API, "corr-1");
        TestAccess.drainOnce(publisher);
        assertEquals(1, producer.history().size());
        assertEquals("asset_press_7", producer.history().get(0).key(), "partition key is assetId");
        JsonNode event = new ObjectMapper().readTree(producer.history().get(0).value());
        assertEquals("equipment.reading.v1", event.path("eventType").asText());
        ContractSchemas.assertValid(event);
    }
}
