package org.box1.asset.consume;

import java.util.Optional;
import java.util.concurrent.CompletionStage;

import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.smallrye.reactive.messaging.annotations.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import org.box1.asset.ingest.Envelopes;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

/**
 * Projects box1.telemetry.events.v1 into the reading store (D-010). Poison records are nacked to the dead-letter
 * topic. A database failure never dead-letters a valid record: it retries with backoff and holds the partition.
 */
@ApplicationScoped
public class ReadingConsumer {
    private static final Logger LOG = Logger.getLogger(ReadingConsumer.class);
    private static final long MAX_BACKOFF_MILLIS = 30_000;
    static final int MAX_PERMANENT_ATTEMPTS = 5;
    private static final TextMapGetter<String> TRACEPARENT = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(String carrier) {
            return java.util.List.of("traceparent");
        }

        @Override
        public String get(String carrier, String key) {
            return "traceparent".equals(key) ? carrier : null;
        }
    };

    private final EventParser parser;
    private final ReadingProjector projector;
    private final MeterRegistry registry;
    private final Tracer tracer;
    private volatile boolean stopping;

    public ReadingConsumer(EventParser parser, ReadingProjector projector, MeterRegistry registry, Tracer tracer) {
        this.parser = parser;
        this.projector = projector;
        this.registry = registry;
        this.tracer = tracer;
    }

    @Incoming("telemetry-in")
    @Blocking(ordered = true)
    public CompletionStage<Void> consume(Message<byte[]> message) {
        Optional<InboundReading> parsed;
        try {
            parsed = parser.parse(message.getPayload());
        } catch (PoisonEventException poison) {
            registry.counter("asset.consume.dead.lettered", "reason", poison.getMessage()).increment();
            LOG.warnf("Dead-lettering record: %s", poison.getMessage());
            return message.nack(poison);
        }
        if (parsed.isEmpty()) {
            registry.counter("asset.consume.ignored").increment();
            return message.ack();
        }
        InboundReading inbound = parsed.get();
        Context parent = inbound.traceparent() == null ? Context.root()
                : W3CTraceContextPropagator.getInstance().extract(Context.root(), inbound.traceparent(), TRACEPARENT);
        Span span = tracer.spanBuilder("asset-service consume equipment.reading.v1").setParent(parent)
                .setSpanKind(SpanKind.CONSUMER).startSpan();
        try (Scope ignored = span.makeCurrent()) {
            MDC.put("eventId", inbound.eventId().toString());
            MDC.put("assetId", inbound.reading().assetId());
            MDC.put("correlationId", inbound.correlationId());
            if (!projectWithRetry(inbound)) {
                registry.counter("asset.consume.dead.lettered", "reason", "PROJECTION_FAILED").increment();
                return message.nack(new PoisonEventException("PROJECTION_FAILED"));
            }
            registry.counter("asset.consume.processed").increment();
        } finally {
            MDC.remove("eventId");
            MDC.remove("assetId");
            MDC.remove("correlationId");
            span.end();
        }
        return message.ack();
    }

    /**
     * Transient database failures retry with backoff for as long as they last and hold the partition, so a database
     * outage never dead-letters a valid reading. Any other failure is a defect or bad data that will not heal by
     * waiting: it gets {@link #MAX_PERMANENT_ATTEMPTS} attempts, then the record is dead-lettered. Returns false then.
     */
    private boolean projectWithRetry(InboundReading inbound) {
        long backoff = 250;
        int permanent = 0;
        while (true) {
            try {
                projector.project(inbound, Envelopes.currentTraceparent());
                return true;
            } catch (RuntimeException error) {
                if (stopping) {
                    throw error;
                }
                boolean transientFailure = Failures.isTransient(error);
                registry.counter("asset.consume.retries", "kind", transientFailure ? "transient" : "permanent").increment();
                if (!transientFailure && ++permanent >= MAX_PERMANENT_ATTEMPTS) {
                    LOG.errorf(error, "Projection of %s failed %d times with a non-transient error; dead-lettering it",
                            inbound.eventId(), permanent);
                    return false;
                }
                LOG.warnf(transientFailure ? null : error, "Projection of %s failed (%s, %s); retrying in %d ms", inbound.eventId(),
                        error.getClass().getSimpleName(), transientFailure ? "transient" : "attempt " + permanent, backoff);
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw error;
                }
                backoff = Math.min(MAX_BACKOFF_MILLIS, backoff * 2);
            }
        }
    }

    void onStop(@jakarta.enterprise.event.Observes io.quarkus.runtime.ShutdownEvent event) {
        stopping = true;
    }
}
