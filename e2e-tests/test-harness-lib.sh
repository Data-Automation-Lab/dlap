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
#
# ---------------------------------------------------------------------------
# Self-test for harness-lib.sh and the HARN-01 compose port wiring. No Gradle, no stack.
#
#   bash e2e-tests/test-harness-lib.sh           # everything; docker-backed cases need a daemon
#   bash e2e-tests/test-harness-lib.sh --quick   # skip the slow lock cases and the docker cases
#
# The lock cases run in a private temp directory (DLAP_HARNESS_LOCK_DIR), so the test never
# waits on, or disturbs, a real harness run. The eviction cases only touch containers they
# create themselves (names dlap-hlt-<pid>). Exit 0 when every case passed or was skipped
# with a printed reason, 1 otherwise.
# ---------------------------------------------------------------------------
# shellcheck disable=SC1090  # the library path is a variable
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
LIB="$HERE/harness-lib.sh"
DEV="$REPO/deployment/environments/dev"
QUICK=""; [[ "${1:-}" == "--quick" ]] && QUICK=1

PASS=0; FAIL=0; SKIP=0
t_ok()   { PASS=$((PASS + 1)); echo "  ok    $1"; }
t_fail() { FAIL=$((FAIL + 1)); echo "  FAIL  $1${2:+ : $2}"; }
t_skip() { SKIP=$((SKIP + 1)); echo "  skip  $1${2:+ : $2}"; }
check()  { if [[ "$2" == "$3" ]]; then t_ok "$1"; else t_fail "$1" "expected '$2', got '$3'"; fi; }
contains() { # contains <name> <haystack> <needle>
  if [[ "$2" == *"$3"* ]]; then t_ok "$1"; else t_fail "$1" "missing '$3' in: ${2:0:300}"; fi
}
section() { echo; echo "== $1 (t+${SECONDS}s)"; }

TMPROOT="$(mktemp -d)"
BG_PIDS=()
COMPOSE_PROJECTS=()
cleanup() {
  local p
  for p in "${BG_PIDS[@]+"${BG_PIDS[@]}"}"; do kill -9 "$p" 2>/dev/null; done
  for p in "${COMPOSE_PROJECTS[@]+"${COMPOSE_PROJECTS[@]}"}"; do
    docker compose -p "$p" -f "$TMPROOT/probe-compose.yml" down -v >/dev/null 2>&1
  done
  rm -rf "$TMPROOT"
}
trap cleanup EXIT

# ---------------------------------------------------------------------------
section "project name"
# ---------------------------------------------------------------------------
pname() { ( unset COMPOSE_PROJECT_NAME; source "$LIB"; harness_project_name "$1"; echo "$COMPOSE_PROJECT_NAME" ); }
n1="$(pname /x/wt/HARN-01)"
n2="$(pname /x/wt/HARN-01)"
n3="$(pname /x/wt/QA-01)"
n4="$(pname /y/wt/HARN-01)"
n5="$(pname /X/WT/harn-01)"
if [[ "$n1" =~ ^dlap-e2e-harn-01-[0-9a-f]{8}$ ]]; then t_ok "shape dlap-e2e-<dir>-<8 hex> ($n1)"; else t_fail "shape" "$n1"; fi
check "same path gives the same name" "$n1" "$n2"
[[ "$n1" != "$n3" ]] && t_ok "different worktrees get different names" || t_fail "different worktrees" "$n1 == $n3"
[[ "$n1" != "$n4" ]] && t_ok "same directory name under another parent differs" || t_fail "same dir name, other parent" "$n1 == $n4"
check "path case does not matter (Windows paths are case-insensitive)" "$n1" "$n5"
check "an explicit COMPOSE_PROJECT_NAME wins" "mine" \
  "$( COMPOSE_PROJECT_NAME=mine; source "$LIB"; harness_project_name /x/wt/HARN-01; echo "$COMPOSE_PROJECT_NAME" )"
long="$(pname "/x/wt/A Very Long Directory Name With Spaces And (Parens) 12345")"
if [[ "$long" =~ ^[a-z0-9][a-z0-9_-]*$ ]]; then t_ok "odd directory names still give a valid compose project name"; else t_fail "odd directory name" "$long"; fi

