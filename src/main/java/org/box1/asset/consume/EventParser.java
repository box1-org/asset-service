package org.box1.asset.consume;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import org.box1.asset.domain.ReadingValidator;
import org.box1.asset.domain.RejectedReadingException;

/**
 * Parses records from box1.telemetry.events.v1. Returns empty for event types this consumer does not project (its own
 * threshold events, future types). Throws {@link PoisonEventException} for records that can never be processed.
 */
@ApplicationScoped
public class EventParser {
    static final Pattern TRACEPARENT = Pattern.compile("^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$");
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };
    private final ObjectMapper mapper = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private final ReadingValidator validator;

    public EventParser(ReadingValidator validator) {
        this.validator = validator;
    }

    public Optional<InboundReading> parse(byte[] value) {
        if (value == null) {
            throw new PoisonEventException("NULL_VALUE");
        }
        Map<String, Object> event;
        try {
            event = mapper.readValue(value, MAP);
        } catch (IOException error) {
            throw new PoisonEventException("NOT_JSON");
        }
        if (!(event.get("eventType") instanceof String type)) {
            throw new PoisonEventException("ENVELOPE_INVALID");
        }
        if (!"equipment.reading.v1".equals(type)) {
            return Optional.empty();
        }
        UUID eventId = uuid(event.get("eventId"));
        if (!Integer.valueOf(1).equals(event.get("schemaVersion")) || !(event.get("correlationId") instanceof String correlation)
                || correlation.isEmpty() || correlation.length() > 128 || !(event.get("occurredAt") instanceof String occurred)) {
            throw new PoisonEventException("ENVELOPE_INVALID");
        }
        try {
            Instant.parse(occurred);
        } catch (DateTimeParseException error) {
            throw new PoisonEventException("ENVELOPE_INVALID");
        }
        Object traceparent = event.get("traceparent");
        if (traceparent != null && !(traceparent instanceof String text && TRACEPARENT.matcher(text).matches())) {
            throw new PoisonEventException("ENVELOPE_INVALID");
        }
        if (!(event.get("payload") instanceof Map<?, ?> payload)) {
            throw new PoisonEventException("PAYLOAD_INVALID");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = (Map<String, Object>) payload;
        try {
            return Optional.of(new InboundReading(eventId, correlation, (String) traceparent, validator.validate(fields, false)));
        } catch (RejectedReadingException rejected) {
            throw new PoisonEventException("POLICY_" + rejected.reason().name());
        }
    }

    private static UUID uuid(Object value) {
        if (value instanceof String text) {
            try {
                return UUID.fromString(text);
            } catch (IllegalArgumentException error) {
                throw new PoisonEventException("ENVELOPE_INVALID");
            }
        }
        throw new PoisonEventException("ENVELOPE_INVALID");
    }
}
