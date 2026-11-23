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

# Read-only pilot

The compose overlay for the first read-only pilot: one VM, in the customer's own cloud account, running the quickstart stack under the `prod` profile with sane limits, one HTTPS entry point and a production Keycloak. It is model M3 in [`docs/architecture/deployment-models.md`](../../../docs/architecture/deployment-models.md) (section 5.4), the proposed default for decision D10. The founder has not confirmed D10 yet, so treat this folder as the base that the remaining pilot packages build on.

This is an overlay on the quickstart pair, not a second stack (ADR-0037). `up.sh` applies every `docker-compose.pilot.*.yml` of this folder, in sorted order, after `deployment/environments/dev/docker-compose.yml` and `docker-compose.microservices.yml`: `backup`, `base`, `identity-map`, `keycloak`, `tls`.

## What the base overlay does

| Area | Setting |
| --- | --- |
| Profile | Every Spring service runs under `prod` with `SPRING_JPA_HIBERNATE_DDL_AUTO=validate`. [`../../PRODUCTION.md`](../../PRODUCTION.md) section 8 lists the environment each service needs. |
| Memory | Every container has a memory limit, and every JVM an explicit `-Xmx` and `-XX:+ExitOnOutOfMemoryError`, so an out-of-memory service restarts instead of limping. |
| Ports | Every published port is bound to `127.0.0.1`. The one exception you can choose is the TLS entry point, which `PILOT_TLS_BIND_ADDRESS` can open (see "Reach the pilot"). |
| Archive and delete | `da-autoarchival` and `da-autodelete` are behind the compose profile `actions` and do not start. If someone enables the profile, deletes are dry runs and archival is mocked. |
| Labelling | `da-autolabel` uses the deterministic `rules` provider (ADR-0052). Ollama is behind the profile `llm` and does not start. |
| Database | The demo seed `03-insert-test-data.sql` is replaced by an empty script. PostgreSQL no longer logs every statement. |
| Logs | Every service logs to a rotated JSON file (20 MB, 5 files), so a long run cannot fill the disk. |
| Gateway | `gateway/nginx.pilot.conf` resolves the two optional services on demand, so the gateway starts without them and answers a clean 404 for their routes. |

## What it does not do

Each of these has its own package. Until they land, the pilot is not a finished deployment.

| Missing | Package | Consequence now |
| --- | --- | --- |
| Shadow mode and report labelling | PILOT-01 | Nothing marks reports as pilot output. |
| Unique database passwords | open question Q-176 | The per-service PostgreSQL users keep the fixed passwords that the init script creates. The database is not published beyond loopback. |

Done by their own packages and described below: TLS on every hop and the single entry point (SEC-06, `docker-compose.pilot.tls.yml`, [`tls/README.md`](tls/README.md)), the production Keycloak realm (IAM-02, `docker-compose.pilot.keycloak.yml`), backups (DR-01, `docker-compose.pilot.backup.yml`).

## Requirements

- A Linux VM with 4 vCPU and 16 GB of memory. The limits add up to about 10 GB with the default profiles. The sizes are conservative starting points that fit that VM; they are not measured yet, so check `docker stats` on the real VM and adjust the file.
- Docker Engine with Docker Compose 2.24 or later (`up.sh` checks; the `!override` tag needs it).
- JDK 21 to build the jars, and the `new-ui` submodule: `git submodule update --init new-ui`.
- The VM's own service identity with the Reader role (IAM-03) for the log sources. No key file is stored.
- Do not run it next to the quickstart stack. The container names are fixed in the quickstart files.

## Start

Run from the repository root.

```bash
./gradlew build -x test                                   # the Dockerfiles copy build/libs/<service>.jar
cp deployment/environments/pilot/.env.pilot.example deployment/environments/pilot/.env.pilot
$EDITOR deployment/environments/pilot/.env.pilot          # every CHANGE_ME value, and the three addresses
deployment/environments/pilot/tls/gen-certs.sh --public-host <name the browsers use>   # once
deployment/environments/pilot/up.sh --check               # checks only, starts nothing
deployment/environments/pilot/up.sh                       # up -d --build
```

`up.sh` stops with a list of problems when:

- a secret is missing, still says `CHANGE_ME`, or is a default that the quickstart publishes (this includes `KEYCLOAK_DB_PASSWORD`);
- a jar or the UI submodule is missing;
- the key material of `gen-certs.sh` is missing (`PILOT_TLS_DIR`), `PILOT_PUBLIC_ORIGIN` is not `https://`, or `DLAP_PILOT_ID_URL` and `DLAP_PILOT_APP_URL` are not that same origin: Keycloak writes the public issuer into every token and the services check it, so the three must agree;
- `PILOT_IDENTITY_MAP_FILE` is set and is not a readable file that starts with the seed header;
- the production Keycloak overlay file was removed and the quickstart realm would be imported without `PILOT_ALLOW_DEV_REALM=1`. The example file no longer carries that variable. It exists only for a lab VM that removed the overlay on purpose, and must never be used with customer data.

