# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Upgrade notes

- **Database roles (ADR-0137 to ADR-0140, SEC-12).** Each service now has its own database password
  (`<DATABASE>_DB_PASSWORD`; see `.env.example`), and the Postgres exporter and the backup agent connect as
  `dlap_monitor` and `dlap_backup` instead of the superuser. Under the `prod` profile a published dev password or
  JWT validation that is off now stops the service. The pilot and demo overlays need all 13 role passwords: run
  `deployment/platform/database/gen-db-passwords.sh` on the env file first.
- **Existing quickstart volumes (Q-918).** A dev volume created before SEC-12 has no `dlap_monitor` role, so with the
  monitoring profile `postgres-exporter` reports `pg_up 0`. Run once:
  `docker exec -i dlap-postgresql bash -s -- --quiet --dev-defaults < deployment/platform/database/upgrades/sync-database-roles.sh`,
  or recreate the volume with `docker compose down -v`. A clean clone is not affected.
- **Existing demo Keycloak (ADR-0142, ID-04).** The demo now requires the `dlap-api` audience. Keycloak imports the
  realm file once, so a demo whose Keycloak database already holds the realm keeps the old clients and every service
  answers 401. Add the `dlap-api-audience` mapper to `dlap-ui` and `dlap-backend`, or delete the realm and let it
  re-import; the demo README has the steps.

### Added

- **Unity Catalog integration (ADR-0029).** Optional, profile-gated
  interoperability with an Apache Unity Catalog metastore. `da-catalog`
  mirrors tabular assets (BigQuery/Glue/warehouse) into UC as
  `catalog.schema.table` with DLAP labels as table properties
  (`dlap.catalog.unity.enabled`, default off; client
  `io.unitycatalog:unitycatalog-client`). The dead in-tree "Unity Catalog"
  stub in `da-discovery` was removed. A `unitycatalog` Docker service (compose
  `unity` profile) + a Testcontainers smoke test back it.
- Real cloud scanners for AWS (S3, RDS, Glue), Azure (Blob), and GCP (GCS, BigQuery) in `da-discovery`
- Real cloud-deletion implementations for AWS S3, Azure Blob, and GCP (GCS, BigQuery) in `da-autodelete`, with persistent compliance audit trail (`DeletionAuditEntity`)
- Discovery scan executor with async dispatch from Kafka consumer to scanner factory; results published on `dalab.discovery.asset.discovered`
- da-autodelete catalog Feign client + `AssetLocatorResolver` (catalog lookup with URI-parser fallback)
- Apache 2.0 license headers across all source files; `LICENSE`, `NOTICE`, `CODE_OF_CONDUCT.md`, `SECURITY.md`, `CONTRIBUTING.md`, `CHANGELOG.md`, `.github/` templates
- Per-service global `@RestControllerAdvice` exception handlers (da-admin-service, da-autocompliance)
- Spring Security `AuditorAware` implementation for `@CreatedBy`/`@LastModifiedBy` in da-admin-service
- Real `LabelFeedbackEntity` persistence in da-autolabel
- Defensive helpers (`asMap`, `asStringList`, `asBoolean`, `parseUuidOrNull`) replacing unchecked JSON casts in da-catalog
- Full audit findings, decision log and setbacks register in the pre-release operating plan (not published)

### Changed

- `da-protos` is now the canonical source of truth for `Asset`/`Label` entities (duplicate definitions in da-catalog removed)
- `MockLLMClient` in da-autolabel is now opt-in via `dalab.autolabel.allow-mock=false` (default); throws `LlmProviderNotConfiguredException` otherwise
- Hardcoded mock data removed from production paths in da-policyengine, da-reporting, da-autocompliance, da-catalog (replaced with `dataAvailable: false` semantics)
- Approval flows in da-autoarchival and da-autodelete are now wired to async execution via lazy self-proxy pattern
- Kafka reliability standardized: manual `MANUAL_IMMEDIATE` ack + `DeadLetterPublishingRecoverer` + `ExponentialBackOff(1000, 2.0)` capped at 60s

### Fixed

