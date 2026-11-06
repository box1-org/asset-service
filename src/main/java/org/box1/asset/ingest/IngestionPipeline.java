package org.box1.asset.ingest;

import java.io.IOException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import org.box1.asset.config.AssetConfig;
import org.box1.asset.domain.Reading;
import org.box1.asset.domain.ReadingValidator;
import org.box1.asset.domain.RejectReason;
import org.box1.asset.domain.RejectedReadingException;

/**
 * The single admission path for API, load and chaos readings (D-003): size, parse, rate, validate, publish. Nothing
 * reaches Kafka without passing every step.
 */
@ApplicationScoped
public class IngestionPipeline {
    public static final String READING_EVENT = "equipment.reading.v1";
    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {
    };

    private final ReadingValidator validator;
    private final ReadingPublisher publisher;
    private final TokenBucket bucket;
    private final TokenBucket runs;
    private final MeterRegistry registry;
    private final Clock clock;
    private final int maxBody;
    private final ObjectMapper strict;

    public IngestionPipeline(ReadingValidator validator, ReadingPublisher publisher, AssetConfig config,
            MeterRegistry registry, Clock clock) {
        this.validator = validator;
        this.publisher = publisher;
        this.bucket = new TokenBucket(config.ingest().maxRatePerSecond(), System::nanoTime);
        // Load and chaos together may use at most half the shared rate, so real API writers keep the rest (ARCH-2, SEC-7).
        this.runs = new TokenBucket(Math.max(1, config.ingest().maxRatePerSecond() / 2), System::nanoTime);
        this.registry = registry;
        this.clock = clock;
        this.maxBody = config.ingest().maxBodyBytes();
        this.strict = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    /** A raw JSON body, as the HTTP resource and the oversize chaos entry submit it. */
    public Submission submitRaw(byte[] body, Source source, String correlationId) {
        if (body == null || body.length == 0) {
            throw count(source, new RejectedReadingException(RejectReason.MALFORMED, "Body is empty"));
        }
        if (body.length > maxBody) {
            throw count(source, new RejectedReadingException(RejectReason.OVERSIZE,
                    "Body is " + body.length + " bytes; the limit is " + maxBody));
        }
        Map<String, Object> fields;
        try {
            fields = strict.readValue(body, MAP);
        } catch (IOException error) {
            throw count(source, new RejectedReadingException(RejectReason.MALFORMED, "Body is not a JSON object"));
        }
        return submit(fields, source, correlationId);
    }

    /** Field map form, used by the load generator so malformed chaos values reach the same validator. */
    public Submission submit(Map<String, ?> fields, Source source, String correlationId) {
        if (source != Source.API && !runs.tryAcquire()) {
            throw count(source, new RejectedReadingException(RejectReason.RATE_LIMITED, "Run share of the ingestion rate reached"));
        }
        if (!bucket.tryAcquire()) {
            throw count(source, new RejectedReadingException(RejectReason.RATE_LIMITED,
                    "Ingestion rate limit reached; retry later"));
        }
        Reading reading;
        try {
            reading = validator.validate(fields);
        } catch (RejectedReadingException rejected) {
            throw count(source, rejected);
        }
        UUID eventId = eventId(reading);
        String traceparent = Envelopes.currentTraceparent();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("assetId", reading.assetId());
        payload.put("siteId", reading.siteId());
        payload.put("measuredAt", reading.measuredAt().toString());
        payload.put("metricName", reading.metricName());
        payload.put("value", reading.value());
        payload.put("unit", reading.unit());
        String correlation = correlationId == null ? eventId.toString() : correlationId;
        Map<String, Object> event = Envelopes.envelope(eventId, READING_EVENT, reading.assetId(), clock.instant(),
                correlation, "ingest:" + source.tag(), traceparent, payload);
        CompletableFuture<?> acked;
        try {
            acked = publisher.publish(eventId.toString(), reading.assetId(), Envelopes.bytes(strict, event));
        } catch (RejectedReadingException rejected) {
            throw count(source, rejected);
        }
        registry.counter("asset.ingest.admitted", "source", source.tag()).increment();
        return new Submission(eventId, traceparent, reading, acked);
    }

    /**
     * The same reading always gets the same eventId, so a client retry after a 503 or a re-sent duplicate cannot look
     * like a new event to consumers that deduplicate on eventId (review MSG-4). Name-based (RFC 4122 v3) over the
     * natural key, not for security.
     */
    static UUID eventId(Reading reading) {
        String key = "box1/equipment.reading.v1/" + reading.assetId() + "/" + reading.metricName() + "/" + reading.measuredAt();
        return UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private RejectedReadingException count(Source source, RejectedReadingException rejected) {
        registry.counter("asset.ingest.rejected", "source", source.tag(), "reason",
                rejected.reason().name().toLowerCase(java.util.Locale.ROOT)).increment();
        return rejected;
    }

    /** {@code acked} completes when Kafka acknowledges, or exceptionally when delivery fails. */
    public record Submission(UUID eventId, String traceparent, Reading reading, CompletableFuture<?> acked) {
    }
}