Anything after the options goes to `docker compose` unchanged:

```bash
deployment/environments/pilot/up.sh ps
deployment/environments/pilot/up.sh logs -f da-catalog
deployment/environments/pilot/up.sh down            # keeps the volumes; add -v to delete the data
```

## Reach the pilot

The only entry point is the gateway's HTTPS listener. Browsers must use exactly `PILOT_PUBLIC_ORIGIN`, because Keycloak fixes every token's issuer to it. By default the listener is bound to `127.0.0.1`, so you reach it through an SSH tunnel. With the example file's values (`PILOT_PUBLIC_ORIGIN=https://localhost:8443`, `PILOT_TLS_PORT=8443`, a certificate made with `--public-host localhost`):

```bash
ssh -L 8443:127.0.0.1:8443 <vm>        # then open https://localhost:8443
```

Your browser must trust the pilot CA (`tls/generated/certs/ca.crt`), or you click through the warning once. For a pilot reached by name, make the certificate with `--public-host <name>`, put `https://<name>` in all three addresses, set `PILOT_TLS_PORT=443`, and set `PILOT_TLS_BIND_ADDRESS` to the VM's private address (or `0.0.0.0`). `verify.sh` accepts exactly that one exception: the gateway's target port 8443 may be bound beyond loopback; every other published port must stay on `127.0.0.1`. The Keycloak admin console stays on loopback: `ssh -L 8180:127.0.0.1:8180 <vm>`, then `https://localhost:8180/admin`.

The realm is imported with `sslRequired` `all` under the TLS overlay (Keycloak refuses plain HTTP also from private addresses) and with `external` when the TLS overlay file is removed. The import happens once, into an empty Keycloak database. A realm that exists already keeps its setting: change it in the admin console (Realm settings, General, Require SSL) or import into a new database. `tls/smoke-tls.sh` reports the live value.

## Keycloak database password

`KEYCLOAK_DB_PASSWORD` is the password of the PostgreSQL role `keycloak`, which owns the database with Keycloak's accounts, one-time-code devices and events. It is its own secret and `up.sh` requires it (Q-541). Before that the overlay used `KEYCLOAK_ADMIN_PASSWORD` for the role, so one secret served both the temporary bootstrap administrator and the long-lived database role. The PostgreSQL init script creates the role with this password only when the volume is new. For a pilot that already runs, either keep the role as it is and set `KEYCLOAK_DB_PASSWORD` to the value `KEYCLOAK_ADMIN_PASSWORD` had when the volume was created, or change the role first:

```bash
deployment/environments/pilot/up.sh exec -T postgresql psql -U dlap -d dlap -c "ALTER ROLE keycloak PASSWORD '<new value>'"
# put the same value in .env.pilot, then recreate Keycloak:
deployment/environments/pilot/up.sh up -d keycloak
```

## Identity map

`docker-compose.pilot.identity-map.yml` mounts a CSV file read-only into `da-reporting` and sets `DLAP_REPORTING_IDENTITY_MAP_SEED_FILE`, so the principal identity map of the drift report (enforcement contract, section 6.1) is applied at every start. Without a map, every cloud principal is unmapped, and a governed read whose PDP subject differs from the log principal becomes `UNENFORCED_READ`. The default file (`identity-map/identity-map.csv`) has a header and no entries. To map principals:

```bash
cp deployment/examples/identity-map/identity-map.example.csv /etc/dlap-pilot/identity-map.csv
$EDITOR /etc/dlap-pilot/identity-map.csv                  # your principals; the example is made up
echo 'PILOT_IDENTITY_MAP_FILE=/etc/dlap-pilot/identity-map.csv' >> deployment/environments/pilot/.env.pilot
deployment/environments/pilot/up.sh --check
deployment/environments/pilot/up.sh restart da-reporting  # after every edit
```

The file owns every key it names. Keys it no longer names are removed at the next start, and rows added with SQL (`MANUAL`) are never touched. A missing or invalid file stops `da-reporting` at start, and `up.sh` checks the path and the header first. The dev compose does not mount a map (it is not part of this folder).

## Database upgrade

`up.sh up` (and `up.sh` with no arguments, which means `up -d --build`) brings the shared `dlap_common` schema forward before any service starts (ADR-0056, Q-142):

