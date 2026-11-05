# Runbook

## First deployment order

Each step is a separate, approved action.

1. **Dead-letter topic.** Merge the `box1-gitops` change adding `box1.telemetry.dlq.v1`, then
   sync that one KafkaTopic in Application `box1-kafka` (it has no automated sync). Confirm the
   topic is `Ready`. The service starts without it, but its first poison record would then fail
   to dead-letter.
2. **Keycloak client.** Merge the `box1-gitops` change adding KeycloakClient `asset-service`.
   Application `keycloak` syncs it automatically. Assign client role `chaos-operator` to each
   console user in the Keycloak admin console (Users → user → Role mapping → client
   `asset-service`). Do not assign `telemetry-writer` unless a client must post readings
   through the Route.
3. **Database Secret.** Create it once, out of band. It is deliberately not in Git
   (box1-docs#41):

   ```sh
   oc -n asset-service-dev create secret generic asset-service-postgres \
     --from-literal=POSTGRESQL_DATABASE=asset_service \
     --from-literal=POSTGRESQL_USER=asset_service \
     --from-literal=POSTGRESQL_PASSWORD="$(openssl rand -base64 30)"
   ```

   Never record the value. PostgreSQL reads it only at first initialization. To rotate it later,
   run `ALTER ROLE`, update the Secret, then restart the application.
4. **Workloads.** Merge the `box1-workloads-dev` change. Argo CD creates the database and
   updates the Deployment.
5. **Service.** Merge the service PR. The release builds, gates, promotes and smoke-tests the
   image.

If the Secret is missing, both pods wait in `CreateContainerConfigError`. That is visible and
harmless.

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
| `ASSET_CONTROL_ENABLED` | `true` | Load and chaos run control |
| `ASSET_OIDC_URL` | box1 realm | Issuer URL including RH-SSO's `/auth` path |
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
- **Deliberate replay** of a window, with the deployment scaled to 0:

  ```sh
  kafka-consumer-groups.sh --bootstrap-server box1-kafka-kafka-bootstrap.box1-kafka.svc:9092 \
    --group asset-service --topic box1.telemetry.events.v1 --reset-offsets --to-datetime 2026-11-05T00:00:00.000 --execute
  ```

  Replays are harmless: readings deduplicate on their natural key, and alert state ignores
  readings older than the last evaluated one.
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
  key. It is idempotent.
- **Database outage:** the consumer retries with backoff up to 30 s and holds its partitions. It
  never dead-letters valid readings for a transient database error. Lag grows and readiness goes
  DOWN; both recover by themselves.
- **Broker outage:** `asset_producer_in_flight` rises to the queue capacity, then the API
  answers `503 BACKPRESSURE` with `Retry-After`. Readings handed to Kafka but not acknowledged
  within 60 s are counted in `asset_ingest_dropped_total` and logged with their `eventId`. Their
  clients never received a 202.

## Rollback

Revert the promotion commit in `box1-workloads-dev` to restore the previous digest. The tables
stay; V1 is forward-only, and earlier images do not use the database. To remove the service's
data entirely, delete the PVC with approval; this is destructive.
