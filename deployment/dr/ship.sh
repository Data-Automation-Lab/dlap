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
# Off-host copy of the backups (DR-01).
#
# Backups on the VM's own disk do not survive the VM. This tool copies every backup set and the WAL
# archive to a place the operator chooses, by running the operator's command once per file. DLAP
# links no cloud SDK: the command is whatever the customer already trusts (rclone, aws s3, gcloud
# storage, azcopy, scp, a mounted share).
#
# Contract of DLAP_BACKUP_SHIP_CMD. It is run as  sh -c "$DLAP_BACKUP_SHIP_CMD" ship <file> <key>
# so inside the command $1 is a local file to upload and $2 is the object key, a relative path with
# forward slashes (for example  logical/20261019T023000Z/da_admin.dump.enc ). Exit status 0 means the
# file is stored; anything else means it is not and the file is tried again on the next pass. The
# command runs without PGPASSWORD and is stopped after DLAP_BACKUP_SHIP_TIMEOUT seconds (default
# 1800). Example, with rclone remote "offsite":   rclone copyto "$1" "offsite:dlap-backups/$2"
#
# What is shipped, under the keys
#   logical/<set>/   <file>.enc for every dump and list, manifest.env and SHA256SUMS as they are, and
#                    SHA256SUMS.shipped last: the SHA-256 of every encrypted file as it was uploaded. A
#                    set without SHA256SUMS.shipped is incomplete and is not restored from.
#   base/<set>/      the same for a base backup
#   wal/<key>/       every WAL segment as <segment>.gz.enc, and the segment in progress as
#                    <segment>.partial.gz.enc, uploaded again whenever it has grown. That is what keeps
#                    the off-host copy within the RPO although a 16 MiB segment can take hours to fill.
# Files are encrypted with the passphrase file (DLAP_BACKUP_PASSPHRASE_FILE). Without one the tool
# refuses, unless DLAP_BACKUP_SHIP_PLAINTEXT=1 says plain files are acceptable.
#
# Usage:  ship.sh [--wal | --sets | --all]
#   --wal    ship the WAL archive only (the agent runs this every cycle)
#   --sets   ship the backup sets only (the agent runs this after each backup)
#   --all    both, in that order (default)
# Exit: 0 every item shipped (or no off-host copy is configured); 1 an item failed; 2 refused.

set -euo pipefail

# shellcheck disable=SC2034  # read by lib.sh
DR_PROG=ship
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

WHAT=all
usage() { awk '/^# Off-host copy of the backups/ { on = 1 } on && /^#/ { sub(/^# ?/, ""); print; next } on { exit }' "${BASH_SOURCE[0]}"; }
while [ $# -gt 0 ]; do
  case "$1" in
    --wal) WHAT=wal; shift ;;
    --sets) WHAT=sets; shift ;;
    --all) WHAT=all; shift ;;
    -h|--help) usage; exit 0 ;;
    *) dr_refuse "unknown argument: $1" ;;
  esac
done

mkdir -p "$DR_DATA/ship/stage" "$DR_DATA/ship/.done" "$DR_DATA/status"
# A pass that was killed leaves its staging file behind; two passes never share a name, so age decides.
find "$DR_DATA/ship/stage" -maxdepth 1 -type f -mmin +1440 -delete 2> /dev/null || true
STATUS_WAL="$DR_DATA/status/ship-wal.env"
STATUS_SETS="$DR_DATA/status/ship-sets.env"

if ! dr_ship_enabled; then
  dr_kv_set "$STATUS_WAL" "MODE=disabled" "LAST_PASS_UTC=$(dr_ts)"
  dr_kv_set "$STATUS_SETS" "MODE=disabled" "LAST_PASS_UTC=$(dr_ts)"
  exit 0
fi

