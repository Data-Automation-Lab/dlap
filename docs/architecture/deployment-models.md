<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
-->

# Deployment and tenancy models

**Decision of record:** ADR-0049 in [../DECISIONS.md](../DECISIONS.md), which amends
ADR-0013 and ADR-0023. **Applies to:** every release up to and including GA.
**Code facts:** cited at `feature/deployment` commit `7984d1d` (UI `301e761`). Line
numbers can move; the facts hold until a tenancy ADR changes them.

Package IDs in this document (DEP-01, SEC-06 and so on) refer to the hardening work
packages of the pre-release work. Their tracker is not published; the decisions they
produced are in [../DECISIONS.md](../DECISIONS.md).

## 1. Decision in brief

- **One deployment serves one customer.** ADR-0013 holds through GA. DLAP has no tenant
  column, tenant claim or tenant topic, and none is added speculatively.
- **Customer data stays in the customer's account by default.** The models in section 4
  run from no access (scan kit), through deployments that stay inside the customer's
  account (pilot, self-hosted), to a deployment DLAP operates (managed).
- **The first hosted offer is single-tenant managed:** one Helm release per customer. Each
  release has its own databases, Kafka, Keycloak realm, keys and cloud identity. No
  stateful component is shared between customers.
- **The shared hosted demo holds synthetic data only.** No customer data, credential or
  cloud connection ever enters it.
- **Multi-tenancy is not offered.** Section 7 lists everything it would take. Section 8
  lists the triggers that reopen the question.

## 2. Terms

| Term | Meaning in this document |
|---|---|
| Customer | One organization that runs DLAP or contracts for it. |
| Deployment | One complete DLAP stack: the ten `da-*` services, Postgres, Kafka and Keycloak, plus the optional UI and reference PEP. |
| Release | A deployment installed with Helm, one per Kubernetes namespace. |
| Tenant | A customer whose data shares a deployment with other customers. DLAP has no tenants. |
| Operator | Whoever runs the deployment: the customer (self-hosted) or DLAP staff (managed). |
| Placement | The cloud project, AWS account or Azure subscription that holds a deployment and pays for it. |

"Tenant" here never means a cloud tenancy. The code uses the word for Azure AD tenants
(`da-discovery/src/main/java/com/dlap/discovery/providers/azure/config/AzureConfigService.java:36-37`)
and OCI tenancies
(`da-discovery/src/main/java/com/dlap/discovery/common/auth/impl/oracle/OracleAuthenticationServiceImpl.java:92-107`).
Those are scopes inside one customer.

## 3. Why DLAP is single-tenant: code facts

DLAP is single-tenant by construction, not by configuration. Every row below would have
to change before two customers could share one deployment.

