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
@TestProfile(FullStack.Disabled.class)
@Tag(ContainersTag.NAME)
@TestSecurity(user = "operator-1", roles = "chaos-operator")
class GateDisabledTest {
    @Test
    void chaosIsRefusedWhenTheFlagIsOff() {
        given().contentType(ContentType.JSON).header("X-Box1-Console", "1").body("{\"kind\":\"CHAOS\"}").post("/api/v1/control/runs")
                .then().statusCode(403).body("code", equalTo("CHAOS_DISABLED"));
    }
}
