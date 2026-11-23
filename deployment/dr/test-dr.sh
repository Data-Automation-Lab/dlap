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
# The named tests of the backup and restore package (DR-01).
#
#   L-nn  static checks of the files (syntax, header, dashes, executable bit, shellcheck when present)
#   O-nn  the merged compose model of the pilot with the backup overlay (docker compose config only)
#   U/K/B/R/S/P/A-nn  tests/unit.sh: every tool against a real PostgreSQL in a container
#   D-nn  restore-drill.sh: lose a stack, rebuild it from the off-host copy, compare every row
#   SD/SM/KR-nn  tests/stack-unit.sh (DR-02): stack-drill.sh arguments, the merged compose model of the full-stack
#                drill, keycloak-realm.sh against prepared volumes
#   KS-nn  tests/stack-unit.sh (DR-03): keycloak-realm-backup.sh, the scheduled host-side wrapper, and the cron and
#          systemd files against the guide
#   F-nn  stack-drill.sh (DR-02, only with --stack-drill): the whole quickstart stack, destroyed and restored
#
# Usage:  test-dr.sh [--quick] [--drill-rows N] [--stack-drill] [SECTION...]
#   --quick        everything except the drills (about 8 minutes)
#   --drill-rows   size of the DR-01 drill (default 20000 events in the first batch)
#   --stack-drill  also run the full-stack drill (about 25 minutes, needs the built service jars and about
#                  6 GB of memory; it takes the host-wide harness lock itself)
#   SECTION        run only these sections of tests/unit.sh (U K B R S P A)
# Needs Docker (and Compose 2.24 or later for the O tests). Without Docker the L tests run and the
# rest are reported as SKIP, which is not a pass: the exit status is 3.
# Exit: 0 every test passed; 1 a test failed; 3 nothing failed but some tests could not run.

set -uo pipefail
export MSYS_NO_PATHCONV=1

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
PILOT_DIR="$REPO_ROOT/deployment/environments/pilot"
OVERLAY="$PILOT_DIR/docker-compose.pilot.backup.yml"
GUIDE="$REPO_ROOT/docs/guides/backup-and-restore.md"
IMAGE="postgres:15.1"

QUICK=0
DRILL_ROWS=20000
STACK_DRILL=0
SECTIONS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --quick) QUICK=1; shift ;;
    --drill-rows) DRILL_ROWS="${2:?--drill-rows needs a number}"; shift 2 ;;
    --stack-drill) STACK_DRILL=1; shift ;;
    -h|--help) awk '/^# The named tests/ { on = 1 } on && /^#/ { sub(/^# ?/, ""); print; next } on { exit }' "${BASH_SOURCE[0]}"; exit 0 ;;
    U|K|B|R|S|P|A) SECTIONS+=("$1"); QUICK=1; shift ;;
    *) echo "test-dr.sh: unknown argument: $1" >&2; exit 2 ;;
  esac
done