- `da-reporting/PolicyActionConsumer` uninitialized `notificationDispatched` variable
- `da-admin-service/SpringSecurityAuditorAware` empty file (would have failed bean init)
- `da-admin-service/UserServiceImpl.updateUser` NPE on empty credentials list
- `da-autocompliance/ComplianceServiceImpl.evaluateControl` unconditional `COMPLETED_SUCCESS`
- `da-autocompliance/ComplianceServiceImpl.getMonitoredRegulations` null Pageable NPE
- `da-autodelete/GcpDeletionProvider` uncaught `InterruptedException`
- Pre-existing Spring Kafka import errors in `KafkaConsumerConfig` / `KafkaReliabilityConfiguration` across 3 services
- A broken policy no longer fails open in two last cases (POL-04, ADR-0136, Q-870, Q-871): a document whose `spec.effect` reads `deny` is kept as an invalid deny whatever its `kind` says, and an ENABLED policy row of kind `DSL` with a null or blank document denies every request (`policy-store-invalid`). New Flyway `V10__policy_kind.sql` in `da-policyengine` adds `policies.policy_kind` (`LEGACY_RULE` or `DSL`, NOT NULL) and backfills it from `policy_document`

### Security

- AES/ECB cipher in `da-admin-service/BasicEncryptionServiceImpl` flagged with `@PostConstruct` warn log; migration to AES/GCM tracked under M2 hardening
- All hardcoded credentials must use `${ENV_VAR:placeholder}` pattern (M2 audit in progress)

## [0.2.0] - 2026-06-07

First release under the **DLAP** name. The project (internal codename
"DALab") is renamed to **DLAP - Data Lifecycle Automation Platform** for
the Apache Software Foundation donation. **DALab / Data Automation Labs
(dalab.ai)** is retained as the commercial donor brand (Yahoo → Hadoop
pattern). See [ADR-0027](docs/DECISIONS.md). The step-by-step migration guide
(`migration/RENAME.md`) was removed before the public launch; it stays in the
repository history.

### Added

- **AWS provider (N5).** Real S3, RDS, and Glue Data Catalog discovery
  scanners in `da-discovery` - each lists live resources via the AWS SDK
  v2 and publishes a `ResourceEvent` per resource into the
  `dlap.discovery.asset.discovered` pipeline. Scanners are gated on
  `cloud.provider.aws.enabled` and share an `AwsClientFactory`
  (IAM access-key + EC2/EKS IMDS workload identity, with an optional
  endpoint override for LocalStack). JOINED-inheritance child tables
  `s3_resource`, `rds_resource`, `glue_resource` (Flyway V3-V5). The S3
  vertical is verified end-to-end against LocalStack
  (`S3ResourceCrawlerLocalStackTest`); RDS and Glue are mock-verified
  (LocalStack community does not implement those APIs - Pro features).
- **AWS CloudTrail audit-log source (N5)** in `da-logingest`, paralleling
  `GcpLogSource` (ADR-0023): pull-based, Kafka-only. Polls the CloudTrail
  `LookupEvents` API per region, normalizes events to the canonical
  `DlapLogEvent`, and resumes from a per-region watermark. Gated on
  `dlap.logingest.aws.enabled` + the `aws`/`prod` profile.
- **`CloudResource` shared model (N4)** in `da-protos` - a richer
  cloud-resource entity for large-asset metadata, cross-region
  replication, and asset-of-assets hierarchies (ADR-0028). Flyway
  bootstrap in `dlap_common`; caller migration guidance in
  [docs/specs/cloud-resource.md](docs/specs/cloud-resource.md).
- **LocalStack `aws` Compose profile** + README quickstart for running
  the AWS provider without a real AWS account.

### Changed

- **Migration tooling (N3).** `da-discovery` ported from Liquibase to
  Flyway SQL, aligning it with every other service (CLAUDE.md §6). Schema
  baselined as `V1__init_discovery_schema.sql`; verified by
  `DiscoveryFlywayMigrationTest` (Testcontainers Postgres, `ddl-auto=validate`).
