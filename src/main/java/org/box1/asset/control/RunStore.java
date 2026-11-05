package org.box1.asset.control;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import jakarta.enterprise.context.ApplicationScoped;
import org.box1.asset.store.StoreException;

/** control_run and the append-only control_audit (D-007). Times come from the database clock. */
@ApplicationScoped
public class RunStore {
    private static final String COLUMNS = "run_id, kind, actor, seed, config, state, namespace, created_at, armed_until, started_at,"
            + " deadline, ttl_at, stopped_at, stop_reason, owner, heartbeat_at, sequence_digest, emitted, rejected";
    private final DataSource dataSource;

    public RunStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void insertArmed(String runId, RunKind kind, String actor, String seed, String config, String namespace,
            long armSeconds, String confirmHash, String owner) {
        exec("INSERT INTO control_run (run_id, kind, actor, seed, config, state, namespace, created_at, armed_until, confirm_hash,"
                + " owner, heartbeat_at) VALUES (?, ?, ?, ?, ?, 'ARMED', ?, now(), now() + ? * interval '1 second', ?, ?, now())",
                "arm run", runId, kind.name(), actor, seed, config, namespace, armSeconds, confirmHash, owner);
    }

    /** Serializes start, stop-all and reaping within the transaction that calls it. */
    public void lock() {
        query("SELECT pg_advisory_xact_lock(150015)", "control lock", r -> null);
    }

    public Optional<RunRecord> find(String runId) {
        List<RunRecord> rows = list("SELECT " + COLUMNS + " FROM control_run WHERE run_id = ?", runId);
        return rows.stream().findFirst();
    }

    public Optional<String> confirmHash(String runId) {
        return Optional.ofNullable(query("SELECT confirm_hash FROM control_run WHERE run_id = ?", "confirm hash",
                r -> r.next() ? r.getString(1) : null, runId));
    }

    public List<RunRecord> open() {
        return list("SELECT " + COLUMNS + " FROM control_run WHERE state IN ('ARMED', 'RUNNING') ORDER BY created_at");
    }

    public List<RunRecord> recent(int limit) {
        return list("SELECT " + COLUMNS + " FROM control_run ORDER BY created_at DESC LIMIT ?", limit);
    }

    public int countRunning() {
        return query("SELECT count(*) FROM control_run WHERE state = 'RUNNING'", "count running", r -> {
            r.next();
            return r.getInt(1);
        });
    }

    public boolean armExpired(String runId) {
        return query("SELECT armed_until < now() FROM control_run WHERE run_id = ?", "arm expiry", r -> r.next() && r.getBoolean(1), runId);
    }

    public Instant now() {
        return query("SELECT now()", "clock", r -> {
            r.next();
            return r.getTimestamp(1).toInstant();
        });
    }

    /** Returns false when the run was no longer ARMED. */
    public boolean markRunning(String runId, long durationSeconds, long ttlSeconds, String owner) {
        return exec("UPDATE control_run SET state = 'RUNNING', started_at = now(), deadline = now() + ? * interval '1 second',"
                + " ttl_at = now() + ? * interval '1 second', owner = ?, heartbeat_at = now() WHERE run_id = ? AND state = 'ARMED'",
                "start run", durationSeconds, ttlSeconds, owner, runId) == 1;
    }

    /** Moves an open run to a terminal state. Returns false when it was already terminal. */
    public boolean finish(String runId, String state, String reason) {
        return exec("UPDATE control_run SET state = ?, stopped_at = now(), stop_reason = ? WHERE run_id = ? AND state IN ('ARMED', 'RUNNING')",
                "finish run", state, reason, runId) == 1;
    }

    /** Returns false when this instance no longer owns a running row, which tells the executor to stop. */
    public boolean heartbeat(String runId, String owner, long emitted, long rejected, String digest) {
        return exec("UPDATE control_run SET heartbeat_at = now(), emitted = ?, rejected = ?, sequence_digest = COALESCE(?, sequence_digest)"
                + " WHERE run_id = ? AND owner = ? AND state = 'RUNNING'", "heartbeat", emitted, rejected, digest, runId, owner) == 1;
    }

    public void progress(String runId, long emitted, long rejected, String digest) {
        exec("UPDATE control_run SET emitted = ?, rejected = ?, sequence_digest = COALESCE(?, sequence_digest) WHERE run_id = ?",
                "progress", emitted, rejected, digest, runId);
    }

    /** Running rows whose owner stopped heartbeating, armed rows past their arm window, running rows past their TTL. */
    public List<RunRecord> staleHeartbeats(long staleSeconds) {
        return list("SELECT " + COLUMNS + " FROM control_run WHERE state = 'RUNNING' AND heartbeat_at < now() - ? * interval '1 second'",
                staleSeconds);
    }

    public List<RunRecord> expiredArms() {
        return list("SELECT " + COLUMNS + " FROM control_run WHERE state = 'ARMED' AND armed_until < now()");
    }

    public List<RunRecord> pastTtl() {
        return list("SELECT " + COLUMNS + " FROM control_run WHERE state = 'RUNNING' AND ttl_at < now()");
    }

    public void audit(String runId, String actor, String action, String detail) {
        exec("INSERT INTO control_audit (run_id, actor, action, detail) VALUES (?, ?, ?, ?)", "audit", runId, actor, action, detail);
    }

    public List<AuditEntry> auditFor(String runId) {
        return query("SELECT id, run_id, at, actor, action, detail FROM control_audit WHERE run_id = ? ORDER BY id", "read audit",
                RunStore::audits, runId);
    }

    public List<AuditEntry> recentAudit(int limit) {
        return query("SELECT id, run_id, at, actor, action, detail FROM control_audit ORDER BY id DESC LIMIT ?", "read audit",
                RunStore::audits, limit);
    }

    private static List<AuditEntry> audits(ResultSet r) throws SQLException {
        List<AuditEntry> entries = new ArrayList<>();
        while (r.next()) {
            entries.add(new AuditEntry(r.getLong(1), r.getString(2), r.getTimestamp(3).toInstant(), r.getString(4), r.getString(5),
                    r.getString(6)));
        }
        return entries;
    }

    private List<RunRecord> list(String sql, Object... args) {
        return query(sql, "read runs", r -> {
            List<RunRecord> rows = new ArrayList<>();
            while (r.next()) {
                rows.add(new RunRecord(r.getString(1), RunKind.valueOf(r.getString(2)), r.getString(3), r.getString(4), r.getString(5),
                        r.getString(6), r.getString(7), instant(r, 8), instant(r, 9), instant(r, 10), instant(r, 11), instant(r, 12),
                        instant(r, 13), r.getString(14), r.getString(15), instant(r, 16), r.getString(17), r.getLong(18), r.getLong(19)));
            }
            return rows;
        }, args);
    }

    private static Instant instant(ResultSet r, int column) throws SQLException {
        Timestamp timestamp = r.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private int exec(String sql, String operation, Object... args) {
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            bind(s, args);
            return s.executeUpdate();
        } catch (SQLException error) {
            throw new StoreException(operation, error);
        }
    }

    private <T> T query(String sql, String operation, Rows<T> reader, Object... args) {
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            bind(s, args);
            try (ResultSet r = s.executeQuery()) {
                return reader.read(r);
            }
        } catch (SQLException error) {
            throw new StoreException(operation, error);
        }
    }

    private static void bind(PreparedStatement s, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            s.setObject(i + 1, args[i]);
        }
    }

    @FunctionalInterface
    interface Rows<T> {
        T read(ResultSet r) throws SQLException;
    }
}
