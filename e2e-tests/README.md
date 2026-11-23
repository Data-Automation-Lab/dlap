# DLAP end-to-end tests

Credential-free, docker-compose-based E2E harnesses that drive the **real** DLAP
runtime spine and assert against the real databases. No cloud accounts needed:
synthetic provider events are replayed through the ingest seam.

## TL;DR

```bash
# Discovery -> Catalog, one cloud or all three:
bash e2e-tests/cloud-pipeline-e2e.sh all          # gcp | aws | azure | all

# FULL lifecycle (label/policy/archive/delete/report), per cloud:
bash e2e-tests/cloud-lifecycle-e2e.sh aws         # gcp | aws | azure

# Tear the stack down (stops containers + wipes volumes):
bash e2e-tests/cloud-pipeline-e2e.sh --down
```

Every run holds a host-wide lock and uses its own compose project, so runs from different
worktrees queue instead of destroying each other's stacks (see **Isolation** below).

Requires **Docker Desktop** and **JDK 21** (the build needs Gradle 8.7, which will
not launch under the box-default JDK 24). Point `DLAP_JDK21` at your JDK 21 home if
it differs from the default in the scripts. Add `--no-build` to reuse already-built
service jars/images.

## The harnesses

| Script | Scope | Driver | Asserts |
|---|---|---|---|
| `gcp-pipeline-e2e.sh` | GCP discovery→catalog | `DemoEventSeeder` (GCP synthetic) | `dlp_assets` GCP rows |
| `cloud-pipeline-e2e.sh <cloud\|all>` | discovery→catalog, **any cloud** | `FixtureReplaySeeder` | `dlp_assets` per-cloud rows |
| `full-lifecycle-e2e.sh` | full lifecycle, **GCP seed assets** | `DemoEventSeeder` + REST policies | label/policy/archive/delete/report (kept for reference: it does not approve, and the evaluation endpoint now holds archive and delete for approval, so use `cloud-lifecycle-e2e.sh gcp`) |
| `cloud-lifecycle-e2e.sh <cloud>` | full lifecycle, **any cloud** | `FixtureReplaySeeder` + REST policies, then the seeded `cdo` and `director` approve the held archive and delete through the approval API (`DLAP_APPROVER_1_USER`, `DLAP_APPROVER_2_USER`, `DLAP_APPROVER_PASSWORD`) | label/policy/held/approval/archive/delete/report |
| `asset-uri-rekey-e2e.sh` | asset_uri re-key (ID-03) on a **copy** of the pipeline database | `cloud-pipeline-e2e.sh all`, then the `da-catalog` image in re-key mode | dry run changes nothing; apply merges into the oldest row and moves labels, lineage and usage; a second apply is a no-op; revert restores the copy; the original database never changes |

The `cloud-*` pair are the provider-parametrized versions; the `gcp-*` /
`full-lifecycle-*` pair are the older GCP-specific originals kept for reference.

## How it works (why no cloud creds are needed)

The spine is **cloud-neutral** from `ILogIngestService.ingest(IngestRequestDTO)`
onward, so the harnesses drive that one seam per cloud instead of per-cloud raw-log
parsers (ADR-0039):

```
fixtures/replay/<cloud>-events.json   (arrays of IngestRequestDTO)
  → da-logingest FixtureReplaySeeder (in-process; no auth, no creds)
  → dlap.log.event.normalized → da-discovery DiscoveryLogEventProcessor (router)
  → typed dlap.discovery.asset.* topics
  → da-catalog DiscoveryAssetDiscoveredListener → dlp_assets      [DISCOVER]
  → dlap.catalog.asset.upserted → da-autolabel                   [LABEL]
then, driven via the policy REST API against the cloud's assets:
  POST /policies (archive/delete/label, ENABLED, rule=true)
  POST /{id}/evaluations
    → dlap_policyengine.policy_evaluations (PASS)                [POLICY]
    → dlap.policies.actions
         → da-autoarchival dlap_archival_tasks                   [ARCHIVE]
         → da-autodelete   soft_delete_records (SOFT_DELETED)    [DELETE]
         → da-reporting    policy_action_events                  [REPORT]
```

`da-catalog` derives `cloud_provider` + `service_type` from each event's URI, so
fixtures only need documentation-shaped URIs (`s3://…`, `gs://…`,
`https://<acct>.blob.core.windows.net/…`). The lifecycle **gates assert the control
plane FIRED** for the cloud's assets; cloud-side archival/deletion success is
governed separately (mock-mode / dry-run).

## Fixtures

- `fixtures/replay/<cloud>-events.json`: normalized `IngestRequestDTO` arrays the
  seeder replays. Provider-prefixed so `dlap.logingest.replay.provider` can select
  one cloud.
- `fixtures/{aws,gcp,azure}/`: the documentation-accurate **native** audit-log
  fixtures (AWS CloudTrail, GCP Cloud Audit Log, Azure Activity Log) the normalized
  events are modeled on, plus per-cloud READMEs.

## Configuration seams

