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
# Scale, soak and backfill resilience harness (E2E-02, decision D39).
#
#   bash e2e-tests/perf/run.sh lite       200k CloudTrail + 50k GCP + 50k Azure, then the backfill resume test
#   bash e2e-tests/perf/run.sh hosted     1M CloudTrail + 250k GCP + 250k Azure (the nightly workflow default,
#                                         sized for a hosted runner, see perf-lib.sh), then the backfill resume
#                                         test; DLAP_PERF_FLAWS=1 adds the flaws.cloud check
#   bash e2e-tests/perf/run.sh full       10M CloudTrail + 2M GCP + 2M Azure (manual dispatch on a larger runner),
#                                         then the backfill resume test; DLAP_PERF_FLAWS=1 adds flaws.cloud
#   bash e2e-tests/perf/run.sh backfill   only the backfill resume test
#   bash e2e-tests/perf/run.sh flaws      only the flaws.cloud check (nightly; DLAP_FLAWS_URL points a
#                                         self-test at a stand-in archive)
#   bash e2e-tests/perf/run.sh --down     tear the stack down and exit
#   options: --no-build (skip the Gradle jar build), --keep (leave the stack up)
#
# What it drives. The same spine as cloud-pipeline-e2e.sh (postgres, kafka, keycloak, da-logingest,
# da-discovery, da-catalog) on the registry pipeline, plus an S3 emulator:
#   CloudTrail  trail files in the emulator, read by the S3_TRAIL_FILES transport (the real stream path)
#   GCP, Azure  normalized events posted to the REST channel (POST /api/v1/logingest/events) from many
#               threads (the log layer has no offline raw transport for them: the GCS sink cannot be pointed
#               at an emulator and no Azure transport exists). The raw record rides along as rawEvent. The
#               single threaded fixture replay seeder is not used: it blocks application start, so the
#               CloudTrail streams could not run beside it.
# The generators are da-log-fixtures gen/scale (seeded, deterministic). Nothing is committed from them.
#
# What it records (measured, never claimed): throughput of stored events, consumer lag of every group on
# dlap.log.event.normalized, Postgres growth, Kafka partition skew, quarantine rate. A figure that a run
# did not produce is written as "not measured". Results go to e2e-tests/perf/results/<mode>-<date>.md and
# a samples CSV beside it (DLAP_PERF_RESULTS_DIR moves them).
#
# Backfill resume. A backfill job reads a trail written five days back (another account, so the stream's
# one day lookback never sees it) at a throttled rate; da-logingest is killed with SIGKILL partway, then
# started again. Afterwards the stored events must equal the generated ids exactly: none lost, none
# stored twice (counts by event id, and the set difference by native event id).
#
# Isolation (HARN-01, harness-lib.sh): the whole run holds the host-wide lock and runs in its own compose
# project. Exit status: 0 when every named check passed, 1 otherwise, 2 for a usage error.
# ---------------------------------------------------------------------------
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
E2E="$REPO/e2e-tests"
PERF="$E2E/perf"
DEV="$REPO/deployment/environments/dev"
# shellcheck source=../harness-lib.sh
source "$E2E/harness-lib.sh"
# shellcheck source=perf-lib.sh
source "$PERF/perf-lib.sh"

say(){ echo "[perf] $*"; }
warn(){ echo "[perf] WARN: $*" >&2; }
fail(){ echo "[perf] FAIL: $*" >&2; exit 1; }

usage() {
  sed -n '/^#   bash e2e-tests\/perf\/run.sh lite/,/^#   options:/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

MODE="${1:-}"
case "$MODE" in
  lite|hosted|full|backfill|flaws|--down) shift ;;
  -h|--help|help) usage; exit 0 ;;
  *) usage >&2; exit 2 ;;
esac
NO_BUILD=""; KEEP=""
for a in "$@"; do
  case "$a" in
    --no-build) NO_BUILD=1 ;;
    --keep) KEEP=1 ;;
    *) echo "unknown option '$a'" >&2; usage >&2; exit 2 ;;
  esac
done

COMPOSE=(docker compose \
  -f "$DEV/docker-compose.yml" \
  -f "$DEV/docker-compose.microservices.yml" \
  -f "$E2E/docker-compose.e2e.yml" \
  -f "$PERF/docker-compose.perf.yml")
SPINE=(postgresql kafka keycloak perf-localstack da-logingest da-discovery da-catalog)
TOPIC="dlap.log.event.normalized"
LOGINGEST_DB="da_logingest"
BF_BINDING="perf-ct-backfill"

# Work directory: generated data, the bindings file, logs. Kept on failure so a failed run can be read.
WORK="$(mktemp -d)"
winpath() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
PASSED_ALL=0
perf_cleanup() {
  if [[ -z "$KEEP" && "$MODE" != "--down" ]]; then
    "${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
  fi
  if [[ "$PASSED_ALL" == 1 ]]; then rm -rf "$WORK"; else echo "[perf] work directory kept: $WORK" >&2; fi
  harness_lock_release
}

# The compose files reference these for every call, including --down.
mkdir -p "$WORK/replay" "$WORK/config" "$WORK/logs"
export DLAP_E2E_FIXTURES_DIR; DLAP_E2E_FIXTURES_DIR="$(winpath "$WORK/replay")"
export DLAP_PERF_CONFIG_DIR; DLAP_PERF_CONFIG_DIR="$(winpath "$WORK/config")"
harness_init perf "$REPO"
trap perf_cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
export DLAP_PERF_S3_PORT="${DLAP_PERF_S3_PORT:-14566}"

if [[ "$MODE" == "--down" ]]; then
  say "tearing down the perf stack of project $COMPOSE_PROJECT_NAME"
  "${COMPOSE[@]}" down -v --remove-orphans
  PASSED_ALL=1; exit 0
fi

[[ "$MODE" == "lite" || "$MODE" == "hosted" || "$MODE" == "full" ]] && perf_profile "$MODE" || perf_profile lite
[[ "$MODE" == "hosted" ]] && PERF_SAMPLE_SECS="${PERF_SAMPLE_SECS_HOSTED:-30}"
[[ "$MODE" == "full" ]] && PERF_SAMPLE_SECS="${PERF_SAMPLE_SECS_FULL:-60}"
export DLAP_PERF_BF_BATCHES_PER_SEC="${DLAP_PERF_BF_BATCHES_PER_SEC:-$PERF_BF_BATCHES_PER_SEC}"
RESULTS_DIR="${DLAP_PERF_RESULTS_DIR:-$PERF/results}"
DATE="$(date -u +%F)"
mkdir -p "$RESULTS_DIR"
CSV="$RESULTS_DIR/$MODE-$DATE-samples.csv"
REPORT="$RESULTS_DIR/$MODE-$DATE.md"

JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
GEN_CP="$(winpath "$REPO/da-log-fixtures/build/classes/java/main")"
scale() { "$JAVA" -cp "$GEN_CP" com.dlap.fixtures.gen.scale.ScaleMain "$@"; }
S3_URL="http://localhost:${DLAP_PERF_S3_PORT}"

# ---- small helpers --------------------------------------------------------------------------------
pq() { docker exec dlap-postgresql psql -U dlap -d "$1" -tA -F, -c "$2" 2>/dev/null | tr -d '\r'; }
pq1() { pq "$1" "$2" | head -n1 | tr -d '[:space:]'; }
json_str() { local key="$1"; grep -oE "\"${key}\"[[:space:]]*:[[:space:]]*\"[^\"]*\"" | head -n1 \
    | sed -E "s/^\"${key}\"[[:space:]]*:[[:space:]]*\"(.*)\"$/\1/"; }
kafka_describe() { docker exec dlap-kafka kafka-consumer-groups --bootstrap-server kafka:29092 --describe --all-groups 2>/dev/null | tr -d '\r'; }
kafka_offsets() { docker exec dlap-kafka kafka-run-class kafka.tools.GetOffsetShell --bootstrap-server kafka:29092 \
    --topic "${1:-$TOPIC}" --time -1 2>/dev/null | tr -d '\r'; }
num() { [[ "${1:-}" =~ ^[0-9]+$ ]] && printf '%s' "$1" || printf '0'; }

CHECKS=()       # "PASS|name|detail" or "FAIL|name|detail"
check() { # check <name> <1|0> <detail>
  if [[ "$2" == 1 ]]; then CHECKS+=("PASS|$1|$3"); say "check PASS: $1 ($3)"; else CHECKS+=("FAIL|$1|$3"); say "check FAIL: $1 ($3)" >&2; fi
}

wait_stack_ready() {
  say "waiting for da-logingest to answer health…"
  local i
  for i in $(seq 1 90); do
    curl -sf "http://localhost:${DLAP_PORT_LOGINGEST}/actuator/health" >/dev/null 2>&1 && { say "da-logingest up (~$((i * 4))s)"; return 0; }
    sleep 4
  done
  warn "da-logingest health not confirmed after 6 minutes"; return 1
}

mint_token() { # an ADMIN access token (password grant on the dev realm, minted inside the compose network)
  local net; net="$(docker inspect -f '{{range $k,$v := .NetworkSettings.Networks}}{{$k}}{{end}}' dlap-postgresql 2>/dev/null | tr -d '\r')"
  [[ -n "$net" ]] || net="${COMPOSE_PROJECT_NAME}_dlap-network"
  docker run --rm --network "$net" curlimages/curl:8.11.1 -s \
    "http://keycloak:8080/realms/dlap/protocol/openid-connect/token" \
    -d grant_type=password -d client_id=dlap-backend -d "client_secret=${DLAP_BACKEND_CLIENT_SECRET:-change-me-dev-only}" \
    -d "username=${DLAP_ADMIN_USER:-admin}" -d "password=${DLAP_ADMIN_PASSWORD:-Password123!}" 2>/dev/null | json_str access_token
}

# The account of CloudTrail stream number $1: ten fixed digits and the index. Invented, like every id here.
ct_account() { printf '1234567890%02d' "$1"; }

# Writes the bindings file. $1 is extra YAML (indented bindings of the same source) appended to the CloudTrail source.
# The load modes get one stream binding per CloudTrail account (PERF_CT_ACCOUNTS): streams of different
# bindings are polled by different scheduler threads, so they read in parallel.
write_bindings() {
  local STREAMS="" i n="${PERF_CT_ACCOUNTS:-1}"
  [[ "$MODE" == "lite" || "$MODE" == "hosted" || "$MODE" == "full" ]] || n=0
  for ((i = 0; i < n; i++)); do
    STREAMS="$STREAMS
          - id: perf-ct-stream-$i
            scope: { account-id: \"$(ct_account "$i")\", regions: [us-east-1] }
            poll-interval: PT5S
            stream:
              transport: S3_TRAIL_FILES
              location: { bucket: dlap-perf-trail, region: us-east-1, endpoint: \"http://perf-localstack:4566\", path-style: \"true\" }"
  done
  cat >"$WORK/config/log-sources.yml" <<EOF
dlap:
  logingest:
    sources:
      - id: perf-aws-cloudtrail
        kind: AWS_CLOUDTRAIL
        bindings:
${STREAMS}
          - id: ${BF_BINDING}
            scope: { account-id: "111122223333", regions: [us-east-1] }
            poll-interval: PT5S
            backfill: { allowed: true }
            stream:
              transport: S3_TRAIL_FILES
              location: { bucket: dlap-perf-backfill, region: us-east-1, endpoint: "http://perf-localstack:4566", path-style: "true" }
${1:-}
EOF
}

up_logingest_side() { # up_logingest_side <replay true|false> <all|core|only-logingest>
  export DLAP_LOGINGEST_REPLAY_ENABLED="$1"
  case "${2:-all}" in
    only-logingest)   # a running stack: recreate the one service
      "${COMPOSE[@]}" up -d --no-deps --force-recreate da-logingest >"$WORK/logs/up-logingest.log" 2>&1 \
        || fail "recreating da-logingest failed (see $WORK/logs/up-logingest.log)" ;;
    core)             # keycloak and da-logingest only: the backfill and flaws modes
      "${COMPOSE[@]}" up -d --build --force-recreate keycloak da-logingest >"$WORK/logs/up-core.log" 2>&1 \
        || fail "compose up failed (see $WORK/logs/up-core.log)" ;;
    *)
      "${COMPOSE[@]}" up -d --build --force-recreate keycloak da-logingest da-discovery da-catalog >"$WORK/logs/up.log" 2>&1 \
        || fail "compose up failed (see $WORK/logs/up.log)" ;;
  esac
}

bf_count() { pq1 "$LOGINGEST_DB" "SELECT count(*) FROM dlap_logingest.log_events WHERE binding_id='$1'"; }

# ---------------------------------------------------------------------------------------------------
# Build and bring up the infrastructure
# ---------------------------------------------------------------------------------------------------
HOST_DESC="$(printf 'cpus=%s docker_mem=%s' "$(docker info --format '{{.NCPU}}' 2>/dev/null | tr -d '\r')" \
  "$(perf_bytes "$(num "$(docker info --format '{{.MemTotal}}' 2>/dev/null | tr -d '\r')")")")"
