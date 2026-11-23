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
# ===========================================================================
# DLAP end-to-end test - FULL Method-B synthetic lifecycle (GCP-first).
#
# Builds + boots the whole stack with the synthetic log-event seeder ON,
# then drives and ASSERTS every lifecycle stage against the real databases.
#
# The cascade under test
# -----------------------
#   da-logingest demo seeder  (synthetic GCS / BigQuery audit events)
#     -> dlap.log.event.normalized -> da-discovery DiscoveryLogEventProcessor
#     -> typed dlap.discovery.asset.* topics
#     -> da-catalog AssetLifecycleEventListener -> dlap_common.dlap_assets
#     -> dlap.catalog.asset.upserted
#          -> da-autolabel AssetUpsertedConsumer  ........ [LABEL]
#
#   then, driven by THIS harness via the policy REST API:
#     POST /policies              (3 ENABLED policies: archive / delete / label)
#     POST /{id}/evaluations      (one per policy, against a real GCP asset)
#       -> PolicyEvaluationService.evaluatePolicyForAsset
#            -> dlap_policyengine.policy_evaluations (PASS) ... [POLICY]
#            -> dlap.policies.actions  (one event per actions-map key)
#                 -> da-reporting   PolicyActionConsumer   .. [REPORT]
#                 -> da-autoarchival PolicyActionEventListener (ARCHIVE)  [ARCHIVE]
#                 -> da-autodelete   PolicyActionEventListener (DELETE)   [DELETE]
#
#   da-autocompliance control-evaluation jobs ............ [COMPLIANCE] (informational)
#   (event-driven off dlap.discovery.asset.permission-changed, not the policy bus)
#
# Gating stages (must each be non-zero to PASS): label, policy, archive, delete, report.
# Compliance is informational only and never red-fails the run.
#
# Usage:
#   ./full-lifecycle-e2e.sh             # build all jars, bring up full stack, assert
#   ./full-lifecycle-e2e.sh --no-build  # skip the gradle jar build
#   ./full-lifecycle-e2e.sh --down      # tear the stack down (-v) and exit
#
# Isolation (HARN-01, helpers in harness-lib.sh): the whole run holds a host-wide lock, runs in
# its own compose project (COMPOSE_PROJECT_NAME, derived per worktree) and reads the published
# host ports from DLAP_PORT_<SERVICE> (defaults are the dev compose values).
#
# Notes:
#   * jq is NOT present in Git Bash on the dev box - JSON is parsed with grep/sed.
#   * Reads are done with `docker exec dlap-postgresql psql` against the exact
#     per-service databases/schemas verified on the live stack.
# ===========================================================================
set -uo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
DEV="$REPO/deployment/environments/dev"
COMPOSE=(docker compose -f "$DEV/docker-compose.yml" -f "$DEV/docker-compose.microservices.yml")
GRADLE="$REPO/gradlew"

# Host-wide lock, per-worktree compose project, DLAP_PORT_* defaults and the JDK 21 choice
# (DLAP_JDK21, else the portable Windows JDK, else the JAVA_HOME the environment provides)
# come from harness-lib.sh (HARN-01).
# shellcheck source=harness-lib.sh
source "$REPO/e2e-tests/harness-lib.sh"

# ---------------------------------------------------------------------------
# Hosts, ports, auth (all on the HOST side via published ports).
# ---------------------------------------------------------------------------
# KC_BASE (keycloak) and PE_BASE (da-policyengine) are set after harness_init, from
# DLAP_PORT_KEYCLOAK and DLAP_PORT_POLICYENGINE (host side of the published ports).
KC_REALM="dlap"
KC_CLIENT_ID="dlap-backend"
KC_CLIENT_SECRET="${DLAP_BACKEND_CLIENT_SECRET:-change-me-dev-only}"
# Policy authoring needs ROLE_ADMIN / ROLE_POLICY_MANAGER; the M2M service account
# (client_credentials) only carries ROLE_SERVICE_ACCOUNT and 403s on POST /policies.
# Password-grant the seeded admin user via the confidential dlap-backend client
# (Direct Access Grants enabled in the dev realm). Dev-only relaxation.
KC_ADMIN_USER="${DLAP_ADMIN_USER:-admin}"
KC_ADMIN_PASS="${DLAP_ADMIN_PASSWORD:-Password123!}"

