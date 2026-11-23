# Observability

DLAP emits **metrics**, **structured logs**, and (once a tracer is wired) **spans** from the
highest-value hot paths. It is built on Micrometer and the Spring Boot Actuator that every
service inherits from the root `build.gradle.kts`. The Prometheus registry
(`io.micrometer:micrometer-registry-prometheus`, Apache-2.0) is declared there for every service
module; `da-discovery` declares it in its own build file as well.

This page covers what is measured (section 1), who may read it (section 2), how it is collected
(section 3), which alerts exist and why (section 4), how to route them (section 5), the dashboards
(section 6), log correlation and tracing (sections 7 and 8), the known gaps (section 9) and how to
check the whole stack (section 10).

## 1. Metric catalog

Meters are registered on the service's `MeterRegistry` and exposed at `GET /actuator/prometheus`.
Tags are deliberately **low cardinality**: no `assetId`, `subjectId`, `decisionId`, URI or other
unbounded value. A Micrometer name becomes a Prometheus name by turning dots into underscores;
counters end in `_total` and timers in `_seconds_count`, `_seconds_sum` and `_seconds_max`. Series
appear after the first event of their kind.

### Platform series (every service)

Spring Boot and Micrometer publish these without any DLAP code. The alerts and recording rules read them.

| Series | Meaning |
|---|---|
| `http_server_requests_seconds_count`, `_sum` | Requests by `status`, `method`, `uri`, `outcome`. |
| `jvm_memory_used_bytes`, `jvm_memory_max_bytes` | Heap and non-heap by `area` and `id`. A pool without a limit reports -1. |
| `jvm_gc_pause_seconds_sum` | Time paused for garbage collection. |
| `hikaricp_connections_active`, `_max`, `_pending` | The database pool of each service, by `pool`. |
| `process_start_time_seconds`, `process_uptime_seconds` | Restarts and age. |

### da-policyengine: PDP verdict hot path

Instrumented in `AccessDecisionService.evaluate(...)`. The verdict is computed by an unchanged
`doEvaluate(...)`; `evaluate` only observes it (no I/O added, the cache path stays fast). The
evaluation is wrapped in a Micrometer `Observation` (`dlap.pdp.evaluation`) so a deployment that wires
a tracer bridge gets a span for free.

| Meter | Type | Tags | Meaning |
|---|---|---|---|
| `dlap.pdp.decisions.total` | Counter | `decision` = `allow` or `deny`, `default` = `true` or `false` | One increment per verdict. `default=true` means no policy matched and the configured default decided. |
| `dlap.pdp.evaluation.duration` | Timer (histogram) | none | Wall time of the in-memory policy evaluation. |
| `dlap.pdp.evaluation` | Observation | `decision`, `defaultApplied` | Timer sample and, with a tracer, a span named `dlap.pdp.evaluation`. |
| `dlap.pdp.policies.invalid.deny` | Gauge | `scope` = `readable` or `unreadable` | Stored ENABLED policies the PDP keeps as an invalid deny at the latest policy refresh (ADR-0133, ADR-0136). `readable`: the document does not parse but its subject or resource selector can be read, so it denies every request in that scope (`policy-invalid`). `unreadable`: no selector can be read, or a policy row of kind DSL has no document, so it denies **every** request (`policy-store-invalid`) until it is fixed or disabled. Both gauges exist from start-up, at 0. Alert `PolicyStoreUnreadableDeny` fires on `scope="unreadable"` above 0. |
| `policyengine.access.decision.publish.failures` | Counter | `decision`, `stage` (on a dispatch failure) | An access-decision event that did not reach Kafka. The verdict was still returned. |
| `policyengine.send.failures` | Counter | `topic`, `actionType`, `stage` (on a dispatch failure) | A policy action event that did not reach Kafka. |

### da-policyengine: approvals

Registered by `ApprovalService` and `ApprovalOutboxRelay` (docs/specs/approvals.md, section 12). Prometheus
names add `_total`: `policyengine_approvals_break_glass_total`, `policyengine_approvals_outbox_total`. A
counter is created by its first increment, so the series of a deployment that never broke glass does not
exist (the alerts below account for that).

| Meter | Type | Tags | Meaning |
|---|---|---|---|
| `policyengine.approvals.break_glass` | Counter | `actionClass` | One per break-glass release: an administrator released a destructive action without the second approver (`ApprovalService.java:508`). Alert `ApprovalBreakGlassUsed`. |
| `policyengine.approvals.outbox` | Counter | `kind` = `REQUESTED`, `DECIDED`, `BREAK_GLASS` or `ACTION`, `result` = `sent` or `failed` | Outbox messages the relay sent or failed to send (`ApprovalOutboxRelay.java:102,110`). A failed message is retried on the next tick. Alert `ApprovalOutboxSendFailing`. |

### da-mcp-pep: enforcement hot path

Instrumented in `McpPolicyEnforcer.enforce(...)` (covers the cache-hit path and the PDP-call path).

| Meter | Type | Tags | Meaning |
|---|---|---|---|
| `dlap.mcppep.decisions.total` | Counter | `allow` = `true` or `false`, `failClosed` = `true` or `false` | One increment per enforced verdict. `failClosed=true` marks a fail-closed (deny) or fail-open (observe-mode allow) fallback because the PDP was unavailable. |
| `dlap.mcppep.enforce.duration` | Timer (histogram) | none | Wall time of `enforce()`: cache lookup plus, on a miss, the PDP round trip. |

### da-reporting: drift detection

Instrumented in `DriftDetectionOrchestrator.recordReadAndDetect(...)` at the finding-persistence point.

