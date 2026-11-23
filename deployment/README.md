# Deployment layout

## Canonical local stack

For a full developer stack (Postgres, Kafka, Keycloak, Redis, and the ten Spring services in this compose), use:

```bash
cd environments/dev
docker compose -f docker-compose.yml -f docker-compose.microservices.yml up -d --build
```

`docker-compose.yml` brings up shared infrastructure; `docker-compose.microservices.yml` layers the Java services and wiring expected by the README quickstart.

The ten Spring services started by `docker-compose.microservices.yml` are
`da-discovery`, `da-catalog`, `da-policyengine`, `da-reporting`, `da-autolabel`,
`da-autoarchival`, `da-autodelete`, `da-autocompliance`, `da-admin-service` and
`da-logingest`, alongside `new-ui` and the nginx `gateway`.

`settings.gradle.kts` includes **eleven** Spring Boot modules: the ten above plus
`da-mcp-pep`, the standalone reference MCP Policy Enforcement Point (ADR-0040),
which is **not** part of this compose and is run separately against the PDP. The
remaining included modules are libraries or the frontend: `da-protos`,
`da-llm-client` (ADR-0044) and `new-ui`.

## Directory map

| Path | Role |
| --- | --- |
| `environments/dev/` | **The canonical quickstart stack** (ADR-0037) - local dev, evaluation, demos. |
| `environments/test/` | Test-oriented compose overlays. |
| `PRODUCTION.md` | Production deployment & hardening guide. Production is documented, **not** a second compose stack (ADR-0037). |
| `platform/database/init-scripts/` | Postgres bootstrap for the shared `dlap_common` schema and the per-service databases (`02-create-common-schema.sql:25`). |
| `platform/database/upgrades/` | Idempotent SQL deltas for existing databases (e.g. index additions). |
| `platform/keycloak/` | Realm and health-check helpers. |
| `scripts/` | Operational shell helpers (GCP, health checks, etc.). |

## Environment files

Copy the repo-root `.env.example` to `.env` (or export variables) before `docker compose`. Compose files reference `${POSTGRES_PASSWORD}` and other keys documented there.

## Changing configuration

Prefer editing `.env` and service `application.properties` defaults rather than hardcoding secrets in compose YAML.
