# DLAP Architecture Overview

DLAP (Data Lifecycle Automation Platform) is a **cloud-neutral,
log-enriched** platform and open standard for end-to-end data lifecycle
management. This document describes the runtime architecture: the lifecycle
stages, the services, the Kafka log-event spine, the access-decision plane,
the asset/lineage model, data stores, and authentication. It is written to
match the code at HEAD; where a detail is service-local, it points at the
service rather than restating it.

The two defining properties:

- **Cloud-neutral.** No cloud-provider SDK is imported in the neutral core
  (`core/`, `api/`, `common/`, `da-protos/`). Provider code lives only under
  `providers/<cloud>/` (CLAUDE.md §9, ADR-0004).
- **Log-enriched.** Lifecycle and access decisions are driven by audit- and
  application-log signals normalized onto one spine - DLAP reconstructs
  truth about assets, owners, and lineage from logs, not from the tools that
  emit them.

---

## Status and limits

DLAP is pre-release software: the root build declares
`version = "0.0.1-SNAPSHOT"` (`build.gradle.kts:29`) and changes accumulate
under `[Unreleased]` in `CHANGELOG.md`. This document states what the code does
today. A capability that has no code path cited here is roadmap, not shipped.

| Topic | State today | Evidence |
|---|---|---|
| Tenancy | Single-tenant per deployment: one Keycloak realm, no tenant column on any shared table. Multi-tenancy is roadmap and is not implemented. | ADR-0013 (`docs/DECISIONS.md:1356`); no `tenant` column in `deployment/platform/database/init-scripts/02-create-common-schema.sql` |
| Production readiness | The compose stack under `deployment/environments/dev/` is a quickstart: embedded-file Keycloak (`start-dev`, `KC_DB=dev-file`), seeded demo users, dev secret defaults. Production hardening is a documented checklist, not a shipped and supported topology. | ADR-0037 (`docs/DECISIONS.md:316`); `deployment/environments/dev/docker-compose.yml:81,86`; `deployment/PRODUCTION.md` |
| Availability | No uptime target or SLA is offered, and no replication or failover is configured in the shipped compose files (no `replicas` setting). | `deployment/environments/dev/docker-compose.microservices.yml` |
| Certifications | None. No SOC 2, ISO 27001, HIPAA or similar attestation has been obtained, and none is claimed. | n/a (the absence of an attestation has no code path) |
| Regulatory frameworks | DLAP does not certify or guarantee compliance with any framework. It ingests posture signals from the customer's tools and projects them onto labels ([../specs/compliance-labels.md](../specs/compliance-labels.md)). The only built-in checks are three catalog-backed controls: public exposure, missing encryption marker on sensitive assets, and EU data residency. | `AssetComplianceEvaluator.java:69-71` |
| Labeling in the quickstart | The dev compose sets `dlap.autolabel.llm-provider` to `mock` with `allow-mock` true. `MockLLMClient` draws 2 to 5 labels at random from a fixed list with a random confidence between 0.5 and 1.0, so quickstart labels are placeholders, not classification. Real labels need the `ollama` or `openai` provider. | `deployment/environments/dev/docker-compose.microservices.yml:311`; `MockLLMClient.java:47-50,127-136` |
| Dashboard figures | The CDO governance score and cost savings include fixed placeholder constants and synthetic trend points. See [../user-guides/cdo-guide.md](../user-guides/cdo-guide.md). | `DashboardService.java:58,79,86,169-186,261,288-309` |

---

## 1. Lifecycle stages

DLAP models the data lifecycle as five stages. The published five-part
vision frames them as **Born → Enriched → Governed → Consumed → Retired**;
the operator-facing agent stages are **Discovery → Label → Govern → Consume
→ Retire**:

