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
# DLAP end-to-end test - GCP synthetic-event pipeline (Method B, GCP first).
#
# Drives the real runtime spine with zero cloud credentials:
#
#   da-logingest demo seeder  (synthetic GCS/BigQuery audit events)
#        -> ILogIngestService.ingest -> DlapLogEvent
#        -> dlap.log.event.normalized -> DiscoveryLogEventProcessor
#        -> typed lifecycle topics
#        -> da-catalog AssetLifecycleEventListener
#        -> Asset upsert in dlap_common.dlap_assets
#
# PASS when GCP assets appear in the catalog within the timeout.
#
# Usage:
#   ./gcp-pipeline-e2e.sh            # build jars, bring up spine, assert
#   ./gcp-pipeline-e2e.sh --no-build # skip the gradle jar build
#   ./gcp-pipeline-e2e.sh --down     # tear the spine down and exit
#
# Isolation (HARN-01, helpers in harness-lib.sh): the whole run holds a host-wide lock, runs in
# its own compose project (COMPOSE_PROJECT_NAME, derived per worktree) and reads the published
# host ports from DLAP_PORT_<SERVICE> (defaults are the dev compose values).
# ---------------------------------------------------------------------------
set -uo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
DEV="$REPO/deployment/environments/dev"
COMPOSE=(docker compose -f "$DEV/docker-compose.yml" -f "$DEV/docker-compose.microservices.yml")
# da-discovery hosts the log-spine router (DiscoveryLogEventProcessor) that
# fans dlap.log.event.normalized out to the typed dlap.discovery.asset.*
# topics that da-catalog consumes - so it is part of the spine.
SPINE=(postgresql kafka keycloak da-logingest da-discovery da-catalog)
GRADLE="$REPO/gradlew"
# Host-wide lock, per-worktree compose project, DLAP_PORT_* defaults and the JDK 21 choice
# (DLAP_JDK21, else the portable Windows JDK, else the JAVA_HOME the environment provides)
# come from harness-lib.sh (HARN-01).
# shellcheck source=harness-lib.sh
source "$REPO/e2e-tests/harness-lib.sh"

say(){ echo "[e2e] $*"; }
fail(){ echo "[e2e] FAIL: $*" >&2; exit 1; }

if [[ "${1:-}" == "--down" ]]; then
  harness_init gcp-pipeline "$REPO"
  say "tearing down spine + volumes"; "${COMPOSE[@]}" down -v; exit 0
fi
harness_init gcp-pipeline "$REPO"

# 1) Build the spine jars (the Dockerfiles package build/libs/*.jar).
if [[ "${1:-}" != "--no-build" ]]; then
  say "building spine jars (da-catalog, da-logingest)…"
  "$GRADLE" -p "$REPO" :da-catalog:bootJar :da-logingest:bootJar :da-discovery:bootJar \
      --no-daemon --console=plain --no-watch-fs >/tmp/e2e_jars.log 2>&1 9>&- \
      || fail "jar build failed (see /tmp/e2e_jars.log)"
fi

# 2) Clean slate, then bring up the spine with the synthetic seeder enabled.
#    A clean start (down -v) avoids stale Kafka offsets/topics from prior runs;
#    --force-recreate avoids reusing a container built from an older image.
say "bringing up spine: ${SPINE[*]} (demo seeder ON)…"
export DLAP_LOGINGEST_DEMO_SEED_ENABLED=true
# Wipes this worktree's project, and evicts any stack another project left holding the
# fixed container names (the compose files pin container_name).
harness_reset_stack
"${COMPOSE[@]}" up -d --build --force-recreate "${SPINE[@]}" >/tmp/e2e_up.log 2>&1 \
    || fail "compose up failed (see /tmp/e2e_up.log)"

# 3) Wait for the keycloak realm FIRST so da-discovery and da-catalog can resolve
#    their OIDC issuer on first boot. Without this they crash-loop until the realm
#    import lands, which can outlast the readiness windows below (flaky false WARN).
say "waiting for keycloak realm 'dlap' (OIDC issuer)…"
for i in $(seq 1 60); do
  curl -sf "http://localhost:${DLAP_PORT_KEYCLOAK}/realms/dlap/.well-known/openid-configuration" >/dev/null 2>&1 \
      && { say "keycloak realm ready (~$((i*3))s)"; break; }
  sleep 3
  [[ $i -eq 60 ]] && say "WARN: keycloak realm not confirmed; continuing"
done

# da-discovery hosts the log-spine router (DiscoveryLogEventProcessor). Its
# management base-path isn't the default, so key off the startup log line.
say "waiting for da-discovery to start (router)…"
# harness_wait_log, not `docker logs | grep -q`: under pipefail grep -q exits on its first match,
# docker logs fails (SIGPIPE, rc 141, on Linux; a write error, rc 1, for docker.exe on Windows),
# so the wait ran its full 6 minutes even after the start line had been logged.
if harness_wait_log dlap-da-discovery "Started DADiscoveryAgent" 72 5; then
  say "da-discovery up (~${HARNESS_WAITED}s)"
else
  say "WARN: da-discovery start not confirmed; continuing"
fi

# da-catalog serves under context path /api/v1/catalog.
say "waiting for da-catalog…"
for i in $(seq 1 40); do
  curl -sf "http://localhost:${DLAP_PORT_CATALOG}/api/v1/catalog/actuator/health" >/dev/null 2>&1 && { say "da-catalog up (~$((i*3))s)"; break; }
  sleep 3
done
say "settling 20s for the event cascade…"; sleep 20

# 4) Poll for assets produced specifically by the synthetic flow.
#    The 03 seed pre-populates 5 assets (dlap-demo-bucket, …); the demo
#    seeder uses distinct URIs (dlap-demo-project, storage.googleapis.com
#    buckets), so we match those to avoid a false positive on seed data.
FLOW_PRED="cloud_provider='GCP' AND (asset_uri LIKE '%dlap-demo-project%' OR asset_uri LIKE '%storage.googleapis.com%')"
say "polling dlap_common.dlap_assets for synthetic-flow GCP assets…"
DEADLINE=$((SECONDS + 240))
COUNT=0
while (( SECONDS < DEADLINE )); do
  COUNT=$(docker exec dlap-postgresql psql -U dlap -d dlap_common -tAc \
      "SELECT count(*) FROM dlap_common.dlap_assets WHERE $FLOW_PRED" 2>/dev/null | tr -d '[:space:]')
  [[ "${COUNT:-0}" =~ ^[0-9]+$ ]] && (( COUNT > 0 )) && break
  sleep 5
done

if [[ "${COUNT:-0}" =~ ^[0-9]+$ ]] && (( COUNT > 0 )); then
  say "PASS - $COUNT synthetic GCP asset(s) flowed da-logingest -> catalog end-to-end"
  docker exec dlap-postgresql psql -U dlap -d dlap_common -c \
      "SELECT asset_name, service_type, status FROM dlap_common.dlap_assets WHERE $FLOW_PRED LIMIT 8" 2>/dev/null
  exit 0
fi
fail "no synthetic-flow GCP assets cataloged within timeout (count=$COUNT). Inspect: '${COMPOSE[*]} logs da-logingest da-catalog'"
