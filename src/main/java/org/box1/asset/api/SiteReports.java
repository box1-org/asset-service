package org.box1.asset.api;

import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.box1.asset.site.SiteReport;

/**
 * Parses a {@code SiteStatusReport} body exactly as the contract's schema states (box1-ops-contracts
 * openapi/asset-service.yaml): a JSON object without duplicate or unknown keys, the required fields, non-negative
 * integers, {@code sha256:} digests or null, the greenboot enum, and at most 8 {@code appVersions}. Each failure is a
 * 400 problem with a stable code.
 */
public final class SiteReports {
    public static final Pattern SITE_ID = Pattern.compile("^site_[A-Za-z0-9_-]{1,64}$");
    static final int MAX_BODY_BYTES = 4096;
    static final int MAX_APP_VERSIONS = 8;
    private static final Pattern DIGEST = Pattern.compile("^sha256:[a-f0-9]{64}$");
    private static final Pattern APP_NAME = Pattern.compile("^[a-z][a-z0-9-]{0,31}$");
    private static final Set<String> GREENBOOT = Set.of("GREEN", "RED", "UNKNOWN");
    private static final Set<String> REQUIRED = Set.of("reportedAt", "bufferDepth", "siteEventBacklog", "bufferOldestAgeSeconds",
            "readingsDropped", "greenbootStatus", "appVersions");
    private static final Set<String> KNOWN = Set.of("reportedAt", "bufferDepth", "siteEventBacklog", "bufferOldestAgeSeconds",
            "readingsDropped", "bootedImageDigest", "stagedImageDigest", "rollbackImageDigest", "greenbootStatus", "bundleDigest",
            "appVersions");
    private static final ObjectMapper STRICT = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private SiteReports() {
    }

    public static boolean validSiteId(String siteId) {
        return siteId != null && SITE_ID.matcher(siteId).matches();
    }

    public static SiteReport parse(byte[] body) {
        if (body == null || body.length == 0) {
            throw new InvalidRequestException("MALFORMED", "The body must be a SiteStatusReport JSON object");
        }
        JsonNode root;
        try {
            root = STRICT.readTree(body);
        } catch (IOException error) {
            throw new InvalidRequestException("MALFORMED", "The body is not valid JSON (duplicate keys are not allowed)");
        }
        if (root == null || !root.isObject()) {
            throw new InvalidRequestException("MALFORMED", "The body must be a SiteStatusReport JSON object");
        }
        for (Iterator<String> names = root.fieldNames(); names.hasNext();) {
            String name = names.next();
            if (!KNOWN.contains(name)) {
                throw new InvalidRequestException("UNKNOWN_FIELD", "Unknown field " + safe(name));
            }
        }
        for (String name : REQUIRED) {
            if (!root.has(name)) {
                throw new InvalidRequestException("MISSING_FIELD", name + " is required");
            }
        }
        return new SiteReport(time(root.get("reportedAt")), count(root, "bufferDepth"), count(root, "siteEventBacklog"),
                count(root, "bufferOldestAgeSeconds"), count(root, "readingsDropped"), digest(root, "bootedImageDigest"),
                digest(root, "stagedImageDigest"), digest(root, "rollbackImageDigest"), greenboot(root.get("greenbootStatus")),
                digest(root, "bundleDigest"), versions(root.get("appVersions")));
    }

    private static Instant time(JsonNode node) {
        if (!node.isTextual()) {
            throw new InvalidRequestException("INVALID_TIME", "reportedAt must be an RFC 3339 date-time");
        }
        try {
            return OffsetDateTime.parse(node.asText()).toInstant();
        } catch (DateTimeParseException error) {
            throw new InvalidRequestException("INVALID_TIME", "reportedAt must be an RFC 3339 date-time");
        }
    }

    /** A JSON Schema integer (a number with no fractional part, 3.0 included) from 0 to Long.MAX_VALUE. */
    private static long count(JsonNode root, String name) {
        JsonNode node = root.get(name);
        boolean integral = node.isIntegralNumber() && node.canConvertToLong()
                || node.isFloatingPointNumber() && node.canConvertToExactIntegral() && node.canConvertToLong();
        if (!integral || node.asLong() < 0) {
            throw new InvalidRequestException("INVALID_INTEGER", name + " must be an integer of at least 0");
        }
        return node.asLong();
    }

    private static String digest(JsonNode root, String name) {
        JsonNode node = root.get(name);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual() || !DIGEST.matcher(node.asText()).matches()) {
            throw new InvalidRequestException("INVALID_DIGEST", name + " must match ^sha256:[a-f0-9]{64}$ or be null");
        }
        return node.asText();
    }

    private static String greenboot(JsonNode node) {
        if (!node.isTextual() || !GREENBOOT.contains(node.asText())) {
            throw new InvalidRequestException("INVALID_GREENBOOT", "greenbootStatus must be GREEN, RED or UNKNOWN");
        }
        return node.asText();
    }

    private static Map<String, String> versions(JsonNode node) {
        if (!node.isObject()) {
            throw new InvalidRequestException("INVALID_APP_VERSIONS", "appVersions must be an object");
        }
        if (node.size() > MAX_APP_VERSIONS) {
            throw new InvalidRequestException("INVALID_APP_VERSIONS", "appVersions has at most " + MAX_APP_VERSIONS + " entries");
        }
        Map<String, String> versions = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> fields = node.fields(); fields.hasNext();) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!APP_NAME.matcher(field.getKey()).matches()) {
                throw new InvalidRequestException("INVALID_APP_VERSIONS", "appVersions names must match ^[a-z][a-z0-9-]{0,31}$");
            }
            JsonNode value = field.getValue();
            int length = value.isTextual() ? value.asText().codePointCount(0, value.asText().length()) : -1;
            if (length < 1 || length > 64) {
                throw new InvalidRequestException("INVALID_APP_VERSIONS", "appVersions values must be strings of 1 to 64 characters");
            }
            versions.put(field.getKey(), value.asText());
        }
        return versions;
    }

    /** Field names are echoed only when they are short and plain, so a problem never reflects arbitrary input. */
    private static String safe(String name) {
        return name.matches("[A-Za-z0-9_-]{1,64}") ? name : "(not shown)";
    }
}
