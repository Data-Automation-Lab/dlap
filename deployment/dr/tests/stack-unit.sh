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
# Named tests of the full-stack drill tools (DR-02). They run on the host and use Docker only for throwaway
# containers and volumes of their own (no Compose project, no published port), so they can run beside a stack.
#
#   SD-nn  stack-drill.sh: arguments and refusals (nothing is started, the lock is not taken)
#   SM-nn  the merged compose model of the drill: the dev pair, the backup overlay, the e2e override and the
#          two drill overrides (docker compose config only)
#   KR-nn  keycloak-realm.sh: refusals, restore from a prepared volume, wrong passphrase, damaged file, wipe
#   KS-nn  keycloak-realm-backup.sh (DR-03): the scheduled host-side wrapper (shipping order, retry, retention,
#          status and check, lock, settings file) against real volumes with a stand-in export tool, and the cron
#          and systemd files against the guide
#
# Usage:  stack-unit.sh [SD|SM|KR|KS ...]
# Output: one line per test ("  ok" or "  FAIL"), then "stack-unit.sh: N passed, M failed, K skipped".
# Exit: 0 all passed; 1 a test failed; 3 nothing failed but Docker is missing.

set -uo pipefail
export MSYS_NO_PATHCONV=1

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DR="$(cd "$HERE/.." && pwd)"
REPO_ROOT="$(cd "$DR/../.." && pwd)"
IMAGE="postgres:15.1"
DRILL="$DR/stack-drill.sh"
KCR="$DR/keycloak-realm.sh"
KCB="$DR/keycloak-realm-backup.sh"

SECTIONS=("$@")
want() { local s; [ "${#SECTIONS[@]}" -eq 0 ] && return 0; for s in "${SECTIONS[@]}"; do [ "$s" = "$1" ] && return 0; done; return 1; }

