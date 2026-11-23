#!/usr/bin/env bash
#
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
# Measure the running demo (DEMO-01): memory, start time and a short load.
#
# The numbers in README.md come from this script, with the host written next to them. A measurement
# on a workstation does not describe an e2-standard-2 (critique item 18): the memory limits and the
# JVM heaps are enforced the same way, but the CPU count, the disk and the clock are not. Run it on
# the target VM (DEMO-04) before any claim about "8 GB". --cpuset restricts every container to a few
# CPUs, which makes a larger machine start about as slowly as a small one; it is an emulation and the
# report says so.
#
# What it records:
#   host        Docker server, CPUs and memory it sees, the cpuset, the git revision
#   limits      the memory limit of every container and their sum, against the budget
#   cold start  seconds from `up` until every service is healthy, and when each became healthy
#   idle        working-set memory and CPU of every container after the stack has been quiet
#   load        N clients for a while against the public paths; request counts, status classes and
#               latency percentiles; the peak memory seen
#   reset       with --reset, the duration of nightly-reset.sh
#   verdict     fails when the limits exceed the budget, a container was OOM-killed or restarted, or a
#               service is not healthy at the end
#
# Usage:  measure.sh [options]
#   --env-file FILE     environment file (default .env.demo)
#   --cpuset LIST       restrict all containers to these CPUs, for example 0-1
#   --no-cold           measure the stack as it is; do not stop and start it
#   --idle SECS         quiet time before the idle sample (default 90)
#   --load N            concurrent clients (default 10; 0 skips the load phase)
#   --load-secs SECS    load duration (default 60)
#   --think SECS        pause of each client between requests (default 1; 0.2 is a stress test)
#   --reset             also run nightly-reset.sh and record its duration
#   --budget-mib N      memory budget for the sum of the limits (default 6656, 6.5 GiB of an 8 GiB host)
#   --out DIR           report folder (default <state>/measure)
#   -h, --help          this text
#
# Load. Without credentials the clients call the unauthenticated paths: the SPA, its assets, and the
# API with no token (answers 401 from the security filter). With DEMO_MEASURE_USER and
# DEMO_MEASURE_PASSWORD set, they send a token of that user (password grant on the dlap-backend
# client); with DEMO_MEASURE_SERVICE_TOKEN=1 a client-credentials token; those requests reach the
# databases. The report states which one was used.
#
# Needs the stack to have been seeded (seed.sh) for the cold start to find its data.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

CPUSET=""
COLD=1
IDLE=90
LOAD=10
LOAD_SECS=60
THINK=1
DO_RESET=0
BUDGET_MIB=6656
OUT=""

usage() { awk '/^# Measure the running demo/ { on = 1 } on && /^#/ { sub(/^# ?/, ""); print; next } on { exit }' "${BASH_SOURCE[0]}"; }