# Full stack: infra + every microservice. (ollama-model-pull is a one-shot
# init container started implicitly by the ollama dependency chain.)
INFRA=(postgresql kafka keycloak ollama)
SERVICES=(da-logingest da-discovery da-catalog da-policyengine da-autolabel \
          da-autoarchival da-autodelete da-autocompliance da-reporting da-admin-service)
STACK=("${INFRA[@]}" "${SERVICES[@]}")

# Downstream HTTP services whose busybox-nc healthcheck we poll via docker inspect.
HEALTH_WAIT=(dlap-da-catalog dlap-da-policyengine dlap-da-autolabel \
             dlap-da-autoarchival dlap-da-autodelete dlap-da-autocompliance \
             dlap-da-reporting dlap-da-admin-service)

# Gradle bootJar targets for the 9 services (da-discovery packages its own
# da-discovery.jar via its bootJar task; da-logingest + the rest rename to
# <name>.jar via the root build.gradle.kts convention).
JAR_TARGETS=(:da-logingest:bootJar :da-discovery:bootJar :da-catalog:bootJar \
             :da-policyengine:bootJar :da-autolabel:bootJar :da-autoarchival:bootJar \
             :da-autodelete:bootJar :da-autocompliance:bootJar :da-reporting:bootJar \
             :da-admin-service:bootJar)

# Real, deterministic GCP assets from the dlap_common seed (03-insert-test-data.sql,
# re-mounted on every `down -v`). The DELETE path (queueSoftDelete) requires a
# valid asset UUID, so we target these by id.
ASSET_GCS_BUCKET="770e8400-e29b-41d4-a716-446655440001"   # GCS_BUCKET  (Customer Data Bucket)
ASSET_BQ_TABLE="770e8400-e29b-41d4-a716-446655440003"     # BIGQUERY_TABLE (Customer Profiles Table)
ASSET_GCS_BUCKET2="770e8400-e29b-41d4-a716-446655440005"  # GCS_BUCKET  (cold backups)

say(){ echo "[e2e] $*"; }
fail(){ echo "[e2e] FAIL: $*" >&2; exit 1; }
warn(){ echo "[e2e] WARN: $*" >&2; }

# psql one-shot against a given database; trims whitespace; never aborts the run.
pq(){ # pq <db> <sql>
  docker exec dlap-postgresql psql -U dlap -d "$1" -tAc "$2" 2>/dev/null | tr -d '[:space:]'
}

# Extract the FIRST "<key>":"<value>" string field without jq. Uses grep -oE so
# the match is left-anchored to the first occurrence - a greedy `sed s/.*"key"//`
# would instead capture the LAST occurrence, which on the policy-create response
# is the nested rule id, not the top-level policy id (evaluating that id 404s).
json_str(){ # json_str <key>   (reads stdin)
  local key="$1"
  grep -oE "\"${key}\"[[:space:]]*:[[:space:]]*\"[^\"]*\"" | head -n1 \
    | sed -E "s/^\"${key}\"[[:space:]]*:[[:space:]]*\"(.*)\"$/\1/"
}

# ---------------------------------------------------------------------------
# --down: tear everything down and exit.
# ---------------------------------------------------------------------------
if [[ "${1:-}" == "--down" ]]; then
  harness_init full-lifecycle "$REPO"
  say "tearing down full stack + volumes"; "${COMPOSE[@]}" down -v; exit 0
fi
harness_init full-lifecycle "$REPO"
KC_BASE="http://localhost:${DLAP_PORT_KEYCLOAK}"
PE_BASE="http://localhost:${DLAP_PORT_POLICYENGINE}"

# ---------------------------------------------------------------------------
# 1) Build all 9 service bootJars (the Dockerfiles COPY build/libs/*.jar).
# ---------------------------------------------------------------------------
if [[ "${1:-}" != "--no-build" ]]; then
  say "building service jars (${#JAR_TARGETS[@]} bootJar targets)…"
  "$GRADLE" -p "$REPO" "${JAR_TARGETS[@]}" \
      --no-daemon --console=plain --no-watch-fs >/tmp/e2e_full_jars.log 2>&1 9>&- \
      || fail "jar build failed (see /tmp/e2e_full_jars.log)"
  say "jars built."