| Meter | Type | Tags | Meaning |
|---|---|---|---|
| `dlap.drift.findings.total` | Counter | `type` = `UNENFORCED_READ` or `ENFORCEMENT_BYPASS` | One increment per `DriftFinding` newly persisted (idempotent: a redelivered read does not double-count). |

### da-discovery: asset discovery

Instrumented in `CrawlerAssetSpineBridge.process(...)`, the single central point where every
discovered or crawled asset is bridged onto the catalog log-event spine.

| Meter | Type | Tags | Meaning |
|---|---|---|---|
| `dlap.discovery.assets.total` | Counter | `provider` (for example `AWS`, `AZURE`, `GCP`, `unknown`), `serviceType` (the resource `typeId`, bounded; `unknown` if absent) | One increment per asset published to the spine. Never tagged by `assetId`. |

### da-catalog

`CatalogIngestMetrics` in da-catalog counts the event consumers (`dlap.catalog.events` by `consumer`
and `outcome`, `dlap.catalog.unmatched.target`, `dlap.catalog.unresolved.target`,
`dlap.catalog.asset.match`). The names and tags are in the class's Javadoc. The spine alert
`SpineUnmatchedTargetsHigh` divides the unmatched counter by the unmatched and matched counters.

### da-logingest: parser health

With `dlap.logingest.pipeline=registry`, da-logingest exports the signals that tell an operator a
provider changed its log format before the reports go wrong (hardening package LOG-03, design B3,
test strategy L8). The legacy pipeline exports only `dlap_logingest_dedup_hits_total`, as before. The
signals come from four places: a wrapper around the parser registry (every outcome with its detected
version and shape), an aspect on the replay store (published and duplicate events), a Kafka producer
listener (failed sends) and a database snapshot rebuilt every minute (cursors, observed versions, the
read signal). A scrape never touches the database.

| Series | Type and labels | Meaning |
|---|---|---|
| `dlap_logingest_records_total` | counter `source, format, version, outcome, binding` | One per source record. `outcome` is `PARSED`, `PARSED_WITH_WARNINGS`, `SKIPPED` or `QUARANTINED`. |
| `dlap_logingest_quarantine_total` | counter `source, format, version, reason, binding` | Quarantined records by `QuarantineReason` (for example `UNKNOWN_MAJOR_VERSION`, `MISSING_REQUIRED_FIELD`). |
| `dlap_logingest_unknown_fields_total` | counter `source, format, version` | JSON paths the parser does not declare, one per path per record. The paths themselves go to the drift report, never to a label. |
| `dlap_logingest_version_observed` | gauge `source, format, version` | 1 when the version was seen in the last 24 hours, else 0. |
| `dlap_logingest_version_first_seen_timestamp_seconds` | gauge `source, format, version` | Epoch seconds of the first sighting, from `format_observation`, so a restart does not make an old version look new. |
| `dlap_logingest_cursor_lag_seconds` | gauge `source, stream, binding` | Now minus the oldest unacknowledged event time of the stream. No series until the first poll. |
| `dlap_logingest_event_delay_seconds` | histogram `source` | Ingest time minus event time, buckets 1s, 5s, 30s, 1m, 5m, 15m, 30m, 1h, 6h, 24h. |
| `dlap_logingest_dedup_hits_total` | counter | Events whose id was already stored. |
| `dlap_logingest_publish_failures_total` | counter `topic` | Kafka sends that failed; a topic that is not a `dlap.` topic reads `other`. |
| `dlap_logingest_field_fill_ratio` | gauge `source, version, field` | Share of parsed events of the last hour that carry the field. `field` is one of ten key optional fields (`principal.id`, `principal.account-id`, `service`, `target.id`, `target.canonical-uri`, `target.project`, `target.region`, `request.caller-ip`, `request.user-agent`, `request.request-id`). NaN when no event arrived in the hour. |
| `dlap_logingest_classification_heuristic_ratio` | gauge `source` | Share of parsed events of the last hour classified by the verb heuristic instead of the mapping table. |
| `dlap_logingest_read_signal_present` | gauge `binding` | 1 when data reads arrive, 0 when data writes arrive and no read did for 24 hours, NaN when unknown or when no stream of the binding can carry reads (CloudTrail `LookupEvents`). |
| `dlap_logingest_parser_selftest_ok` | gauge | 1 when the startup self-test passed. |

#### Backfill and drift state

Bounded backfill jobs (`BackfillMetrics.java`, Q-558) and the drift state persister
(`DriftStatePersister.java`, Q-364) add these series to da-logingest.

| Series | Type and labels | Meaning |
|---|---|---|
| `dlap_logingest_backfill_batches_total` | counter `binding` | Batches that reached their outcomes. Every batch also advances the progress heartbeat of its job. The series appears with the first batch. |
| `dlap_logingest_backfill_records_read_total`, `_records_quarantined_total` | counter `binding` | Records handed to the ingest path, and those quarantined. |
| `dlap_logingest_backfill_objects_failed_total` | counter `binding, reason` | Objects that failed, `read` or `ingest`; each failed attempt counts. |
| `dlap_logingest_backfill_job_duration_seconds` | timer `binding, status` | Run time of a job that ended (`SUCCEEDED`, `FAILED`, `CANCELLED`). |
| `dlap_logingest_backfill_jobs` | gauge `status` | Jobs in that status now, across bindings, read from the job table (cached 10 s). `FAILED` counts every failed job in the table, so it never falls. NaN when the table cannot be read. |
| `dlap_logingest_backfill_heartbeat_age_seconds` | gauge `binding` | Seconds since the stored `progress.heartbeat` of the oldest `RUNNING` job of that binding, computed on the service clock from the job table (cached 10 s; a job that has not written a heartbeat yet counts from its start). The label is `_all` when the service has many bindings, and then the value is the largest. 0 while the binding has no `RUNNING` job. NaN when the table cannot be read. A binding's series appears after the first scrape that finds a `RUNNING` job of it, and then stays. A running job writes a heartbeat at the start of every slice and after every batch. |
| `dlap_logingest_drift_state_flush_failures_total` | counter | Drift state flushes that failed (`DriftStatePersister.FLUSH_FAILURES`). Registered at 0 when the service starts, so the series always exists. |

