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

# DLAP: Production Deployment & Hardening Guide

**Status:** authoritative for production. **Applies to:** DLAP v0.2.x.
**Decision of record:** [ADR-0037](../docs/DECISIONS.md).

## The model: one quickstart, production is documented

DLAP ships **one** runnable Docker Compose stack, the quickstart under
`environments/dev/`, for local development, evaluation, and demos. It boots clean from a
fresh clone with **safe dev defaults and seeded demo data**. That is deliberate and correct
for a quickstart (the same model Apache Airflow uses: one `docker-compose.yaml` labeled
"not for production", plus a Helm chart and this kind of guide for real installs).

**Do not run the quickstart compose in production.** It runs Keycloak in development mode on
an embedded file database, a seeded realm with demo users, and development secret defaults. Production is the hardening
delta described below, applied on top of your own orchestration (Kubernetes/Helm, ECS,
Nomad, or a hand-managed Compose you own and secure). A Helm chart is the intended
post-launch home for a first-class production topology; until then, this guide carries it. The one other use of the quickstart files is the read-only pilot (section 9).

> **Quickstart (NOT production):**
> ```bash
> cd deployment/environments/dev
> cp ../../../.env.example .env          # then edit .env
> docker compose -f docker-compose.yml -f docker-compose.microservices.yml up -d --build
> ```

---

## 1. Secrets: fail closed

Every secret has a **safe dev default** in the quickstart. In production, **none** of them
may keep its default. Provide real values via your orchestrator's secret store (Kubernetes
Secrets, Vault, cloud secret manager) or a root `.env` derived from
[`.env.example`](../.env.example). **Never commit a populated `.env`**.

| Secret (env var) | Used by | Production requirement |
|---|---|---|
| `POSTGRES_PASSWORD` | Postgres + all services | Strong, unique. Prefer a managed DB with rotation. |
| `SPRING_DATASOURCE_PASSWORD` (or `DB_PASS`) | every service with a database | The shipped default is `<database>_pass`, the password the init script creates. Set your own and change the PostgreSQL user (section 8.1, Q-176). |
| `KEYCLOAK_ADMIN_PASSWORD` | Keycloak admin, `da-admin-service` | Strong, unique. Not `admin` and not `changeme`. Consider disabling the master admin post-bootstrap. |
| `DLAP_ENCRYPTION_KEY` | `da-admin-service` credential store | **32-byte** key, not the sample from `.env.example`. See §5 for the hex/UTF-8 caveat. Better: use `secret-manager`/`federated` credential mode ([ADR-0036](../docs/DECISIONS.md)). |
| `DLAP_BACKEND_CLIENT_SECRET` (Keycloak `dlap-backend`); in a service: `DLAP_SERVICE_AUTH_CLIENT_SECRET` | service-to-service auth | Real per-environment secret; **remove** the `change-me-dev-only` default. Pass it as its own variable, not inside `SPRING_APPLICATION_JSON`. |
| `DLAP_PDP_TOKEN` | `da-mcp-pep` | The bearer token the PEP presents to the PDP. Must not be empty. |
| `GRAFANA_ADMIN_PASSWORD` | Grafana | Set explicitly, do not rely on the `changeme` default. |
| `OPENAI_API_KEY` (if OpenAI LLM used) | `da-autolabel` | Real key, or use Ollama (`ILLMClient` local mode). |
| `DLAP_MCP_GATEWAY_CLIENT_SECRET` (Keycloak `dlap-mcp-gateway`) | MCP gateways that call `da-mcp-pep` | Real per-environment secret; the production realm template has no default. |
| Identity-provider client secret (Okta, Entra ID) | Keycloak, entered in the admin console | Stored in the Keycloak database, never in a file; track its expiry. |

### The boot guard

Under `SPRING_PROFILES_ACTIVE=prod`, every service that uses `da-protos` runs `DevSecretGuard` before it
creates any other bean (before the data source, the Kafka factories or the JWT decoder). It stops the
start when a secret is empty or still carries a default that this repository publishes. It is
auto-configured; there is nothing to enable.

**Refused** (the service does not start):

- a setting whose name looks like a secret (`password`, `secret`, `token`, `credential`, `api-key`,
  `encryption-key`) and whose value is `change-me-dev-only`, `Password123!` or the sample encryption key of
  `.env.example`;