`FixtureReplaySeeder` (da-logingest, `@Profile{demo,dev}`, off by default):
`dlap.logingest.replay.enabled` / `.dir` / `.provider`. The test-only
`docker-compose.e2e.yml` override mounts `fixtures/replay` read-only into
da-logingest and enables replay; it also turns on GCS/Azure archival mock-mode so a
credential-free archive action completes. The harness exports
`DLAP_E2E_FIXTURES_DIR` (a `D:/…` Windows drive path via `cygpath -m` for Docker
Desktop) - it must be set for every compose call, including `--down`.

## Auth model (important)

Policy authoring (`POST /policies`) requires `ROLE_ADMIN` / `ROLE_POLICY_MANAGER`.
The M2M service account (`client_credentials`, client `dlap-backend`) only carries
`ROLE_SERVICE_ACCOUNT` since the M2M hardening, so it **403s** on policy creation.
The lifecycle harnesses therefore **password-grant the seeded `admin` user**
(`Password123!`, `ROLE_ADMIN`) via the confidential `dlap-backend` client (Direct
Access Grants is enabled on it in the dev realm). This keeps every service account
least-privilege - admin ops are done by an admin identity. The token is minted from
**inside** the compose network (`http://keycloak:8080`) so its `iss` matches what
the services validate. The active realm is
`deployment/platform/keycloak/keycloak-import/dlap-realm.json`.

Override the credentials with `DLAP_ADMIN_USER` / `DLAP_ADMIN_PASSWORD` and
`DLAP_BACKEND_CLIENT_SECRET`.

## Isolation: one stack at a time

All harnesses share one Docker host. The compose files pin `container_name` (`dlap-*`) and
publish fixed host ports, and every run starts with `down -v`. Two runs at once (two agents,
two worktrees, a hand-run dev stack) delete each other's stacks, which gives false greens and
false reds. Each harness script therefore takes these four measures, implemented in
`harness-lib.sh` (sourced by all four scripts):

| Measure | What it does |
|---|---|
| Host-wide lock | One lock around the whole run, build included. The lock lives in one fixed absolute directory per user, `<home>/.dlap/harness-lock` (for example `/home/runner/.dlap/harness-lock` in CI, `/c/Users/<user>/.dlap/harness-lock` in Git Bash), the same for every shell whatever its `TMPDIR` (Q-577; it used to be `${TMPDIR:-/tmp}`, which differs between two Git Bash sessions, so they did not exclude each other). `<home>` is the Windows `USERPROFILE` under Git Bash, else the passwd home, else `$HOME`. Where `flock` exists (Linux, CI) the lock is `flock` on `dlap-e2e-harness.lock` there. Where it does not (Git Bash on Windows, macOS) it is a `mkdir` lock, `dlap-e2e-harness.lock.d`, that records the owner's PID. A second run waits, says who holds the lock every 30 s, and gives up after `DLAP_HARNESS_LOCK_TIMEOUT` seconds (default 3600). The lock goes with the run however it ends: `flock` releases on process death, and a `mkdir` lock whose PID is gone, or that is older than `DLAP_HARNESS_LOCK_MAX_AGE` seconds (default 14400, for PID reuse after a reboot), is reclaimed by the next run. |
| Compose project per worktree | `COMPOSE_PROJECT_NAME=dlap-e2e-<directory>-<8 hex digits of a hash of the path>`, unless you set it yourself. Volumes, networks and image tags carry the name, so a run in one worktree never wipes another worktree's data, or the dev stack's (project `dev`). |
| Ports from the environment | The host side of every published port is `DLAP_PORT_<SERVICE>`, in the compose pair and in the scripts' `curl` URLs. Defaults are the values the stack always used, so nothing changes until you set one. Set one when another program holds a port: `DLAP_PORT_KEYCLOAK=18180 bash e2e-tests/cloud-pipeline-e2e.sh gcp`. All names are listed in `.env.example` ("Host port overrides"). `DLAP_PORT_KAFKA` also moves the broker's host listener inside the container, so the advertised address stays right. |
| Clean slate | Before `up`, `harness_reset_stack` removes whatever holds this stack's fixed container names: another harness project (`dlap-e2e-*`) is taken down with its volumes, a non-harness container (for example the dev stack) loses only the colliding containers and keeps its volumes. Then it wipes this worktree's own project. |

Run any other command that needs the shared Docker host or the fixed dev ports under the same
lock with the wrapper. A harness started inside it does not wait on the lock it is already
covered by:

```bash
bash e2e-tests/with-harness-lock.sh -- powershell -NoProfile -File e2e-tests/authzen-pdp-verify.ps1
bash e2e-tests/with-harness-lock.sh --label demo -- bash e2e-tests/cloud-pipeline-e2e.sh gcp
```

What this does not do:

- It serializes; it does not allow two stacks side by side. `container_name` is still fixed, so
  the second project would collide even on different ports.
- It only protects callers that use it. A raw `docker compose up` or `down -v` bypasses the lock.
- The backend is chosen by whether `flock` is on `PATH` (override: `DLAP_HARNESS_LOCK_BACKEND=flock|mkdir`).
  Two callers on one host that pick different backends do not exclude each other, so keep one
  shell family per host.
