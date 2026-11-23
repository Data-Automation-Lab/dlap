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

# Hosted demo, Lite profile

The compose overlay for the public demo: one small VM, eight JVMs, two host names, fixture data, and a nightly return to the starting state. It is an overlay on the quickstart pair, not a second stack ([ADR-0058](../../../docs/DECISIONS.md)). It is not production: [`../../PRODUCTION.md`](../../PRODUCTION.md) stays the production path.

The demo shows the lifecycle on **synthetic data**. No cloud account is connected, and the service network has no route out, so none can be.

| Status | |
| --- | --- |
| This folder (DEMO-01) | Compose overlay, nginx gateway, Caddy edge, maintenance page, SPA build, seed, golden dump, nightly reset, measurement, offline check, 121 named tests. |
| Identity (DEMO-02) | `keycloak/` holds the hardened demo realm, the patched Keycloak image and its own database, applied after this overlay by `up.sh` and the scripts. The quickstart realm, with its seeded users and published password, is not used. |
| Not here, other packages | The banner and the page allowlist in the SPA (DEMO-03). The VM, DNS, go-live, the numbers on the target host and the recording (DEMO-04, founder actions F5, F6, F13). |

## What runs

```
Internet --TCP 80, 443--> caddy ---> gateway (nginx) ---> da-catalog  da-policyengine  da-discovery   (wave 1)
                            |            |  serves the SPA            da-logingest  da-reporting  da-autolabel
                            |            |  /api/v1/*, /access/v1/    da-autoarchival  da-autodelete      (wave 2)
                            |            '------------------------->  postgresql   kafka
                            '---------> keycloak   (realm endpoints and static resources only)
```

| Service | Wave | Memory limit | JVM heap | Notes |
| --- | --- | ---: | ---: | --- |
| `postgresql` | infra | 512m | | `shared_buffers=128MB`, `max_connections=100`, `log_statement=none` |
| `keycloak` | infra | 768m | 512m | Patched image, hardened demo realm and its own `keycloak` database (DEMO-02); port on the loopback only |
| `kafka` | infra | 640m | 256m | One KRaft node, one partition per topic, 24 h retention |
| `da-catalog`, `da-policyengine` | 1 | 512m each | 224m | |
| `da-discovery` | 1 | 640m | 288m | Largest jar (cloud SDKs); built for a project id and never called |
| `da-logingest`, `da-reporting`, `da-autolabel`, `da-autoarchival`, `da-autodelete` | 2 | 512m each | 224m | Wave 2 waits for all of wave 1 to be healthy, so the replay finds the consumers running |
| `gateway` (nginx 1.27) | edge | 64m | | Static SPA, rate limits, refusals; resolves upstreams per request |
| `caddy` 2.11 | edge | 96m | | The only published ports: TCP 80 and 443 |
| `da-admin-service`, `da-autocompliance` | | 512m each | 224m | **Plus profile** (`COMPOSE_PROFILES=plus`), configured but not measured |

Left out of Lite: the `new-ui` JVM (nginx serves the build), Ollama and its 1.3 GB model (labels come from the deterministic rules provider, ADR-0052), Prometheus, Grafana, LocalStack and Unity Catalog (already behind profiles; their ports are loopback only if someone switches them on).

Every JVM runs `prod` with `ddl-auto=validate` (`da-logingest` adds `demo` for the replay seeder), a 224 MiB heap, a bounded metaspace and code cache, the C1 compiler only, and `-XX:+ExitOnOutOfMemoryError`, so the restart policy brings a service back instead of leaving it half dead. All eight drop every capability, cannot gain privileges and have a read-only root file system.

## Measured numbers

**Host of these numbers: a Windows workstation, Docker Desktop 28.3.0 (a WSL2 VM with 20 CPUs and 15.8 GiB), every container pinned to 2 of its CPUs with `measure.sh --cpuset 0-1`.** That is an emulation of a 2 vCPU host, not one, and other jobs were running on the same machine. The numbers show that the limits hold and what the stack costs. They do not show how an e2-standard-2 behaves: its CPUs are slower, so expect longer starts. DEMO-04 runs `scripts/measure.sh` on the VM and replaces this section. Until then "boots on 8 GB" rests on the hard container limits below, not on a boot on an 8 GB machine.

