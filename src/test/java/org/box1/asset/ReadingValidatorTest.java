package org.box1.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.box1.asset.domain.Reading;
import org.box1.asset.domain.ReadingValidator;
import org.box1.asset.domain.RejectReason;
import org.box1.asset.domain.RejectedReadingException;
import org.junit.jupiter.api.Test;

/** TEL-INGEST-001 and TEL-INGEST-003: the one validation path. */
class ReadingValidatorTest {
    private static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");
    private final ReadingValidator validator = new ReadingValidator(TestCatalog.catalog(), Clock.fixed(NOW, ZoneOffset.UTC),
            TestCatalog.config(false, List.of(), null));

    private static Map<String, Object> valid() {
        Map<String, Object> fields = new HashMap<>();
        fields.put("assetId", "asset_press_7");
        fields.put("siteId", "site_nyc");
        fields.put("metricName", "temperature");
        fields.put("measuredAt", "2026-11-05T11:59:00Z");
        fields.put("value", 72.4);
        fields.put("unit", "C");
        return fields;
    }

    private RejectReason reason(Map<String, Object> fields) {
        return assertThrows(RejectedReadingException.class, () -> validator.validate(fields)).reason();
    }

    @Test
    void acceptsAValidReadingAndConvertsFahrenheit() {
        assertEquals(72.4, validator.validate(valid()).value());
        Map<String, Object> f = valid();
        f.put("unit", "F");
        f.put("value", 212.0);
        Reading reading = validator.validate(f);
        assertEquals(100.0, reading.value(), 1e-9);
        assertEquals("C", reading.unit());
    }

    @Test
    void rejectsEachInvalidShape() {
        Map<String, Object> f = valid();
        f.put("unit", "furlong");
        assertEquals(RejectReason.INVALID_UNIT, reason(f));
        f = valid();
        f.put("unit", "mm/s");
        assertEquals(RejectReason.INVALID_UNIT, reason(f));
        f = valid();
        f.remove("siteId");
        assertEquals(RejectReason.MISSING_FIELD, reason(f));
        f = valid();
        f.put("extra", 1);
        assertEquals(RejectReason.UNKNOWN_FIELD, reason(f));
        f = valid();
        f.put("metricName", "humidity");
        assertEquals(RejectReason.UNKNOWN_METRIC, reason(f));
        f = valid();
        f.put("value", Double.NaN);
        assertEquals(RejectReason.NOT_FINITE, reason(f));
        f = valid();
        f.put("value", 251.0);
        assertEquals(RejectReason.OUT_OF_BOUNDS, reason(f));
        f = valid();
        f.put("value", "72");
        assertEquals(RejectReason.MALFORMED, reason(f));
        f = valid();
        f.put("assetId", "press_7");
        assertEquals(RejectReason.INVALID_ID, reason(f));
        f = valid();
        f.put("measuredAt", "yesterday");
        assertEquals(RejectReason.MALFORMED, reason(f));
    }

    @Test
    void enforcesTimestampSkewOnAdmissionOnly() {
        Map<String, Object> f = valid();
        f.put("measuredAt", "2026-11-05T12:05:01Z");
        assertEquals(RejectReason.FUTURE_TIMESTAMP, reason(f));
        f.put("measuredAt", "2026-11-04T11:59:59Z");
        assertEquals(RejectReason.STALE_TIMESTAMP, reason(f));
        assertEquals("asset_press_7", validator.validate(f, false).assetId());
    }

    @Test
    void siteOverrideIsMoreSpecificThanTheMetricDefault() {
        assertEquals(90.0, TestCatalog.catalog().threshold("asset_x", "site_hot", "temperature").warning());
        assertEquals(95.0, TestCatalog.catalog().threshold("asset_x", "site_hot", "temperature").critical());
        assertEquals(80.0, TestCatalog.catalog().threshold("asset_x", "site_cold", "temperature").warning());
    }
}
