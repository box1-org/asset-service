package org.box1.asset.ops;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import org.box1.asset.ingest.ReadingPublisher;

/** Console readouts computed from this service's own Micrometer meters, over a ~10 s window. */
@ApplicationScoped
public class Readouts {
    private static final int WINDOW = 6;
    private final MeterRegistry registry;
    private final ReadingPublisher publisher;
    private final OperationalGauges gauges;
    private final Deque<Sample> samples = new ArrayDeque<>();

    public Readouts(MeterRegistry registry, ReadingPublisher publisher, OperationalGauges gauges) {
        this.registry = registry;
        this.publisher = publisher;
        this.gauges = gauges;
    }

    @Scheduled(every = "2s")
    synchronized void sample() {
        samples.addLast(new Sample(System.nanoTime(), sum("asset.ingest.admitted"), sum("asset.ingest.published"),
                sum("asset.ingest.rejected"), sum("asset.ingest.dropped")));
        while (samples.size() > WINDOW) {
            samples.removeFirst();
        }
    }

    public synchronized Map<String, Object> current() {
        Map<String, Object> view = new LinkedHashMap<>();
        if (samples.size() >= 2) {
            Sample first = samples.peekFirst();
            Sample last = samples.peekLast();
            double seconds = (last.nanos() - first.nanos()) / 1e9;
            view.put("admittedPerSecond", round((last.admitted() - first.admitted()) / seconds));
            view.put("publishedPerSecond", round((last.published() - first.published()) / seconds));
            view.put("rejectedPerSecond", round((last.rejected() - first.rejected()) / seconds));
            view.put("droppedTotal", last.dropped());
        } else {
            view.put("admittedPerSecond", null);
            view.put("publishedPerSecond", null);
            view.put("rejectedPerSecond", null);
            view.put("droppedTotal", null);
        }
        OperationalGauges.Snapshot snapshot = gauges.snapshot();
        view.put("activeAlerts", nullIfNaN(snapshot.activeAlerts()));
        view.put("staleAssets", nullIfNaN(snapshot.staleAssets()));
        view.put("consumerLag", nullIfNaN(snapshot.consumerLag()));
        view.put("gaugesMeasuredAt", snapshot.measuredAt().equals(Instant.EPOCH) ? null : snapshot.measuredAt());
        view.put("producerInFlight", publisher.inFlight());
        view.put("producerCapacity", publisher.capacity());
        view.put("producerPaused", publisher.isPaused());
        view.put("producerPausedUntil", publisher.isPaused() ? publisher.pausedUntil() : null);
        return view;
    }

    private double sum(String name) {
        return registry.find(name).counters().stream().mapToDouble(Counter::count).sum();
    }

    private static Double round(double value) {
        return Double.isFinite(value) ? Math.round(value * 10.0) / 10.0 : null;
    }

    private static Double nullIfNaN(double value) {
        return Double.isNaN(value) ? null : value;
    }

    record Sample(long nanos, double admitted, double published, double rejected, double dropped) {
    }
}