ENCRYPT=1
if ! dr_have_passphrase; then
  if [ "${DLAP_BACKUP_SHIP_PLAINTEXT:-0}" = "1" ]; then
    ENCRYPT=0
    dr_warn "no passphrase file: shipping PLAIN files because DLAP_BACKUP_SHIP_PLAINTEXT=1"
  else
    for s in "$STATUS_WAL" "$STATUS_SETS"; do
      dr_kv_set "$s" "MODE=blocked" "LAST_PASS_UTC=$(dr_ts)" \
        "LAST_ERROR=DLAP_BACKUP_SHIP_CMD is set but there is no passphrase file ($DR_PASSFILE)"
    done
    dr_refuse "DLAP_BACKUP_SHIP_CMD is set but there is no passphrase file at $DR_PASSFILE, so nothing is shipped. Provide DLAP_BACKUP_PASSPHRASE_FILE, or set DLAP_BACKUP_SHIP_PLAINTEXT=1 to accept plain files."
  fi
fi

# suffix <transform>: the key suffix a transform gives.
suffix() {
  case "$1" in
    plain) echo "" ;;
    enc) if [ "$ENCRYPT" -eq 1 ]; then echo ".enc"; else echo ""; fi ;;
    gzenc) if [ "$ENCRYPT" -eq 1 ]; then echo ".gz.enc"; else echo ".gz"; fi ;;
  esac
}

run_ship_cmd() { # <file> <key>
  timeout "${DLAP_BACKUP_SHIP_TIMEOUT:-1800}" env -u PGPASSWORD sh -c "$DLAP_BACKUP_SHIP_CMD" ship "$1" "$2"
}

# ship_item <plain|enc|gzenc> <source> <key> <marker> [extra]: transform into the staging file, run
# the command, and on success write the marker (line 1: SHA-256 and name as shipped; line 2: extra).
# Returns 0 when shipped or when the source vanished, 1 when the command or the transform failed.
ship_item() {
  local transform="$1" src="$2" key="$3" marker="$4" extra="${5:-}" stage sum
  stage="$DR_DATA/ship/stage/item.$$"
  [ -f "$src" ] || return 0
  rm -f "$stage"
  case "$transform" in
    plain) cp "$src" "$stage" || { rm -f "$stage"; return 1; } ;;
    enc)
      if [ "$ENCRYPT" -eq 1 ]; then dr_encrypt < "$src" > "$stage" || { rm -f "$stage"; return 1; }
      else cp "$src" "$stage" || { rm -f "$stage"; return 1; }; fi ;;
    gzenc)
      if [ "$ENCRYPT" -eq 1 ]; then gzip -1 -c < "$src" | dr_encrypt > "$stage" || { rm -f "$stage"; return 1; }
      else gzip -1 -c < "$src" > "$stage" || { rm -f "$stage"; return 1; }; fi ;;
  esac
  sum="$(dr_sha256 "$stage")"
  if run_ship_cmd "$stage" "$key"; then
    mkdir -p "$(dirname "$marker")"
    printf '%s  %s\n%s\n' "$sum" "$(basename "$key")" "$extra" > "$marker"
    rm -f "$stage"
    return 0
  fi
  dr_warn "shipping $key failed"
  rm -f "$stage"
  return 1
}

# --- WAL archive ----------------------------------------------------------------------------------------