| Stage | What happens | Primary owner |
|---|---|---|
| **Discovery** (Born) | Assets are found across clouds and normalized into the catalog | `da-discovery` → `da-catalog` |
| **Label** (Enriched) | Sensitivity/compliance/domain labels are applied by an LLM provider behind `ILLMClient`; the quickstart wires a mock provider (see Status and limits) | `da-autolabel` |
| **Govern** (Governed) | Policies match assets and fire lifecycle actions | `da-policyengine` |
| **Consume** (Consumed) | Per-request access verdicts at read time (AuthZEN PDP) | `da-policyengine` PDP + PEPs |
| **Retire** (Retired) | Policy-driven archival and audited deletion | `da-autoarchival`, `da-autodelete` |

The **Consume** stage is the AuthZEN control plane added by ADR-0040 - the
one stage that answers the per-request "may this agent read this asset now?"
question. It is implemented as an extension of `da-policyengine`, not a new
service.

---

## 2. Services

Ten Spring Boot microservices, the shared `da-protos` library, a reference
Policy Enforcement Point, and the React UI. Ports below are the
**host-published** ports from the canonical dev quickstart
(`deployment/environments/dev/`, ADR-0037); inside the container network
most services listen on `8080` and are mapped out.

| Component | Port (host) | Responsibility | Notes |
|---|---:|---|---|
| `da-discovery` | 8091* | Discovery jobs + resource crawlers; coverage differs per cloud, see the coverage table below | *host 8081 is the API gateway; da-discovery is remapped to 8091 in the microservices compose. Stage: Discovery. |
| `da-catalog` | 8082 | Canonical asset + label catalog; system of record for `Asset` | Writes `dlap_common.dlap_assets`. |
| `da-policyengine` | 8083 | Policy definitions, evaluation, Event Center; **AuthZEN PDP** (`/access/v1/evaluation`) | Stage: Govern + Consume. |
| `da-reporting` | 8084 | Cross-service dashboards, report definitions, CSV export, email and Slack notifications | Dashboard scores include placeholder estimates (see Status and limits). Actuator on a separate management port `8380` (unpublished) - health is checked via the container healthcheck, not `localhost:8084`. Stage: Report. |
| `da-autolabel` | 8085 | LLM-assisted labeling via `ILLMClient` | Stage: Label. |
| `da-autoarchival` | 8086 | Archival workflows: moves data to a colder storage class through per-cloud services (`AwsS3StorageService`, `GcsCloudStorageService`, `AzureCloudStorageService`) | Stage: Retire (Archive). No immutable or WORM storage mode exists in the code. |
| `da-autodelete` | 8087 | Soft-delete window, then hard delete through each cloud's delete API, with a persisted audit record (`DeletionAuditEntity`) | Stage: Retire (Delete). `DeletionType.SECURE_OVERWRITE` is declared (`DeletionType.java:39`) but no implementation uses it, so there is no overwrite or certified-erasure mode. |
| `da-autocompliance` | 8088 | Compliance posture → labels; control roll-up | Ingests posture, projects onto labels (ADR-0035). |
| `da-admin-service` | 8089 | Users, cloud connections, system admin | Credential store SPI (ADR-0036). |
| `da-logingest` | 8090 | Cloud audit-log ingest → normalized `DlapLogEvent` spine | The log-enriched entry point (ADR-0010, ADR-0023). |
| `da-protos` | - | Shared entities (`Asset`, `Label`, `User`, `AssetLabelAssignment`, `CloudResource`) + `DlapConstants` | Library, not a service. Single source of truth for shared types. |
| `da-mcp-pep` | 8091** | **Reference** MCP Policy Enforcement Point - fronts an MCP server, enforces AuthZEN verdicts from the PDP | **Standalone reference PEP (ADR-0040 / V4-C6); **not** in the canonical compose. Stateless: no DB, no Kafka. Its `server.port=8091` is its own default; run it separately. |
| `new-ui` | 3000 | React 18 + TypeScript operator UI | Submodule; optional. Business logic stays server-side. |
| gateway | 8081 | nginx API gateway / SPA host in front of the services | Dev compose. |