- a password setting whose value is `admin` (the `admin`/`admin` login), `changeme` or `change-me`;
- an empty `dlap.service-auth.client-secret` (checked when `dlap.service-auth.enabled=true`, the only case
  in which a service uses it), an empty `app.encryption.key` (`DLAP_ENCRYPTION_KEY`, `da-admin-service`),
  an empty `keycloak.admin.password` (`da-admin-service`) or an empty `dlap.pdp.token`
  (`DLAP_PDP_TOKEN`, `da-mcp-pep`).

**Warned about** (logged at start, and refused as well when `DLAP_SECURITY_DEV_SECRET_GUARD_STRICT=true`):

- a database password that is the published `<database>_pass` (for example `da_catalog_pass`,
  `dlap_common_pass`) or `dlap`;
- JWT issuer or audience validation that is off (`dlap.security.jwt.validate-issuer` and
  `validate-audience`, section 4). `da-mcp-pep` always validates and is not reported.

These two are warnings because the init scripts still create the `<database>_pass` logins and have no
variable to change them (Q-176), so a deployment cannot satisfy the first yet without changing the users in
PostgreSQL by hand (section 8.1). Turn strict mode on once you have.

How it looks: it scans the property sources of the service (application files, `SPRING_APPLICATION_JSON`,
system properties, the OS environment), resolves each placeholder the way the service does (so
`${DLAP_SERVICE_CLIENT_SECRET:change-me-dev-only}` is judged by what it resolves to) and reports each
setting once. The message names the setting and where it came from, never the value:

```text
Refusing to start: 2 secret setting(s) are empty or still carry a published development default. Set real
values (deployment/PRODUCTION.md, section 1): keycloak.admin.password (applicationConfig:
[classpath:/application.properties]) carries a published development default; app.encryption.key
(applicationConfig: [classpath:/application.properties]) is empty and must be set
```

Switches, both optional: `DLAP_SECURITY_DEV_SECRET_GUARD_PROFILES` (comma list, default `prod`; an empty value
means `prod`) names the profiles the guard applies to, for a deployment whose production profile has another name;
`DLAP_SECURITY_DEV_SECRET_GUARD_STRICT` (default `false`) is described above. There is no switch to turn
the guard off. Outside those profiles (`dev`, `test`) it does nothing, which is what keeps the quickstart
booting with its defaults.

What the guard does not see: a weak secret that is not one of the published defaults, a secret that is
set but wrong, and a component that does not use `da-protos` (the Keycloak container, Grafana, PostgreSQL).
It is a backstop for "I forgot to set it", not a review of your secrets.

**Verify no default secret reaches production:**
```bash
grep -rEn "change-me-dev-only|Password123!|:-admin\b|:-dlap\b|:-changeme\b" deployment/ | grep -v PRODUCTION.md
```
This must return nothing in the manifests you actually deploy. (The dev compose pair matches, by design.)

---

## 2. Database: external, HA, validate-only

- **Use a managed/HA PostgreSQL 15** (RDS, Cloud SQL, Patroni, …), not the single-node
  quickstart Postgres container.
- Create one database + least-privilege user **per service** plus the shared `dlap_common`.
  The init script honors its list (ADR-0037), so a one-shot bootstrap job can reuse it:
  ```bash
  POSTGRES_MULTIPLE_DATABASES=dlap_common,da_discovery,da_catalog,da_policyengine,\
  da_reporting,da_autolabel,da_autoarchival,da_autodelete,da_autocompliance,da_admin,da_logingest \
  KEYCLOAK_DB_NAME=keycloak \
    ./deployment/platform/database/init-scripts/00-create-multiple-databases.sh
  ```
  Or provision these through your DBA tooling with the same names.
- Point each service at its DB with `SPRING_DATASOURCE_URL=jdbc:postgresql://<host>:5432/<db>`
  using the **exact** `da_<svc>` / `dlap_common` names above (name drift here is the classic
  boot failure; see ADR-0037).
- **`spring.jpa.hibernate.ddl-auto=validate`** in production (never `update`/`create`).
  Flyway owns schema per service; `dlap_common` bootstraps from the SQL init scripts.
  > Every service ships `validate` and a Flyway baseline (DB-01); `ProdProfileContextTest` starts each one under
  > `prod` on a database built from these scripts, so migration drift fails the build (section 8).
- **Do not** mount `03-insert-test-data.sql` (demo rows) against a production database.

---

