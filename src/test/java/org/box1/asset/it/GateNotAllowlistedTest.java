package org.box1.asset.it;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** TEL-GATE-001 (D-015): chaos needs the flag and an allowlisted namespace; load is not gated. */
@QuarkusTest
@TestProfile(FullStack.NotAllowlisted.class)
@Tag(ContainersTag.NAME)
@TestSecurity(user = "operator-1", roles = "chaos-operator")
class GateNotAllowlistedTest {
    @Test
    void chaosIsRefusedButLoadIsArmed() {
        given().contentType(ContentType.JSON).header("X-Box1-Console", "1").body("{\"kind\":\"CHAOS\"}").post("/api/v1/control/runs")
                .then().statusCode(403).body("code", equalTo("NAMESPACE_NOT_ALLOWLISTED"));
        given().get("/api/v1/control/state").then().statusCode(200).body("chaos.open", equalTo(false))
                .body("namespace", equalTo("asset-service-prod"));
        given().contentType(ContentType.JSON).header("X-Box1-Console", "1").body("{\"kind\":\"LOAD\"}").post("/api/v1/control/runs")
                .then().statusCode(201);
        given().contentType(ContentType.JSON).header("X-Box1-Console", "1").body("{}").post("/api/v1/control/stop-all").then()
                .statusCode(200);
    }
}