GIT_REV="$(git -C "$REPO" rev-parse --short HEAD 2>/dev/null || echo unknown)"
# A tracked file that differs from HEAD means the run measured code that is not in this commit.
[[ -z "$(git -C "$REPO" status --porcelain --untracked-files=no 2>/dev/null)" ]] || GIT_REV="$GIT_REV+uncommitted"
say "mode=$MODE project=$COMPOSE_PROJECT_NAME rev=$GIT_REV host: $HOST_DESC"

JARS=(:da-log-fixtures:classes)
[[ "$MODE" != "flaws" || -z "$NO_BUILD" ]] && JARS+=(:da-catalog:bootJar :da-logingest:bootJar :da-discovery:bootJar)
if [[ -z "$NO_BUILD" ]]; then
  say "building ${JARS[*]}…"
  "$REPO/gradlew" -p "$REPO" "${JARS[@]}" --no-daemon --console=plain --no-watch-fs --max-workers=2 \
      >"$WORK/logs/gradle.log" 2>&1 9>&- || { tail -n 30 "$WORK/logs/gradle.log" >&2; fail "jar build failed"; }
fi
[[ -d "$REPO/da-log-fixtures/build/classes/java/main/com/dlap/fixtures/gen/scale" ]] \
  || fail "the generator classes are missing; run without --no-build once"

say "clean slate, then infrastructure (postgresql, kafka, S3 emulator)…"
harness_reset_stack
export DLAP_LOGINGEST_REPLAY_ENABLED=false
"${COMPOSE[@]}" up -d postgresql kafka perf-localstack >"$WORK/logs/up-infra.log" 2>&1 \
    || fail "infrastructure failed to start (see $WORK/logs/up-infra.log)"
for i in $(seq 1 60); do
  curl -sf "$S3_URL/_localstack/health" >/dev/null 2>&1 && break
  sleep 3
  [[ $i -eq 60 ]] && fail "the S3 emulator did not answer on $S3_URL"
done
# postgresql restarts once on a first start (its init scripts run on a temporary server), and compose refuses to
# start a service that depends on an unhealthy one, so wait until both are healthy.
for c in dlap-postgresql dlap-kafka; do
  for i in $(seq 1 100); do
    [[ "$(docker inspect -f '{{.State.Health.Status}}' "$c" 2>/dev/null | tr -d '\r')" == "healthy" ]] && break
    sleep 3
    [[ $i -eq 100 ]] && fail "$c did not become healthy"
  done
done
# Both buckets of the bindings file exist from the start, so a mode that loads only one of them does not
# make the other stream poll a bucket that is not there.
for b in dlap-perf-trail dlap-perf-backfill; do curl -s -o /dev/null -X PUT "$S3_URL/$b" || fail "could not create bucket $b"; done

# ---------------------------------------------------------------------------------------------------
# Generate and load the data
# ---------------------------------------------------------------------------------------------------
NOW_EPOCH="$(date -u +%s)"
END_MAIN="$(date -u -d "@$((NOW_EPOCH - 900))" +%Y-%m-%dT%H:%M:%SZ)"
END_BF="$(date -u -d "@$((NOW_EPOCH - 5 * 86400))" +%Y-%m-%dT%H:%M:%SZ)"
kv() { grep "^$1=" | head -n1 | cut -d= -f2-; }
pv() { num "$(kv "$2" <"$WORK/logs/post-$1.txt" 2>/dev/null)"; }  # pv <gcp|azure> <key>: a figure of the REST poster output

GEN_SECONDS=0
if [[ "$MODE" == "lite" || "$MODE" == "hosted" || "$MODE" == "full" ]]; then
  g0=$SECONDS
  (( PERF_CT_COUNT % PERF_CT_ACCOUNTS == 0 )) || fail "PERF_CT_COUNT ($PERF_CT_COUNT) must divide evenly over PERF_CT_ACCOUNTS ($PERF_CT_ACCOUNTS)"
  mkdir -p "$WORK/rest"
  say "generating GCP ($PERF_GCP_COUNT) and Azure ($PERF_AZURE_COUNT) events for the REST channel…"
  scale replay --provider gcp --seed "$PERF_SEED" --count "$PERF_GCP_COUNT" --end "$END_MAIN" --format lines \
      --span-hours "$PERF_SPAN_HOURS" --per-file "$PERF_REPLAY_PER_FILE" --out-dir "$(winpath "$WORK/rest")" >"$WORK/logs/gen-gcp.txt" || fail "GCP generation failed"
  scale replay --provider azure --seed "$((PERF_SEED + 2))" --count "$PERF_AZURE_COUNT" --end "$END_MAIN" --format lines \
      --span-hours "$PERF_SPAN_HOURS" --per-file "$PERF_REPLAY_PER_FILE" --out-dir "$(winpath "$WORK/rest")" >"$WORK/logs/gen-azure.txt" || fail "Azure generation failed"
  say "generating and uploading CloudTrail trail files ($PERF_CT_COUNT records over $PERF_CT_ACCOUNTS accounts, $PERF_CT_PER_FILE per file)…"
  CT_FILES=0; CT_BYTES=0
  for ((a = 0; a < PERF_CT_ACCOUNTS; a++)); do
    scale trail --seed "$((PERF_SEED + 10 + a))" --count "$((PERF_CT_COUNT / PERF_CT_ACCOUNTS))" --end "$END_MAIN" \
        --span-hours "$PERF_SPAN_HOURS" --account "$(ct_account "$a")" --region us-east-1 --per-file "$PERF_CT_PER_FILE" \
        --endpoint "$S3_URL" --bucket dlap-perf-trail --threads 4 >"$WORK/logs/gen-ct-$a.txt" || fail "CloudTrail generation or upload failed"
    CT_FILES=$((CT_FILES + $(num "$(kv files <"$WORK/logs/gen-ct-$a.txt")")))
    CT_BYTES=$((CT_BYTES + $(num "$(kv bytes <"$WORK/logs/gen-ct-$a.txt")")))
  done
  GEN_SECONDS=$((SECONDS - g0))
  say "generated and loaded in ${GEN_SECONDS}s: $CT_FILES trail files, $(perf_bytes "$CT_BYTES") gzip"
fi

