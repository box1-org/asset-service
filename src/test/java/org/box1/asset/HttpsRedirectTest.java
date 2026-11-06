package org.box1.asset;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

import java.util.Map;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

/** Security review SEC-1: behind the edge-TLS Route the login must use an https redirect URI and Secure cookies. */
@QuarkusTest
@TestProfile(HttpsRedirectTest.Production.class)
class HttpsRedirectTest {
    public static class Production implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.oidc.console.authentication.force-redirect-https-scheme", "true",
                    "quarkus.oidc.console.authentication.cookie-force-secure", "true");
        }
    }

    @Test
    void loginRedirectUsesHttpsAndCookiesAreSecure() {
        given().redirects().follow(false).get("/console/").then().statusCode(302)
                .header("Location", containsString("redirect_uri=https%3A%2F%2F"))
                .header("Set-Cookie", containsString("Secure"));
    }
}