> Historical note: earlier docs describe "nine services." The current tree
> has **ten** running `da-*` services (`da-logingest` was added per ADR-0010)
> plus `da-mcp-pep` (a standalone reference PEP). The `docs/GLOSSARY.md`
> service list is stale on this point; the code and the root `README.md`
> service catalog are authoritative.

### Cloud coverage at HEAD

What exists in code, per cloud. "Present" means the class is in the tree; this
table does not claim each path has been exercised against a live account.

| Cloud | Resource crawlers (`da-discovery`, `crawler/service/<cloud>/`) | Audit-log ingest (`da-logingest`, `source/<cloud>/`) | Gaps |
|---|---|---|---|
| AWS | S3, Glue, RDS, EC2, processing jobs (`S3ResourceCrawler`, `GlueResourceCrawler`, `RdsResourceCrawler`, `EC2InstanceCrawler`, `AwsProcessingJobResourceCrawler`) | CloudTrail `LookupEvents` (management events), polled per region: `CloudTrailLogSource.java`. Off by default (`dlap.logingest.aws.enabled`, `application.properties:109`), active only under the `aws` or `prod` profile (`CloudTrailLogSource.java:94-95`). | CloudWatch Logs ingest is not implemented: no code path calls the CloudWatch Logs API (`da-logingest/build.gradle.kts:40` depends only on the `cloudtrail` SDK module). Roadmap. `LookupEvents` returns management events, so S3 object-level data events are not visible through this source. |
| GCP | GCS, BigQuery, Cloud SQL, BigQuery jobs (`GCSResourceCrawler`, `BigQueryCrawler`, `CloudSQLInstanceCrawler`, `GcpBigQueryJobResourceCrawler`) | Cloud Logging `entries.list`, audit-log family: `GcpLogSource.java`. Off by default (`dlap.logingest.gcp.enabled`), active only under the `gcp` or `prod` profile (class annotations in `GcpLogSource.java`). | Not assessed here. Object reads appear only if the customer enables Data Access audit logs in GCP, which is a GCP setting that DLAP does not control. |
| Azure | Data Factory and Synapse processing jobs (`AzureProcessingJobResourceCrawler`). The VM crawler `AzureVirtualMachineCrawler` publishes nothing: it does not fabricate placeholder VMs (ADR-0017). | None. `da-logingest` has no Azure source package. | Azure Monitor and Activity Log analysis is not implemented: `AzureLogAnalyzer.java:124-129,159,185` logs a warning and emits no events. Roadmap. Treat Azure as experimental. |
| Databricks and Unity Catalog | Databricks processing jobs (`DatabricksProcessingJobResourceCrawler`), Unity Catalog (`UnityCatalogResourceCrawler`) | None | Optional catalog mirroring into a Unity Catalog metastore is off by default (`dlap.catalog.unity.enabled`, ADR-0029). |
| OCI | Compute instances (`OracleComputeInstanceCrawler`) | None | Gated off by default; productionization is deferred (ADR-0026, `docs/DECISIONS.md:874`). |

Without a cloud account, events enter through the authenticated HTTP endpoint
`POST /api/v1/logingest/events` (`IngestController.java:51,68`), the synthetic
`DemoEventSeeder`, or the offline `FixtureReplaySeeder` (both off by default,
`application.properties:122,129`).

---

## 3. The Kafka log-event spine

Kafka (KRaft mode) is the **only** event transport inside DLAP - no
cloud-native pub/sub is used as a transport plane (ADR-0023). Per-cloud log
adapters *pull* audit events using each cloud's log-query API and publish
one normalized shape. Topic names are constants in
`DlapConstants.KafkaTopic` (`da-protos`) - never scattered string literals.

The spine, end to end:

1. **Ingest.** `da-logingest` pulls cloud audit logs and publishes normalized
   events to **`dlap.log.event.normalized`** (`LOG_EVENT_NORMALIZED`). The
   implemented sources are GCP Cloud Logging and AWS CloudTrail, both
   pull plus watermark (ADR-0023); see the coverage table above. Azure Monitor
   and CloudWatch Logs sources are roadmap. Discovery crawlers also feed this
   spine via a synthetic `DlapLogEvent` (ADR-0033), so crawler discoveries
   reach the catalog through the same path.