ship_wal() {
  dr_lock ship-wal
  local pass_start failed=0 shipped=0 dir key f name marker stat have marker_dir
  pass_start="$(dr_now)"
  for dir in "$DR_DATA"/wal/*/; do
    [ -d "$dir" ] || continue
    key="$(basename "$dir")"
    marker_dir="$DR_DATA/ship/.done/wal/$key"
    if [ -d "$marker_dir" ]; then
      for marker in "$marker_dir"/*; do
        [ -e "$marker" ] || continue
        [ -e "$dir$(basename "$marker")" ] || rm -f "$marker"
      done
    fi
    while IFS= read -r f; do
      name="$(basename "$f")"
      marker="$marker_dir/$name"
      stat="$(stat -c '%s %Y' "$f" 2>/dev/null || true)"
      [ -n "$stat" ] || continue
      if [[ "$name" == *.partial ]]; then
        have="$(sed -n 2p "$marker" 2>/dev/null || true)"
        [ "$have" = "$stat" ] && continue
      else
        [ -f "$marker" ] && continue
      fi
      if ship_item gzenc "$f" "wal/$key/$name$(suffix gzenc)" "$marker" "$stat"; then
        shipped=$((shipped + 1))
      else
        failed=$((failed + 1))
      fi
    done < <(find "$dir" -maxdepth 1 -type f \( -name '[0-9A-F]*' \) | LC_ALL=C sort)
  done
  if [ "$failed" -eq 0 ]; then
    dr_kv_set "$STATUS_WAL" "MODE=enabled" "LAST_PASS_UTC=$(dr_ts)" "WAL_SHIPPED_UPTO_UTC=$(date -u -d "@$pass_start" +%Y-%m-%dT%H:%M:%SZ)" \
      "WAL_SHIPPED_UPTO_EPOCH=$pass_start" "PENDING=0" "LAST_ERROR="
  else
    dr_kv_set "$STATUS_WAL" "MODE=enabled" "LAST_PASS_UTC=$(dr_ts)" "PENDING=$failed" "LAST_ERROR=$failed WAL file(s) could not be shipped"
  fi
  [ "$shipped" -eq 0 ] || dr_say "wal: shipped $shipped file(s)"
  [ "$failed" -eq 0 ]
}

# --- Backup sets ----------------------------------------------------------------------------------------

ship_sets() {
  dr_lock ship-sets
  local failed=0 kind dir setname name transform marker done_marker sums ok f m
  for kind in logical base; do
    while IFS= read -r dir; do
      setname="$(basename "$dir")"
      done_marker="$DR_DATA/ship/.done/$kind/$setname/SHA256SUMS.shipped"
      [ -f "$done_marker" ] && continue
      ok=1
      while IFS= read -r f; do
        name="$(basename "$f")"
        case "$name" in manifest.env|SHA256SUMS) transform=plain ;; *) transform=enc ;; esac
        marker="$DR_DATA/ship/.done/$kind/$setname/$name"
        [ -f "$marker" ] && continue
        if ! ship_item "$transform" "$f" "$kind/$setname/$name$(suffix "$transform")" "$marker"; then ok=0; break; fi
      done < <(find "$dir" -maxdepth 1 -type f ! -name 'SHA256SUMS.shipped' | LC_ALL=C sort)
      if [ "$ok" -eq 0 ]; then failed=$((failed + 1)); continue; fi
      sums="$DR_DATA/ship/stage/sums.$$"
      : > "$sums"
      while IFS= read -r f; do
        name="$(basename "$f")"
        case "$name" in manifest.env|SHA256SUMS) continue ;; esac
        m="$DR_DATA/ship/.done/$kind/$setname/$name"
        [ -f "$m" ] && sed -n 1p "$m" >> "$sums"
      done < <(find "$dir" -maxdepth 1 -type f ! -name 'SHA256SUMS.shipped' | LC_ALL=C sort)
      if ship_item plain "$sums" "$kind/$setname/SHA256SUMS.shipped" "$done_marker"; then
        dr_say "sets: shipped $kind/$setname"
      else
        failed=$((failed + 1))
      fi
      rm -f "$sums"
    done < <(find "$DR_DATA/$kind" -mindepth 1 -maxdepth 1 -type d -name '[0-9]*Z' 2>/dev/null | LC_ALL=C sort -r)
  done
  if [ "$failed" -eq 0 ]; then
    dr_kv_set "$STATUS_SETS" "MODE=enabled" "LAST_PASS_UTC=$(dr_ts)" "LAST_OK_UTC=$(dr_ts)" "PENDING=0" "LAST_ERROR="
  else
    dr_kv_set "$STATUS_SETS" "MODE=enabled" "LAST_PASS_UTC=$(dr_ts)" "PENDING=$failed" "LAST_ERROR=$failed backup set(s) could not be shipped"
  fi
  [ "$failed" -eq 0 ]
}

rc=0
case "$WHAT" in
  wal) ship_wal || rc=1 ;;
  sets) ship_sets || rc=1 ;;
  all) ship_wal || rc=1; ship_sets || rc=1 ;;
esac
exit "$rc"