if [[ "$MODE" != "flaws" ]]; then
  say "generating and uploading the backfill trail ($PERF_BF_COUNT records, dated five days back)…"
  scale trail --seed "$((PERF_SEED + 1))" --count "$PERF_BF_COUNT" --end "$END_BF" --span-hours 6 --account 111122223333 \
      --region us-east-1 --per-file "$PERF_BF_PER_FILE" --endpoint "$S3_URL" --bucket dlap-perf-backfill \
      --ids-out "$(winpath "$WORK/bf-ids.txt")" >"$WORK/logs/gen-bf.txt" || fail "backfill trail generation failed"
  BF_FROM="$(date -u -d "$(kv eventsFrom <"$WORK/logs/gen-bf.txt") - 1 hour" +%Y-%m-%dT%H:%M:%SZ)"
  BF_TO="$(date -u -d "$(kv deliveredLast <"$WORK/logs/gen-bf.txt") + 1 hour" +%Y-%m-%dT%H:%M:%SZ)"
  BF_FILES="$(kv files <"$WORK/logs/gen-bf.txt")"
fi
write_bindings ""

# ---------------------------------------------------------------------------------------------------
# Phase A: load
# ---------------------------------------------------------------------------------------------------
PG_BASE_LOGINGEST=""; PG_BASE_COMMON=""
LOAD_DONE=0; STORED=0; LAG_FINAL=0; LAG_PEAK=0
SKEW=""; TOPIC_TOTAL=""; PG_AFTER_LOGINGEST=""; PG_AFTER_COMMON=""; LOGEVENTS_BYTES=""; QUARANTINED=0
RATES=""; GROUP_LAGS=""; DLT_TOTAL=""; POST_SUMMARY=""; POST_SECONDS=""

phase_load() {
  PG_BASE_LOGINGEST="$(pq1 "$LOGINGEST_DB" "SELECT pg_database_size('$LOGINGEST_DB')")"
  PG_BASE_COMMON="$(pq1 dlap_common "SELECT pg_database_size('dlap_common')")"
  say "baseline sizes: $LOGINGEST_DB=$(perf_bytes "$(num "$PG_BASE_LOGINGEST")") dlap_common=$(perf_bytes "$(num "$PG_BASE_COMMON")")"
  say "starting keycloak, da-logingest, da-discovery, da-catalog…"
  up_logingest_side false all
  wait_stack_ready || fail "da-logingest did not become healthy"
  # GCP and Azure events go to the REST channel from many threads, beside the CloudTrail streams that the
  # scheduler threads of da-logingest are already polling.
  local p s posters=()
  for p in gcp azure; do
    ( DLAP_PERF_CLIENT_SECRET="${DLAP_BACKEND_CLIENT_SECRET:-change-me-dev-only}" DLAP_PERF_PASSWORD="${DLAP_ADMIN_PASSWORD:-Password123!}" \
      scale post --dir "$(winpath "$WORK/rest")" --prefix "$p" --threads "$PERF_POST_THREADS" \
        --url "http://localhost:${DLAP_PORT_LOGINGEST}/api/v1/logingest/events" \
        --token-url "http://localhost:${DLAP_PORT_KEYCLOAK}/realms/dlap/protocol/openid-connect/token" \
        --client-id dlap-backend --user "${DLAP_ADMIN_USER:-admin}" >"$WORK/logs/post-$p.txt" 2>"$WORK/logs/post-$p.err" ) &
    posters+=($!)
  done
  local t0=$SECONDS deadline=$((SECONDS + PERF_TIMEOUT_MIN * 60)) last_stored=-1 last_change=$SECONDS drained=0 quiet=0
  echo "elapsed_s,stored,aws,gcp,azure,lag_max,lag_sum,pg_bytes" >"$CSV"
  while (( SECONDS < deadline )); do
    sleep "$PERF_SAMPLE_SECS"
    local row stored aws gcp azure lagout lagmax lagsum pg
    row="$(pq "$LOGINGEST_DB" "SELECT count(*), count(*) FILTER (WHERE source_provider='AWS'), count(*) FILTER (WHERE source_provider='GCP'), count(*) FILTER (WHERE source_provider='AZURE') FROM dlap_logingest.log_events")"
    IFS=, read -r stored aws gcp azure <<<"$row"
    stored="$(num "$stored")"; aws="$(num "$aws")"; gcp="$(num "$gcp")"; azure="$(num "$azure")"
    lagout="$(kafka_describe | perf_lag_by_group "$TOPIC")"
    lagmax="$(printf '%s\n' "$lagout" | perf_lag_max)"; lagsum="$(printf '%s\n' "$lagout" | awk '{ s += $2 } END { print s + 0 }')"
    pg="$(num "$(pq1 "$LOGINGEST_DB" "SELECT pg_database_size('$LOGINGEST_DB')")")"
    echo "$((SECONDS - t0)),$stored,$aws,$gcp,$azure,$lagmax,$lagsum,$pg" >>"$CSV"
    say "t=$((SECONDS - t0))s stored=$stored/$PERF_TOTAL (aws=$aws gcp=$gcp azure=$azure) lag_max=$lagmax"
    if (( stored != last_stored )); then last_stored=$stored; last_change=$SECONDS; fi
    # Caught up means every consumer group of the topic reports and none has lag: the router, the
    # lineage listener and the read coverage listener. A group that has not started is no lag, so it is counted.
    local groups; groups="$(printf '%s\n' "$lagout" | grep -c .)"
    if (( stored >= PERF_TOTAL && lagmax == 0 && groups >= ${PERF_LAG_GROUPS:-3} )); then
      quiet=$((quiet + 1)); (( quiet >= 2 )) && { drained=1; break; }
    else quiet=0; fi
    if (( SECONDS - last_change > ${PERF_STALL_MIN:-12} * 60 && stored < PERF_TOTAL )); then
      warn "no new stored event for ${PERF_STALL_MIN:-12} minutes (stored=$stored of $PERF_TOTAL)"; break
    fi
  done
  LOAD_DONE=$drained
  # The posters have finished when everything is stored; after a timeout or a stall they are stopped.
  if (( drained == 1 )); then wait "${posters[@]}" 2>/dev/null; else kill "${posters[@]}" 2>/dev/null; wait "${posters[@]}" 2>/dev/null; fi
  local sent=0 acc=0 dup=0 rej=0 fl=0 psec=0
  for p in gcp azure; do
    sent=$((sent + $(pv "$p" sent))); acc=$((acc + $(pv "$p" accepted))); dup=$((dup + $(pv "$p" duplicates)))
    rej=$((rej + $(pv "$p" rejected))); fl=$((fl + $(pv "$p" failed)))
    s=$(pv "$p" seconds); (( s > psec )) && psec=$s
  done
  POST_SUMMARY="sent=$sent accepted=$acc duplicates=$dup rejected=$rej failed=$fl"; POST_SECONDS="$psec"
  STORED="$(num "$(pq1 "$LOGINGEST_DB" "SELECT count(*) FROM dlap_logingest.log_events")")"
  RATES="$(perf_rates "$CSV" "$PERF_TOTAL" 2>/dev/null)"
  LAG_PEAK="$(perf_col_max "$CSV" 6)"
  lagout="$(kafka_describe | perf_lag_by_group "$TOPIC")"
  GROUP_LAGS="$(printf '%s\n' "$lagout" | awk '{ printf "%s%s=%s", (NR > 1 ? ", " : ""), $1, $2 }')"
  LAG_FINAL="$(printf '%s\n' "$lagout" | perf_lag_max)"
  SKEW="$(kafka_offsets "$TOPIC" | perf_skew 2>/dev/null)"
  TOPIC_TOTAL="$(kafka_offsets "$TOPIC" | perf_topic_total)"
  PG_AFTER_LOGINGEST="$(pq1 "$LOGINGEST_DB" "SELECT pg_database_size('$LOGINGEST_DB')")"
  PG_AFTER_COMMON="$(pq1 dlap_common "SELECT pg_database_size('dlap_common')")"
  LOGEVENTS_BYTES="$(pq1 "$LOGINGEST_DB" "SELECT coalesce(sum(pg_total_relation_size(inhrelid)),0) FROM pg_inherits WHERE inhparent='dlap_logingest.log_events'::regclass")"
  QUARANTINED="$(num "$(pq1 "$LOGINGEST_DB" "SELECT count(*) FROM dlap_logingest.quarantine_record")")"
  DLT_TOTAL="$(for t in $(docker exec dlap-kafka kafka-topics --bootstrap-server kafka:29092 --list 2>/dev/null | tr -d '\r' | grep -E '\.DLT$'); do kafka_offsets "$t"; done | perf_topic_total)"

  local by_provider distinct
  by_provider="$(pq "$LOGINGEST_DB" "SELECT count(*) FILTER (WHERE source_provider='AWS'), count(*) FILTER (WHERE source_provider='GCP'), count(*) FILTER (WHERE source_provider='AZURE') FROM dlap_logingest.log_events")"
  distinct="$(num "$(pq1 "$LOGINGEST_DB" "SELECT count(DISTINCT event_id) FROM dlap_logingest.log_events")")"
  check "load_all_events_stored" "$(( STORED == PERF_TOTAL ))" "stored $STORED of $PERF_TOTAL"
  check "load_by_provider" "$([[ "$by_provider" == "$PERF_CT_COUNT,$PERF_GCP_COUNT,$PERF_AZURE_COUNT" ]] && echo 1 || echo 0)" "aws,gcp,azure = $by_provider, wanted $PERF_CT_COUNT,$PERF_GCP_COUNT,$PERF_AZURE_COUNT"
  check "load_event_ids_unique" "$(( distinct == STORED ))" "$distinct distinct ids in $STORED rows"
  local qpct; qpct="$(perf_pct "$QUARANTINED" "$((STORED + QUARANTINED))")"
  check "load_quarantine_rate_under_1_percent" "$(awk -v q="$qpct" 'BEGIN { print (q + 0 < 1.0) ? 1 : 0 }')" "$QUARANTINED quarantined = $qpct percent"
  check "load_consumers_caught_up" "$(( drained == 1 && LAG_FINAL == 0 ))" "final lag per group: ${GROUP_LAGS:-none}"
  check "load_rest_pushes_all_accepted" "$(( fl == 0 && rej == 0 && acc + dup == PERF_GCP_COUNT + PERF_AZURE_COUNT ))" "$POST_SUMMARY"
}