while [ $# -gt 0 ]; do
  case "$1" in
    --env-file) [ $# -ge 2 ] || demo_die "--env-file needs a file name"; DEMO_ENV_FILE="$2"; shift 2 ;;
    --cpuset) [ $# -ge 2 ] || demo_die "--cpuset needs a list"; CPUSET="$2"; shift 2 ;;
    --no-cold) COLD=0; shift ;;
    --idle) [ $# -ge 2 ] || demo_die "--idle needs seconds"; IDLE="$2"; shift 2 ;;
    --load) [ $# -ge 2 ] || demo_die "--load needs a number"; LOAD="$2"; shift 2 ;;
    --load-secs) [ $# -ge 2 ] || demo_die "--load-secs needs seconds"; LOAD_SECS="$2"; shift 2 ;;
    --think) [ $# -ge 2 ] || demo_die "--think needs seconds"; THINK="$2"; shift 2 ;;
    --reset) DO_RESET=1; shift ;;
    --budget-mib) [ $# -ge 2 ] || demo_die "--budget-mib needs a number"; BUDGET_MIB="$2"; shift 2 ;;
    --out) [ $# -ge 2 ] || demo_die "--out needs a folder"; OUT="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) demo_die "unknown argument: $1" ;;
  esac
done

STATE="$(demo_state_dir)"
[ -n "$OUT" ] || OUT="$STATE/measure"
mkdir -p "$OUT"
TS="$(date -u +%Y%m%dT%H%M%SZ)"
WORK="$OUT/work-$TS"
mkdir -p "$WORK"
REPORT="$OUT/measure-$TS.md"

# A cpuset override for every service of the active model.
if [ -n "$CPUSET" ]; then
  demo_init "$DEMO_ENV_FILE"
  {
    echo "# Generated by measure.sh --cpuset $CPUSET. Not part of the demo."
    echo "services:"
    for s in $(demo_services); do
      printf '  %s:\n    cpuset: "%s"\n' "$s" "$CPUSET"
    done
  } > "$WORK/cpuset.yml"
  export DEMO_EXTRA_COMPOSE="${DEMO_EXTRA_COMPOSE:-} $(demo_native_path "$WORK/cpuset.yml")"
fi
demo_init "$DEMO_ENV_FILE"
demo_harness_prepare
UP=("$DEMO_DIR/up.sh" --env-file "$DEMO_ENV_FILE" --no-build)

GW="http://127.0.0.1:$(demo_cfg DLAP_PORT_GATEWAY 8081)"
KC="http://127.0.0.1:$(demo_cfg DLAP_PORT_KEYCLOAK 8180)"

# --- helpers ----------------------------------------------------------------------------------------

# "312.4MiB / 512MiB" -> "312.4 512" in MiB.
to_mib() {
  awk '
    function mib(s,   n, u) {
      n = s; sub(/[A-Za-z]+$/, "", n); u = s; sub(/^[0-9.]+/, "", u)
      if (u == "GiB") return n * 1024
      if (u == "MiB") return n
      if (u == "KiB") return n / 1024
      if (u == "kB")  return n / 1024
      if (u == "B")   return n / 1048576
      return n
    }
    { split($0, p, " / "); printf "%.1f %.1f\n", mib(p[1]), mib(p[2]) }'
}

# One docker stats sample of every container of the project: "service mem_mib limit_mib cpu_pct".
stats_sample() {
  local ids id svc line mem cpu
  ids="$(demo_dc ps -q | tr -d '\r')"
  [ -n "$ids" ] || return 0
  # shellcheck disable=SC2086
  docker stats --no-stream --format '{{.ID}}|{{.MemUsage}}|{{.CPUPerc}}' $ids 2>/dev/null | tr -d '\r' | while IFS='|' read -r id mem cpu; do
    svc="$(docker inspect --format '{{index .Config.Labels "com.docker.compose.service"}}' "$id" 2>/dev/null | tr -d '\r')"
    line="$(printf '%s' "$mem" | to_mib)"
    printf '%s %s %s\n' "$svc" "$line" "${cpu%\%}"
  done
}

# Merge samples (lines "service mem limit cpu") into the maximum memory per service.
max_by_service() {
  awk '{ if (!($1 in m) || $2 > m[$1]) m[$1] = $2; l[$1] = $3; if ($4 > c[$1]) c[$1] = $4 }
       END { for (s in m) printf "%s %.1f %.1f %.1f\n", s, m[s], l[s], c[s] }' | sort
}

now_ms() { date +%s%3N 2>/dev/null || echo $(( $(date +%s) * 1000 )); }

# --- host and limits --------------------------------------------------------------------------------

docker_ncpu="$(docker info --format '{{.NCPU}}' 2>/dev/null | tr -d '\r' || echo '?')"
docker_mem_b="$(docker info --format '{{.MemTotal}}' 2>/dev/null | tr -d '\r' || echo 0)"
docker_ver="$(docker version --format '{{.Server.Version}} {{.Server.Os}}/{{.Server.Arch}}' 2>/dev/null | tr -d '\r' || echo '?')"
rev="$(git -C "$(demo_native_path "$DEMO_REPO_ROOT")" rev-parse --short HEAD 2>/dev/null || echo unknown)"

demo_dc config --format json 2>/dev/null > "$WORK/model.json" || demo_die "cannot render the compose model"
PY=""
for c in python3 python; do command -v "$c" >/dev/null 2>&1 && { PY="$c"; break; }; done
[ -n "$PY" ] || demo_die "python is required to read the compose model"

limits_mib="$("$PY" - "$(demo_native_path "$WORK/model.json")" <<'PYEOF'
import json, sys
m = json.load(open(sys.argv[1], encoding="utf-8"))["services"]
tot = 0
rows = []
for name, s in sorted(m.items()):
    lim = (((s.get("deploy") or {}).get("resources") or {}).get("limits") or {}).get("memory")
    if lim:
        mib = int(lim) / 1048576
        tot += mib
        rows.append("%s %.0f" % (name, mib))
print("TOTAL %.0f" % tot)
print("\n".join(rows))
PYEOF
)"
limits_total="$(printf '%s\n' "$limits_mib" | awk '$1 == "TOTAL" { print $2 }')"