## 3. Keycloak: production mode on Postgres, the production realm template

Run Keycloak 26.8.1 (the release every compose file pins, `platform/keycloak/README.md`) in production
mode. The quickstart runs `start-dev` on an embedded file database and imports the **development** realm
(`platform/keycloak/keycloak-import/dlap-realm.json`), which seeds five people with the published password
`Password123!` and opens the password grant. **Never import that realm into production.**

**Server** (`platform/keycloak/Dockerfile`; the pilot overlay builds and runs it):
- Build once with `kc.sh build` and the build-time options `KC_DB=postgres`, `KC_HEALTH_ENABLED=true`,
  `KC_METRICS_ENABLED=false` and `KC_FEATURES_DISABLED=scripts,impersonation,device-flow,ciba,kerberos`;
  start with `kc.sh start --optimized`, which does no build at start and refuses an option the image was not
  built with. The scripts feature (JavaScript authenticators and mappers) stays off.
- Database: `KC_DB_URL=jdbc:postgresql://<host>:5432/keycloak`, `KC_DB_USERNAME`, `KC_DB_PASSWORD`; create the
  `keycloak` database and role (`KEYCLOAK_DB_NAME=keycloak` for the bootstrap in section 2). Back the
  database up: it holds accounts, OTP devices and events.
- Host name: `KC_HOSTNAME=<public URL>` fixes one issuer for every token, `KC_HOSTNAME_BACKCHANNEL_DYNAMIC=true`
  keeps the internal URL working for keys and tokens. Behind a TLS terminator also set `KC_PROXY_HEADERS`
  (`xforwarded` or `forwarded`) to match it, and `KC_HTTP_ENABLED=false` once Keycloak itself serves TLS.
- Health on the management port 9000 (`/health/ready`, `/health/live`); publish nothing but the HTTP(S) port.
- The first start creates the temporary master admin from `KC_BOOTSTRAP_ADMIN_USERNAME` and
  `KC_BOOTSTRAP_ADMIN_PASSWORD`. Replace it with a permanent administrator that has a one-time code
  (`platform/keycloak/production/README.md`, "First start").

**Realm:** import `platform/keycloak/production/dlap-realm.json` (`--import-realm`, once, into an empty
database; later changes go through the admin console). It already meets the checklist below; its README
says what it contains and which variables it reads (`DLAP_BACKEND_CLIENT_SECRET`,
`DLAP_MCP_GATEWAY_CLIENT_SECRET`, `DLAP_APP_URL`, `DLAP_API_AUDIENCE`).
- [ ] **No seeded people.** Accounts come from your identity provider or an operator.
- [ ] **MFA**: a one-time code at every sign-in for `ROLE_ADMIN`, `ROLE_CDO`, `ROLE_POLICY_MANAGER` and every
      role that includes the marker role `dlap-mfa-required` (add it to every approver role you create),
      also after an identity provider's sign-in (post login flow `dlap post broker mfa`).
- [ ] **No password grant and no implicit flow** on any client; `dlap-ui` uses PKCE S256 and exact redirect
      URIs and origins, no wildcard; `dlap-backend` and `dlap-mcp-gateway` are client credentials only.
- [ ] **Real client secrets** injected through the environment at import; the template has no default.
- [ ] **Audience**: `dlap-ui` and `dlap-backend` tokens carry `dlap-api` (section 4); `dlap-mcp-gateway`
      tokens carry only `dlap-mcp-pep`.
- [ ] **Login and admin events** stored (30 days); brute-force protection on; 5-minute access tokens.
- [ ] **No social brokers.** Corporate sign-in goes through an OpenID Connect provider: Okta and Microsoft
      Entra ID are documented in `platform/keycloak/production/README.md`.
- [ ] `sslRequired`: the template ships `external` (plain HTTP only from private addresses, which a single-VM
      internal network uses); set `all` once every hop is TLS.

---

## 4. JWT validation: enforce issuer & audience

Services validate JWT **signature only** by default (Q-014 dev relaxation: the UI token
carries `iss=localhost:8180` while service tokens carry `iss=keycloak:8080`, both signed by
the same realm key). Issuer + audience validation ships built-in but **off by default**;
enable it in production so a token minted for a different realm/audience is rejected:

