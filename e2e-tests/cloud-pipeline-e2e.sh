#!/usr/bin/env bash
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
#
# ---------------------------------------------------------------------------
# DLAP cross-cloud offline E2E, parametrized over GCP / AWS / Azure.
#
# Drives the real runtime spine with zero cloud credentials by replaying
# per-cloud NORMALIZED fixtures (arrays of IngestRequestDTO) through
# da-logingest's FixtureReplaySeeder:
#
#   e2e-tests/fixtures/replay/<cloud>-events.json
#        -> FixtureReplaySeeder -> ILogIngestService.ingest -> DlapLogEvent
#        -> dlap.log.event.normalized -> DiscoveryLogEventProcessor (router)
#        -> typed lifecycle topics
#        -> da-catalog DiscoveryAssetDiscoveredListener
#        -> Asset upsert in dlap_common.dlap_assets
#
# The spine is cloud-neutral: the same code path carries GCP, AWS and Azure
# events; only the fixture URIs differ, and da-catalog derives the right
# cloud_provider + service_type per URI. PASS when the expected per-cloud
# assets appear in the catalog within the timeout.
#
# Usage:
#   ./cloud-pipeline-e2e.sh                 # all three clouds
#   ./cloud-pipeline-e2e.sh aws             # one cloud (gcp|aws|azure)
#   ./cloud-pipeline-e2e.sh all --no-build  # skip the gradle jar build
#   ./cloud-pipeline-e2e.sh --down          # tear the spine down and exit
#
# Isolation (HARN-01, helpers in harness-lib.sh): the whole run holds a host-wide lock, runs in
# its own compose project (COMPOSE_PROJECT_NAME, derived per worktree) and reads the published
# host ports from DLAP_PORT_<SERVICE> (defaults are the dev compose values).
# ---------------------------------------------------------------------------
set -uo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
DEV="$REPO/deployment/environments/dev"
E2E="$REPO/e2e-tests"
COMPOSE=(docker compose \
  -f "$DEV/docker-compose.yml" \
  -f "$DEV/docker-compose.microservices.yml" \
  -f "$E2E/docker-compose.e2e.yml")
SPINE=(postgresql kafka keycloak da-logingest da-discovery da-catalog)
GRADLE="$REPO/gradlew"
# Host-wide lock, per-worktree compose project, DLAP_PORT_* defaults and the JDK 21 choice
# (DLAP_JDK21, else the portable Windows JDK, else the JAVA_HOME the environment provides)
# come from harness-lib.sh. harness_init runs after the argument checks, so a typo never
# waits on the lock.
# shellcheck source=harness-lib.sh
source "$E2E/harness-lib.sh"

say(){ echo "[e2e] $*"; }
fail(){ echo "[e2e] FAIL: $*" >&2; exit 1; }

# The e2e compose override references ${DLAP_E2E_FIXTURES_DIR} for its bind mount, so it
# must be set for EVERY compose call, including --down. Convert the Git-Bash path to a
# Windows drive path (D:/git/sdg/...) via cygpath when present so Docker Desktop accepts it.
FIXDIR="$E2E/fixtures/replay"
command -v cygpath >/dev/null 2>&1 && FIXDIR="$(cygpath -m "$FIXDIR")"
export DLAP_E2E_FIXTURES_DIR="$FIXDIR"

if [[ "${1:-}" == "--down" ]]; then
  harness_init cloud-pipeline "$REPO"
  say "tearing down spine + volumes"; "${COMPOSE[@]}" down -v --remove-orphans; exit 0
fi

PROVIDER="${1:-all}"
[[ "$PROVIDER" == "--no-build" ]] && PROVIDER="all"   # allow bare flag
NO_BUILD=""
for a in "$@"; do [[ "$a" == "--no-build" ]] && NO_BUILD=1; done
case "$PROVIDER" in gcp|aws|azure|all) ;; *) fail "unknown provider '$PROVIDER' (want: gcp|aws|azure|all)";; esac
harness_init cloud-pipeline "$REPO"

# Per-cloud assertion predicate + expected minimum CREATE-row count. The
# fixtures use provider-derivable URIs (gs:// / projects/ -> GCP, s3:// -> AWS,
# *.blob.core.windows.net -> AZURE) so da-catalog derives cloud_provider itself.
pred_gcp="cloud_provider='GCP' AND (asset_uri LIKE 'gs://dlap-gcp-e2e%' OR asset_uri LIKE 'bq://dlap-gcp-e2e/%' OR asset_uri LIKE 'projects/dlap-gcp-e2e/%')"
pred_aws="cloud_provider='AWS' AND asset_uri LIKE 's3://dlap-aws-e2e%'"
pred_azure="cloud_provider='AZURE' AND (asset_uri LIKE 'az://dlapazuree2e%' OR asset_uri LIKE '%dlapazuree2e.blob.core.windows.net%')"
min_gcp=5; min_aws=3; min_azure=3

