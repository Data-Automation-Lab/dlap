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
# DLAP FULL-lifecycle E2E, parametrized per cloud (GCP / AWS / Azure).
#
# The provider-neutral sibling of full-lifecycle-e2e.sh (which is GCP-seed
# specific). Instead of the GCP demo seeder + hard-coded seed UUIDs, this drives
# the target cloud's assets into the catalog via FixtureReplaySeeder, resolves
# their UUIDs dynamically, then runs and ASSERTS the policy-driven lifecycle:
#
#   fixtures/replay/<cloud>-events.json
#     -> FixtureReplaySeeder -> spine -> dlap_common.dlap_assets      [DISCOVER]
#     -> dlap.catalog.asset.upserted -> da-autolabel                 [LABEL]
#   then, driven here via the policy REST API against the cloud's assets:
#     POST /policies (archive/delete/label, ENABLED, rule=true)
#     POST /{id}/evaluations
#       -> dlap_policyengine.policy_evaluations (PASS)               [POLICY]
#       -> ApprovalGate: ARCHIVE and DELETE wait as approval requests [HELD]
#     the admin who triggered the evaluations decides one of them: refused   [SELF_APPROVAL]
#       (403, reason self-approval, the request still PENDING; Q-592, ADR-0098)
#     two seeded approvers (cdo, director) decide them through
#     POST /approvals/{id}/decisions                                  [APPROVAL]
#       -> dlap.policies.actions (released, with the approval block)
#            -> da-autoarchival dlap_archival_tasks                  [ARCHIVE]
#            -> da-autodelete   soft_delete_records (SOFT_DELETED)   [DELETE]
#            -> da-reporting    policy_action_events                 [REPORT]
#
# Gates (each must be non-zero to PASS): label, policy, held, self_approval, approval, archive,
# delete, report. The gates assert the control plane FIRED for the cloud's assets - archival /
# deletion cloud-side success is governed separately (mock-mode / dry-run).
#
# Retire phase, aws only (RET-08, Q-714, decision D38; skip with DLAP_E2E_AWS_RETIRE=0): after
# the gates above, LocalStack S3 joins the stack (docker-compose.lifecycle-aws.yml) and
# da-autodelete is recreated with dry run off and its S3 applier on LocalStack through the
# TEST-ONLY endpoint dlap.autodelete.test-endpoint.s3 (Q-713), a rule maximum age of
# DLAP_E2E_RULE_MAX_AGE_MINUTES (default 4) and fast sweeps; da-logingest is recreated with the
# S3_TRAIL_FILES transport on a LocalStack bucket (fixtures/aws/lifecycle-log-sources.yml).
# Then, against the harness's own SOFT_DELETED record and four copies of it:
#   versioned_refused    a versioning-Enabled and a versioning-Suspended bucket are refused
#                        versioned-bucket-unsupported at planning, no approval asked;
#   withdrawn            DIRECTOR restores a record waiting for its HARD_DELETE approval and
#                        da-autodelete's service account withdraws it on their behalf (WITHDRAWN);
#   rule_apply           two approvers release the hard delete; the executor applies the
#                        age-based S3 rule (Expiration.Days) on LocalStack, AWAITING_PROOF;
#   rule_in_force        a CloudTrail trail file with the s3lifecycle DeleteObject proves it;
#   rule_ended           past the maximum age the executor removes the rule, RULE_ENDED;
#   rule_ended_unproven  a second applied rule that nothing proves is removed at the same
#                        maximum age, RULE_ENDED_UNPROVEN (Q-695).
# Every account id, key and request id in the phase is invented; nothing reaches AWS.
#
# Dual control (APPR-01, Q-569, Q-586): the evaluation endpoint routes archive and delete
# through ApprovalGate, so nothing reaches the executors until the approvers decide. The
# DELETE policy asks for two approvers (requiredApprovers: 2), so both seeded approvers take
# part; the ARCHIVE request needs one. The approvers are DLAP_APPROVER_1_USER (default cdo)
# and DLAP_APPROVER_2_USER (default director); their password is DLAP_APPROVER_PASSWORD,
# which defaults to the admin password (the dev realm seeds one password for all its users).
#
# Usage:
#   ./cloud-lifecycle-e2e.sh aws          # one cloud (gcp|aws|azure)
#   ./cloud-lifecycle-e2e.sh aws --no-build
#   ./cloud-lifecycle-e2e.sh --down
#
# Isolation (HARN-01, helpers in harness-lib.sh): the whole run holds a host-wide lock, runs in
# its own compose project (COMPOSE_PROJECT_NAME, derived per worktree) and reads the published
# host ports from DLAP_PORT_<SERVICE> (defaults are the dev compose values).
#
# CI lean mode (CI-03, Q-787, decision D41): DLAP_E2E_CI_LEAN=1 fits the aws run on a hosted runner
# with 7.9 GB of memory. It layers docker-compose.ci-lean.yml (a heap ceiling and mem_limit on every
# container) on the stack, stops the services the retire phase does not call (LEAN_STOP_SERVICES)
# before LocalStack starts, and samples `docker stats` every DLAP_E2E_MEM_SAMPLE_SECS (default 5)
# seconds. The run prints the measured peak total container memory and, in lean mode, fails when it
# exceeds DLAP_E2E_MEM_BUDGET_MIB (default 6000). The sampler also runs without lean mode when
# DLAP_E2E_MEM_SAMPLE=1. e2e-tests/test-cloud-lifecycle-e2e.sh tests the helpers without Docker.
# ===========================================================================
set -uo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
DEV="$REPO/deployment/environments/dev"
E2E="$REPO/e2e-tests"
COMPOSE=(docker compose \
  -f "$DEV/docker-compose.yml" \
  -f "$DEV/docker-compose.microservices.yml" \
  -f "$E2E/docker-compose.e2e.yml")
GRADLE="$REPO/gradlew"
# Host-wide lock, per-worktree compose project, DLAP_PORT_* defaults and the JDK 21 choice
# (DLAP_JDK21, else the portable Windows JDK, else the JAVA_HOME the environment provides)
# come from harness-lib.sh. harness_init runs after the argument checks, so a typo never
# waits on the lock.
# shellcheck source=harness-lib.sh
source "$E2E/harness-lib.sh"

# KC_BASE and PE_BASE are set after harness_init, from DLAP_PORT_KEYCLOAK / DLAP_PORT_POLICYENGINE.
KC_REALM="dlap"; KC_CLIENT_ID="dlap-backend"
KC_CLIENT_SECRET="${DLAP_BACKEND_CLIENT_SECRET:-change-me-dev-only}"
# Policy authoring needs ROLE_ADMIN / ROLE_POLICY_MANAGER. The M2M service account
# (client_credentials) only carries ROLE_SERVICE_ACCOUNT, so we password-grant the
# seeded admin user via the confidential dlap-backend client (Direct Access Grants
# is enabled on it in the dev realm). Dev-only relaxation; admin ops by an admin.
KC_ADMIN_USER="${DLAP_ADMIN_USER:-admin}"
KC_ADMIN_PASS="${DLAP_ADMIN_PASSWORD:-Password123!}"
# Dual control (APPR-01): two distinct seeded users who may approve (ADMIN, CDO, DIRECTOR and
# POLICY_MANAGER may; the admin who triggers the evaluation stays out of it).
APPROVERS=("${DLAP_APPROVER_1_USER:-cdo}" "${DLAP_APPROVER_2_USER:-director}")
KC_APPROVER_PASS="${DLAP_APPROVER_PASSWORD:-$KC_ADMIN_PASS}"