fi

# ---------------------------------------------------------------------------
# 2) Clean slate, then bring up the whole stack with the synthetic seeder ON.
#    down -v wipes stale Kafka offsets/topics AND every per-service DB, so any
#    row we later assert is unambiguously produced by THIS run (the dlap_common
#    seed is re-applied from init scripts, giving us the stable asset UUIDs).
# ---------------------------------------------------------------------------
say "bringing up full stack: ${STACK[*]} (demo seeder ON)…"
export DLAP_LOGINGEST_DEMO_SEED_ENABLED=true
# Wipes this worktree's project, and evicts any stack another project left holding the
# fixed container names (the compose files pin container_name).
harness_reset_stack
"${COMPOSE[@]}" up -d --build --force-recreate "${STACK[@]}" >/tmp/e2e_full_up.log 2>&1 \
    || fail "compose up failed (see /tmp/e2e_full_up.log)"

# ---------------------------------------------------------------------------
# 3) Wait for the keycloak realm FIRST so every service can resolve its OIDC
#    issuer on first boot (otherwise they crash-loop past the windows below).
# ---------------------------------------------------------------------------
say "waiting for keycloak realm '$KC_REALM' (OIDC issuer)…"
for i in $(seq 1 60); do
  curl -sf "$KC_BASE/realms/$KC_REALM/.well-known/openid-configuration" >/dev/null 2>&1 \
      && { say "keycloak realm ready (~$((i*3))s)"; break; }
  sleep 3
  [[ $i -eq 60 ]] && warn "keycloak realm not confirmed; continuing"
done

# da-discovery hosts the log-spine router (DiscoveryLogEventProcessor). Its
# management base-path isn't the default, so key off the startup log line.
say "waiting for da-discovery to start (log-spine router)…"
# harness_wait_log, not `docker logs | grep -q`: under pipefail grep -q exits on its first match,
# docker logs fails (SIGPIPE, rc 141, on Linux; a write error, rc 1, for docker.exe on Windows),
# so the wait ran its full 6 minutes even after the start line had been logged.
if harness_wait_log dlap-da-discovery "Started DADiscoveryAgent" 72 5; then
  say "da-discovery up (~${HARNESS_WAITED}s)"
else
  warn "da-discovery start not confirmed; continuing"
fi

# ---------------------------------------------------------------------------
# 3b) Poll the busybox-nc container healthchecks for the downstream HTTP
#     services (compose already defines them - we just read Health.Status).
# ---------------------------------------------------------------------------
say "waiting for downstream services to report healthy…"
HEALTH_DEADLINE=$((SECONDS + 300))
for c in "${HEALTH_WAIT[@]}"; do
  while (( SECONDS < HEALTH_DEADLINE )); do
    st=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$c" 2>/dev/null)
    if [[ "$st" == "healthy" ]]; then say "  $c: healthy"; break; fi
    if [[ "$st" == "none" ]]; then
      # No healthcheck defined - fall back to "running".
      run=$(docker inspect -f '{{.State.Running}}' "$c" 2>/dev/null)
      [[ "$run" == "true" ]] && { say "  $c: running (no healthcheck)"; break; }
    fi
    sleep 4
  done
  st=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}running{{end}}' "$c" 2>/dev/null)
  [[ "$st" == "healthy" || "$st" == "running" ]] || warn "$c never reported healthy (last=$st); continuing"
done

# da-policyengine also needs its HTTP API reachable on the host port.
say "waiting for da-policyengine API (host :8083)…"
for i in $(seq 1 40); do
  curl -sf "$PE_BASE/api/v1/policyengine/policies/actuator/health" >/dev/null 2>&1 && break
  # health may be gated; a 401/403 still means the port is serving - accept any HTTP.
  code=$(curl -s -o /dev/null -w '%{http_code}' "$PE_BASE/api/v1/policyengine/policies" 2>/dev/null)
  [[ "$code" =~ ^[0-9]+$ ]] && (( code > 0 )) && { say "da-policyengine serving (HTTP $code, ~$((i*3))s)"; break; }
  sleep 3
  [[ $i -eq 40 ]] && warn "da-policyengine API not confirmed; continuing"