```properties
# Point every service at ONE externally-reachable issuer (avoids the localhost vs
# keycloak:8080 split that motivated the dev relaxation), then turn on validation:
DLAP_SECURITY_JWT_VALIDATE_ISSUER=true
DLAP_SECURITY_JWT_EXPECTED_ISSUER=https://auth.example.com/realms/dlap
DLAP_SECURITY_JWT_VALIDATE_AUDIENCE=true
DLAP_SECURITY_JWT_EXPECTED_AUDIENCE=dlap-api
```

`DLAP_SECURITY_JWT_EXPECTED_ISSUER` takes one issuer or a comma separated list, written exactly as the
tokens carry them in `iss`. List every issuer your realm signs for, for example the public URL and the
internal one while services still fetch tokens from `http://keycloak:8080`:

```properties
DLAP_SECURITY_JWT_EXPECTED_ISSUER=https://auth.example.com/realms/dlap,http://keycloak:8080/realms/dlap
```

With both flags off (the default) the decoder validates signature + timestamp only:
identical to the dev relaxation. These are wired into every service's JWT decoder, and the dev compose pair
passes all four variables to every service (`.env.example`, "JWT validation"), so setting them in `.env` is
enough there. Under `prod` the boot guard of section 1 warns while a flag is off and refuses to start when
`DLAP_SECURITY_DEV_SECRET_GUARD_STRICT=true`. `da-mcp-pep` has no flags: it always validates, and refuses to
start without an issuer and an audience (section 8.2).

---

## 5. Credential store: prefer a real secret backend

The `da-admin-service` credential store defaults to `encrypted-store` (AES-256/GCM in DLAP's
own DB), which is a **dev-grade** default:
- The AES key comes from `DLAP_ENCRYPTION_KEY` and must decode to **32 bytes**. Note the
  documented `openssl rand -hex 32` yields 64 hex chars; supply the key so it resolves to
  32 bytes (hex-decoded); mismatches fail service startup.
- `encrypted-store` has no envelope key-id, so rotating the key orphans existing ciphertext.
  Treat it as dev-only.
- **Production:** set `dlap.credentials.provider=secret-manager` (supply a `SecretResolver`
  bean, e.g. Vault) or `federated` (ambient cloud identity, no stored secret). See
  [ADR-0036](../docs/DECISIONS.md) and [credential-store.md](../docs/guides/credential-store.md).

---

## 6. Transport, actuator, monitoring, resources

- **TLS everywhere**: terminate HTTPS at your ingress/load balancer; consider mTLS between
  services. Never expose plaintext service ports publicly.
- **Actuator**: keep only `/actuator/health` (and readiness/liveness) reachable; restrict
  `env`, `beans`, `mappings`, `httpexchanges`, etc. Metrics scrape should be network-internal.
- **Monitoring**: Prometheus/Grafana are `monitoring`-profile services in the quickstart;
  in production run them (or your own stack) with a real `GRAFANA_ADMIN_PASSWORD` and
  authenticated scrape.
- **Resources / JVM**: set container CPU/memory limits and JVM heap per service; DLAP’s
  distroless Java 21 images honor standard `JAVA_TOOL_OPTIONS`. The pilot overlay shows a worked set of limits and heap sizes.
- **Kafka**: a real multi-broker cluster with `replication.factor >= 3` and `min.insync.replicas >= 2` (the quickstart runs a single KRaft node with RF=1). Switch broker auto-create off (`auto.create.topics.enable=false`). The services create their own topics, see 6.1.

### 6.1 Kafka topics

Each service that produces topics creates them, and the `<topic>.DLT` dead-letter topic of each, when it starts (`config/KafkaTopicsConfiguration.java`; Q-336, Q-369, Q-380, ADR-0079). With auto-create off, a dead-letter publish to a topic that does not exist is rejected, so either let the services provision, or create the topics below before the first start.

| Service | Topics (each also has `<topic>.DLT`) | Settings |
|---|---|---|
| da-logingest | `dlap.log.event.normalized`, `dlap.log.event.quarantine`, `dlap.logingest.binding.status` | quarantine `retention.ms=2592000000` (30 days); binding status `cleanup.policy=compact` |
| da-catalog | `dlap.asset.change.event`, `dlap.catalog.asset.upserted`, `dlap.asset.label.assignment.changed`, `dlap.catalog.asset.retention-due`, `dlap.logingest.binding.status` (also declared by da-logingest) | retention-due `retention.ms=1209600000` (14 days); binding status `cleanup.policy=compact` |
| da-discovery | `dlap.discovery.asset.discovered`, `.modified`, `.deleted`, `.accessed`, `.permission-changed`, `.archived` | defaults |
| da-policyengine | `dlap.policies.actions`, `dlap.access.decision` | defaults |
| da-autolabel | `dlap.labeling.request.event` | defaults |

