package org.box1.asset.store;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.sql.DataSource;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import org.box1.asset.site.SiteReport;
import org.box1.asset.site.StoredSite;

/** Each edge site's latest status (V2 {@code site_status}) joined with its latest reading time. */
@ApplicationScoped
public class SiteStore {
    private static final TypeReference<Map<String, String>> VERSIONS = new TypeReference<>() {
    };
    private static final String SELECT = "SELECT s.site_id, s.reported_at, s.received_at, s.client_id, s.buffer_depth,"
            + " s.site_event_backlog, s.buffer_oldest_age_seconds, s.readings_dropped, s.booted_image_digest,"
            + " s.staged_image_digest, s.rollback_image_digest, s.greenboot_status, s.bundle_digest, s.app_versions::text,"
            + " l.last_measured_at FROM site_status s LEFT JOIN site_last_reading l ON l.site_id = s.site_id";

    private final DataSource dataSource;
    private final ObjectMapper mapper = new ObjectMapper();

    public SiteStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Replaces the site's latest status. The latest received report wins; the site's own clock never orders them. */
    public void upsert(String siteId, SiteReport report, Instant receivedAt, String clientId) {
        String sql = "INSERT INTO site_status (site_id, reported_at, received_at, client_id, buffer_depth, site_event_backlog,"
                + " buffer_oldest_age_seconds, readings_dropped, booted_image_digest, staged_image_digest, rollback_image_digest,"
                + " greenboot_status, bundle_digest, app_versions) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))"
                + " ON CONFLICT (site_id) DO UPDATE SET reported_at = EXCLUDED.reported_at, received_at = EXCLUDED.received_at,"
                + " client_id = EXCLUDED.client_id, buffer_depth = EXCLUDED.buffer_depth,"
                + " site_event_backlog = EXCLUDED.site_event_backlog, buffer_oldest_age_seconds = EXCLUDED.buffer_oldest_age_seconds,"
                + " readings_dropped = EXCLUDED.readings_dropped, booted_image_digest = EXCLUDED.booted_image_digest,"
                + " staged_image_digest = EXCLUDED.staged_image_digest, rollback_image_digest = EXCLUDED.rollback_image_digest,"
                + " greenboot_status = EXCLUDED.greenboot_status, bundle_digest = EXCLUDED.bundle_digest,"
                + " app_versions = EXCLUDED.app_versions";
        String versions;
        try {
            versions = mapper.writeValueAsString(report.appVersions());
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("appVersions is not serializable", error);
        }
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, siteId);
            s.setTimestamp(2, Timestamp.from(report.reportedAt()));
            s.setTimestamp(3, Timestamp.from(receivedAt));
            s.setString(4, clientId);
            s.setLong(5, report.bufferDepth());
            s.setLong(6, report.siteEventBacklog());
            s.setLong(7, report.bufferOldestAgeSeconds());
            s.setLong(8, report.readingsDropped());
            s.setString(9, report.bootedImageDigest());
            s.setString(10, report.stagedImageDigest());
            s.setString(11, report.rollbackImageDigest());
            s.setString(12, report.greenbootStatus());
            s.setString(13, report.bundleDigest());
            s.setString(14, versions);
            s.executeUpdate();
        } catch (SQLException error) {
            throw new StoreException("upsert site status", error);
        }
    }

    public Optional<StoredSite> find(String siteId) {
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(SELECT + " WHERE s.site_id = ?")) {
            s.setString(1, siteId);
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? Optional.of(row(r)) : Optional.empty();
            }
        } catch (SQLException error) {
            throw new StoreException("read site status", error);
        }
    }

    /** Every site that has reported a status, ordered by siteId. */
    public List<StoredSite> list() {
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(SELECT + " ORDER BY s.site_id");
                ResultSet r = s.executeQuery()) {
            List<StoredSite> sites = new ArrayList<>();
            while (r.next()) {
                sites.add(row(r));
            }
            return sites;
        } catch (SQLException error) {
            throw new StoreException("list site status", error);
        }
    }

    private StoredSite row(ResultSet r) throws SQLException {
        Map<String, String> versions;
        try {
            versions = mapper.readValue(r.getString(14), VERSIONS);
        } catch (IOException error) {
            throw new SQLException("app_versions is not a JSON object of strings", error);
        }
        SiteReport report = new SiteReport(r.getTimestamp(2).toInstant(), r.getLong(5), r.getLong(6), r.getLong(7), r.getLong(8),
                r.getString(9), r.getString(10), r.getString(11), r.getString(12), r.getString(13), versions);
        Timestamp lastReading = r.getTimestamp(15);
        return new StoredSite(r.getString(1), report, r.getTimestamp(3).toInstant(), r.getString(4),
                lastReading == null ? null : lastReading.toInstant());
    }
}
