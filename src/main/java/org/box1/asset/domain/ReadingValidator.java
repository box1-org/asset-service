package org.box1.asset.domain;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;
import org.box1.asset.config.AssetConfig;

/**
 * The one validation path for every reading: API, load and chaos (D-003). Input is the raw field map so that
 * malformed chaos payloads are rejected here, not built around.
 */
@ApplicationScoped
public class ReadingValidator {
    static final Set<String> FIELDS = Set.of("assetId", "siteId", "metricName", "measuredAt", "value", "unit");
    private static final Pattern ASSET = Pattern.compile("^asset_[A-Za-z0-9_-]{1,64}$");
    private static final Pattern SITE = Pattern.compile("^site_[A-Za-z0-9_-]{1,64}$");

    private final MetricCatalog catalog;
    private final Clock clock;
    private final Duration maxFuture;
    private final Duration maxAge;

    public ReadingValidator(MetricCatalog catalog, Clock clock, AssetConfig config) {
        this.catalog = catalog;
        this.clock = clock;
        this.maxFuture = config.ingest().maxFutureSkew();
        this.maxAge = config.retention().readings();
    }

    public Reading validate(Map<String, ?> fields) {
        return validate(fields, true);
    }

    /**
     * {@code checkTime=false} is for the consumer: a reading accepted at ingestion must not become invalid because it
     * waited in Kafka, so skew and age are checked only on admission.
     */
    public Reading validate(Map<String, ?> fields, boolean checkTime) {
        if (fields == null) {
            throw reject(RejectReason.MALFORMED, "Body must be a JSON object");
        }
        for (String key : fields.keySet()) {
            if (!FIELDS.contains(key)) {
                throw reject(RejectReason.UNKNOWN_FIELD, "Unknown field " + safe(key));
            }
        }
        for (String key : FIELDS) {
            if (fields.get(key) == null) {
                throw reject(RejectReason.MISSING_FIELD, "Missing field " + key);
            }
        }
        String assetId = string(fields, "assetId");
        String siteId = string(fields, "siteId");
        String metric = string(fields, "metricName");
        String unit = string(fields, "unit");
        if (!ASSET.matcher(assetId).matches()) {
            throw reject(RejectReason.INVALID_ID, "assetId must match asset_[A-Za-z0-9_-]{1,64}");
        }
        if (!SITE.matcher(siteId).matches()) {
            throw reject(RejectReason.INVALID_ID, "siteId must match site_[A-Za-z0-9_-]{1,64}");
        }
        AssetConfig.MetricPolicy policy = catalog.policy(metric)
                .orElseThrow(() -> reject(RejectReason.UNKNOWN_METRIC, "metricName must be one of " + catalog.names()));
        if (!catalog.acceptsUnit(metric, unit)) {
            throw reject(RejectReason.INVALID_UNIT, "unit " + safe(unit) + " is not accepted for " + metric);
        }
        if (!(fields.get("value") instanceof Number number)) {
            throw reject(RejectReason.MALFORMED, "value must be a number");
        }
        double raw = number.doubleValue();
        if (!Double.isFinite(raw)) {
            throw reject(RejectReason.NOT_FINITE, "value must be finite");
        }
        double value = Units.toCanonical(raw, unit, policy.unit());
        if (value < policy.min() || value > policy.max()) {
            throw reject(RejectReason.OUT_OF_BOUNDS,
                    metric + " must be between " + policy.min() + " and " + policy.max() + " " + policy.unit());
        }
        Instant measuredAt;
        try {
            measuredAt = Instant.parse(string(fields, "measuredAt"));
        } catch (DateTimeParseException error) {
            throw reject(RejectReason.MALFORMED, "measuredAt must be an RFC 3339 UTC timestamp");
        }
        if (!checkTime) {
            return new Reading(assetId, siteId, metric, measuredAt, value, policy.unit());
        }
        Instant now = clock.instant();
        if (measuredAt.isAfter(now.plus(maxFuture))) {
            throw reject(RejectReason.FUTURE_TIMESTAMP, "measuredAt is more than " + maxFuture + " in the future");
        }
        if (measuredAt.isBefore(now.minus(maxAge))) {
            throw reject(RejectReason.STALE_TIMESTAMP, "measuredAt is older than the " + maxAge + " retention");
        }
        return new Reading(assetId, siteId, metric, measuredAt, value, policy.unit());
    }

    private static String string(Map<String, ?> fields, String key) {
        if (!(fields.get(key) instanceof String text)) {
            throw reject(RejectReason.MALFORMED, key + " must be a string");
        }
        if (text.isEmpty() || text.length() > 80) {
            throw reject(RejectReason.MALFORMED, key + " must be 1 to 80 characters");
        }
        return text;
    }

    /** Echoes only short, plain identifiers back to the client. */
    private static String safe(String text) {
        return text.length() <= 20 && text.matches("[A-Za-z0-9_/.-]*") ? text : "(redacted)";
    }

    private static RejectedReadingException reject(RejectReason reason, String message) {
        return new RejectedReadingException(reason, message);
    }
}