demo_say "host: Docker $docker_ver, ${docker_ncpu} CPUs, $((docker_mem_b / 1048576)) MiB; cpuset: ${CPUSET:-none}"
demo_say "sum of memory limits: ${limits_total} MiB (budget ${BUDGET_MIB})"

# --- cold start -------------------------------------------------------------------------------------

declare -A healthy_at
: > "$WORK/cold-stats.txt"
cold_total="-"
cold_peak="0"
if [ "$COLD" = "1" ]; then
  demo_say "cold start: down (volumes kept), then up"
  demo_dc down --remove-orphans > /dev/null 2>&1 || true
  t0_ms="$(now_ms)"
  "${UP[@]}" > "$WORK/up.log" 2>&1 &
  up_pid=$!
  last_stat=0
  while kill -0 "$up_pid" 2>/dev/null; do
    for s in $(demo_services); do
      if [ -z "${healthy_at[$s]:-}" ] && [ "$(demo_health "$s")" = "healthy" ]; then
        healthy_at[$s]=$(( ( $(now_ms) - t0_ms ) / 1000 ))
      fi
    done
    if (( SECONDS - last_stat >= 20 )); then
      stats_sample >> "$WORK/cold-stats.txt" || true
      last_stat=$SECONDS
    fi
    sleep 3
  done
  wait "$up_pid" || demo_die "the stack did not become healthy; see $WORK/up.log"
  cold_total=$(( ( $(now_ms) - t0_ms ) / 1000 ))
  for s in $(demo_services); do
    [ -n "${healthy_at[$s]:-}" ] || healthy_at[$s]=$cold_total
  done
  demo_say "healthy after ${cold_total}s"
fi

# --- idle -------------------------------------------------------------------------------------------

demo_say "quiet for ${IDLE}s, then sampling"
sleep "$IDLE"
: > "$WORK/idle-stats.txt"
for _ in 1 2 3; do stats_sample >> "$WORK/idle-stats.txt" || true; sleep 5; done
max_by_service < "$WORK/idle-stats.txt" > "$WORK/idle.txt"
idle_total="$(awk '{ s += $2 } END { printf "%.0f", s }' "$WORK/idle.txt")"

# --- load -------------------------------------------------------------------------------------------

