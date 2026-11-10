package org.box1.asset;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.oidc.server.OidcWiremockTestResource;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

/**
 * TEL-SEC-001, TEL-CON-002, TEL-SMOKE-001 without a database or broker (runs in CI). Positive control-API behavior needs
 * the database and is in the containers suite.
 */
@QuarkusTest
@QuarkusTestResource(value = OidcWiremockTestResource.class, restrictToAnnotatedClass = false)
class SecurityTest {
    private static final String READING = "{\"assetId\":\"asset_a\",\"siteId\":\"site_a\",\"metricName\":\"temperature\","
            + "\"measuredAt\":\"2026-11-05T12:00:00Z\",\"value\":1,\"unit\":\"furlong\"}";

    @Test
    void ingestionIsNeverAnonymous() {
        given().contentType(ContentType.JSON).body(READING).post("/api/v1/telemetry/readings").then().statusCode(401);
        given().get("/api/v1/alerts").then().statusCode(401);
        given().get("/api/v1/assets/asset_a/readings").then().statusCode(401);
    }

    @Test
    void aConsoleSessionCookieNeverAuthenticatesTheBearerOnlyApi() {
        for (String cookie : new String[] {"q_session_console=abc", "q_auth_console_x=abc", "q_session_console_chunk_1=abc"}) {
            given().redirects().follow(false).header("Cookie", cookie).get("/api/v1/alerts").then().statusCode(401);
            given().redirects().follow(false).header("Cookie", cookie).contentType(ContentType.JSON).body(READING)
                    .post("/api/v1/telemetry/readings").then().statusCode(401);
        }
    }

    @Test
    void ingestionNeedsTheWriterRoleFromThisClientsClaim() {
        given().auth().oauth2(Tokens.clientRoles("alice")).contentType(ContentType.JSON).body(READING)
                .post("/api/v1/telemetry/readings").then().statusCode(403);
        given().auth().oauth2(Tokens.clientRoles("alice", "chaos-operator")).contentType(ContentType.JSON).body(READING)
                .post("/api/v1/telemetry/readings").then().statusCode(403);
        given().auth().oauth2(Tokens.topLevelGroups("mallory", "telemetry-writer")).contentType(ContentType.JSON).body(READING)
                .post("/api/v1/telemetry/readings").then().statusCode(403);
        given().auth().oauth2(Tokens.otherAudience("eve", "telemetry-writer")).contentType(ContentType.JSON).body(READING)
                .post("/api/v1/telemetry/readings").then().statusCode(401);
        given().auth().oauth2(Tokens.otherClient("eve", "telemetry-writer")).contentType(ContentType.JSON).body(READING)
                .post("/api/v1/telemetry/readings").then().statusCode(401);
        given().auth().oauth2(Tokens.clientRoles("writer", "telemetry-writer")).contentType(ContentType.JSON).body(READING)
                .post("/api/v1/telemetry/readings").then().statusCode(400).contentType("application/problem+json")
                .body("code", equalTo("INVALID_UNIT"));
    }

    @Test
    void readsNeedATelemetryRoleNotJustARealmLogin() {
        given().auth().oauth2(Tokens.clientRoles("anyone")).get("/api/v1/alerts").then().statusCode(403);
        given().auth().oauth2(Tokens.clientRoles("anyone")).get("/api/v1/assets/asset_a/readings").then().statusCode(403);
    }

    @Test
    void theOperationsConsoleMayReadWithTheReaderRoleAndNothingElse() {
        // box1-docs#80: tokens issued to operations-console keep only telemetry-reader.
        String reader = Tokens.issuedTo("operations-console", "olivia", "telemetry-reader");
        // Authorization is what is under test. The default build has no database, so an authorized read may answer
        // 503 STORE_UNAVAILABLE; the container tests (it/TelemetryFlowTest) cover reads returning 200.
        given().auth().oauth2(reader).get("/api/v1/alerts?status=ACTIVE&limit=1").then()
                .statusCode(not(anyOf(equalTo(401), equalTo(403))));
        given().auth().oauth2(reader).get("/api/v1/assets/asset_a/readings").then()
                .statusCode(not(anyOf(equalTo(401), equalTo(403))));
        given().auth().oauth2(reader).contentType(ContentType.JSON).body(READING).post("/api/v1/telemetry/readings").then()
                .statusCode(403);

        String everything = Tokens.issuedTo("operations-console", "olivia", "telemetry-writer", "chaos-operator");
        given().auth().oauth2(everything).get("/api/v1/alerts").then().statusCode(403);
        given().auth().oauth2(everything).contentType(ContentType.JSON).body(READING).post("/api/v1/telemetry/readings").then()
                .statusCode(403);
        given().redirects().follow(false).auth().oauth2(everything).get("/api/v1/control/state").then().statusCode(not(200));
    }

