# Runbook

## First deployment order

Each step is a separate, approved action.

1. **Dead-letter topic.** Merge the `box1-gitops` change adding `box1.telemetry.dlq.v1`, then
   sync that one KafkaTopic in Application `box1-kafka` (it has no automated sync). Confirm the
   topic is `Ready`. Without it, the broker's auto-create would make the topic on the first
   poison record with broker defaults (1 partition, 7 days), and Strimzi would then have to
   reconcile it.
2. **Keycloak client.** Merge the `box1-gitops` change adding KeycloakClient `asset-service`.
   Application `keycloak` syncs it automatically. Assign client roles in the Keycloak admin
   console (Users → user → Role mapping → client `asset-service`): `chaos-operator` for console
   users, `telemetry-reader` for people who read readings and alerts through the API. Do not
   assign `telemetry-writer` unless someone must post readings through the Route. The API
   accepts only tokens issued to `asset-service` (`azp`), not tokens from other clients.
3. **Database Secret.** Create it once, out of band. It is deliberately not in Git
   (box1-docs#41):

   ```sh
   oc -n asset-service-dev create secret generic asset-service-postgres \
     --from-literal=POSTGRESQL_DATABASE=asset_service \
     --from-literal=POSTGRESQL_USER=asset_service \
     --from-literal=POSTGRESQL_PASSWORD="$(openssl rand -base64 30)"
   ```

   Never record the value. The RHEL PostgreSQL image applies `POSTGRESQL_PASSWORD` at every
   start, so rotation is: update the Secret, restart the database pod, then restart the
   application pod.
4. **Workloads.** Merge the `box1-workloads-dev` change. Argo CD creates the database and
   updates the Deployment.
5. **Service.** Merge the service PR. The release builds, gates, promotes and smoke-tests the
   image.

**Preflight before step 5.** Check all three:

```sh
oc -n asset-service-dev get secret asset-service-postgres -o name   # exists (do not print it)
oc -n asset-service-dev rollout status deploy/asset-service-postgres --timeout=120s
oc -n openshift-gitops get application asset-service -o jsonpath='{.status.health.status}'   # Healthy
```

If the Secret is missing when step 4 merges, both new pods wait in `CreateContainerConfigError`.
The old application pod keeps serving (`maxUnavailable: 0`), but the Deployment reports
`Progressing=False` after 300 s, Argo shows the application unhealthy, and any release smoke
fails. Create the Secret and the pods start on their own.

## Configuration

Set these as Deployment `env` entries in `box1-workloads-dev`, not as ConfigMap literals. A
changed literal orphans the hashed ConfigMap, and the next promote waits on `OutOfSync`
(box1-docs#42).

| Variable | Default | Meaning |
|---|---|---|
| `ASSET_CHAOS_ENABLED` | `false` | Chaos flag; both this and the allowlist are needed |
| `ASSET_CHAOS_NAMESPACE_ALLOWLIST` | empty | Comma-separated namespaces where chaos may run |
| `POD_NAMESPACE` | downward API | The namespace checked against the allowlist |
| `ASSET_ENVIRONMENT` | `unknown` | Shown in the console banner |
| `ASSET_INGEST_MAX_RATE` | `100` | Shared readings/s for API, load and chaos (maximum 200) |
| `ASSET_PRODUCER_QUEUE` | `2000` | Readings in flight before backpressure (maximum 5000) |
| `ASSET_READING_RETENTION` | `PT24H` | Reading age limit; also the oldest accepted `measuredAt` |
| `ASSET_READING_MAX_ROWS` | `2000000` | Hard row cap |
| `ASSET_CONTROL_ENABLED` | `false` | Load and chaos run control; the dev Deployment sets `true` |
| `ASSET_OIDC_URL` | box1 realm | Issuer URL including RH-SSO's `/auth` path |
| `ASSET_READ_CLIENTS` | `operations-console` | Clients whose tokens keep only `telemetry-reader` |
| `ASSET_SITE_CLIENTS` | `site-west,site-east` | Edge site clients; their tokens keep only `site-reporter` |
| `ASSET_SITE_OFFLINE_AFTER` | `PT90S` | No status for longer than this: `OFFLINE` (at most `PT1H`) |
| `ASSET_SITE_DEGRADED_BUFFER_DEPTH` | `200` | Buffer depth at or above this: `DEGRADED` |
| `ASSET_SITE_DEGRADED_BUFFER_AGE` | `PT5M` | Oldest buffered row at least this old: `DEGRADED` (at most `P1D`) |
| `ASSET_RESERVED_SITES` | `site_ds9,site_spacedock` | Real edge sites that load and chaos runs may never emit |
| `QUARKUS_DATASOURCE_*` | — | JDBC URL, and user and password from Secret `asset-service-postgres` |

Run limits (`asset.control.*`) can only be lowered. Compiled maximums are 50 readings/s per
run, 5 sites, 20 assets per site, 5 readings/s per asset, 15 minutes, 2 concurrent runs, a 30 s
producer pause and a 5x burst. The service refuses to start if configuration exceeds them.

## Enabling chaos in a namespace

1. The namespace must be named by the user, per `06_RESOURCE_AND_SECURITY_BOUNDARIES.md`.
2. In `workloads/asset-service/deployment.yaml`, set `ASSET_CHAOS_ENABLED=true` and
   `ASSET_CHAOS_NAMESPACE_ALLOWLIST=<namespace>`. Merge through a PR.
3. Confirm in the console banner: **Chaos gate: OPEN**. To disable chaos, revert the change.
   Load-only runs stay available.

## Console operations

- **Stop everything:** the **Stop all runs** button, or `POST /api/v1/control/stop-all` with a
  console session. **Reset** also resumes the producer. Neither deletes data.
- **A run shows `RUNNING?` (no heartbeat):** its pod stopped. The reaper marks it `ABORTED`
  within about 20 s and never resumes it.
- **`UNKNOWN` in the banner:** the page has had no confirmed state for 15 s. Check the pod, the
  database and the session. Do not assume runs stopped; use **Stop all runs** once the service
  answers.
- **Audit:** `GET /api/v1/control/runs/{runId}` returns a run with its full audit trail. The
  `control_audit` table is append-only for 90 days.

## Kafka

- **Consumer group** `asset-service`. On first start it reads from `latest`: readings are
  perishable, and replaying another producer's backlog would raise alerts for stale data. After
  that, committed offsets carry across restarts.
- **Deliberate replay** of a recent window. Offsets of an active group cannot be reset, and Argo
  CD reverts a manual scale-down, so stop consumption through Git:
  1. PR to `box1-workloads-dev`: add env `MP_MESSAGING_INCOMING_TELEMETRY_IN_ENABLED=false` to
     the Deployment. Merge it and wait for the rollout. Ingestion continues; projection stops.
  2. Reset from inside the broker pod. `--by-duration` avoids time-zone ambiguity:

     ```sh
     oc -n box1-kafka exec box1-kafka-box1-kafka-0 -- bin/kafka-consumer-groups.sh \
       --bootstrap-server localhost:9092 --group asset-service --topic box1.telemetry.events.v1 \
       --reset-offsets --by-duration PT2H --execute
     ```
  3. Revert the PR. The consumer resumes from the reset position.

  Replays deduplicate on the natural key, so a replayed reading is not stored twice. Readings
  older than the reading retention (24 h) are skipped, not dead-lettered. A reading that retention
  already deleted is stored again but not re-evaluated, because alert state ignores readings at or
  before the last evaluated one. When two readings share asset, metric and instant with different
  values, **the first one stored wins**; the later one is counted in
  `asset_consume_duplicates_total{kind="different_value"}` and logged.
- **Lag:** `asset_consumer_lag_records` (NaN when the broker cannot be asked).
- **Dead letters** on `box1.telemetry.dlq.v1`, unchanged, with header `dead-letter-reason`:

  | Reason | Meaning |
  |---|---|
  | `NOT_JSON`, `NULL_VALUE` | Not JSON, or an empty record |
  | `ENVELOPE_INVALID` | The event envelope is invalid |
  | `PAYLOAD_INVALID` | The payload is missing or invalid |
  | `POLICY_<reason>` | The service's validation rejected the reading |
  | `PROJECTION_FAILED` | Five non-transient processing failures; see the ERROR log for the event ID |

  To replay one after a fix, copy the record back to `box1.telemetry.events.v1` with the same
  key, for example with `kafka-console-consumer.sh` on the DLQ (`--max-messages 1`,
  `--property print.key=true`), piped into `kafka-console-producer.sh --property parse.key=true`
  in the broker pod. Readings carry a deterministic `eventId` derived from their natural key, so a
  replay is a duplicate, not a new event.
- **Database outage:** the consumer retries with backoff up to 30 s. One ordered worker processes
  the channel, so all partitions wait. It never dead-letters valid readings for a transient
  database error. Lag grows and readiness goes DOWN; both recover by themselves. Running load
  and chaos runs stop themselves once heartbeats have failed for 20 s, because neither the console
  nor the reaper can reach them then.
- **Stalled projection:** if one record has been retried for more than 10 minutes
  (`box1.projection.stall-limit`), readiness check `projection` goes DOWN. The record is not
  dead-lettered automatically. Read the ERROR/WARN log for its event ID and cause.
- **Broker outage:** `asset_producer_in_flight` rises to the queue capacity, then the API
  answers `503 BACKPRESSURE` with `Retry-After`. Readings handed to Kafka but not acknowledged
  within 60 s are counted in `asset_ingest_dropped_total` and logged with their `eventId`. Their
  clients never received a 202.

## Edge site status

Edge sites (box1-docs#126) report a heartbeat about every 15 s with `PUT /api/v1/sites/{siteId}/status`
([box1-docs#130](https://github.com/box1-org/box1-docs/issues/130); contract in box1-ops-contracts `SITE.md` and
`openapi/asset-service.yaml`). Readers list them with `GET /api/v1/sites` or read one with
`GET /api/v1/sites/{siteId}`.

**Who may write.** Only a token issued to a client in `ASSET_SITE_CLIENTS` (`azp` `site-west` or `site-east`) with
client role `site-reporter` on `asset-service` and a `siteId` claim equal to the path `siteId`. Anything else is
`403 SITE_MISMATCH` (or `403 FORBIDDEN` without the role); no token is `401`; a client not configured anywhere is
`401`. Site tokens keep only `site-reporter`, so `GET /api/v1/alerts`, readings, sites and ingestion all answer
`403` to them. The Keycloak clients, their audience mapper and the hardcoded `siteId` claim live in `box1-gitops`.

**Reading a site's state.** With a reader token (`telemetry-reader`, `telemetry-writer` or `chaos-operator`; the
operations console's client has `telemetry-reader`):

```sh
curl -sH "Authorization: Bearer $TOKEN" https://asset-service-dev.apps.ocp.box1.org/api/v1/sites/site_ds9 | jq
```

| Field | Meaning |
|---|---|
| `state`, `stateReasons` | Derived when read, from the thresholds below; reasons are empty when `ONLINE` |
| `lastSeenAt`, `lastSeenAgeSeconds` | asset-service's clock when it stored the latest status, and its age now |
| `reportedAt` | The site's own clock when it sent it (informational; a skewed site clock does not change the state) |
| `lastReadingAt` | `measuredAt` of the site's newest stored reading, capped at the time it was stored; `null` when none |
| `bufferDepth`, `bufferOldestAgeSeconds`, `siteEventBacklog`, `readingsDropped` | The site outbox as last reported |
| `bootedImageDigest`, `stagedImageDigest`, `rollbackImageDigest`, `greenbootStatus`, `bundleDigest`, `appVersions` | The site's OS image and application versions as last reported |

**Thresholds** (defaults, configurable through the variables above):

| State | Condition | Reason |
|---|---|---|
| `OFFLINE` | No status received for more than 90 s (exactly 90 s is still online) | `HEARTBEAT_STALE` only |
| `DEGRADED` | Online, and buffer depth ≥ 200 | `BUFFER_DEPTH` |
| `DEGRADED` | Online, and the oldest buffered row ≥ 300 s old | `BUFFER_AGE` |
| `DEGRADED` | Online, and greenboot `RED` (`UNKNOWN` is not degraded) | `GREENBOOT_RED` |
| `ONLINE` | Otherwise | none |

A site appears only after its first status. Only the latest status is kept; a newer report always replaces it
(the latest received wins, whatever its `reportedAt`). A decommissioned site stays listed, `OFFLINE`; remove its row
by hand (`DELETE FROM site_status WHERE site_id = ...`, approved) if it should disappear.

**Metrics** (`/q/metrics`, one series per site in `site_status`; the database is read every 10 s, ages are computed
from the service clock at scrape time, so they keep growing while a site is silent):

| Metric | Labels | Value |
|---|---|---|
| `asset_site_heartbeat_age_seconds` | `siteId` | Seconds since the latest status was received |
| `asset_site_last_reading_age_seconds` | `siteId` | Seconds since `lastReadingAt`; `+Inf` when the site has no stored reading |
| `asset_site_buffer_depth` | `siteId` | `bufferDepth` from the latest status |
| `asset_site_state` | `siteId`, `state` (`ONLINE`, `DEGRADED`, `OFFLINE`) | `1` for the current state, else `0` |
| `asset_consume_event_id_mismatch_total` | none | Readings whose `eventId` does not follow the contract's derivation (deduplication is unaffected) |

All four site gauges read `NaN` while the database cannot be read.

**Stale-site alert.** `Box1SiteStale` (PrometheusRule `asset-service-availability` in
`box1-workloads-dev/workloads/asset-service/prometheusrule.yaml`) fires per `siteId` when both
`asset_site_heartbeat_age_seconds` and `asset_site_last_reading_age_seconds` exceed 120 s for 1 minute, and clears
when either resumes. With the 30 s scrape and 30 s evaluation intervals it fires 180 to 240 s after the site's last
heartbeat (about 165 to 240 s after a NIC detach, given the 15 s heartbeat), and resolves within about 70 s of the
next heartbeat (10 s gauge refresh, 30 s scrape, 30 s evaluation). The console shows the site `OFFLINE` from 90 s,
before the alert. It does not fire for a site that has never reported a status (no series), while the database is
unreadable (`NaN`), or while asset-service is not scraped (`Box1ServiceDown` covers that).

**Readings from sites** are ordinary `equipment.reading.v1` records with `causationId` `ingest:site`. A replayed
site backlog deduplicates on `(assetId, metricName, measuredAt)` before the threshold engine runs, so it stores no
second copy and raises or re-counts no alert. `asset_consume_duplicates_total` counts the replayed records.

**Generator guard.** Load and chaos runs only emit `site_load_NN`. A run that would emit a site in
`ASSET_RESERVED_SITES` is refused at arm, preview and start with `422 RESERVED_SITE` (audited), and the ingestion
pipeline rejects any load or chaos reading for such a site
(`asset_ingest_rejected_total{reason="reserved_site"}`). API readings are not affected.

**Troubleshooting.**

- `403 SITE_MISMATCH` from a site: decode its token (without logging it) and check `azp`, the `siteId` claim and
  `resource_access.asset-service.roles`; the path must use the claim's `siteId`.
- `401` from a site: the client is not in `ASSET_SITE_CLIENTS`, the token lacks audience `asset-service`, or it is
  expired.
- A site stays `DEGRADED` with `BUFFER_DEPTH` or `BUFFER_AGE`: its forwarder cannot publish to Kafka; check the
  site's connectivity and `asset_consumer_lag_records`.

## Backup and restore

The PVC is marked so Argo CD never prunes or deletes it. To take a dump (keep it off Git):

```sh
oc -n asset-service-dev exec deploy/asset-service-postgres -- sh -c 'pg_dump -Fc -U "$POSTGRESQL_USER" "$POSTGRESQL_DATABASE"' > asset-service.dump
```

Restore into an empty database with `pg_restore -d asset_service`. Restore was not exercised
by the run that wrote this runbook. Telemetry is perishable (24 h retention); the audit trail is
the part worth keeping.

## Known limits

- **Sessions end on restart.** The console's session encryption key is generated per process
  (the client is public, so there is no secret to derive it from). Every restart or rollout signs
  operators out. Keep one replica; more than one would break login. A Secret-backed key needs
  approval.
- **The audit guard protects against application bugs, not against the database owner role**,
  which also runs migrations and could drop the triggers. A separate migration owner is
  box1-docs#41 territory.
- **Seed replay reproduces what a run generated, not what was ingested.** Rate limits and
  backpressure can refuse different readings in two runs with the same seed.

## Rollback

Revert the promotion commit in `box1-workloads-dev` to restore the previous digest. The tables
stay; V1 and V2 are forward-only and additive, and earlier images ignore the V2 tables (Flyway's default ignores an
applied migration that is newer than the image; not exercised). With an image before V2, site status writes answer
refused (`401` or `403`) and the site metrics disappear, so `Box1SiteStale` cannot fire. To remove the service's
data entirely, delete the PVC with approval; this is destructive.
