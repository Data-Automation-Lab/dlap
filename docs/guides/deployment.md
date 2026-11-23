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

# Deploying and using DLAP

How to run DLAP, walk one asset through the lifecycle, and harden a deployment before it sees real data. It is a map: each section names the file that holds the detail.

**Status: early-evaluation release.** DLAP is pre-release software. It holds no certification and offers no uptime target ([`docs/architecture/overview.md`](../architecture/overview.md), "Status and limits"). Production hardening is a documented checklist, not a shipped and supported topology. "Present in code" does not mean "exercised against a live cloud account"; this guide says which is which. Where two documents disagree, the code and the compose files win, and this guide follows them.

## 1. What you are deploying

One DLAP deployment is ten Spring Boot services and a React UI around PostgreSQL, Apache Kafka (KRaft mode) and Keycloak, behind an nginx gateway. One deployment serves one customer: there is no multi-tenancy (ADR-0049, [`docs/architecture/deployment-models.md`](../architecture/deployment-models.md)). Lifecycle decisions are driven by audit-log events that `da-logingest` normalizes onto one Kafka topic.

| Part | What it is | Quickstart host port |
| --- | --- | --- |
| Ten `da-*` services | `da-discovery`, `da-catalog`, `da-policyengine` (policy and the AuthZEN decision point), `da-reporting`, `da-autolabel`, `da-autoarchival`, `da-autodelete`, `da-autocompliance`, `da-admin-service`, `da-logingest` | 8082 to 8091 |
| Gateway | nginx. Serves the UI and routes `/api/v1/<service>`, so the browser sees one origin | 8081 |
| UI (`new-ui`) | React operator UI, a git submodule | 3000 (also through the gateway) |
| PostgreSQL 15 | One database per service plus the shared `dlap_common` | 5432 |
| Kafka | One KRaft node in the quickstart | 9092 |
| Keycloak 26.8.1 | Realm `dlap`: sign-in, roles, service-to-service tokens | 8180 |
| `da-mcp-pep` | Reference MCP enforcement point. Standalone, in no compose file, no database, no Kafka | own default 8091 |

Every port and each service's purpose is in the [README service catalog](../../README.md#service-catalog). The architecture is in [`docs/architecture/overview.md`](../architecture/overview.md).

## 2. Choose a path

The repository ships one runnable compose stack, the quickstart pair under `deployment/environments/dev/` (ADR-0037). The demo and the pilot are overlays on that pair, never a second stack.

| Path | Purpose | Needs | What is hardened | What is not | Start here |
| --- | --- | --- | --- | --- | --- |
| A. Local quickstart | Evaluate on a laptop or an isolated VM | Git, curl, Docker 24+ with Compose 2.20+, 10 GiB of memory for Docker, JDK 21 | Nothing | Dev secrets, five seeded users with a published password, plain HTTP on every hop, every port on all host interfaces, single Kafka node | `bash scripts/quickstart.sh` |
| B. Hosted demo overlay | A public demo on synthetic data | One VM with 2 vCPU and 8 GB; Compose 2.24+; JDK 21 and Node 22 on a build machine | Only TCP 80 and 443 published, hard memory limits, `prod` profile, a demo realm without the seeded users, no network route out, dry-run deletes, mocked archival, nightly reset | Not production. Never holds customer data. Numbers were measured on a workstation emulation, not on the target VM | [`deployment/environments/demo/README.md`](../../deployment/environments/demo/README.md) |
| C. Pilot overlay | A read-only pilot on one VM in your own cloud account | Linux VM with 4 vCPU and 16 GB; Compose 2.24+; JDK 21 | `prod` profile, schema `validate`, TLS on every hop, production Keycloak realm with MFA, one password per database role, loopback-only ports, backup agent | The repo calls it "not a finished deployment". Sizes are not measured on a real VM. Single host, no failover | [`deployment/environments/pilot/README.md`](../../deployment/environments/pilot/README.md) |
| D. Self-hosted production | Your own orchestration (Kubernetes, ECS, Nomad, or a compose you own) | Managed PostgreSQL 15, a multi-broker Kafka, Keycloak on PostgreSQL, a TLS ingress | Whatever you apply from the hardening guide | There is no Helm chart yet. The repo calls production hardening "a documented checklist, not a shipped and supported topology" | [`deployment/PRODUCTION.md`](../../deployment/PRODUCTION.md) |

[`docs/architecture/deployment-models.md`](../architecture/deployment-models.md) also lists a zero-access scan kit (planned), a managed single-tenant offer (after GA) and multi-tenant hosting (not offered). None of them exists in this release.

