package org.box1.asset.it;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.box1.asset.control.ChaosEntry;
import org.box1.asset.control.RunManager;
import org.box1.asset.ingest.ReadingPublisher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** TEL-CHAOS-002 to -007, TEL-LOAD-001/002, TEL-GATE-001: every limit is enforced on the server, the page is bypassed here. */
@QuarkusTest
@TestProfile(FullStack.class)
@Tag(ContainersTag.NAME)
@TestSecurity(user = "operator-1", roles = "chaos-operator")
class ControlTest {
    @Inject
    DataSource dataSource;
    @Inject
    RunManager manager;
    @Inject
    ReadingPublisher publisher;

    static RequestSpecification console() {
        return given().contentType(ContentType.JSON).header("X-Box1-Console", "1");
    }

    static JsonPath arm(String body) {
        return console().body(body).post("/api/v1/control/runs").then().statusCode(201).extract().jsonPath();
    }

    static JsonPath run(String runId) {
        return given().get("/api/v1/control/runs/" + runId).then().statusCode(200).extract().jsonPath();
    }

    static void await(String what, Duration timeout, BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < end) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("Timed out waiting for " + what);
    }

    @AfterEach
    void stopEverything() {
        console().body("{}").post("/api/v1/control/stop-all").then().statusCode(200);
    }

    @Test
    void capsHoldAgainstRequestsThatBypassThePage() {
        console().body("{\"kind\":\"LOAD\",\"sites\":6}").post("/api/v1/control/runs").then().statusCode(422)
                .body("code", equalTo("LIMIT_EXCEEDED"));
        console().body("{\"kind\":\"LOAD\",\"sites\":5,\"assetsPerSite\":11,\"metrics\":[\"power\"],\"ratePerAsset\":1}")
                .post("/api/v1/control/runs").then().statusCode(422).body("code", equalTo("RATE_CAP"));
        console().body("{\"kind\":\"LOAD\",\"durationSeconds\":901}").post("/api/v1/control/runs").then().statusCode(422)
                .body("code", equalTo("LIMIT_EXCEEDED"));
        console().body("{\"kind\":\"LOAD\",\"ratePerAsset\":6}").post("/api/v1/control/runs").then().statusCode(422);
        console().body("{\"kind\":\"LOAD\",\"seed\":\"NOT-HEX\"}").post("/api/v1/control/runs").then().statusCode(422)
                .body("code", equalTo("INVALID_SEED"));
        console().body("{\"kind\":\"LOAD\",\"metrics\":[\"humidity\"]}").post("/api/v1/control/runs").then().statusCode(422);
        console().body("{\"kind\":\"LOAD\",\"unexpected\":1}").post("/api/v1/control/runs").then().statusCode(400);
        List<String> actions = given().get("/api/v1/control/audit?limit=20").then().extract().jsonPath().getList("action");
        assertTrue(actions.stream().filter("DENIED"::equals).count() >= 6, "refusals are audited: " + actions);
    }

    @Test
    void stateChangesNeedTheConsoleHeader() {
        given().contentType(ContentType.JSON).body("{\"kind\":\"LOAD\"}").post("/api/v1/control/runs").then().statusCode(403)
                .body("code", equalTo("CONSOLE_HEADER_REQUIRED"));
        int formStatus = given().contentType(ContentType.URLENC).header("X-Box1-Console", "1").formParam("kind", "LOAD")
                .post("/api/v1/control/runs").then().extract().statusCode();
        assertTrue(formStatus == 403 || formStatus == 415, "a form post is refused, by the filter or by content negotiation: " + formStatus);
    }

    @Test
    void armThenConfirmWithTokenAndTypedNamespaceThenConcurrencyCapThenStopAll() throws Exception {
        JsonPath armed = arm("{\"kind\":\"LOAD\",\"sites\":1,\"assetsPerSite\":2,\"metrics\":[\"temperature\"],\"ratePerAsset\":2,"
                + "\"durationSeconds\":60}");
        String runId = armed.getString("runId");
        assertEquals(FullStack.NAMESPACE, armed.getString("namespace"));
        console().body("{\"confirmToken\":\"0000\",\"confirmNamespace\":\"run15-local\"}").post("/api/v1/control/runs/" + runId + "/start")
                .then().statusCode(403).body("code", equalTo("CONFIRM_MISMATCH"));
        console().body(Map.of("confirmToken", armed.getString("confirmToken"), "confirmNamespace", "asset-service-dev"))
                .post("/api/v1/control/runs/" + runId + "/start").then().statusCode(422).body("code", equalTo("NAMESPACE_MISMATCH"));
        console().body(Map.of("confirmToken", armed.getString("confirmToken"), "confirmNamespace", FullStack.NAMESPACE))
                .post("/api/v1/control/runs/" + runId + "/start").then().statusCode(200).body("state", equalTo("RUNNING"));
        JsonPath second = arm("{\"kind\":\"LOAD\",\"sites\":1,\"assetsPerSite\":1,\"metrics\":[\"power\"],\"durationSeconds\":60}");
        console().body(Map.of("confirmToken", second.getString("confirmToken"), "confirmNamespace", FullStack.NAMESPACE))
                .post("/api/v1/control/runs/" + second.getString("runId") + "/start").then().statusCode(409)
                .body("code", equalTo("CONCURRENCY_CAP"));
        await("emissions", Duration.ofSeconds(10), () -> run(runId).getLong("emitted") > 4);
        JsonPath stopped = console().body("{}").post("/api/v1/control/stop-all").then().statusCode(200).extract().jsonPath();
        assertEquals(Set.of(runId, second.getString("runId")), Set.copyOf(stopped.getList("stopped")));
        long emitted = run(runId).getLong("emitted");
        Thread.sleep(2000);
        JsonPath after = run(runId);
        assertEquals("STOPPED", after.getString("state"));
        assertEquals(emitted, after.getLong("emitted"), "nothing is sent after stop-all");
        assertTrue(manager.active().isEmpty());
        List<String> actions = after.getList("audit.action");
        assertTrue(actions.containsAll(List.of("ARM", "DENIED", "START", "STOP_ALL")), actions.toString());
        assertEquals("operator-1", after.getString("actor"), "actor comes from the identity");
    }

    @Test
    void openArmsAreCapped() {
        for (int i = 0; i < 4; i++) {
            arm("{\"kind\":\"LOAD\",\"durationSeconds\":30}");
        }
        console().body("{\"kind\":\"LOAD\"}").post("/api/v1/control/runs").then().statusCode(409).body("code", equalTo("ARM_CAP"));
    }

    @Test
    void anArmThatIsNotConfirmedInTimeCannotStart() throws Exception {
        JsonPath armed = arm("{\"kind\":\"LOAD\",\"durationSeconds\":30}");
        Thread.sleep(3500);
        int status = console().body(Map.of("confirmToken", armed.getString("confirmToken"), "confirmNamespace", FullStack.NAMESPACE))
                .post("/api/v1/control/runs/" + armed.getString("runId") + "/start").then().extract().statusCode();
        assertTrue(status == 410 || status == 409, "refused as ARM_EXPIRED, or NOT_ARMED once the reaper closed it: " + status);
        await("reaper closes the arm", Duration.ofSeconds(5), () -> run(armed.getString("runId")).getList("audit.action").contains("ARM_EXPIRED"));
        assertEquals("STOPPED", run(armed.getString("runId")).getString("state"));
    }

    @Test
    void sameSeedReplaysTheSameSequenceAndRunsEndWithoutABrowser() throws Exception {
        String body = "{\"kind\":\"LOAD\",\"sites\":2,\"assetsPerSite\":2,\"metrics\":[\"vibration\"],\"ratePerAsset\":5,"
                + "\"durationSeconds\":12,\"seed\":\"5eed00000000002a\"}";
        String first = completedDigest(body);
        String second = completedDigest(body);
        assertEquals(first, second, "replay with the same seed");
        assertTrue(first.startsWith("200:"), first);
        JsonPath preview = console().body(body).post("/api/v1/control/preview?limit=5").then().statusCode(200).extract().jsonPath();
        assertEquals(first, preview.getString("digest"), "preview computes the same digest without publishing");
        String other = completedDigest(body.replace("5eed00000000002a", "5eed00000000002b"));
        assertTrue(!other.equals(first), "a different seed differs");
    }

    private String completedDigest(String body) throws Exception {
        JsonPath armed = arm(body);
        String runId = armed.getString("runId");
        console().body(Map.of("confirmToken", armed.getString("confirmToken"), "confirmNamespace", FullStack.NAMESPACE))
                .post("/api/v1/control/runs/" + runId + "/start").then().statusCode(200);
        await("completion", Duration.ofSeconds(25), () -> "COMPLETED".equals(run(runId).getString("state")));
        JsonPath done = run(runId);
        assertEquals(armed.getString("expectedDigest"), done.getString("sequenceDigest"), "executed sequence matches the arm's prediction");
        assertTrue(done.getList("audit.action").contains("COMPLETE"));
        return done.getString("sequenceDigest");
    }

    @Test
    void reaperAbortsRunsWhoseOwnerStoppedAndExpiresRunsPastTtl() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO control_run (run_id, kind, actor, seed, config, state, namespace, created_at, armed_until, confirm_hash,"
                    + " started_at, deadline, ttl_at, owner, heartbeat_at) VALUES ('run_dead0000000000000001', 'CHAOS', 'operator-1',"
                    + " '0000000000000001', '{}', 'RUNNING', 'run15-local', now(), now(), 'x', now() - interval '2 minutes',"
                    + " now() + interval '5 minutes', now() + interval '6 minutes', 'pod-dead', now() - interval '60 seconds')");
            s.executeUpdate("INSERT INTO control_run (run_id, kind, actor, seed, config, state, namespace, created_at, armed_until, confirm_hash,"
                    + " started_at, deadline, ttl_at, owner, heartbeat_at) VALUES ('run_eeee0000000000000002', 'LOAD', 'operator-1',"
                    + " '0000000000000002', '{}', 'RUNNING', 'run15-local', now(), now(), 'x', now() - interval '2 minutes',"
                    + " now() - interval '5 seconds', now() - interval '1 second', '" + manager.owner() + "', now())");
        }
        await("reaper", Duration.ofSeconds(10), () -> "ABORTED".equals(run("run_dead0000000000000001").getString("state"))
                && "EXPIRED".equals(run("run_eeee0000000000000002").getString("state")));
        assertTrue(run("run_dead0000000000000001").getList("audit.action").contains("ABORTED_RESTART"));
        assertTrue(run("run_eeee0000000000000002").getList("audit.action").contains("TTL_EXPIRED"));
    }

    @Test
    void chaosRunsInjectOnlyCatalogEntriesAndStopAllResumesTheProducer() throws Exception {
        JsonPath armed = arm("{\"kind\":\"CHAOS\",\"sites\":1,\"assetsPerSite\":2,\"metrics\":[\"temperature\"],\"ratePerAsset\":5,"
                + "\"durationSeconds\":120,\"seed\":\"00000000000000c4\"}");
        String runId = armed.getString("runId");
        console().body(Map.of("confirmToken", armed.getString("confirmToken"), "confirmNamespace", FullStack.NAMESPACE))
                .post("/api/v1/control/runs/" + runId + "/start").then().statusCode(200);
        await("two injections", Duration.ofSeconds(75), () -> run(runId).getList("audit.action").stream().filter("INJECT"::equals).count() >= 2);
        Set<String> catalog = Arrays.stream(ChaosEntry.values()).map(Enum::name).collect(Collectors.toSet());
        for (Map<String, Object> entry : run(runId).<Map<String, Object>>getList("audit")) {
            if ("INJECT".equals(entry.get("action"))) {
                String detail = (String) entry.get("detail");
                assertTrue(catalog.stream().anyMatch(name -> detail.contains("\"entry\":\"" + name + "\"")), detail);
            }
        }
        console().body("{}").post("/api/v1/control/reset").then().statusCode(200);
        assertTrue(!publisher.isPaused(), "reset resumes the producer");
        assertEquals("STOPPED", run(runId).getString("state"));
    }
}