Cardinality is bounded on purpose. `version` takes at most 32 values per (source, format); later ones
read `other`, because the version string comes from the record. `binding` is a label only while the
service has at most 50 bindings; with more it reads `_all` and the gauges combine (the largest lag,
the lowest read signal). `field` is a closed set. Settings: `dlap.logingest.metrics.binding-label-max`,
`.max-versions-per-format`, `.fill-window` (default `PT1H`), `.fill-min-samples`, `.snapshot-interval`
(`PT1M`), `.read-signal-window` (`PT24H`), `.drift-drop-threshold` (0.2).

The per-instance state (fill ratios, unknown paths, the window counts of the binding status) is the
view of one instance since it started; the read signal is seeded from `log_events` at start. The
alerts aggregate across instances.

Not exported by da-logingest: `dlap_spine_unmatched_target_ratio` (the alert reads the catalog counters
instead), `dlap_reporting_skipped_total` and `dlap_discovery_router_dropped_total` (test strategy L8
names them; they belong to da-reporting and da-discovery).

### Parser health APIs, quarantine and binding status

All handlers are `ROLE_ADMIN` only.

| Endpoint | What it returns |
|---|---|
| `GET /api/v1/logingest/health` | One row per binding: state, declared against observed versions, top quarantine reasons with redacted samples, unknown field paths, fill ratios, last record time, cursor lag, read coverage; plus the startup self-test. |
| `GET /api/v1/logingest/health/{bindingId}` | The same row for one binding; 404 when the id is not configured. |
| `GET /api/v1/logingest/drift?since=&limit=` | New versions since `since` (default 7 days, persistent), unknown JSON paths (this instance since start), fields whose fill ratio fell below their baseline. Paths and field names, never values. |
| `GET /api/v1/logingest/quarantine` | The quarantine table, newest first, filtered by `bindingId`, `reason`, `version`, `replayed`, `from`, `to`, paged. |
| `GET /api/v1/logingest/quarantine/{id}` | One record with its masked excerpt and whether it can be replayed. |
| `POST /api/v1/logingest/quarantine/replay` | Re-runs quarantined records through the current registry; see the runbook. |

The quarantine goes to `dlap.log.event.quarantine` (key `binding_id`, proto3 JSON
`LogQuarantineRecord`) and to `dlap_logingest.quarantine_record`: the excerpt is at most 2048 bytes
with email addresses and IP addresses masked, `reason_detail` at most 1024 bytes and never a value,
and rows older than 30 days are deleted nightly (`dlap.logingest.quarantine.retention-days`,
`.retention-cron`). The topic's own retention is a broker setting. Binding status goes to
`dlap.logingest.binding.status` (key `binding_id`, `LogBindingStatus`) every minute
(`dlap.logingest.health.status-interval`); the topic may be compacted
(`cleanup.policy=compact`). The built-in REST and replay bindings report once they carried a record.

The `logParsers` component of `/actuator/health` is the startup self-test: a bundled golden set
(`dlap/log-formats/golden/*.json`, one file per case; a parser family ships its own next to its mapping)
runs through the real registry and mapper when the service starts. DOWN names the failing case ids.
`dlap.logingest.health.selftest-fail-fast=true` stops the start instead.

Settings of the parser health layer. The defaults are in code, so a deployment sets only what it changes.

| Property (environment variable by the usual relaxed binding) | Default | Meaning |
|---|---|---|
| `dlap.logingest.quarantine.table-enabled` / `.topic-enabled` | `true` / `true` | The two quarantine sinks. With both off the pipeline only logs. |
| `dlap.logingest.quarantine.max-excerpt-bytes` | `2048` (ceiling 8192) | Bytes of masked excerpt a sink keeps. |
| `dlap.logingest.quarantine.retention-days` / `.retention-enabled` / `.retention-cron` / `.retention-batch` | `30` / `true` / `0 45 3 * * ?` / `5000` | The nightly sweep of the table, a ShedLock singleton. 0 or less turns it off. |
| `dlap.logingest.quarantine.replay-max-batch` | `500` | The most records, and the most ids, one replay call takes. |
| `dlap.logingest.health.status-enabled` / `.status-interval` / `.status-initial-delay` | `true` / `PT1M` / `PT30S` | The binding-status publisher (registry pipeline only), a ShedLock singleton. |
| `dlap.logingest.health.degraded-lag` | `PT30M` | A cursor older than this makes the binding `DEGRADED`. |
| `dlap.logingest.health.degraded-quarantine-ratio` / `.degraded-min-records` | `0.05` / `20` | More than this share of at least this many records of a status window quarantined makes it `DEGRADED`. |
| `dlap.logingest.health.no-read-transports` | `LOOKUP_EVENTS` | Transports that cannot carry data reads; a binding that only uses them reports `READ_COVERAGE_NOT_CAPTURED`. |
| `dlap.logingest.health.quarantine-window` / `.top-reasons` / `.samples-per-reason` | `P7D` / `5` / `3` | What the health page shows per binding. |
| `dlap.logingest.health.selftest-fail-fast` | `false` | Stop the start when the self-test fails. |
| `dlap.logingest.metrics.binding-label-max` / `.max-versions-per-format` | `50` / `32` | Cardinality bounds of the labels above. |
| `dlap.logingest.metrics.fill-window` / `.fill-min-samples` / `.drift-drop-threshold` | `PT1H` / `50` / `0.2` | The fill ratio window, the samples needed to judge a field, and the drop the drift report lists. |
| `dlap.logingest.metrics.snapshot-interval` / `.snapshot-initial-delay` / `.read-signal-window` | `PT1M` / `PT15S` / `PT24H` | The snapshot rebuild and the read signal lookback. |