2. **Route.** `DiscoveryLogEventProcessor` routes each normalized event by
   `changeType` to typed lifecycle topics:
   `dlap.discovery.asset.discovered` / `.modified` / `.deleted` /
   `.accessed` / `.permission-changed` / `.archived`. The producer enforces
   the flat `DlapLogEvent` wire contract (ADR-0038); the router is a thin
   router that DLQs on a missing `changeType` as a true contract violation.
3. **Catalog.** `da-catalog` consumes the typed topics and upserts `Asset`
   rows (idempotent by `assetUri`), harvesting owner/team and deriving
   lineage from the event.
4. **Label.** Labeling requests flow on `dlap.labeling.request.event`;
   labels are applied and label-assignment changes announced on
   `dlap.asset.label.assignment.changed`.
5. **Govern.** `da-policyengine` matches assets and emits policy actions on
   **`dlap.policies.actions`** (`POLICY_ACTION_EVENT`), which the archival,
   deletion, and labeling consumers act on
   (`dlap.archival.request.event`, `dlap.deletion.request.event`, …).
6. **Consume (audit).** Every AuthZEN access verdict emits a decision-log
   event on **`dlap.access.decision`** (`ACCESS_DECISION`) - the single
   replayable audit trail for the Consumed stage. Enforcement-point access
   logs returning via `da-logingest` are joined against it to detect drift
   between verdicts and actual reads (ADR-0040).

Kafka consumers use manual acknowledgment with dead-letter handling,
configured per service (`KafkaReliabilityConfiguration.java` in
`da-admin-service`, `da-autoarchival`, `da-autocompliance`, `da-autodelete`,
`da-autolabel`, `da-discovery`, `da-logingest` and `da-policyengine`;
`DiscoveryKafkaConfiguration.java` in `da-catalog`; `KafkaConsumerConfig.java`
in `da-reporting`). Idempotent producers are set explicitly in `da-discovery`
(`KafkaConfiguration.java:180,231`) and `da-logingest`
(`application.properties:69`); the other services rely on the Kafka client
default, which is idempotent from Kafka 3.0 (Spring Boot 3.2.5,
`build.gradle.kts:21`). Delivery is at least once; downstream upserts are
idempotent (ADR-0033).

---

## 4. The AuthZEN access-decision plane (Consumed stage)

DLAP ships the **decision, not the doorway** (ADR-0040). `da-policyengine`
is an **OpenID AuthZEN Authorization API 1.0** Policy Decision Point (PDP):

- **Endpoints:** `POST /access/v1/evaluation` (single) and
  `/access/v1/evaluations` (batch), served by `da-policyengine` (8083),
  Keycloak-authenticated. PEPs authenticate as M2M service accounts
  (`ROLE_SERVICE_ACCOUNT`, ADR-0034). Spec:
  [../specs/enforcement-contract.md](../specs/enforcement-contract.md).
- **Policy model:** access intent is a `kind: AccessPolicy` in the existing
  `dlap/v1` DSL (ADR-0031) - `spec.subjects`, `spec.actions`, `spec.match`
  (the same asset-selector clauses lifecycle policies use - labels are the
  join point), optional `spec.condition` (purpose/consent/region),
  `spec.effect` + `spec.obligations` (mask/redact). One DSL, two kinds.
- **Combining algorithm:** explicit deny overrides allow; obligations union
  to most-restrictive; the default decision is configuration
  (`dlap.pdp.default-decision`, ships `deny`). Evaluation is deterministic:
  no LLM in the verdict path (AI senses; compiled policy decides).

Enforcement is **pluggable**, mirroring the orchestrator pattern:

- **Pull (primary):** customers' existing MCP gateways / OPA sidecars /
  Keycloak call the AuthZEN endpoint. DLAP builds no gateway.