Dead-letter topics: 1 partition, `retention.ms=1209600000` (14 days). Not provisioned yet: da-discovery's `resource-*` topics and their dead-letter topics (Q-420); create those yourself when auto-create is off.

Set on every service:

| Variable | Default | Notes |
|---|---|---|
| `DLAP_KAFKA_TOPIC_PARTITIONS` | broker `num.partitions` | Set 6 on the production cluster. |
| `DLAP_KAFKA_TOPIC_REPLICATION_FACTOR` | broker `default.replication.factor` | Set 3. A single broker needs 1 or unset. |
| `DLAP_KAFKA_PROVISION_TOPICS` | `true` | `false` when the topics are created by your own tooling; then create the table above, including the `.DLT` topics. |
| `DLAP_KAFKA_QUARANTINE_RETENTION`, `DLAP_KAFKA_RETENTION_DUE_RETENTION`, `DLAP_KAFKA_DLT_RETENTION` | `P30D`, `P14D`, `P14D` | ISO-8601 durations. |

da-discovery under `prod` takes the sizing from `KAFKA_ADMIN_PARTITIONS` (default 6) and `KAFKA_ADMIN_REPLICATION_FACTOR` (default 3), as before.

If Kafka ACLs are on, each service principal needs Create, Describe, DescribeConfigs and Alter on its own topics (Alter is used only to add partitions), plus Read on the topics it consumes and Write on its dead-letter topics.

The services create missing topics and add partitions. They never lower a partition count and never change the configs of a topic that exists. On a cluster whose topics were auto-created earlier, run once (docs/guides/upgrade.md, "Kafka topics"):

    kafka-configs --bootstrap-server "$BOOTSTRAP" --alter --entity-type topics --entity-name dlap.logingest.binding.status --add-config cleanup.policy=compact
    kafka-configs --bootstrap-server "$BOOTSTRAP" --alter --entity-type topics --entity-name dlap.log.event.quarantine --add-config retention.ms=2592000000
    kafka-configs --bootstrap-server "$BOOTSTRAP" --alter --entity-type topics --entity-name dlap.catalog.asset.retention-due --add-config retention.ms=1209600000

A broker that is down at start-up is logged once as an ERROR (`Could not configure topics`) and does not stop the service; the next start retries.

---

## 7. Pre-flight checklist

- [ ] All secrets set to real values; the §1 grep returns nothing in deployed manifests, and no service
      stops at start-up with a boot guard refusal (§1). Strict mode on once the database users are changed.
- [ ] Per-service + `dlap_common` (+ `keycloak`) databases exist with the exact names in §2.
- [ ] `ddl-auto=validate`; Flyway migrations applied cleanly per service.
- [ ] Keycloak 26.8.1 `start --optimized` on Postgres; production realm template imported (§3 checklist
      ticked); the temporary master admin replaced; every MFA account has registered its authenticator.
- [ ] JWT issuer/audience validation enabled (§4).
- [ ] Credential provider set to `secret-manager` or `federated` (§5).
- [ ] TLS terminated; actuator surface restricted; Grafana password set.
- [ ] Kafka RF/ISR set for durability; DLQ topics present.
- [ ] `/actuator/health` returns 200 on every service; a fresh smoke run passes.
- [ ] Every variable of section 8 is set for every service, and `ProdProfileContextTest` passes
      (`./gradlew test --tests "*ProdProfileContextTest*"`, Docker required).

---

## 8. Environment under the prod profile

Every service starts under `SPRING_PROFILES_ACTIVE=prod` with the variables below. `ProdProfileContextTest`
in each of the eleven services starts the full application context under `prod` with exactly this
environment, against a PostgreSQL 15.1 built from the platform init scripts (00 to 02, without the
03 demo seed), and fails the build when a service no longer starts. The test is a Testcontainers test, not a
slice test, and needs Docker. da-mcp-pep has no database and needs no Docker. da-discovery runs in a forked JVM
because its test classes live in the package that its main class scans.

### 8.1 Every service with a database