- **Provider isolation (N2).** Cloud-provider code physically relocated
  under `providers/<cloud>/`, enforced by an ArchUnit boundary test
  (`ProviderBoundaryArchTest`) so `common`/`core` cannot depend on
  provider SDKs (Oracle excepted, ADR-0026).

### ⚠️ Breaking changes

- **Kafka topic prefix** renamed `dalab.*` → `dlap.*` (all topic names,
  e.g. `dlap.log.event.normalized`, `dlap.assets.changes`).
- **Environment-variable prefix** renamed `DALAB_*` → `DLAP_*`
  (e.g. `DLAP_ENCRYPTION_KEY`, `DLAP_LOGINGEST_GCP_ENABLED`).
- **Config-property prefix** renamed `dalab.*` → `dlap.*`.
- **Java packages** renamed `com.dalab.*` → `com.dlap.*`;
  `DalabConstants` → `DlapConstants`; proto `DalabLogEvent` →
  `DlapLogEvent`, namespace `dalab.log.v1` → `dlap.log.v1`.
- **Database identifiers** renamed `dalab_*` → `dlap_*` (schemas,
  tables, columns, DB roles). Existing databases require migration.
- **Keycloak realm** renamed `dalab` → `dlap`; clients `dalab-ui`,
  `dalab-services`, `dalab-backend` → `dlap-*`. Realm re-import required.
- **Policy DSL** `apiVersion` `dalab/v1` → `dlap/v1`.
- **Gradle root project** `microservices-dalab` → `microservices-dlap`.

### Migration

- The full step-by-step migration path (`migration/RENAME.md`) was removed before the
  public launch; it stays in the repository history.
- **Hard cutover - no dual-publish shim.** The `dalab.*` → `dlap.*` topic
  rename has no backward-compatibility window: this platform has no external
  consumers yet, so asset-change events publish only to the `dlap.*` topics.
  (An interim 30-day dual-publish shim for `dalab.assets.changes` was added
  during development and removed before release - no consumer depended on it.)

## [0.1.0] - 2026-05-12

First public Apache-2.0 OSS release. Demo-quality + production fundamentals,
GCP-first cloud scope (AWS in 0.2.0; Azure in 0.3.0), object storage + cloud
databases as the v0.1.0 asset bestiary. Single-tenant per deployment per
ADR-0013. Apache incubation proposal is a post-v0.1.0 milestone per ADR-0012.

### Added: completion milestones

- **M0 Foundations** (tag `m0-foundations`). Killed mock fallbacks on
  prod paths (`CloudProvidersConfig`'s `mock-project-id` BigQuery/Storage
  fallbacks, `AWSLoggingAdapter.createSampleLogEntry`,
  `MockAdminServiceClient` nested in `DiscoveryJobController`). Wired
  real Kafka publish for `triggerLabelingRun`. Apache 2.0 headers on 98
  TS/TSX files. Retired six legacy planning docs (3165 lines) into the
  `/docs` scaffold. Naming-audit clean.
- **M1.1 cleanup wave** (tag `m1.1-cleanup`). `AWSDiscoveryServiceImpl`
  gated `@Profile("aws")` with v0.2.0 deferral messages.
  `KafkaReliabilityConfiguration` (manual ack + DLQ + ExponentialBackOff)
  ported to five services that lacked it. `@WebMvcTest` +
  `@DataJpaTest` (Testcontainers Postgres `@ServiceConnection`)
  coverage added to `da-policyengine` and `da-reporting`.
- **M1.2 phase-1 surgical wave** (tag `m1.2-phase-1`). R-P1-12 phase-1
  surface split: four focused interfaces (KPI, controls, reports,
  scans) + thin facades. R-P0-4 (Liquibase → Flyway) and R-P0-5
  (providers/<cloud>/) accepted as ADR-0008 + ADR-0004 directives;
  physical migrations deferred to v0.2.0 per ADR-0025.