# ---------------------------------------------------------------------------
section "host ports"
# ---------------------------------------------------------------------------
GOLDEN_PORTS="3000 3001 4566 5432 8081 8082 8083 8084 8085 8086 8087 8088 8089 8090 8091 8180 8443 9090 9092 9093 11434 18080"

defaults_out="$( for v in $(env | grep -o '^DLAP_PORT_[A-Z_]*' ); do unset "$v"; done; source "$LIB"; harness_port_defaults; env | grep '^DLAP_PORT_' | sort )"
want_count="$(echo "$GOLDEN_PORTS" | wc -w | tr -d ' ')"
check "harness_port_defaults exports one variable per published port" "$want_count" "$(echo "$defaults_out" | grep -c .)"
got_ports="$(echo "$defaults_out" | sed 's/.*=//' | sort -n | tr '\n' ' ' | sed 's/ $//')"
check "defaults are today's published ports" "$GOLDEN_PORTS" "$got_ports"
contains "an override is kept" \
  "$( DLAP_PORT_CATALOG=18082 bash -c "source '$LIB'; harness_port_defaults; echo \$DLAP_PORT_CATALOG" )" "18082"
for bad in abc 0 70000 12x; do
  out="$( DLAP_PORT_CATALOG=$bad bash -c "source '$LIB'; harness_port_defaults" 2>&1 )"; rc=$?
  if [[ $rc -ne 0 && "$out" == *"DLAP_PORT_CATALOG must be a port number"* ]]; then t_ok "invalid port '$bad' is rejected"; else t_fail "invalid port '$bad'" "rc=$rc out=$out"; fi
done

# The lib list, the compose defaults and .env.example must name the same variables and values.
lib_set="$( source "$LIB"; printf '%s\n' "${HARNESS_PORT_DEFAULTS[@]}" | sort )"
compose_set="$( grep -ohE '\$\{DLAP_PORT_[A-Z_]+:-[0-9]+\}' "$DEV"/docker-compose.yml "$DEV"/docker-compose.microservices.yml \
    | sed -E 's/^\$\{DLAP_PORT_([A-Z_]+):-([0-9]+)\}$/\1=\2/' | sort -u )"
env_set="$( grep -E '^DLAP_PORT_[A-Z_]+=[0-9]+$' "$REPO/.env.example" | sed 's/^DLAP_PORT_//' | sort )"
check "compose defaults == harness-lib.sh defaults" "$lib_set" "$compose_set"
check ".env.example values == harness-lib.sh defaults" "$lib_set" "$env_set"
raw_ports="$( grep -hE '^\s+- "[0-9$]' "$DEV"/docker-compose.yml "$DEV"/docker-compose.microservices.yml | grep -vc 'DLAP_PORT_' )"
check "no published port is left hard-coded in the compose pair" "0" "$raw_ports"

if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
  render() { docker compose --profile monitoring --profile aws --profile unity \
      -f "$DEV/docker-compose.yml" -f "$DEV/docker-compose.microservices.yml" config 2>/dev/null; }
  published() { grep -E '^\s+published:' | sed -E 's/[^0-9]//g' | sort -n | tr '\n' ' ' | sed 's/ $//'; }
  default_render="$( for v in $(env | grep -o '^DLAP_PORT_[A-Z_]*'); do unset "$v"; done; render )"
  check "compose with no overrides publishes exactly today's ports" "$GOLDEN_PORTS" "$(echo "$default_render" | published)"
  over_render="$( DLAP_PORT_KAFKA=19092 DLAP_PORT_CATALOG=18082 render )"
  contains "an override changes the published host port" "$(echo "$over_render" | published)" "18082"
  contains "a Kafka override moves the host listener too" "$over_render" "PLAINTEXT_HOST://0.0.0.0:19092"
  contains "a Kafka override moves the advertised address too" "$over_render" "PLAINTEXT_HOST://localhost:19092"
else
  t_skip "compose rendering cases" "docker compose CLI not available"
fi

