package org.box1.asset;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.net.URI;
import java.util.Iterator;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/** TEL-CONTRACT-001, TEL-DOC-001: every operation in the shared asset-service contract exists in the served OpenAPI. */
@QuarkusTest
class OpenApiContractTest {
    @Test
    void servedOpenApiCoversTheSharedContract() throws Exception {
        String ref = System.getProperty("contracts.ref");
        JsonNode contract;
        try (InputStream in = URI.create("https://raw.githubusercontent.com/box1-org/box1-ops-contracts/" + ref + "/openapi/asset-service.yaml")
                .toURL().openStream()) {
            contract = new ObjectMapper(new YAMLFactory()).readTree(in);
        }
        JsonNode served = new ObjectMapper().readTree(given().accept("application/json").get("/q/openapi").asString());
        int operations = 0;
        for (Iterator<Map.Entry<String, JsonNode>> paths = contract.path("paths").fields(); paths.hasNext();) {
            Map.Entry<String, JsonNode> path = paths.next();
            for (Iterator<String> methods = path.getValue().fieldNames(); methods.hasNext();) {
                String method = methods.next();
                assertTrue(served.path("paths").path(path.getKey()).has(method), method + " " + path.getKey() + " is served");
                operations++;
            }
        }
        assertTrue(operations >= 3, "contract has operations");
        assertTrue(served.path("paths").path("/api/v1/telemetry/readings").path("post").path("responses").has("202"));
        assertTrue(served.path("components").path("securitySchemes").has("bearer"));
    }
}