# ---------------------------------------------------------------------------------------------------
# Phase B: backfill resume
# ---------------------------------------------------------------------------------------------------
BF_KILLED_AT=""; BF_FINAL=""; BF_DISTINCT=""; BF_DISTINCT_NATIVE=""; BF_DIFF=""; BF_STATUS=""; BF_TOPIC_DELTA=""
BF_JOB=""; BF_PROGRESS=""; BF_RESUME_SECS=""; BF_ORIGIN=""; BF_RESUMED_AFTER=""

phase_backfill() {
  say "backfill resume: starting da-logingest with the replay off…"
  if [[ "$MODE" == "backfill" ]]; then up_logingest_side false core; else up_logingest_side false only-logingest; fi
  wait_stack_ready || fail "da-logingest did not come back before the backfill test"
  local pre; pre="$(num "$(bf_count "$BF_BINDING")")"
  check "backfill_stream_does_not_read_history" "$(( pre == 0 ))" "$pre events of $BF_BINDING before the job (the stream looks back one day only)"
  local topic_before; topic_before="$(kafka_offsets "$TOPIC" | perf_topic_total)"
  local token resp code body
  token="$(mint_token)"; [[ -n "$token" ]] || fail "could not obtain an ADMIN token from keycloak"
  resp="$(curl -s -w $'\n%{http_code}' -X POST "http://localhost:${DLAP_PORT_LOGINGEST}/api/v1/logingest/backfills" \
      -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
      -d "{\"bindingId\":\"$BF_BINDING\",\"from\":\"$BF_FROM\",\"to\":\"$BF_TO\"}" 2>/dev/null | tr -d '\r')"
  code="$(printf '%s' "$resp" | tail -n1)"; body="$(printf '%s' "$resp" | sed '$d')"
  [[ "$code" == "201" ]] || fail "POST /backfills answered HTTP $code: ${body:0:300}"
  BF_JOB="$(printf '%s' "$body" | json_str id)"
  say "backfill job $BF_JOB accepted for [$BF_FROM, $BF_TO): $PERF_BF_COUNT events in $BF_FILES files at $PERF_BF_BATCHES_PER_SEC files per second"

  local want=$((PERF_BF_COUNT * 25 / 100)) n=0 dl=$((SECONDS + 600)) st=""
  while (( SECONDS < dl )); do
    n="$(num "$(bf_count "$BF_BINDING")")"
    st="$(pq1 "$LOGINGEST_DB" "SELECT status FROM dlap_logingest.backfill_job WHERE id='$BF_JOB'")"
    (( n >= want )) && break
    [[ "$st" == "SUCCEEDED" || "$st" == "FAILED" ]] && break
    sleep 1
  done
  [[ "$st" == "RUNNING" && n -ge want ]] || fail "the job was $st with $n events when the kill point (${want}) was due; it must be RUNNING"
  say "killing da-logingest with SIGKILL at $n stored events (job $st)…"
  docker update --restart=no dlap-da-logingest >/dev/null 2>&1 || warn "could not disable the restart policy"
  docker kill dlap-da-logingest >/dev/null 2>&1 || fail "docker kill failed"
  sleep 4
  BF_KILLED_AT="$(num "$(bf_count "$BF_BINDING")")"
  local st_dead; st_dead="$(pq1 "$LOGINGEST_DB" "SELECT status FROM dlap_logingest.backfill_job WHERE id='$BF_JOB'")"
  check "backfill_killed_midway" "$(( BF_KILLED_AT > 0 && BF_KILLED_AT < PERF_BF_COUNT ))" "$BF_KILLED_AT of $PERF_BF_COUNT stored when the process died, job $st_dead"
  check "backfill_job_survives_the_kill" "$([[ "$st_dead" == "RUNNING" ]] && echo 1 || echo 0)" "job status after the kill: $st_dead"

  local r0=$SECONDS
  say "starting da-logingest again…"
  docker start dlap-da-logingest >/dev/null 2>&1 || fail "docker start failed"
  dl=$((SECONDS + 900))
  while (( SECONDS < dl )); do
    BF_STATUS="$(pq1 "$LOGINGEST_DB" "SELECT status FROM dlap_logingest.backfill_job WHERE id='$BF_JOB'")"
    [[ "$BF_STATUS" == "SUCCEEDED" || "$BF_STATUS" == "FAILED" || "$BF_STATUS" == "CANCELLED" ]] && break
    sleep 5
  done
  BF_RESUME_SECS=$((SECONDS - r0))
  local started runs
  started="$(docker inspect -f '{{.State.StartedAt}}' dlap-da-logingest 2>/dev/null | tr -d '\r')"
  runs="$(docker logs --timestamps dlap-da-logingest 2>&1 | grep "Backfill job $BF_JOB runs" | tail -n1 | cut -d' ' -f1)"
  if [[ -n "$started" && -n "$runs" ]]; then BF_RESUMED_AFTER=$(( $(date -u -d "$runs" +%s) - $(date -u -d "$started" +%s) )); fi
  BF_PROGRESS="$(pq1 "$LOGINGEST_DB" "SELECT progress::text FROM dlap_logingest.backfill_job WHERE id='$BF_JOB'")"
  BF_FINAL="$(num "$(bf_count "$BF_BINDING")")"
  BF_DISTINCT="$(num "$(pq1 "$LOGINGEST_DB" "SELECT count(DISTINCT event_id) FROM dlap_logingest.log_events WHERE binding_id='$BF_BINDING'")")"
  BF_DISTINCT_NATIVE="$(num "$(pq1 "$LOGINGEST_DB" "SELECT count(DISTINCT native_event_id) FROM dlap_logingest.log_events WHERE binding_id='$BF_BINDING'")")"
  BF_ORIGIN="$(pq "$LOGINGEST_DB" "SELECT DISTINCT origin FROM dlap_logingest.log_events WHERE binding_id='$BF_BINDING'" | tr '\n' ' ')"
  pq "$LOGINGEST_DB" "SELECT native_event_id FROM dlap_logingest.log_events WHERE binding_id='$BF_BINDING'" | LC_ALL=C sort >"$WORK/bf-stored.txt"
  tr -d '\015' <"$WORK/bf-ids.txt" | LC_ALL=C sort >"$WORK/bf-expected.txt"
  BF_DIFF="$(LC_ALL=C comm -3 "$WORK/bf-expected.txt" "$WORK/bf-stored.txt" | wc -l | tr -d '[:space:]')"
  BF_TOPIC_DELTA="$(( $(kafka_offsets "$TOPIC" | perf_topic_total) - topic_before ))"

  check "backfill_job_succeeds_after_restart" "$([[ "$BF_STATUS" == "SUCCEEDED" ]] && echo 1 || echo 0)" "job $BF_STATUS ${BF_RESUME_SECS}s after the restart"
  check "backfill_no_record_lost" "$(( BF_FINAL == PERF_BF_COUNT ))" "$BF_FINAL events stored, $PERF_BF_COUNT generated"
  check "backfill_no_record_duplicated" "$(( BF_DISTINCT == BF_FINAL && BF_DISTINCT_NATIVE == BF_FINAL ))" "$BF_FINAL rows, $BF_DISTINCT distinct event_id, $BF_DISTINCT_NATIVE distinct native_event_id"
  check "backfill_stored_set_equals_generated_set" "$(( BF_DIFF == 0 ))" "$BF_DIFF native event ids differ between the generator's list and the table"
}

