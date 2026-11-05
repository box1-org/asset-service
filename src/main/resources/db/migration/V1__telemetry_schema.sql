-- asset-service owns this database (box1-ops-contracts postgres/OWNERSHIP.md). No other service reads it.

-- Readings: the projection of box1.telemetry.events.v1. The natural key deduplicates redelivery and client retries.
CREATE TABLE reading (
    asset_id        text             NOT NULL,
    metric_name     text             NOT NULL,
    measured_at     timestamptz      NOT NULL,
    site_id         text             NOT NULL,
    value           double precision NOT NULL,
    unit            text             NOT NULL,
    source_event_id uuid             NOT NULL,
    traceparent     text,
    received_at     timestamptz      NOT NULL DEFAULT now(),
    PRIMARY KEY (asset_id, metric_name, measured_at)
);
-- Keyset pagination for one asset across metrics, ordered by (measured_at, metric_name).
CREATE INDEX reading_asset_time ON reading (asset_id, measured_at, metric_name);
-- Retention deletes by age.
CREATE INDEX reading_measured_at ON reading (measured_at);

CREATE TABLE asset_last_seen (
    asset_id         text        PRIMARY KEY,
    site_id          text        NOT NULL,
    last_received_at timestamptz NOT NULL
);

-- Evaluation state per asset and metric (D-008).
CREATE TABLE alert_state (
    asset_id         text        NOT NULL,
    metric_name      text        NOT NULL,
    active           boolean     NOT NULL,
    severity         text        CHECK (severity IN ('WARNING', 'CRITICAL')),
    breach_streak    integer     NOT NULL DEFAULT 0,
    clear_streak     integer     NOT NULL DEFAULT 0,
    last_measured_at timestamptz NOT NULL,
    PRIMARY KEY (asset_id, metric_name)
);

-- One row per alert episode.
CREATE TABLE alert (
    alert_id        text             PRIMARY KEY,
    asset_id        text             NOT NULL,
    site_id         text             NOT NULL,
    metric_name     text             NOT NULL,
    unit            text             NOT NULL,
    status          text             NOT NULL CHECK (status IN ('ACTIVE', 'CLEARED')),
    severity        text             NOT NULL CHECK (severity IN ('WARNING', 'CRITICAL')),
    threshold       double precision NOT NULL,
    first_value     double precision NOT NULL,
    peak_value      double precision NOT NULL,
    last_value      double precision NOT NULL,
    reading_count   integer          NOT NULL,
    activated_at    timestamptz      NOT NULL,
    activated_measured_at timestamptz NOT NULL,
    escalated_at    timestamptz,
    cleared_at      timestamptz,
    source_event_id uuid             NOT NULL,
    event_id        uuid             NOT NULL UNIQUE,
    traceparent     text
);
-- At most one active alert per asset and metric: the deduplication rule.
CREATE UNIQUE INDEX alert_one_active ON alert (asset_id, metric_name) WHERE status = 'ACTIVE';
CREATE INDEX alert_listing ON alert (activated_at, alert_id);

-- Transactional outbox for equipment.threshold-exceeded.v1.
CREATE TABLE outbox (
    id              bigserial   PRIMARY KEY,
    event_id        uuid        NOT NULL UNIQUE,
    topic           text        NOT NULL,
    record_key      text        NOT NULL,
    body            bytea       NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    attempts        integer     NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    claimed_until   timestamptz,
    published_at    timestamptz
);
CREATE INDEX outbox_pending ON outbox (next_attempt_at) WHERE published_at IS NULL;

-- Load and chaos runs (D-007).
CREATE TABLE control_run (
    run_id          text        PRIMARY KEY,
    kind            text        NOT NULL CHECK (kind IN ('LOAD', 'CHAOS')),
    actor           text        NOT NULL,
    seed            text        NOT NULL,
    config          text        NOT NULL,
    state           text        NOT NULL CHECK (state IN ('ARMED', 'RUNNING', 'STOPPED', 'COMPLETED', 'EXPIRED', 'ABORTED')),
    namespace       text        NOT NULL,
    created_at      timestamptz NOT NULL,
    armed_until     timestamptz NOT NULL,
    confirm_hash    text        NOT NULL,
    started_at      timestamptz,
    deadline        timestamptz,
    ttl_at          timestamptz,
    stopped_at      timestamptz,
    stop_reason     text,
    owner           text        NOT NULL,
    heartbeat_at    timestamptz NOT NULL,
    sequence_digest text,
    emitted         bigint      NOT NULL DEFAULT 0,
    rejected        bigint      NOT NULL DEFAULT 0
);
CREATE INDEX control_run_open ON control_run (state) WHERE state IN ('ARMED', 'RUNNING');

-- Append-only audit. Rows cannot be changed, and only rows older than 90 days can be deleted.
CREATE TABLE control_audit (
    id      bigserial   PRIMARY KEY,
    run_id  text,
    at      timestamptz NOT NULL DEFAULT now(),
    actor   text        NOT NULL,
    action  text        NOT NULL,
    detail  text        NOT NULL
);
CREATE INDEX control_audit_run ON control_audit (run_id, id);
CREATE INDEX control_audit_at ON control_audit (at);

CREATE FUNCTION control_audit_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'control_audit is append-only';
    END IF;
    IF OLD.at > now() - interval '90 days' THEN
        RAISE EXCEPTION 'control_audit rows are kept for 90 days';
    END IF;
    RETURN OLD;
END;
$$;
CREATE TRIGGER control_audit_guard BEFORE UPDATE OR DELETE ON control_audit
    FOR EACH ROW EXECUTE FUNCTION control_audit_guard();