- **Push-down (supplement):** a `PolicyProjectionSink` SPI compiles
  AccessPolicies into platform-native controls (Unity Catalog grants first).
  Detection-grade; the AuthZEN path is the source of truth.
- **Reference PEP:** `da-mcp-pep` is the thin reference enforcement point:
  it maps an MCP access request to an AuthZEN evaluation, POSTs it to the PDP
  (`${dlap.pdp.base-url}/access/v1/evaluation`), caches verdicts briefly, and
  applies obligations. It fails **closed** (deny) when the PDP is
  unavailable in `enforce` mode; `observe` mode fails open with an audit
  flag. See `da-mcp-pep`.

---

## 5. Asset and lineage model

The canonical asset is `Asset` in `da-protos`
(`dlap_common.dlap_assets`) - the lightweight, cloud-neutral cross-service
asset keyed by `assetUri`, the join key everything references (labels,
policies, lineage, access decisions). See
[../specs/asset-descriptor.md](../specs/asset-descriptor.md).

- **Job / lineage:** log events harvest each asset's owner/team and derive
  **lineage edges** between assets from the raw event. Lineage + harvested
  metadata are the substrate the policy engine and agentic capabilities
  reason over (specs README §"How they fit together").
- **CloudResource:** a richer, *optional* model (`da-protos`,
  `dlap_common.dlap_cloud_resources`) carrying provider-native detail:
  hierarchy (`parent_resource_id` → asset-of-assets), cross-region
  replication, and the raw provider payload - linked to an `Asset` by a
  nullable `asset_id`. It **complements** `Asset`, it does not replace it
  (ADR-0028). `da-discovery` also has an internal `CloudResource` inventory
  that is the future convergence target. See
  [../specs/cloud-resource.md](../specs/cloud-resource.md).
- **Labels:** the governance vocabulary applied to assets, with provenance
  (USER / SYSTEM / SMART_LABEL_ML / SMART_LABEL_RULE / POLICY_ENGINE) and
  confidence. See [../specs/label.md](../specs/label.md).

---

## 6. Data stores

- **PostgreSQL 15, per-service schema.** Each service owns its own schema
  and manages it with **Flyway** SQL migrations
  (`<service>/src/main/resources/db/migration/V{N}__*.sql`). Hibernate
  `ddl-auto` is `validate` in production (CLAUDE.md §6).
- **Shared `dlap_common` schema.** Bootstrapped by raw SQL init scripts in
  `deployment/platform/database/init-scripts/` (`00`-`03`), mounted by the
  compose - **not** Flyway, because multiple services must read the schema
  before any one of them runs migrations. Its tables: `dlap_users`,
  `dlap_labels`, `dlap_assets`, `dlap_asset_label_assignments`,
  `dlap_cloud_resources`. Changes go in `02-create-common-schema.sql` plus a
  delta under `deployment/platform/database/upgrades/`.
- **Redis** - caching for `da-catalog` / `da-reporting` (optional; reporting
  falls back to an in-memory cache, Q-013).

---

## 7. Authentication and authorization

- **Keycloak** issues JWTs; every service has its own
  `SecurityConfiguration.java` mapping realm roles to Spring Security
  authorities. Controller methods carry `@PreAuthorize`, except
  `/actuator/health`, OpenAPI, and the stateless reference PEP `da-mcp-pep`,
  which has none by design (`McpPepController.java:45`).
- **4-tier Access Model (ADR-0032)** over nine human realm roles plus the
  machine role `ROLE_SERVICE_ACCOUNT`, with Keycloak as the single source of
  truth for role names (`DlapConstants.java:147-169`):
  - **Executive** - `ADMIN`, `CDO`
  - **Leadership** - `DIRECTOR`, `POLICY_MANAGER`
  - **Practitioner** - `DATA_ENGINEER`, `DATA_SCIENTIST`
  - **Reader** - `DATA_ANALYST`, `AUDITOR`, `VIEWER`

  The normative persona → role → permission matrix is published at
  [../specs/access-model.md](../specs/access-model.md).