INFRA=(postgresql kafka keycloak ollama)
SERVICES=(da-logingest da-discovery da-catalog da-policyengine da-autolabel \
          da-autoarchival da-autodelete da-autocompliance da-reporting da-admin-service)
STACK=("${INFRA[@]}" "${SERVICES[@]}")
HEALTH_WAIT=(dlap-da-catalog dlap-da-policyengine dlap-da-autolabel \
             dlap-da-autoarchival dlap-da-autodelete dlap-da-reporting)
JAR_TARGETS=(:da-logingest:bootJar :da-discovery:bootJar :da-catalog:bootJar \
             :da-policyengine:bootJar :da-autolabel:bootJar :da-autoarchival:bootJar \
             :da-autodelete:bootJar :da-autocompliance:bootJar :da-reporting:bootJar \
             :da-admin-service:bootJar)

say(){ echo "[e2e] $*"; }
fail(){ echo "[e2e] FAIL: $*" >&2; exit 1; }
warn(){ echo "[e2e] WARN: $*" >&2; }
pq(){ docker exec dlap-postgresql psql -U dlap -d "$1" -tAc "$2" 2>/dev/null | tr -d '[:space:]'; }
json_str(){ local key="$1"; grep -oE "\"${key}\"[[:space:]]*:[[:space:]]*\"[^\"]*\"" | head -n1 \
    | sed -E "s/^\"${key}\"[[:space:]]*:[[:space:]]*\"(.*)\"$/\1/"; }

# Memory measurement for the CI lean mode (CI-03, Q-787): mem_sampler_start, mem_mark, mem_sampler_report.
# shellcheck source=lean-lib.sh
source "$E2E/lean-lib.sh"

# Fixtures mount source (Windows drive path for Docker Desktop); needed for every
# compose call, including --down.
FIXDIR="$E2E/fixtures/replay"
command -v cygpath >/dev/null 2>&1 && FIXDIR="$(cygpath -m "$FIXDIR")"
export DLAP_E2E_FIXTURES_DIR="$FIXDIR"
# The aws retire phase (RET-08) layers one more file on the stack and mounts the aws fixtures.
AWSFIXDIR="$E2E/fixtures/aws"
command -v cygpath >/dev/null 2>&1 && AWSFIXDIR="$(cygpath -m "$AWSFIXDIR")"
export DLAP_E2E_AWS_FIXTURES_DIR="$AWSFIXDIR"
RULE_MAX_AGE_MIN="${DLAP_E2E_RULE_MAX_AGE_MINUTES:-4}"
[[ "$RULE_MAX_AGE_MIN" =~ ^[1-9][0-9]*$ ]] || { echo "[e2e] FAIL: DLAP_E2E_RULE_MAX_AGE_MINUTES must be a whole number of minutes" >&2; exit 1; }
export DLAP_E2E_RULE_MAX_AGE="PT${RULE_MAX_AGE_MIN}M"
COMPOSE_RETIRE=(docker compose   -f "$DEV/docker-compose.yml"   -f "$DEV/docker-compose.microservices.yml"   -f "$E2E/docker-compose.e2e.yml"   -f "$E2E/docker-compose.lifecycle-aws.yml")

# CI lean mode (CI-03). The overlay goes last so its limits win. The LocalStack limit is a separate
# file because that service is defined only in docker-compose.lifecycle-aws.yml.
LEAN=0; [[ "${DLAP_E2E_CI_LEAN:-0}" == "1" ]] && LEAN=1
if (( LEAN )); then
  COMPOSE+=(-f "$E2E/docker-compose.ci-lean.yml")
  COMPOSE_RETIRE+=(-f "$E2E/docker-compose.ci-lean.yml" -f "$E2E/docker-compose.ci-lean-localstack.yml")
fi
# Services the retire phase never uses. Traced in the code, over both HTTP and Kafka, not guessed:
#   HTTP   the harness calls da-policyengine (approvals) and da-autodelete (restores); da-autodelete calls
#          da-catalog (CatalogFactsClient) and da-policyengine; da-autoarchival calls da-catalog and
#          da-policyengine; nothing in them calls the five below (grep of the main sources).
#   Kafka  the delete proof travels da-logingest -> dlap.log.event.normalized -> da-discovery
#          (DiscoveryLogEventProcessor, group da-discovery-log-event-router) -> dlap.discovery.asset.deleted ->
#          da-autodelete (DeletionProofListener) and da-catalog; so da-discovery MUST keep running (a first
#          lean run stopped it and rule_in_force and rule_ended failed). da-autoarchival reads the same log
#          topic (ArchiveProofListener) and approval decisions.
#   Not used: da-autolabel (catalog upserts, labeling requests), da-autocompliance (permission changes),
#          da-reporting (policy actions and access decisions into reports), da-admin-service (no consumer)
#          and ollama (da-autolabel runs the rules provider).
# Every gate of the earlier phases (label, policy, held, self_approval, approval, archive, archive_loc, delete, report,
# compliance) is read before the retire phase starts, so these can go first.
LEAN_STOP_SERVICES=(da-autolabel da-autocompliance da-reporting da-admin-service ollama)

if [[ "${1:-}" == "--down" ]]; then
  harness_init cloud-lifecycle "$REPO"
  say "tearing down full stack + volumes"; "${COMPOSE_RETIRE[@]}" down -v --remove-orphans; exit 0
fi

PROVIDER="${1:-}"
case "$PROVIDER" in gcp|aws|azure) ;; *) fail "provider required: gcp|aws|azure (got '${PROVIDER:-}')";; esac
harness_init "cloud-lifecycle-$PROVIDER" "$REPO"
KC_BASE="http://localhost:${DLAP_PORT_KEYCLOAK}"; PE_BASE="http://localhost:${DLAP_PORT_POLICYENGINE}"
NO_BUILD=""; for a in "$@"; do [[ "$a" == "--no-build" ]] && NO_BUILD=1; done

# Per-cloud asset predicate (matches the fixtures/replay/<cloud>-events.json URIs).
case "$PROVIDER" in
  gcp)   PROVIDER_URI_HINT="dlap-gcp-e2e";  PRED="cloud_provider='GCP' AND (asset_uri LIKE 'gs://dlap-gcp-e2e%' OR asset_uri LIKE 'bq://dlap-gcp-e2e/%' OR asset_uri LIKE 'projects/dlap-gcp-e2e/%')";;
  aws)   PROVIDER_URI_HINT="dlap-aws-e2e";  PRED="cloud_provider='AWS' AND asset_uri LIKE 's3://dlap-aws-e2e%'";;
  azure) PROVIDER_URI_HINT="dlapazuree2e"; PRED="cloud_provider='AZURE' AND (asset_uri LIKE 'az://dlapazuree2e%' OR asset_uri LIKE '%dlapazuree2e.blob.core.windows.net%')";;