| Layer | Fact | Evidence |
|---|---|---|
| Schema | No table has a tenant column: `grep -rli tenant --include=*.sql .` matches no file. 30 Flyway files in 10 services create 92 tables; `dlap_common` has 5 more. | `<service>/src/main/resources/db/migration/`; `deployment/platform/database/init-scripts/02-create-common-schema.sql:32,59,82,151,238` |
| Uniqueness | Asset URIs, label names, user emails and Keycloak subjects are unique per deployment. | `02-create-common-schema.sql:34-35,61,76,84,116` |
| Database roles | Each service connects as one Postgres user, and Flyway runs as that same user (no service sets `spring.flyway.user`), so the service owns its tables. | `da-catalog/src/main/resources/application.properties:33-34,63` |
| Identity | Every service validates JWTs against the one realm `dlap`. The SPA fixes its realm at build time. | `deployment/platform/keycloak/keycloak-import/dlap-realm.json:2-3`; `da-catalog/src/main/resources/application.properties:86`; `da-autoarchival/src/main/resources/application.properties:74-75`; `new-ui/frontend/src/keycloak.ts:25-27` |
| Messaging | Topic names are compile-time constants with no prefix or tenant segment. | `da-protos/src/main/java/com/dlap/common/constants/DlapConstants.java:206-269` |
| Event contract | `DlapLogEvent` carries no DLAP tenant. `Principal.account_id` is the cloud account. | `da-protos/src/main/proto/dlap/log/v1/log_event.proto:42,133-135` |
| Cloud identity | Log sources use one process identity: Google Application Default Credentials over one project list, and the AWS default chain or one static key pair. | `da-logingest/src/main/java/com/dlap/logingest/source/gcp/GcpLogSource.java:79-81,97-98`; `da-logingest/src/main/resources/application.properties:104-116` |
| Cursors | Ingest watermarks are keyed by provider and cloud account only. | `GcpLogSource.java:278-283` |
| Keys | One `DLAP_ENCRYPTION_KEY` encrypts every stored cloud credential. | `da-admin-service/src/main/resources/application.properties:149`; `da-admin-service/src/main/java/com/dlap/adminservice/credential/CredentialProvider.java:30-31` |
| Caches | The PDP holds one in-memory policy list. The reference PEP keys verdicts by principal, action, resource (asset id, else URI), the caller's roles sorted, the tier and the SHA-256 digest of the purpose and consent context; no part of the key is a tenant. | `da-policyengine/src/main/java/com/dlap/policyengine/access/AccessDecisionService.java:104`; `AccessPolicyRefreshListener.java:64`; `da-mcp-pep/src/main/java/com/dlap/mcppep/DecisionCache.java:100-119` (key), `:121-136` (context digest) |
| ML settings | One global LLM configuration per deployment. | `da-autolabel/src/main/resources/db/migration/V1__init_autolabel_schema.sql:97` |
| Jobs | 8 `@Scheduled` methods each run over the whole deployment. | for example `da-autodelete/src/main/java/com/dlap/autodelete/service/impl/DeletionServiceImpl.java:171`, `da-discovery/src/main/java/com/dlap/discovery/scheduler/ScheduledDiscoveryTrigger.java:95`, `GcpLogSource.java:114` |
| Quickstart | The compose pair fixes 21 container names, so one Docker host runs one quickstart. | `container_name` in `deployment/environments/dev/docker-compose.yml` and `docker-compose.microservices.yml` |

Keeping this shape through GA is the safer and cheaper choice:
- Isolation comes from separate infrastructure. It is easy to explain and to audit.
- No query path can leak across customers, because no query path spans customers.
- The default models keep metadata and audit-log events inside the customer's account.

## 4. Models at a glance

| # | Model | Operator | Placement | Where customer data lives | Isolation unit | State |
|---|---|---|---|---|---|---|
| M0 | Zero-access scan kit | Customer | Customer's own console | Customer's account; DLAP receives aggregates only | Not a deployment | Planned: SCAN-01 |
| M1 | Quickstart | Anyone | Laptop or VM | Nowhere: seeded demo data | One compose stack per host | Shipped: ADR-0037 |
| M2 | Hosted demo | DLAP | DLAP project | Nowhere: synthetic fixtures | One shared stack | Planned: DEMO-01 to DEMO-04 |
| M3 | Read-only pilot | Customer, DLAP assists | One VM in the customer's account | Customer's account only | One VM | Proposed default (decision D10, founder) |
| M4 | Self-hosted production | Customer | Customer's account | Customer's account only | One Helm release, or the customer's own compose | Guide shipped; Helm planned: DEP-02, DEP-03 |
| M5 | Single-tenant managed | DLAP | DLAP project per customer, or the customer's account | One release that DLAP operates (section 5.6) | One Helm release with its own stateful services | After GA |
| M6 | Multi-tenant hosted | DLAP | One shared plane | One plane shared by all tenants | Row, topic and realm (section 7) | Not offered |

## 5. The models in detail

### 5.1 M0: zero-access scan kit

- Open SQL that the customer runs in their own BigQuery or Databricks console
  (`scripts/scan/`, SCAN-01).
- DLAP gets no install, no role grant and no query text. Only per-table aggregates leave,
  and the customer reviews them before sending.
- It is not a deployment, so tenancy does not apply.

### 5.2 M1: quickstart

- The dev compose pair is the single canonical quickstart (ADR-0037,
  `../DECISIONS.md` lines 316-372). It seeds demo users and dev secrets and is not for
  production (`../../deployment/PRODUCTION.md` lines 33-37).
- The demo (M2) and pilot (M3) stacks are compose override files on top of this pair,
  never a second stack.

### 5.3 M2: hosted demo

- One DLAP-operated stack on one VM: the Lite overlay (DEMO-01), a demo realm with no
  seeded users (DEMO-02) and a banner on every page saying the data is synthetic (DEMO-03).
