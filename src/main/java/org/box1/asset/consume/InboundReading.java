package org.box1.asset.consume;

import java.util.UUID;

import org.box1.asset.domain.Reading;

/** A parsed equipment.reading.v1 event. */
public record InboundReading(UUID eventId, String correlationId, String traceparent, Reading reading) {
}
