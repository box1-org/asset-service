-- Edge site status (box1-docs#130) and each site's latest reading time (box1-docs#128). Additive only: V1 is unchanged.

-- The latest status each site reported with PUT /api/v1/sites/{siteId}/status. One row per site; a newer report
-- replaces it. received_at is asset-service's clock when it stored the report (the contract's lastSeenAt); reported_at
-- is the site's own clock and is informational. client_id is the token's azp that wrote the row.
CREATE TABLE site_status (
    site_id                   text        PRIMARY KEY CHECK (site_id ~ '^site_[A-Za-z0-9_-]{1,64}$'),
    reported_at               timestamptz NOT NULL,
    received_at               timestamptz NOT NULL,
    client_id                 text        NOT NULL,
    buffer_depth              bigint      NOT NULL CHECK (buffer_depth >= 0),
    site_event_backlog        bigint      NOT NULL CHECK (site_event_backlog >= 0),
    buffer_oldest_age_seconds bigint      NOT NULL CHECK (buffer_oldest_age_seconds >= 0),
    readings_dropped          bigint      NOT NULL CHECK (readings_dropped >= 0),
    booted_image_digest       text        CHECK (booted_image_digest ~ '^sha256:[a-f0-9]{64}$'),
    staged_image_digest       text        CHECK (staged_image_digest ~ '^sha256:[a-f0-9]{64}$'),
    rollback_image_digest     text        CHECK (rollback_image_digest ~ '^sha256:[a-f0-9]{64}$'),
    greenboot_status          text        NOT NULL CHECK (greenboot_status IN ('GREEN', 'RED', 'UNKNOWN')),
    bundle_digest             text        CHECK (bundle_digest ~ '^sha256:[a-f0-9]{64}$'),
    app_versions              jsonb       NOT NULL DEFAULT '{}'::jsonb
);

-- The newest measured_at stored for each site, maintained by the reading projector with GREATEST in the same
-- transaction as the reading, capped at the time it was stored so a site clock running ahead cannot hide staleness.
-- A table rather than max(measured_at) over reading: reading has no site index, adding one to a table of up to
-- 2,000,000 rows would lock writes during this migration, and the metric must keep growing after retention deletes a
-- silent site's last readings. One row per distinct siteId, never pruned.
CREATE TABLE site_last_reading (
    site_id          text        PRIMARY KEY,
    last_measured_at timestamptz NOT NULL
);

-- Seed from readings already stored, so the first scrape after the upgrade is not "no readings" for every site.
INSERT INTO site_last_reading (site_id, last_measured_at)
SELECT site_id, LEAST(max(measured_at), now()) FROM reading GROUP BY site_id;
