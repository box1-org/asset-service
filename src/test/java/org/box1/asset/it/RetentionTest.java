package org.box1.asset.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.box1.asset.ops.RetentionJob;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** TEL-STORE-001 and TEL-CHAOS-004: bounded reading retention, and an audit table the database protects. */
@QuarkusTest
@TestProfile(FullStack.class)
@Tag(ContainersTag.NAME)
class RetentionTest {
    @Inject
    DataSource dataSource;
    @Inject
    RetentionJob retention;

    private long count(Statement s, String sql) throws SQLException {
        try (var r = s.executeQuery(sql)) {
            r.next();
            return r.getLong(1);
        }
    }

    @Test
    void oldReadingsAreDeletedAndRecentOnesKept() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO reading (asset_id, metric_name, measured_at, site_id, value, unit, source_event_id) VALUES"
                    + " ('asset_retention_old', 'power', now() - interval '25 hours', 'site_r', 1, 'kW', gen_random_uuid()),"
                    + " ('asset_retention_new', 'power', now() - interval '1 hour', 'site_r', 1, 'kW', gen_random_uuid())");
            retention.run();
            assertEquals(0, count(s, "SELECT count(*) FROM reading WHERE asset_id = 'asset_retention_old'"), "older than 24 h deleted");
            assertEquals(1, count(s, "SELECT count(*) FROM reading WHERE asset_id = 'asset_retention_new'"), "recent reading kept");
        }
    }

    @Test
    void recentAuditRowsCannotBeChangedOrDeleted() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO control_audit (run_id, actor, action, detail) VALUES (null, 'test', 'RESET', '{}')");
            assertThrows(SQLException.class, () -> s.executeUpdate("UPDATE control_audit SET actor = 'someone-else' WHERE actor = 'test'"));
            assertThrows(SQLException.class, () -> s.executeUpdate("DELETE FROM control_audit WHERE actor = 'test'"));
            assertEquals(1, count(s, "SELECT count(*) FROM control_audit WHERE actor = 'test'"));
        }
    }
}