load_summary="skipped"
load_auth="none"
: > "$WORK/load-stats.txt"
if [ "$LOAD" -gt 0 ]; then
  token=""
  secret="$(demo_cfg DLAP_BACKEND_CLIENT_SECRET "")"
  if [ -n "${DEMO_MEASURE_USER:-}" ] && [ -n "${DEMO_MEASURE_PASSWORD:-}" ]; then
    token="$(curl -s --max-time 15 -d grant_type=password -d client_id=dlap-backend --data-urlencode "client_secret=$secret" \
      -d "username=$DEMO_MEASURE_USER" --data-urlencode "password=$DEMO_MEASURE_PASSWORD" \
      "$KC/realms/dlap/protocol/openid-connect/token" | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')"
    [ -n "$token" ] && load_auth="user token ($DEMO_MEASURE_USER)" || demo_warn "no token for $DEMO_MEASURE_USER; the load runs without one"
  elif [ "${DEMO_MEASURE_SERVICE_TOKEN:-0}" = "1" ]; then
    token="$(curl -s --max-time 15 -d grant_type=client_credentials -d client_id=dlap-backend --data-urlencode "client_secret=$secret" \
      "$KC/realms/dlap/protocol/openid-connect/token" | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')"
    [ -n "$token" ] && load_auth="service-account token" || demo_warn "no service token; the load runs without one"
  fi

  asset_js="$(find "$DEMO_DIR/spa/assets" -name 'index-*.js' 2>/dev/null | head -n 1 | xargs -r basename || true)"
  PATHS=("/" "/gateway-health" "/api/v1/catalog/assets?page=0&size=20" "/api/v1/catalog/assets/count"
         "/api/v1/policyengine/policies" "/api/v1/reporting/dashboards/cdo/governance-score" "/api/v1/discovery/statistics")
  [ -z "$asset_js" ] || PATHS+=("/assets/$asset_js")

  demo_say "load: $LOAD clients for ${LOAD_SECS}s, think time ${THINK}s, auth: $load_auth"
  end=$((SECONDS + LOAD_SECS))
  pids=()
  for ((w = 1; w <= LOAD; w++)); do
    (
      i=$w
      while (( SECONDS < end )); do
        p="${PATHS[$((i % ${#PATHS[@]}))]}"
        i=$((i + 1))
        # curl prints its -w line even when the request fails (code 000, for example after the 30 s timeout).
        # No output at all means the client itself could not run curl (a process spawn failure on a loaded
        # Windows shell); that is recorded as ERR and is not a server result.
        if [ -n "$token" ]; then
          res="$(curl -s -o /dev/null -w '%{http_code} %{time_total}' --max-time 30 -H "Authorization: Bearer $token" "$GW$p" || true)"
        else
          res="$(curl -s -o /dev/null -w '%{http_code} %{time_total}' --max-time 30 "$GW$p" || true)"
        fi
        echo "${res:-ERR 0} $p"
        sleep "$THINK"
      done
    ) >> "$WORK/load-w$w.txt" &
    pids+=($!)
  done
  while (( SECONDS < end )); do stats_sample >> "$WORK/load-stats.txt" || true; sleep 8; done
  wait "${pids[@]}" 2>/dev/null || true
  cat "$WORK"/load-w*.txt > "$WORK/load-all.txt" 2>/dev/null || : > "$WORK/load-all.txt"
  # Lines: "<status> <seconds> <path>". Summary overall (group 'ALL') and per path (query string cut).
  awk '
    { key = $3; sub(/\?.*/, "", key); n[key]++; n["ALL"]++
      c = substr($1, 1, 1)
      if ($1 == "ERR") { x[key]++; x["ALL"]++; n[key]--; n["ALL"]--; next }
      if ($1 == "000") { f[key]++; f["ALL"]++ } else if (c == "2") { ok[key]++; ok["ALL"]++ }
      else if (c == "4") { e4[key]++; e4["ALL"]++ } else if (c == "5") { e5[key]++; e5["ALL"]++ }
      if ($1 == "429") { r[key]++; r["ALL"]++ }
      m[key, n[key]] = $2; m["ALL", n["ALL"]] = $2 }
    function pct(key, q,   cnt, a, i, j, v, idx) {
      cnt = n[key]
      for (i = 1; i <= cnt; i++) a[i] = m[key, i]
      for (i = 2; i <= cnt; i++) { v = a[i]; j = i - 1; while (j > 0 && a[j] > v) { a[j + 1] = a[j]; j-- } a[j + 1] = v }
      idx = int((cnt + 1) * q); if (idx > cnt) idx = cnt; if (idx < 1) idx = 1
      return a[idx]
    }
    END { for (key in n) printf "%s %d %d %d %d %d %d %.3f %.3f %d\n", key, n[key], ok[key], e4[key], r[key], e5[key], f[key], pct(key, 0.50), pct(key, 0.95), x[key] }
  ' "$WORK/load-all.txt" | sort > "$WORK/load-paths.txt" || : > "$WORK/load-paths.txt"
  load_summary="$(awk '$1 == "ALL" { printf "requests=%d 2xx=%d 4xx=%d (429=%d) 5xx=%d timeouts=%d p50=%.3fs p95=%.3fs client_errors=%d", $2, $3, $4, $5, $6, $7, $8, $9, $10 }' "$WORK/load-paths.txt")"
  [ -n "$load_summary" ] || load_summary="no requests (see $WORK/load-all.txt)"
  demo_say "load: $load_summary"
fi
cat "$WORK/idle-stats.txt" "$WORK/load-stats.txt" "$WORK/cold-stats.txt" 2>/dev/null | max_by_service > "$WORK/peak.txt"

# --- reset ------------------------------------------------------------------------------------------

reset_secs="-"
if [ "$DO_RESET" = "1" ]; then
  demo_say "nightly reset"
  r0=$SECONDS
  "$SCRIPT_DIR/nightly-reset.sh" --env-file "$DEMO_ENV_FILE" > "$WORK/reset.log" 2>&1 || demo_die "the reset failed; see $WORK/reset.log"
  reset_secs=$((SECONDS - r0))
  demo_say "reset took ${reset_secs}s"
fi

# --- health, OOM, restarts, outbound traffic -------------------------------------------------------------

problems=()
ooms=0
restarts=0
for id in $(demo_dc ps -q | tr -d '\r'); do
  svc="$(docker inspect --format '{{index .Config.Labels "com.docker.compose.service"}}' "$id" | tr -d '\r')"
  oom="$(docker inspect --format '{{.State.OOMKilled}}' "$id" | tr -d '\r')"
  rc="$(docker inspect --format '{{.RestartCount}}' "$id" | tr -d '\r')"
  [ "$oom" = "true" ] && { ooms=$((ooms + 1)); problems+=("$svc was OOM-killed"); }
  [ "${rc:-0}" -gt 0 ] && { restarts=$((restarts + rc)); problems+=("$svc restarted $rc time(s)"); }
  h="$(demo_health "$svc")"
  [ "$h" = "healthy" ] || problems+=("$svc is $h at the end")