- `--down` tears down this worktree's project only. Images stay; remove a worktree's tags with
  `docker image ls 'dlap-e2e-*'` and `docker image rm`.

Lock settings: `DLAP_HARNESS_LOCK_DIR` (default `<home>/.dlap/harness-lock`; leave it unset, two callers with different values do not exclude each other), `DLAP_HARNESS_LOCK_TIMEOUT`,
`DLAP_HARNESS_LOCK_MAX_AGE`, `DLAP_HARNESS_LOCK_BACKEND`. The logic is covered by a self-test
that needs no stack: `bash e2e-tests/test-harness-lib.sh` (add `--quick` to skip the slow lock
cases and the Docker cases). Git Bash has no `flock`, so it covers the `mkdir` backend. Cover the
`flock` backend in any Linux image that has `bash` and util-linux, for example (from the repo root;
the Docker cases skip themselves inside the container):

```bash
MSYS_NO_PATHCONV=1 docker run --rm -v "$PWD:/repo:ro" --entrypoint bash postgres:15 /repo/e2e-tests/test-harness-lib.sh
```

## Re-key rehearsal (ID-03)

`asset-uri-rekey-e2e.sh` rehearses the runbook in `docs/guides/upgrade.md` ("Re-key asset URIs")
against the real catalog image. It runs `cloud-pipeline-e2e.sh all` (skip with `--reuse-stack`),
drains the topics the way the runbook does (stop `da-logingest`, wait for zero lag in the
`da-discovery` groups, stop `da-discovery`, wait for the `da-catalog` groups, stop `da-catalog`),
and copies `dlap_common` into `dlap_common_rekey_e2e` with `pg_dump | psql`. Into the copy it adds
rows in pre-canonical forms that collide with pipeline rows: an older bare GCS bucket name (it
survives) and a newer S3 bucket ARN (the pipeline row survives), with labels, lineage and usage on
both sides, plus a lone BigQuery `p:d.t` row. Then it runs the catalog image as a one-off container
(`docker compose run --rm --no-deps da-catalog` with `DLAP_CATALOG_REKEY_COMMAND`) against the copy:

| Step | Checked |
|---|---|
| `dry-run` | exit 0, no blocker, the copy's fingerprint unchanged, the survivors and the BigQuery re-key in the report |
| `apply` | survivors keep the oldest `asset_id` and take the canonical URI, `native_asset_id` set, merged rows gone, labels deduplicated and moved, lineage edges moved without self edges, usage conserved, the map points merged rows at their survivor |
| `apply` again | exit 0, nothing re-keyed, no second run recorded, fingerprint unchanged |
| `revert` | the copy's fingerprint equals the one before the apply; the run is `REVERTED` |
| `apply` once more | the fingerprint equals the first apply's; the original `dlap_common` fingerprint never changed |

The fingerprint is an md5 over every row of the asset, label, cloud-resource, lineage, usage,
processed-event and retention-rule tables (`last_modified_ts` left out: the asset trigger sets it on
every update). The reports land in `${TMPDIR:-/tmp}/asset-uri-rekey-e2e-<step>.json` and are read
with `python3` (or `python`), which the script needs. `--keep-copy` keeps the copy database. The
writers are started again at the end.

## Troubleshooting

- **`Conflict. The container name "..." is already in use`** - a container from another
  compose project holds one of the fixed `dlap-*` names. The harnesses evict such
  containers before `up` (see Isolation). If it still occurs, the holder is not a
  compose container of this stack: `docker rm -f` it.
- **`waiting for the host-wide harness lock`** - another harness run holds the lock; the
  message names its PID, label and worktree. Wait for it, or stop that run. A lock whose
  owner died is reclaimed by the next run on its own. To clear one by hand, delete
  `~/.dlap/harness-lock/dlap-e2e-harness.lock.d` (on Windows under Git Bash, `$(cygpath -u "$USERPROFILE")/.dlap/harness-lock/`).
- **`gave up after Ns waiting for the harness lock`** - the holder ran longer than
  `DLAP_HARNESS_LOCK_TIMEOUT`. Raise it, or wait for the holder.
- **`Bind for 0.0.0.0:NNNN failed: port is already allocated`** - another program holds
  that host port. Set the matching `DLAP_PORT_<SERVICE>` (names in `.env.example`).
- **`POST /policies -> 403`**: the token lacks a policy-author role; ensure the
  harness is using the admin password grant (see Auth model), not `client_credentials`.
- **`invalid spec: :/fixtures/replay:ro`**: `DLAP_E2E_FIXTURES_DIR` was empty;
  run the harness (which sets it) rather than raw `docker compose`.
- **jq is not installed** in Git Bash on the dev box - the scripts parse JSON with
  `grep`/`sed` on purpose.

## Verified

Live-verified 2026-07-05 (fresh `down -v` stack). Discovery→catalog: GCP 5, AWS 3,
Azure 3 rows. Full lifecycle (label/policy/archive/delete/report): AWS 5/3/1/1/3,
Azure 3/3/1/1/3, GCP 5/3/1/1/3 - all gates green.
