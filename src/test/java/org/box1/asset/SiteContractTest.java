package org.box1.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.ValidationMessage;
import org.box1.asset.api.InvalidRequestException;
import org.box1.asset.api.SiteReports;
import org.box1.asset.api.SiteStatusView;
import org.box1.asset.site.SiteReport;
import org.box1.asset.site.SiteStates;
import org.box1.asset.site.StoredSite;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * TEL-CONTRACT-001 for the site status API (box1-docs#130): the service accepts exactly the SiteStatusReport bodies the
 * pinned contract's schema accepts, and its SiteStatus responses validate against the contract.
 */
class SiteContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Serializes as Quarkus REST does: java.time as ISO-8601 strings. */
    private static final ObjectMapper QUARKUS_JSON = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static JsonNode openApi;
    private static JsonSchema reportSchema;
    private static JsonSchema statusSchema;

    @BeforeAll
    static void contract() throws Exception {
        openApi = ContractSchemas.openApi();
        reportSchema = ContractSchemas.component(openApi, "SiteStatusReport");
        statusSchema = ContractSchemas.component(openApi, "SiteStatus");
    }

    private static ObjectNode example() {
        return (ObjectNode) openApi.path("components").path("examples").path("SiteStatusReport").path("value").deepCopy();
    }

    private static boolean serviceAccepts(JsonNode body) throws Exception {
        try {
            SiteReports.parse(JSON.writeValueAsBytes(body));
            return true;
        } catch (InvalidRequestException rejected) {
            return false;
        }
    }

    private static void agree(String name, JsonNode body, boolean valid) throws Exception {
        Set<ValidationMessage> errors = reportSchema.validate(body);
        assertEquals(valid, errors.isEmpty(), name + ": contract schema says " + errors);
        assertEquals(valid, serviceAccepts(body), name + ": the service must agree with the contract");
    }

    @Test
    void theServiceAcceptsExactlyWhatTheContractSchemaAccepts() throws Exception {
        agree("contract example", example(), true);
        ObjectNode b;
        b = example(); b.remove("appVersions"); agree("missing appVersions", b, false);
        b = example(); b.remove("reportedAt"); agree("missing reportedAt", b, false);
        b = example(); b.remove("bootedImageDigest"); b.remove("bundleDigest"); agree("optional digests absent", b, true);
        b = example(); b.put("extra", 1); agree("unknown field", b, false);
        b = example(); b.put("bufferDepth", -1); agree("negative integer", b, false);
        b = example(); b.put("bufferDepth", 3.5); agree("fraction", b, false);
        b = example(); b.put("bufferDepth", 3.0); agree("integral float", b, true);
        b = example(); b.put("bufferDepth", "3"); agree("string integer", b, false);
        b = example(); b.putNull("readingsDropped"); agree("null integer", b, false);
        b = example(); b.put("bootedImageDigest", "sha256:" + "A".repeat(64)); agree("upper-case digest", b, false);
        b = example(); b.put("bootedImageDigest", "sha256:" + "a".repeat(63)); agree("short digest", b, false);
        b = example(); b.put("bundleDigest", "sha512:" + "a".repeat(64)); agree("other algorithm", b, false);
        b = example(); b.put("greenbootStatus", "AMBER"); agree("greenboot not in enum", b, false);
        b = example(); b.putNull("greenbootStatus"); agree("greenboot null", b, false);
        b = example(); b.put("greenbootStatus", "RED"); agree("greenboot RED", b, true);
        b = example(); b.put("reportedAt", "yesterday"); agree("not a date-time", b, false);
        b = example(); b.put("reportedAt", "2026-11-10T13:00:00+01:00"); agree("offset date-time", b, true);
        b = example(); b.put("appVersions", versions(8, "1.0.0")); agree("8 app versions", b, true);
        b = example(); b.put("appVersions", versions(9, "1.0.0")); agree("9 app versions", b, false);
        b = example(); b.set("appVersions", JSON.createObjectNode()); agree("no app versions", b, true);
        b = example(); b.set("appVersions", JSON.createObjectNode().put("Gateway", "1")); agree("upper-case app name", b, false);
        b = example(); b.set("appVersions", JSON.createObjectNode().put("a".repeat(33), "1")); agree("app name too long", b, false);
        b = example(); b.set("appVersions", JSON.createObjectNode().put("gateway", "")); agree("empty version", b, false);
        b = example(); b.set("appVersions", JSON.createObjectNode().put("gateway", "v".repeat(64))); agree("64-character version", b, true);
        b = example(); b.set("appVersions", JSON.createObjectNode().put("gateway", "v".repeat(65))); agree("65-character version", b, false);
        b = example(); b.set("appVersions", JSON.createObjectNode().put("gateway", 1)); agree("numeric version", b, false);
        b = example(); b.set("appVersions", JSON.createArrayNode()); agree("appVersions array", b, false);
        agree("array body", JSON.createArrayNode().add(example()), false);
    }

    private static ObjectNode versions(int count, String version) {
        ObjectNode node = JSON.createObjectNode();
        for (int i = 0; i < count; i++) {
            node.put("app-" + i, version);
        }
        return node;
    }

    @Test
    void strictJsonBeyondTheSchema() {
        String body = example().toString();
        String duplicated = body.replace("{\"reportedAt\"", "{\"bufferDepth\":1,\"reportedAt\"");
        InvalidRequestException duplicate = assertThrows(InvalidRequestException.class,
                () -> SiteReports.parse(duplicated.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals("MALFORMED", duplicate.code());
        InvalidRequestException trailing = assertThrows(InvalidRequestException.class,
                () -> SiteReports.parse((body + "{}").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals("MALFORMED", trailing.code());
        assertEquals("MALFORMED", assertThrows(InvalidRequestException.class, () -> SiteReports.parse(new byte[0])).code());
    }

    @Test
    void theContractExampleParsesToItsValues() throws Exception {
        SiteReport report = SiteReports.parse(JSON.writeValueAsBytes(example()));
        assertEquals(Instant.parse("2026-11-10T12:00:00Z"), report.reportedAt());
        assertEquals(3, report.bufferDepth());
        assertEquals(1, report.siteEventBacklog());
        assertEquals(4, report.bufferOldestAgeSeconds());
        assertEquals(0, report.readingsDropped());
        assertEquals("GREEN", report.greenbootStatus());
        assertEquals(null, report.stagedImageDigest());
        assertEquals(List.of("gateway", "ops", "simulator"), List.copyOf(report.appVersions().keySet()));
    }

    @Test
    void siteStatusResponsesValidateAgainstTheContract() throws Exception {
        SiteReport report = SiteReports.parse(JSON.writeValueAsBytes(example()));
        Instant received = Instant.parse("2026-11-10T12:00:00.120Z");
        SiteStates.Thresholds thresholds = SiteStates.Thresholds.of(TestCatalog.config(false, List.of(), null));
        Map<String, StoredSite> cases = new LinkedHashMap<>();
        cases.put("ONLINE", new StoredSite("site_spacedock", report, received, "site-east", Instant.parse("2026-11-10T12:00:01Z")));
        cases.put("DEGRADED", new StoredSite("site_ds9", new SiteReport(received, 250, 0, 400, 7, null, null, null, "RED", null, Map.of()),
                received, "site-west", null));
        cases.put("OFFLINE", new StoredSite("site_ds9", report, received.minus(Duration.ofMinutes(5)), "site-west", null));
        for (Map.Entry<String, StoredSite> c : cases.entrySet()) {
            JsonNode body = JSON.readTree(QUARKUS_JSON.writeValueAsBytes(SiteStatusView.of(c.getValue(), thresholds, received.plusSeconds(6))));
            Set<ValidationMessage> errors = statusSchema.validate(body);
            assertTrue(errors.isEmpty(), c.getKey() + " response is invalid: " + errors + " " + body);
            assertEquals(c.getKey(), body.path("state").asText());
            assertTrue(body.has("lastReadingAt"), "lastReadingAt is always present, null when unknown");
        }
        JsonNode online = JSON.readTree(QUARKUS_JSON.writeValueAsBytes(SiteStatusView.of(cases.get("ONLINE"), thresholds,
                received.plusSeconds(6))));
        assertEquals("2026-11-10T12:00:00.120Z", online.path("lastSeenAt").asText());
        assertEquals(6, online.path("lastSeenAgeSeconds").asInt());
        assertEquals(0, online.path("stateReasons").size());
        JsonNode list = JSON.readTree(QUARKUS_JSON.writeValueAsBytes(new SiteStatusView.Page(List.of())));
        assertTrue(list.path("items").isArray(), "list response has items");
    }
}