    @Test
    void anyOtherClientOrNoClientIsRefusedEvenWithTheReaderRole() {
        given().auth().oauth2(Tokens.issuedTo("openshell-cli", "eve", "telemetry-reader")).get("/api/v1/alerts").then().statusCode(401);
        given().auth().oauth2(Tokens.issuedTo("developer-hub", "eve", "telemetry-reader")).get("/api/v1/alerts").then().statusCode(401);
        given().auth().oauth2(Tokens.issuedTo(null, "eve", "telemetry-reader")).get("/api/v1/alerts").then().statusCode(401);
    }

    /** A valid SiteStatusReport (the contract's example). */
    static final String SITE_REPORT = "{\"reportedAt\":\"2026-11-10T12:00:00Z\",\"bufferDepth\":3,\"siteEventBacklog\":1,"
            + "\"bufferOldestAgeSeconds\":4,\"readingsDropped\":0,"
            + "\"bootedImageDigest\":\"sha256:a11d6786b9a86378148ae55194acfd1cfb2d150ad298334fe00798d026abe3fe\","
            + "\"stagedImageDigest\":null,\"rollbackImageDigest\":null,\"greenbootStatus\":\"GREEN\","
            + "\"bundleDigest\":\"sha256:8e9d01383c43e37cbf30b083be311866dc9806158fa09062ede88f9afb41edb6\","
            + "\"appVersions\":{\"gateway\":\"1.0.0-SNAPSHOT\",\"ops\":\"1.0.0-SNAPSHOT\",\"simulator\":\"1.0.0-SNAPSHOT\"}}";

    private static io.restassured.response.ValidatableResponse putStatus(String token, String siteId, String body) {
        var request = given().redirects().follow(false).contentType(ContentType.JSON).body(body);
        if (token != null) {
            request = request.auth().oauth2(token);
        }
        return request.put("/api/v1/sites/" + siteId + "/status").then();
    }

    @Test
    void siteStatusWritesAreNeverAnonymous() {
        putStatus(null, "site_ds9", SITE_REPORT).statusCode(401);
        given().get("/api/v1/sites").then().statusCode(401);
        given().get("/api/v1/sites/site_ds9").then().statusCode(401);
    }

    @Test
    void aSiteMayWriteOnlyItsOwnStatus() {
        String west = Tokens.site("site-west", "site_ds9", "site-reporter");
        // Cross-site: site-west's token writing site-east's site.
        putStatus(west, "site_spacedock", SITE_REPORT).statusCode(403).contentType("application/problem+json")
                .body("code", equalTo("SITE_MISMATCH"));
        // No siteId claim.
        putStatus(Tokens.site("site-west", null, "site-reporter"), "site_ds9", SITE_REPORT).statusCode(403)
                .body("code", equalTo("SITE_MISMATCH"));
        // Wrong role: the site client without site-reporter, and readers and writers of other clients.
        putStatus(Tokens.site("site-west", "site_ds9", "telemetry-writer"), "site_ds9", SITE_REPORT).statusCode(403);
        putStatus(Tokens.site("site-west", "site_ds9"), "site_ds9", SITE_REPORT).statusCode(403);
        putStatus(Tokens.clientRoles("writer", "telemetry-writer", "telemetry-reader", "chaos-operator"), "site_ds9", SITE_REPORT)
                .statusCode(403);
        putStatus(Tokens.issuedTo("operations-console", "olivia", "telemetry-reader", "site-reporter"), "site_ds9", SITE_REPORT)
                .statusCode(403);
        // site-reporter and a matching claim are not enough without a site client's azp.
        putStatus(Tokens.ownClientWithSite("mallory", "site_ds9", "site-reporter"), "site_ds9", SITE_REPORT).statusCode(403)
                .body("code", equalTo("SITE_MISMATCH"));
        // A client that is not configured at all is refused as before.
        putStatus(Tokens.site("site-north", "site_ds9", "site-reporter"), "site_ds9", SITE_REPORT).statusCode(401);
        // Own site: authorized. The default build has no database, so the write answers 503 STORE_UNAVAILABLE here; the
        // containers suite (it/SiteStatusTest) covers the 200.
        putStatus(west, "site_ds9", SITE_REPORT).statusCode(not(anyOf(equalTo(401), equalTo(403))));
        putStatus(Tokens.site("site-east", "site_spacedock", "site-reporter"), "site_spacedock", SITE_REPORT)
                .statusCode(not(anyOf(equalTo(401), equalTo(403))));
    }

