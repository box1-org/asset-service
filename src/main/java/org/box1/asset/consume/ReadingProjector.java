package org.box1.asset.consume;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.box1.asset.config.AssetConfig;
import org.box1.asset.domain.AlertState;
import org.box1.asset.domain.MetricCatalog;
import org.box1.asset.domain.Reading;
import org.box1.asset.domain.Threshold;
import org.box1.asset.domain.ThresholdEngine;
import org.box1.asset.ingest.Envelopes;
import org.box1.asset.store.TelemetryStore;
import org.jboss.logging.Logger;

/**
 * Stores one reading and evaluates its threshold in one transaction (D-008, D-009). The alert row and its outbox event
 * commit together, so an activation is never lost or emitted without its alert.
 */
@ApplicationScoped
public class ReadingProjector {
    public static final String THRESHOLD_EVENT = "equipment.threshold-exceeded.v1";
    private static final Logger LOG = Logger.getLogger(ReadingProjector.class);

    private final TelemetryStore store;
    private final MetricCatalog catalog;
    private final ThresholdEngine engine = new ThresholdEngine(ThresholdEngine.DEFAULT_TRIGGER);
    private final MeterRegistry registry;
    private final Clock clock;
    private final String topic;
    private final ObjectMapper mapper = new ObjectMapper();

    public ReadingProjector(TelemetryStore store, MetricCatalog catalog, MeterRegistry registry, Clock clock, AssetConfig config) {
        this.store = store;
        this.catalog = catalog;
        this.registry = registry;
        this.clock = clock;
        this.topic = config.ingest().topic();
    }

    /** Returns what happened, for metrics and tests. */
    @Transactional
    public Result project(InboundReading inbound, String currentTraceparent) {
        Reading reading = inbound.reading();
        if (!store.insertReading(reading, inbound.eventId(), inbound.traceparent())) {
            registry.counter("asset.consume.duplicates").increment();
            return Result.DUPLICATE;
        }
        store.touchLastSeen(reading.assetId(), reading.siteId());
        TelemetryStore.StoredState stored = store.lockState(reading.assetId(), reading.metricName()).orElse(null);
        if (stored != null && reading.measuredAt().isBefore(stored.lastMeasuredAt())) {
            registry.counter("asset.consume.out.of.order").increment();
            return Result.STORED_NOT_EVALUATED;
        }
        AlertState state = stored == null ? AlertState.NONE : stored.state();
        Threshold threshold = catalog.threshold(reading.assetId(), reading.siteId(), reading.metricName());
        ThresholdEngine.Outcome outcome = engine.evaluate(state, threshold, reading.value());
        switch (outcome.transition()) {
            case ACTIVATED -> activate(inbound, outcome, currentTraceparent);
            case ESCALATED -> store.updateActiveAlert(reading.assetId(), reading.metricName(), reading.value(),
                    outcome.next().severity().name(), outcome.threshold());
            case CLEARED -> {
                store.clearAlert(reading.assetId(), reading.metricName(), reading.value());
                transition("cleared", state.severity());
                LOG.infof("Alert cleared for %s %s", reading.assetId(), reading.metricName());
            }
            case REPEATED, NONE -> {
                if (state.active()) {
                    store.updateActiveAlert(reading.assetId(), reading.metricName(), reading.value(), null, null);
                }
            }
            default -> throw new IllegalStateException("Unhandled transition " + outcome.transition());
        }
        if (outcome.transition() == ThresholdEngine.Transition.ESCALATED) {
            transition("escalated", outcome.next().severity());
        }
        store.saveState(reading.assetId(), reading.metricName(), outcome.next(), reading.measuredAt());
        return outcome.transition() == ThresholdEngine.Transition.ACTIVATED ? Result.ACTIVATED : Result.STORED;
    }

    private void activate(InboundReading inbound, ThresholdEngine.Outcome outcome, String currentTraceparent) {
        Reading reading = inbound.reading();
        String alertId = "alt_" + UUID.randomUUID().toString().replace("-", "");
        UUID eventId = UUID.randomUUID();
        String severity = outcome.next().severity().name();
        String traceparent = currentTraceparent != null ? currentTraceparent : inbound.traceparent();
        store.insertAlert(alertId, reading, severity, outcome.threshold(), inbound.eventId(), eventId, traceparent);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("assetId", reading.assetId());
        payload.put("siteId", reading.siteId());
        payload.put("metricName", reading.metricName());
        payload.put("value", reading.value());
        payload.put("threshold", outcome.threshold());
        payload.put("severity", severity);
        payload.put("unit", reading.unit());
        payload.put("alertId", alertId);
        payload.put("measuredAt", reading.measuredAt().toString());
        payload.put("sourceEventId", inbound.eventId().toString());
        Map<String, Object> event = Envelopes.envelope(eventId, THRESHOLD_EVENT, reading.assetId(), clock.instant(),
                inbound.correlationId(), inbound.eventId().toString(), traceparent, payload);
        store.insertOutbox(eventId, topic, reading.assetId(), Envelopes.bytes(mapper, event));
        transition("activated", outcome.next().severity());
        LOG.infof("Alert %s activated for %s %s (%s) from reading %s", alertId, reading.assetId(), reading.metricName(),
                severity, inbound.eventId());
    }

    /** One meter name, always the same tag keys: Prometheus rejects a name registered with different keys. */
    private void transition(String transition, AlertState.Severity severity) {
        registry.counter("asset.alerts.transitions", "transition", transition, "severity",
                severity == null ? "none" : severity.name().toLowerCase(java.util.Locale.ROOT)).increment();
    }

    public enum Result { DUPLICATE, STORED, STORED_NOT_EVALUATED, ACTIVATED }
}