done
if awk -v t="$limits_total" -v b="$BUDGET_MIB" 'BEGIN { exit !(t > b) }'; then
  problems+=("the memory limits add up to ${limits_total} MiB, over the budget of ${BUDGET_MIB} MiB")
fi
# Cloud endpoints in the logs of the services that could call one. The network has no route out, so a
# hit here is an attempt that failed.
cloud_hits=0
for svc in da-discovery da-logingest da-autoarchival da-autodelete; do
  n="$(demo_dc logs --no-color "$svc" 2>/dev/null | grep -ciE 'googleapis\.com|amazonaws\.com|blob\.core\.windows\.net|metadata\.google\.internal|169\.254\.169\.254' || true)"
  cloud_hits=$((cloud_hits + ${n:-0}))
done

# --- report -----------------------------------------------------------------------------------------

{
  echo "# Demo measurement $TS"
  echo
  echo "| | |"
  echo "|---|---|"
  echo "| Git revision | $rev |"
  echo "| Docker server | $docker_ver |"
  echo "| CPUs the Docker host offers | $docker_ncpu |"
  echo "| Memory the Docker host offers | $((docker_mem_b / 1048576)) MiB |"
  if [ -n "$CPUSET" ]; then echo "| CPU restriction | cpuset $CPUSET (an emulation of a small host, not one) |"; else echo "| CPU restriction | none |"; fi
  echo "| Compose project | $(demo_project) |"
  echo "| Profiles | ${COMPOSE_PROFILES:-default (Lite)} |"
  echo "| Sum of memory limits | ${limits_total} MiB (budget ${BUDGET_MIB} MiB) |"
  echo "| Cold start until every service is healthy | ${cold_total} s |"
  echo "| Working set of all containers, idle | ${idle_total} MiB |"
  echo "| Load | $LOAD clients, ${LOAD_SECS} s, think time ${THINK} s, auth: $load_auth |"
  echo "| Load result | $load_summary |"
  echo "| Nightly reset | ${reset_secs} s |"
  echo "| OOM kills / restarts | $ooms / $restarts |"
  echo "| Cloud endpoint mentions in service logs | $cloud_hits |"
  echo
  echo "## Per container (MiB)"
  echo
  echo "| Service | Limit | Idle | Peak seen | Peak % of limit | CPU % (peak) | Healthy after (s) |"
  echo "|---|---:|---:|---:|---:|---:|---:|"
  while read -r svc peak limit cpu; do
    idle="$(awk -v s="$svc" '$1 == s { print $2 }' "$WORK/idle.txt")"
    lim="$(printf '%s\n' "$limits_mib" | awk -v s="$svc" '$1 == s { print $2 }')"
    [ -n "$lim" ] || lim="$limit"
    pct="$(awk -v p="$peak" -v l="$lim" 'BEGIN { if (l > 0) printf "%.0f", 100 * p / l; else print "-" }')"
    echo "| $svc | $lim | ${idle:--} | $peak | $pct | $cpu | ${healthy_at[$svc]:--} |"
  done < "$WORK/peak.txt"
  echo
  if [ -s "$WORK/load-paths.txt" ]; then
    echo "## Load by path"
    echo
    echo "| Path | Requests | 2xx | 4xx | of which 429 | 5xx | Timeouts (30 s) | p50 (s) | p95 (s) | Client errors |"
    echo "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|"
    awk '{ printf "| %s | %d | %d | %d | %d | %d | %d | %s | %s | %d |\n", $1, $2, $3, $4, $5, $6, $7, $8, $9, $10 }' "$WORK/load-paths.txt"
    echo
  fi
  if [ "${#problems[@]}" -gt 0 ]; then
    echo "## Problems"
    echo
    for p in "${problems[@]}"; do echo "- $p"; done
  else
    echo "No problems: every service healthy, no OOM kill, no restart, limits within the budget."
  fi
} > "$REPORT"

cat "$REPORT"
demo_say "report: $REPORT"
if [ "${#problems[@]}" -gt 0 ]; then
  demo_die "${#problems[@]} problem(s): ${problems[*]}"
fi