# ---------------------------------------------------------------------------
section "log wait does not false-wait under pipefail (SIGPIPE)"
# ---------------------------------------------------------------------------
(
  source "$LIB"
  set -o pipefail
  docker() { # fake `docker logs <name>`: a big log whose match is on the FIRST line
    [[ "$1" == logs ]] || return 2
    case "$2" in
      big)    echo "Started DADiscoveryAgent in 41 seconds"
              awk 'BEGIN { for (i = 0; i < 300000; i++) print "noise noise noise noise noise noise noise noise" }' ;;
      nomatch) echo "starting" ;;
      *)      echo "Error: No such container: $2" >&2; return 1 ;;
    esac
  }
  # The pattern the scripts used to have, for the record. It depends on timing, so informational.
  docker logs big 2>&1 | grep -q "Started DADiscoveryAgent"; old_rc=$?
  echo "  info  old pattern 'docker logs | grep -q' under pipefail returned rc=$old_rc (non-zero is the false wait: 141 from SIGPIPE on Linux, 1 from docker.exe on Windows)"
  harness_log_has big "Started DADiscoveryAgent"; rc=$?
  [[ $rc -eq 0 ]] && echo "  ok    harness_log_has finds a match on the first line of a 300k line log" || echo "  FAIL  harness_log_has big log rc=$rc"
  harness_log_has nomatch "Started DADiscoveryAgent"; rc=$?
  [[ $rc -ne 0 ]] && echo "  ok    harness_log_has is false when the line is absent" || echo "  FAIL  harness_log_has nomatch rc=$rc"
  harness_log_has missing "x"; rc=$?
  [[ $rc -ne 0 ]] && echo "  ok    harness_log_has is false for a missing container" || echo "  FAIL  harness_log_has missing rc=$rc"
  start=$SECONDS
  harness_wait_log big "Started DADiscoveryAgent" 72 5; rc=$?
  el=$((SECONDS - start))
  [[ $rc -eq 0 && $el -le 5 ]] && echo "  ok    harness_wait_log returns at once (waited ${el}s of a possible 360s)" || echo "  FAIL  harness_wait_log big: rc=$rc after ${el}s"
  harness_wait_log nomatch "Started DADiscoveryAgent" 2 0; rc=$?
  [[ $rc -eq 1 ]] && echo "  ok    harness_wait_log gives up after its tries" || echo "  FAIL  harness_wait_log nomatch rc=$rc"
) | tee "$TMPROOT/waitlog.out"
PASS=$((PASS + $(grep -c '^  ok ' "$TMPROOT/waitlog.out")))
FAIL=$((FAIL + $(grep -c '^  FAIL ' "$TMPROOT/waitlog.out")))

# ---------------------------------------------------------------------------
# Lock cases, once per backend available on this host.
# ---------------------------------------------------------------------------
# Holder: takes the lock, signals readiness, then idles in short sleeps so SIGTERM is handled at
# once. The sleeps drop fd 9 (9>&-) so only the holder shell owns the flock descriptor.
start_holder() { # start_holder <ready file>; sets HOLDER_PID
  local ready="$1"
  bash -c "source '$LIB'; harness_lock_acquire holder >/dev/null; : > '$ready'; for _i in \$(seq 1 600); do sleep 0.1 9>&-; done" &
  HOLDER_PID=$!; BG_PIDS+=("$HOLDER_PID")
  local _n; for _n in $(seq 1 150); do [[ -e "$ready" ]] && return 0; sleep 0.1; done
  return 1
}
try_acquire() { # try_acquire <timeout secs> [extra env assignments via env]; prints output then "rc=N"
  local out rc
  out="$(DLAP_HARNESS_LOCK_TIMEOUT="$1" bash -c "source '$LIB'; harness_lock_acquire contender" 2>&1)"; rc=$?
  echo "$out"; echo "rc=$rc"
}