# ---------------------------------------------------------------------------------------------------
# Phase C: flaws.cloud (nightly only)
# ---------------------------------------------------------------------------------------------------
FLAWS_FILES=""; FLAWS_STORED=""; FLAWS_QUAR=""; FLAWS_SHA=""; FLAWS_UNMAPPED=""; FLAWS_URL_USED=""

phase_flaws() {
  local pin="$PERF/flaws-cloud.pin" url size sha
  url="${DLAP_FLAWS_URL:-$(grep '^url=' "$pin" | cut -d= -f2-)}"
  size="${DLAP_FLAWS_SIZE:-$(grep '^size=' "$pin" | cut -d= -f2-)}"
  sha="${DLAP_FLAWS_SHA256:-$(grep '^sha256=' "$pin" | cut -d= -f2-)}"
  FLAWS_URL_USED="$url"
  say "flaws.cloud logs: fetching $url at run time (not stored in the repository)…"
  local fetched; fetched="$(perf_fetch_verified "$url" "$WORK/flaws.tar" "$size" "$sha" "${DLAP_FLAWS_ALLOW_UNPINNED:-0}")" \
    || fail "the flaws.cloud archive failed its size or checksum check"
  FLAWS_SHA="${fetched#sha256=}"
  say "archive sha256=$FLAWS_SHA"
  if tar -tf "$WORK/flaws.tar" | grep -Eq '(^/|(^|/)\.\.(/|$))'; then fail "the archive holds an absolute or parent path; refusing to extract"; fi
  mkdir -p "$WORK/flaws"
  tar -xf "$WORK/flaws.tar" -C "$WORK/flaws" || fail "could not extract the archive"
  scale flaws --tar-dir "$(winpath "$WORK/flaws")" --endpoint "$S3_URL" --bucket dlap-perf-flaws --threads 4 >"$WORK/logs/flaws-upload.txt" \
    || fail "placing the archive files failed"
  FLAWS_FILES="$(kv files <"$WORK/logs/flaws-upload.txt")"; FLAWS_UNMAPPED="$(kv unmapped <"$WORK/logs/flaws-upload.txt")"
  local accounts first last
  accounts="$(kv accounts <"$WORK/logs/flaws-upload.txt")"; first="$(kv deliveredFirst <"$WORK/logs/flaws-upload.txt")"
  last="$(kv deliveredLast <"$WORK/logs/flaws-upload.txt")"
  # Files whose names are not CloudTrail's (a README, a manifest) cannot be placed and are not log data; more
  # than one percent of the archive would mean the naming is not what this harness assumes.
  check "flaws_files_placed" "$(( $(num "$FLAWS_FILES") > 0 && $(num "$FLAWS_UNMAPPED") * 100 <= $(num "$FLAWS_FILES") ))" "$FLAWS_FILES files placed under trail keys, $FLAWS_UNMAPPED with a name that is not CloudTrail's (at most 1 percent allowed)"
  [[ -n "$accounts" ]] || fail "no CloudTrail-named file in the archive"

  local extra="" a
  for a in ${accounts//,/ }; do
    extra="$extra
          - id: perf-flaws-$a
            scope: { account-id: \"$a\" }
            poll-interval: P30D
            backfill: { allowed: true }
            stream:
              transport: S3_TRAIL_FILES
              location: { bucket: dlap-perf-flaws, endpoint: \"http://perf-localstack:4566\", path-style: \"true\" }"
  done
  write_bindings "$extra"
  export DLAP_PERF_BF_BATCHES_PER_SEC=50 DLAP_PERF_BF_MAX_WINDOW=P3650D
  if [[ "$MODE" == "flaws" ]]; then up_logingest_side false core; else up_logingest_side false only-logingest; fi
  wait_stack_ready || fail "da-logingest did not come back for the flaws.cloud check"
  local from to token job st dl
  from="$(date -u -d "$first - 1 hour" +%Y-%m-%dT%H:%M:%SZ)"; to="$(date -u -d "$last + 1 day" +%Y-%m-%dT%H:%M:%SZ)"
  # A job window may not end in the future (a stand-in archive made today ends close to now).
  if (( $(date -u -d "$to" +%s) > $(date -u +%s) - 60 )); then to="$(date -u -d "@$(( $(date -u +%s) - 60 ))" +%Y-%m-%dT%H:%M:%SZ)"; fi
  for a in ${accounts//,/ }; do
    token="$(mint_token)"; [[ -n "$token" ]] || fail "could not obtain an ADMIN token"
    job="$(curl -s -X POST "http://localhost:${DLAP_PORT_LOGINGEST}/api/v1/logingest/backfills" -H "Authorization: Bearer $token" \
        -H 'Content-Type: application/json' -d "{\"bindingId\":\"perf-flaws-$a\",\"from\":\"$from\",\"to\":\"$to\"}" | json_str id)"
    [[ -n "$job" ]] || fail "the flaws backfill job for account $a was not accepted"
    dl=$((SECONDS + ${PERF_FLAWS_TIMEOUT_MIN:-120} * 60)); st=""
    while (( SECONDS < dl )); do
      st="$(pq1 "$LOGINGEST_DB" "SELECT status FROM dlap_logingest.backfill_job WHERE id='$job'")"
      [[ "$st" == "SUCCEEDED" || "$st" == "FAILED" || "$st" == "CANCELLED" ]] && break
      sleep 10
    done
    check "flaws_backfill_job_succeeds" "$([[ "$st" == "SUCCEEDED" ]] && echo 1 || echo 0)" "account $a job $st"
  done
  FLAWS_STORED="$(num "$(pq1 "$LOGINGEST_DB" "SELECT count(*) FROM dlap_logingest.log_events WHERE binding_id LIKE 'perf-flaws-%'")")"
  FLAWS_QUAR="$(num "$(pq1 "$LOGINGEST_DB" "SELECT count(*) FROM dlap_logingest.quarantine_record WHERE binding_id LIKE 'perf-flaws-%'")")"
  local pct; pct="$(perf_pct "$FLAWS_QUAR" "$((FLAWS_STORED + FLAWS_QUAR))")"
  local under; under="$(awk -v q="$pct" 'BEGIN { print (q + 0 < 1.0) ? 1 : 0 }')"
  check "flaws_quarantine_rate_under_1_percent" "$(( FLAWS_STORED > 0 && under == 1 ))" "$FLAWS_QUAR quarantined of $((FLAWS_STORED + FLAWS_QUAR)) records = $pct percent"
}

# ---------------------------------------------------------------------------------------------------
# Run the phases, write the results
# ---------------------------------------------------------------------------------------------------
case "$MODE" in
  lite|hosted|full) phase_load; phase_backfill
             [[ "${DLAP_PERF_FLAWS:-0}" == 1 ]] && phase_flaws ;;
  backfill)  phase_backfill ;;
  flaws)     phase_flaws ;;