| | Run 1 | Run 2 | Run 3 |
| --- | --- | --- | --- |
| Load (60 s, an admin token, 7 read paths) | 10 clients, pause 0.2 s | 5 clients, pause 1 s | 10 clients, pause 0.2 s |
| Cold start until all 13 containers are healthy | 216 s | 163 s | 121 s |
| Working set of all containers, quiet | 3487 MiB | 3509 MiB | 3525 MiB |
| Requests answered | 1917: 1622 2xx, 295 rate limited (429) | 270, all 2xx | 1861: 1589 2xx, 272 rate limited (429) |
| Latency p50 / p95 | 9 ms / 37 ms | 9 ms / 35 ms | 8 ms / 32 ms |
| 5xx and timeouts | 0 | 0 | 0 |
| Nightly reset | 120 s | 119 s | not run |
| OOM kills and restarts | 0 | 0 | 0 |

- Run 1 also lost 1917 attempts on the client side: the shell could not start `curl` while 10 loops ran on a busy machine. The first version of the script wrote a placeholder for each and counted it as a 30 s timeout, so its report showed a median of 2 s; the raw data, read again, shows no failed request. `measure.sh` now records such attempts as client errors and keeps them out of the latencies. Runs 2 and 3 had none.
- The rate limit does its job: the 429s come only from the API paths, 15 requests a second per client address with a burst of 60. Ten clients with a 0.2 s pause are a stress test from one address, not a visitor.
- Five cold starts took 121 to 216 s. The spread follows the load on the shared machine.

Memory, run 2 (MiB; the peak includes the load):

| Service | Limit | Quiet | Peak | | Service | Limit | Quiet | Peak |
| --- | ---: | ---: | ---: | --- | --- | ---: | ---: | ---: |
| `da-catalog` | 512 | 315 | 329 | | `postgresql` | 512 | 86 | 88 |
| `da-policyengine` | 512 | 355 | 360 | | `kafka` | 640 | 372 | 419 |
| `da-discovery` | 640 | 428 | 441 | | `keycloak` | 768 | 356 | 369 |
| `da-logingest` | 512 | 287 | 287 | | `gateway` | 64 | 3 | 4 |
| `da-reporting` | 512 | 316 | 327 | | `caddy` | 96 | 13 | 15 |
| `da-autolabel`, `da-autoarchival`, `da-autodelete` | 512 | 323, 325, 330 | same | | **Sum of limits** | **6304** | **3509** | |

- The limits add up to 6.16 GiB, under the 6.5 GiB budget of an 8 GiB host (`verify.sh` fails above it). The limits are hard, so the containers cannot take more; the rest of the 8 GiB is the operating system and the page cache.
- No container passed 72% of its limit under load in any run. The JVM limits could come down once the VM has been measured.
- Wave 1 was healthy after 57 to 104 s, wave 2 after 121 to 216 s in total. Keycloak peaks at 200% of one CPU while it starts.
- The reset (restore eight databases, wipe Kafka, restart in two waves) took 119 to 141 s.
- Cloud endpoint names (googleapis, amazonaws, blob.core.windows.net, the metadata address) appear in the logs of the four services that could call one: 0 times in every run.

## Run it on your machine (rehearsal)

Needs Docker Compose 2.24 or later, JDK 21 and Node 22. From the repository root:

```bash
git submodule update --init new-ui
./gradlew :da-catalog:bootJar :da-policyengine:bootJar :da-discovery:bootJar :da-logingest:bootJar \
          :da-reporting:bootJar :da-autolabel:bootJar :da-autoarchival:bootJar :da-autodelete:bootJar

cd deployment/environments/demo
cp .env.demo.example .env.demo
$EDITOR .env.demo        # fill in the secrets; uncomment the "Local rehearsal" block at the end
scripts/build-spa.sh     # the SPA for those host names, into ./spa
./up.sh --check          # checks only
scripts/seed.sh          # starts the stack, replays the fixtures, takes the golden dump (first run: about 6 minutes)
```