lock_cases() {
  local B="$1" d lockpath out rc t0 el
  d="$(mktemp -d "$TMPROOT/lock-$B.XXXX")"
  export DLAP_HARNESS_LOCK_DIR="$d" DLAP_HARNESS_LOCK_BACKEND="$B"
  unset DLAP_HARNESS_LOCK_HELD
  if [[ "$B" == mkdir ]]; then lockpath="$d/dlap-e2e-harness.lock.d"; else lockpath="$d/dlap-e2e-harness.lock"; fi
  section "host-wide lock, backend=$B"

  # mutual exclusion and timeout
  if start_holder "$d/ready1"; then
    t0=$SECONDS
    out="$(try_acquire 3)"; el=$((SECONDS - t0))
    contains "[$B] a second run is refused while the lock is held" "$out" "rc=1"
    contains "[$B] the refusal names the holder" "$out" "holder: pid $HOLDER_PID"
    [[ $el -le 20 ]] && t_ok "[$B] the refusal arrives at the timeout, not a poll interval later (${el}s for 3s)" || t_fail "[$B] timeout latency" "${el}s"
    # release on SIGTERM
    kill -TERM "$HOLDER_PID" 2>/dev/null; wait "$HOLDER_PID" 2>/dev/null
    out="$(try_acquire 10)"
    contains "[$B] the lock is free after the holder is terminated" "$out" "rc=0"
    [[ ! -e "$lockpath" || "$B" == flock ]] && t_ok "[$B] no lock directory is left behind" || t_fail "[$B] lock directory left behind" "$lockpath"
  else
    t_fail "[$B] holder did not start"
  fi

  # release after SIGKILL (no trap runs)
  rm -f "$d/ready2"
  if start_holder "$d/ready2"; then
    kill -9 "$HOLDER_PID" 2>/dev/null; wait "$HOLDER_PID" 2>/dev/null
    out="$(try_acquire 15)"
    contains "[$B] the lock is free after the holder is SIGKILLed" "$out" "rc=0"
    [[ "$B" == mkdir ]] && contains "[$B] the dead holder's lock was reclaimed" "$out" "reclaiming stale harness lock"
  else
    t_fail "[$B] second holder did not start"
  fi

  # re-entrancy: a child of a live holder does not wait on the lock its parent holds
  out="$( DLAP_HARNESS_LOCK_HELD=$$ bash -c "source '$LIB'; harness_lock_acquire inner; echo done-inner" 2>&1 )"
  contains "[$B] a child of a live holder does not re-lock" "$out" "already held by ancestor pid $$"
  contains "[$B] ...and carries on" "$out" "done-inner"
  out="$( bash "$HERE/with-harness-lock.sh" --label wrapped -- bash -c "source '$LIB'; harness_lock_acquire inner; echo done-inner" 2>&1 )"; rc=$?
  contains "[$B] a harness inside with-harness-lock.sh does not deadlock" "$out" "done-inner"
  check "[$B] with-harness-lock.sh passes the exit status through" "7" \
    "$( bash "$HERE/with-harness-lock.sh" -- bash -c 'exit 7' >/dev/null 2>&1; echo $? )"
  out="$(try_acquire 5)"
  contains "[$B] with-harness-lock.sh releases the lock when the command ends" "$out" "rc=0"
  out="$( bash "$HERE/with-harness-lock.sh" 2>&1 )"; rc=$?
  check "[$B] with-harness-lock.sh without a command is a usage error" "2" "$rc"

  # stress: workers take turns; the log must never show two inside at once
  : > "$d/stress.log"
  local w pids=()
  for w in 1 2 3 4; do
    bash -c "source '$LIB'
             for _i in 1 2; do
               harness_lock_acquire w$w >/dev/null
               echo s$w >> '$d/stress.log'; sleep 0.1 9>&-; echo e$w >> '$d/stress.log'
               harness_lock_release
             done" &
    pids+=($!); BG_PIDS+=($!)
  done
  for w in "${pids[@]}"; do wait "$w" 2>/dev/null; done
  local bad
  bad="$(awk '{ k = substr($0, 2) }
              /^s/ { if (open != "") { print "overlap at line " NR; exit } open = k; starts++; next }
              /^e/ { if (open != k) { print "unpaired end at line " NR; exit } open = ""; ends++ }
              END { if (starts != 8 || ends != 8) print "expected 8 sections, saw " starts "/" ends }' "$d/stress.log")"
  check "[$B] 4 workers x 2 turns never overlap (8 clean critical sections)" "" "$bad"

  if [[ "$B" == mkdir ]]; then
    # a crashed holder's lock (dead PID) is reclaimed
    bash -c 'exit 0' & dead=$!; wait "$dead" 2>/dev/null
    rm -rf "$lockpath"; mkdir "$lockpath"; echo "$dead $(date +%s) crashed /tmp" > "$lockpath/pid"
    out="$(try_acquire 10)"
    contains "[$B] a lock whose PID is dead is reclaimed" "$out" "rc=0"
    # a lock older than MAX_AGE is stale even when its PID looks alive (PID reuse after a reboot)
    rm -rf "$lockpath"; mkdir "$lockpath"; echo "$$ 1 ancient /tmp" > "$lockpath/pid"
    out="$(DLAP_HARNESS_LOCK_MAX_AGE=60 try_acquire 10)"
    contains "[$B] a lock older than DLAP_HARNESS_LOCK_MAX_AGE is reclaimed" "$out" "rc=0"
    # a young lock with a live PID is respected
    rm -rf "$lockpath"; mkdir "$lockpath"; echo "$$ $(date +%s) alive /tmp" > "$lockpath/pid"
    out="$(try_acquire 2)"
    contains "[$B] a young lock with a live PID is respected" "$out" "rc=1"
    # a lock directory with no PID file yet is a holder mid-startup: not stolen at once
    rm -rf "$lockpath"; mkdir "$lockpath"
    out="$(try_acquire 3)"
    contains "[$B] a lock still being written is not stolen within the 10s grace" "$out" "rc=1"
    if [[ -z "$QUICK" ]]; then
      out="$(try_acquire 25)"
      contains "[$B] ...but a lock that never gets a PID is reclaimed after the grace" "$out" "rc=0"
    else
      t_skip "[$B] reclaim of a PID-less lock after the grace" "--quick"
    fi
    rm -rf "$lockpath"
  fi
  unset DLAP_HARNESS_LOCK_DIR DLAP_HARNESS_LOCK_BACKEND
}