## 3. Path A: local evaluation in minutes

**Prerequisites.** Git, curl, Docker 24+ with Compose v2 (2.20 or later), 10 GiB or more of memory given to Docker, and a JDK 21. Gradle runs on newer JDKs, but the build needs a Java 21 toolchain. On Windows run every command in Git Bash.

```bash
git clone --recurse-submodules https://github.com/Data-Automation-Lab/dlap.git
cd dlap
bash scripts/quickstart.sh
```

The script (`scripts/quickstart.sh`) runs six timed steps: check the prerequisites, fetch the `new-ui` submodule, copy `.env.example` to `.env` (never overwriting one), build the jars with Gradle, start the stack and wait until every container is healthy, then run a smoke test that gets a token for the seeded `admin` user and reads the catalog. It skips Ollama: the dev stack labels with a rules provider and needs no model.

| Flag | Effect |
| --- | --- |
| `--check` | Prerequisites only. Changes nothing |
| `--dry-run` | Print the commands it would run |
| `--no-ui` | Backend only. No UI, no gateway, no submodule needed |
| `--no-build` | Reuse the jars already built |
| `--down` | Stop the stack, keep its data |
| `--reset` | Stop the stack and delete its data |

**It is not five minutes.** The README measured 5 min 46 s with warm caches and 13 min 34 s with a cold Gradle cache, on one workstation with the Docker base images already pulled. See [How long it takes](../../README.md#how-long-it-takes). The 15 containers held 8.5 GiB once healthy. A Docker VM under 10 GiB was not tested.

**Sign in.** Open `http://localhost:8081`. The UI hands you to Keycloak. Sign in as the seeded user `admin`. The dev password is printed in the README [Sign in](../../README.md#sign-in) section and defined in `deployment/platform/keycloak/keycloak-import/dlap-realm.json`. The same realm seeds `cdo`, `director`, `dataengineer` and `datascientist`. **Data Estate** then lists five sample assets. They are seed rows from `03-insert-test-data.sql`, not discovered assets.

**Run compose by hand.** The script only wraps the commands in the README section "What the script runs, by hand". Always pass `--env-file .env`: Compose reads `.env` from the compose file's folder, not from the repo root, so without the flag your `.env` is silently ignored ([`deployment/environments/dev/README.md`](../../deployment/environments/dev/README.md)). Every variable is documented in [`.env.example`](../../.env.example).

**Most common failures** (full table: [If it does not start](../../README.md#if-it-does-not-start)):

| Symptom | Fix |
| --- | --- |
| `no JDK 21 found` | Install a JDK 21 and set `DLAP_JDK21` or `JAVA_HOME` to it. A JDK 24 alone is not enough |
| `port is already allocated` | Put a free port in `.env`, for example `DLAP_PORT_POSTGRES=15432`. Keep 8081 and 8180: the sign-in redirect uses them |
| A container exits or stays unhealthy | Read its log with `docker compose ... logs <service>`. The usual cause is too little memory for Docker |
| `da-admin-service` exits at start | `DLAP_ENCRYPTION_KEY` must resolve to exactly 32 bytes. The placeholder in `.env.example` is valid |
| `dlap-postgresql` unhealthy, `^M` in its log | The init scripts were checked out with CRLF line endings. Check them out again |
| Another DLAP stack runs on the host | Container names and ports are fixed, so one stack runs per Docker host |

Do not wait with `docker compose up --wait`: the larger JVMs report `unhealthy` once before they turn healthy, and `--wait` gives up on that report.

**Keep it private.** The quickstart has known passwords, no transport encryption and no Kafka authentication. Run it only on a single-user machine or an isolated VM ([`SECURITY.md`](../../SECURITY.md), "Threat model").

## 4. First use: one asset through the lifecycle

This walk needs no cloud account. It follows [`docs/getting-started.md`](../getting-started.md) section 5 and [`docs/launch/test-without-gcp.md`](../launch/test-without-gcp.md). The UI menu names below come from those documents; the README marks them as not verified against the UI submodule.

**Seed synthetic events first.** Set `DLAP_LOGINGEST_DEMO_SEED_ENABLED=true` in `.env`, then re-create `da-logingest` so it reads the value (the same pattern the README uses for `da-discovery`):

```bash
docker compose --env-file .env \
  -f deployment/environments/dev/docker-compose.yml \
  -f deployment/environments/dev/docker-compose.microservices.yml \
  up -d da-logingest
```

The seeder runs once per start of `da-logingest`. It sends about 50 synthetic audit events for 4 GCS buckets and 4 BigQuery tables through the real pipeline: ingest, the normalized topic, the typed lifecycle topics, catalog upserts, policy match, action events. Wait about 30 seconds. A green run proves the internal wiring for these events. It proves nothing about any cloud: the cloud log sources, real audit-log payloads and credential flows are not exercised.

| Step | Stage | Where | What to expect |
| --- | --- | --- | --- |
| 1 | Discovery | *Smart Agents*, *Smart Discovery* | Discovered assets are published on `dlap.discovery.asset.discovered` and reach the catalog. Cloud connections are provisioned server-side only; there is no create or edit screen |
| 2 | Catalog | *Data Estate* (`/data-catalog`) | The seeded assets appear as `Asset` rows with source, kind and location |
| 3 | Label | *Smart Agents*, *Smart Label* | The quickstart labels with the deterministic `rules` provider (`docker-compose.microservices.yml`, `da-autolabel`). For a model, set `dlap.autolabel.llm-provider` to `ollama` or `openai` |
| 4 | Govern | *Data Policy Center* (`/policy-editor`) | Write a policy in the `dlap/v1` YAML DSL; an example is in `test-without-gcp.md`. A matching asset fires an event on `dlap.policies.actions` |
| 5 | Consume | The API, not the UI | See the `curl` call below |
| 6 | Retire | *Smart Archival*, *Smart Deletion* | A soft-delete window, then a scheduled hard-delete sweep with an audit record. Deletion is a dry run by default |
| 7 | Report | *Event Center*, *Analytics* | The activity feed and the dashboards. The governance score and cost savings include placeholder constants ([`docs/user-guides/cdo-guide.md`](../user-guides/cdo-guide.md)) |

**Policy actions.** Only `archive`, `delete`, `addLabel` and `setRetention` have a live consumer. Alerts, `removeLabel`, `tagAsset` and the rest are recorded and have no effect ([`docs/specs/policy-dsl.md`](../specs/policy-dsl.md), "Implementation status").

**Access decision.** The decision point accepts a token with `ROLE_ADMIN` or `ROLE_SERVICE_ACCOUNT` (`AuthZenEvaluationController.java`). Against the dev stack, the `dlap-backend` client gives a service-account token (`keycloak-prod-setup.md`, "Quick verification"):

```bash
TOKEN=$(curl -s -X POST http://localhost:8180/realms/dlap/protocol/openid-connect/token \
  -d grant_type=client_credentials -d client_id=dlap-backend \
  -d client_secret="<DLAP_BACKEND_CLIENT_SECRET from .env>" \
  | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')

curl -sf -X POST http://localhost:8083/access/v1/evaluation \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"subject":{...},"action":{...},"resource":{...}}'
```

Fill the body from [`docs/specs/enforcement-contract.md`](../specs/enforcement-contract.md), which is normative for the request and response. Every verdict emits a decision-log event on `dlap.access.decision`. This repository has no run guide for `da-mcp-pep`; its settings are in `deployment/PRODUCTION.md` section 8.2.

**Archive and delete stay dry.** The dev compose sets `DLAP_AUTODELETE_DRY_RUN_CLOUD_DELETE=true`, and rows are stamped "DRY-RUN: no cloud call". Archival is different: its mock switches (`DLAP_AUTOARCHIVAL_GCS_MOCK_MODE`, `DLAP_AUTOARCHIVAL_AZURE_MOCK_MODE`) default to false and the dev compose file does not pass them to the container, so add them to the `da-autoarchival` environment yourself before you archive anything that is real. The demo and pilot overlays set both to true.

**Other ways in.** You can POST your own events to `/api/v1/logingest/events` (needs `ROLE_ADMIN` or `ROLE_DATA_ENGINEER`) or replay the fixture files under `e2e-tests/fixtures/replay/`; both are in `test-without-gcp.md`.

**AWS through LocalStack.** The AWS discovery provider runs against LocalStack with no AWS account: set the four `DLAP_AWS_*` variables, start the `aws` compose profile, create a bucket and re-create `da-discovery`. The community LocalStack image implements S3 only, so only the S3 bucket crawler runs end to end; RDS and Glue discovery are covered by mock-based tests. Commands: [README](../../README.md#try-the-aws-provider-against-localstack-no-aws-account).

**Unity Catalog.** `da-catalog` can mirror tabular assets into a Unity Catalog metastore as `catalog.schema.table`, with labels as table properties (ADR-0029). It is off by default. Set `DLAP_UNITY_ENABLED=true`, start the `unity` compose profile (host port 18080) and re-create `da-catalog`. Commands: [README](../../README.md#mirror-assets-into-unity-catalog-optional).

## 5. Path B: hosted demo overlay

`deployment/environments/demo/` is an overlay for a public demo on one small VM (ADR-0058). It runs eight JVMs behind nginx and a Caddy edge, on fixture data, with a nightly return to a frozen state. The service network has no route out, so no cloud account can be connected. Read its [README](../../deployment/environments/demo/README.md) before you start; this is the outline.

**Rehearse on your machine** (Compose 2.24+, JDK 21, Node 22), from the repository root:

```bash
git submodule update --init new-ui
./gradlew :da-catalog:bootJar :da-policyengine:bootJar :da-discovery:bootJar :da-logingest:bootJar \
          :da-reporting:bootJar :da-autolabel:bootJar :da-autoarchival:bootJar :da-autodelete:bootJar

cd deployment/environments/demo
cp .env.demo.example .env.demo
bash ../../platform/database/gen-db-passwords.sh .env.demo   # the 13 database role passwords
$EDITOR .env.demo        # the other secrets; uncomment the "Local rehearsal" block at the end
scripts/build-spa.sh     # the UI build for those host names
./up.sh --check          # checks only
scripts/seed.sh          # starts the stack, replays the fixtures, takes the golden dump
```

With the rehearsal block the demo is at `http://localhost:18080` and `https://localhost:18443`. Your browser will not trust Caddy's local certificate authority until you accept or import it.

**The env file.** `.env.demo.example` lists every setting. You set the two host names (`DEMO_APP_HOST`, `DEMO_ID_HOST`), `DEMO_TLS=auto`, `POSTGRES_PASSWORD`, the 13 role passwords, `KEYCLOAK_ADMIN_PASSWORD` and `DLAP_BACKEND_CLIENT_SECRET`.

**What `up.sh` refuses.** A missing secret, a `CHANGE_ME` placeholder, a default the quickstart publishes, a database password used twice, and a missing Keycloak overlay (unless `DEMO_ALLOW_DEV_REALM=1`, for a rehearsal only).

**The forbidden-hosts guard.** `DEMO_FORBIDDEN_HOSTS` is a comma-separated list of domains the demo must never be served from, for example a production site on the same hosting account. `up.sh` refuses to start when either host name contains one of them.

**On a real host.** One VM with 2 vCPU and 8 GB, no service account on the VM, inbound firewall for TCP 80 and 443 only, SSH through a tunnel. Build the jars and the UI elsewhere and copy the tree; do not run Gradle on the 8 GB VM. Then `./up.sh --check` and `scripts/seed.sh --no-build`, and schedule `scripts/nightly-reset.sh` with cron.

**Limits.**

- It is not production, and it must never hold customer data, credentials or cloud connections.
- The published numbers come from a workstation pinned to two CPUs, not from the target VM. "Boots on 8 GB" rests on the hard container limits (6.16 GiB in total), not on a boot on an 8 GB machine.
- One demo runs per Docker host, because the quickstart files pin container names.
- The UI hotlinks fonts and pictures from third-party hosts. The demo's content security policy blocks them by default.
- A demo whose Keycloak database predates the audience check answers 401 until the realm gets the `dlap-api` audience mapper ([`CHANGELOG.md`](../../CHANGELOG.md), "Upgrade notes").

## 6. Path C: pilot overlay on your own host

`deployment/environments/pilot/` runs the quickstart stack under the `prod` profile on one VM in your own cloud account. Its [README](../../deployment/environments/pilot/README.md) is the reference. `up.sh` applies every `docker-compose.pilot.*.yml` in sorted order: `backup`, `base`, `identity-map`, `keycloak`, `tls`.

**Prerequisites.** A Linux VM with 4 vCPU and 16 GB. The memory limits add up to about 10 GB; the README says they are conservative starting points and not measured yet. Docker Engine with Compose 2.24+, JDK 21 to build the jars, and the `new-ui` submodule. For the log sources, give the VM its own service identity with the read-only Reader role (section 8); no key file is stored. Do not run it next to the quickstart: the container names are fixed.

```bash
./gradlew build -x test
cp deployment/environments/pilot/.env.pilot.example deployment/environments/pilot/.env.pilot
bash deployment/platform/database/gen-db-passwords.sh deployment/environments/pilot/.env.pilot
$EDITOR deployment/environments/pilot/.env.pilot          # every CHANGE_ME value, and the three addresses
deployment/environments/pilot/tls/gen-certs.sh --public-host <name the browsers use>   # once
deployment/environments/pilot/up.sh --check               # checks only, starts nothing
deployment/environments/pilot/up.sh                       # up -d --build
```

**Environment** (`.env.pilot.example`):

| Group | Variables |
| --- | --- |
| Database | `POSTGRES_PASSWORD`, and one password per role: eleven service roles plus `dlap_monitor` and `dlap_backup`. `gen-db-passwords.sh` fills in all thirteen |
| Keycloak | `KEYCLOAK_ADMIN`, `KEYCLOAK_ADMIN_PASSWORD` (a temporary bootstrap account), `KEYCLOAK_DB_PASSWORD` |
| Clients and keys | `DLAP_BACKEND_CLIENT_SECRET`, `DLAP_MCP_GATEWAY_CLIENT_SECRET`, `DLAP_ENCRYPTION_KEY` (exactly 64 hex characters, `openssl rand -hex 32`) |
| Cloud | `GCP_PROJECT_ID` (required even if you scan nothing on GCP), the `DLAP_LOGINGEST_GCP_*` and `DLAP_LOGINGEST_AWS_*` source flags, both off |
| Addresses | `PILOT_PUBLIC_ORIGIN`, `DLAP_PILOT_ID_URL`, `DLAP_PILOT_APP_URL`: the same `https://` origin three times |
| Optional | `COMPOSE_PROFILES` (`actions`, `llm`, `monitoring`), `PILOT_TLS_BIND_ADDRESS`, `PILOT_TLS_PORT`, `PILOT_IDENTITY_MAP_FILE` |

**TLS.** `tls/gen-certs.sh` creates one private certificate authority per pilot and a certificate per service. Every hop is encrypted and every client verifies its server: browser to gateway, gateway to services, services to PostgreSQL (`sslmode=verify-full`), to Kafka (`SASL_SSL` with one SCRAM user per service and ACLs per topic) and to Keycloak. The only entry point is the gateway's HTTPS listener. By default it is bound to `127.0.0.1`, so you reach it through an SSH tunnel (`ssh -L 8443:127.0.0.1:8443 <vm>`, then `https://localhost:8443` with the example values). Set `PILOT_TLS_BIND_ADDRESS` to open it to a network. You can put your own certificate on the entry point with `--public-cert` and `--public-key`. Detail: [`transport-security.md`](transport-security.md).

**Identity.** The pilot runs Keycloak in production mode on PostgreSQL and imports the production realm template: no seeded users, no password grant, a one-time code for `ROLE_ADMIN`, `ROLE_CDO` and `ROLE_POLICY_MANAGER`, login and admin events kept for 30 days. After the first start, replace the temporary bootstrap administrator with a permanent one, then add your identity provider or create accounts. Okta and Microsoft Entra ID setups are documented in [`deployment/platform/keycloak/production/README.md`](../../deployment/platform/keycloak/production/README.md). The realm file is imported once, into an empty database; later changes go through the admin console.

**What `up.sh` checks before it starts anything:**

- Docker Compose is 2.24 or later.
- Every secret is set, is not a `CHANGE_ME` placeholder and is not a default the quickstart publishes.
- Each database role has its own password. A value used twice is refused.
- `DLAP_ENCRYPTION_KEY` is 64 hex characters.
- The TLS key material exists, `PILOT_PUBLIC_ORIGIN` is `https://`, and the three addresses are the same origin.
- `PILOT_IDENTITY_MAP_FILE`, when set, is a readable file with the right header.
- The jars and the UI submodule are present.
- The production Keycloak overlay is present. Without it the seeded dev realm would be imported, and `up.sh` stops unless `PILOT_ALLOW_DEV_REALM=1` (a lab VM only, never with customer data).

Then, on `up`, it starts PostgreSQL alone, runs the `dlap_common` upgrade runner and the role synchronisation, and only then starts the services. If the runner fails or refuses, no service starts.

**What the base overlay sets.** Every port on loopback, a memory limit and an explicit `-Xmx` per container, no demo seed data, rules-based labelling. `da-autoarchival` and `da-autodelete` do not start at all unless you enable the `actions` profile, and then deletes stay dry runs and archival stays mocked whatever the env file says.

**Rehearsal and checks the repo documents:**

| Script | What it shows |
| --- | --- |
| `deployment/environments/pilot/verify.sh` | The merged compose model, offline: ports, limits, profile, dry-run deletes, JWT issuer and audience checks. Needs no secrets |
| `deployment/environments/pilot/up-test.sh` | Tests `up.sh` itself, including the database upgrade step |
| `deployment/environments/pilot/tls/smoke-tls.sh` | Every hop of a running pilot (`--static` checks the model without running anything) |
| `deployment/environments/pilot/tls/tls-e2e.sh` | A throwaway pilot with fresh secrets and certificates: started, smoke-tested, removed |
| `deployment/platform/keycloak/tests/pilot-login-smoke.sh` | A sign-in against the production realm configuration |
| `deployment/dr/restore-drill.sh`, `deployment/dr/stack-drill.sh` | Destroy and restore from backup, database tier or whole stack |

**Limits.** The pilot README says it is not a finished deployment: nothing marks reports as pilot output yet (shadow mode is a later package). The rehearsals above ran on a development workstation. The repo records no run on a customer VM. It is one host with no failover.

## 7. Before any real data

Do these before DLAP is reachable by anyone else or reads anything real. Under `SPRING_PROFILES_ACTIVE=prod` a boot guard refuses to start with an empty secret, a published default, a published database password, or JWT validation switched off (`CHANGELOG.md`, "Upgrade notes"). It is a backstop for "I forgot", not a review of your secrets.

| # | Step | Document |
| --- | --- | --- |
| 1 | Rotate every dev secret: database passwords, the Keycloak admin, `DLAP_BACKEND_CLIENT_SECRET`, `DLAP_MCP_GATEWAY_CLIENT_SECRET`, `DLAP_ENCRYPTION_KEY`, the Grafana password. Run the grep in section 1 on the manifests you deploy | [`deployment/PRODUCTION.md`](../../deployment/PRODUCTION.md) section 1 |
| 2 | Never import the dev realm. Remove the five seeded users, use the production realm template, turn on MFA, federate your identity provider, narrow redirect URIs | [`production/README.md`](../../deployment/platform/keycloak/production/README.md), [`keycloak-prod-setup.md`](../launch/keycloak-prod-setup.md) |
| 3 | Run Keycloak in production mode on PostgreSQL and replace the temporary bootstrap administrator | `deployment/PRODUCTION.md` section 3 |
| 4 | Turn on JWT issuer and audience validation. Both are off in the quickstart, so any token of the realm is accepted | `deployment/PRODUCTION.md` section 4 |
| 5 | Encrypt every hop and authenticate Kafka clients | [`transport-security.md`](transport-security.md), [`SECURITY.md`](../../SECURITY.md) |
| 6 | Move stored cloud credentials off the default `encrypted-store`: use `secret-manager` or `federated` | [`credential-store.md`](credential-store.md) |
| 7 | Database: a managed PostgreSQL 15, one role and password per service (`gen-db-passwords.sh`), `ddl-auto=validate`, and no `03-insert-test-data.sql` | `deployment/PRODUCTION.md` section 2, [`CHANGELOG.md`](../../CHANGELOG.md) "Upgrade notes" |
| 8 | Backups off the host, an escrow copy of `DLAP_ENCRYPTION_KEY` (it is in no backup), and one restore drill on the real VM | [`backup-and-restore.md`](backup-and-restore.md) |
| 9 | Cloud access with least privilege: the read-only Reader role per cloud; separate Actor roles only where archive or delete is meant | [`cloud-permissions.md`](cloud-permissions.md) |
| 10 | Keep deletion dry-run and archival mocked until you have verified a run. Outside the dev compose `DLAP_AUTODELETE_DRY_RUN_CLOUD_DELETE` defaults to **false** in the code, which deletes for real: set it to `true` yourself. Keep `da-autodelete` and `da-autoarchival` stopped wherever other people can reach an unhardened stack: its Kafka has no authentication | `deployment/PRODUCTION.md` section 8.2, `SECURITY.md` |
| 11 | Kafka: several brokers, replication factor 3, broker auto-create off, ACLs | `deployment/PRODUCTION.md` section 6 |
| 12 | Monitoring: run Prometheus and Alertmanager, add a receiver (the shipped config routes alerts nowhere), keep actuator endpoints off public addresses | [`docs/observability.md`](../observability.md) |
| 13 | Read the threat model and its accepted risks | [`docs/architecture/threat-model.md`](../architecture/threat-model.md) |

`deployment/PRODUCTION.md` section 7 is the pre-flight checklist, and section 8 lists the environment every service needs under `prod`.

## 8. Connecting a cloud account

**How credentials are supplied.** Server-side only: through the environment, a secret manager or the runtime's own identity. The UI has no screen to create or edit a cloud connection. Prefer the VM or pod identity over a stored key.

| Cloud | Discovery crawlers in code | Audit-log ingest | Read-only role | Known gaps |
| --- | --- | --- | --- | --- |
| AWS | S3, Glue, RDS, EC2, processing jobs | CloudTrail `LookupEvents` (management events only). CloudTrail trail files and S3 server access logs from S3, on the `registry` pipeline with its switch on | `deployment/iam/aws/reader/` (CloudFormation or Terraform) | S3 object reads are data events: they arrive only through trail files, never through `LookupEvents`. No CloudWatch Logs ingest |
| GCP | GCS, BigQuery, Cloud SQL, BigQuery jobs | Cloud Logging API; GCS and BigQuery log sinks on the `registry` pipeline | `deployment/iam/gcp/reader/` (Terraform) | Object reads appear only if you enable Data Access audit logs in GCP |
| Azure | Partial and experimental (ADR-0017) | None | `deployment/iam/azure/reader/` (Terraform, two read actions) | VM discovery and Azure Monitor analysis are not implemented. The docs disagree on the Data Factory and Synapse job crawler (README: real; `cloud-permissions.md`: offline stubs), so treat it as unverified |
| Databricks, Unity Catalog | Unity Catalog crawler, Databricks processing jobs | None | `deployment/iam/databricks/reader/` (Terraform) | The crawler lists only tables the service principal owns until it sends `include_browse=true` |
| OCI | Compute instances, gated off | None | None | Deferred (ADR-0026) |

The overview states that this table lists what exists in code and "does not claim each path has been exercised against a live account". The permission self-check is tested against stand-ins, and `cloud-permissions.md` says its first real answer per cloud is still to be taken on a live account.

**Discovery (AWS).** Five variables in `.env.example` section 5. For real AWS set `DLAP_AWS_ENABLED=true`, leave the endpoint and keys empty and let the AWS SDK default credential chain apply (for example an instance role); `DLAP_AWS_REGION` selects the region.

**Log sources.** Each source needs its enable flag and a Spring profile: GCP loads under `gcp` or `prod`, AWS under `aws` or `prod`. In the dev stack that means, for example, `DLAP_LOGINGEST_PROFILES=dev,gcp`, `DLAP_LOGINGEST_GCP_ENABLED=true` and `DLAP_LOGINGEST_GCP_PROJECTS=<project>`. GCP uses Application Default Credentials: workload identity on GCE or GKE, or a key file mounted through a local override compose file ([`deployment/environments/dev/README.md`](../../deployment/environments/dev/README.md), "Real cloud log sources").

**Bindings and the two pipelines.** `da-logingest` has a `legacy` pipeline (the default) and a `registry` pipeline that reads a bindings file (`DLAP_LOGINGEST_SOURCES_FILE`, default `/etc/dlap/log-sources.yml`). One binding is one account, project, subscription or workspace. A `credentials-ref` is a variable name or a role ARN, never a secret. Guide: [`log-sources.md`](log-sources.md). Example: [`deployment/examples/log-sources`](../../deployment/examples/log-sources/README.md).

**Permissions.** [`cloud-permissions.md`](cloud-permissions.md) lists every permission the Reader holds, with the code line that needs it. The Reader holds no write permission and reads content only in the audit-log buckets you name. Archive and delete use separate Actor roles, granted only on the resources you name.

## 9. Operating it

**Health.** In the dev stack every service answers `GET /actuator/health` with 200 and no token on its application port (README, [Health checks](../../README.md#health-checks); the header of `docker-compose.microservices.yml`). Notes in `deployment/environments/dev/README.md` and `deployment/PRODUCTION.md` section 8.2 that `da-policyengine` answers 401, or that `da-reporting` has no reachable health endpoint, describe an earlier state. Every other actuator endpoint needs a token. In the pilot with the `monitoring` profile, health and metrics are served on management port 9091, over TLS, to the compose network only, and the application port serves no actuator endpoint (pilot README, "Optional parts").

**Logs.** `docker compose --env-file .env -f ... -f ... logs -f --tail=100 <service>` in the dev stack; `up.sh logs -f <service>` in the demo and the pilot. The pilot rotates each log at 20 MB and keeps 5 files. Log lines carry `[service,traceId,spanId]`; the ids are empty until you add a tracer bridge.

**Metrics and alerts.** Start the monitoring profile and check it:

```bash
docker compose --env-file .env --profile monitoring \
  -f deployment/environments/dev/docker-compose.yml \
  -f deployment/environments/dev/docker-compose.microservices.yml \
  -f deployment/environments/dev/docker-compose.monitoring.yml up -d
bash deployment/platform/prometheus/verify-targets.sh
```

Prometheus is on `localhost:9090` and Grafana on `localhost:3001`. The repo ships 35 alert rules, two dashboards and runbooks ([`docs/observability.md`](../observability.md)). Alertmanager routes everything to a `null` receiver: nothing is sent until you add your own. The thresholds are first values and have not met production traffic. There are no host or container metrics.

**Upgrades.** Two layers, two mechanisms ([`upgrade.md`](upgrade.md)):

- Per-service schemas: each service runs its own Flyway migrations at startup. Nothing to do.
- Shared `dlap_common`: existing databases move forward only through `deployment/platform/database/upgrades/run-upgrades.sh`, which records each applied delta and its checksum in `dlap_common.schema_upgrades`. Preview with `--dry-run`. The pilot's `up.sh` runs it for you before any service starts; elsewhere run it yourself, with the services stopped.

Back up first. A dev or demo database is disposable: recreate it with `down -v` instead. Read the "Upgrade notes" in [`CHANGELOG.md`](../../CHANGELOG.md) before every upgrade, and [`upgrade-boot4.md`](upgrade-boot4.md) when you move from a Spring Boot 3 build.

**Backup and restore.** The pilot starts a backup agent with no setting needed: a continuous copy of the write-ahead log, a weekly base backup and a daily logical backup of every database. The targets are an RPO of 15 minutes and an RTO of 4 hours. Three things are yours to do: set an off-host command and a passphrase (without them the backups die with the VM), escrow `DLAP_ENCRYPTION_KEY`, and run the drill. Kafka, Prometheus data and Ollama models are not backed up, by design. In the quickstart, Keycloak users created at run time are lost when the container is recreated unless you schedule the realm export. The measured timings come from a development host; the example upload commands were not run against a live object store. Guide: [`backup-and-restore.md`](backup-and-restore.md). Tools: [`deployment/dr/README.md`](../../deployment/dr/README.md).

**High availability.** The shipped compose files configure no replication and no failover: one PostgreSQL, one Kafka node with replication factor 1, one instance per service. If you run several replicas yourself, the singleton scheduled jobs take a database lock (ShedLock), and Kafka consumers share one group per service. [`high-availability.md`](high-availability.md) lists the jobs that hold a lock and four that do not yet.

## 10. Known limits of this release

- **Early evaluation.** No certification (no SOC 2, ISO 27001 or similar), no SLA, no supported production topology. DLAP does not certify compliance with any regulatory framework.
- **Single tenant.** One deployment serves one customer. Multi-tenancy is not offered.
- **No Helm chart.** Production means applying `deployment/PRODUCTION.md` to your own orchestration.
- **Live cloud coverage is unproven in the repo docs.** Crawlers and log sources exist in code; the documents do not record a verified run against a live AWS, GCP or Azure account. LocalStack exercises S3 only.
- **Azure is partial and experimental.** No Azure audit-log source; VM discovery and Azure Monitor analysis emit nothing.
- **Hard cloud deletion is opt-in and unverified here.** It is a dry run in the dev, demo and pilot stacks. The code default outside those files is a real delete. There is no overwrite or certified-erasure mode, and no immutable (WORM) archive mode.
- **The quickstart is open by design.** Known passwords, plain HTTP, unauthenticated Kafka, ports on all interfaces.
- **Labels in the quickstart come from fixed rules,** not from a model. Model-based labelling needs Ollama or an OpenAI key.
- **Some policy actions do nothing yet.** Alerts, `removeLabel`, `tagAsset`, `evaluateCompliance` and `runDiscovery` are recorded only.
- **Dashboard figures include placeholders.** The governance score and cost savings use fixed constants and estimates.
- **The UI is a separate submodule,** and the menu names in this guide are not verified against it.
- **`da-mcp-pep` has no run guide** and is in no compose file. It is a reference, not a product.
- **Sizing is thin.** The quickstart was measured on one workstation, the demo on an emulation of its target VM, the pilot limits not at all.
- **Some repository documents lag the code.** For example `docs/getting-started.md` still shows an older build command and a mock labelling provider. For the quickstart, the README, `.env.example` and the compose files are current.

## 11. Getting help

- **Questions and design discussion:** GitHub Discussions at `https://github.com/Data-Automation-Lab/dlap/discussions`.
- **Bugs and feature requests:** GitHub Issues at `https://github.com/Data-Automation-Lab/dlap/issues`. Say which path you ran (A, B, C or D), the commit, and the failing command with its output. Remove secrets first.
- **Security problems:** never in a public issue. Report privately through the repository's **Security** tab, "Report a vulnerability". The policy, scope and response times are in [`SECURITY.md`](../../SECURITY.md).
- **Contributing:** [`CONTRIBUTING.md`](../../CONTRIBUTING.md) covers the process and the DCO sign-off.