- **M2 Log-event spine** (tag `m2-log-spine`). DalabLogEvent v1
  protobuf at `da-protos/src/main/proto/dalab/log/v1/log_event.proto`
  + human spec at `docs/specs/log-event.md`. New `da-logingest`
  service (port 8090): Spring Boot skeleton, Flyway, Keycloak,
  KafkaReliabilityConfiguration, idempotent producer (acks=all +
  enable.idempotence=true), HTTP ingest at
  `POST /api/v1/logingest/events` (used by tests + replay), 90-day
  default retention.
- **M3 Discovery (code-side)**. `da-catalog/AssetService` dual-publishes
  asset upserts to the legacy `dalab.asset.change.event` AND the
  dedicated `dalab.catalog.asset.upserted` topic for M4 to consume.
  GCP Cloud Logging API pull-based source (`GcpLogSource`, ADR-0023)
  with watermark resume across restarts; emits to
  `dalab.log.event.normalized`. Existing GCP crawlers (GCSResourceCrawler,
  BigQueryCrawler, CloudSQLInstanceCrawler) verified as functional
  skeletons; full GCP integration verification (>=100 assets in 60s,
  >=3 asset kinds) blocked on owner GCP service-account JSON.
- **M4 Auto-Label real wiring**. `AssetUpsertedConsumer` Kafka
  listener on `dalab.catalog.asset.upserted` ->
  `AutoLabelOrchestratorImpl` fetches asset via Feign, calls the
  configured `ILLMClient.suggestLabelsWithConfidence` (Ollama
  default per ADR-0015; OpenAI opt-in), splits on
  `dalab.autolabel.confidence-threshold` (default 0.7), applies
  confident labels, queues sub-threshold suggestions in
  `dalab_autolabel.manual_review_queue` (V2 Flyway).
- **M5 Policy + Auto-Archive + Auto-Delete** (tag
  `m5-policy-archive-delete`). Policy DSL v1.1 spec at
  `docs/specs/policy-dsl.md` with parser + evaluator +
  LifecyclePolicyConsumer. Auto-archive: existing
  `da-autoarchival/PolicyActionEventListener` wired in M0. Auto-delete:
  new `IDeletionService` + `DeletionServiceImpl` with 30-day
  soft-delete window, idempotent on assetId, scheduled hard-delete
  sweeper wired to real `ICloudDeletionService` adapters (dryRun off
  by default), `RestoreController` exposing
  `POST /api/v1/autodelete/restores/{id}` for operator restore. V3
  Flyway migration adds `soft_delete_records` table with
  status CHECK constraint.
- **M6 Compliance + Reporting + Admin** (tag
  `m6-compliance-report-admin`). CSV export endpoint at
  `GET /api/v1/reporting/exports/csv/{generatedReportId}` (RFC-4180
  rendering). `BasicEncryptionServiceImpl` AES-256/GCM envelope
  encryption with v1 ECB legacy-decrypt fallback. `da-autocompliance`
  regulations + violations + KPIs. Full admin service with user CRUD
  + cloud-connection management.
- **M7 Demo UI** (tag `m7-demo-ui`). README's Demo Tour section walks
  the v0.1.0 happy path. PolicyEditor.tsx YAML policy editor lands in
  the admin UI; DataCatalog.tsx swapped from Unity-Catalog endpoint to
  real da-catalog API. Existing 30+ pages in the
  `Data-Automation-Lab/dlap-ui` submodule cover the seven demo screens.
- **M8 Public OSS launch prep** (tag `m8-launch-prep`). README quickstart
  verified under 5 minutes from fresh clone. `docs/launch/test-without-gcp.md`
  documents the synthetic-event path (DemoEventSeeder injects 50
  events covering the full lifecycle mix; tests ~90% of the runtime
  spine without real GCP creds). `docs/launch/keycloak-prod-setup.md`
  documents production realm hardening (M2M client rotation, CORS
  narrowing, IdP federation, audit-log forwarding).

### Added: open-standard surface area

- `docs/specs/log-event.md`: DalabLogEvent v1 wire format
  (cloud-neutral audit-log normalization). Producers conform; adopters
  building their own evaluators can target this schema.
- `docs/specs/policy-dsl.md`: Policy DSL v1.1 (YAML schema for
  triggers, match clauses, action types, parameters; JSON wire format
  for `dalab.policy.action.event`).

