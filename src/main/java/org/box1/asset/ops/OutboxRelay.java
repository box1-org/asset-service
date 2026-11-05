package org.box1.asset.ops;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.jboss.logging.Logger;

/**
 * Publishes threshold events from the outbox after their transaction commits. Claims use the database clock and
 * SKIP LOCKED, so a restart or a second replica cannot double-claim; a claim expires after 60 s. A row is marked
 * published only after the broker acknowledges it. Failures back off exponentially up to 5 minutes, without limit
 * on attempts: an alert event is never discarded.
 */
@ApplicationScoped
public class OutboxRelay {
    private static final Logger LOG = Logger.getLogger(OutboxRelay.class);
    private static final String CLAIM = "UPDATE outbox SET claimed_until = now() + interval '60 seconds' WHERE id IN ("
            + " SELECT id FROM outbox WHERE published_at IS NULL AND next_attempt_at <= now()"
            + " AND (claimed_until IS NULL OR claimed_until < now()) ORDER BY id LIMIT 100 FOR UPDATE SKIP LOCKED)"
            + " RETURNING id, topic, record_key, body";

    private final DataSource dataSource;
    private final Producer<String, byte[]> producer;
    private final MeterRegistry registry;

    public OutboxRelay(DataSource dataSource, Producer<String, byte[]> producer, MeterRegistry registry) {
        this.dataSource = dataSource;
        this.producer = producer;
        this.registry = registry;
        registry.gauge("asset.outbox.pending", this, OutboxRelay::pendingOrNaN);
    }

    @Scheduled(every = "${box1.outbox.interval:1s}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void relay() {
        List<Row> rows;
        try {
            rows = claim();
        } catch (SQLException error) {
            LOG.warnf("Outbox claim failed (%s)", error.getClass().getSimpleName());
            return;
        }
        for (Row row : rows) {
            try {
                Future<?> ack = producer.send(new ProducerRecord<>(row.topic(), row.key(), row.body()));
                ack.get(15, TimeUnit.SECONDS);
                mark(row.id(), true);
                registry.counter("asset.outbox.published").increment();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception error) {
                registry.counter("asset.outbox.failures").increment();
                LOG.warnf("Outbox row %d not published (%s); will retry", row.id(), error.getClass().getSimpleName());
                try {
                    mark(row.id(), false);
                } catch (SQLException ignored) {
                    LOG.warn("Outbox backoff update failed; the claim expires in 60 s");
                }
            }
        }
    }

    private List<Row> claim() throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(CLAIM); ResultSet r = s.executeQuery()) {
            List<Row> rows = new ArrayList<>();
            while (r.next()) {
                rows.add(new Row(r.getLong(1), r.getString(2), r.getString(3), r.getBytes(4)));
            }
            return rows;
        }
    }

    private void mark(long id, boolean published) throws SQLException {
        String sql = published ? "UPDATE outbox SET published_at = now(), claimed_until = NULL WHERE id = ?"
                : "UPDATE outbox SET attempts = attempts + 1, claimed_until = NULL, next_attempt_at = now()"
                        + " + least(interval '300 seconds', interval '1 second' * power(2, least(attempts, 9))) WHERE id = ?";
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, id);
            s.executeUpdate();
        }
    }

    private double pendingOrNaN() {
        try (Connection c = dataSource.getConnection();
                PreparedStatement s = c.prepareStatement("SELECT count(*) FROM outbox WHERE published_at IS NULL");
                ResultSet r = s.executeQuery()) {
            r.next();
            return r.getLong(1);
        } catch (SQLException error) {
            return Double.NaN;
        }
    }

    record Row(long id, String topic, String key, byte[] body) {
    }
}