### Exporters

PostgreSQL and Kafka are not instrumented by DLAP. The `monitoring` profile starts
`postgres_exporter` and `kafka_exporter`. The alerts read `pg_up`, `pg_stat_activity_count`,
`pg_settings_max_connections`, `pg_stat_database_deadlocks`, `kafka_brokers` and
`kafka_consumergroup_lag`. These names were read from the pinned exporter images
(`prometheuscommunity/postgres-exporter:v0.15.0`, `danielqsj/kafka-exporter:v1.7.0`) on 2026-10-19.

## 2. Who may read what

| Path | Application port | Management port (`MANAGEMENT_SERVER_PORT` set) |
|---|---|---|
| `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness` | public when the actuator shares the application port | public |
| `/actuator/prometheus` | needs a token | public, and only here |
| every other actuator endpoint (`info`, `metrics`) | needs a token | needs a token |
| every API path | needs a token | needs a token |

Rules behind the table:

- **Health is public** because probes and load balancers carry no token (CLAUDE.md section 9; Q-086
  closed). It reports the status only. Component detail stays behind
  `management.endpoint.health.show-details=when-authorized`.
- **Prometheus is public on the management port only.** Set the environment variable
  `MANAGEMENT_SERVER_PORT` (Spring's `management.server.port`) to a port other than the application
  port. Each service then adds a security chain, `prometheusScrapeFilterChain`, that matches the
  Prometheus endpoint on the management server and nothing else. On the application port the
  endpoint is not served at all, and the main chain asks for a token for anything unmatched. With
  no management port set, `/actuator/prometheus` needs a token like any other endpoint, so a
  default deployment exposes no metrics.
- **The management port belongs on the container network, not on a public address.** The dev
  overlay publishes no host port for it.
- **`/actuator/info` is no longer public** (it was on eight services). CLAUDE.md section 9 names
  only health and the Swagger paths as exceptions.
- **da-mcp-pep** keeps its reference endpoints open (caller authentication is the MCP transport's
  job). Its chain permits health, denies every other actuator endpoint on either port, and the
  Prometheus chain opens the scrape on the management port.
- **Issuers.** `dlap.security.jwt.expected-issuer` (env `DLAP_SECURITY_JWT_EXPECTED_ISSUER`) accepts
  several issuers separated by commas, active when `dlap.security.jwt.validate-issuer` (env
  `DLAP_SECURITY_JWT_VALIDATE_ISSUER`) is true. A token passes when its `iss` equals one entry
  exactly. A demo or production deployment lists the public Keycloak URL that browser tokens carry
  and the internal URL that service tokens carry, for example
  `https://auth.example.com/realms/dlap,http://keycloak:8080/realms/dlap`. A single value works as
  before and the check stays off by default (Q-014).

Named tests: `ActuatorExposureTest` in every service module (the real security chain on a real servlet
container, with and without a management port), `JwtTokenValidatorTest` in every module that validates
tokens (all but da-mcp-pep), and `ManagementPortContextTest` in every service except da-discovery (the
whole service with its own `application.properties`; a real PostgreSQL through Testcontainers, except
for da-mcp-pep, which needs no Docker). da-discovery has no `ManagementPortContextTest` because its
production-profile test starts a forked JVM (see section 9). Two series names in the alerts are proved
by name: `dlap_mcppep_decisions_total{allow,failClosed}` in da-mcp-pep's `ManagementPortContextTest`,
and `policyengine_access_decision_publish_failures_total` and `policyengine_send_failures_total` in
da-policyengine's `PublishFailureMetricsTest`.

### Ports in the dev stack

Every service runs on port 8080 inside its container (da-logingest on 8090). The host ports are the
`DLAP_PORT_*` values of `.env.example`.

| Setting | Actuator port | Notes |
|---|---|---|
| default (`docker-compose.yml` and `docker-compose.microservices.yml`) | the application port | `/actuator/health` answers there for every service, as the README and the e2e harnesses expect. Prometheus needs a token, so there is nothing to scrape. |
| with `docker-compose.monitoring.yml` | 9091 inside the container, not published | `/actuator/health` moves to 9091 as well. Prometheus scrapes `<service>:9091`. |

da-reporting and da-autolabel used to put the actuator on 8380 and 8385 through
`application.properties`, where nothing could reach it (Q-177). The compose now sets
`MANAGEMENT_SERVER_PORT` empty for both in the default stack, which means "same port as the
application", and 9091 in the overlay. Outside compose, an environment variable overrides the
property file in the same way.

## 3. Collection

`deployment/platform/prometheus/prometheus.yml` has these scrape jobs:

| Job | Targets | Path |
|---|---|---|
| `dlap-services` | the 11 services on port 9091: `da-admin-service`, `da-autoarchival`, `da-autocompliance`, `da-autodelete`, `da-autolabel`, `da-catalog`, `da-discovery`, `da-logingest`, `da-mcp-pep`, `da-policyengine`, `da-reporting` | `/actuator/prometheus` |
| `postgres` | `postgres-exporter:9187` | `/metrics` |
| `kafka` | `kafka-exporter:9308` | `/metrics` |
| `alertmanager`, `grafana`, `prometheus` | themselves | `/metrics` |

`da-mcp-pep` is a standalone reference deployable and the dev compose does not run it. Its target is
listed so that a deployment that runs it is scraped without a change, and `McpPepDown` alerts only
when it was up earlier in the day.

The services get a `service` label from the target name. The old file listed targets nobody runs
(Redis, Keycloak metrics, node exporter, Docker daemon, Nginx), storage settings Prometheus rejects,
and a `remote_write` to a host that does not exist. `promtool check config` failed on it, so the
`monitoring` profile could not start. All of that is gone. To scrape a host or container exporter,
add a job and its rules here; the old host and container alerts were removed because no exporter
fed them.

### Images

All images of the monitoring stack are pinned: `prom/prometheus:v2.53.0`, `prom/alertmanager:v0.27.0`,
`grafana/grafana:10.4.2`, `prometheuscommunity/postgres-exporter:v0.15.0` and
`danielqsj/kafka-exporter:v1.7.0`. `deployment/platform/prometheus/check.sh` reads the Prometheus and
Alertmanager tags from `docker-compose.yml`, so the checks always use the tags the stack runs.

### Bring-up

```bash
docker compose --env-file .env --profile monitoring \
  -f deployment/environments/dev/docker-compose.yml \
  -f deployment/environments/dev/docker-compose.microservices.yml \
  -f deployment/environments/dev/docker-compose.monitoring.yml up -d
bash deployment/platform/prometheus/verify-targets.sh
```

`verify-targets.sh` asks Prometheus whether all 14 targets answer, whether all 8 rule groups loaded
without error, and whether Alertmanager is connected. Prometheus is on `localhost:9090` and Grafana
on `localhost:3001` (`GRAFANA_ADMIN_PASSWORD`, default `admin` in dev only). Alertmanager and the
exporters publish no host port.

## 4. Alerts

`deployment/platform/prometheus/alerts.yml`, 35 rules in 7 groups. `alerts.test.yml` fires every rule
on synthetic series and checks that it stays quiet on healthy ones (`promtool test rules`). Each alert
carries `summary`, `description` and a `runbook` pointing here. **critical**: a component is down or
failing for callers, or a control was bypassed (break-glass), act now. **warning**: degrading, act in working hours. **info**: open a ticket,
nobody is paged.

Traffic floor: the HTTP rules ignore a service under 0.1 requests per second (about 30 requests in
5 minutes), so one failure in a quiet period does not page. Actuator paths are excluded everywhere
so the scrape and the probes do not count as traffic.

| Alert | Fires when | For | Severity | Why this threshold / what to do |
|---|---|---|---|---|
| `ServiceDown` | `up == 0` for a service other than da-mcp-pep | 2m | critical | Two minutes of failed scrapes. A start that takes longer than that, for example a first boot with every migration, fires it once; that is the cue to read the log. Check the container, then its management port. |
| `McpPepDown` | the PEP was up in the last day and is not now | 2m | warning | Optional component, so it only alerts after having been seen up. Agents behind it are denied or unchecked. |
| `ServiceRestartingRepeatedly` | more than 2 restarts in 30m | 0 | warning | Three restarts in half an hour is a crash loop. Look for out-of-memory kills and failing start-up dependencies. |
| `PostgresDown` | `pg_up == 0` | 1m | critical | Every service with a database is affected. |
| `KafkaBrokerDown` | `kafka_brokers < 1` | 1m | critical | The log spine and the policy action bus stop. |
| `MonitoringTargetDown` | an exporter, Alertmanager or Grafana cannot be scraped | 5m | warning | Covers the case where `pg_up` and `kafka_brokers` have no samples at all. |
| `HighServerErrorRatio` | more than 5% of requests answer 5xx | 10m | warning | 5% sustained for ten minutes is a fault, not a stray bad request, and ten minutes rides out a deploy. |
| `ServerErrorSpike` | more than 25% answer 5xx | 5m | critical | A quarter of the requests failing means callers are broken. |
| `HighMeanRequestLatency` | mean request time above 2s | 10m | warning | Mean, because the services publish no histogram buckets. LLM labelling and exports are the slow paths; if one is legitimately slow, split it out by `uri`. |
| `HighAuthRejectRate` | more than 1 request per second answers 401 or 403 | 10m | warning | A caller with an expired token or a changed role mapping, or probing. The rate is per service. |
| `HighJvmHeapUsage` | heap above 90% of its maximum | 10m | warning | Pools without a maximum (-1) are left out of the divisor. Act before the next out-of-memory restart. |
| `HighGcPauseTime` | more than 10% of wall time in GC pauses | 10m | warning | The JVM is thrashing; raise memory or find the leak. |
| `DbConnectionPoolNearlyExhausted` | pool above 90% in use | 5m | warning | A slow query or a leaked connection holds the pool. |
| `DbConnectionPoolWaiting` | any thread waits for a connection | 5m | warning | Callers are already queueing. |
| `KafkaConsumerLagHigh` | consumer group lag above 1000 messages on a topic | 10m | warning | Consumers are down, stuck or slower than the producers. Lag builds while a consumer restarts; ten minutes separates that from a stuck consumer. |
| `PostgresConnectionsNearLimit` | more than 80% of `max_connections` | 5m | warning | Eleven services each hold a pool. The dev stack sets 200. |
| `PostgresDeadlocks` | any new deadlock in 10m | 0 | warning | Rare; a deadlock is always a bug in the access order of two transactions. |
| `McpPepFailClosed` | a decision was taken with `failClosed="true"` in 10m | 0 | warning | The PDP was unreachable. Enforce mode denied; observe mode allowed and flagged. Check da-policyengine. |
| `PolicyDecisionEventsNotPublished` | an access-decision event failed to reach Kafka in 15m | 0 | warning | Verdicts were returned, but the audit trail and drift detection miss the events. |
| `PolicyActionEventsNotPublished` | a policy action event failed to reach Kafka in 15m | 0 | warning | The label, archive and delete agents did not receive it. |
| `PolicyStoreUnreadableDeny` | `dlap_pdp_policies_invalid_deny{scope="unreadable"}` above 0 (maximum over the instances of a service) | 5m | critical | A stored ENABLED policy cannot be read and has no readable scope, so the PDP denies every request (`policy-store-invalid`). The WARN line of the latest policy refresh lists the row ids; fix the document or disable the policy. Five minutes rides out an edit that is saved in two steps. `scope="readable"` is narrower and has no alert; the start-up check `policyengine.policies.invalid{check="access-policy"}` and the same WARN line report it. |
| `ApprovalBreakGlassUsed` | any `policyengine_approvals_break_glass_total` increase in 10m, per `actionClass`; also a series that did not exist 10 minutes ago | 0 | critical | A break-glass release skips dual control, so every one is reviewed. The counter is created by the first release and `increase()` cannot see a series that starts at 1, so the rule also reports a new series. A Prometheus with no history reports every non-zero break-glass counter once, in its first 10 minutes. |
| `ApprovalOutboxSendFailing` | any `policyengine_approvals_outbox_total{result="failed"}` increase in 15m, per `kind` (same new-series clause) | 0 | warning | The relay could not send an approval message to Kafka. An approved action is carried out only after its message is acknowledged, so it waits while this fires. |
| `LogQuarantineRatioHigh` | more than 1% of a (source, version) quarantined over 15m (warning, up to 10%); above 10% (critical); at least 20 records in the window | 5m | warning / critical | Below 1% a few odd records are normal. A jump means the provider changed a format: read the reasons on `GET /api/v1/logingest/health`. |
| `LogUnknownMajorVersion` | any `UNKNOWN_MAJOR_VERSION` quarantine in 10m | 0 | critical | No parser reads the version at all (frozen rule 4). Pages: everything of that version is quarantined until a parser exists. |
| `LogNewFormatVersionObserved` | a version first seen less than an hour ago | 0 | info | Opens a ticket, not a page. The first sighting is read from `format_observation`, so a restart does not re-raise it. |
| `LogFieldFillDrop` | a field filled 20 points less often than its 7-day median (the median ends an hour ago) | 30m | warning | Silent drift: records parse, a field stopped arriving. Nothing is quarantined, so no other alert sees it. |
| `SpineUnmatchedTargetsHigh` | more than 5% of the log targets of the last hour match no catalog asset; at least 50 targets | 15m | warning | The best sign of asset-id drift between logs and crawlers. Reads the catalog counters, not a da-logingest series. |
| `ReadSignalMissing` | `dlap_logingest_read_signal_present` is 0 | 30m | warning | Writes arrive, reads do not, for 24 hours: read logging is probably off. Reports must say "not enabled", never "not read". |
| `LogSourceSilent` | no records for 2h from a binding that delivered some in the 22h before | 0 | warning | Built-in `dlap-` bindings are left out. A binding that restarted has no series until its next record. |
| `LogCursorLagHigh` | the oldest unacknowledged event of a stream is more than 30m old | 10m | warning | CloudTrail delivers in about 5 minutes, GCS sinks hourly; 30 minutes means a stuck or throttled poll. |
| `LogPublishFailures` | any failed Kafka send in 10m | 0 | warning | For `dlap.log.event.normalized` the events are stored but not on the spine; for the quarantine and status topics only the notice is lost. |
| `BackfillJobStuck` | the oldest `RUNNING` job of a binding last wrote its heartbeat more than 900 s ago (`dlap_logingest_backfill_heartbeat_age_seconds`, per binding) | 5m | warning | Exact rule of Q-846: the age is read from `progress.heartbeat`, not inferred from batches, so a job whose slices are empty (the heartbeat moves per slice, batches do not) no longer fires. 900 s is the longest retry backoff (`BackfillProperties.MAX_BACKOFF_MINUTES`); a job in its last retries writes a heartbeat again as soon as it retries. The value is 0 for a binding with no `RUNNING` job and NaN when the job table cannot be read, and neither fires. |
| `BackfillJobFailed` | the `FAILED` job count rose in the last hour | 0 | warning | The job stopped for good. The gauge counts every failed job and never falls, so the rule looks for a rise, not a level. |
| `DriftStateFlushFailing` | any `dlap_logingest_drift_state_flush_failures_total` increase in 15m | 0 | warning | The unknown-path counts go back to memory and are retried every 5 minutes; a restart while it fails loses them and the fill baselines. |

The thresholds are first values for a single-node pilot. They have not met production traffic;
review them after the first real account (P1).

`recording_rules.yml` holds 16 rules the dashboards read, for example
`service:http_server_errors:ratio5m` and `service:jvm_heap_used:ratio`.

### Log parser health runbook

What to do when one of the `dlap-log-ingest` alerts fires. Start with
`GET /api/v1/logingest/health` (ADMIN): it shows, per binding, the state, the top quarantine reasons
with redacted samples, the declared and observed versions, the cursor and the read coverage.

| Alert | First look | Action |
|---|---|---|
| `LogQuarantineRatioHigh` | The top reasons of the binding and a sample. `MISSING_REQUIRED_FIELD` or `FIELD_TYPE_MISMATCH` mean the provider moved or retyped a field. `INVALID_JSON` or `INVALID_ENCODING` mean a bad object or sink. | Fix the mapping (or the sink), add the sample to the fixtures as a redacted case, ship the parser, then replay (below). |
| `LogUnknownMajorVersion` | `GET /api/v1/logingest/drift` for the version and the paths it brings. | Add a parser or a version to the family mapping. The records wait in the quarantine table for 30 days; replay after the fix. |
| `LogNewFormatVersionObserved` | The drift report's new versions. | If the version is a minor above the highest known, the record parsed with `PARSE_WARNING_NEWER_MINOR`: add a fixture and bump the family. Otherwise treat it as `LogUnknownMajorVersion`. |
| `LogFieldFillDrop` | The `fillRatios` of the binding and the drift report's `fillDrops`. | Compare a recent record with the mapping: the provider renamed the field, or a field-override path is stale. No records are lost, but reports built on the field degrade. |
| `SpineUnmatchedTargetsHigh` | `dlap_catalog_unmatched_target_total` by `provider`, `kind` and `category`. | A kind that never matches means the crawler and the log name the asset differently (canonical id, ADR-0047): fix the normalizer for that kind. |
| `ReadSignalMissing` | The binding's `readCoverage`: `NONE_OBSERVED` with writes seen. | Enable data-access logging at the provider (CloudTrail data events, GCP data-access audit logs, Azure diagnostic settings). Until then reports show read logging as not enabled. |
| `LogSourceSilent` | `lagSeconds`, `lastRecordAt` and the stream's cursor. | Check the credentials and the sink (bucket, trail, subscription). A source that is really quiet needs its binding disabled. |
| `LogCursorLagHigh` | The cursor of the stream and the transport's errors in the log. | A throttled provider API or one poisoned object blocks the cursor. Fix the cause; the cursor catches up on its own. |
| `LogPublishFailures` | The `topic` label, then Kafka health. | Restore Kafka. Events and quarantine rows are in PostgreSQL; run a replay of the fixture or a bounded backfill if the spine missed events. |

### Approvals and backfill runbook

| Alert | First look | Action |
|---|---|---|
| `ApprovalBreakGlassUsed` | The log line `BREAK-GLASS: approval request ...` of da-policyengine and the audit entry `govern.approval.break_glass` of the request (docs/specs/approvals.md, section 8.1). | Confirm with the administrator that the emergency was real and the justification is sound. A release nobody can explain is a security incident: withdraw the administrator's role, then check the audit stream with `GET /api/v1/audit/streams/{streamId}/verification`. |
| `ApprovalOutboxSendFailing` | The `kind` label, then Kafka health and the warning `Approval outbox message ... not sent` in the log. | Restore Kafka. The relay retries every tick and nothing is lost: the messages stay in `approval_outbox` until acknowledged. |
| `BackfillJobStuck` | `GET /api/v1/logingest/backfills?status=RUNNING`: the `progress` of the job (its heartbeat, which is the time the gauge measures, its batch count and the partition it is on) and the log of da-logingest for the job id. | If the job waits on a throttled or failing source, fix the source; the job resumes by itself. If its replica died and no replica resumed it, check `dlap.logingest.backfill.enabled` and the scheduler. If it will never progress, cancel it with `POST /api/v1/logingest/backfills/{id}/cancel` and start a new job for the remaining window. |
| `BackfillJobFailed` | `GET /api/v1/logingest/backfills?status=FAILED`: `errorDetail`. | Fix the cause (configuration or the source), then start a new job; a finished job is never restarted. |
| `DriftStateFlushFailing` | The warning `Drift state flush failed` in the log of da-logingest and the state of the `dlap_logingest` database. | Restore the database. Until then the drift report is held in memory only. |

**Replaying the quarantine.** `POST /api/v1/logingest/quarantine/replay` (ADMIN) re-runs pending
records through the current registry. Body, all optional: `bindingId`, `reason`, `detectedVersion`,
`quarantineIds`, `limit` (default 100, at most 500) and `dryRun`. Start with `"dryRun": true`.

- A record that now parses (or is skipped) is marked `replayed_at` and never selected again; its events
  keep their ids, so replaying twice cannot store an event twice, and the corrected event is published
  because replay mode republishes a stored id. The stored `log_events` row of a record that had a
  pass-through event keeps its `PARSE_STATUS_PARTIAL`; the corrected event is the published one.
- A record that is quarantined again stays pending with `replay_count` up by one, so the next fix can
  replay it.
- Only a row whose stored excerpt is exactly the original record is replayed. The table keeps a
  masked, bounded excerpt: a truncated row (`notReplayableReason` `TRUNCATED`) or one with a masked email
  or address (`ALTERED`) is counted and left alone, because replaying it would store `<email>` as a
  principal. Re-read those from the source with a bounded backfill.
- One replay runs at a time in the cluster; a second call answers 409. On the legacy pipeline the call
  answers 409 too.

## 5. Alertmanager

`deployment/platform/alertmanager/alertmanager.yml` routes everything to a `null` receiver. Alerts are
grouped by `alertname`, `cluster` and `service`, shown in the Prometheus and Alertmanager user
interfaces, and sent nowhere. The old file held an e-mail server, two addresses and a Slack
webhook placeholder; the repository carries no notification credentials (CLAUDE.md section 10.2).

One inhibit rule keeps the root cause: while `ServiceDown` fires for a service, its warnings are
muted.

To send alerts, copy the file, add a receiver and route to it. Read every secret from a file or a
variable, never from the repository:

```yaml
receivers:
  - name: ops-mail
    email_configs:
      - to: ops@example.com
        from: alerts@example.com
        smarthost: smtp.example.com:587
        auth_username: alerts@example.com
        auth_password_file: /run/secrets/alertmanager_smtp_password
  - name: ops-webhook
    webhook_configs:
      - url_file: /run/secrets/alertmanager_webhook_url
route:
  receiver: 'null'
  routes:
    - matchers: [severity = "critical"]
      receiver: ops-webhook
    - matchers: [severity = "warning"]
      receiver: ops-mail
```

Mount your file over `/etc/alertmanager/alertmanager.yml` and run `amtool check-config` on it.

## 6. Dashboards

Two dashboards are provisioned into the folder DLAP (`deployment/platform/grafana/provisioning`),
against a Prometheus data source with the fixed uid `dlap-prometheus`:

- **DLAP Services** (`dlap-services`): services up, firing alerts, PostgreSQL and Kafka status;
  requests, 5xx ratio, mean request time, heap, pool use, GC share, consumer lag and PostgreSQL
  connections. Panel descriptions name the alert that watches the same series.
- **DLAP Lifecycle** (`dlap-lifecycle`): PDP decisions and evaluation time, MCP PEP decisions and
  fail-closed count, assets discovered, catalog events, drift findings and log ingest dedup hits.

The earlier dashboards drew recording rules over series no service emits (compliance scores, storage
cost, data quality). They were replaced.

## 7. Structured logging and correlation

Each service's `application.properties` sets Spring Boot's standard correlation pattern:

```
logging.pattern.level=%5p [${spring.application.name:},%X{traceId:-},%X{spanId:-}]
```

This is **inert without a tracer** (the `traceId` and `spanId` MDC keys resolve to empty) and
**correct with one** (the tracer bridge populates them per request), so no dependency is needed to
add it now. Once tracing is enabled, every log line carries `[service,traceId,spanId]` and logs
correlate to spans automatically.

## 8. Enabling distributed tracing (deployment add)

Metrics and log-correlation context are produced today with no extra dependency. To also **export
spans**, a deployment adds a tracer bridge and an OTLP exporter. These are the only pieces not
already on the classpath:

1. Add to the service (or the shared `build.gradle.kts` `dependencies` block):
   `io.micrometer:micrometer-tracing-bridge-otel` and
   `io.opentelemetry:opentelemetry-exporter-otlp`.
2. Point the exporter at your collector, for example:
   ```
   management.otlp.tracing.endpoint=http://otel-collector:4318/v1/traces
   management.tracing.sampling.probability=0.1
   ```
3. No code change is required: the `Observation` in `AccessDecisionService` (and any future
   `Observation`) becomes a span, and the existing `logging.pattern.level` starts emitting real
   trace and span ids.

Until then the Observation API still produces metrics and the logging pattern still renders (with
empty ids); everything degrades gracefully.

## 9. Known gaps

These were found while building the stack. Each one sits in a file outside the observability work and is
reported as an open question.

- **da-discovery switches Prometheus export off under the `prod` profile**
  (`config/application-prod.yml`, `management.prometheus.metrics.export.enabled: false`). A prod
  deployment of da-discovery has no `/actuator/prometheus` until that line is removed or the variable
  `MANAGEMENT_PROMETHEUS_METRICS_EXPORT_ENABLED=true` is set.
- **da-discovery shows health detail to everyone under the `dev` profile**
  (`config/application-dev.yml`, `show-details: always`). Health is public, so the dev compose sets
  `MANAGEMENT_ENDPOINT_HEALTH_SHOW_DETAILS=when-authorized` for it. The file should say so itself.
- **da-autodelete does not enable the probe groups.** Its `application.properties` lacks
  `management.endpoint.health.probes.enabled=true`, so `/actuator/health/liveness` and `/readiness`
  answer 404 there. The dev compose sets the variable `MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED`.
- **da-reporting's mail health check** holds the aggregate health DOWN with placeholder SMTP
  credentials. The dev compose sets `MANAGEMENT_HEALTH_MAIL_ENABLED=false`, as the pilot overlay does.
- **da-mcp-pep answers 500 for an unknown path**, because its `GlobalExceptionHandler` catches
  `Exception`, which includes Spring's "no resource" exception. Spring would answer 404.
- **`management.metrics.export.prometheus.enabled`** in nine `application.properties` files is a
  Spring Boot 2 key. Boot 3 reads `management.prometheus.metrics.export.enabled` and defaults to true,
  so the line does nothing.
- **postgres_exporter connects as the dev superuser** (`dlap`). A production deployment should create a
  role with `pg_monitor` only; the init scripts are not part of this work.
- **da-discovery has no `ManagementPortContextTest`.** Its production-profile test boots a forked JVM
  with a stub OIDC server, which this work did not duplicate. The security chain is covered by
  `ActuatorExposureTest`.
- **The heartbeat gauge appears one scrape late.** A binding is unknown until its job exists, so the
  `dlap_logingest_backfill_heartbeat_age_seconds` series of a binding is registered by the first read of the
  job table that finds a `RUNNING` job of it (the scrape of `dlap_logingest_backfill_jobs` triggers it) and
  is exported from the next scrape on. A job that is stuck from its first second therefore needs one more
  scrape interval before the alert can start its 5 minutes.
- **Latency is a mean.** The services publish no histogram buckets. Setting
  `management.metrics.distribution.percentiles-histogram.http.server.requests=true` would allow
  percentile alerts, at a cost in series count.
- **No host or container metrics.** Add node exporter and cAdvisor, with their rules, when a pilot
  needs CPU, memory and disk alerts.

## 10. Verifying

| What | Command |
|---|---|
| Rules, config and Alertmanager syntax, and every alert on synthetic series | `bash deployment/platform/prometheus/check.sh` (Docker, starts no stack) |
| Security chain of every service | `./gradlew :<module>:test --tests '*ActuatorExposureTest' --tests '*JwtTokenValidatorTest'` |
| Whole service on a management port | `./gradlew :<module>:test --tests '*ManagementPortContextTest'` (Docker for all but da-mcp-pep) |
| The running stack | `verify-targets.sh` after the bring-up in section 3 |
