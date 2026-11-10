package org.box1.asset;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.net.URI;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

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
        assertTrue(operations >= 6, "contract has operations, including the three site operations");
        assertTrue(served.path("paths").path("/api/v1/telemetry/readings").path("post").path("responses").has("202"));
        assertTrue(served.path("components").path("securitySchemes").has("bearer"));
        sitePathsMatch(contract, served);
        for (String schema : new String[] {"SiteStatusReport", "SiteStatus"}) {
            schemasMatch(schema, contract.path("components").path("schemas").path(schema),
                    served.path("components").path("schemas").path(schema), served);
        }
    }

    /** box1-docs#130: the site operations' ids, response codes, security roles and path parameter pattern. */
    private static void sitePathsMatch(JsonNode contract, JsonNode served) {
        for (Iterator<Map.Entry<String, JsonNode>> paths = contract.path("paths").fields(); paths.hasNext();) {
            Map.Entry<String, JsonNode> path = paths.next();
            if (!path.getKey().startsWith("/api/v1/sites")) {
                continue;
            }
            for (Iterator<Map.Entry<String, JsonNode>> methods = path.getValue().fields(); methods.hasNext();) {
                Map.Entry<String, JsonNode> method = methods.next();
                String what = method.getKey() + " " + path.getKey();
                JsonNode expected = method.getValue();
                JsonNode actual = served.path("paths").path(path.getKey()).path(method.getKey());
                assertEquals(expected.path("operationId").asText(), actual.path("operationId").asText(), what + " operationId");
                assertEquals(names(expected.path("responses")), names(actual.path("responses")), what + " response codes");
                assertEquals(roles(expected.path("security")), roles(actual.path("security")), what + " security roles");
                for (JsonNode parameter : actual.path("parameters")) {
                    if ("siteId".equals(parameter.path("name").asText())) {
                        assertEquals("^site_[A-Za-z0-9_-]{1,64}$", parameter.path("schema").path("pattern").asText(), what + " siteId pattern");
                    }
                }
                if (expected.has("requestBody")) {
                    assertTrue(actual.path("requestBody").path("required").asBoolean(), what + " body required");
                    assertTrue(actual.path("requestBody").path("content").path("application/json").path("schema").path("$ref").asText()
                            .endsWith("/SiteStatusReport"), what + " body schema");
                }
            }
        }
    }

    private static void schemasMatch(String name, JsonNode expected, JsonNode actual, JsonNode served) {
        assertTrue(actual.isObject(), name + " is served");
        assertEquals(names(expected.path("properties")), names(actual.path("properties")), name + " properties");
        assertEquals(values(expected.path("required")), values(actual.path("required")), name + " required");
        if (expected.has("additionalProperties") && !expected.path("additionalProperties").asBoolean(true)) {
            assertFalse(actual.path("additionalProperties").asBoolean(true), name + " additionalProperties false");
        }
        for (String field : new String[] {"state", "greenbootStatus"}) {
            if (expected.path("properties").has(field)) {
                assertEquals(values(expected.path("properties").path(field).path("enum")),
                        values(actual.path("properties").path(field).path("enum")), name + "." + field + " enum");
            }
        }
        if (expected.path("properties").has("stateReasons")) {
            JsonNode items = actual.path("properties").path("stateReasons").path("items");
            if (items.has("$ref")) {
                items = served.path("components").path("schemas").path(items.path("$ref").asText().replace("#/components/schemas/", ""));
            }
            JsonNode reasonEnum = items.path("enum");
            assertEquals(values(expected.path("properties").path("stateReasons").path("items").path("enum")), values(reasonEnum),
                    name + ".stateReasons enum");
        }
    }

    private static Set<String> names(JsonNode object) {
        Set<String> names = new TreeSet<>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static Set<String> values(JsonNode array) {
        Set<String> values = new TreeSet<>();
        array.forEach(v -> values.add(v.asText()));
        return values;
    }

    /** Each security alternative as a sorted set of bearer roles, so order does not matter but the alternatives do. */
    private static Set<Set<String>> roles(JsonNode security) {
        Set<Set<String>> alternatives = new java.util.HashSet<>();
        security.forEach(requirement -> {
            Set<String> roles = new TreeSet<>();
            requirement.path("bearer").forEach(role -> roles.add(role.asText()));
            alternatives.add(roles);
        });
        return alternatives;
    }
}
