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
# Backup and restore of the Keycloak realm of the quickstart stack (DR-02).
#
# The quickstart runs Keycloak as `start-dev` on an embedded file database inside the container (no
# volume), so the PostgreSQL backups do not carry it. This tool exports the whole realm, users with their
# password hashes, roles, groups, clients and client secrets, with Keycloak's own `kc.sh export`, encrypts
# the file with the backup passphrase and stores it in a Docker volume (the stand-in for the off-host
# store; the same volume ship.sh fills). A restore decrypts it into a volume that a fresh Keycloak reads
# with --import-realm. The plain file is never written to the host file system: it exists only inside a
# throwaway container and inside the import volume until `wipe` removes it.
#
# Why the container is stopped. The embedded H2 file cannot be opened by a second JVM while Keycloak runs,
# and a copy of a file in use is not a safe backup. So `backup` stops the container (a few seconds),
# snapshots its file system with `docker commit`, starts it again and exports from the snapshot in a
# throwaway container. Keycloak on PostgreSQL (the pilot overlay, IAM-02) needs none of this: its database
# is in the PostgreSQL backups.
#
# Usage:
#   keycloak-realm.sh backup  --container NAME --realm REALM --to-volume VOL --key PREFIX
#   keycloak-realm.sh restore --from-volume VOL --key PREFIX --realm REALM --import-volume VOL2
#   keycloak-realm.sh wipe    --import-volume VOL2
#
#   backup    writes  <PREFIX>/<REALM>-realm.json.enc  and  <PREFIX>/manifest.env  and, last,
#             <PREFIX>/SHA256SUMS.shipped  (the SHA-256 of the encrypted file) into volume VOL
#   restore   checks SHA256SUMS.shipped, decrypts into volume VOL2 as <REALM>-realm.json (readable by
#             the Keycloak user, uid 1000); mount VOL2 at /opt/keycloak/data/import of a fresh container
#   wipe      removes every file from VOL2 (run it when the new Keycloak has imported the realm)
#
# Environment: DLAP_BACKUP_PASSPHRASE_FILE  host path of the one-line passphrase file (as in ship.sh)
#              DLAP_DR_IMAGE                image that has openssl and bash [postgres:15.1]
#              DLAP_KC_WAIT_SECONDS         wait for the restarted Keycloak to be healthy [240]
# Needs: docker; the Keycloak container must have been started with `start-dev` and KC_DB=dev-file.
# Exit: 0 done; 1 a step failed; 2 a refusal or a bad argument (nothing was changed).

set -euo pipefail
export MSYS_NO_PATHCONV=1

IMAGE="${DLAP_DR_IMAGE:-postgres:15.1}"
WAIT="${DLAP_KC_WAIT_SECONDS:-240}"

say() { printf '%s keycloak-realm: %s\n' "$(date -u +%H:%M:%S)" "$*"; }
refuse() { printf 'keycloak-realm: ERROR: %s\n' "$*" >&2; exit 2; }
die() { printf 'keycloak-realm: ERROR: %s\n' "$*" >&2; exit 1; }
usage() { awk '/^# Backup and restore of the Keycloak realm/ { on = 1 } on && /^#/ { sub(/^# ?/, ""); print; next } on { exit }' "${BASH_SOURCE[0]}"; }
hostpath() { if command -v cygpath > /dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

CMD="${1:-}"
case "$CMD" in
  backup | restore | wipe) shift ;;
  -h | --help) usage; exit 0 ;;
  *) usage >&2; refuse "unknown command '$CMD' (backup, restore, wipe)" ;;
esac

CONTAINER=""
REALM=""
TO_VOLUME=""
FROM_VOLUME=""
IMPORT_VOLUME=""
KEY=""
while [ $# -gt 0 ]; do
  case "$1" in
    --container) [ $# -ge 2 ] || refuse "--container needs a name"; CONTAINER="$2"; shift 2 ;;
    --realm) [ $# -ge 2 ] || refuse "--realm needs a name"; REALM="$2"; shift 2 ;;
    --to-volume) [ $# -ge 2 ] || refuse "--to-volume needs a volume"; TO_VOLUME="$2"; shift 2 ;;
    --from-volume) [ $# -ge 2 ] || refuse "--from-volume needs a volume"; FROM_VOLUME="$2"; shift 2 ;;
    --import-volume) [ $# -ge 2 ] || refuse "--import-volume needs a volume"; IMPORT_VOLUME="$2"; shift 2 ;;
    --key) [ $# -ge 2 ] || refuse "--key needs a prefix"; KEY="$2"; shift 2 ;;
    -h | --help) usage; exit 0 ;;
    *) refuse "unknown argument: $1" ;;
  esac
done

command -v docker > /dev/null 2>&1 || refuse "docker is not installed"
docker info > /dev/null 2>&1 || refuse "the Docker daemon does not answer"
NAME_RE='^[A-Za-z0-9][A-Za-z0-9_.-]*$'
KEY_RE='^[A-Za-z0-9][A-Za-z0-9_./-]*$'
[ -z "$REALM" ] || [[ "$REALM" =~ $NAME_RE ]] || refuse "not a realm name: $REALM"
[ -z "$KEY" ] || { [[ "$KEY" =~ $KEY_RE ]] && [[ "$KEY" != *..* ]]; } || refuse "not a key prefix: $KEY"