done

# ---------------------------------------------------------------------------
# 4) Acquire a service token (client_credentials) from keycloak. No jq.
# ---------------------------------------------------------------------------
say "acquiring admin token (password grant, user=$KC_ADMIN_USER, client=$KC_CLIENT_ID) via internal network…"
# The token MUST be minted from inside the compose network (http://keycloak:8080)
# so its `iss` claim is http://keycloak:8080/realms/dlap - the issuer every
# service validates against. A token minted via the host port (localhost:8180)
# carries iss=localhost:8180 and is rejected by the services (401). Service-to-
# service Feign tokens are already minted internally, so they match; only this
# harness, running on the host, has to reach keycloak by its compose name.
NET=$(docker inspect -f '{{range $k,$v := .NetworkSettings.Networks}}{{$k}}{{end}}' dlap-postgresql 2>/dev/null)
[[ -n "$NET" ]] || NET="${COMPOSE_PROJECT_NAME}_dlap-network"
TOKEN_JSON=$(docker run --rm --network "$NET" curlimages/curl:8.11.1 -s \
    "http://keycloak:8080/realms/$KC_REALM/protocol/openid-connect/token" \
    -d grant_type=password \
    -d client_id="$KC_CLIENT_ID" \
    -d client_secret="$KC_CLIENT_SECRET" \
    -d username="$KC_ADMIN_USER" \
    -d password="$KC_ADMIN_PASS" 2>/dev/null)
TOKEN=$(printf '%s' "$TOKEN_JSON" | json_str access_token)
[[ -n "$TOKEN" ]] || fail "could not obtain access_token from keycloak (net=$NET, response head: ${TOKEN_JSON:0:160})"
say "token acquired (len=${#TOKEN}, minted internally on $NET)."