- **M2M (service-to-service)** calls use a Keycloak client-credentials
  token bound to `ROLE_SERVICE_ACCOUNT`, granted by an explicit
  per-endpoint allow-list - the service account is **not** an admin
  (ADR-0034). This is the same principal PEPs present to the PDP.
- **Credentials for cloud connections** resolve through a pluggable
  `CredentialProvider` SPI (`encrypted-store` default / `secret-manager` /
  `federated`), selected by `dlap.credentials.provider` (ADR-0036).

---

## 8. Component diagram (text)

```
                         ┌───────────────┐
                         │    new-ui     │  React SPA (:3000)
                         │  (operator)   │
                         └───────┬───────┘
                                 │  REST (JWT)
                         ┌───────▼───────┐
                         │  gateway :8081│  nginx
                         └───────┬───────┘
                                 │
   ┌─────────────────────────────┼──────────────────────────────────────┐
   │                             REST / @PreAuthorize (Keycloak :8180)    │
   │                                                                      │
   │  ┌────────────┐   ┌───────────┐   ┌──────────────┐   ┌───────────┐   │
   │  │da-discovery│   │da-catalog │   │da-policyengine│  │da-autolabel│   │
   │  │  :8091     │   │  :8082    │   │  :8083        │  │  :8085     │   │
   │  │ crawlers   │   │ SoR:Asset │   │ policy + PDP  │  │  ILLMClient│   │
   │  └─────┬──────┘   └────┬──────┘   │/access/v1/... │  └─────┬─────┘   │
   │        │               │          └──────┬────────┘        │         │
   │  ┌─────┴──────┐  ┌──────┴─────┐  ┌────────┴──────┐  ┌───────┴──────┐  │
   │  │da-autoarch.│  │da-autodel. │  │da-autocompl.  │  │da-admin-svc  │  │
   │  │  :8086     │  │  :8087     │  │  :8088        │  │  :8089       │  │
   │  └─────┬──────┘  └──────┬─────┘  └───────┬───────┘  └──────────────┘  │
   │        │               │                │                            │
   │  ┌─────┴───────────────┴────────────────┴──────┐  ┌───────────────┐  │
   │  │  da-logingest :8090  (cloud audit-log pull)  │  │ da-reporting  │  │
   │  └───────────────────────┬──────────────────────┘  │  :8084 (mgmt  │  │
   └──────────────────────────┼─────────────────────────│  port :8380)  │──┘
                              │                          └───────────────┘
        ┌─────────────────────▼───────────────────────────────────┐
        │           Apache Kafka (KRaft) - the log-event spine     │
        │  dlap.log.event.normalized ──▶ dlap.discovery.asset.*    │
        │  dlap.asset.label.assignment.changed                     │
        │  dlap.policies.actions ──▶ archival/deletion/labeling    │
        │  dlap.access.decision  (Consumed-stage audit trail)      │
        └─────────────────────┬───────────────────────────────────┘
                              │
                    ┌─────────▼─────────┐        ┌──────────────────────┐
                    │  PostgreSQL 15    │        │ da-mcp-pep :8091      │
                    │  dlap_common +    │        │ (reference MCP PEP;   │
                    │  per-service      │        │  standalone, not in   │
                    │  schemas (Flyway) │        │  compose) ──▶ PDP      │
                    └───────────────────┘        └──────────────────────┘
```

---

## 9. Where to go next

- **Specifications** (normative contracts): [../specs/](../specs/) -
  Asset Descriptor, Cloud Resource, Label, Log Event, Policy DSL, Access
  Model, Enforcement Contract, AuthZEN interop.
- **Decision log:** [../DECISIONS.md](../DECISIONS.md) - the ADRs cited
  throughout this document.
- **Getting started:** [../getting-started.md](../getting-started.md) - run
  the stack and walk the lifecycle end to end.
