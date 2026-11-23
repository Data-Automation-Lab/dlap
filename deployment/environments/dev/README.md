# Development environment

The local DLAP stack: PostgreSQL, Keycloak, Kafka (KRaft), the ten Spring Boot services, an nginx gateway and the UI. The e2e harnesses and the demos run on it. The same commands are in the root [`README.md`](../../../README.md) Quickstart.

## Start

Run from the **repo root**, in Git Bash on Windows. The UI is a git submodule, so clone with `--recurse-submodules` (or run `git submodule update --init --recursive` afterwards).

```bash
cp .env.example .env
export JAVA_HOME="/c/Program Files/Java/jdk-21"   # Gradle 8.7 needs JDK 21, not 24+
./gradlew build -x test                            # the Dockerfiles COPY build/libs/*.jar

docker compose --env-file .env \
  -f deployment/environments/dev/docker-compose.yml \
  -f deployment/environments/dev/docker-compose.microservices.yml \
  up -d --build
```

### Which `.env`

There is one env file: `.env` at the repo root, created from [`.env.example`](../../../.env.example). Compose reads `.env` from the compose file's directory (this one), not from the repo root, so `--env-file .env` is required or your `.env` is silently ignored. To drop the flag on later commands run `export COMPOSE_ENV_FILES=.env` once per shell. Do not also create `deployment/environments/dev/.env`: Compose would read it automatically and the two files could disagree. [`env.dev.example`](env.dev.example) only points back here.

If a variable is unset, the compose files fall back to the same dev-only defaults, so the stack also starts without a `.env`.

## Files

| File | What it defines |
| --- | --- |
| `docker-compose.yml` | Infrastructure: PostgreSQL, Keycloak, Kafka. Opt-in profiles: `monitoring` (Prometheus, Grafana), `aws` (LocalStack), `unity` (Unity Catalog server) |
| `docker-compose.microservices.yml` | The ten services, the gateway, the UI and Ollama (plus its one-shot model pull) |
| `gateway/nginx.conf` | Single browser origin on 8081: serves the UI and routes `/api/v1/<service>` |
| `env.dev.example` | Pointer to the root `.env.example` (no variables) |
| `env.social-login.example` | Notes for Google and Facebook login in Keycloak. Put its `GOOGLE_*` and `FACEBOOK_*` variables in the root `.env`, not in a second `.env` |

## Ports (host)

| Port | Service |
| ---: | --- |
| 8081 | Gateway (UI plus API) |
| 3000 | UI container, direct |
| 8180 | Keycloak (HTTP), 8443 (HTTPS) |
| 5432 | PostgreSQL |
| 9092 | Kafka (host listener) |
| 8091 | da-discovery (8081 is the gateway) |
| 8082 to 8089 | da-catalog, da-policyengine, da-reporting, da-autolabel, da-autoarchival, da-autodelete, da-autocompliance, da-admin-service |
| 8090 | da-logingest |
| 11434 | Ollama |
| 9090, 3001 | Prometheus, Grafana (`monitoring` profile) |
| 4566 | LocalStack (`aws` profile) |
| 18080 | Unity Catalog (`unity` profile) |

## Health

Every service exposes `/actuator/health`. After the stack is up:

```bash
docker compose ps                                 # healthcheck column (needs the same -f / --env-file flags)
curl -sf http://localhost:8091/actuator/health    # da-discovery
curl -sf http://localhost:8082/actuator/health    # da-catalog
curl -sf http://localhost:8090/actuator/health    # da-logingest
```

Two services differ:

- **da-policyengine (8083)** has no public matcher in its `SecurityConfiguration` (`SecurityConfiguration.java:69-77` in `da-policyengine`), so an unauthenticated `/actuator/health` answers `401`. Use `docker compose ps` or send a bearer token.
- **da-reporting (8084)** serves actuator on an unpublished management port (`management.server.port=8380`). Use `docker compose ps`.

## Sign in

Keycloak imports the `dlap` realm from `deployment/platform/keycloak/keycloak-import/dlap-realm.json`. It seeds the users `admin`, `cdo`, `director`, `dataengineer` and `datascientist`, all with the dev password `Password123!`. The Keycloak master-realm console uses `KEYCLOAK_ADMIN` and `KEYCLOAK_ADMIN_PASSWORD` from your `.env`. These are development credentials; rotate them before the stack is reachable by anyone else ([`docs/launch/keycloak-prod-setup.md`](../../../docs/launch/keycloak-prod-setup.md)).

## Real cloud log sources (opt-in)

`da-logingest` can pull real audit logs. Both sources are off by default and need an enable flag **and** a Spring profile, because the GCP source loads only under profile `gcp` (or `prod`) and the AWS source only under `aws` (or `prod`):

```bash
# .env, GCP Cloud Logging
DLAP_LOGINGEST_PROFILES=dev,gcp
DLAP_LOGINGEST_GCP_ENABLED=true
DLAP_LOGINGEST_GCP_PROJECTS=my-project-id

# .env, AWS CloudTrail (leave the endpoint and keys empty to use the SDK default chain)
DLAP_LOGINGEST_PROFILES=dev,aws
DLAP_LOGINGEST_AWS_ENABLED=true
DLAP_LOGINGEST_AWS_REGIONS=us-east-1
```

All settings are listed in `.env.example`, section 6. These are separate from the discovery AWS variables (`DLAP_AWS_*`, section 5).

GCP credentials come from Application Default Credentials. On GCE or GKE, workload identity needs nothing more. On a laptop, mount a key file with a local override file that you do not commit, and add it as a third `-f` (set `GOOGLE_APPLICATION_CREDENTIALS_FILE` to the key path in your `.env`; adapt the path style to your OS):

```yaml
# docker-compose.gcp-local.yml
services:
  da-logingest:
    volumes:
      - ${GOOGLE_APPLICATION_CREDENTIALS_FILE}:/run/secrets/gcp-credentials.json:ro
    environment:
      - GOOGLE_APPLICATION_CREDENTIALS=/run/secrets/gcp-credentials.json
```

## Common operations

Every command below takes the same `--env-file .env -f ... -f ...` flags as the start command (or set `COMPOSE_ENV_FILES=.env` and pass only the two `-f` flags).

```bash
docker compose ... logs -f --tail=100 da-catalog      # follow one service
docker compose ... up -d --build da-catalog           # rebuild one service (run ./gradlew :da-catalog:build -x test first)
docker compose ... down                               # stop, keep volumes
docker compose ... down -v                            # stop and delete the named volumes (PostgreSQL, Kafka, Ollama models)
docker compose ... --profile monitoring up -d         # add Prometheus and Grafana
```

## Troubleshooting

- **Services restart or never become healthy.** Wait for PostgreSQL, Keycloak and Kafka to report healthy first (`docker compose ps`). Give Docker 8 GB or more.
- **`dlap-postgresql` is unhealthy with `bad interpreter` and `^M` in its logs.** A CRLF checkout of the init scripts. `.gitattributes` pins them to LF; re-checkout the files.
- **A port is taken.** Find the process with `netstat -ano | findstr :8082` (Windows) or `lsof -i :8082`, or change the published port in the compose file.
- **`da-admin-service` exits on start.** `DLAP_ENCRYPTION_KEY` must resolve to exactly 32 bytes (`openssl rand -hex 32`).
- **A variable in `.env` has no effect.** Check that the command has `--env-file .env`, then run `docker compose ... config` and look for the value.