AUTH=(-H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json")

# ---------------------------------------------------------------------------
# 5) Seed 3 policies via POST /api/v1/policyengine/policies.
#
#    POST contract (verified against PolicyController + PolicyInputDTO):
#      name        @NotBlank
#      description  (<=1000) - plain human-readable string. (We deliberately do
#                   NOT stuff YAML DSL here: the Kafka LifecyclePolicyConsumer's
#                   YAML-in-description path is a separate mechanism; this
#                   harness drives the gates through the deterministic REST
#                   evaluation path below, which needs only rules + actions.)
#      status      ENABLED | DISABLED   (must be ENABLED so evaluatePolicyForAsset
#                   does not short-circuit as NOT_APPLICABLE).
#      rules       @NotEmpty list of {name @NotBlank, condition @NotBlank MVEL}.
#                   We use condition "true" so the rule always fires PASS on the
#                   targeted asset (deterministic, no time-window dependence).
#      actions     map<actionType, params>. On PASS, the REST evaluator publishes
#                   one dlap.policies.actions event PER KEY with actionType=<key>.
#                   da-autoarchival fires on actionType ARCHIVE/AUTO_ARCHIVE,
#                   da-autodelete on DELETE/AUTO_DELETE, da-reporting on ALL.
#                   Keys are therefore UPPERCASE ARCHIVE / DELETE; the label
#                   policy uses addLabel (reporting still records it).
#
#    Descriptions are kept single-line (no embedded newlines/quotes) so the
#    hand-rolled JSON body needs no escaping - jq is unavailable on this box.
# ---------------------------------------------------------------------------

# SEC-04 (ADR-0057): rules[] (MVEL) is retired and answers 400. Each policy is a dlap/v1
# document that matches the GCP seed assets; the REST evaluation publishes the actions map.
create_policy(){ # create_policy <name> <description> <actionsJson> <dslActionType>  -> echoes policy id
  local name="$1" desc="$2" actions="$3" atype="$4" cloud="GCP"
  local body doc
  doc="apiVersion: dlap/v1\\nkind: Policy\\nmetadata: {id: $name, name: $name}\\nspec:\\n  triggers: [{type: schedule, cron: '0 0 0 1 1 ?'}]\\n  match: {cloudProvider: $cloud}\\n  action: {type: $atype}\\n"
  body="{\"name\":\"$name\",\"description\":\"$desc\",\"status\":\"ENABLED\",\"policyDocument\":\"$doc\",\"actions\":$actions}"
  local resp code
  resp=$(curl -s -w $'\n%{http_code}' -X POST "$PE_BASE/api/v1/policyengine/policies" "${AUTH[@]}" -d "$body" 2>/dev/null)
  code=$(printf '%s' "$resp" | tail -n1)
  local json; json=$(printf '%s' "$resp" | sed '$d')
  say "  POST /policies '$name' -> HTTP $code" >&2
  if ! [[ "$code" =~ ^2[0-9][0-9]$ ]]; then
    warn "policy '$name' create not 2xx (HTTP $code): ${json:0:200}"
    return 1
  fi
  printf '%s' "$json" | json_str id
}

trigger_eval(){ # trigger_eval <policyId> <assetUuid>
  local pid="$1" asset="$2"
  [[ -n "$pid" ]] || { warn "no policy id; skipping evaluation"; return 1; }
  local body code
  body=$(printf '{"targetAssetId":"%s"}' "$asset")
  code=$(curl -s -o /dev/null -w '%{http_code}' -X POST \
      "$PE_BASE/api/v1/policyengine/$pid/evaluations" "${AUTH[@]}" -d "$body" 2>/dev/null)
  say "  POST /$pid/evaluations (asset $asset) -> HTTP $code"
  [[ "$code" =~ ^2[0-9][0-9]$ ]] || warn "evaluation for policy $pid not 2xx (HTTP $code)"
}

say "seeding 3 demo policies…"
P_ARCHIVE=$(create_policy "e2e-archive-gcs-buckets" \
    "E2E archive policy for GCP GCS buckets (REST-driven; rule=true)" \
    '{"ARCHIVE":{"tier":"COLD","reason":"e2e archive of GCS bucket"}}' archive)
P_DELETE=$(create_policy "e2e-delete-bq-table" \
    "E2E delete policy for a GCP BigQuery table (REST-driven; rule=true)" \
    '{"DELETE":{"auditNote":"e2e secure delete of BigQuery table"}}' delete)
P_LABEL=$(create_policy "e2e-label-bq-table" \
    "E2E label policy for a GCP BigQuery table (REST-driven; rule=true)" \
    '{"addLabel":{"label":"E2E_REVIEWED"}}' addLabel)

say "created policies: archive=$P_ARCHIVE delete=$P_DELETE label=$P_LABEL"
[[ -n "$P_ARCHIVE$P_DELETE$P_LABEL" ]] || fail "no policies were created - cannot drive the policy cascade"

# Record evaluation start time so the LABEL fresh-assignment check ignores seed rows.
RUN_START_TS=$(pq dlap_common "SELECT now()")

say "triggering policy evaluations against real GCP seed assets…"
trigger_eval "$P_ARCHIVE" "$ASSET_GCS_BUCKET"
trigger_eval "$P_ARCHIVE" "$ASSET_GCS_BUCKET2"
trigger_eval "$P_DELETE"  "$ASSET_BQ_TABLE"
trigger_eval "$P_LABEL"   "$ASSET_BQ_TABLE"

# ---------------------------------------------------------------------------
# 6) Settle, then assert each stage. Multi-hop stages (label off the synthetic
#    flow; policy-action fan-out) get generous deadlines via poll-with-timeout.
# ---------------------------------------------------------------------------
say "settling 30s for the policy-action fan-out + synthetic label cascade…"
sleep 30

# poll_count <db> <sql> <deadlineSecs> <label> -> echoes final count; returns 0 if >0
poll_count(){
  local db="$1" sql="$2" budget="$3" label="$4"
  local dl=$((SECONDS + budget)) c=0
  while (( SECONDS < dl )); do
    c=$(pq "$db" "$sql")
    [[ "$c" =~ ^[0-9]+$ ]] && (( c > 0 )) && break
    sleep 5
  done
  [[ "$c" =~ ^[0-9]+$ ]] || c=0
  printf '%s' "$c"
  (( c > 0 ))
}

declare -A TALLY

# --- LABEL ----------------------------------------------------------------
# Service signal: any row in dlap_autolabel.dlap_autolabel_job (db wiped by
# down -v, so any row is service-produced). The autolabel AssetUpsertedConsumer
# fires automatically off dlap.catalog.asset.upserted from the synthetic flow.
# Secondary signal: SMART_LABEL_ML assignments created AFTER this run started
# (the seed pre-loads SMART_LABEL_ML rows, so we must gate on assigned_ts).
say "[LABEL] polling for auto-labeled assignments (dlap_common, up to 240s, multi-hop)…"
# The event-driven labeler applies SMART_LABEL_ML assignments to catalog assets and
# auto-creates any label name it has not seen before (the description marker below).
# dlap_autolabel_job is a batch-flow table the event path never writes, so gate on the
# applied labels themselves - the marker is timing-independent (the seed sets no such
# description) unlike an assigned_ts > RUN_START window, since labeling runs at startup.
LABEL_APPLIED=$(poll_count dlap_common \
    "SELECT count(*) FROM dlap_common.dlap_labels WHERE description = 'Auto-created on first assignment'" 240 "label-applied")
LABEL_ASSIGNMENTS=$(pq dlap_common \
    "SELECT count(*) FROM dlap_common.dlap_asset_label_assignments WHERE source='SMART_LABEL_ML'")
say "[LABEL] auto-created labels=$LABEL_APPLIED ; total SMART_LABEL_ML assignments=$LABEL_ASSIGNMENTS"
TALLY[label]=$LABEL_APPLIED

# --- POLICY ---------------------------------------------------------------
# policy_evaluations PASS rows in dlap_policyengine (written by evaluatePolicyForAsset).
say "[POLICY] polling dlap_policyengine.policy_evaluations status=PASS (up to 240s)…"
POLICY_PASS=$(poll_count da_policyengine \
    "SELECT count(*) FROM dlap_policyengine.policy_evaluations WHERE status='PASS'" 240 "policy-pass")
POLICY_ALL=$(pq da_policyengine "SELECT count(*) FROM dlap_policyengine.policy_evaluations")
say "[POLICY] PASS evaluations=$POLICY_PASS (all statuses=$POLICY_ALL)"
TALLY[policy]=$POLICY_PASS

# --- ARCHIVE --------------------------------------------------------------
# dlap_archival_tasks written synchronously by processArchivalAction on ARCHIVE.
say "[ARCHIVE] polling dlap_autoarchival.dlap_archival_tasks (up to 120s)…"
ARCHIVE_TASKS=$(poll_count da_autoarchival \
    "SELECT count(*) FROM dlap_autoarchival.dlap_archival_tasks" 120 "archive-tasks")
ARCHIVE_ACTS=$(pq da_autoarchival "SELECT count(*) FROM dlap_autoarchival.dlap_archival_activities")
say "[ARCHIVE] archival tasks=$ARCHIVE_TASKS (activities=$ARCHIVE_ACTS)"
TALLY[archive]=$ARCHIVE_TASKS

# --- DELETE ---------------------------------------------------------------
# soft_delete_records (status SOFT_DELETED) written by queueSoftDelete on DELETE.
say "[DELETE] polling dlap_autodelete.soft_delete_records status=SOFT_DELETED (up to 120s)…"
DELETE_SOFT=$(poll_count da_autodelete \
    "SELECT count(*) FROM dlap_autodelete.soft_delete_records WHERE status='SOFT_DELETED'" 120 "delete-soft")
DELETE_TASKS=$(pq da_autodelete "SELECT count(*) FROM dlap_autodelete.dlap_deletion_tasks")
say "[DELETE] soft-delete records=$DELETE_SOFT (deletion tasks=$DELETE_TASKS)"
TALLY[delete]=$DELETE_SOFT

# --- REPORT ---------------------------------------------------------------
# dlap_reporting.policy_action_events - reporting records EVERY policy action.
say "[REPORT] polling dlap_reporting.policy_action_events (up to 120s)…"
REPORT_EVENTS=$(poll_count da_reporting \
    "SELECT count(*) FROM dlap_reporting.policy_action_events" 120 "report-events")
say "[REPORT] policy_action_events=$REPORT_EVENTS"
TALLY[report]=$REPORT_EVENTS

# --- COMPLIANCE (informational, never red-fails) --------------------------
say "[COMPLIANCE] reading dlap_autocompliance.compliance_control_evaluation_jobs (informational)…"
COMPLIANCE_JOBS=$(pq da_autocompliance \
    "SELECT count(*) FROM dlap_autocompliance.compliance_control_evaluation_jobs")
[[ "$COMPLIANCE_JOBS" =~ ^[0-9]+$ ]] || COMPLIANCE_JOBS=0
say "[COMPLIANCE] control evaluation jobs=$COMPLIANCE_JOBS (informational, not gated)"
TALLY[compliance]=$COMPLIANCE_JOBS

# ---------------------------------------------------------------------------
# 7) Final per-stage tally + gating verdict.
# ---------------------------------------------------------------------------
echo
echo "======================= DLAP full-lifecycle tally ======================="
printf '  %-12s %-8s %-6s  %s\n' "STAGE" "COUNT" "GATE" "SOURCE (db.schema.table)"
printf '  %-12s %-8s %-6s  %s\n' "-----" "-----" "----" "------------------------"
row(){ printf '  %-12s %-8s %-6s  %s\n' "$1" "$2" "$3" "$4"; }
row "LABEL"      "${TALLY[label]}"      "gate"  "dlap_common.dlap_labels (auto-created) + SMART_LABEL_ML assignments"
row "POLICY"     "${TALLY[policy]}"     "gate"  "da_policyengine.dlap_policyengine.policy_evaluations (PASS)"
row "ARCHIVE"    "${TALLY[archive]}"    "gate"  "da_autoarchival.dlap_autoarchival.dlap_archival_tasks"
row "DELETE"     "${TALLY[delete]}"     "gate"  "da_autodelete.dlap_autodelete.soft_delete_records (SOFT_DELETED)"
row "REPORT"     "${TALLY[report]}"     "gate"  "da_reporting.dlap_reporting.policy_action_events"
row "COMPLIANCE" "${TALLY[compliance]}" "info"  "da_autocompliance.dlap_autocompliance.compliance_control_evaluation_jobs"
echo "========================================================================="
echo

# Hint map: which service + why to inspect when a gate is empty.
declare -A HINT=(
  [label]="da-autolabel (consumes dlap.catalog.asset.upserted; check orchestrator.labelAsset)"
  [policy]="da-policyengine (PolicyEvaluationService.evaluatePolicyForAsset; check the POST /evaluations HTTP codes above)"
  [archive]="da-autoarchival (PolicyActionEventListener; needs actionType=ARCHIVE on dlap.policies.actions)"
  [delete]="da-autodelete (PolicyActionEventListener; needs actionType=DELETE + valid asset UUID on dlap.policies.actions)"
  [report]="da-reporting (PolicyActionConsumer on dlap.policies.actions)"
)
# Compose service name per gate (for the suggested logs command).
declare -A SVC=(
  [label]=da-autolabel [policy]=da-policyengine [archive]=da-autoarchival
  [delete]=da-autodelete [report]=da-reporting
)

GATES=(label policy archive delete report)
EMPTY=()
for g in "${GATES[@]}"; do
  v="${TALLY[$g]:-0}"
  [[ "$v" =~ ^[0-9]+$ ]] && (( v > 0 )) || EMPTY+=("$g")
done

if (( ${#EMPTY[@]} == 0 )); then
  say "PASS - all gating stages produced rows (label/policy/archive/delete/report)."
  say "       compliance (informational) = ${TALLY[compliance]}."
  exit 0
fi

echo "[e2e] FAIL: the following gating stage(s) were EMPTY:" >&2
for g in "${EMPTY[@]}"; do
  echo "[e2e]   - ${g^^}: 0 rows. Inspect: ${HINT[$g]}" >&2
  echo "[e2e]     logs: ${COMPOSE[*]} logs --tail=200 ${SVC[$g]}" >&2
done
echo "[e2e]   General: '${COMPOSE[*]} logs --tail=200 <service>' and re-check the POST HTTP codes above." >&2
exit 1
