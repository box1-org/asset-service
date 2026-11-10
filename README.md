# asset-service

Represent connected industrial equipment at multiple sites.

Owner: `user:default/mike`. Quarkus 3.40.1.1, Java 21, Maven 3.9.16.

This is the box1 **Telemetry** service ([box1-docs#15](https://github.com/box1-org/box1-docs/issues/15)).
It ingests sensor readings from equipment at several sites, keeps recent readings in its own
PostgreSQL database, raises threshold alerts with hysteresis, and publishes
`equipment.reading.v1` and `equipment.threshold-exceeded.v1` on `box1.telemetry.events.v1`.
It also serves a **load and chaos console** at `/console/` that generates seeded telemetry load
and bounded, telemetry-only disruption.

| Read | For |
|---|---|
| [docs/architecture.md](docs/architecture.md) | Data flow, ownership, alert rules, the console's safety model |
| [docs/runbook.md](docs/runbook.md) | First deployment, configuration, operations, recovery and replay |
| [box1-ops-contracts TELEMETRY.md](https://github.com/box1-org/box1-ops-contracts/blob/main/TELEMETRY.md) | Event contract, keying and dead letters |
| [box1-ops-contracts SITE.md](https://github.com/box1-org/box1-ops-contracts/blob/main/SITE.md) | Edge sites: readings, site status and state thresholds |

Onboarded with PostgreSQL = `false`, Kafka = `true`. PostgreSQL was added afterwards without
the template's inline Secret: the Secret is provisioned out of band (see the runbook). Kafka
uses the shared `box1-kafka` cluster at `box1-kafka-kafka-bootstrap.box1-kafka.svc:9092`; the
topics are managed in `box1-gitops/platform/kafka/topics/`.

## API

| Path | Access | Purpose |
|---|---|---|
| `POST /api/v1/telemetry/readings` | bearer token, client role `telemetry-writer` | Ingest one reading; `202` after Kafka acknowledges |
| `GET /api/v1/assets/{assetId}/readings` | bearer token, role `telemetry-reader`, `telemetry-writer` or `chaos-operator` | Time window, keyset pagination, ordered by `measuredAt`, `metricName` |
| `GET /api/v1/alerts` | as above | Alert episodes, newest first |
| `PUT /api/v1/sites/{siteId}/status` | bearer token from an edge site's own client (`site-west`, `site-east`), client role `site-reporter`, claim `siteId` equal to the path | Site heartbeat ([box1-docs#130](https://github.com/box1-org/box1-docs/issues/130)); returns the stored `SiteStatus` |
| `GET /api/v1/sites`, `GET /api/v1/sites/{siteId}` | bearer token, role `telemetry-reader`, `telemetry-writer` or `chaos-operator` | Each site's latest status with its `ONLINE`, `DEGRADED` or `OFFLINE` state derived at read time |
| `/console/`, `/api/v1/control/*` | Keycloak login, client role `chaos-operator` | Load and chaos console |
| `/api/greetings`, `/api/info`, `/q/*` | anonymous | Golden Path smoke contract, unchanged |

Errors are `application/problem+json` with a stable `code`. Tokens come from realm `box1`;
roles are read only from `resource_access.asset-service.roles`. Site clients' tokens keep only
`site-reporter`, so a site can write its own status and read nothing. The site status API, its
thresholds, metrics and the `Box1SiteStale` alert are in the [runbook](docs/runbook.md#edge-site-status).

## Local development

Install JDK 21 and use the included Maven wrapper:

```sh
./mvnw verify                      # unit, security and contract tests; no database or broker needed
./mvnw verify -Pcontainers         # adds the PostgreSQL and Kafka suite
```

The containers suite expects PostgreSQL on `localhost:15432` (database and user
`asset_service`) and Kafka on `localhost:19092` with topics `box1.telemetry.events.v1` and
`box1.telemetry.dlq.v1`; override with `TEST_DATABASE_URL`, `TEST_DATABASE_USERNAME`,
`TEST_DATABASE_PASSWORD` and `TEST_KAFKA`. The contract tests fetch schemas from
`box1-ops-contracts` at the commit pinned by `contracts.ref` in `pom.xml`, so they need
network access to GitHub.

Verification runs unit tests, HTTP tests, packaged JVM integration tests,
Checkstyle, and SpotBugs/FindSecBugs. `/api/info` includes the immutable Git SHA
embedded in the image; local builds default to `unknown` unless supplied above.
Java package `org.box1` is deliberately stable across generated services.

## First deployment

Create the service in Developer Hub. Its task tracks repository creation,
validated development onboarding, platform readiness, initial CI, image promotion,
and the verified first deployment. When the task completes, its output links to
source, catalog, initial pipeline, Argo CD, and the running service. No manual
onboarding merge or extra source commit is required.

If onboarding fails, the task reports the failed state and diagnostic link.
Submit the same name, owner, visibility, and description to resume the recorded
workflow. The repository and development resources are reused. A concurrent
request for the same service is rejected while the first task holds its lease.
The initial CI run is named `onb-asset-service-<revision>`; a retried failed
build has a numbered suffix. Completed builds are reused.

Route: https://asset-service-dev.apps.ocp.box1.org

## Delivery and configuration

PRs validate without registry credentials. Main releases use centrally managed
Tasks from `box1-gitops/platform/pipelines/tasks`, resolved from `box1-ci`.
GitHub checks link to cluster PipelineRuns named `asset-service-validate-*`
and `asset-service-release-*`. Pipelines as Code and Developer Hub use
separate GitHub Apps, both installed for all `box1-org` repositories.

Jib creates JVM images from a digest-pinned UBI 9 / Java 21 runtime without a
Docker daemon. Images use `registry.apps.ocp.box1.org/box1-ci/asset-service:<SHA>`;
OpenShift pulls by digest through the internal registry service.

After onboarding, the GitOps repository is the authoritative deployment source.
Change the Route, probes, resources, or
`GREETING_PREFIX` there. The default smoke contract expects `Hello from box1`.
The ConfigMap also sets JVM container memory limits; runtime has no API token.

Endpoints: `/api/greetings`, `/api/info`, `/q/health/live`, `/q/health/ready`,
`/q/health/started`, `/q/openapi`, `/q/metrics`.

The development promotion policy is automated after checks. Other environments
need explicit platform configuration and approval. Static analysis is included;
no external dependency CVE database scan is implied.

## API documentation and endpoint testing

Developer Hub populates **Provides APIs** with `asset-service-api`.
Open the API's **Definition** tab to browse the deployed OpenAPI contract and use
**Try it out**. The catalog reads `https://asset-service-dev.apps.ocp.box1.org/q/openapi`,
so the API entity becomes available after the first successful deployment.
Swagger UI is also available at `https://asset-service-dev.apps.ocp.box1.org/q/swagger-ui/`.
The production contract targets the public HTTPS Route; override `PUBLIC_API_URL`
when deploying behind another public address. CORS allows the Developer Hub
origin to make tester requests without cookies. API authentication, if added,
should be declared in the OpenAPI contract and supplied through **Authorize**.

## Documentation

Developer Hub publishes `docs/` on the service's **Docs** tab using TechDocs.
List new pages in `mkdocs.yml`.