if [[ "$PROVIDER" == "all" ]]; then CLOUDS=(gcp aws azure); else CLOUDS=("$PROVIDER"); fi

# The FixtureReplaySeeder filters fixture files by filename prefix; blank = all.
REPLAY_PROVIDER=""; [[ "$PROVIDER" != "all" ]] && REPLAY_PROVIDER="$PROVIDER"

export DLAP_LOGINGEST_REPLAY_ENABLED=true
export DLAP_LOGINGEST_REPLAY_PROVIDER="$REPLAY_PROVIDER"
export DLAP_LOGINGEST_DEMO_SEED_ENABLED=false   # replay drives the events, not the GCP demo seeder
say "fixtures mount source: $DLAP_E2E_FIXTURES_DIR (provider filter: '${REPLAY_PROVIDER:-<all>}')"

# 1) Build the spine jars.
if [[ -z "$NO_BUILD" ]]; then
  say "building spine jars (da-catalog, da-logingest, da-discovery)…"
  "$GRADLE" -p "$REPO" :da-catalog:bootJar :da-logingest:bootJar :da-discovery:bootJar \
      --no-daemon --console=plain --no-watch-fs >/tmp/cloud_e2e_jars.log 2>&1 9>&- \
      || fail "jar build failed (see /tmp/cloud_e2e_jars.log)"
fi

# 2) Clean slate, then bring up the spine with fixture replay enabled.
say "bringing up spine: ${SPINE[*]} (fixture replay ON)…"
# Wipes this worktree's project, and evicts any stack another project left holding the
# fixed container names (the compose files pin container_name).
harness_reset_stack
"${COMPOSE[@]}" up -d --build --force-recreate "${SPINE[@]}" >/tmp/cloud_e2e_up.log 2>&1 \
    || fail "compose up failed (see /tmp/cloud_e2e_up.log)"

# 3) Wait for the keycloak realm, then the spine services.
say "waiting for keycloak realm 'dlap' (OIDC issuer)…"
for i in $(seq 1 60); do
  curl -sf "http://localhost:${DLAP_PORT_KEYCLOAK}/realms/dlap/.well-known/openid-configuration" >/dev/null 2>&1 \
      && { say "keycloak realm ready (~$((i*3))s)"; break; }
  sleep 3
  [[ $i -eq 60 ]] && say "WARN: keycloak realm not confirmed; continuing"
done
say "waiting for da-discovery to start (router)…"
# harness_wait_log, not `docker logs | grep -q`: under pipefail grep -q exits on its first match,
# docker logs fails (SIGPIPE, rc 141, on Linux; a write error, rc 1, for docker.exe on Windows),
# so the wait ran its full 6 minutes even after the start line had been logged.
if harness_wait_log dlap-da-discovery "Started DADiscoveryAgent" 72 5; then
  say "da-discovery up (~${HARNESS_WAITED}s)"
else
  say "WARN: da-discovery start not confirmed; continuing"
fi
say "waiting for da-catalog…"
for i in $(seq 1 40); do
  curl -sf "http://localhost:${DLAP_PORT_CATALOG}/api/v1/catalog/actuator/health" >/dev/null 2>&1 && { say "da-catalog up (~$((i*3))s)"; break; }
  sleep 3
done
say "settling 25s for the replay + event cascade…"; sleep 25

# 4) Assert per cloud.
pq(){ docker exec dlap-postgresql psql -U dlap -d dlap_common -tAc "$1" 2>/dev/null | tr -d '[:space:]'; }

overall=0
for cloud in "${CLOUDS[@]}"; do
  eval "pred=\$pred_$cloud"; eval "want=\$min_$cloud"
  say "polling dlap_common.dlap_assets for $cloud assets (expect >= $want)…"
  DEADLINE=$((SECONDS + 180)); count=0
  while (( SECONDS < DEADLINE )); do
    count=$(pq "SELECT count(*) FROM dlap_common.dlap_assets WHERE $pred")
    [[ "${count:-0}" =~ ^[0-9]+$ ]] && (( count >= want )) && break
    sleep 5
  done
  if [[ "${count:-0}" =~ ^[0-9]+$ ]] && (( count >= want )); then
    say "PASS [$cloud] - $count asset(s) cataloged (>= $want)"
    docker exec dlap-postgresql psql -U dlap -d dlap_common -c \
      "SELECT cloud_provider, service_type, asset_name, status FROM dlap_common.dlap_assets WHERE $pred ORDER BY asset_name LIMIT 10" 2>/dev/null
  else
    say "FAIL [$cloud] - only ${count:-0} asset(s) cataloged (< $want)"
    overall=1
  fi
done

if (( overall == 0 )); then
  say "OVERALL PASS - cross-cloud offline pipeline cataloged all requested provider(s): ${CLOUDS[*]}"
  exit 0
fi
fail "one or more clouds did not reach the expected catalog state. Inspect: '${COMPOSE[*]} logs da-logingest da-catalog'"