esac

export DLAP_LOGINGEST_REPLAY_ENABLED=true
export DLAP_LOGINGEST_REPLAY_PROVIDER="$PROVIDER"
export DLAP_LOGINGEST_DEMO_SEED_ENABLED=false
say "cloud=$PROVIDER  fixtures=$DLAP_E2E_FIXTURES_DIR"

# 1) Build.
if [[ -z "$NO_BUILD" ]]; then
  say "building ${#JAR_TARGETS[@]} service jars…"
  "$GRADLE" -p "$REPO" "${JAR_TARGETS[@]}" --no-daemon --console=plain --no-watch-fs \
      >/tmp/cloud_lc_jars.log 2>&1 9>&- || fail "jar build failed (see /tmp/cloud_lc_jars.log)"
fi

# 2) Clean slate + up.
say "bringing up full stack (replay=$PROVIDER)…"
(( LEAN )) && say "CI lean mode: heap ceilings and container limits from docker-compose.ci-lean.yml"
if (( LEAN )) || [[ "${DLAP_E2E_MEM_SAMPLE:-0}" == "1" ]]; then mem_sampler_start; fi
# Wipes this worktree's project, and evicts any stack another project left holding the
# fixed container names (the compose files pin container_name).
# The aws run resets with the retire file too, so a LocalStack left by an earlier run goes as well.
if [[ "$PROVIDER" == "aws" ]]; then BASE_COMPOSE=("${COMPOSE[@]}"); COMPOSE=("${COMPOSE_RETIRE[@]}"); fi
harness_reset_stack
if [[ "$PROVIDER" == "aws" ]]; then COMPOSE=("${BASE_COMPOSE[@]}"); fi
"${COMPOSE[@]}" up -d --build --force-recreate "${STACK[@]}" >/tmp/cloud_lc_up.log 2>&1 \
    || fail "compose up failed (see /tmp/cloud_lc_up.log)"

# 3) Wait for keycloak realm, router, downstream services.
say "waiting for keycloak realm…"
for i in $(seq 1 60); do
  curl -sf "$KC_BASE/realms/$KC_REALM/.well-known/openid-configuration" >/dev/null 2>&1 && break; sleep 3; done
say "waiting for da-discovery (router)…"
# harness_wait_log, not `docker logs | grep -q`: under pipefail grep -q exits on its first match,
# docker logs fails (SIGPIPE, rc 141, on Linux; a write error, rc 1, for docker.exe on Windows),
# so the wait ran its full 6 minutes even after the start line had been logged.
if harness_wait_log dlap-da-discovery "Started DADiscoveryAgent" 72 5; then
  say "da-discovery up (~${HARNESS_WAITED}s)"
else
  warn "da-discovery start not confirmed; continuing"
fi
say "waiting for downstream services healthy…"
HD=$((SECONDS+300))
for c in "${HEALTH_WAIT[@]}"; do
  while (( SECONDS < HD )); do
    st=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$c" 2>/dev/null)
    [[ "$st" == "healthy" ]] && break
    [[ "$st" == "none" && "$(docker inspect -f '{{.State.Running}}' "$c" 2>/dev/null)" == "true" ]] && break
    sleep 4
  done
done
say "settling 25s for replay + label cascade…"; sleep 25

# 4) Resolve the cloud's asset UUIDs (created by the replay). Use a raw multi-row
#    query (the pq helper strips ALL whitespace, which would mash rows together);
#    keep one UUID per line, drop the Windows CR, filter to UUID-shaped lines.
mapfile -t UUIDS < <(docker exec dlap-postgresql psql -U dlap -d dlap_common -tAc \
    "SELECT asset_id FROM dlap_common.dlap_assets WHERE $PRED ORDER BY asset_uri" 2>/dev/null \
    | tr -d '\r' | grep -iE '^[0-9a-f-]{36}$')
