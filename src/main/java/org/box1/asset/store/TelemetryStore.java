package org.box1.asset.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import jakarta.enterprise.context.ApplicationScoped;
import org.box1.asset.domain.AlertState;
import org.box1.asset.domain.Reading;

/** Readings, evaluation state, alerts and the outbox. Callers own the transaction. */
@ApplicationScoped
public class TelemetryStore {
    private final DataSource dataSource;

    public TelemetryStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Returns false when the natural key already exists (duplicate). */
    public boolean insertReading(Reading reading, UUID eventId, String traceparent) {
        String sql = "INSERT INTO reading (asset_id, metric_name, measured_at, site_id, value, unit, source_event_id, traceparent)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING";
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, reading.assetId());
            s.setString(2, reading.metricName());
            s.setTimestamp(3, Timestamp.from(reading.measuredAt()));
            s.setString(4, reading.siteId());
            s.setDouble(5, reading.value());
            s.setString(6, reading.unit());
            s.setObject(7, eventId);
            s.setString(8, traceparent);
            return s.executeUpdate() == 1;
        } catch (SQLException error) {
            throw new StoreException("insert reading", error);
        }
    }

    /** The stored value at a reading's natural key, if any. */
    public Optional<Double> storedValue(Reading reading) {
        String sql = "SELECT value FROM reading WHERE asset_id = ? AND metric_name = ? AND measured_at = ?";
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, reading.assetId());
            s.setString(2, reading.metricName());
            s.setTimestamp(3, Timestamp.from(reading.measuredAt()));
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? Optional.of(r.getDouble(1)) : Optional.empty();
            }
        } catch (SQLException error) {
            throw new StoreException("read stored value", error);
        }
    }

    public void touchLastSeen(String assetId, String siteId) {
        String sql = "INSERT INTO asset_last_seen (asset_id, site_id, last_received_at) VALUES (?, ?, now())"
                + " ON CONFLICT (asset_id) DO UPDATE SET site_id = EXCLUDED.site_id, last_received_at = now()";
        update(sql, "touch last seen", assetId, siteId);
    }

    /** Locks the pair's state row for this transaction. */
    public Optional<StoredState> lockState(String assetId, String metric) {
        String sql = "SELECT active, severity, breach_streak, clear_streak, last_measured_at FROM alert_state"
                + " WHERE asset_id = ? AND metric_name = ? FOR UPDATE";
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, assetId);
            s.setString(2, metric);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) {
                    return Optional.empty();
                }
                String severity = r.getString(2);
                AlertState state = new AlertState(r.getBoolean(1), severity == null ? null : AlertState.Severity.valueOf(severity),
                        r.getInt(3), r.getInt(4));
                return Optional.of(new StoredState(state, r.getTimestamp(5).toInstant()));
            }
        } catch (SQLException error) {
            throw new StoreException("lock alert state", error);
        }
    }

    public void saveState(String assetId, String metric, AlertState state, Instant measuredAt) {
        String sql = "INSERT INTO alert_state (asset_id, metric_name, active, severity, breach_streak, clear_streak, last_measured_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (asset_id, metric_name) DO UPDATE SET active = EXCLUDED.active,"
                + " severity = EXCLUDED.severity, breach_streak = EXCLUDED.breach_streak, clear_streak = EXCLUDED.clear_streak,"
                + " last_measured_at = EXCLUDED.last_measured_at";
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, assetId);
            s.setString(2, metric);
            s.setBoolean(3, state.active());
            s.setString(4, state.severity() == null ? null : state.severity().name());
            s.setInt(5, state.breachStreak());
            s.setInt(6, state.clearStreak());
            s.setTimestamp(7, Timestamp.from(measuredAt));
            s.executeUpdate();
        } catch (SQLException error) {
            throw new StoreException("save alert state", error);
        }
    }

    public void insertAlert(String alertId, Reading reading, String severity, double threshold, UUID sourceEventId,
            UUID eventId, String traceparent) {
        String sql = "INSERT INTO alert (alert_id, asset_id, site_id, metric_name, unit, status, severity, threshold, first_value,"
                + " peak_value, last_value, reading_count, activated_at, activated_measured_at, source_event_id, event_id, traceparent)"
                + " VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?, 1, now(), ?, ?, ?, ?)";
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, alertId);
            s.setString(2, reading.assetId());
            s.setString(3, reading.siteId());
            s.setString(4, reading.metricName());
            s.setString(5, reading.unit());
            s.setString(6, severity);
            s.setDouble(7, threshold);
            s.setDouble(8, reading.value());
            s.setDouble(9, reading.value());
            s.setDouble(10, reading.value());
            s.setTimestamp(11, Timestamp.from(reading.measuredAt()));
            s.setObject(12, sourceEventId);
            s.setObject(13, eventId);
            s.setString(14, traceparent);
            s.executeUpdate();
        } catch (SQLException error) {
            throw new StoreException("insert alert", error);
        }
    }

    /** Updates the active alert's running values; {@code escalateTo} non-null raises severity. */
    public void updateActiveAlert(String assetId, String metric, double value, String escalateTo, Double escalationThreshold) {
        String sql = "UPDATE alert SET last_value = ?, peak_value = GREATEST(peak_value, ?), reading_count = reading_count + 1,"
                + " severity = COALESCE(?, severity), threshold = COALESCE(?, threshold),"
                + " escalated_at = CASE WHEN ? IS NULL THEN escalated_at ELSE now() END"
                + " WHERE asset_id = ? AND metric_name = ? AND status = 'ACTIVE'";
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setDouble(1, value);
            s.setDouble(2, value);
            s.setString(3, escalateTo);
            s.setObject(4, escalationThreshold);
            s.setString(5, escalateTo);
            s.setString(6, assetId);
            s.setString(7, metric);
            s.executeUpdate();
        } catch (SQLException error) {
            throw new StoreException("update alert", error);
        }
    }

    public void clearAlert(String assetId, String metric, double value) {
        update("UPDATE alert SET status = 'CLEARED', cleared_at = now(), last_value = ?, reading_count = reading_count + 1"
                + " WHERE asset_id = ? AND metric_name = ? AND status = 'ACTIVE'", "clear alert", value, assetId, metric);
    }

    public void insertOutbox(UUID eventId, String topic, String key, byte[] body) {
        String sql = "INSERT INTO outbox (event_id, topic, record_key, body) VALUES (?, ?, ?, ?)";
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setObject(1, eventId);
            s.setString(2, topic);
            s.setString(3, key);
            s.setBytes(4, body);
            s.executeUpdate();
        } catch (SQLException error) {
            throw new StoreException("insert outbox", error);
        }
    }

    /** Keyset page ordered by (measured_at, metric_name). */
    public List<ReadingView> readings(String assetId, Instant from, Instant to, String metric, Instant afterTime,
            String afterMetric, int limit) {
        StringBuilder sql = new StringBuilder("SELECT asset_id, site_id, metric_name, measured_at, value, unit, source_event_id,"
                + " traceparent FROM reading WHERE asset_id = ? AND measured_at >= ? AND measured_at < ?");
        if (metric != null) {
            sql.append(" AND metric_name = ?");
        }
        if (afterTime != null) {
            sql.append(" AND (measured_at, metric_name) > (?, ?)");
        }
        sql.append(" ORDER BY measured_at, metric_name LIMIT ?");
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(sql.toString())) {
            int i = 1;
            s.setString(i++, assetId);
            s.setTimestamp(i++, Timestamp.from(from));
            s.setTimestamp(i++, Timestamp.from(to));
            if (metric != null) {
                s.setString(i++, metric);
            }
            if (afterTime != null) {
                s.setTimestamp(i++, Timestamp.from(afterTime));
                s.setString(i++, afterMetric);
            }
            s.setInt(i, limit);
            List<ReadingView> page = new ArrayList<>();
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    page.add(new ReadingView(r.getString(1), r.getString(2), r.getString(3), r.getTimestamp(4).toInstant(),
                            r.getDouble(5), r.getString(6), r.getObject(7, UUID.class), r.getString(8)));
                }
            }
            return page;
        } catch (SQLException error) {
            throw new StoreException("query readings", error);
        }
    }

    /** Newest first, keyset on (activated_at, alert_id) descending. */
    public List<AlertView> alerts(String status, String assetId, Instant beforeTime, String beforeId, int limit) {
        StringBuilder sql = new StringBuilder("SELECT alert_id, asset_id, site_id, metric_name, unit, status, severity, threshold,"
                + " first_value, peak_value, last_value, reading_count, activated_at, activated_measured_at, escalated_at,"
                + " cleared_at, source_event_id, event_id, traceparent FROM alert WHERE true");
        if (status != null) {
            sql.append(" AND status = ?");
        }
        if (assetId != null) {
            sql.append(" AND asset_id = ?");
        }
        if (beforeTime != null) {
            sql.append(" AND (activated_at, alert_id) < (?, ?)");
        }
        sql.append(" ORDER BY activated_at DESC, alert_id DESC LIMIT ?");
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(sql.toString())) {
            int i = 1;
            if (status != null) {
                s.setString(i++, status);
            }
            if (assetId != null) {
                s.setString(i++, assetId);
            }
            if (beforeTime != null) {
                s.setTimestamp(i++, Timestamp.from(beforeTime));
                s.setString(i++, beforeId);
            }
            s.setInt(i, limit);
            List<AlertView> page = new ArrayList<>();
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    page.add(new AlertView(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5),
                            r.getString(6), r.getString(7), r.getDouble(8), r.getDouble(9), r.getDouble(10), r.getDouble(11),
                            r.getInt(12), instant(r.getTimestamp(13)), instant(r.getTimestamp(14)), instant(r.getTimestamp(15)),
                            instant(r.getTimestamp(16)), r.getObject(17, UUID.class), r.getObject(18, UUID.class), r.getString(19)));
                }
            }
            return page;
        } catch (SQLException error) {
            throw new StoreException("query alerts", error);
        }
    }

    public long count(String sql) {
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(sql); ResultSet r = s.executeQuery()) {
            r.next();
            return r.getLong(1);
        } catch (SQLException error) {
            throw new StoreException("count", error);
        }
    }

    public int update(String sql, String operation, Object... args) {
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                if (args[i] instanceof Instant instant) {
                    s.setTimestamp(i + 1, Timestamp.from(instant));
                } else {
                    s.setObject(i + 1, args[i]);
                }
            }
            return s.executeUpdate();
        } catch (SQLException error) {
            throw new StoreException(operation, error);
        }
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    public record StoredState(AlertState state, Instant lastMeasuredAt) {
    }
}
