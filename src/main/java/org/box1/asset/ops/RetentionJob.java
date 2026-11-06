package org.box1.asset.ops;

import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import org.box1.asset.config.AssetConfig;
import org.box1.asset.store.StoreException;
import org.box1.asset.store.TelemetryStore;
import org.jboss.logging.Logger;

/** Bounded retention (D-009): age, then a hard row cap, in batches so no single delete holds long locks. */
@ApplicationScoped
public class RetentionJob {
    private static final Logger LOG = Logger.getLogger(RetentionJob.class);
    private final TelemetryStore store;
    private final AssetConfig.Retention retention;
    private final MeterRegistry registry;

    public RetentionJob(TelemetryStore store, AssetConfig config, MeterRegistry registry) {
        this.store = store;
        this.retention = config.retention();
        this.registry = registry;
    }

    @Scheduled(every = "${box1.retention.interval:60s}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void run() {
        try {
            long seconds = retention.readings().toSeconds();
            int batch = retention.batchSize();
            int aged = store.update("DELETE FROM reading WHERE ctid IN (SELECT ctid FROM reading WHERE measured_at < now() - ? * interval"
                    + " '1 second' LIMIT ?)", "retention by age", seconds, batch);
            long total = store.count("SELECT count(*) FROM reading");
            int capped = 0;
            if (total > retention.maxRows()) {
                long excess = Math.min(batch, total - retention.maxRows());
                capped = store.update("DELETE FROM reading WHERE ctid IN (SELECT ctid FROM reading ORDER BY measured_at LIMIT ?)",
                        "retention by row cap", excess);
            }
            store.update("DELETE FROM alert WHERE status = 'CLEARED' AND cleared_at < now() - ? * interval '1 second'",
                    "alert retention", retention.clearedAlerts().toSeconds());
            store.update("DELETE FROM outbox WHERE published_at < now() - interval '1 day'", "outbox retention");
            store.update("DELETE FROM control_audit WHERE at < now() - interval '90 days'", "audit retention");
            store.update("DELETE FROM control_run WHERE created_at < now() - interval '90 days' AND state NOT IN ('ARMED', 'RUNNING')",
                    "run retention");
            registry.counter("asset.retention.deleted", "reason", "age").increment(aged);
            registry.counter("asset.retention.deleted", "reason", "row_cap").increment(capped);
        } catch (StoreException error) {
            LOG.warnf("Retention pass failed (%s); next pass retries", error.getMessage());
        }
    }
}
