# Architecture

The Telemetry service owns sensor readings, threshold alerts and ingestion control
(box1-ops-contracts `01_DOMAIN_CONTRACTS.md`). It never owns order or fulfillment decisions.

## Data flow

```text
HTTP POST /api/v1/telemetry/readings ─┐
Load and chaos runs (in process) ─────┤
                                      ▼
            IngestionPipeline: size → parse → shared rate limit → validate
                                      │
                       bounded producer (≤ queue capacity in flight)
                                      ▼
            Kafka box1.telemetry.events.v1   (key = assetId, 6 partitions)
                                      │  consumer group asset-service
                                      ▼
   ReadingProjector, one transaction: reading ─ last seen ─ alert state ─ alert ─ outbox
                                      │
                       OutboxRelay ─► equipment.threshold-exceeded.v1 (same topic, key assetId)
```

- **One admission path.** API, load and chaos readings all pass the same size check, JSON
  parse, shared token bucket and validator. Generated invalid readings are rejected there, never
  published.
- **Kafka is the system of record for arrival order.** The read model is a projection of the
  topic, which makes consumer lag a meaningful operational signal.
- **Acknowledged means durable in Kafka.** The API returns `202` only after the broker
  acknowledges. A `503` (backpressure, timeout or delivery failure) is safe to retry because
  storage deduplicates on `(assetId, metricName, measuredAt)`.
- **Bounded memory.** At most `asset.ingest.queue-capacity` readings are in flight, counting
  queued and sent-but-unacknowledged. Beyond that, ingestion answers `503` and the load
  generator counts a rejection. A broker outage therefore never grows memory.
- **No distributed transaction.** Alerts and their events commit together through the outbox;
  readings and Kafka are joined by idempotent storage, not by a transaction.

## Edge sites