PASSED=0
FAILED=0
SKIPPED=0
ok()   { PASSED=$((PASSED + 1)); echo "  ok    $*"; }
bad()  { FAILED=$((FAILED + 1)); echo "  FAIL  $*" >&2; }
skip() { SKIPPED=$((SKIPPED + 1)); echo "  SKIP  $*"; }
check() { local d="$1"; shift; if "$@" > /dev/null 2>&1; then ok "$d"; else bad "$d"; fi; }
hostpath() { if command -v cygpath > /dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

HAVE_DOCKER=0
if command -v docker > /dev/null 2>&1 && docker info > /dev/null 2>&1; then HAVE_DOCKER=1; fi
PYTHON=""
for c in python3 python; do if command -v "$c" > /dev/null 2>&1; then PYTHON="$c"; break; fi; done

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# =====================================================================================================
echo "== L static checks"

FILES=("$SCRIPT_DIR"/*.sh "$SCRIPT_DIR"/tests/unit.sh "$SCRIPT_DIR"/tests/stack-unit.sh)
COMPOSE_FILES=("$SCRIPT_DIR/docker-compose.drill.yml" "$SCRIPT_DIR/docker-compose.stack-drill.yml" "$SCRIPT_DIR/docker-compose.stack-drill.restore.yml")
# The cron file and the systemd units of the scheduled Keycloak realm backup (DR-03) run on Linux hosts.
HOST_FILES=("$SCRIPT_DIR/cron/dlap-keycloak-realm-backup" "$SCRIPT_DIR/systemd/dlap-keycloak-realm-backup.service" "$SCRIPT_DIR/systemd/dlap-keycloak-realm-backup.timer")
syntax_ok() { local f; for f in "${FILES[@]}"; do bash -n "$f" || return 1; done; }
check "L-01 every script parses (bash -n): ${#FILES[@]} files" syntax_ok

header_ok() {
  local f
  for f in "${FILES[@]}" "${COMPOSE_FILES[@]}" "${HOST_FILES[@]}" "$OVERLAY" "$SCRIPT_DIR/README.md" "$GUIDE"; do
    head -n 30 "$f" | grep -q 'Licensed to the Apache Software Foundation' || { echo "no Apache header: $f" >&2; return 1; }
    head -n 30 "$f" | grep -q 'www.apache.org/licenses/LICENSE-2.0' || { echo "no licence URL: $f" >&2; return 1; }
  done
}
check "L-02 every new file carries the Apache header" header_ok

nodash_ok() {
  local f
  for f in "${FILES[@]}" "${COMPOSE_FILES[@]}" "${HOST_FILES[@]}" "$OVERLAY" "$SCRIPT_DIR/README.md" "$GUIDE"; do
    if grep -q -e $'\xe2\x80\x94' -e $'\xe2\x80\x93' "$f"; then echo "em-dash or en-dash in $f" >&2; return 1; fi
  done
}
check "L-03 no em-dash or en-dash in any new file" nodash_ok

crlf_ok() {
  local f
  for f in "${FILES[@]}" "${COMPOSE_FILES[@]}" "${HOST_FILES[@]}" "$OVERLAY"; do
    if grep -q $'\r' "$f"; then echo "CR in $f" >&2; return 1; fi
  done
}
check "L-04 shell scripts and compose files use LF line endings (they run in Linux containers)" crlf_ok

# git is a native Windows program in Git Bash and cannot read /d/... paths while MSYS_NO_PATHCONV is set, so cd there.
if (cd "$REPO_ROOT" && git rev-parse --is-inside-work-tree > /dev/null 2>&1) && [ -n "$(cd "$REPO_ROOT" && git ls-files "deployment/dr/*.sh")" ]; then
  exec_ok() { ! (cd "$REPO_ROOT" && git ls-files -s "deployment/dr/*.sh" "deployment/dr/tests/*.sh") | grep -v '^100755' | grep -q .; }
  check "L-05 every script is committed with the executable bit" exec_ok
else
  skip "L-05 executable bit (the files are not committed yet)"
fi

if command -v shellcheck > /dev/null 2>&1; then
  check "L-06 shellcheck reports no warning or error" shellcheck -x -S warning -s bash "${FILES[@]}"
elif [ "$HAVE_DOCKER" -eq 1 ] && [ -n "${DLAP_SHELLCHECK_IMAGE:-}" ]; then
  # For example DLAP_SHELLCHECK_IMAGE=rhysd/actionlint:1.7.12, which carries shellcheck.
  in_container=()
  for f in "${FILES[@]}"; do in_container+=("/mnt/${f#"$SCRIPT_DIR"/}"); done
  check "L-06 shellcheck (in $DLAP_SHELLCHECK_IMAGE) reports no warning or error"     docker run --rm -v "$(hostpath "$SCRIPT_DIR"):/mnt:ro" --entrypoint shellcheck "$DLAP_SHELLCHECK_IMAGE" -x -S warning -s bash "${in_container[@]}"
else
  skip "L-06 shellcheck is not installed (set DLAP_SHELLCHECK_IMAGE to run it in a container)"
fi

no_secret_ok() {
  # The overlay and the drill file hold no literal credential: every secret is a variable reference.
  ! grep -nE '(PASSWORD|SECRET|PASSPHRASE|KEY)=[^$[:space:]"]' "$OVERLAY" "${COMPOSE_FILES[@]}" | grep -v 'PASSPHRASE_FILE=\|FINGERPRINT=\|KEEP\|_KEY_FINGERPRINT' | grep -q .
}
check "L-07 the compose files contain no literal secret" no_secret_ok

# =====================================================================================================
echo "== O overlay model"

if [ "$HAVE_DOCKER" -eq 0 ] || [ -z "$PYTHON" ]; then
  skip "O-01..O-14 need Docker with Compose and python"
else
  ENVF="$TMP/env"
  cp "$PILOT_DIR/.env.pilot.example" "$ENVF"
  model() { # <env file> -> JSON of the merged model on stdout
    local -a args=() a
    while IFS= read -r a; do
      if [[ "$a" == /* ]] && [ -e "$a" ]; then a="$(hostpath "$a")"; fi
      args+=("$a")
    done < <(COMPOSE_PROJECT_NAME=dlap-dr-test bash "$PILOT_DIR/up.sh" --env-file "$1" --print-args)
    docker compose "${args[@]}" config --format json 2> /dev/null
  }
  model "$ENVF" > "$TMP/model.json"
  { cat "$ENVF"; echo "DLAP_BACKUP_DIR=/srv/dlap-backups"; echo "DLAP_BACKUP_PASSPHRASE_FILE=/etc/dlap/backup-passphrase"; echo "DLAP_BACKUP_SHIP_CMD='rclone copyto \"\$1\" \"offsite:dlap/\$2\"'"; } > "$TMP/env2"
  model "$TMP/env2" > "$TMP/model2.json"
  # py <model> <expression over s (services)>: exit 0 when the expression is true.
  py() { "$PYTHON" -c "
import json, sys
s = json.load(open(sys.argv[1]))['services']
b = s.get('dlap-backup', {})
i = s.get('dlap-dr-init', {})
p = s.get('postgresql', {})
sys.exit(0 if ($2) else 1)" "$(hostpath "$TMP/$1")"; }

  check "O-01 the merged model has dlap-backup and dlap-dr-init" py model.json "'dlap-backup' in s and 'dlap-dr-init' in s"
  check "O-02 the agent runs the same image as the database (the client must match the server)" py model.json "b['image'] == p['image'] == 'postgres:15.1'"
  check "O-03 the agent shares the network of the database, publishes no port and has no network of its own" py model.json "b['network_mode'] == 'service:postgresql' and not b.get('ports') and not b.get('networks')"
  check "O-04 the agent runs as uid 999 on a read-only root file system with every capability dropped" py model.json "b['user'] == '999:999' and b['read_only'] is True and b['cap_drop'] == ['ALL'] and not b.get('cap_add')"
  check "O-05 the agent cannot gain privileges and has a memory and a process limit" py model.json "'no-new-privileges:true' in b['security_opt'] and b['deploy']['resources']['limits']['memory'] and b['deploy']['resources']['limits']['pids']"
  check "O-06 the agent waits for a healthy database and for the volume to be prepared" py model.json "b['depends_on']['postgresql']['condition'] == 'service_healthy' and b['depends_on']['dlap-dr-init']['condition'] == 'service_completed_successfully'"
  check "O-07 the scripts are mounted read-only at /opt/dr" py model.json "any(v['target'] == '/opt/dr' and v.get('read_only') and v['source'].replace('\\\\', '/').endswith('deployment/dr') for v in b['volumes'])"
  check "O-08 by default the backups go to the named volume dlap-backups and the passphrase mount is an empty file" py model.json "any(v['target'] == '/backups' and v['type'] == 'volume' and v['source'] == 'dlap-backups' for v in b['volumes']) and any(v['target'] == '/run/secrets/dlap_backup_passphrase' and v['source'] == '/dev/null' for v in b['volumes'])"
  check "O-09 DLAP_BACKUP_DIR and DLAP_BACKUP_PASSPHRASE_FILE in the environment file select a host folder and a host file" py model2.json "any(v['target'] == '/backups' and v['type'] == 'bind' and v['source'] == '/srv/dlap-backups' for v in b['volumes']) and any(v['target'] == '/run/secrets/dlap_backup_passphrase' and v['source'] == '/etc/dlap/backup-passphrase' for v in b['volumes'])"
  check "O-10 DLAP_BACKUP_SHIP_CMD, single-quoted in the environment file, is passed on with \$1 and \$2 not expanded (config prints \$ as \$\$; the drill checks the running container)" py model2.json "b['environment']['DLAP_BACKUP_SHIP_CMD'] == 'rclone copyto \"\$\$1\" \"offsite:dlap/\$\$2\"'"
  check "O-11 the health check is agent.sh status --check" py model.json "b['healthcheck']['test'][-3:] == ['status', '--check', '--quiet']"
  check "O-12 the overlay leaves the database service alone: the base overlay's command and loopback port stand" py model.json "p['ports'][0]['host_ip'] == '127.0.0.1' and 'log_statement=none' in p['command']"
  check "O-13 the overlay sorts before the base overlay (byte order), as its header says" bash -c "[ \"\$(COMPOSE_PROJECT_NAME=x bash $PILOT_DIR/up.sh --env-file $ENVF --print-args | grep 'docker-compose.pilot' | sed 's#.*/##' | head -n 2 | tr '\n' ' ')\" = 'docker-compose.pilot.backup.yml docker-compose.pilot.base.yml ' ]"
  verify_out="$(bash "$PILOT_DIR/verify.sh" 2>&1 || true)"
  check "O-14 the pilot's own verify.sh finds nothing wrong with dlap-backup or dlap-dr-init" bash -c "! grep -q 'dlap-backup\|dlap-dr-init' <<< \"\$1\"" _ "$verify_out"
fi

# =====================================================================================================
echo "== U K B R S P A: the tools against a real PostgreSQL ($IMAGE)"

if [ "$HAVE_DOCKER" -eq 0 ]; then
  skip "U/K/B/R/S/P/A need Docker"
else
  docker image inspect "$IMAGE" > /dev/null 2>&1 || docker pull "$IMAGE" > /dev/null 2>&1
  UNIT_LOG="$TMP/unit.log"
  docker run --rm --user postgres -v "$(hostpath "$SCRIPT_DIR"):/opt/dr:ro" "$IMAGE" bash /opt/dr/tests/unit.sh "${SECTIONS[@]}" 2>&1 | tee "$UNIT_LOG" | grep -v '^NOTICE'
  unit_passed="$(grep -c '^  ok ' "$UNIT_LOG" || true)"
  unit_failed="$(grep -c '^  FAIL ' "$UNIT_LOG" || true)"
  PASSED=$((PASSED + unit_passed))
  FAILED=$((FAILED + unit_failed))
  if ! grep -q '^unit.sh: ' "$UNIT_LOG"; then bad "unit.sh did not finish (no summary line)"; fi
fi

# =====================================================================================================
echo "== SD SM KR KS: the full-stack drill tools (tests/stack-unit.sh)"

if [ "${#SECTIONS[@]}" -gt 0 ]; then
  skip "SD/SM/KR/KS (sections of unit.sh were given)"
else
  SU_LOG="$TMP/stack-unit.log"
  bash "$SCRIPT_DIR/tests/stack-unit.sh" 2>&1 | tee "$SU_LOG"
  su_passed="$(grep -c '^  ok ' "$SU_LOG" || true)"
  su_failed="$(grep -c '^  FAIL ' "$SU_LOG" || true)"
  su_skipped="$(grep -c '^  SKIP ' "$SU_LOG" || true)"
  PASSED=$((PASSED + su_passed))
  FAILED=$((FAILED + su_failed))
  SKIPPED=$((SKIPPED + su_skipped))
  if ! grep -q '^stack-unit.sh: ' "$SU_LOG"; then bad "stack-unit.sh did not finish (no summary line)"; fi
fi

# =====================================================================================================
echo "== D restore drill"

if [ "$QUICK" -eq 1 ]; then
  skip "D-nn the restore drill (--quick or sections were given)"
elif [ "$HAVE_DOCKER" -eq 0 ]; then
  skip "D-nn need Docker"
else
  DRILL_OUT="$(mktemp -d)"
  if [ -f "$REPO_ROOT/e2e-tests/with-harness-lock.sh" ]; then
    bash "$REPO_ROOT/e2e-tests/with-harness-lock.sh" --label dr-drill -- bash "$SCRIPT_DIR/restore-drill.sh" --rows "$DRILL_ROWS" --out "$DRILL_OUT" | sed 's/^/    /'
  else
    bash "$SCRIPT_DIR/restore-drill.sh" --rows "$DRILL_ROWS" --out "$DRILL_OUT" | sed 's/^/    /'
  fi
  drill_rc=${PIPESTATUS[0]}
  dp="$(grep -c '^CHECK_[0-9]*=pass' "$DRILL_OUT/results.env" 2> /dev/null || true)"
  df="$(grep -c '^CHECK_FAILED_' "$DRILL_OUT/results.env" 2> /dev/null || true)"
  PASSED=$((PASSED + dp))
  FAILED=$((FAILED + df))
  if [ "$drill_rc" -ne 0 ] && [ "$df" -eq 0 ]; then bad "D-00 the drill stopped early (exit $drill_rc); evidence in $DRILL_OUT"; fi
  echo "    drill evidence: $DRILL_OUT"
fi

# =====================================================================================================
echo "== F full-stack restore drill"

if [ "$STACK_DRILL" -eq 0 ]; then
  skip "F-nn the full-stack drill (add --stack-drill; about 25 minutes)"
elif [ "$HAVE_DOCKER" -eq 0 ]; then
  skip "F-nn need Docker"
else
  STACK_OUT="$(mktemp -d)"
  bash "$SCRIPT_DIR/stack-drill.sh" --out "$STACK_OUT" | sed 's/^/    /'
  stack_rc=${PIPESTATUS[0]}
  fp="$(grep -c '^CHECK_[0-9]*=pass' "$STACK_OUT/results.env" 2> /dev/null || true)"
  ff="$(grep -c '^CHECK_FAILED_' "$STACK_OUT/results.env" 2> /dev/null || true)"
  PASSED=$((PASSED + fp))
  FAILED=$((FAILED + ff))
  if [ "$stack_rc" -ne 0 ] && [ "$ff" -eq 0 ]; then bad "F-00 the full-stack drill stopped early (exit $stack_rc); evidence in $STACK_OUT"; fi
  echo "    full-stack drill evidence: $STACK_OUT"
fi

echo
echo "test-dr.sh: $PASSED passed, $FAILED failed, $SKIPPED skipped"
[ "$FAILED" -eq 0 ] || exit 1
[ "$HAVE_DOCKER" -eq 1 ] || exit 3
exit 0