With the rehearsal block the demo is at `http://localhost:18080` and `https://localhost:18443` (Caddy's local certificate authority), Keycloak at `https://id.localhost:18443`. The check of the plan works against the public entry point:

```bash
curl -I http://localhost:18080/api/v1/policyengine/events/test-rule      # 403
```

The quickstart files pin container names, so one demo runs per Docker host. On a machine that also runs the e2e harnesses set `DEMO_EVICT_COLLIDING=1`: the scripts then take the harness lock and evict colliding containers of other projects.

## Run it on the demo host

1. **Host.** One VM with 2 vCPU and 8 GB (GCP e2-standard-2, decision D18), Docker Engine with Compose 2.24 or later, 60 GB disk, 2 GB swap or zram. No service account on the VM (`--no-service-account --no-scopes`). Firewall: TCP 80 and 443 in; SSH only through IAP. Reject the metadata address from containers that have a route out (Caddy, the gateway and Keycloak share the edge network): `sudo iptables -I DOCKER-USER -d 169.254.169.254 -j REJECT`.
2. **DNS.** Two A records on a DLAP-owned domain: `demo.<domain>` and `id.<domain>`, both at the VM's static IP. List any domain the demo must never use (for example a production site on the same hosting account) in `DEMO_FORBIDDEN_HOSTS`.
3. **Build off the host.** Do not build with Gradle on the 8 GB VM. Build the eight jars and the SPA on a workstation or in CI (`scripts/build-spa.sh` reads the two host names from `.env.demo`), then copy the repository tree with `da-*/build/libs/*.jar` and `deployment/environments/demo/spa/` to the VM. `up.sh` builds the images there from the jars, which is a file copy.
4. **Configure.** `cp .env.demo.example .env.demo`; set both host names, `DEMO_TLS=auto` and four secrets (`openssl rand -hex 24` is enough for each). `KEYCLOAK_DB_PASSWORD` is optional: unset, the Keycloak database role uses `KEYCLOAK_ADMIN_PASSWORD`; set, `up.sh` refuses a `CHANGE_ME` or published value. Leave `DEMO_ALLOW_DEV_REALM=0`: it matters only when `keycloak/docker-compose.demo.keycloak.yml` is missing, and then `up.sh` refuses to start.
5. **Start and seed.** `./up.sh --check`, then `scripts/seed.sh --no-build`. The first start fetches the certificates, which needs the DNS records and the open ports.
6. **Schedule the reset** (below) and an alert on `nightly-reset.sh --check`.
7. **Accounts.** The founder creates per-prospect accounts in Keycloak and rotates them weekly (F13). They live in Keycloak, which the reset does not touch.

## Every night

`scripts/nightly-reset.sh` returns the demo to the state `seed.sh` froze:

1. verifies the golden dump (`SHA256SUMS`);
2. switches the maintenance page on (the file `state/flags/maintenance`, which Caddy watches);
3. stops the eight JVMs, restores every database (`pg_restore -C --clean --if-exists`), removes the Kafka broker and its volume and starts a new one;
4. starts the JVMs in two waves and waits until they are healthy;
5. checks that the gateway answers and that the catalog holds exactly the golden number of assets;
6. switches the page off and writes `state/last-reset.json`.

Replay runs once, at seeding. The reset restores, because a replay on top of a day of visitors' changes would not return to the same state. Keycloak, Caddy, the gateway and the Postgres roles are not touched. A failed step leaves the maintenance page **on** and the status `failed`: visitors see an honest page, not a broken demo.

```cron
# 03:00 local time on the demo host (adjust the path)
0 3 * * *     /opt/dlap/deployment/environments/demo/scripts/nightly-reset.sh >> /var/log/dlap-demo-reset.log 2>&1
# the daily check that the reset ran: exit 1 when the last reset failed or is older than 26 hours
17 * * * *    /opt/dlap/deployment/environments/demo/scripts/nightly-reset.sh --check || <your alert command>
```

`scripts/golden-dump.sh` takes a new golden dump of the running stack (for example after the founder curates the story). `scripts/seed.sh --fresh` deletes the demo's volumes first.

## What it refuses, and the test that proves it

The tests are `test-demo.sh` (`bash test-demo.sh`; `--no-docker` skips N and C; `--only C-05` runs one). A test can fail: V-* checks are run against 26 deliberate breakages of the files, AUD-* run `keycloak/verify-issuer.sh --static` on the real files and on four breakages of the audience check (no mapper, a boot-guard waiver, the check off, another audience), N-* and C-* start the real nginx and Caddy configuration in hardened containers.

| Promise | Where | Tests |
| --- | --- | --- |
| Only TCP 80 and 443 are published; every other port is loopback | `docker-compose.demo.yml`, `verify.sh` V03 | V-00, V-01, V-10, V-15, V-26 |
| 8 JVMs, nothing else of the stack runs; ollama, new-ui, admin and compliance are behind profiles | V01, V02 | V-00, V-09 |
| Every container has a memory limit; the limits add up to 6.1 GiB, under the 6.5 GiB budget of an 8 GiB host | V04, V05 | V-02, V-03, V-18, V-20 |
| Fixture data only, no cloud call: log sources off, replay off by default, deletes dry, archival mocked, no route out | V08, V09 | V-04, V-05, V-06, V-11, V-21, V-22 |
| Prod profile, `ddl-auto=validate`, labelling by rules | V06, V07 | V-07, V-16, V-19 |
| `test-rule` is blocked at the gateway; actuator, Swagger and OpenAPI documents are 404 on every service path | `nginx.demo.conf` | N-06, N-07, N-08, V-23 |
| Rate limits, 256k body, connection limit, request timeouts; AuthZEN has its own tight limit | `nginx.demo.conf`, `Caddyfile` | N-13, N-14, C-13 |
| No query string (an OIDC code) in the access log; the client address is the forwarded one | `nginx.demo.conf` | N-20, N-21 |
| The gateway starts with no backend and finds a restarted one again | `nginx.demo.conf` | N-15, N-22, V-24 |
| Keycloak: the realm endpoints and static resources only; never `/admin`, `/metrics`, `/health`, `/realms/master` | `Caddyfile` | C-03 to C-07, V-25 |
| One set of browser headers: HSTS, CSP naming the Keycloak origin, frame-ancestors, Referrer-Policy; Caddy has the last word | `Caddyfile`, `nginx.demo.conf` | C-08 to C-12 (answers Q-190 for the demo) |
| The maintenance page during the reset or when the gateway is down; Keycloak keeps answering | `Caddyfile`, `maintenance/` | C-14 to C-21 |
| HTTP is redirected to HTTPS; the plain-HTTP route exists only in the local check mode | `Caddyfile` | C-22, C-23, V-26 |
| The launcher refuses weak, missing or published secrets, a missing Keycloak overlay, a weak optional Keycloak database password and a stray replay switch | `up.sh` | ENV-01 to ENV-16 |
| The reset verifies its dump first and reports to monitoring | `nightly-reset.sh` | R-01 to R-08 |

The seed, the golden dump, the reset and the measurement were run end to end on the whole stack for this package (the commands and results are in the package report), but they are not tests: they need the images, the jars and ten minutes. The service network was also probed from inside: `docker exec dlap-da-catalog /busybox wget http://1.1.1.1/` answers "Network unreachable", while the gateway, which shares the edge network, gets through.

## Third-party assets of the UI

Loading the built SPA through Caddy in a browser showed what the content security policy blocks: the UI hotlinks IBM Plex from Google Fonts (`fonts.googleapis.com`) and pictures from Unsplash (`images.unsplash.com`) and Cloudinary (`res.cloudinary.com`) on the sign-in page. By default the browser refuses them, so the fonts fall back to system fonts, the sign-in page loses its pictures, and no visitor's browser calls those hosts. To allow them, set `DEMO_CSP_STYLE_EXTRA`, `DEMO_CSP_FONT_EXTRA` and `DEMO_CSP_IMG_EXTRA` in `.env.demo` (examples are in `.env.demo.example`; test C-24). That is a decision for the founder. The clean fix is in the UI: ship the fonts and pictures with the build.

In a rehearsal the browser does not trust Caddy's local certificate authority, so the Keycloak sign-in fails with a certificate error until you accept the certificate for `https://id.localhost:18443` or import the root certificate (`/data/caddy/pki/authorities/local/root.crt` in the `caddy-data` volume).

## Administration

- **Keycloak console.** Never published. `ssh -L 8180:127.0.0.1:8180 <vm>`, then `http://localhost:8180` (the port is `DLAP_PORT_KEYCLOAK`). Over IAP: `gcloud compute ssh <vm> --tunnel-through-iap -- -L 8180:127.0.0.1:8180`.
- **Gateway without TLS** (debugging): `ssh -L 8081:127.0.0.1:8081 <vm>`.
- **Logs and status.** `./up.sh ps`, `./up.sh logs -f da-catalog`. Each log is capped at 3 files of 10 MB.
- **Database.** `./up.sh exec postgresql psql -U dlap -d dlap_common`. No port is published.
- **Stop, keep the data:** `./up.sh down`. **Delete the data:** `./up.sh down -v`.

## Hand-off to the other demo packages

- **DEMO-02** (merged) adds `keycloak/docker-compose.demo.keycloak.yml`. `up.sh` and the scripts apply it after this overlay because the file exists, so the launcher does not ask for `DEMO_ALLOW_DEV_REALM`. It keeps the limits here and replaces the image, command and realm, gives Keycloak its own database so that accounts survive a container recreation, sets the services' issuer and key-set URIs through environment variables and keeps the loopback port. Paths in an overlay are relative to `deployment/environments/dev`.
- **ID-04** (Q-913) switches JWT audience validation on in every JVM (`DLAP_SECURITY_JWT_VALIDATE_AUDIENCE=true`, expected audience `dlap-api`) and adds the `dlap-api-audience` mapper to `dlap-ui` and `dlap-backend` in `keycloak/dlap-demo-realm.json`, as the pilot realm does. The demo runs under `prod` with full JWT validation and no `DLAP_SECURITY_DEV_SECRET_GUARD_ACCEPTED` waiver. Upgrade: Keycloak imports a realm file once, so a demo whose Keycloak database already holds the realm keeps the old clients and every service answers 401 until the mapper is there. Add the mapper (Clients, dlap-ui and dlap-backend, Client scopes, dedicated scope, Add mapper, Audience, included custom audience `dlap-api`, add to access token) or delete the realm `dlap` in the admin console and restart Keycloak so it imports again (accounts created by hand are lost). `keycloak/verify-issuer.sh` S-13 and I-08 check the file and a running stack.
- **DEMO-03** builds the banner into the SPA with `VITE_DEMO_MODE`; `scripts/build-spa.sh` already sets it.
- **DEMO-04** runs `scripts/measure.sh` on the target VM (without `--cpuset`) and replaces the numbers above.

## Files

| Path | What |
| --- | --- |
| `docker-compose.demo.yml` | The overlay |
| `gateway/nginx.demo.conf` | The gateway |
| `caddy/Caddyfile` | The edge |
| `maintenance/index.html` | The page shown during the reset |
| `up.sh` | Launcher with checks; `--check`, `--print-args` |
| `verify.sh` | Offline check of the merged model (16 checks) |
| `test-demo.sh` | The named tests |
| `scripts/lib.sh` | Shared helpers |
| `scripts/build-spa.sh` | The SPA for the public host names |
| `scripts/seed.sh`, `scripts/golden-dump.sh`, `scripts/nightly-reset.sh` | Seed, golden dump, reset |
| `scripts/measure.sh` | Memory, start time, load, reset time |
| `.env.demo.example` | Every setting, with the rehearsal block |
| `spa/`, `state/`, `.env.demo` | Local only, ignored by git |