PASSED=0
FAILED=0
SKIPPED=0
ok() { PASSED=$((PASSED + 1)); echo "  ok    $*"; }
bad() { FAILED=$((FAILED + 1)); echo "  FAIL  $*" >&2; }
skip() { SKIPPED=$((SKIPPED + 1)); echo "  SKIP  $*"; }
check() { local d="$1"; shift; if "$@" > /dev/null 2>&1; then ok "$d"; else bad "$d"; fi; }
hostpath() { if command -v cygpath > /dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
# rc <command...>: the exit status of the command, with its output discarded.
rc() { "$@" > /dev/null 2>&1; echo $?; }

HAVE_DOCKER=0
if command -v docker > /dev/null 2>&1 && docker info > /dev/null 2>&1; then HAVE_DOCKER=1; fi
PYTHON=""
for c in python3 python; do if command -v "$c" > /dev/null 2>&1; then PYTHON="$c"; break; fi; done
TMP="$(mktemp -d)"
RUN="$$-$RANDOM"
VOLS=()
CONTAINERS=()
cleanup() {
  local v c
  for c in "${CONTAINERS[@]+"${CONTAINERS[@]}"}"; do docker rm -f "$c" > /dev/null 2>&1 || true; done
  for v in "${VOLS[@]+"${VOLS[@]}"}"; do docker volume rm -f "$v" > /dev/null 2>&1 || true; done
  rm -rf "$TMP"
}
trap cleanup EXIT
# newvol <name>: creates a volume and sets NEWVOL; no command substitution, so the cleanup trap sees VOLS.
newvol() { local v="dlap-su-$RUN-$1"; docker volume create "$v" > /dev/null; VOLS+=("$v"); NEWVOL="$v"; }

# =====================================================================================================
if want SD; then
  echo "== SD stack-drill.sh arguments"
  check "SD-01 --help prints the usage and exits 0 without taking the lock" bash -c "bash '$DRILL' --help | grep -q 'Full-stack restore drill'"
  check "SD-02 an unknown argument is a refusal (exit 2)" test "$(rc bash "$DRILL" --nonsense)" = 2
  check "SD-03 --restore accepts only logical, pitr or both (exit 2 otherwise)" test "$(rc bash "$DRILL" --restore sideways)" = 2
  check "SD-04 --stop-after accepts only seed or protect (exit 2 otherwise)" test "$(rc bash "$DRILL" --stop-after never)" = 2
  check "SD-05 --out without a value is a refusal" test "$(rc bash "$DRILL" --out)" = 2
  check "SD-09 --rows must be a whole number (exit 2 otherwise)" test "$(rc bash "$DRILL" --rows many)" = 2
  check "SD-06 the script takes the host-wide harness lock and uses the harness project name" bash -c "grep -q 'harness_init stack-drill' '$DRILL' && grep -q 'harness_lock_release' '$DRILL'"
  nolit() { ! grep -nE '(PASSWORD|SECRET|PASSPHRASE)=[A-Za-z0-9+/]{6,}' "$DRILL" "$KCR" | grep -q .; }
  check "SD-07 the scripts hold no literal credential (every secret is generated or comes from DLAP_DRILL_*)" nolit
  check "SD-08 the drill checks the properties the guide promises: digests, Flyway history, audit-verify, health, login" bash -c "for k in 'digest-backup-point' 'flyway_history' 'audit_verify' 'actuator/health' 'kc_token'; do grep -q \"\$k\" '$DRILL' || exit 1; done"
fi

# =====================================================================================================
if want SM; then
  echo "== SM compose model of the drill"
  if [ "$HAVE_DOCKER" -eq 0 ] || [ -z "$PYTHON" ]; then
    skip "SM-01..SM-08 need Docker with Compose and python"
  else
    printf 'POSTGRES_PASSWORD=model-check\nDLAP_BACKUP_SHIP_CMD='"'"'cp "$1" "/remote/$2"'"'"'\n' > "$TMP/env"
    : > "$TMP/pass"
    model() { # <1 with the restore override|0 without> -> JSON of the merged model on stdout
      local -a files=(-f "$(hostpath "$REPO_ROOT/deployment/environments/dev/docker-compose.yml")"
        -f "$(hostpath "$REPO_ROOT/deployment/environments/dev/docker-compose.microservices.yml")"
        -f "$(hostpath "$REPO_ROOT/deployment/environments/pilot/docker-compose.pilot.backup.yml")"
        -f "$(hostpath "$REPO_ROOT/e2e-tests/docker-compose.e2e.yml")"
        -f "$(hostpath "$DR/docker-compose.stack-drill.yml")")
      [ "$1" = 1 ] && files+=(-f "$(hostpath "$DR/docker-compose.stack-drill.restore.yml")")
      DR_STACK_REMOTE=model-remote DR_STACK_SOURCE=model-source DR_STACK_KC_IMPORT=model-kc \
        DLAP_E2E_FIXTURES_DIR="$(hostpath "$REPO_ROOT/e2e-tests/fixtures/replay")" DLAP_BACKUP_PASSPHRASE_FILE="$(hostpath "$TMP/pass")" \
        docker compose --env-file "$(hostpath "$TMP/env")" "${files[@]}" config --format json 2> /dev/null
    }
    model 0 > "$TMP/m0.json"
    model 1 > "$TMP/m1.json"
    py() { "$PYTHON" -c "
import json, sys
s = json.load(open(sys.argv[1]))['services']
v = json.load(open(sys.argv[1])).get('volumes', {})
b = s.get('dlap-backup', {})
k = s.get('keycloak', {})
sys.exit(0 if ($2) else 1)" "$(hostpath "$TMP/$1")"; }
    check "SM-01 the merged model has the ten DLAP services, PostgreSQL, Kafka, Keycloak and the backup agent" py m0.json "all(n in s for n in ['da-admin-service','da-autoarchival','da-autocompliance','da-autodelete','da-autolabel','da-catalog','da-discovery','da-logingest','da-policyengine','da-reporting','postgresql','kafka','keycloak','dlap-backup'])"
    check "SM-02 without the restore override Keycloak imports the baseline realm folder of git" py m0.json "any(x['target'] == '/opt/keycloak/data/import' and x['type'] == 'bind' and x['source'].replace('\\\\','/').endswith('deployment/platform/keycloak/keycloak-import') for x in k['volumes'])"
    check "SM-03 with the restore override the same mount is the import volume, read-only, and only one mount has that target" py m1.json "[x for x in k['volumes'] if x['target'] == '/opt/keycloak/data/import'] and len([x for x in k['volumes'] if x['target'] == '/opt/keycloak/data/import']) == 1 and [x for x in k['volumes'] if x['target'] == '/opt/keycloak/data/import'][0]['type'] == 'volume' and [x for x in k['volumes'] if x['target'] == '/opt/keycloak/data/import'][0].get('read_only')"
    check "SM-04 the agent mounts the off-host volume at /remote (writable) and the lost stack's volume at /source (read-only)" py m0.json "any(x['target'] == '/remote' and not x.get('read_only') for x in b['volumes']) and any(x['target'] == '/source' and x.get('read_only') for x in b['volumes'])"
    check "SM-05 both off-host volumes are external, so they survive down -v" py m0.json "v['drill-remote'].get('external') and v['drill-source'].get('external')"
    check "SM-06 the agent ships every 30 seconds in the drill" py m0.json "'DLAP_WAL_CYCLE_SECONDS=30' in [e for e in ['%s=%s' % (a, c) for a, c in b['environment'].items()]]"
    check "SM-07 the e2e override is part of the model: the delete executor stays in dry-run mode" py m0.json "s['da-autodelete']['environment'].get('DLAP_AUTODELETE_DRY_RUN_CLOUD_DELETE') == 'true'"
    check "SM-08 the agent still shares the database container's network and runs as uid 999" py m0.json "b['network_mode'] == 'service:postgresql' and b['user'] == '999:999'"
    check "SM-09 the model needs DR_STACK_REMOTE: without it docker compose config fails" bash -c "! DR_STACK_SOURCE=x DR_STACK_KC_IMPORT=y docker compose --env-file '$(hostpath "$TMP/env")' -f '$(hostpath "$DR/docker-compose.stack-drill.yml")' config > /dev/null 2>&1"
  fi
fi

# =====================================================================================================
if want KR; then
  echo "== KR keycloak-realm.sh"
  check "KR-01 --help prints the usage and exits 0" bash -c "bash '$KCR' --help | grep -q 'Backup and restore of the Keycloak realm'"
  check "KR-02 an unknown command is a refusal (exit 2)" test "$(rc bash "$KCR" explode)" = 2
  if [ "$HAVE_DOCKER" -eq 0 ]; then
    skip "KR-03..KR-14 need Docker"
  else
    docker image inspect "$IMAGE" > /dev/null 2>&1 || docker pull "$IMAGE" > /dev/null 2>&1
    PASS="$TMP/passphrase"
    printf '%s\n' "kr-test-passphrase-$RUN" > "$PASS"
    WRONG="$TMP/wrong"
    printf '%s\n' "another-passphrase-$RUN" > "$WRONG"
    newvol src
    SRC="$NEWVOL"
    newvol imp
    IMP="$NEWVOL"
    # A realm file of a believable size, encrypted the way keycloak-realm.sh backup writes it.
    "${PYTHON:-python3}" -c "
import json
r = {'realm': 'dlap', 'enabled': True, 'users': [{'username': 'u%d' % i, 'credentials': [{'type': 'password', 'secretData': 'x' * 40}]} for i in range(30)]}
open('$(hostpath "$TMP/realm.json")', 'w').write(json.dumps(r, indent=1))" 2> /dev/null || printf '{"realm": "dlap", "pad": "%s"}\n' "$(head -c 1500 /dev/zero | tr '\0' x)" > "$TMP/realm.json"
    enc() { docker run --rm -i -v "$(hostpath "$1"):/run/secrets/dlap_backup_passphrase:ro" --entrypoint openssl "$IMAGE" enc -aes-256-cbc -pbkdf2 -iter 600000 -md sha256 -salt -pass file:/run/secrets/dlap_backup_passphrase; }
    put() { docker run --rm -i -v "$SRC:/v" --entrypoint bash "$IMAGE" -c "mkdir -p \"/v/\$(dirname \"$1\")\" && cat > \"/v/$1\""; }
    enc "$PASS" < "$TMP/realm.json" | put "kc/ok/dlap-realm.json.enc"
    sum="$(docker run --rm -v "$SRC:/v:ro" --entrypoint bash "$IMAGE" -c "sha256sum /v/kc/ok/dlap-realm.json.enc | cut -d' ' -f1" | tr -d '\r\n')"
    printf '%s  dlap-realm.json.enc\n' "$sum" | put "kc/ok/SHA256SUMS.shipped"
    # An interrupted backup: the encrypted file is there, the marker is not.
    enc "$PASS" < "$TMP/realm.json" | put "kc/nomarker/dlap-realm.json.enc"
    # A damaged one: the marker names another checksum.
    enc "$PASS" < "$TMP/realm.json" | put "kc/damaged/dlap-realm.json.enc"
    printf '%s  dlap-realm.json.enc\n' "0000000000000000000000000000000000000000000000000000000000000000" | put "kc/damaged/SHA256SUMS.shipped"
    kr() { DLAP_BACKUP_PASSPHRASE_FILE="$1" bash "$KCR" "${@:2}"; }
    imp_has() { docker run --rm -v "$IMP:/v:ro" --entrypoint bash "$IMAGE" -c "$1"; }

    check "KR-03 backup without a passphrase file is a refusal (the realm holds password hashes)" test "$(rc env -u DLAP_BACKUP_PASSPHRASE_FILE bash "$KCR" backup --container x --realm dlap --to-volume "$SRC" --key kc/x)" = 2
    check "KR-04 backup of a container that does not exist is a refusal" test "$(rc kr "$PASS" backup --container dlap-no-such-container --realm dlap --to-volume "$SRC" --key kc/x)" = 2
    check "KR-05 a key prefix that climbs out of the volume is refused" test "$(rc kr "$PASS" restore --from-volume "$SRC" --key ../etc --realm dlap --import-volume "$IMP")" = 2
    check "KR-06 a realm name with a path in it is refused" test "$(rc kr "$PASS" restore --from-volume "$SRC" --key kc/ok --realm ../x --import-volume "$IMP")" = 2
    check "KR-07 restore refuses a prefix without SHA256SUMS.shipped (an interrupted backup) and writes nothing" bash -c "[ \"\$(DLAP_BACKUP_PASSPHRASE_FILE='$PASS' bash '$KCR' restore --from-volume '$SRC' --key kc/nomarker --realm dlap --import-volume '$IMP' > /dev/null 2>&1; echo \$?)\" = 2 ]"
    check "KR-08 restore refuses a file that does not match its checksum" test "$(rc kr "$PASS" restore --from-volume "$SRC" --key kc/damaged --realm dlap --import-volume "$IMP")" = 2
    check "KR-09 after the refusals the import volume is still empty" imp_has 'test -z "$(ls -A /v)"'
    check "KR-10 a wrong passphrase fails (exit 1) and leaves no realm file behind" bash -c "[ \"\$(DLAP_BACKUP_PASSPHRASE_FILE='$WRONG' bash '$KCR' restore --from-volume '$SRC' --key kc/ok --realm dlap --import-volume '$IMP' > /dev/null 2>&1; echo \$?)\" = 1 ] && docker run --rm -v '$IMP:/v:ro' --entrypoint bash $IMAGE -c 'test -z \"\$(ls -A /v)\"'"
    check "KR-11 restore with the right passphrase exits 0" test "$(rc kr "$PASS" restore --from-volume "$SRC" --key kc/ok --realm dlap --import-volume "$IMP")" = 0
    check "KR-12 the restored realm file is byte for byte the exported one" bash -c "docker run --rm -v '$IMP:/v:ro' --entrypoint cat $IMAGE /v/dlap-realm.json | cmp -s - '$TMP/realm.json'"
    check "KR-13 the restored file is readable by the Keycloak user (mode 644)" imp_has 'test "$(stat -c %a /v/dlap-realm.json)" = 644'
    not_plain() { ! src_has_plain_realm; }
    src_has_plain_realm() { docker run --rm -v "$SRC:/v:ro" --entrypoint bash "$IMAGE" -c 'grep -rl "\"realm\"" /v' | grep -q .; }
    check "KR-14 the stored object is encrypted: the volume holds no plain realm" not_plain
    check "KR-15 wipe empties the import volume" bash -c "bash '$KCR' wipe --import-volume '$IMP' > /dev/null 2>&1 && docker run --rm -v '$IMP:/v:ro' --entrypoint bash $IMAGE -c 'test -z \"\$(ls -A /v)\"'"
  fi
fi

# =====================================================================================================
if want KS; then
  echo "== KS keycloak-realm-backup.sh"
  GUIDE="$REPO_ROOT/docs/guides/backup-and-restore.md"
  check "KS-01 --help prints the usage and exits 0" bash -c "bash '$KCB' --help | grep -q 'Scheduled backup of the Keycloak realm'"
  check "KS-02 an unknown argument and an unknown environment file are refusals (exit 2)" bash -c "[ \"\$(bash '$KCB' --nonsense > /dev/null 2>&1; echo \$?)\" = 2 ] && [ \"\$(bash '$KCB' run --env-file /nonexistent > /dev/null 2>&1; echo \$?)\" = 2 ]"
  # The files the guide promises: every active line of the cron file and of both unit files is in the guide, word for word.
  files_in_guide() {
    local f line lf="$TMP/guide-lf.md"
    tr -d '\r' < "$GUIDE" > "$lf"
    for f in "$DR/cron/dlap-keycloak-realm-backup" "$DR/systemd/dlap-keycloak-realm-backup.service" "$DR/systemd/dlap-keycloak-realm-backup.timer"; do
      while IFS= read -r line; do
        grep -qxF -- "$line" "$lf" || { echo "not in the guide: $line" >&2; return 1; }
      done < <(grep -v -e '^[[:space:]]*#' -e '^[[:space:]]*$' "$f")
    done
  }
  check "KS-03 the guide shows the cron file and both systemd units line for line" files_in_guide
  check "KS-04 the cron line and the service run the script with run --env-file /etc/dlap/keycloak-realm-backup.env" bash -c "grep -q 'keycloak-realm-backup.sh run --env-file /etc/dlap/keycloak-realm-backup.env' '$DR/cron/dlap-keycloak-realm-backup' && grep -q '^ExecStart=.*keycloak-realm-backup.sh run --env-file /etc/dlap/keycloak-realm-backup.env' '$DR/systemd/dlap-keycloak-realm-backup.service' && test -f '$KCB'"
  check "KS-05 the timer is daily and persistent, the service is a oneshot, the timer is wanted by timers.target" bash -c "grep -q '^OnCalendar=\*-\*-\* [0-9:]* UTC\$' '$DR/systemd/dlap-keycloak-realm-backup.timer' && grep -q '^Persistent=true\$' '$DR/systemd/dlap-keycloak-realm-backup.timer' && grep -q '^Type=oneshot\$' '$DR/systemd/dlap-keycloak-realm-backup.service' && grep -q '^WantedBy=timers.target\$' '$DR/systemd/dlap-keycloak-realm-backup.timer'"
  check "KS-06 the cron file has a user field on each job (cron.d format) and a check job with --quiet" bash -c "grep -qE '^[0-9*/]+ [0-9*/]+ \* \* \* root ' '$DR/cron/dlap-keycloak-realm-backup' && grep -q 'check --quiet' '$DR/cron/dlap-keycloak-realm-backup'"

  if [ "$HAVE_DOCKER" -eq 0 ]; then
    skip "KS-07..KS-30 need Docker"
  else
    docker image inspect "$IMAGE" > /dev/null 2>&1 || docker pull "$IMAGE" > /dev/null 2>&1
    KSP="$TMP/ks-pass"
    printf '%s\n' "ks-test-passphrase-$RUN" > "$KSP"
    KS_CONT="dlap-ks-kc-$RUN"
    docker create --name "$KS_CONT" "$IMAGE" > /dev/null
    CONTAINERS+=("$KS_CONT")
    # A stand-in for keycloak-realm.sh backup: the same files in the same layout, without a Keycloak behind it.
    printf '{"realm": "dlap", "users": [%s]}\n' "$(head -c 400 /dev/zero | tr '\0' x | sed 's/x/"u",/g' | sed 's/,$//')" > "$TMP/ks-realm.json"
    cat > "$TMP/ks-tool.sh" << 'TOOL'
#!/usr/bin/env bash
set -euo pipefail
export MSYS_NO_PATHCONV=1
[ "${FAKE_FAIL:-0}" != 1 ] || { echo "stand-in export failed" >&2; exit 1; }
shift
while [ $# -gt 0 ]; do case "$1" in --container) C="$2" ;; --realm) R="$2" ;; --to-volume) V="$2" ;; --key) K="$2" ;; esac; shift 2; done
hp() { if command -v cygpath > /dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
vol() { docker run --rm -i -v "$V:/v" --entrypoint bash "${FAKE_IMAGE}" -c "$1"; }
docker run --rm -i -v "$(hp "$DLAP_BACKUP_PASSPHRASE_FILE"):/run/secrets/dlap_backup_passphrase:ro" --entrypoint openssl "${FAKE_IMAGE}" \
  enc -aes-256-cbc -pbkdf2 -iter 600000 -md sha256 -salt -pass file:/run/secrets/dlap_backup_passphrase < "$FAKE_REALM" \
  | vol "mkdir -p \"/v/$K\" && cat > \"/v/$K/$R-realm.json.enc\""
sum="$(vol "sha256sum \"/v/$K/$R-realm.json.enc\" | cut -d' ' -f1" < /dev/null | tr -d '\r\n')"
printf 'WHAT=keycloak-realm\nREALM=%s\nCONTAINER=%s\n' "$R" "$C" | vol "cat > \"/v/$K/manifest.env\""
printf '%s  %s\n' "$sum" "$R-realm.json.enc" | vol "cat > \"/v/$K/SHA256SUMS.shipped\""
TOOL
    export FAKE_IMAGE="$IMAGE" FAKE_REALM="$TMP/ks-realm.json"
    newvol ks1
    KSV="$NEWVOL"
    KS_STATE="$TMP/ks-state"
    OFF="$TMP/ks-off"
    LOG="$TMP/ks-order.log"
    mkdir -p "$OFF"
    # ksrun <command...>: the wrapper with the stand-in tool, a volume and a state folder of its own; settings can be prefixed as NAME=value
    ksrun() {
      env DLAP_KC_TOOL="$TMP/ks-tool.sh" DLAP_KC_CONTAINER="$KS_CONT" DLAP_KC_REALM=dlap DLAP_KC_BACKUP_VOLUME="$KSV" DLAP_KC_STATE_DIR="$KS_STATE" \
        DLAP_BACKUP_PASSPHRASE_FILE="$KSP" DLAP_DR_IMAGE="$IMAGE" "$@"
    }
    export -f ksrun rc
    export KS_CONT KSV KS_STATE KSP IMAGE TMP KCB
    SHIP='mkdir -p "$(dirname "'"$OFF"'/$2")" && echo "$2" >> "'"$LOG"'" && cp "$1" "'"$OFF"'/$2"'
    sets_in_volume() { docker run --rm -v "$KSV:/v:ro" --entrypoint bash "$IMAGE" -c "ls -1 /v/keycloak 2> /dev/null | wc -l" | tr -d '\r '; }
    sets_off_host() { find "$OFF/keycloak" -name SHA256SUMS.shipped 2> /dev/null | wc -l | tr -d ' '; }
    export -f sets_in_volume sets_off_host
    export OFF LOG

    check "KS-07 without a passphrase file the run is a refusal (exit 2)" test "$(rc env -u DLAP_BACKUP_PASSPHRASE_FILE DLAP_KC_STATE_DIR="$KS_STATE" bash "$KCB" run)" = 2
    check "KS-08 a Keycloak container that does not exist is a refusal (exit 2)" test "$(rc ksrun DLAP_KC_CONTAINER=dlap-no-such-container bash "$KCB" run)" = 2
    check "KS-09 a settings value that is not a number is a refusal (exit 2)" test "$(rc ksrun DLAP_KC_KEEP=many bash "$KCB" run)" = 2

    # 1. no off-host command: the set is made, the run warns, the status says it was not shipped
    ksrun bash "$KCB" run > "$TMP/ks-run1.log" 2>&1; ks1=$?
    check "KS-10 without DLAP_BACKUP_SHIP_CMD the run succeeds, warns that the copy stays on the host, and records SHIPPED=no" bash -c "[ $ks1 -eq 0 ] && grep -q 'stays on this host' '$TMP/ks-run1.log' && grep -q '^SHIPPED=no\$' '$KS_STATE/status.env' && grep -q '^LAST_RESULT=ok\$' '$KS_STATE/status.env'"
    listing="$(docker run --rm -v "$KSV:/v:ro" --entrypoint bash "$IMAGE" -c 'cd /v/keycloak/* && ls | LC_ALL=C sort | tr "\n" " "' | tr -d '\r')"
    check "KS-11 the staging volume holds one complete set: the encrypted realm, manifest.env and SHA256SUMS.shipped" test "$listing" = "SHA256SUMS.shipped dlap-realm.json.enc manifest.env "
    check "KS-12 the stored realm is encrypted: no plain realm anywhere in the volume" bash -c "! docker run --rm -v '$KSV:/v:ro' --entrypoint bash '$IMAGE' -c 'grep -rl realm /v/keycloak/*/dlap-realm.json.enc' | grep -q ."

    # 2. with an off-host command, passed in a settings file the way .env.pilot writes it
    {
      echo "# a comment"
      echo "DLAP_BACKUP_SHIP_CMD='$SHIP'"
      echo "DLAP_KC_KEEP=2"
      echo "DLAP_KC_STATE_DIR=/must/not/win/over/the/environment"
      echo "OTHER_SETTING=\$(touch $TMP/ks-executed)"
      echo "DLAP_KC_REALM=\$(touch $TMP/ks-executed2)"
    } > "$TMP/ks.env"
    sleep 1
    ksrun bash "$KCB" run --env-file "$TMP/ks.env" > "$TMP/ks-run2.log" 2>&1; ks2=$?
    check "KS-13 the settings file is parsed, never executed (a \$(...) in it does nothing), and the environment wins over it" bash -c "[ $ks2 -eq 0 ] && [ ! -e '$TMP/ks-executed' ] && [ ! -e '$TMP/ks-executed2' ] && grep -q '^SHIPPED=yes\$' '$KS_STATE/status.env'"
    check "KS-14 the earlier set that was never shipped is shipped too (2 sets off-host, 2 in the volume)" bash -c "[ \"\$(sets_off_host)\" = 2 ] && [ \"\$(sets_in_volume)\" = 2 ]"
    check "KS-15 inside each set SHA256SUMS.shipped is handed over last" bash -c "for s in \$(ls '$OFF/keycloak'); do grep \"keycloak/\$s/\" '$LOG' | tail -n 1 | grep -q 'SHA256SUMS.shipped\$' || exit 1; done"
    check "KS-16 the command received \$1 as a real file and \$2 as the key keycloak/<stamp>/<name>" bash -c "grep -qE '^keycloak/[0-9]{8}T[0-9]{6}Z/dlap-realm.json.enc\$' '$LOG'"
    check "KS-17 what reached the off-host folder decrypts to exactly the realm that was exported" bash -c "f=\$(ls '$OFF'/keycloak/*/dlap-realm.json.enc | tail -n 1); docker run --rm -i -v '$(hostpath "$KSP"):/run/secrets/dlap_backup_passphrase:ro' --entrypoint openssl '$IMAGE' enc -d -aes-256-cbc -pbkdf2 -iter 600000 -md sha256 -pass file:/run/secrets/dlap_backup_passphrase < \"\$f\" | cmp -s - '$TMP/ks-realm.json'"
    check "KS-18 the off-host folder holds no plain realm" bash -c "! grep -rl '\"realm\"' '$OFF' | grep -q ."

    # 3. retention keeps the newest KEEP shipped sets
    sleep 1; ksrun bash "$KCB" run --env-file "$TMP/ks.env" > /dev/null 2>&1
    sleep 1; ksrun bash "$KCB" run --env-file "$TMP/ks.env" > /dev/null 2>&1
    check "KS-19 with DLAP_KC_KEEP=2 only the newest 2 shipped sets stay in the staging volume (4 runs made 4 sets, all off-host)" bash -c "[ \"\$(sets_in_volume)\" = 2 ] && [ \"\$(sets_off_host)\" -ge 4 ]"

    # 4. a failing off-host command: exit 1, nothing is marked shipped, nothing unshipped is removed, the next run catches up
    rm -rf "$OFF" "$LOG"
    mkdir -p "$OFF"
    sleep 1
    ksrun DLAP_KC_KEEP=1 DLAP_BACKUP_SHIP_CMD='false' bash "$KCB" run > "$TMP/ks-run5.log" 2>&1; ks5=$?
    check "KS-20 a failing off-host command fails the run (exit 1) and the status says ship-failed" bash -c "[ $ks5 -eq 1 ] && grep -q '^LAST_RESULT=ship-failed\$' '$KS_STATE/status.env'"
    sleep 1
    ksrun DLAP_KC_KEEP=1 DLAP_BACKUP_SHIP_CMD='false' bash "$KCB" run > /dev/null 2>&1
    check "KS-21 sets that could not be shipped are never removed, whatever DLAP_KC_KEEP says" bash -c "[ \"\$(sets_in_volume)\" -ge 3 ]"
    sleep 1
    ksrun DLAP_KC_KEEP=1 bash "$KCB" run --env-file "$TMP/ks.env" > /dev/null 2>&1; ks6=$?
    check "KS-22 once the command works again the next run ships every waiting set and the status is ok" bash -c "[ $ks6 -eq 0 ] && [ \"\$(sets_off_host)\" -ge 3 ] && grep -q '^LAST_RESULT=ok\$' '$KS_STATE/status.env'"

    # 5. a failing export
    sleep 1
    ksrun FAKE_FAIL=1 bash "$KCB" run > "$TMP/ks-run7.log" 2>&1; ks7=$?
    check "KS-23 a failing export fails the run (exit 1), records the step, and leaves the last success untouched" bash -c "[ $ks7 -eq 1 ] && grep -q '^LAST_RESULT=failed\$' '$KS_STATE/status.env' && grep -q \"failed in step 'backup'\" '$KS_STATE/status.env' && grep -q '^LAST_SUCCESS_UTC=20' '$KS_STATE/status.env'"

    # 6. check
    check "KS-24 check fails when the last run failed (exit 1)" test "$(rc ksrun bash "$KCB" check)" = 1
    sleep 1
    ksrun bash "$KCB" run --env-file "$TMP/ks.env" > /dev/null 2>&1
    check "KS-25 check passes after a good run and prints OK" bash -c "ksrun bash '$KCB' check | grep -q '^OK'"
    check "KS-26 check --quiet prints nothing when all is well" bash -c "[ -z \"\$(ksrun bash '$KCB' check --quiet)\" ]"
    sed -i 's/^LAST_SUCCESS_UTC=.*/LAST_SUCCESS_UTC=2020-01-01T00:00:00Z/' "$KS_STATE/status.env"
    check "KS-27 an old last success makes check fail (exit 1)" test "$(rc ksrun bash "$KCB" check)" = 1
    check "KS-28 check with no status file at all fails (exit 1)" test "$(rc env DLAP_KC_STATE_DIR="$TMP/ks-empty" bash "$KCB" check)" = 1

    # 7. the lock
    mkdir -p "$KS_STATE/lock.d"
    echo "$$" > "$KS_STATE/lock.d/pid"
    check "KS-29 a run while another one is in progress (a live process in the lock) is refused (exit 2) and leaves the lock" bash -c "[ \"\$(rc ksrun bash '$KCB' run)\" = 2 ] && [ -d '$KS_STATE/lock.d' ]"
    echo 99999999 > "$KS_STATE/lock.d/pid"
    sleep 1
    ksrun bash "$KCB" run --env-file "$TMP/ks.env" > /dev/null 2>&1; ks8=$?
    check "KS-30 a lock left by a process that is gone is removed and the run goes on (exit 0)" bash -c "[ $ks8 -eq 0 ] && [ ! -d '$KS_STATE/lock.d' ]"
  fi
fi

echo
echo "stack-unit.sh: $PASSED passed, $FAILED failed, $SKIPPED skipped"
[ "$FAILED" -eq 0 ] || exit 1
[ "$HAVE_DOCKER" -eq 1 ] || exit 3
exit 0