PASSFILE="${DLAP_BACKUP_PASSPHRASE_FILE:-}"
need_passphrase() {
  [ -n "$PASSFILE" ] || refuse "DLAP_BACKUP_PASSPHRASE_FILE is not set (the realm holds password hashes and client secrets: it is never stored in plain)"
  [ -s "$PASSFILE" ] || refuse "the passphrase file is missing or empty: $PASSFILE"
}

# crypt <encrypt|decrypt>: stdin to stdout, in a container with the pass file mounted. The parameters
# are those of lib.sh (dr_encrypt, dr_decrypt), so ship.sh, restore.sh and this tool open each other's files.
crypt() {
  local mode="$1" flag=""
  [ "$mode" = decrypt ] && flag="-d"
  docker run --rm -i -v "$(hostpath "$PASSFILE"):/run/secrets/dlap_backup_passphrase:ro" --entrypoint openssl "$IMAGE" \
    enc $flag -aes-256-cbc -pbkdf2 -iter 600000 -md sha256 -salt -pass file:/run/secrets/dlap_backup_passphrase
}

# vol <volume> <ro|rw> <script>: run a shell snippet in a throwaway container with the volume at /v.
vol() {
  local v="$1" mode="$2" opt=""
  shift 2
  [ "$mode" = ro ] && opt=":ro"
  docker run --rm -i -v "$v:/v$opt" --entrypoint bash "$IMAGE" -c "$1"
}

sha_of() { # <volume> <path in volume>
  vol "$1" ro "sha256sum \"/v/$2\" | cut -d' ' -f1" | tr -d '\r\n'
}

wait_healthy() { # <container>
  local c="$1" i=0 st
  while :; do
    st="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$c" 2> /dev/null || echo missing)"
    [ "$st" = healthy ] && return 0
    [ "$st" = none ] && [ "$(docker inspect -f '{{.State.Running}}' "$c" 2> /dev/null)" = true ] && return 0
    i=$((i + 2))
    [ "$i" -le "$WAIT" ] || return 1
    sleep 2
  done
}

# --- backup ----------------------------------------------------------------------------------------------

do_backup() {
  [ -n "$CONTAINER" ] && [ -n "$REALM" ] && [ -n "$TO_VOLUME" ] && [ -n "$KEY" ] || refuse "backup needs --container, --realm, --to-volume and --key"
  need_passphrase
  docker inspect "$CONTAINER" > /dev/null 2>&1 || refuse "no container named $CONTAINER"
  docker volume inspect "$TO_VOLUME" > /dev/null 2>&1 || refuse "no volume named $TO_VOLUME"
  local kc_image started snap exporter t0 t1 users clients plain_bytes enc_sum
  kc_image="$(docker inspect -f '{{.Config.Image}}' "$CONTAINER")"
  t0="$(date +%s)"
  say "stopping $CONTAINER to take a consistent copy of its embedded database"
  docker stop --time 60 "$CONTAINER" > /dev/null || die "could not stop $CONTAINER"
  snap="dlap-kc-snapshot:$$-$(date +%s)"
  exporter="dlap-kc-export-$$-$(date +%s)"
  started=0
  # The container is started again whatever happens next: the stack must not stay without identity.
  restart_kc() { if [ "$started" -eq 0 ]; then docker start "$CONTAINER" > /dev/null 2>&1 || true; started=1; fi; }
  cleanup_snap() { docker rm -f "$exporter" > /dev/null 2>&1 || true; docker rmi -f "$snap" > /dev/null 2>&1 || true; }
  trap 'restart_kc; cleanup_snap' EXIT
  docker commit --pause=false "$CONTAINER" "$snap" > /dev/null || die "docker commit failed"
  restart_kc
  say "$CONTAINER was down for $(( $(date +%s) - t0 )) s; exporting realm $REALM from the snapshot of $kc_image"
  # The snapshot keeps the container's environment (KC_DB=dev-file ...); only the command differs.
  docker run --name "$exporter" "$snap" export --realm "$REALM" --users realm_file --dir /tmp/kc-export \
    > "${TMPDIR:-/tmp}/$exporter.log" 2>&1 \
    || { tail -n 20 "${TMPDIR:-/tmp}/$exporter.log" >&2; rm -f "${TMPDIR:-/tmp}/$exporter.log"; die "kc.sh export failed"; }
  rm -f "${TMPDIR:-/tmp}/$exporter.log"
  # tar -xO writes the single file to stdout; it goes straight into the encryption, never to the host disk.
  plain_bytes="$(docker cp "$exporter:/tmp/kc-export/$REALM-realm.json" - | tar -xO | wc -c | tr -d ' ')"
  users="$(docker cp "$exporter:/tmp/kc-export/$REALM-realm.json" - | tar -xO | grep -c '"username"' || true)"
  clients="$(docker cp "$exporter:/tmp/kc-export/$REALM-realm.json" - | tar -xO | grep -c '"clientId"' || true)"
  [ "$plain_bytes" -gt 1000 ] || die "the exported realm is only $plain_bytes bytes"
  docker cp "$exporter:/tmp/kc-export/$REALM-realm.json" - | tar -xO | crypt encrypt \
    | vol "$TO_VOLUME" rw "mkdir -p \"/v/$KEY\" && cat > \"/v/$KEY/$REALM-realm.json.enc\""
  enc_sum="$(sha_of "$TO_VOLUME" "$KEY/$REALM-realm.json.enc")"
  [ -n "$enc_sum" ] || die "could not read back the encrypted file"
  t1="$(date +%s)"
  vol "$TO_VOLUME" rw "cat > \"/v/$KEY/manifest.env\"" << EOF
WHAT=keycloak-realm
REALM=$REALM
CREATED_UTC=$(date -u +%Y-%m-%dT%H:%M:%SZ)
KEYCLOAK_IMAGE=$kc_image
PLAIN_BYTES=$plain_bytes
USERS=$users
CLIENT_ENTRIES=$clients
EXPORT_SECONDS=$((t1 - t0))
EOF
  # The marker comes last: a prefix without it is an interrupted backup and is never restored from.
  vol "$TO_VOLUME" rw "printf '%s  %s\n' '$enc_sum' '$REALM-realm.json.enc' > \"/v/$KEY/SHA256SUMS.shipped\""
  trap - EXIT
  cleanup_snap
  wait_healthy "$CONTAINER" || die "$CONTAINER did not become healthy again within ${WAIT}s"
  say "realm $REALM stored encrypted at $KEY: $plain_bytes bytes plain, $users user entries, $((t1 - t0)) s"
}

