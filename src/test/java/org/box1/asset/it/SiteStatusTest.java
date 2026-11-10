package org.box1.asset.it;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.ValidationMessage;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import org.box1.asset.ContractSchemas;
import org.box1.asset.Tokens;
import org.box1.asset.domain.Reading;
import org.box1.asset.ingest.Envelopes;
import org.box1.asset.ingest.IngestionPipeline;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * box1-docs#130 and #128 against real PostgreSQL and Kafka: a site writes its own status and readers see it with its
 * derived state; per-site metrics; and a replayed site backlog (direct Kafka records with causationId ingest:site)
 * stores nothing twice and raises no second alert. Each test uses its own site and asset IDs.
 */
@QuarkusTest
@TestProfile(FullStack.ShortSiteOffline.class)
@Tag(ContainersTag.NAME)
class SiteStatusTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static JsonSchema statusSchema;
    private final String reader = Tokens.issuedTo("operations-console", "olivia", "telemetry-reader");
    private final String writer = Tokens.clientRoles("writer", "telemetry-writer");

    @BeforeAll
    static void contract() throws Exception {
        statusSchema = ContractSchemas.component(ContractSchemas.openApi(), "SiteStatus");
    }

    private static String newSite() {
        return "site_it_" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String report(long depth, String greenboot) {
        return "{\"reportedAt\":\"" + Instant.now().truncatedTo(ChronoUnit.SECONDS) + "\",\"bufferDepth\":" + depth
                + ",\"siteEventBacklog\":0,\"bufferOldestAgeSeconds\":1,\"readingsDropped\":0,"
                + "\"bootedImageDigest\":\"sha256:a11d6786b9a86378148ae55194acfd1cfb2d150ad298334fe00798d026abe3fe\","
                + "\"stagedImageDigest\":null,\"rollbackImageDigest\":null,\"greenbootStatus\":\"" + greenboot + "\","
                + "\"bundleDigest\":null,\"appVersions\":{\"gateway\":\"1.0.0-SNAPSHOT\"}}";
    }

    private static JsonNode valid(String body) throws Exception {
        JsonNode node = JSON.readTree(body);
        Set<ValidationMessage> errors = statusSchema.validate(node);
        assertTrue(errors.isEmpty(), "SiteStatus invalid against the contract: " + errors + " " + body);
        return node;
    }

    private static void await(String what, Duration timeout, BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < end) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("Timed out waiting for " + what);
    }

    @Test
    void aSiteWritesItsOwnStatusAndReadersSeeItsDerivedState() throws Exception {
        String site = newSite();
        String other = newSite();
        String own = Tokens.site("site-west", site, "site-reporter");
        JsonNode stored = valid(given().auth().oauth2(own).contentType(ContentType.JSON).body(report(3, "GREEN"))
                .put("/api/v1/sites/" + site + "/status").then().statusCode(200).extract().asString());
        assertEquals(site, stored.path("siteId").asText());
        assertEquals("ONLINE", stored.path("state").asText());
        assertEquals(0, stored.path("stateReasons").size());
        assertTrue(stored.path("lastReadingAt").isNull(), "no readings yet");
        assertEquals("sha256:a11d6786b9a86378148ae55194acfd1cfb2d150ad298334fe00798d026abe3fe", stored.path("bootedImageDigest").asText());

        JsonNode read = valid(given().auth().oauth2(reader).get("/api/v1/sites/" + site).then().statusCode(200).extract().asString());
        assertEquals(stored.path("lastSeenAt").asText(), read.path("lastSeenAt").asText(), "readers see the stored lastSeenAt");

        // Cross-site and anonymous writes change nothing.
        given().auth().oauth2(Tokens.site("site-east", other, "site-reporter")).contentType(ContentType.JSON).body(report(999, "RED"))
                .put("/api/v1/sites/" + site + "/status").then().statusCode(403).body("code", equalTo("SITE_MISMATCH"));
        given().contentType(ContentType.JSON).body(report(999, "RED")).put("/api/v1/sites/" + site + "/status").then().statusCode(401);
        assertEquals(3, given().auth().oauth2(reader).get("/api/v1/sites/" + site).then().extract().jsonPath().getInt("bufferDepth"));

        // Degraded at the buffer-depth threshold (200) and with greenboot RED.
        JsonNode degraded = valid(given().auth().oauth2(own).contentType(ContentType.JSON).body(report(200, "RED"))
                .put("/api/v1/sites/" + site + "/status").then().statusCode(200).extract().asString());
        assertEquals("DEGRADED", degraded.path("state").asText());
        assertEquals("[\"BUFFER_DEPTH\",\"GREENBOOT_RED\"]", degraded.path("stateReasons").toString());

        // The list holds every reporting site, ordered by siteId, and validates.
        given().auth().oauth2(Tokens.site("site-east", other, "site-reporter")).contentType(ContentType.JSON).body(report(0, "GREEN"))
                .put("/api/v1/sites/" + other + "/status").then().statusCode(200);
        JsonNode list = JSON.readTree(given().auth().oauth2(reader).get("/api/v1/sites").then().statusCode(200).extract().asString());
        String previous = "";
        int found = 0;
        for (JsonNode item : list.path("items")) {
            assertTrue(statusSchema.validate(item).isEmpty(), "list item validates");
            assertTrue(item.path("siteId").asText().compareTo(previous) > 0, "ordered by siteId");
            previous = item.path("siteId").asText();
            found += Set.of(site, other).contains(previous) ? 1 : 0;
        }
        assertEquals(2, found);
        given().auth().oauth2(reader).get("/api/v1/sites/" + newSite()).then().statusCode(404).contentType("application/problem+json")
                .body("code", equalTo("SITE_NOT_FOUND"));

        // A silent site turns OFFLINE after asset.sites.offline-after (PT3S in this profile).
        Thread.sleep(3_500);
        JsonNode offline = valid(given().auth().oauth2(reader).get("/api/v1/sites/" + other).then().statusCode(200).extract().asString());
        assertEquals("OFFLINE", offline.path("state").asText());
        assertEquals("[\"HEARTBEAT_STALE\"]", offline.path("stateReasons").toString());
        assertTrue(offline.path("lastSeenAgeSeconds").asInt() >= 3);
        // The next heartbeat brings it back.
        given().auth().oauth2(Tokens.site("site-east", other, "site-reporter")).contentType(ContentType.JSON).body(report(0, "GREEN"))
                .put("/api/v1/sites/" + other + "/status").then().statusCode(200).body("state", equalTo("ONLINE"));
    }

    @Test
    void perSiteMetricsAreExposedForPrometheus() throws Exception {
        String site = newSite();
        given().auth().oauth2(Tokens.site("site-west", site, "site-reporter")).contentType(ContentType.JSON).body(report(7, "GREEN"))
                .put("/api/v1/sites/" + site + "/status").then().statusCode(200);
        await("site gauges registered", Duration.ofSeconds(15),
                () -> given().get("/q/metrics").then().extract().asString().contains("asset_site_buffer_depth{siteId=\"" + site + "\""));
        String metrics = given().get("/q/metrics").then().statusCode(200).extract().asString();
        for (String series : List.of("asset_site_heartbeat_age_seconds{siteId=\"" + site + "\"",
            "asset_site_last_reading_age_seconds{siteId=\"" + site + "\"", "asset_site_buffer_depth{siteId=\"" + site + "\"",
            "asset_site_state{siteId=\"" + site + "\",state=\"ONLINE\"")) {
            assertTrue(metrics.contains(series), "metrics have " + series);
        }
        assertTrue(metrics.lines().anyMatch(l -> l.startsWith("asset_site_last_reading_age_seconds{siteId=\"" + site + "\"")
                && l.endsWith("+Inf")), "no readings yet: +Inf");
        // Silent past the offline threshold: the age keeps growing and the OFFLINE series is 1.
        Thread.sleep(3_500);
        String later = given().get("/q/metrics").then().extract().asString();
        assertTrue(later.lines().anyMatch(l -> l.startsWith("asset_site_state{siteId=\"" + site + "\",state=\"OFFLINE\"") && l.endsWith(" 1.0")),
                "OFFLINE is 1");
        double age = later.lines().filter(l -> l.startsWith("asset_site_heartbeat_age_seconds{siteId=\"" + site + "\""))
                .mapToDouble(l -> Double.parseDouble(l.substring(l.lastIndexOf(' ') + 1))).findFirst().orElseThrow();
        assertTrue(age >= 3.0, "heartbeat age grows while silent: " + age);
    }

    @Test
    void aReplayedSiteBacklogStoresNothingTwiceAndRaisesNoSecondAlert() throws Exception {
        String site = newSite();
        String asset = "asset_it_" + UUID.randomUUID().toString().substring(0, 8);
        given().auth().oauth2(Tokens.site("site-west", site, "site-reporter")).contentType(ContentType.JSON).body(report(0, "GREEN"))
                .put("/api/v1/sites/" + site + "/status").then().statusCode(200);
        ensureConsuming();
        Instant base = Instant.now().minus(2, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
        // Three readings at or above warning (80 C) activate one alert, then two more repeat it.
        List<String> backlog = List.of(record(asset, site, base, 85), record(asset, site, base.plusSeconds(1), 86),
                record(asset, site, base.plusSeconds(2), 87), record(asset, site, base.plusSeconds(3), 88),
                record(asset, site, base.plusSeconds(4), 89));
        for (String event : backlog) {
            Kafka.produce("box1.telemetry.events.v1", asset, event);
        }
        await("five readings and one active alert", Duration.ofSeconds(30), () -> readings(asset, base).size() == 5 && alerts(asset).size() == 1);
        Map<String, Object> before = alerts(asset).get(0);
        assertEquals(3, ((Number) before.get("readingCount")).intValue(), "activated by the third, repeated by the next two");
        JsonPath siteBefore = given().auth().oauth2(reader).get("/api/v1/sites/" + site).then().statusCode(200).extract().jsonPath();
        assertEquals(base.plusSeconds(4).toString(), siteBefore.getString("lastReadingAt"));

        // Replay the whole window twice (a reset site outbox), followed by a marker reading that proves it was consumed.
        for (int i = 0; i < 2; i++) {
            for (String event : backlog) {
                Kafka.produce("box1.telemetry.events.v1", asset, event);
            }
        }
        Kafka.produce("box1.telemetry.events.v1", asset, record(asset, site, base.plusSeconds(5), 89));
        await("marker consumed", Duration.ofSeconds(30), () -> readings(asset, base).size() == 6);
        List<Map<String, Object>> after = alerts(asset);
        assertEquals(1, after.size(), "no second alert");
        assertEquals(before.get("alertId"), after.get(0).get("alertId"));
        assertEquals(4, ((Number) after.get(0).get("readingCount")).intValue(), "only the new marker reading was counted");
        assertEquals(base.plusSeconds(5).toString(),
                given().auth().oauth2(reader).get("/api/v1/sites/" + site).then().extract().jsonPath().getString("lastReadingAt"));
    }

    private static String record(String asset, String site, Instant at, double value) throws Exception {
        Reading reading = new Reading(asset, site, "temperature", at, value, "C");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("assetId", asset);
        payload.put("siteId", site);
        payload.put("measuredAt", at.toString());
        payload.put("metricName", "temperature");
        payload.put("value", value);
        payload.put("unit", "C");
        UUID eventId = IngestionPipeline.eventId(reading);
        return new String(Envelopes.bytes(JSON, Envelopes.envelope(eventId, "equipment.reading.v1", asset, at, eventId.toString(),
                "ingest:site", null, payload)), java.nio.charset.StandardCharsets.UTF_8);
    }

    private List<Map<String, Object>> readings(String asset, Instant base) {
        return given().auth().oauth2(reader).queryParam("from", base.minusSeconds(1).toString()).queryParam("to", base.plusSeconds(60).toString())
                .get("/api/v1/assets/" + asset + "/readings").then().statusCode(200).extract().jsonPath().getList("items");
    }

    private List<Map<String, Object>> alerts(String asset) {
        return given().auth().oauth2(reader).queryParam("assetId", asset).get("/api/v1/alerts").then().statusCode(200).extract().jsonPath()
                .getList("items");
    }

    /** The consumer starts at "latest" in this profile; wait until a probe reading is consumed (as TelemetryFlowTest). */
    private void ensureConsuming() throws InterruptedException {
        long end = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < end) {
            Instant at = Instant.now().minusSeconds(5).truncatedTo(ChronoUnit.MILLIS);
            given().auth().oauth2(writer).contentType(ContentType.JSON).body("{\"assetId\":\"asset_it_probe\",\"siteId\":\"site_it\","
                    + "\"metricName\":\"power\",\"measuredAt\":\"" + at + "\",\"value\":250,\"unit\":\"kW\"}").post("/api/v1/telemetry/readings")
                    .then().statusCode(202);
            Thread.sleep(1500);
            if (!given().auth().oauth2(reader).queryParam("from", at.toString()).queryParam("to", at.plusMillis(1).toString())
                    .get("/api/v1/assets/asset_it_probe/readings").then().extract().jsonPath().getList("items").isEmpty()) {
                return;
            }
        }
        throw new AssertionError("The consumer never consumed a probe reading");
    }
}