    @Test
    void aSiteTokenReadsNothing() {
        // Even a site client granted reader and writer roles keeps only site-reporter.
        String site = Tokens.site("site-west", "site_ds9", "site-reporter", "telemetry-reader", "telemetry-writer", "chaos-operator");
        given().auth().oauth2(site).get("/api/v1/alerts").then().statusCode(403);
        given().auth().oauth2(site).get("/api/v1/assets/asset_a/readings").then().statusCode(403);
        given().auth().oauth2(site).get("/api/v1/sites").then().statusCode(403);
        given().auth().oauth2(site).get("/api/v1/sites/site_ds9").then().statusCode(403);
        given().auth().oauth2(site).contentType(ContentType.JSON).body(READING).post("/api/v1/telemetry/readings").then().statusCode(403);
        given().redirects().follow(false).auth().oauth2(site).get("/api/v1/control/state").then().statusCode(not(200));
    }

    @Test
    void readersMayReadSiteStatus() {
        // Authorization is under test; without a database an authorized read answers 503 (it/SiteStatusTest covers 200).
        for (String reader : new String[] {Tokens.issuedTo("operations-console", "olivia", "telemetry-reader"),
            Tokens.clientRoles("reader", "telemetry-reader"), Tokens.clientRoles("writer", "telemetry-writer"),
            Tokens.clientRoles("op", "chaos-operator")}) {
            given().auth().oauth2(reader).get("/api/v1/sites").then().statusCode(not(anyOf(equalTo(401), equalTo(403))));
            given().auth().oauth2(reader).get("/api/v1/sites/site_ds9").then().statusCode(not(anyOf(equalTo(401), equalTo(403))));
        }
        given().auth().oauth2(Tokens.clientRoles("anyone")).get("/api/v1/sites").then().statusCode(403);
        given().auth().oauth2(Tokens.clientRoles("anyone")).get("/api/v1/sites/site_ds9").then().statusCode(403);
        // An invalid siteId is simply not a known site.
        given().auth().oauth2(Tokens.clientRoles("reader", "telemetry-reader")).get("/api/v1/sites/not-a-site").then().statusCode(404)
                .contentType("application/problem+json").body("code", equalTo("SITE_NOT_FOUND"));
    }

    @Test
    void siteStatusBodiesAreValidatedBeforeStorage() {
        String west = Tokens.site("site-west", "site_ds9", "site-reporter");
        putStatus(west, "site_ds9", "{not json").statusCode(400).contentType("application/problem+json").body("code", equalTo("MALFORMED"));
        putStatus(west, "site_ds9", SITE_REPORT.replace("\"bufferDepth\":3", "\"bufferDepth\":-1")).statusCode(400)
                .body("code", equalTo("INVALID_INTEGER"));
        putStatus(west, "site_ds9", SITE_REPORT.replace("{\"reportedAt\"", "{\"extra\":1,\"reportedAt\"")).statusCode(400)
                .body("code", equalTo("UNKNOWN_FIELD"));
        putStatus(west, "site_ds9", SITE_REPORT.replace("\"GREEN\"", "\"AMBER\"")).statusCode(400)
                .body("code", equalTo("INVALID_GREENBOOT"));
        putStatus(west, "site_ds9", "{\"a\":1}" + " ".repeat(5000)).statusCode(400).body("code", equalTo("OVERSIZE"));
        given().auth().oauth2(west).contentType(ContentType.TEXT).body(SITE_REPORT).put("/api/v1/sites/site_ds9/status").then()
                .statusCode(415);
    }