1. It starts PostgreSQL alone and waits until it accepts TCP connections, for at most `PILOT_DB_WAIT_SECONDS` seconds (a shell variable, default 180). On a new volume that is after the init scripts have finished. The container's own healthcheck passes earlier, while they still run, so it is not used.
2. It runs [`run-upgrades.sh`](../../platform/database/upgrades/run-upgrades.sh) through `docker exec` into the container, so the host needs no `psql`. The runner applies every dated delta the database has not had and records each one, with its checksum, in `dlap_common.schema_upgrades`. A second run applies nothing.
3. Only then does compose start the services.

If the runner fails (exit 1) or refuses (exit 2: a delta changed after it was applied, or a pending delta sorts before the newest applied one), `up.sh` exits with that status and starts no service. Read the runner's message and [`docs/guides/upgrade.md`](../../../docs/guides/upgrade.md), "When the runner refuses". `up.sh ps`, `logs`, `down` and every other command never touch the database. The per-service schemas are not part of this step: each service runs its own Flyway migrations when it starts.

## Optional parts

Set `COMPOSE_PROFILES` in `.env.pilot` (comma separated):

| Profile | Adds |
| --- | --- |
| `actions` | `da-autoarchival` and `da-autodelete`. Deletes stay dry runs and archival stays mocked, whatever the environment file says. A real cloud call needs a deliberate edit of the overlay. |
| `llm` | Ollama and its model pull, for an LLM labelling provider instead of `rules`. |
| `monitoring` | Prometheus, Alertmanager, Grafana and the two exporters (`GRAFANA_ADMIN_PASSWORD` is then required). Prometheus runs [`prometheus.tls.yml`](../../platform/prometheus/prometheus.tls.yml): it scrapes every service over HTTPS on the management port 9091 and verifies the service certificate against the pilot CA. Alertmanager, Grafana and the exporters stay plain HTTP on the container network (internal hops that nothing publishes beyond loopback). The management port serves `/actuator/health` and `/actuator/prometheus` without a token, over TLS, to the compose network only; the application port serves no actuator endpoint. Check with `deployment/platform/prometheus/verify-targets.sh`. |

## Check the overlay

`verify.sh` builds the merged model with `docker compose config`, starts nothing and needs no secrets. It checks that every port is on `127.0.0.1` (except the gateway's TLS entry point, which `PILOT_TLS_BIND_ADDRESS` may open), every service has a memory limit and every JVM an `-Xmx`, the Spring services run under `prod` with `validate`, deletes are a dry run, archive, delete and Ollama are opt-in, and the demo seed is replaced; it also checks that every Spring service validates the JWT issuer and audience with and without the TLS overlay (Q-919).

```bash
deployment/environments/pilot/verify.sh
```

`up-test.sh` tests `up.sh`: the checks that depend on an overlay (Keycloak password, TLS key material and addresses, identity map file, the closing message) and the database upgrade step. Its first part needs only bash: it copies `up.sh` into a scratch tree, puts a stand-in `docker` first on `PATH` and checks the order (PostgreSQL, readiness probe, runner, services), the exit status of a runner failure and of a refusal, and that `ps`, `logs`, `down` and `--check` never upgrade. Its second part needs Docker and the `postgres:15.1` image: it starts one throwaway container from the repository's init scripts, runs `up.sh` against it while the init scripts are still executing, and checks that the real runner applies and records every delta, applies none the second time, and stops the start with exit 2 for an edited or out-of-order delta. No compose project is created. `up-test.sh --quick` runs the first part only.

```bash
deployment/environments/pilot/up-test.sh
```

The prod profile itself is covered by `ProdProfileContextTest` in each of the eleven services (`./gradlew test --tests "*ProdProfileContextTest*"`, Docker required). [`../../PRODUCTION.md`](../../PRODUCTION.md) section 8 lists the environment they set.

## Adding an overlay

Name it `docker-compose.pilot.<name>.yml` and drop it in this folder; `up.sh` picks it up. Files are applied in byte order, so `backup` comes before `base`, and `identity-map`, `keycloak`, `shadow` and `tls` come after it. An overlay that replaces a published port list needs the `!override` tag, because compose appends port lists by default. Paths inside an overlay are relative to `deployment/environments/dev`, the folder of the first compose file.

## References

- [`../../PRODUCTION.md`](../../PRODUCTION.md) for the hardening guide and the environment tables.
- [`docs/architecture/deployment-models.md`](../../../docs/architecture/deployment-models.md) for where the pilot sits among the deployment models.
- ADR-0037 (one quickstart, production documented), ADR-0049 (single-tenant deployments), ADR-0052 (rules labelling).
