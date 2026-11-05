package org.box1.asset.ingest;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;

/** Builds the shared v1 event envelope (01_DOMAIN_CONTRACTS.md). */
public final class Envelopes {
    private Envelopes() {
    }

    public static Map<String, Object> envelope(UUID eventId, String eventType, String aggregateId, Instant occurredAt,
            String correlationId, String causationId, String traceparent, Map<String, Object> payload) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", eventId.toString());
        event.put("eventType", eventType);
        event.put("schemaVersion", 1);
        event.put("aggregateId", aggregateId);
        event.put("occurredAt", occurredAt.toString());
        event.put("correlationId", correlationId);
        event.put("causationId", causationId);
        if (traceparent != null) {
            event.put("traceparent", traceparent);
        }
        event.put("payload", payload);
        return event;
    }

    /** The W3C traceparent of the current span, or null when there is no valid span. */
    public static String currentTraceparent() {
        SpanContext context = Span.current().getSpanContext();
        if (!context.isValid()) {
            return null;
        }
        return "00-" + context.getTraceId() + "-" + context.getSpanId() + "-" + context.getTraceFlags().asHex();
    }

    public static byte[] bytes(ObjectMapper mapper, Map<String, Object> event) {
        try {
            return mapper.writeValueAsBytes(event);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Event serialization failed", error);
        }
    }
}