# --- restore ---------------------------------------------------------------------------------------------

do_restore() {
  [ -n "$FROM_VOLUME" ] && [ -n "$KEY" ] && [ -n "$REALM" ] && [ -n "$IMPORT_VOLUME" ] || refuse "restore needs --from-volume, --key, --realm and --import-volume"
  need_passphrase
  docker volume inspect "$FROM_VOLUME" > /dev/null 2>&1 || refuse "no volume named $FROM_VOLUME"
  docker volume inspect "$IMPORT_VOLUME" > /dev/null 2>&1 || refuse "no volume named $IMPORT_VOLUME"
  local want got
  vol "$FROM_VOLUME" ro "test -f \"/v/$KEY/SHA256SUMS.shipped\"" || refuse "$KEY has no SHA256SUMS.shipped: the backup is incomplete and is not restored from"
  want="$(vol "$FROM_VOLUME" ro "cut -d' ' -f1 \"/v/$KEY/SHA256SUMS.shipped\"" | tr -d '\r\n')"
  got="$(sha_of "$FROM_VOLUME" "$KEY/$REALM-realm.json.enc")"
  [ -n "$want" ] && [ "$want" = "$got" ] || refuse "$KEY/$REALM-realm.json.enc does not match SHA256SUMS.shipped (damaged or cut short)"
  # Decrypt into a name Keycloak does not import, check it, and only then give it its final name: a wrong
  # passphrase or a damaged file must never leave a half realm where Keycloak would read it.
  local part="$REALM-realm.json.part" bytes
  if ! vol "$FROM_VOLUME" ro "cat \"/v/$KEY/$REALM-realm.json.enc\"" | crypt decrypt \
       | vol "$IMPORT_VOLUME" rw "cat > \"/v/$part\""; then
    vol "$IMPORT_VOLUME" rw "rm -f \"/v/$part\"" || true
    die "decrypting the realm failed (wrong passphrase?)"
  fi
  bytes="$(vol "$IMPORT_VOLUME" ro "wc -c < \"/v/$part\"" | tr -d '[:space:]')"
  if [ "${bytes:-0}" -le 1000 ] || ! vol "$IMPORT_VOLUME" ro "grep -q '\"realm\" *: *\"$REALM\"' \"/v/$part\""; then
    vol "$IMPORT_VOLUME" rw "rm -f \"/v/$part\"" || true
    die "the decrypted file is not the realm $REALM ($bytes bytes): wrong passphrase?"
  fi
  vol "$IMPORT_VOLUME" rw "chmod 644 \"/v/$part\" && mv \"/v/$part\" \"/v/$REALM-realm.json\""
  say "realm $REALM decrypted into $IMPORT_VOLUME ($bytes bytes); mount it at /opt/keycloak/data/import and start Keycloak with --import-realm"
}

do_wipe() {
  [ -n "$IMPORT_VOLUME" ] || refuse "wipe needs --import-volume"
  docker volume inspect "$IMPORT_VOLUME" > /dev/null 2>&1 || refuse "no volume named $IMPORT_VOLUME"
  vol "$IMPORT_VOLUME" rw 'find /v -mindepth 1 -delete'
  say "$IMPORT_VOLUME is empty"
}

case "$CMD" in
  backup) do_backup ;;
  restore) do_restore ;;
  wipe) do_wipe ;;
esac