Two edge-site VMs (`site-west` is `site_ds9`, `site-east` is `site_spacedock`; box1-docs#126) publish
ordinary `equipment.reading.v1` events to `box1.telemetry.events.v1` with `causationId` `ingest:site` and the
contract's derived `eventId`, so the consumer projects them like any other reading (box1-docs#128). Each site also
sends a heartbeat about every 15 s:

```text
site gateway ──PUT /api/v1/sites/{siteId}/status (HTTPS, client-credentials token)──► SitesResource
                                                                                         │ site_status (one row per site)
ReadingProjector ── site_last_reading (GREATEST measuredAt per site) ────────────────────┤
                                                                                         ▼
          GET /api/v1/sites[/{siteId}] (state derived at read time)      SiteGauges ─► /q/metrics ─► Box1SiteStale
```

- **Identity.** Only a token issued to a configured site client (`asset.site-clients`) with client role
  `site-reporter` and a `siteId` claim equal to the path may write; otherwise `403`. `ClientAccess` strips every
  other role from site tokens, so a site can read nothing (box1-docs#130, SITE.md).
- **Latest status only.** A report replaces the site's row; `lastSeenAt` is asset-service's clock when it stored it,
  never the site's `reportedAt`, so a skewed site clock cannot make a site look online. No history is kept: failed
  heartbeats are not replayed by the site, and the state needs only the latest row.
- **State at read time.** `OFFLINE` after more than `asset.sites.offline-after` (90 s) without a status;
  otherwise `DEGRADED` when buffer depth ≥ 200, the oldest buffered row ≥ 5 min old, or greenboot `RED`;
  otherwise `ONLINE`. The same derivation feeds the `asset_site_state` metric.
- **Last reading.** `site_last_reading` holds the newest `measuredAt` per site, moved only forward (`GREATEST`)
  in the projection transaction and capped at the service clock, so a late or replayed older reading, or a site
  clock running ahead, cannot make a silent site look fresh.
- **Deduplication.** A reading whose natural key `(assetId, metricName, measuredAt)` is already stored returns
  before the threshold engine: a replayed site backlog neither stores a second copy nor re-evaluates, re-counts or
  re-activates an alert. No separate `eventId` check is needed, because the contract makes `eventId` a pure
  function of that key; the natural key is the stricter of the two. A record whose `eventId` does not follow the
  derivation is still deduplicated on the key and counted in `asset_consume_event_id_mismatch_total`.
- **Generator guard.** The in-process load and chaos generator emits only `site_load_NN`. Runs that would emit a
  site in `asset.control.reserved-sites` (`site_ds9`, `site_spacedock`) are refused with `422 RESERVED_SITE` on
  arm, preview and start, and the pipeline rejects such load or chaos readings (`RESERVED_SITE`) as a last line.

## Validation

| Check | Rule |
|---|---|
| Body | JSON object, at most 4 KiB, no duplicate keys, no unknown fields |
| IDs | `asset_…` and `site_…`, 1 to 64 characters after the prefix |
| Metric and unit | `temperature` (C, or F converted), `vibration` (mm/s), `pressure` (bar), `power` (kW) |
| Value | finite and within the metric's bounds |
| `measuredAt` | RFC 3339 UTC, at most 5 minutes in the future, not older than reading retention |
| Rate | shared 100 readings/s (compiled maximum 200); load and chaos together at most half of it, each run at most its own cap including bursts |

## Alerts

Per metric thresholds with site and asset overrides (`asset.threshold-overrides."<siteId|assetId>.<metric>".*`).

| Metric | Warning | Critical | Clear below |
|---|---|---|---|
| temperature | 80 | 95 | 75 |
| vibration | 7.1 | 11.2 | 6.0 |
| pressure | 8.0 | 10.0 | 7.5 |
| power | 400 | 480 | 380 |

- Readings more than 5 minutes in the future are stored but not evaluated, and the evaluation
  watermark never runs ahead of the service's clock, so a skewed reading cannot suspend alerting.
- An alert activates at the third reading at or above warning since the last normal reading,
  or at once at critical. Readings between the clear level and warning neither count nor reset.
- It clears after three consecutive readings below the clear level.
- At most one active alert per asset and metric (partial unique index). Repeats update the
  alert; escalation to critical updates it in place. Only activation emits an event.
- A reading older than the last evaluated reading for its asset and metric is stored but not
  evaluated, so out-of-order delivery cannot flip state backwards.

## Storage

Tables: `reading`, `asset_last_seen`, `alert_state`, `alert`, `outbox`, `control_run`,
`control_audit` (Flyway `V1__telemetry_schema.sql`), and `site_status`, `site_last_reading`
(`V2__site_status.sql`, additive). Retention runs every minute: readings older
than 24 h, a 2,000,000-row cap, cleared alerts after 30 days, audit and runs after 90 days.
`control_audit` rejects updates and deletes of rows younger than 90 days in the database.

## Load and chaos console

The page is plain HTML, CSS and JavaScript under `src/main/resources/META-INF/resources/console/`,
with no build step, no CDN and no external fonts. The mark `console/brand/box1-mark.png` is the box1 cube "1" brand
mark, unmodified: the `box1-org` GitHub organization avatar (400 x 400 PNG, SHA-256 `11ccbb2d…62ea`), supplied by the
project owner. Do not regenerate it. It calls only `/api/v1/control/*`.
**The page enforces nothing.** It renders limits it reads from the service.

- **Access.** Keycloak code flow with PKCE on the `console` OIDC tenant, chosen by path only;
  client role `chaos-operator`; tokens must be issued to `asset-service` (`azp`). State-changing
  calls need `X-Box1-Console: 1` and a JSON body; the session cookie is `SameSite=Strict` and
  `Secure`; the page is served with CSP `frame-ancestors 'none'`, `X-Frame-Options: DENY` and HSTS.
- **Deliberate change.** Arm (`POST /runs`) returns a one-time confirmation token valid for
  60 s. Start needs the token and the namespace typed exactly. Every run has a server-side rate
  cap, duration cap, TTL (duration + 30 s) and a concurrency cap of 1 (maximum 2).
- **Stop.** Stop one run, stop all, or reset (stop all and resume the producer). Nothing deletes data.
- **Seeds.** SplitMix64 with independent streams for values, chaos schedule, chaos targets and
  chaos extras; 16 hex characters. The same seed and configuration reproduce the same sequence;
  each run records a digest of its first 200 emissions, which the arm response predicts.
- **Chaos catalog** (telemetry only): burst, spike, sustained breach, flap, stale asset, clock
  skew, out of order, duplicate, invalid unit, invalid payload, oversize payload, producer pause.
- **Safety gate.** Chaos needs `ASSET_CHAOS_ENABLED=true` and the pod's own namespace (downward
  API) in `ASSET_CHAOS_NAMESPACE_ALLOWLIST`. Load-only runs are not gated.
- **Restarts.** Runs execute only in the pod that started them and heartbeat every 5 s. A pod
  that stops heartbeating has its runs marked `ABORTED` within about 20 s; nothing resumes.
- **Audit.** Arm, start, inject, stop, stop-all, reset, completion, expiry, abort and every
  refusal, with the actor from the token.

## Observability

- JSON logs in the image with `eventId`, `assetId` and `correlationId` in MDC while consuming.
- `traceparent` travels in the event envelope. The consumer continues the reading's trace, and
  the threshold event carries it, so a reading and the alert it caused share a trace ID.
  Export stays off until a collector exists (box1-docs#22).
- Metrics (`/q/metrics`): `asset_ingest_admitted_total{source}`, `asset_ingest_rejected_total{source,reason}`,
  `asset_ingest_published_total`, `asset_ingest_dropped_total`, `asset_producer_in_flight`,
  `asset_producer_paused`, `asset_consumer_lag_records`, `asset_alerts_active`,
  `asset_alerts_transitions_total`, `asset_assets_stale`, `asset_consume_dead_lettered_total{reason}`,
  `asset_consume_retries_total{kind}`, `asset_outbox_pending`, `asset_chaos_injections_total{entry}`,
  `asset_control_denied_total{code}`, `asset_consume_event_id_mismatch_total`, and per edge site
  `asset_site_heartbeat_age_seconds{siteId}`, `asset_site_last_reading_age_seconds{siteId}`,
  `asset_site_buffer_depth{siteId}`, `asset_site_state{siteId,state}`. Unmeasurable gauges report NaN, shown as
  "unknown".
