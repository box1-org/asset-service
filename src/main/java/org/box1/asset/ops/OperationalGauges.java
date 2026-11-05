package org.box1.asset.ops;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.box1.asset.config.AssetConfig;
import org.box1.asset.store.StoreException;
import org.box1.asset.store.TelemetryStore;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Gauges that need a query: active alerts, stale assets and consumer lag. Each is NaN when it cannot be measured, so
 * dashboards and the console show "unknown" rather than a stale or invented value.
 */
@ApplicationScoped
public class OperationalGauges {
    private final TelemetryStore store;
    private final long staleSeconds;
    private final String topic;
    private final String group;
    private final String bootstrap;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(Snapshot.UNKNOWN);
    private Admin admin;

    public OperationalGauges(TelemetryStore store, AssetConfig config, MeterRegistry registry,
            @ConfigProperty(name = "kafka.bootstrap.servers") String bootstrap,
            @ConfigProperty(name = "mp.messaging.incoming.telemetry-in.group.id") String group) {
        this.store = store;
        this.staleSeconds = config.staleAfter().toSeconds();
        this.topic = config.ingest().topic();
        this.group = group;
        this.bootstrap = bootstrap;
        registry.gauge("asset.alerts.active", this, g -> g.snapshot.get().activeAlerts());
        registry.gauge("asset.assets.stale", this, g -> g.snapshot.get().staleAssets());
        registry.gauge("asset.consumer.lag.records", this, g -> g.snapshot.get().consumerLag());
    }

    public Snapshot snapshot() {
        return snapshot.get();
    }

    @Scheduled(every = "${box1.gauges.interval:10s}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void refresh() {
        double active = Double.NaN;
        double stale = Double.NaN;
        try {
            active = store.count("SELECT count(*) FROM alert WHERE status = 'ACTIVE'");
            stale = store.count("SELECT count(*) FROM asset_last_seen WHERE last_received_at < now() - interval '"
                    + staleSeconds + " seconds'");
        } catch (StoreException error) {
            // Left as NaN: unknown.
        }
        snapshot.set(new Snapshot(active, stale, lag(), Instant.now()));
    }

    private double lag() {
        try {
            Map<TopicPartition, OffsetAndMetadata> committed = admin().listConsumerGroupOffsets(group)
                    .partitionsToOffsetAndMetadata().get(3, TimeUnit.SECONDS);
            Map<TopicPartition, OffsetSpec> request = new HashMap<>();
            committed.keySet().stream().filter(tp -> tp.topic().equals(topic)).forEach(tp -> request.put(tp, OffsetSpec.latest()));
            if (request.isEmpty()) {
                return Double.NaN;
            }
            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> ends = admin().listOffsets(request).all().get(3, TimeUnit.SECONDS);
            long lag = 0;
            for (Map.Entry<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> end : ends.entrySet()) {
                OffsetAndMetadata at = committed.get(end.getKey());
                lag += Math.max(0, end.getValue().offset() - (at == null ? 0 : at.offset()));
            }
            return lag;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Double.NaN;
        } catch (Exception error) {
            return Double.NaN;
        }
    }

    private synchronized Admin admin() {
        if (admin == null) {
            admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                    AdminClientConfig.CLIENT_ID_CONFIG, "asset-service-gauges",
                    AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 3000,
                    AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 5000));
        }
        return admin;
    }

    synchronized void close(@Observes ShutdownEvent event) {
        if (admin != null) {
            admin.close(java.time.Duration.ofSeconds(2));
        }
    }

    /** {@code measuredAt} lets readers judge freshness. */
    public record Snapshot(double activeAlerts, double staleAssets, double consumerLag, Instant measuredAt) {
        static final Snapshot UNKNOWN = new Snapshot(Double.NaN, Double.NaN, Double.NaN, Instant.EPOCH);
    }
}