| Variable | Value | Notes |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `prod` | |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://<host>:5432/da_<service>` | The exact names of section 2. da-catalog uses `dlap_common`. da-discovery has no default and fails fast without it. |
| `KAFKA_BOOTSTRAP_SERVERS` | `<broker>:<port>` | da-discovery reads `SPRING_KAFKA_BOOTSTRAP_SERVERS` instead; under `prod` it has no default and the start fails naming it (section 8.3). |
| `DLAP_KAFKA_TOPIC_PARTITIONS`, `DLAP_KAFKA_TOPIC_REPLICATION_FACTOR` | optional | Sizing of the topics the service provisions. See 6.1. |
| `KEYCLOAK_AUTH_SERVER_URL` | base URL of Keycloak, without the realm path | The services append `/realms/dlap/...`. The keys are fetched on the first request, so Keycloak need not be up at start (da-discovery is the exception, see below). |
| `SPRING_JPA_HIBERNATE_DDL_AUTO` | `validate` | Already the shipped default; set it anyway so no file can change it. |

The database user and password are not part of the table. Each service connects as `da_<service>_user` with the
password that `00-create-multiple-databases.sh` gave it (`<database>_pass`, fixed in the script). To use other
credentials, change the users in PostgreSQL and set `SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD`
for that service. The init script has no variable for the passwords (Q-176), so keep PostgreSQL off every
network except the services' own until it has. The boot guard (section 1) warns about the `<database>_pass`
passwords under `prod` and refuses them in strict mode; the secrets it refuses outright (the client secret
of `dlap-backend`, the encryption key, the Keycloak admin password) are part of section 8.2.

Services that call other services also need the service-to-service client: `dlap.service-auth.enabled=true`,
`dlap.service-auth.token-uri`, `dlap.service-auth.client-id` and `dlap.service-auth.client-secret`, plus the
target service's URL. The quickstart compose file shows the keys for each service. Pass the secret as its own
variable, `DLAP_SERVICE_AUTH_CLIENT_SECRET`, and keep the rest in `SPRING_APPLICATION_JSON` if you like: the
boot guard refuses `change-me-dev-only` and an empty value once `dlap.service-auth.enabled` is true.

### 8.2 Per service

| Service | Needs on top of 8.1 |
|---|---|
| da-admin-service | `DLAP_ENCRYPTION_KEY`, 64 hex characters (no default; the service refuses to start without a 32-byte key, and the boot guard refuses an empty one and the sample key). `KEYCLOAK_ADMIN_USER` and `KEYCLOAK_ADMIN_PASSWORD` for the Keycloak admin API (the shipped default password `admin` is refused). |
| da-autoarchival | `DA_CATALOG_URL`. `DLAP_AUTOARCHIVAL_GCS_MOCK_MODE` and `DLAP_AUTOARCHIVAL_AZURE_MOCK_MODE` default to false, so prod performs the real storage-class transition and needs cloud credentials. |
| da-autocompliance | `DA_CATALOG_URL`. |
| da-autodelete | `CATALOG_SERVICE_URL`. `DLAP_AUTODELETE_DRY_RUN_CLOUD_DELETE` defaults to false in the code, which deletes for real. Set it to `true` until a deletion is meant. |
| da-autolabel | `DA_CATALOG_URL`. `dlap.autolabel.llm-provider` (`rules`, `ollama` or `openai`; the code default is `ollama`) through `SPRING_APPLICATION_JSON`. Actuator is on the separate management port 8385. |
| da-catalog | Nothing more. |
| da-discovery | See 8.3. |
| da-logingest | The prod profile also loads the GCP and CloudTrail log sources. Each stays off until its own flag is set (`DLAP_LOGINGEST_GCP_ENABLED`, `DLAP_LOGINGEST_AWS_ENABLED`). Credentials come from the runtime identity. |
| da-mcp-pep | `DLAP_PDP_BASE_URL` and `DLAP_PDP_TOKEN` (the bearer token it presents to the PDP; the boot guard refuses an empty one). It is a JWT resource server with no off switch, so it also needs `KEYCLOAK_AUTH_SERVER_URL` (the realm keys are read from it), `DLAP_SECURITY_JWT_EXPECTED_ISSUER` (one issuer or a comma list, as the tokens carry it in `iss`) and `DLAP_SECURITY_JWT_EXPECTED_AUDIENCE`; it refuses to start without an issuer and an audience, and answers `401` to a call with no token. `DLAP_MCPPEP_MODE` stays `enforce`, which denies when the PDP is unreachable. No database, no Kafka. |
| da-policyengine | Nothing more. An unauthenticated `/actuator/health` answers 401. |
| da-reporting | `MANAGEMENT_HEALTH_MAIL_ENABLED=false` unless SMTP is configured: the mail check dials `smtp.gmail.com` with placeholder credentials and holds the aggregate health DOWN. Actuator is on the separate management port 8380. |