    @Test
    void consoleResponsesCarryBrowserHardeningHeaders() {
        given().get("/signed-out.html").then().statusCode(200).header("X-Frame-Options", "DENY")
                .header("Content-Security-Policy", containsString("frame-ancestors 'none'")).header("X-Content-Type-Options", "nosniff");
        given().redirects().follow(false).get("/console/").then().header("X-Frame-Options", "DENY");
    }

    @Test
    void oversizeAndMalformedBodiesAreRejectedAsProblems() {
        String writer = Tokens.clientRoles("writer", "telemetry-writer");
        given().auth().oauth2(writer).contentType(ContentType.JSON).body("{\"assetId\":\"" + "x".repeat(5000) + "\"}")
                .post("/api/v1/telemetry/readings").then().statusCode(413).body("code", equalTo("OVERSIZE"));
        given().auth().oauth2(writer).contentType(ContentType.JSON).body("{not json").post("/api/v1/telemetry/readings").then()
                .statusCode(400).body("code", equalTo("MALFORMED"));
        given().auth().oauth2(writer).contentType(ContentType.JSON).body("{\"a\":1,\"a\":2}").post("/api/v1/telemetry/readings").then()
                .statusCode(400).body("code", equalTo("MALFORMED"));
    }

    @Test
    void consoleAndControlNeedABrowserLoginAndNeverAcceptAnonymousOrBearerAlone() {
        given().redirects().follow(false).get("/console/").then().statusCode(302)
                .header("Location", containsString("/realms/quarkus?response_type=code"));
        given().redirects().follow(false).get("/api/v1/control/state").then().statusCode(302);
        given().redirects().follow(false).header("X-Requested-With", "JavaScript").get("/api/v1/control/state").then().statusCode(499);
        given().redirects().follow(false).contentType(ContentType.JSON).header("X-Box1-Console", "1").body("{}")
                .post("/api/v1/control/stop-all").then().statusCode(302);
        given().redirects().follow(false).auth().oauth2(Tokens.clientRoles("op", "chaos-operator")).get("/api/v1/control/state").then()
                .statusCode(not(equalTo(200)));
    }

    @Test
    void theConsolesOwnHttpsOriginIsNotRejectedBehindTheEdgeButOthersAre() {
        // A browser POST from the console carries Origin https://<route> while the pod sees http behind the edge Route.
        int own = given().redirects().follow(false).header("Origin", "https://asset-service-dev.apps.ocp.box1.org")
                .header("X-Requested-With", "JavaScript").header("X-Box1-Console", "1").contentType(ContentType.JSON).body("{}")
                .post("/api/v1/control/stop-all").then().extract().statusCode();
        org.junit.jupiter.api.Assertions.assertEquals(499, own, "reaches authentication, not rejected by CORS");
        given().redirects().follow(false).header("Origin", "https://evil.example").header("X-Box1-Console", "1")
                .contentType(ContentType.JSON).body("{}").post("/api/v1/control/stop-all").then().statusCode(403);
    }

    @Test
    void everythingUnlistedIsDenied() {
        given().redirects().follow(false).get("/").then().statusCode(not(equalTo(200)));
        given().redirects().follow(false).get("/internal").then().statusCode(not(equalTo(200)));
    }

    @Test
    void goldenPathSmokePathsStayPublic() {
        given().get("/api/greetings?name=OpenShift").then().statusCode(200).body("message", equalTo("Hello, OpenShift!"));
        given().queryParam("name", " ").get("/api/greetings").then().statusCode(400);
        given().get("/api/info").then().statusCode(200).body("javaVersion", equalTo(21));
        given().get("/q/health/live").then().statusCode(200);
        given().get("/q/health/ready").then().statusCode(200);
        given().get("/q/health/started").then().statusCode(200);
        given().accept(ContentType.JSON).get("/q/openapi").then().statusCode(200).body("paths", hasKey("/api/greetings"))
                .body("paths", hasKey("/api/info")).body("paths", hasKey("/api/v1/telemetry/readings"));
        given().get("/signed-out.html").then().statusCode(200);
    }
}