lock_cases mkdir
if command -v flock >/dev/null 2>&1; then
  lock_cases flock
else
  section "host-wide lock, backend=flock"
  t_skip "flock backend" "flock is not installed on this host (Git Bash); run this test in a Linux container to cover it"
fi

section "lock misuse"
out="$( DLAP_HARNESS_LOCK_DIR="$TMPROOT" DLAP_HARNESS_LOCK_BACKEND=bogus bash -c "source '$LIB'; harness_lock_acquire x" 2>&1 )"; rc=$?
if [[ $rc -ne 0 && "$out" == *"unknown DLAP_HARNESS_LOCK_BACKEND"* ]]; then t_ok "an unknown backend is rejected"; else t_fail "unknown backend" "rc=$rc $out"; fi
out="$( DLAP_HARNESS_LOCK_DIR="$TMPROOT" DLAP_HARNESS_LOCK_TIMEOUT=soon bash -c "source '$LIB'; harness_lock_acquire x" 2>&1 )"; rc=$?
if [[ $rc -ne 0 && "$out" == *"DLAP_HARNESS_LOCK_TIMEOUT must be a number"* ]]; then t_ok "a non-numeric timeout is rejected"; else t_fail "bad timeout" "rc=$rc $out"; fi

# ---------------------------------------------------------------------------
section "lock base is one fixed path, independent of TMPDIR (Q-577)"
# ---------------------------------------------------------------------------
# lock_base <TMPDIR value>: what harness_lock_base prints in a fresh shell with that TMPDIR and no override.
lock_base() { env -u DLAP_HARNESS_LOCK_DIR -u DLAP_HARNESS_LOCK_HOME TMPDIR="$1" bash -c "source '$LIB'; harness_lock_base" 2>&1; }
base_a="$(lock_base /tmp)"
base_b="$(lock_base "$TMPROOT/some/other/tmp")"
base_c="$(env -u DLAP_HARNESS_LOCK_DIR -u DLAP_HARNESS_LOCK_HOME -u TMPDIR bash -c "source '$LIB'; harness_lock_base" 2>&1)"
check "the lock base is the same for TMPDIR=/tmp and a different TMPDIR" "$base_a" "$base_b"
check "the lock base is the same with TMPDIR unset" "$base_a" "$base_c"
[[ "$base_a" == /* ]] && t_ok "the lock base is an absolute path ($base_a)" || t_fail "lock base is absolute" "$base_a"
contains "the lock base is a per-user .dlap directory" "$base_a" "/.dlap/harness-lock"
[[ "$base_a" != "$TMPROOT"* && "$base_a" != /tmp/* ]] && t_ok "the lock base is not under a temp directory" || t_fail "lock base under a temp directory" "$base_a"
out="$(env -u DLAP_HARNESS_LOCK_DIR DLAP_HARNESS_LOCK_HOME="$TMPROOT/fakehome" bash -c "source '$LIB'; harness_lock_base")"
check "DLAP_HARNESS_LOCK_HOME moves only the home part" "$TMPROOT/fakehome/.dlap/harness-lock" "$out"
out="$(env DLAP_HARNESS_LOCK_DIR="$TMPROOT/explicit" bash -c "source '$LIB'; harness_lock_base")"
check "DLAP_HARNESS_LOCK_DIR still replaces the whole base" "$TMPROOT/explicit" "$out"
out="$(env -u DLAP_HARNESS_LOCK_DIR DLAP_HARNESS_LOCK_HOME=relative/dir bash -c "source '$LIB'; harness_lock_base" 2>&1)"; rc=$?
if [[ $rc -ne 0 && "$out" == *"no absolute home directory"* ]]; then t_ok "a relative home is refused"; else t_fail "relative home" "rc=$rc $out"; fi
# The code reads no TMPDIR (comments may name it).
if grep -v '^[[:space:]]*#' "$LIB" | grep -q 'TMPDIR'; then t_fail "harness-lib.sh code still reads TMPDIR"; else t_ok "harness-lib.sh code does not read TMPDIR"; fi

# Two shells with different TMPDIR exclude each other: both take the lock at the default location
# (built from a private home, so the real host lock is never touched), and the second is refused.
lh="$TMPROOT/lockhome"; mkdir -p "$lh"
for B in mkdir flock; do
  if [[ "$B" == flock ]] && ! command -v flock >/dev/null 2>&1; then t_skip "[$B] two TMPDIR values exclude each other" "flock is not installed on this host"; continue; fi
  rm -rf "$lh/.dlap"; rm -f "$lh/ready"
  mkdir -p "$TMPROOT/tmpA" "$TMPROOT/tmpB"
  env -u DLAP_HARNESS_LOCK_DIR DLAP_HARNESS_LOCK_HOME="$lh" DLAP_HARNESS_LOCK_BACKEND="$B" TMPDIR="$TMPROOT/tmpA" bash -c     "source '$LIB'; harness_lock_acquire holderA >/dev/null; : > '$lh/ready'; for _i in \$(seq 1 300); do sleep 0.1 9>&-; done" &
  hp=$!; BG_PIDS+=("$hp")
  for _n in $(seq 1 150); do [[ -e "$lh/ready" ]] && break; sleep 0.1; done
  out="$(env -u DLAP_HARNESS_LOCK_DIR DLAP_HARNESS_LOCK_HOME="$lh" DLAP_HARNESS_LOCK_BACKEND="$B" TMPDIR="$TMPROOT/tmpB"          DLAP_HARNESS_LOCK_TIMEOUT=3 bash -c "source '$LIB'; harness_lock_acquire holderB" 2>&1; echo "rc=$?")"
  contains "[$B] a second shell with another TMPDIR is refused while the first holds the lock" "$out" "rc=1"
  contains "[$B] ...and the lock it waited on is under the fixed base" "$out" "$lh/.dlap/harness-lock/"
  kill -9 "$hp" 2>/dev/null; wait "$hp" 2>/dev/null
done

# ---------------------------------------------------------------------------
section "the harness scripts use the lock and have no pipefail log wait"
# ---------------------------------------------------------------------------
for f in cloud-pipeline-e2e.sh cloud-lifecycle-e2e.sh gcp-pipeline-e2e.sh full-lifecycle-e2e.sh; do
  bash -n "$HERE/$f" 2>/dev/null && t_ok "$f parses" || t_fail "$f parses"
  grep -q 'source .*harness-lib.sh' "$HERE/$f" && t_ok "$f sources harness-lib.sh" || t_fail "$f sources harness-lib.sh"
  # harness_init must come before the first compose or Gradle call that is not inside a function
  first_init="$(grep -n 'harness_init ' "$HERE/$f" | head -n1 | cut -d: -f1)"
  first_use="$(grep -nE '^[^#]*("\$\{COMPOSE\[@\]\}"|"\$GRADLE") ' "$HERE/$f" | grep -v 'harness_init' | head -n1 | cut -d: -f1)"
  if [[ -n "$first_init" && -n "$first_use" && "$first_init" -lt "$first_use" ]]; then t_ok "$f takes the lock before its first compose or Gradle call (line $first_init < $first_use)"
  else t_fail "$f takes the lock first" "harness_init line '${first_init:-none}', first use '${first_use:-none}'"; fi
  if grep -nE '^[^#]*docker logs[^|]*\|[[:space:]]*grep[[:space:]]+-q' "$HERE/$f" >/dev/null; then t_fail "$f still pipes docker logs into grep -q"; else t_ok "$f has no 'docker logs | grep -q'"; fi
  if grep -nE '^[^#]*(localhost|127\.0\.0\.1):(8180|8082|8083)\b' "$HERE/$f" >/dev/null; then t_fail "$f hard-codes a host port in a URL"; else t_ok "$f reads host ports from DLAP_PORT_*"; fi
done

# ---------------------------------------------------------------------------
section "eviction of containers left by another project (docker)"
# ---------------------------------------------------------------------------
if [[ -n "$QUICK" ]]; then
  t_skip "eviction cases" "--quick"
elif ! docker info >/dev/null 2>&1; then
  t_skip "eviction cases" "no Docker daemon"
elif ! docker image inspect alpine:3.20 >/dev/null 2>&1 && ! docker pull -q alpine:3.20 >/dev/null 2>&1; then
  t_skip "eviction cases" "alpine:3.20 is not available"
else
  name="dlap-hlt-$$"
  oldp="dlap-e2e-hlt-old-$$"; devp="hlt-dev-$$"; newp="dlap-e2e-hlt-new-$$"
  COMPOSE_PROJECTS+=("$oldp" "$devp" "$newp")
  cat > "$TMPROOT/probe-compose.yml" <<YML
services:
  probe:
    image: alpine:3.20
    container_name: $name
    command: ["sleep", "300"]
    volumes:
      - probedata:/data
volumes:
  probedata:
YML
  exists() { [[ -n "$(MSYS_NO_PATHCONV=1 docker ps -a --filter "name=^$1\$" --format '{{.Names}}')" ]]; }
  vol()    { docker volume inspect "$1" >/dev/null 2>&1; }
  # shellcheck disable=SC2034  # COMPOSE is read by the sourced library
  evict()  { ( source "$LIB"; COMPOSE=(docker compose -f "$TMPROOT/probe-compose.yml"); export COMPOSE_PROJECT_NAME="$newp"; "$@" ) 2>&1; }

  docker compose -p "$oldp" -f "$TMPROOT/probe-compose.yml" up -d >/dev/null 2>&1
  if exists "$name"; then
    out="$(evict harness_evict_colliding)"
    contains "a leftover harness project is announced" "$out" "evicting leftover harness project '$oldp'"
    exists "$name" && t_fail "the leftover harness container is gone" || t_ok "the leftover harness container is gone"
    vol "${oldp}_probedata" && t_fail "the leftover harness project's volume is removed" || t_ok "the leftover harness project's volume is removed"
  else
    t_fail "probe container did not start (old harness project)"
  fi

  docker compose -p "$devp" -f "$TMPROOT/probe-compose.yml" up -d >/dev/null 2>&1
  if exists "$name"; then
    out="$(evict harness_evict_colliding)"
    contains "a non-harness project's container is announced" "$out" "evicting container $name (compose project '$devp'); its volumes are kept"
    exists "$name" && t_fail "the dev-style container is gone" || t_ok "the dev-style container is gone"
    vol "${devp}_probedata" && t_ok "the dev-style project's volume is kept" || t_fail "the dev-style project's volume is kept"
    docker volume rm "${devp}_probedata" >/dev/null 2>&1
  else
    t_fail "probe container did not start (dev-style project)"
  fi

  docker compose -p "$newp" -f "$TMPROOT/probe-compose.yml" up -d >/dev/null 2>&1
  if exists "$name"; then
    evict harness_evict_colliding >/dev/null
    exists "$name" && t_ok "a container of this run's own project is left alone by eviction" || t_fail "own project container survives eviction"
    evict harness_reset_stack >/dev/null
    exists "$name" && t_fail "harness_reset_stack removes the run's own project" || t_ok "harness_reset_stack removes the run's own project"
    vol "${newp}_probedata" && t_fail "harness_reset_stack removes the run's own volumes" || t_ok "harness_reset_stack removes the run's own volumes"
  else
    t_fail "probe container did not start (own project)"
  fi
fi

echo
echo "harness-lib self-test: $PASS passed, $FAIL failed, $SKIP skipped"
[[ $FAIL -eq 0 ]]
