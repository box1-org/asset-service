package org.box1.asset;

import java.io.InputStream;
import java.net.URI;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

/** Validates events against box1-ops-contracts at the pinned commit (contracts.ref). No local copy of a shared schema. */
public final class ContractSchemas {
    private ContractSchemas() {
    }

    public static void assertValid(JsonNode event) throws Exception {
        String ref = System.getProperty("contracts.ref");
        if (ref == null || ref.isBlank()) {
            throw new AssertionError("contracts.ref is not set");
        }
        String type = event.path("eventType").asText().replaceAll("\\.v[0-9]+$", "");
        check("envelope", event, ref);
        check(type, event.path("payload"), ref);
    }

    /** The pinned contract's openapi/asset-service.yaml, as a tree. */
    public static JsonNode openApi() throws Exception {
        String ref = System.getProperty("contracts.ref");
        if (ref == null || ref.isBlank()) {
            throw new AssertionError("contracts.ref is not set");
        }
        try (InputStream in = URI.create("https://raw.githubusercontent.com/box1-org/box1-ops-contracts/" + ref + "/openapi/asset-service.yaml")
                .toURL().openStream()) {
            return new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory()).readTree(in);
        }
    }

    /** A JSON Schema (2020-12) for one of the contract's components, its references resolved inside the document. */
    public static JsonSchema component(JsonNode openApi, String name) {
        com.fasterxml.jackson.databind.node.ObjectNode root = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        root.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        root.put("$ref", "#/components/schemas/" + name);
        root.set("components", openApi.path("components"));
        SchemaValidatorsConfig config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(root, config);
    }

    private static void check(String name, JsonNode node, String ref) throws Exception {
        URI uri = URI.create("https://raw.githubusercontent.com/box1-org/box1-ops-contracts/" + ref + "/events/v1/" + name + ".schema.json");
        SchemaValidatorsConfig config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
        JsonSchema schema;
        try (InputStream in = uri.toURL().openStream()) {
            schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(in, config);
        }
        Set<ValidationMessage> errors = schema.validate(node);
        if (!errors.isEmpty()) {
            throw new AssertionError(name + " invalid: " + errors);
        }
    }
}
