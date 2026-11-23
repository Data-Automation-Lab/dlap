# Getting Started: DLAP in 10 minutes

Bring the full DLAP stack up from a fresh clone and walk one asset through
the lifecycle: discover it, see it in the catalog, author a policy, and
watch the Event Center. No cloud account required.

This is an evaluation quickstart, not a production deployment. It runs one
instance of every service with development defaults and seeded demo users, and
some of what you will see is synthetic (the walkthrough marks where). See
[architecture/overview.md](architecture/overview.md#status-and-limits).

This guide uses the **single canonical quickstart stack** under
`deployment/environments/dev/` (ADR-0037: one runnable quickstart with safe
dev defaults; production is documented in `deployment/PRODUCTION.md`, not a
second compose file). The commands here mirror the quickstart in the root
[README.md](../README.md#quickstart-fresh-clone-under-5-minutes); that section
is authoritative if anything drifts.

---

## 1. Prerequisites

- **Git**
- **Docker 24+** with Compose v2
- **JDK 21**: required for the local Gradle build. Gradle 8.7 will not run
  on JDK 24+, so point `JAVA_HOME` at a JDK 21.
- **About 8 GB of free RAM** for PostgreSQL + Kafka + Keycloak + the services.
  This is a rough estimate, not a measured minimum.

---

## 2. Clone and configure

```bash
git clone https://github.com/Data-Automation-Lab/dlap.git
cd dlap
cp .env.example .env
# Edit .env, set at minimum:
#   POSTGRES_PASSWORD, KEYCLOAK_ADMIN_PASSWORD,
#   DLAP_ENCRYPTION_KEY (32+ chars)
```

The `.env.example` at the repo root is the single config template; secrets
carry safe dev defaults for the quickstart and are documented as
must-change for production.

---

## 3. Build the service JARs

The service Dockerfiles `COPY` prebuilt `build/libs/*.jar`, so a fresh clone
must build **before** `docker compose --build`:

```bash
# Requires JDK 21 (see prerequisites).
./gradlew build -x test
```

---

## 4. Bring the stack up

```bash
cd deployment/environments/dev
docker compose -f docker-compose.yml -f docker-compose.microservices.yml up -d --build
```

The first compose file provides the infrastructure (PostgreSQL, Kafka in
KRaft mode, Keycloak, Redis); the second provides the microservices, the
gateway, and the UI.

### What comes up

| What | Where |
|---|---|
| Operator UI (`new-ui`) | http://localhost:3000 |
| API gateway (nginx) | http://localhost:8081 |
| Keycloak | http://localhost:8180 (admin console `/admin`, realm `dlap`) |
| PostgreSQL | localhost:5432 |
| Kafka | localhost:9092 |
| `da-catalog` | http://localhost:8082 |
| `da-policyengine` (incl. AuthZEN PDP) | http://localhost:8083 |
| `da-reporting` | 8084 (actuator on unpublished mgmt port 8380, see note) |
| `da-autolabel` | http://localhost:8085 |
| `da-autoarchival` | http://localhost:8086 |
| `da-autodelete` | http://localhost:8087 |
| `da-autocompliance` | http://localhost:8088 |
| `da-admin-service` | http://localhost:8089 |
| `da-logingest` | http://localhost:8090 |
| `da-discovery` | http://localhost:8091 (host 8081 is the gateway) |

> `da-mcp-pep` (the reference MCP Policy Enforcement Point) is **not** part
> of this compose: it is a standalone reference PEP you run separately
> against the PDP. See `da-mcp-pep`.

### Verify health

Once containers report healthy:

```bash
curl -sf http://localhost:8082/actuator/health   # da-catalog
curl -sf http://localhost:8083/actuator/health   # da-policyengine
curl -sf http://localhost:8085/actuator/health   # da-autolabel
curl -sf http://localhost:8086/actuator/health   # da-autoarchival
curl -sf http://localhost:8087/actuator/health   # da-autodelete
curl -sf http://localhost:8088/actuator/health   # da-autocompliance
curl -sf http://localhost:8089/actuator/health   # da-admin-service
curl -sf http://localhost:8090/actuator/health   # da-logingest
curl -sf http://localhost:8091/actuator/health   # da-discovery
```

> **da-reporting** serves actuator on a separate, unpublished management
> port (`management.server.port=8380`), so there is no host-reachable
> `http://localhost:8084/actuator/health`. Verify it via the Compose
> `healthcheck`: check `docker compose ps` or `docker inspect` the
> `da-reporting` container's health status.

---

## 5. First walkthrough: one asset through the lifecycle

You can drive this without any cloud credentials using the **synthetic-event
seeder**. It injects events through the real ingest pipeline (ingest ->
`DlapLogEvent` -> typed lifecycle topics -> catalog `Asset` updates -> policy
match -> action events). It does **not** exercise the cloud log sources
(`GcpLogSource`, `CloudTrailLogSource`), real audit-log payload shapes, or any
cloud credential flow. Set `DLAP_LOGINGEST_DEMO_SEED_ENABLED=true` in your
`.env`, bring the stack up, and about 50 synthetic GCS / BigQuery audit events
(`dlap.logingest.demo-seed.event-count`, default 50) cascade through. Full
guide: [launch/test-without-gcp.md](launch/test-without-gcp.md).

Then, in the UI (http://localhost:3000):

1. **Sign in** at `/` via Keycloak SSO with one of the seeded dev users
   (`admin`, `cdo`, `director`, `dataengineer`, `datascientist`). Their
   dev-only credentials are defined in
   `deployment/platform/keycloak/keycloak-import/dlap-realm.json`. They exist
   only in the quickstart realm and must never exist in a shared deployment.
2. **Run discovery**: *Smart Agents -> Smart Discovery*. Discovered assets
   publish on `dlap.discovery.asset.discovered` and flow through the spine
   into the catalog. (Cloud connections are provisioned server-side; the
   synthetic seeder or the LocalStack AWS path avoids needing real
   credentials, see the README.)
3. **See it in the catalog**: *Data Estate*. Confirm the discovered assets
   landed as `Asset` rows with source, kind, and location.
4. **Apply a label**: *Smart Agents -> Smart Label*. **In this quickstart the
   labeler uses a mock provider** (`dlap.autolabel.llm-provider=mock`,
   `deployment/environments/dev/docker-compose.microservices.yml:311`).
   `MockLLMClient` picks 2 to 5 labels at random from a fixed list with a
   random confidence (`MockLLMClient.java:47-50,127-136`), so the labels you
   see are placeholders, not classification of your data. For real labels set
   `dlap.autolabel.llm-provider` to `ollama` (a model is pulled by the compose
   `ollama` service) or `openai` with an API key.
5. **Author a policy**: *Data Policy Center*. Define a lifecycle policy in
   the `dlap/v1` YAML DSL (or the visual builder, which the backend
   translates to the same canonical DSL, ADR-0031). A matching asset fires
   a `dlap.policies.actions` event. Only some actions have a live consumer;
   see the "Implementation status" table in [specs/policy-dsl.md](specs/policy-dsl.md).
6. **Check the Event Center**: *Event Center* shows the activity feed served
   by `da-policyengine`. *Analytics* shows the cross-service dashboards; the
   governance score and cost savings include placeholder constants and
   estimates, see [user-guides/cdo-guide.md](user-guides/cdo-guide.md).

To exercise **Retire**, trigger *Smart Archival* or *Smart Deletion*. A
policy-driven action dispatches to the archival or deletion workflow (a
soft-delete window, then a scheduled hard-delete sweep, with an audit record).
The quickstart touches no real cloud object: deletion runs in dry-run mode
(`DLAP_AUTODELETE_DRY_RUN_CLOUD_DELETE` defaults to true,
`docker-compose.microservices.yml:381`) and rows are stamped
"DRY-RUN: no cloud call". Archival has no dry-run switch enabled in the
quickstart compose: the synthetic assets have nothing real to move, so enable
`DLAP_AUTOARCHIVAL_GCS_MOCK_MODE` or `DLAP_AUTOARCHIVAL_AZURE_MOCK_MODE`
(`da-autoarchival/src/main/resources/application.properties:133,138`) to
exercise the workflow without a cloud.

---

## 6. Resetting

```bash
cd deployment/environments/dev
docker compose -f docker-compose.yml -f docker-compose.microservices.yml down -v
```

`down -v` removes the volumes so the next `up` bootstraps a clean database
(all schemas re-created, dev seed data reloaded).

---

## 7. Where to go next

- **Architecture:** [architecture/overview.md](architecture/overview.md):
  the services, the Kafka spine, the AuthZEN access plane, the data model, and
  what is and is not implemented per cloud.
- **Specifications:** [specs/](specs/): the normative contracts (Asset
  Descriptor, Label, Log Event, Policy DSL, Access Model, Enforcement
  Contract).
- **Test without GCP:** [launch/test-without-gcp.md](launch/test-without-gcp.md).
- **AWS via LocalStack / Unity Catalog federation:** see the root
  [README.md](../README.md).
- **Contributing:** [../CONTRIBUTING.md](../CONTRIBUTING.md) (including DCO
  sign-off) and [../GOVERNANCE.md](../GOVERNANCE.md).