### 8.3 da-discovery

da-discovery has its own `prod` configuration (`config/application-prod.yml`). It fails fast, naming the
variable, when one of these is missing.

| Variable | Notes |
|---|---|
| `SPRING_DATASOURCE_URL` | Required. No default. |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | Required, no default under `prod`: the context fails fast, naming the variable (ProdProfileContextTest.refusesToStartWithoutTheKafkaBootstrapServers). The same list serves every Kafka client of the service (`spring.kafka.*`); the old `kafka.*` client block is gone (Q-334). |
| `GCP_PROJECT_ID` | Required. The GCP clients are built for this project. A deployment that never scans GCP can set any value. |
| `JHIPSTER_REGISTRY_PASSWORD` | Required and unused: any value. DLAP runs no JHipster registry, but `bootstrap-prod.yml` fails the whole context without it (Q-173). |
| `SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_OIDC_ISSUER_URI` | Default `http://keycloak:8080/realms/dlap`. The OAuth2 client registration reads the issuer's `.well-known/openid-configuration` while the context is built, so Keycloak must be reachable at start. When Keycloak's front-end URL is fixed (`KC_HOSTNAME`, section 3), the issuer in that document is the public URL and the start fails with "Unable to resolve Configuration with the provided Issuer"; then point the registration at a provider with explicit endpoints, as the pilot Keycloak overlay does (`SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_OIDC_PROVIDER=pilot`, `SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_PILOT_*` and `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_OIDC_AUTHORIZATIONGRANTTYPE=authorization_code`: without an issuer nothing supplies the grant type, and the start fails again). |
| `KAFKA_ADMIN_REPLICATION_FACTOR` and `KAFKA_ADMIN_PARTITIONS` | Default 3 and 6, the production values of section 6. A single broker needs `KAFKA_ADMIN_REPLICATION_FACTOR=1`. |
| `DLAP_DISCOVERY_JOB_EXECUTOR_MODE` | Default `DEFAULT` (crawls run in the process). `SPARK` and `NIFI` also need `discovery.job.executor.spark.enabled` or `.nifi.enabled` and an external cluster; without it the start-up message names the flag. Before DEP-01 the profile selected `SPARK` unconditionally and never started. |
| `CLOUD_PROVIDER_GCP_ENABLED`, `DLAP_AWS_ENABLED`, `CLOUD_PROVIDER_UNITY_ENABLED` | Provider switches. The quickstart compose sets the GCP one to `true`. |

Its `prod` profile also validates the schema (`ddl-auto: validate`), turns the Spring Cloud Secret Manager
starter off (it needs a credentials bean that the disabled core never creates), and listens on `SERVER_PORT`
(default 8080) like the other services.

## 9. The read-only pilot

The first pilot runs on one VM in the customer's cloud account, as model M3 of
[`docs/architecture/deployment-models.md`](../docs/architecture/deployment-models.md). It is the
quickstart pair plus compose overlays in [`environments/pilot/`](environments/pilot/README.md), which start the
services under `prod` with `validate`, a memory limit and an explicit `-Xmx` per container, every port on
`127.0.0.1`, no archive or delete service, dry-run deletes and no demo seed. `environments/pilot/up.sh`
applies the overlays in sorted order and refuses to start with a missing or published-default secret. Section 8 is
the environment it sets. The production Keycloak is the overlay
`environments/pilot/docker-compose.pilot.keycloak.yml` (IAM-02): `start --optimized` on the pilot PostgreSQL
with the production realm template, one issuer (`DLAP_PILOT_ID_URL`) and the redirect URIs of
`DLAP_PILOT_APP_URL`. With it present, `up.sh` no longer asks for `PILOT_ALLOW_DEV_REALM`. Check it with
`platform/keycloak/tests/pilot-login-smoke.sh`. TLS, shadow mode and backups arrive as further overlays; until
they do, the pilot is a base for a lab VM, not a production topology.