- Many prospects use it at once. That is safe only because the data is synthetic: fixture
  replay only, cloud sources off, only ports 80 and 443 published, and a nightly reset to a
  golden dump (DEMO-01).
- Rules:
  - Never connect a customer cloud account, load a customer log or store a customer
    credential in the demo. A prospect who wants to see their own data goes to M0 or M3.
  - Prospect accounts are per person in one demo realm, and every account sees the same
    demo data. That is not tenancy, and nobody may describe it as tenancy.

### 5.4 M3: read-only pilot

**Status:** proposed default for hardening decision D10. The founder decides. The
alternative is the M4 release unit on GKE (DEP-02).

- **Placement:** one VM in a project or account that the customer owns and pays for. DLAP
  keeps no copy of anything.
- **Stack:** the quickstart pair plus the overlays
  `deployment/environments/pilot/docker-compose.pilot.*.yml`, which `up.sh` applies in
  sorted order (DEP-01):
  - base: memory limits and `-Xmx`, loopback-only service ports, `ddl-auto=validate` and
    dry-run deletes; the archive and delete services are left out by default (DEP-01);
  - shadow: actions disabled, executors excluded, reports labelled (PILOT-01);
  - tls: every hop encrypted; Kafka on SASL_SSL with SCRAM and ACLs (SEC-06);
  - keycloak: production mode on Postgres, no seeded users, MFA for privileged roles
    (IAM-02);
  - backup: scheduled backups and a tested restore (DR-01).
- **Cloud access:** the Reader role only (IAM-03), granted to the VM's own service
  identity. The log sources already use the process identity (`GcpLogSource.java:79-81`;
  `da-logingest/src/main/resources/application.properties:104-108`), and stored
  connections use `dlap.credentials.provider=federated`, so no secret is stored
  (ADR-0036; `CredentialProvider.java:36-38`). Static keys are not used.
- **Network:** one TLS entry point for the UI and the API; everything else on loopback.
  Outbound traffic goes only to the cloud APIs and the customer's identity provider.
- **Operator access:** the customer runs `up.sh`. DLAP staff help over a screen share or
  in a time-boxed, logged session that the customer grants (for example GCP OS Login or
  AWS Systems Manager Session Manager). DLAP never holds a standing credential.
- **Exit:** the PILOT-01 checklist, with 7 clean days. Teardown deletes the VM and its
  disks; the customer keeps the reports and backups.
- One customer per VM, so every property in section 3 carries over unchanged.

### 5.5 M4: self-hosted production

- The customer runs DLAP on their own infrastructure, following
  [../../deployment/PRODUCTION.md](../../deployment/PRODUCTION.md). It already requires a
  database per service plus `dlap_common` (lines 77-78), a hardened realm (line 101) and
  Kafka with a replication factor of 3 (lines 168-169).
- Once it lands, the Helm chart `deployment/helm/dlap` is the reference install: minimal
  on GKE (DEP-02), then GKE, EKS and AKS with workload identity (DEP-03).
- This is the default model through GA.

### 5.6 M5: single-tenant managed, the first hosted offer

**Release unit.** One Helm release of the M4 chart per customer, in its own namespace,
with:
- its own Postgres (a CloudNativePG cluster or a managed instance) holding the
  per-service databases and `dlap_common`;
- its own Kafka cluster with SASL and ACLs;
- its own Keycloak and realm, federated to the customer's identity provider (IAM-02);
- its own encryption key, or its own KMS key once SEC-10 adds the envelope option;
- its own cloud identity, bound to the namespace and trusted by the customer's Reader
  role (and, once archive or delete is in scope, a scoped Actor role, IAM-04);
- its own backups and restore drills (DR-01, DR-02);
- its own audit chain and exports (AUD-01).

Releases share only the container images, the chart, the CI/CD pipeline and DLAP's
operator tooling. Telemetry leaving a release is metrics only. Logs and traces stay in the
release unless the customer opts in, because application logs can name principals and
assets.

**Placement.**
- Default: a DLAP-owned project (or AWS account, or Azure subscription) dedicated to the
  customer. IAM grants stay per customer, billing is per customer, and deleting the
  project is the offboarding backstop.
- Option: the same release in the customer's own account, operated by DLAP under
  delegated access. Data then never leaves the customer's account.
