package org.box1.asset.it;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.box1.asset.ContractSchemas;
import org.box1.asset.Tokens;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * TEL-INGEST-002/003, TEL-STORE-001, TEL-READ-001, TEL-ALERT-001/002/003, TEL-DLQ-001, TEL-OBS-002 against real
 * PostgreSQL and Kafka. Each test uses its own asset IDs so tests do not interfere.
 */
@QuarkusTest
@TestProfile(FullStack.class)
@Tag(ContainersTag.NAME)
class TelemetryFlowTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final String writer = Tokens.clientRoles("writer", "telemetry-writer");
    private final String reader = Tokens.clientRoles("reader");

    private static String asset() {
        return "asset_it_" + UUID.randomUUID().toString().substring(0, 8);
    }

    private JsonPath post(String asset, String metric, Instant at, double value, String unit) {
        String body = String.format("{\"assetId\":\"%s\",\"siteId\":\"site_it\",\"metricName\":\"%s\",\"measuredAt\":\"%s\",\"value\":%s,"
                + "\"unit\":\"%s\"}", asset, metric, at, value, unit);
        return given().auth().oauth2(writer).contentType(ContentType.JSON).body(body).post("/api/v1/telemetry/readings").then()
                .statusCode(202).extract().jsonPath();
    }

    private List<Map<String, Object>> readings(String asset, String query) {
        List<Map<String, Object>> all = new ArrayList<>();
        String cursor = null;
        do {
            JsonPath page = given().auth().oauth2(reader).queryParam("limit", 7).queryParam("cursor", cursor == null ? new Object[0] : new Object[] {cursor})
                    .get("/api/v1/assets/" + asset + "/readings" + query).then().statusCode(200).extract().jsonPath();
            all.addAll(page.getList("items"));
            cursor = page.getString("nextCursor");
        } while (cursor != null);
        return all;
    }

    /**
     * The consumer starts at "latest" (D-010), so readings posted before its partitions are assigned are skipped by
     * design. Wait until a probe reading is consumed before a test relies on consumption.
     */
    private void ensureConsuming() throws InterruptedException {
        String probe = "asset_it_probe";
        long end = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < end) {
            Instant at = Instant.now().minusSeconds(5).truncatedTo(ChronoUnit.MILLIS);
            post(probe, "power", at, 250, "kW");
            Thread.sleep(1500);
            if (!readings(probe, "?from=" + at + "&to=" + at.plusMillis(1)).isEmpty()) {
                return;
            }
        }
        throw new AssertionError("The consumer never consumed a probe reading");
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < end) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Timed out waiting for " + what);
    }

    @Test
    void burstWithDuplicatesStoresEachReadingOnceAndPagesStably() throws Exception {
        ensureConsuming();
        String asset = asset();
        Instant base = Instant.now().minus(10, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
        for (int i = 0; i < 40; i++) {
            post(asset, i % 2 == 0 ? "temperature" : "vibration", base.plusSeconds(i), i % 2 == 0 ? 60 : 3, i % 2 == 0 ? "C" : "mm/s");
        }
        for (int i = 0; i < 10; i++) {
            post(asset, i % 2 == 0 ? "temperature" : "vibration", base.plusSeconds(i), i % 2 == 0 ? 60 : 3, i % 2 == 0 ? "C" : "mm/s");
        }
        given().auth().oauth2(writer).contentType(ContentType.JSON).body("{\"assetId\":\"" + asset + "\",\"siteId\":\"site_it\","
                + "\"metricName\":\"temperature\",\"measuredAt\":\"" + base + "\",\"value\":60,\"unit\":\"K\"}")
                .post("/api/v1/telemetry/readings").then().statusCode(400).body("code", equalTo("INVALID_UNIT"));
        await("40 unique readings", () -> readings(asset, "?from=" + base.minusSeconds(1) + "&to=" + base.plusSeconds(60)).size() == 40);
        Thread.sleep(1500);
        List<Map<String, Object>> all = readings(asset, "?from=" + base.minusSeconds(1) + "&to=" + base.plusSeconds(60));
        assertEquals(40, all.size(), "50 accepted posts, 10 duplicates, 1 invalid: 40 stored");
        Set<String> keys = new HashSet<>();
        String previous = "";
        for (Map<String, Object> r : all) {
            String key = r.get("measuredAt") + "|" + r.get("metricName");
            assertTrue(keys.add(key), "no duplicate across pages");
            assertTrue(key.compareTo(previous) > 0, "ascending (measuredAt, metricName)");
            previous = key;
        }
        assertEquals(10, readings(asset, "?from=" + base.plusSeconds(10) + "&to=" + base.plusSeconds(20)).size(), "time window");
        given().auth().oauth2(reader).get("/api/v1/assets/" + asset + "/readings?from=" + base + "&to=" + base.plusSeconds(90_000))
                .then().statusCode(400).body("code", equalTo("INVALID_WINDOW"));
    }

    @Test
    void flappingGivesOneAlertOneEventThenOneClearWithLinkedIds() throws Exception {
        ensureConsuming();
        String asset = asset();
        Instant base = Instant.now().minus(5, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
        List<String> eventIds = new ArrayList<>();
        List<String> traceparents = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            JsonPath accepted = post(asset, "temperature", base.plusSeconds(i), i % 2 == 0 ? 80.75 : 77.5, "C");
            eventIds.add(accepted.getString("eventId"));
            traceparents.add(accepted.getString("traceparent"));
        }
        for (int i = 20; i < 24; i++) {
            post(asset, "temperature", base.plusSeconds(i), 60, "C");
        }
        await("alert cleared", () -> {
            List<Map<String, Object>> alerts = given().auth().oauth2(reader).queryParam("assetId", asset).get("/api/v1/alerts").then()
                    .statusCode(200).extract().jsonPath().getList("items");
            return alerts.size() == 1 && "CLEARED".equals(alerts.get(0).get("status"));
        });
        JsonPath alerts = given().auth().oauth2(reader).queryParam("assetId", asset).get("/api/v1/alerts").then().extract().jsonPath();
        assertEquals(1, alerts.getList("items").size(), "one alert for the whole flapping period");
        String sourceEventId = alerts.getString("items[0].sourceEventId");
        assertEquals(eventIds.get(4), sourceEventId, "the third breaching reading (index 4) activated it");
        String alertId = alerts.getString("items[0].alertId");
        List<ConsumerRecord<String, byte[]>> events = Kafka.read("box1.telemetry.events.v1",
                list -> list.stream().anyMatch(r -> new String(r.value(), StandardCharsets.UTF_8).contains(alertId)), Duration.ofSeconds(20));
        List<JsonNode> thresholdEvents = new ArrayList<>();
        for (ConsumerRecord<String, byte[]> record : events) {
            JsonNode event = JSON.readTree(record.value());
            if ("equipment.threshold-exceeded.v1".equals(event.path("eventType").asText()) && asset.equals(event.path("aggregateId").asText())) {
                thresholdEvents.add(event);
                assertEquals(asset, record.key(), "keyed by assetId");
            }
        }
        assertEquals(1, thresholdEvents.size(), "one event per activation");
        JsonNode event = thresholdEvents.get(0);
        assertEquals(sourceEventId, event.path("payload").path("sourceEventId").asText());
        assertEquals(sourceEventId, event.path("causationId").asText());
        assertEquals("WARNING", event.path("payload").path("severity").asText());
        String readingTrace = traceparents.get(4).split("-")[1];
        assertNotNull(event.path("traceparent").asText());
        assertEquals(readingTrace, event.path("traceparent").asText().split("-")[1], "the alert event continues the reading's trace");
        ContractSchemas.assertValid(event);
    }

    @Test
    void poisonRecordsGoToTheDeadLetterTopicAndConsumptionContinues() throws Exception {
        ensureConsuming();
        String marker = "poison-" + UUID.randomUUID();
        Kafka.produce("box1.telemetry.events.v1", "asset_poison", "{\"eventType\":\"equipment.reading.v1\",\"note\":\"" + marker + "\"}");
        String asset = asset();
        Instant at = Instant.now().minusSeconds(30).truncatedTo(ChronoUnit.SECONDS);
        post(asset, "pressure", at, 5, "bar");
        await("valid reading after poison is stored", () -> readings(asset, "?from=" + at.minusSeconds(1) + "&to=" + at.plusSeconds(1)).size() == 1);
        List<ConsumerRecord<String, byte[]>> dead = Kafka.read("box1.telemetry.dlq.v1",
                list -> list.stream().anyMatch(r -> new String(r.value(), StandardCharsets.UTF_8).contains(marker)), Duration.ofSeconds(20));
        ConsumerRecord<String, byte[]> record = dead.stream().filter(r -> new String(r.value(), StandardCharsets.UTF_8).contains(marker))
                .findFirst().orElseThrow(() -> new AssertionError("poison record not dead-lettered"));
        Header reason = record.headers().lastHeader("dead-letter-reason");
        assertNotNull(reason);
        assertEquals("ENVELOPE_INVALID", new String(reason.value(), StandardCharsets.UTF_8), "reason code only, no driver text");
    }
}