esac

fig() { perf_fig "$@"; }
rate() { printf '%s\n' "$RATES" | kv "$1"; }
skew() { printf '%s\n' "$SKEW" | kv "$1"; }
growth() { # growth <after> <before>
  [[ -n "$1" && -n "$2" ]] && perf_bytes "$(( $1 - $2 ))" || true; }
perevent=""
[[ "$STORED" -gt 0 && -n "$LOGEVENTS_BYTES" ]] && perevent="$(awk -v b="$LOGEVENTS_BYTES" -v n="$STORED" 'BEGIN { printf "%.0f", b / n }')"

{
  echo "# Scale and soak results: $MODE, $DATE"
  echo
  echo "Measured by \`bash e2e-tests/perf/run.sh $MODE\` on $(date -u +%FT%TZ) at commit \`$GIT_REV\`. A figure the run did not produce reads \`not measured\`; nothing here is an estimate."
  echo
  echo "## Environment"
  echo
  echo "- Host: $HOST_DESC (Docker Desktop or the CI runner; one machine runs the load generator, the stack and the emulator)"
  echo "- Stack: postgresql, kafka (KRaft, 3 partitions per topic), keycloak, da-logingest (registry pipeline), da-discovery, da-catalog, S3 emulator; compose project \`$COMPOSE_PROJECT_NAME\`"
  echo "- Seed: $PERF_SEED. The generators are deterministic: the same seed, counts and end time give the same records."
  if [[ "$MODE" == "lite" || "$MODE" == "hosted" || "$MODE" == "full" ]]; then
    echo
    echo "## Load parameters"
    echo
    echo "| Source | Records | Path into the log layer |"
    echo "|---|---|---|"
    echo "| AWS CloudTrail | $PERF_CT_COUNT | $PERF_CT_ACCOUNTS accounts, $CT_FILES trail files ($PERF_CT_PER_FILE records each) in the S3 emulator, one \`S3_TRAIL_FILES\` stream per account (the production path) |"
    echo "| GCP audit | $PERF_GCP_COUNT | REST channel \`POST /api/v1/logingest/events\`, $PERF_POST_THREADS threads (no offline raw transport exists) |"
    echo "| Azure activity | $PERF_AZURE_COUNT | REST channel \`POST /api/v1/logingest/events\`, $PERF_POST_THREADS threads (no Azure transport exists) |"
    echo
    echo "Event times span the $PERF_SPAN_HOURS hours before the run. Generation and upload took $(fig "$GEN_SECONDS" s) and are not part of the throughput. Settings that shape the numbers: $PERF_CT_ACCOUNTS CloudTrail stream bindings polled by ${DLAP_PERF_SCHED_POOL:-8} scheduler threads, $PERF_POST_THREADS REST threads for each of GCP and Azure, a database connection pool of ${DLAP_PERF_DB_POOL:-40}."
    echo
    echo "## Measured"
    echo
    echo "| Measure | Result |"
    echo "|---|---|"
    echo "| Events stored | $(fig "$STORED") of $PERF_TOTAL |"
    echo "| Time to first stored event | $(fig "$(rate first_s)" s) after da-logingest was healthy and the load began |"
    echo "| Time until every event was stored | $(fig "$(rate done_s)" s) |"
    echo "| Throughput, end to end (stored events over that time, JVM start and migrations included) | $(fig "$(rate overall_eps)" 'events/s') |"
    echo "| Throughput, median over $(fig "$(rate window_s)" s) windows | $(fig "$(rate median_eps)" 'events/s') |"
    echo "| Throughput, best window | $(fig "$(rate peak_eps)" 'events/s') |"
    echo "| Consumer lag on \`$TOPIC\`, peak of the worst group | $(fig "$LAG_PEAK" messages) |"
    echo "| Consumer lag at the end, per group | $(fig "$GROUP_LAGS") |"
    echo "| Postgres growth, \`$LOGINGEST_DB\` | $(fig "$(growth "$PG_AFTER_LOGINGEST" "$PG_BASE_LOGINGEST")") (from $(perf_bytes "$(num "$PG_BASE_LOGINGEST")") to $(perf_bytes "$(num "$PG_AFTER_LOGINGEST")")) |"
    echo "| Postgres growth, \`dlap_common\` (the catalog) | $(fig "$(growth "$PG_AFTER_COMMON" "$PG_BASE_COMMON")") |"
    echo "| \`log_events\` partitions, total size | $(fig "$([[ -n "$LOGEVENTS_BYTES" ]] && perf_bytes "$LOGEVENTS_BYTES")") ($(fig "$perevent" 'bytes per event')) |"
    echo "| Kafka partitions of \`$TOPIC\` | $(fig "$(skew partitions)") |"
    echo "| Kafka messages per partition (end offsets) | $(fig "$(skew offsets)") |"
    echo "| Kafka partition skew (fullest over emptiest) | $(fig "$(skew ratio)") |"
    echo "| Kafka share of the fullest partition | $(fig "$(skew maxshare)") |"
    echo "| Messages ever written to the topic | $(fig "$TOPIC_TOTAL") |"
    echo "| Quarantined records | $(fig "$QUARANTINED") ($(perf_pct "$QUARANTINED" "$((STORED + QUARANTINED))") percent) |"
    echo "| Messages in dead-letter topics | $(fig "$DLT_TOTAL") |"
    echo "| REST pushes (GCP and Azure) | $(fig "$POST_SUMMARY"), longest poster ran $(fig "$POST_SECONDS" s) |"
    echo
    echo "Samples every ${PERF_SAMPLE_SECS} s: \`$(basename "$CSV")\`."
  fi
  if [[ "$MODE" != "flaws" ]]; then
    echo
    echo "## Backfill resume"
    echo
    echo "A backfill job reads $PERF_BF_COUNT generated CloudTrail events ($BF_FILES files, another account, dated five days back) at $PERF_BF_BATCHES_PER_SEC files per second. da-logingest is killed with SIGKILL after 25 percent of the events are stored, then started again."
    echo
    echo "| Measure | Result |"
    echo "|---|---|"
    echo "| Events generated | $PERF_BF_COUNT |"
    echo "| Stored when the process was killed | $(fig "$BF_KILLED_AT") |"
    echo "| Job status after the restart | $(fig "$BF_STATUS") ($(fig "$BF_RESUME_SECS" s) after the process was started again) |"
    echo "| Time from the process start until the job resumed | $(fig "$BF_RESUMED_AFTER" s) |"
    echo "| Events stored at the end | $(fig "$BF_FINAL") |"
    echo "| Distinct \`event_id\` / distinct \`native_event_id\` | $(fig "$BF_DISTINCT") / $(fig "$BF_DISTINCT_NATIVE") |"
    echo "| Ids that differ between the generator's list and the table | $(fig "$BF_DIFF") |"
    echo "| Origin of the stored rows | $(fig "$BF_ORIGIN") |"
    echo "| Messages written to the topic during the job (events plus any re-publication) | $(fig "$BF_TOPIC_DELTA") |"
    echo "| Job progress | \`$(fig "$BF_PROGRESS")\` |"
  fi
  if [[ -n "$FLAWS_FILES" ]]; then
    echo
    echo "## flaws.cloud logs"
    echo
    echo "Downloaded at run time from \`$FLAWS_URL_USED\`; nothing of it is committed."
    echo
    echo "| Measure | Result |"
    echo "|---|---|"
    echo "| Archive sha256 | \`$(fig "$FLAWS_SHA")\` |"
    echo "| Files placed, files not placed | $(fig "$FLAWS_FILES"), $(fig "$FLAWS_UNMAPPED") |"
    echo "| Events stored, records quarantined | $(fig "$FLAWS_STORED"), $(fig "$FLAWS_QUAR") |"
    echo "| Quarantine rate | $(perf_pct "${FLAWS_QUAR:-0}" "$(( ${FLAWS_STORED:-0} + ${FLAWS_QUAR:-0} ))") percent (limit 1 percent) |"
  fi
  echo
  echo "## Named checks"
  echo
  echo "| Check | Result | Detail |"
  echo "|---|---|---|"
  for c in "${CHECKS[@]}"; do IFS='|' read -r res name detail <<<"$c"; echo "| \`$name\` | $res | $detail |"; done
} >"$REPORT"
say "results written to $REPORT"

FAILED=0
for c in "${CHECKS[@]}"; do [[ "$c" == FAIL* ]] && FAILED=$((FAILED + 1)); done
if (( FAILED > 0 )); then
  { echo "--- last lines of the service logs ---"; for s in dlap-da-logingest dlap-da-discovery dlap-da-catalog; do echo "== $s"; docker logs --tail 25 "$s" 2>&1; done; } >&2 || true
  fail "$FAILED check(s) failed; see $REPORT"
fi
PASSED_ALL=1
say "PASS: all ${#CHECKS[@]} checks passed ($MODE)"