- Not in the first offer: several customers' namespaces on one shared cluster. That
  becomes acceptable only after DEP-03 lands default-deny NetworkPolicies, the restricted
  Pod Security level, per-namespace workload identity and resource quotas, and a pen test
  covers namespace escape (SEC-09). Even then each release keeps its own stateful
  services; only compute is shared.

**Cross-account access.** No identity is reused across customers. If a central DLAP
identity ever assumes roles in customers' AWS accounts, every trust policy requires a
per-customer `sts:ExternalId` that DLAP generates, which prevents the confused-deputy
problem ([AWS IAM: the confused deputy problem](https://docs.aws.amazon.com/IAM/latest/UserGuide/confused-deputy.html)).

**What DLAP then holds for the customer:**
- the catalog: asset names and technical metadata;
- normalized audit-log events, which name principals; `da-logingest` keeps a row per event
  (ADR-0014);
- labels, policies and access decisions;
- cloud-connection metadata;
- with auto-labelling on, asset content passes to the configured LLM provider (ADR-0044).

So M5 needs a data-processing agreement and a region choice for each customer.

**Operations.**
- Upgrades roll out release by release on one chart version. A customer can stay one minor
  version behind. Each upgrade runs Flyway per service and the `dlap_common` upgrade runner
  (DB-02).
- DLAP access to a release is break-glass: time-boxed, ticketed, and written to that
  release's audit chain, where the customer can see it (AUD-01, AUD-02b).
- Offboarding: export the reports and the audit chain to the customer, delete the
  release, destroy its key, then delete the project.

**Prerequisites before the first M5 contract.**
- A Helm chart for the target cloud with workload identity (DEP-02, DEP-03).
- Encryption in transit (SEC-06), production Keycloak (IAM-02), the dev-secret boot guard
  (SEC-05) and the credential SPI with a KMS option (SEC-10).
- Backups with a passed restore drill (DR-01, DR-02). Kafka and Postgres HA when the
  contract sets an availability target (HA-01, HA-02).
- A tamper-evident audit trail that records operator access (AUD-01, AUD-02b), and
  per-release metrics and alerts (OBS-01).
- A SOC 2 report whose scope covers the managed service, with the Availability and
  Confidentiality criteria (CERT-03, CERT-04).

## 6. Separation inside one customer is not tenancy

- Business units, subsidiaries and data domains of one customer share one deployment.
  Approvers and executors act only inside their scopes (RBAC-02). Reads, reports and
  exports are filtered through the PDP (RBAC-03).
- Several cloud accounts or projects are several log-source bindings in one deployment,
  each with its own `credentials-ref` (LOG-02).
- A customer that needs hard separation between two units, for example a regulated
  subsidiary, runs two deployments.

## 7. What multi-tenancy would require

A shared plane would have to make every row of section 3 tenant-aware, and prove it. This
list is the scope of any future multi-tenancy ADR. Nothing in it is planned. It touches
all ten services, the shared library, the realm, the chart and the UI.

**7.1 Tenant lifecycle**
- A tenant registry and a provisioning API that creates, suspends and deletes a tenant
  across every layer below in one audited workflow.
- Tenant IDs that never change and are never reused.

**7.2 Persistence**
- `tenant_id NOT NULL` on the 5 `dlap_common` tables and the 92 per-service tables (section
  3), through the init script plus an upgrade delta, and Flyway per service.
- Every unique constraint becomes tenant-scoped, starting with `asset_uri`, `label_name`,
  `email` and `keycloak_subject_id` (`02-create-common-schema.sql:34-35,61,84`).
- Postgres row-level security on every table, enforced against a runtime role that does
  not own the tables, or with `FORCE ROW LEVEL SECURITY`. Table owners bypass row security
  by default ([PostgreSQL 15: row security policies](https://www.postgresql.org/docs/15/ddl-rowsecurity.html)),
  and today every service owns its tables (section 3).
- The tenant is set on each transaction from the request or message context, for example
  with `SET LOCAL app.tenant_id`. An ORM discriminator such as Hibernate's `@TenantId`
  (Hibernate ORM 6 User Guide, section 23.3.1) is a convenience. Row-level security is the
  boundary, so it also covers the 7 native queries in 4 repositories today (for example
  `da-protos/src/main/java/com/dlap/common/repository/IUserRepository.java`) and any
  future JDBC path.
- Restore and deletion per tenant. A shared database cluster restores every tenant at
  once, so this needs per-tenant logical export or a database per tenant.

**7.3 Messaging**
- Per-tenant topic prefixes, resolved at runtime instead of the compile-time constants
  (`DlapConstants.java:206-269`), with PREFIXED ACLs per tenant principal
  ([KIP-290: prefixed ACLs](https://cwiki.apache.org/confluence/display/KAFKA/KIP-290%3A+Support+for+Prefixed+ACLs)).
  Consumer groups, dead-letter topics and replay tools per tenant.
- A tenant field in the `DlapLogEvent` contract and a tenant header on every record, which
  consumers check against the topic prefix.
- Kafka client quotas per tenant.
- **Rejected:** the ADR-0023 sketch of one shared topic keyed by tenant
  (`../DECISIONS.md` lines 1006-1009). Kafka ACLs stop at the topic: they cover the
  cluster, groups, topics, transactional IDs and delegation tokens, never a partition or a
  key ([Confluent: ACL overview](https://docs.confluent.io/platform/current/security/authorization/acls/overview.html)).
  On a shared topic every consumer would read every tenant's events.

**7.4 Identity and authorization**
- Either a realm per tenant, with a multi-issuer JWT resolver in all 11
  `SecurityConfiguration` classes, or Keycloak Organizations with an organization claim
  mapped to the tenant. Organizations are fully supported from Keycloak 26
  ([Keycloak 26.0.0 release, 2024-10-04](https://www.keycloak.org/2024/10/keycloak-2600-released)).
  The dev stack runs Keycloak 23.0.1 (`deployment/environments/dev/docker-compose.yml:79`),
  so either path needs the 26.x upgrade in IAM-02 first.
- The ten realm roles (ADR-0032) scoped per tenant; a tenant administrator who cannot see
  other tenants; a separate, audited DLAP operator role for break-glass access.
- Service-to-service calls carry the tenant explicitly, through token exchange or a signed
  tenant claim, and services reject a call without one. Today the one
  `ROLE_SERVICE_ACCOUNT` principal (ADR-0034) would act for every tenant.
- The SPA chooses its realm or organization at login, not at build time
  (`new-ui/frontend/src/keycloak.ts:25-27`).
- The AuthZEN request and the decision log carry the tenant, and the PDP policy cache and
  PEP verdict cache are keyed by tenant (`AccessDecisionService.java:104`;
  `DecisionCache.java:100-119` builds the key today from principal, action, resource, roles, tier
  and context digest, so a tenant part is added there).

**7.5 Cloud access**
- Per-tenant log-source bindings and identities instead of one process identity
  (`GcpLogSource.java:79-81`; `da-logingest/src/main/resources/application.properties:104-116`),
  through per-tenant impersonation: GCP service-account impersonation, AWS AssumeRole with
  a per-tenant external ID, Azure federated credentials.
- Cursors, event IDs and canonical asset IDs scoped by tenant. Two tenants may connect the
  same cloud account. Watermarks key on the cloud account alone (`GcpLogSource.java:278-283`),
  and a shared dedup store would drop the second tenant's copy of each event.
- Per-tenant envelope keys in a KMS for stored credentials, replacing the one
  `DLAP_ENCRYPTION_KEY`, with key destruction on offboarding.

**7.6 Jobs and execution**
- Every scheduled job iterates tenants with per-tenant locks and budgets, so one tenant's
  backlog cannot starve another.
- Archive and delete Actor roles, approvals and dual control per tenant (APPR-01, RBAC-02).
- Per-tenant LLM settings and keys instead of the global `ml_global_config`, and a
  per-tenant opt-in before asset content reaches any LLM provider.

**7.7 Fairness**
- API, ingest and storage limits per tenant.
- Fair use of connection pools. Each service has one pool of 10 connections today
  (`da-catalog/src/main/resources/application.properties:39`).

**7.8 Observability and audit**
- A tenant label on metrics with bounded cardinality; logs and traces tagged and
  access-controlled per tenant; per-tenant SLOs.
- One audit hash chain per tenant (AUD-01 chains per stream), tenant-scoped audit export,
  and every operator access written to that tenant's chain.

**7.9 Assurance**
- A cross-tenant isolation suite that exercises every endpoint, topic, job and cache with
  two tenants and fails on any leak. It gates every release.
- A pen test scoped to tenant isolation (SEC-09).
- SOC 2 with the Availability and Confidentiality criteria in scope, and data residency by
  region, which means one plane per region.

**7.10 Upgrades**
- All tenants move together. Per-tenant version pinning is impossible on one plane, so
  every schema change must be online for all tenants at once.

## 8. Triggers for revisiting

Reopen the question with a new ADR when at least one trigger fires and every precondition
holds. The thresholds are estimates; review them at each GA planning round.

**Triggers**
- **T1 Demand.** In any rolling 6 months, 3 or more qualified prospects decline M0, M3, M4
  and M5, and each names per-deployment cost or setup time as the reason.
- **T2 Fleet.** More than 25 M5 releases are in service, or a fleet upgrade costs more
  than one operator-day per release train after automation.
- **T3 Footprint.** The measured idle footprint of one release (the DEMO-01 `measure.sh`
  method) stays too large for the smallest customers after a slimmer single-tenant
  profile has been tried.
- **T4 Channel.** A partner that serves many end customers (a managed service provider,
  auditor or vCISO firm) needs one plane with delegated per-customer administration, and
  per-customer releases with a partner operator role do not meet that need.
- **T5 Product.** A capability needs data from several customers in one computation. This
  is a non-goal; such a feature needs its own ADR, covering consent and anonymization,
  before any tenancy work.

**Preconditions**
- M5 runs in production, covered by a SOC 2 Type II report (CERT-04).
- RBAC-03, AUD-01, AUD-02a, AUD-02b, HA-02 and DB-02 have landed; multi-tenancy builds
  on each of them.
- The isolation suite in section 7.9 exists before the first `tenant_id` column.

**Not triggers**
- One prospect asking for "SaaS": M5 serves them.
- Business units inside one customer: section 6 covers them.
- Many prospects on the demo: M2 holds synthetic data only.
- Lower cost alone, before T3 has been measured.

## 9. Rules for contributors until then

- Do not add `tenant_id` columns, tenant headers or tenant claims speculatively. Untested
  tenancy is worse than none: it implies an isolation nobody has proved.
- Keep topic names in `DlapConstants.KafkaTopic`, and keep every deployment-specific value
  (project IDs, regions, issuer URLs, buckets) in configuration (CLAUDE.md §9). A future
  per-tenant prefix or setting then has one place to change.
- Never describe DLAP as multi-tenant: not in docs, the UI, listings or sales material.
  CLAIM-01 removes the stale multi-tenant claims under `gcp-marketplace/`.
- A feature that only works on data from several customers needs an ADR before code.
- Never put customer data in the hosted demo (M2).

## 10. References

- ADRs in [../DECISIONS.md](../DECISIONS.md): ADR-0013 (single tenant), ADR-0014
  (Postgres only), ADR-0023 (Kafka only), ADR-0032 (access model), ADR-0034 (M2M service
  account), ADR-0036 (credential SPI), ADR-0037 (one quickstart), ADR-0044 (LLM client),
  ADR-0049 (this model).
- [../../deployment/PRODUCTION.md](../../deployment/PRODUCTION.md),
  [../guides/credential-store.md](../guides/credential-store.md),
  [overview.md](overview.md).
- External sources, read 2026-10-19:
  - [PostgreSQL 15: row security policies](https://www.postgresql.org/docs/15/ddl-rowsecurity.html)
  - [KIP-290: support for prefixed ACLs](https://cwiki.apache.org/confluence/display/KAFKA/KIP-290%3A+Support+for+Prefixed+ACLs)
  - [Confluent: ACL overview](https://docs.confluent.io/platform/current/security/authorization/acls/overview.html)
  - [Keycloak 26.0.0 release](https://www.keycloak.org/2024/10/keycloak-2600-released)
  - [AWS IAM: the confused deputy problem](https://docs.aws.amazon.com/IAM/latest/UserGuide/confused-deputy.html)
  - [Hibernate ORM 6.4 User Guide](https://docs.hibernate.org/orm/6.4/userguide/html_single/Hibernate_User_Guide.html), section 23.3.1 `@TenantId`