### Added: infrastructure

- `dalab.log.event.normalized` Kafka topic (LOG_EVENT_NORMALIZED).
- `dalab.catalog.asset.upserted` Kafka topic (CATALOG_ASSET_UPSERTED).
- Five typed lifecycle topics: `dalab.discovery.asset.{discovered,
  modified, deleted, accessed, permission_changed, archived}`.
- `dalab.policy.action.event` Kafka topic for policy-driven actions.
- `DalabConstants.ServicePort` registry (single source of truth: 8081-8090).
- `DalabConstants.Schema.LOGINGEST` schema name.
- `da_logingest_user` + `da_logingest` database in both dev and
  platform init-script copies.
- `dalab-services` Keycloak M2M client (client_credentials only,
  refresh-tokens disabled, 1h access-token lifespan) with auto-config
  in `da-protos`: `ServiceTokenProvider` + `ServiceAuthAutoConfiguration`
  off by default (`dalab.service-auth.enabled=false`); production
  flips on per `docs/launch/keycloak-prod-setup.md`.
- `.gitignore` regression fix: removed overbroad
  `**/src/main/resources/db/` rule that ignored every service's
  Flyway migration tree.

### Removed

- Gemini surface area (config, build dep, factory branch, docs)
  per ADR-0009 / R-P1-1. v0.1.0 ships OpenAI + Ollama only behind
  the `ILLMClient` abstraction. `GeminiLLMClient` lands in 0.2.0
  if needed.
- Google Pub/Sub subscriber path (replaced by Kafka-only event
  transport per ADR-0023).

### Decisions

ADRs 0008-0025 captured in `docs/DECISIONS.md`. Highlights: Flyway
port for da-discovery (0008), Gemini purged from v0.1.0 (0009),
`da-logingest` as a new service (0010), keep DALab name (0011),
solo-owner Apache-ready fork now (0012), single-tenant per deployment
(0013), Postgres-only persistence (0014), Ollama as default LLM
(0015), reference impl IS the platform (0016), continuous execution
between milestone gates (0017), trademark name-clearance is P0 before
launch (0018), `feature/deployment` is the long-running execution
branch (0019), god-class split deferred to M1.2 (0020), end-to-end
log-enriched architecture under policy control (0021), CloudResource
entity deferred to v0.2.0 (0022), Kafka-only event transport / no
cloud-native pub-sub (0023), ComplianceServiceImpl physical split
deferred to v0.2.0 phase-2 (0024), R-P0-4 + R-P0-5 split into
phase-1 (markers) for v0.1.0 + phase-2 (physical migration) for
v0.2.0 (0025).

### Standing items deferred to v0.2.0

- **R-P0-4 phase-2**: physical migration of `da-discovery` Liquibase
  changelogs to `V1...VN` Flyway SQL.
- **R-P0-5 phase-2**: physical relocation of cloud-specific code
  from `da-discovery/common/**` into top-level `providers/<cloud>/`
  packages + ArchUnit boundary rule.
- **R-P1-12 phase-2**: physical split of
  `da-autocompliance/ComplianceServiceImpl` (1188-line god-class)
  into focused services behind the four phase-1 interfaces.
- **P0-E**: CloudResource entity deferred per ADR-0022 - current
  Asset model covers v0.1.0's GCP-first scope.
- **M3 GCP integration verification**: >=100 real assets in 60s,
  >=3 asset kinds. Synthetic-event path in
  `docs/launch/test-without-gcp.md` covers ~90% of confidence;
  remaining ~10% needs owner GCP service-account JSON.

### Owner-side launch tasks (not blockers for code freeze)

- GPG-sign + push `v0.1.0` annotated tag (sandbox tag is unsigned).
- Merge `feature/deployment` to `main` (or fast-forward).
- Apache trademark name-clearance per ADR-0018.
- Apache incubation proposal cut per ADR-0012 (post-v0.1.0).
- Production Keycloak realm hardening per `docs/launch/keycloak-prod-setup.md`.
- Rotate any pre-scrub dev secrets that ever booted in a non-dev environment.