say "resolved ${#UUIDS[@]} $PROVIDER asset UUID(s) from the catalog: ${UUIDS[*]:0:3}"
(( ${#UUIDS[@]} >= 1 )) || fail "no $PROVIDER assets in the catalog - replay/discovery did not land (check da-logingest/da-catalog logs)"
A0="${UUIDS[0]}"; A1="${UUIDS[1]:-$A0}"; A2="${UUIDS[2]:-$A1}"

# 5) Admin token (password grant, minted inside the compose network so iss matches).
NET=$(docker inspect -f '{{range $k,$v := .NetworkSettings.Networks}}{{$k}}{{end}}' dlap-postgresql 2>/dev/null); [[ -n "$NET" ]] || NET="${COMPOSE_PROJECT_NAME}_dlap-network"
mint_token(){ local user="$1" pass="$2"
  docker run --rm --network "$NET" curlimages/curl:8.11.1 -s \
    "http://keycloak:8080/realms/$KC_REALM/protocol/openid-connect/token" \
    -d grant_type=password -d client_id="$KC_CLIENT_ID" -d client_secret="$KC_CLIENT_SECRET" \
    -d username="$user" -d password="$pass" 2>/dev/null | json_str access_token; }
TOKEN=$(mint_token "$KC_ADMIN_USER" "$KC_ADMIN_PASS")
[[ -n "$TOKEN" ]] || fail "could not obtain admin access_token from keycloak (net=$NET, user=$KC_ADMIN_USER)"
AUTH=(-H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json")
say "admin token acquired (len=${#TOKEN})."

# 6) Seed policies + evaluate against the cloud's assets.
# SEC-04 (ADR-0057): rules[] (MVEL) is retired and answers 400. Each policy is a dlap/v1
# document that matches the cloud's assets; the REST evaluation fires the actions map through
# ApprovalGate (archive and delete wait for approval, the label action publishes at once).
create_policy(){ local name="$1" desc="$2" actions="$3" atype="$4" cloud="${PROVIDER^^}" body resp code json doc
  doc="apiVersion: dlap/v1\\nkind: Policy\\nmetadata: {id: $name, name: $name}\\nspec:\\n  triggers: [{type: schedule, cron: '0 0 0 1 1 ?'}]\\n  match: {cloudProvider: $cloud}\\n  action: {type: $atype}\\n"
  body="{\"name\":\"$name\",\"description\":\"$desc\",\"status\":\"ENABLED\",\"policyDocument\":\"$doc\",\"actions\":$actions}"
  resp=$(curl -s -w $'\n%{http_code}' -X POST "$PE_BASE/api/v1/policyengine/policies" "${AUTH[@]}" -d "$body" 2>/dev/null)
  code=$(printf '%s' "$resp" | tail -n1); json=$(printf '%s' "$resp" | sed '$d')
  say "  POST /policies '$name' -> HTTP $code" >&2
  [[ "$code" =~ ^2[0-9][0-9]$ ]] || { warn "policy '$name' not 2xx: ${json:0:160}"; return 1; }
  printf '%s' "$json" | json_str id; }
trigger_eval(){ local pid="$1" asset="$2" code
  [[ -n "$pid" ]] || { warn "no policy id"; return 1; }
  code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$PE_BASE/api/v1/policyengine/$pid/evaluations" \
      "${AUTH[@]}" -d "$(printf '{"targetAssetId":"%s"}' "$asset")" 2>/dev/null)
  say "  POST /$pid/evaluations (asset $asset) -> HTTP $code"; }

say "seeding 3 policies for $PROVIDER…"
P_ARCHIVE=$(create_policy "e2e-$PROVIDER-archive" "E2E archive policy ($PROVIDER; DSL match)" '{"ARCHIVE":{"tier":"COLD","reason":"e2e archive"}}' archive)
P_DELETE=$(create_policy  "e2e-$PROVIDER-delete"  "E2E delete policy ($PROVIDER; DSL match)"  '{"DELETE":{"auditNote":"e2e secure delete","requiredApprovers":2}}' delete)
P_LABEL=$(create_policy   "e2e-$PROVIDER-label"   "E2E label policy ($PROVIDER; DSL match)"   '{"addLabel":{"label":"E2E_REVIEWED"}}' addLabel)
[[ -n "$P_ARCHIVE$P_DELETE$P_LABEL" ]] || fail "no policies created"
say "triggering evaluations (archive=$A0 delete=$A1 label=$A2)…"
trigger_eval "$P_ARCHIVE" "$A0"
trigger_eval "$P_DELETE"  "$A1"
trigger_eval "$P_LABEL"   "$A2"

poll_count(){ local db="$1" sql="$2" budget="$3" dl=$((SECONDS+$3)) c=0
  while (( SECONDS < dl )); do c=$(pq "$db" "$sql"); [[ "$c" =~ ^[0-9]+$ ]] && (( c>0 )) && break; sleep 5; done
  [[ "$c" =~ ^[0-9]+$ ]] || c=0; printf '%s' "$c"; }
declare -A T

# 6b) Dual control (APPR-01, Q-569, Q-586): the archive and delete fires wait as approval requests.
#     Each seeded approver reads the requests still waiting for them (the inbox) and approves
#     them through the approval REST API. A request that needs two approvers stays PENDING after
#     the first and is left to the second; nobody approves a request twice.
inbox_ids(){ local tok="$1"
  curl -s "$PE_BASE/api/v1/policyengine/approvals/inbox?size=200" -H "Authorization: Bearer $tok" 2>/dev/null \
    | tr -d '\r' | grep -oE '"id"[[:space:]]*:[[:space:]]*"[0-9a-fA-F-]{36}"' | grep -oE '[0-9a-fA-F-]{36}'; }
approve(){ local tok="$1" user="$2" rid="$3" code
  code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$PE_BASE/api/v1/policyengine/approvals/$rid/decisions" \
      -H "Authorization: Bearer $tok" -H "Content-Type: application/json" \
      -d "{\"decision\":\"APPROVE\",\"comment\":\"e2e dual control: $user approves $rid\"}" 2>/dev/null)
  say "  $user APPROVE $rid -> HTTP $code"; [[ "$code" == "200" ]]; }

# self_approval_verdict <http code> <response body> <request status afterwards>: 1 when the refusal is the
# right one (HTTP 403, reason self-approval, and the request still PENDING), else 0. Kept as a function so
# e2e-tests/test-cloud-lifecycle-e2e.sh can prove the verdict without a stack (Q-592).
self_approval_verdict(){ local code="$1" body="$2" status="$3" reason
  reason=$(printf '%s' "$body" | json_str reason)
  [[ "$code" == "403" && "$reason" == "self-approval" && "$status" == "PENDING" ]] && echo 1 || echo 0; }

say "waiting for the archive and delete fires to wait for approval…"
T[held]=$(poll_count da_policyengine "SELECT count(*) FROM dlap_policyengine.approval_requests WHERE status='PENDING'" 60)
say "[HELD] approval requests waiting: ${T[held]} (want >0; the archive and delete fires)"

# 6a) Self-approval (Q-592, ADR-0098): the admin token triggered every evaluation above, so the admin may not
#     decide the requests they caused. The admin decision on a held request must answer 403 with reason
#     self-approval and leave it PENDING; the approvers below then decide it as before.
SELF_RID=$(pq da_policyengine "SELECT id FROM dlap_policyengine.approval_requests WHERE status='PENDING' AND policy_db_id='${P_ARCHIVE:-none}' LIMIT 1")
if [[ "$SELF_RID" =~ ^[0-9a-fA-F-]{36}$ ]]; then
  SELF_RESP=$(curl -s -w $'\n%{http_code}' -X POST "$PE_BASE/api/v1/policyengine/approvals/$SELF_RID/decisions" \
      -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
      -d '{"decision":"APPROVE","comment":"e2e self-approval: the admin who triggered the evaluation decides its own request"}' 2>/dev/null)
  SELF_CODE=$(printf '%s' "$SELF_RESP" | tail -n1); SELF_BODY=$(printf '%s' "$SELF_RESP" | sed '$d')
  SELF_STATUS=$(pq da_policyengine "SELECT status FROM dlap_policyengine.approval_requests WHERE id='$SELF_RID'")
  T[self_approval]=$(self_approval_verdict "$SELF_CODE" "$SELF_BODY" "$SELF_STATUS")
  say "[SELF_APPROVAL] admin APPROVE on own request $SELF_RID -> HTTP $SELF_CODE, reason $(printf '%s' "$SELF_BODY" | json_str reason), request $SELF_STATUS (want 403 self-approval, PENDING) -> ${T[self_approval]}"
else
  T[self_approval]=0
  warn "[SELF_APPROVAL] no PENDING request for the archive policy ($P_ARCHIVE) to try"
fi
APPROVE_FAILED=0
for u in "${APPROVERS[@]}"; do
  UTOK=$(mint_token "$u" "$KC_APPROVER_PASS")
  [[ -n "$UTOK" ]] || { warn "could not obtain an access_token for approver '$u'"; APPROVE_FAILED=1; continue; }
  mapfile -t RIDS < <(inbox_ids "$UTOK")
  say "approver '$u' sees ${#RIDS[@]} request(s) waiting for them"
  for rid in "${RIDS[@]}"; do approve "$UTOK" "$u" "$rid" || APPROVE_FAILED=1; done
done
(( APPROVE_FAILED == 0 )) || warn "an approval call failed; the gates below show what was released"

# 7) Settle + gate.
say "settling 30s for the policy-action fan-out…"; sleep 30
T[approval]=$(poll_count da_policyengine "SELECT count(*) FROM dlap_policyengine.approval_requests WHERE status='APPROVED'" 60)
T[label]=$(poll_count dlap_common "SELECT count(*) FROM dlap_common.dlap_labels WHERE description='Auto-created on first assignment'" 240)
T[policy]=$(poll_count da_policyengine "SELECT count(*) FROM dlap_policyengine.policy_evaluations WHERE status='PASS'" 240)
T[archive]=$(poll_count da_autoarchival "SELECT count(*) FROM dlap_autoarchival.dlap_archival_tasks" 120)
# Positive check for the resolved asset location (hardening 2026-09-17): the policy-driven task
# must carry the asset's REAL location, never the old fabricated s3://unknown/<id>. Both are read
# from the task's asset_statuses jsonb payload. A dry-run marker on soft-delete rows appears only
# after the hard-delete sweeper has processed them (past the soft-delete window), so not gated here.
ARCH_FAKE=$(pq da_autoarchival "SELECT count(*) FROM dlap_autoarchival.dlap_archival_tasks WHERE asset_statuses::text LIKE '%s3://unknown%'")
ARCH_REAL=$(pq da_autoarchival "SELECT count(*) FROM dlap_autoarchival.dlap_archival_tasks WHERE asset_statuses::text LIKE '%${PROVIDER_URI_HINT}%'")
[[ "$ARCH_FAKE" =~ ^[0-9]+$ ]] || ARCH_FAKE=0; [[ "$ARCH_REAL" =~ ^[0-9]+$ ]] || ARCH_REAL=0
T[archive_loc]=$(( ARCH_FAKE == 0 && ARCH_REAL > 0 ? 1 : 0 ))
say "[ARCHIVE] tasks with fabricated s3://unknown location: $ARCH_FAKE (want 0); with real $PROVIDER hint '$PROVIDER_URI_HINT': $ARCH_REAL (want >0)"
T[delete]=$(poll_count da_autodelete "SELECT count(*) FROM dlap_autodelete.soft_delete_records WHERE status='SOFT_DELETED'" 120)
T[report]=$(poll_count da_reporting "SELECT count(*) FROM dlap_reporting.policy_action_events" 120)
T[compliance]=$(pq da_autocompliance "SELECT count(*) FROM dlap_autocompliance.compliance_control_evaluation_jobs"); [[ "${T[compliance]}" =~ ^[0-9]+$ ]] || T[compliance]=0

# 8) Retire phase (aws only; RET-08, Q-714). See the header for what each gate proves.
AD_BASE="http://localhost:${DLAP_PORT_AUTODELETE}"
RETIRE_GATES=()
rpq(){ pq da_autodelete "$1"; }
rec(){ rpq "SELECT $2 FROM dlap_autodelete.soft_delete_records WHERE id='$1'"; }
disp(){ rpq "SELECT $2 FROM dlap_autodelete.retire_dispositions WHERE soft_delete_record_id='$1'"; }
audit_count(){ # audit_count <stream> <event type> <outcome> <needle>
  pq dlap_common "SELECT count(*) FROM dlap_common.audit_log WHERE stream_id='$1' AND event_type='$2' AND record::jsonb->>'outcome'='$3' AND record LIKE '%$4%'"; }
wait_rec(){ # wait_rec <record id> <status regex> <seconds>: prints the last status
  local id="$1" want="$2" dl=$((SECONDS+$3)) st=""
  while (( SECONDS < dl )); do st=$(rec "$id" status); [[ "$st" =~ ^($want)$ ]] && break; sleep 5; done
  printf '%s' "$st"; }
ls_exec(){ # run a command inside this project's LocalStack container (curl, gzip are in the image)
  local cid; cid=$("${COMPOSE_RETIRE[@]}" ps -q e2e-localstack 2>/dev/null | tr -d '\r' | head -n1)
  [[ -n "$cid" ]] || return 1
  MSYS_NO_PATHCONV=1 docker exec -i "$cid" "$@"; }
s3(){ # s3 <method> <path and query>: LocalStack S3 (path-style), prints the body then the HTTP code
  ls_exec curl -s -w '\n%{http_code}' -X "$1" "http://localhost:4566$2" </dev/null | tr -d '\r'; }
s3_put_xml(){ # s3_put_xml <path and query> <xml>
  printf '%s' "$2" | ls_exec curl -s -o /dev/null -w '%{http_code}' -X PUT -H 'Content-Type: application/xml' \
    --data-binary @- "http://localhost:4566$1" | tr -d '\r'; }
s3_put_gz(){ # s3_put_gz <bucket/key>: gzips stdin into the object, prints the HTTP code
  ls_exec sh -c 'gzip -c | curl -s -o /dev/null -w "%{http_code}" -X PUT --data-binary @- "http://localhost:4566/$1"' \
    sh "$1" | tr -d '\r'; }
lifecycle_of(){ s3 GET "/$1?lifecycle"; }
jwt_sub(){ local p; p=$(printf '%s' "$1" | cut -d. -f2 | tr '_-' '/+'); while (( ${#p} % 4 )); do p="$p="; done
  printf '%s' "$p" | base64 -d 2>/dev/null | json_str sub; }
clone_record(){ # clone_record <source record> <bucket> <asset id>: a due SOFT_DELETED copy for another bucket
  local nid; nid=$(rpq "SELECT gen_random_uuid()")
  rpq "CREATE TEMP TABLE e2e_clone AS SELECT * FROM dlap_autodelete.soft_delete_records WHERE id='$1';
       UPDATE e2e_clone SET id='$nid', asset_id='$3', asset_uri='s3://$2', status='SOFT_DELETED',
         hard_delete_approval_id=NULL, hard_delete_requested_at=NULL, last_error=NULL, hard_deleted_at=NULL,
         scheduled_hard_delete_at=now()-interval '1 minute', next_check_at=NULL;
       INSERT INTO dlap_autodelete.soft_delete_records SELECT * FROM e2e_clone;" >/dev/null
  # The row must exist; its status is not checked. da-autodelete sweeps every 10 s
  # (DLAP_AUTODELETE_SWEEP_INTERVAL=PT10S) and may already have moved a due copy on (Q-765).
  [[ "$(rec "$nid" id)" == "$nid" ]] && printf '%s' "$nid"; }
wait_healthy(){ local c="$1" i
  for i in $(seq 1 100); do
    [[ "$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{end}}' "$c" 2>/dev/null)" == "healthy" ]] && return 0
    sleep 3; done; return 1; }

retire_phase(){
  local rid aid uri bucket uaid ubucket rid_u rid_v rid_s rid_w st body code i
  local trail="dlap-e2e-trail" vbucket="dlap-e2e-versioned" sbucket="dlap-e2e-suspended" wbucket="dlap-e2e-withdraw"
  local vxml='<VersioningConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/"><Status>%s</Status></VersioningConfiguration>'
  rid=$(rpq "SELECT id FROM dlap_autodelete.soft_delete_records WHERE status='SOFT_DELETED' AND asset_uri ~ '^s3://[^/]+$' ORDER BY queued_at DESC LIMIT 1")
  [[ -n "$rid" ]] || { warn "[RETIRE] no SOFT_DELETED record of a whole S3 bucket"; return 1; }
  aid=$(rec "$rid" asset_id); uri=$(rec "$rid" asset_uri); bucket="${uri#s3://}"
  # The second applied rule (never proven) needs a second bucket the catalog knows.
  uaid=$(pq dlap_common "SELECT asset_id FROM dlap_common.dlap_assets WHERE $PRED AND asset_id <> '$aid' AND asset_uri ~ '^s3://[^/]+$' ORDER BY (asset_uri = 's3://dlap-aws-e2e-analytics') DESC, asset_uri LIMIT 1")
  ubucket=$(pq dlap_common "SELECT asset_uri FROM dlap_common.dlap_assets WHERE asset_id='$uaid'"); ubucket="${ubucket#s3://}"
  [[ -n "$uaid" && -n "$ubucket" ]] || { warn "[RETIRE] no second S3 bucket asset in the catalog"; return 1; }
  say "[RETIRE] record $rid ($uri); unproven rule on s3://$ubucket; rule max age $DLAP_E2E_RULE_MAX_AGE"

  if (( LEAN )); then
    mem_mark retire
    say "[RETIRE] lean mode: stopping services this phase does not call: ${LEAN_STOP_SERVICES[*]}"
    "${COMPOSE_RETIRE[@]}" stop "${LEAN_STOP_SERVICES[@]}" >/tmp/cloud_lc_lean_stop.log 2>&1 \
        || { warn "[RETIRE] could not stop the unused services (see /tmp/cloud_lc_lean_stop.log)"; return 1; }
    mem_mark retire-after-stop
  fi
  say "[RETIRE] starting LocalStack S3 on the stack network…"
  "${COMPOSE_RETIRE[@]}" up -d --no-deps e2e-localstack >/tmp/cloud_lc_retire_up.log 2>&1 \
      || { warn "[RETIRE] LocalStack did not start (see /tmp/cloud_lc_retire_up.log)"; return 1; }
  for i in $(seq 1 60); do
    body=$(ls_exec curl -s http://localhost:4566/_localstack/health 2>/dev/null | tr -d '\r')
    [[ "$body" =~ \"s3\":[[:space:]]*\"(available|running)\" ]] && break; sleep 2; done
  [[ "$body" =~ \"s3\":[[:space:]]*\"(available|running)\" ]] || { warn "[RETIRE] LocalStack S3 not ready: ${body:0:160}"; return 1; }
  for b in "$trail" "$bucket" "$ubucket" "$vbucket" "$sbucket" "$wbucket"; do
    code=$(s3 PUT "/$b" | tail -n1); [[ "$code" == "200" ]] || { warn "[RETIRE] create bucket $b -> HTTP $code"; return 1; }
  done
  # shellcheck disable=SC2059  # the format is the XML template
  s3_put_xml "/$vbucket?versioning" "$(printf "$vxml" Enabled)" >/dev/null
  # shellcheck disable=SC2059
  s3_put_xml "/$sbucket?versioning" "$(printf "$vxml" Enabled)" >/dev/null
  # shellcheck disable=SC2059
  s3_put_xml "/$sbucket?versioning" "$(printf "$vxml" Suspended)" >/dev/null
  body=$(s3 GET "/$vbucket?versioning")$(s3 GET "/$sbucket?versioning")
  [[ "$body" == *"<Status>Enabled</Status>"* && "$body" == *"<Status>Suspended</Status>"* ]] \
      || { warn "[RETIRE] LocalStack did not keep the versioning states: ${body:0:200}"; return 1; }

  say "[RETIRE] recreating da-autodelete (dry run off, S3 test endpoint), da-autoarchival (S3 test endpoint) and da-logingest (trail-file transport)…"
  "${COMPOSE_RETIRE[@]}" up -d --no-deps --no-build --force-recreate da-autodelete da-autoarchival da-logingest \
      >>/tmp/cloud_lc_retire_up.log 2>&1 || { warn "[RETIRE] recreate failed (see /tmp/cloud_lc_retire_up.log)"; return 1; }
  wait_healthy dlap-da-autodelete || { warn "[RETIRE] da-autodelete is not healthy"; return 1; }
  wait_healthy dlap-da-autoarchival || { warn "[RETIRE] da-autoarchival is not healthy"; return 1; }
  wait_healthy dlap-da-logingest || { warn "[RETIRE] da-logingest is not healthy"; return 1; }
  local endpoint_warned=0 archival_warned=0 archival_deprecated=0
  harness_log_has dlap-da-autodelete "TEST-ONLY endpoint http://e2e-localstack:4566" && endpoint_warned=1
  say "[RETIRE] da-autodelete start-up WARN for the test endpoint: $endpoint_warned (want 1)"
  # EXEC-01 (Q-740): da-autoarchival reads the same scheme; its guard names the property it saw, and a deprecated
  # RET-03 name would make it log "is DEPRECATED".
  harness_log_has dlap-da-autoarchival "dlap.autoarchival.test-endpoint.s3" && archival_warned=1
  harness_log_has dlap-da-autoarchival "is DEPRECATED" && archival_deprecated=1
  say "[RETIRE] da-autoarchival start-up WARN names dlap.autoarchival.test-endpoint.s3: $archival_warned (want 1); deprecated names seen: $archival_deprecated (want 0)"

  # Read coverage FULL on both assets (a hard delete needs it) and the copies, all due now.
  pq dlap_common "UPDATE dlap_common.dlap_assets SET read_coverage='FULL', read_coverage_since=now()-interval '400 days', read_coverage_observed_at=now() WHERE asset_id IN ('$aid', '$uaid')" >/dev/null
  rid_u=$(clone_record "$rid" "$ubucket" "$uaid")
  rid_v=$(clone_record "$rid" "$vbucket" "$(rpq "SELECT gen_random_uuid()")")
  rid_s=$(clone_record "$rid" "$sbucket" "$(rpq "SELECT gen_random_uuid()")")
  rid_w=$(clone_record "$rid" "$wbucket" "$(rpq "SELECT gen_random_uuid()")")
  [[ -n "$rid_u" && -n "$rid_v" && -n "$rid_s" && -n "$rid_w" ]] || { warn "[RETIRE] could not copy the record"; return 1; }
  rpq "UPDATE dlap_autodelete.soft_delete_records SET scheduled_hard_delete_at=now()-interval '1 minute', next_check_at=NULL WHERE id='$rid'" >/dev/null

  # versioned_refused: refused at planning, before anyone is asked to approve.
  local vs ss ve se
  vs=$(wait_rec "$rid_v" 'HARD_DELETE_REFUSED|HARD_DELETE_PENDING_APPROVAL' 180)
  ss=$(wait_rec "$rid_s" 'HARD_DELETE_REFUSED|HARD_DELETE_PENDING_APPROVAL' 60)
  ve=$(rec "$rid_v" "coalesce(last_error,'')||'|'||coalesce(hard_delete_approval_id::text,'none')")
  se=$(rec "$rid_s" "coalesce(last_error,'')||'|'||coalesce(hard_delete_approval_id::text,'none')")
  body=$(lifecycle_of "$vbucket")
  if [[ "$vs" == "HARD_DELETE_REFUSED" && "$ss" == "HARD_DELETE_REFUSED" && "$ve" == versioned-bucket-unsupported*ENABLED*"|none" \
        && "$se" == versioned-bucket-unsupported*SUSPENDED*"|none" && "$body" == *NoSuchLifecycleConfiguration* ]]; then T[versioned_refused]=1; fi
  say "[RETIRE] versioned buckets: Enabled $vs, Suspended $ss (want HARD_DELETE_REFUSED, versioned-bucket-unsupported, no approval) -> ${T[versioned_refused]}"

  # The record, the unproven copy and the withdraw copy wait for their HARD_DELETE approvals.
  st=$(wait_rec "$rid" 'HARD_DELETE_PENDING_APPROVAL|HARD_DELETE_REFUSED' 180)
  local st_u st_w
  st_u=$(wait_rec "$rid_u" 'HARD_DELETE_PENDING_APPROVAL|HARD_DELETE_REFUSED' 60)
  st_w=$(wait_rec "$rid_w" 'HARD_DELETE_PENDING_APPROVAL|HARD_DELETE_REFUSED' 60)
  say "[RETIRE] waiting for HARD_DELETE approval: record $st, unproven copy $st_u, withdraw copy $st_w"
  [[ "$st" == "HARD_DELETE_PENDING_APPROVAL" && "$st_u" == "HARD_DELETE_PENDING_APPROVAL" ]] \
      || { warn "[RETIRE] the hard deletes were not asked for: $(rec "$rid" "coalesce(last_error,'')") / $(rec "$rid_u" "coalesce(last_error,'')")"; return 1; }

  # withdrawn: DIRECTOR restores the withdraw copy; the executor's service account withdraws its request.
  local dtok ctok wid dsub
  dtok=$(mint_token "${APPROVERS[1]}" "$KC_APPROVER_PASS"); ctok=$(mint_token "${APPROVERS[0]}" "$KC_APPROVER_PASS")
  wid=$(rec "$rid_w" "coalesce(hard_delete_approval_id::text,'')"); dsub=$(jwt_sub "$dtok")
  if [[ "$st_w" == "HARD_DELETE_PENDING_APPROVAL" && -n "$wid" && -n "$dtok" ]]; then
    body=$(curl -s -X POST "$AD_BASE/api/v1/autodelete/restores/$rid_w" -H "Authorization: Bearer $dtok" \
        -H "Content-Type: application/json" -d '{"notes":"e2e: restored before the hard delete, withdraw it"}' 2>/dev/null)
    local view
    view=$(curl -s "$PE_BASE/api/v1/policyengine/approvals/$wid" -H "Authorization: Bearer $ctok" 2>/dev/null)
    local withdrawn_audit
    withdrawn_audit=$(pq dlap_common "SELECT count(*) FROM dlap_common.audit_log WHERE stream_id='govern.approval' AND event_type='govern.approval.withdrawn' AND record LIKE '%$wid%'")
    if [[ "$body" == *'"status":"RESTORED"'* && "$body" == *'"approvalWithdrawal":"WITHDRAWN"'* \
          && "$(printf '%s' "$view" | json_str status)" == "WITHDRAWN" && -n "$(printf '%s' "$view" | json_str withdrawnBy)" \
          && "$(printf '%s' "$view" | json_str onBehalfOf)" == "$dsub" && "$(rec "$rid_w" status)" == "RESTORED" \
          && "${withdrawn_audit:-0}" =~ ^[1-9] ]]; then T[withdrawn]=1; fi
    say "[RETIRE] restore by ${APPROVERS[1]}: ${body:0:160}; approval $wid is $(printf '%s' "$view" | json_str status), govern.approval.withdrawn records: ${withdrawn_audit:-0} -> ${T[withdrawn]}"
  else
    warn "[RETIRE] the withdraw copy did not wait for its approval ($st_w)"
  fi

  # rule_apply: two approvers release the record and the unproven copy; the executor applies on LocalStack.
  local hid hid_u
  hid=$(rec "$rid" "hard_delete_approval_id"); hid_u=$(rec "$rid_u" "hard_delete_approval_id")
  for r in "$hid" "$hid_u"; do
    approve "$ctok" "${APPROVERS[0]}" "$r" || warn "[RETIRE] ${APPROVERS[0]} could not approve $r"
    approve "$dtok" "${APPROVERS[1]}" "$r" || warn "[RETIRE] ${APPROVERS[1]} could not approve $r"
  done
  st=$(wait_rec "$rid" 'AWAITING_PROOF|HARD_DELETE_REFUSED|HARD_DELETE_FAILED|DRY_RUN_COMPLETE' 180)
  st_u=$(wait_rec "$rid_u" 'AWAITING_PROOF|HARD_DELETE_REFUSED|HARD_DELETE_FAILED|DRY_RUN_COMPLETE' 60)
  local d_r d_u lc lc_u applied_ok
  d_r=$(disp "$rid" "mode||'|'||platform||'|'||dry_run||'|'||coalesce(rule_id,'')||'|'||(applied_at IS NOT NULL)")
  d_u=$(disp "$rid_u" "mode||'|'||platform||'|'||dry_run||'|'||coalesce(rule_id,'')||'|'||(applied_at IS NOT NULL)")
  lc=$(lifecycle_of "$bucket"); lc_u=$(lifecycle_of "$ubucket")
  applied_ok=$(audit_count retire.delete retire.delete.apply SUCCEEDED "$rid")
  if [[ "$st" == "AWAITING_PROOF" && "$st_u" == "AWAITING_PROOF" && "$d_r" == NATIVE_RULE\|S3_LIFECYCLE\|false\|dlap-retire-*\|true \
        && "$lc" == *"<ID>$(disp "$rid" "rule_id")</ID>"* && "$lc" == *"<Days>1</Days>"* && "$lc" != *"<Date>"* && "$lc_u" == *"<Days>1</Days>"* \
        && "${applied_ok:-0}" =~ ^[1-9] && "$endpoint_warned" == 1 && "$archival_warned" == 1 && "$archival_deprecated" == 0 ]]; then T[rule_apply]=1; fi
  say "[RETIRE] apply: record $st ($d_r), unproven copy $st_u; LocalStack rule with Days 1 and no Date on both buckets; apply SUCCEEDED records ${applied_ok:-0} -> ${T[rule_apply]}"
  (( ${T[rule_apply]} == 1 )) || { warn "[RETIRE] last error: $(rec "$rid" "coalesce(last_error,'')")"; return 1; }

  # rule_in_force: a CloudTrail trail file with the s3lifecycle DeleteObject of an object in the bucket.
  local applied now ev_time delivered ymd stamp unique eid key code_t
  applied=$(disp "$rid" "floor(extract(epoch from applied_at))::bigint")
  while (( $(date -u +%s) < applied + 20 )); do sleep 2; done
  now=$(date -u +%s); ev_time=$(date -u -d "@$((applied + 10))" +%Y-%m-%dT%H:%M:%SZ)
  delivered=$((now - 60)); ymd=$(date -u -d "@$delivered" +%Y/%m/%d); stamp=$(date -u -d "@$delivered" +%Y%m%dT%H%MZ)
  unique="E2eRet08$(date -u +%H%M%S)"
  eid=$(printf '%08x-%04x-4%03x-8%03x-%04x%08x' $((RANDOM*RANDOM)) $RANDOM $((RANDOM%4096)) $((RANDOM%4096)) $RANDOM $((RANDOM*RANDOM)))
  key="AWSLogs/123456789012/CloudTrail/us-east-1/$ymd/123456789012_CloudTrail_us-east-1_${stamp}_${unique}.json.gz"
  code_t=$(printf '{"Records":[{"eventVersion":"1.11","userIdentity":{"type":"AWSService","invokedBy":"lifecycle.s3.amazonaws.com"},"eventTime":"%s","eventSource":"s3lifecycle.amazonaws.com","eventName":"DeleteObject","awsRegion":"us-east-1","sourceIPAddress":"lifecycle.s3.amazonaws.com","requestParameters":{"bucketName":"%s","key":"raw/e2e/expired.parquet"},"responseElements":null,"requestID":"E2ERET08REQ0001","eventID":"%s","readOnly":false,"resources":[{"type":"AWS::S3::Object","ARN":"arn:aws:s3:::%s/raw/e2e/expired.parquet"}],"eventType":"AwsServiceEvent","managementEvent":false,"recipientAccountId":"123456789012","eventCategory":"Data"}]}' \
      "$ev_time" "$bucket" "$eid" "$bucket" | s3_put_gz "$trail/$key")
  say "[RETIRE] trail file $key -> HTTP $code_t"
  st=$(wait_rec "$rid" 'RULE_IN_FORCE|RULE_ENDED|RULE_ENDED_UNPROVEN|HARD_DELETED' 240)
  local d_p tasks row
  d_p=$(disp "$rid" "status||'|'||coalesce(proof_source,'')")
  tasks=$(curl -s "$AD_BASE/api/v1/deletion/tasks?size=200" -H "Authorization: Bearer $dtok" 2>/dev/null | tr -d '\r')
  row=$(printf '%s' "$tasks" | grep -oE "\{[^{}]*\"taskId\":\"$rid\"[^{}]*\}" | head -n1)
  if [[ "$code_t" == "200" && "$st" == "RULE_IN_FORCE" && "$d_p" == EXECUTED\|AWS_CLOUDTRAIL* \
        && -z "$(rec "$rid" "coalesce(hard_deleted_at::text,'')")" && "$row" == *'"status":"RULE_IN_FORCE"'* \
        && "$row" == *'"ruleEndsAt":"'* ]]; then T[rule_in_force]=1; fi
  say "[RETIRE] proof: record $st, disposition $d_p, task row ${row:0:200} -> ${T[rule_in_force]}"

  # rule_ended and rule_ended_unproven: past the maximum age, without an approval.
  local budget=$(( applied + RULE_MAX_AGE_MIN * 60 + 150 - $(date -u +%s) ))
  (( budget < 60 )) && budget=60
  say "[RETIRE] waiting up to ${budget}s for the maximum age to end both rules…"
  st=$(wait_rec "$rid" 'RULE_ENDED|RULE_ENDED_UNPROVEN|RESTORE_FAILED' "$budget")
  st_u=$(wait_rec "$rid_u" 'RULE_ENDED|RULE_ENDED_UNPROVEN|RESTORE_FAILED' 60)
  lc=$(lifecycle_of "$bucket"); lc_u=$(lifecycle_of "$ubucket")
  local ended_req ended_ok unproven_req unproven_ok unproven_warned=0
  ended_req=$(audit_count retire.delete retire.delete.rule_ended REQUESTED "$rid")
  ended_ok=$(audit_count retire.delete retire.delete.rule_ended SUCCEEDED "$rid")
  unproven_req=$(audit_count retire.delete retire.delete.rule_ended_unproven REQUESTED "$rid_u")
  unproven_ok=$(audit_count retire.delete retire.delete.rule_ended_unproven SUCCEEDED "$rid_u")
  harness_log_has dlap-da-autodelete "is RULE_ENDED_UNPROVEN" && unproven_warned=1
  if [[ "$st" == "RULE_ENDED" && -n "$(disp "$rid" "coalesce(rule_removed_at::text,'')")" && "$lc" == *NoSuchLifecycleConfiguration* \
        && "${ended_req:-0}" =~ ^[1-9] && "${ended_ok:-0}" =~ ^[1-9] ]]; then T[rule_ended]=1; fi
  if [[ "$st_u" == "RULE_ENDED_UNPROVEN" && "$lc_u" == *NoSuchLifecycleConfiguration* && "${unproven_req:-0}" =~ ^[1-9] \
        && "${unproven_ok:-0}" =~ ^[1-9] && "$unproven_warned" == 1 ]]; then T[rule_ended_unproven]=1; fi
  say "[RETIRE] end: record $st (rule_ended REQUESTED ${ended_req:-0}, SUCCEEDED ${ended_ok:-0}) -> ${T[rule_ended]}; unproven copy $st_u (rule_ended_unproven REQUESTED ${unproven_req:-0}, SUCCEEDED ${unproven_ok:-0}, WARN $unproven_warned) -> ${T[rule_ended_unproven]}; LocalStack lifecycle removed from both: $([[ "$lc$lc_u" == *NoSuchLifecycleConfiguration*NoSuchLifecycleConfiguration* ]] && echo yes || echo no)"
  return 0
}

if [[ "$PROVIDER" == "aws" && "${DLAP_E2E_AWS_RETIRE:-1}" != "0" ]]; then
  RETIRE_GATES=(versioned_refused withdrawn rule_apply rule_in_force rule_ended rule_ended_unproven)
  for g in "${RETIRE_GATES[@]}"; do T[$g]=0; done
  if [[ "${T[delete]}" =~ ^[0-9]+$ ]] && (( T[delete] > 0 )); then
    retire_phase || warn "[RETIRE] the retire phase stopped early; the gates below show how far it got"
  else
    warn "[RETIRE] no SOFT_DELETED record, so the retire phase cannot run"
  fi
fi

echo
echo "============== DLAP $PROVIDER full-lifecycle tally =============="
printf '  %-19s %-7s %s\n' STAGE COUNT GATE
for s in label policy held self_approval approval archive archive_loc delete report "${RETIRE_GATES[@]}"; do printf '  %-19s %-7s %s\n' "${s^^}" "${T[$s]}" gate; done
printf '  %-19s %-7s %s\n' COMPLIANCE "${T[compliance]}" info
echo "==============================================================="

MEM_OVER=0; mem_sampler_report || MEM_OVER=1
EMPTY=(); for g in label policy held self_approval approval archive archive_loc delete report "${RETIRE_GATES[@]}"; do v="${T[$g]:-0}"; [[ "$v" =~ ^[0-9]+$ ]] && (( v>0 )) || EMPTY+=("$g"); done
if (( ${#EMPTY[@]} == 0 && MEM_OVER == 1 )); then
  fail "[$PROVIDER] all gates fired but the lean memory budget was exceeded (see the peak report above)"
fi
if (( ${#EMPTY[@]} == 0 )); then
  say "PASS [$PROVIDER] - all gating stages fired (label/policy/held/self-approval/approval/archive/delete/report${RETIRE_GATES[*]:+ and the retire phase: ${RETIRE_GATES[*]}}); compliance=${T[compliance]}"
  exit 0
fi
fail "[$PROVIDER] empty gate(s): ${EMPTY[*]}. Inspect: '${COMPOSE[*]} logs --tail=200 <service>'"
