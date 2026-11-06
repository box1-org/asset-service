package org.box1.asset;

import static io.restassured.RestAssured.given;
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
        given().auth().oauth2(reader).get("/api/v1/alerts?status=ACTIVE&limit=1").then().statusCode(200);
        given().auth().oauth2(reader).get("/api/v1/assets/asset_a/readings").then().statusCode(200);
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
