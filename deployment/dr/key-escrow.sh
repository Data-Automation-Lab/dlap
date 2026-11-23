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
# Escrow helpers for DLAP_ENCRYPTION_KEY and the other secrets a restore cannot rebuild (DR-01).
#
# DLAP_ENCRYPTION_KEY encrypts every stored cloud credential (da-admin-service,
# BasicEncryptionServiceImpl). A backup of the database without the key restores rows that nobody can
# read, and the key is not in the database or in any backup. Losing it loses the stored connections;
# nothing else is lost, because the connections can be entered again. The key therefore needs a copy
# of its own, kept apart from the backups. This tool does not store the copy for you. It gives you:
#
#   fingerprint   a short, non-secret name for the key, to put in DLAP_ENCRYPTION_KEY_FINGERPRINT.
#                 Every backup records it, and a restore refuses an environment whose key has another one.
#   check         is the key well formed, and does it match the fingerprint of the given backup sets?
#   seal          encrypt a file (the pilot's .env.pilot, say) with a passphrase, for storage on paper,
#                 a USB stick in a safe, or a second cloud account. It is the fallback for a customer
#                 who has no secret manager; a secret manager is better.
#   unseal        the reverse. Wrong passphrase or damaged file: it says so and writes nothing.
#
# The key is never printed and never taken from the command line. Passphrases come from a file.
#
# Usage:
#   key-escrow.sh fingerprint [--env-file FILE]
#   key-escrow.sh check [--env-file FILE] [--against SET_DIR]...
#   key-escrow.sh seal   --in FILE --out FILE --passphrase-file FILE
#   key-escrow.sh unseal --in FILE --out FILE --passphrase-file FILE
# The key comes from --env-file (the DLAP_ENCRYPTION_KEY line; the file is read, never sourced) or,
# without it, from the environment variable DLAP_ENCRYPTION_KEY.
# Exit: 0 ok; 1 a check failed (no match, wrong passphrase); 2 bad arguments or an unusable key.

set -euo pipefail

# shellcheck disable=SC2034  # read by lib.sh
DR_PROG=key-escrow
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

usage() { awk '/^# Escrow helpers/ { on = 1 } on && /^#/ { sub(/^# ?/, ""); print; next } on { exit }' "${BASH_SOURCE[0]}"; }

# env_value <file> <KEY>: the last assignment of KEY in an env file, quotes removed. Nothing is evaluated.
env_value() {
  local line value
  line="$(grep -E "^[[:space:]]*$2=" "$1" | tail -n 1 || true)"
  value="${line#*=}"
  value="${value%$'\r'}"
  case "$value" in
    \"*\") value="${value#\"}"; value="${value%\"}" ;;
    \'*\') value="${value#\'}"; value="${value%\'}" ;;
  esac
  printf '%s' "$value"
}

# load_key <env file or empty>: the key in $KEY, validated as the service validates it.
load_key() {
  if [ -n "$1" ]; then
    [ -f "$1" ] || dr_refuse "environment file not found: $1"
    KEY="$(env_value "$1" DLAP_ENCRYPTION_KEY)"
  else
    KEY="${DLAP_ENCRYPTION_KEY:-}"
  fi
  [ -n "$KEY" ] || dr_refuse "DLAP_ENCRYPTION_KEY is not set${1:+ in $1}"
  case "$KEY" in CHANGE_ME*) dr_refuse "DLAP_ENCRYPTION_KEY still has the placeholder from the example file" ;; esac
  if [[ "$KEY" =~ ^[0-9a-fA-F]{64}$ ]]; then
    :
  elif [ "${#KEY}" -eq 32 ]; then
    dr_warn "the key is a 32 character string, which the service accepts for old installations; a 64 hex character key (openssl rand -hex 32) has twice the entropy"
  else
    dr_refuse "DLAP_ENCRYPTION_KEY must be 64 hex characters (32 bytes) or, for old installations, 32 characters; it has ${#KEY}"
  fi
}

# fingerprint_of <key>: fp1: plus the first 16 hex digits of SHA-256 over a fixed label and the key.
fingerprint_of() {
  printf 'fp1:%s' "$(printf 'dlap-dr-key-v1:%s' "$1" | openssl dgst -sha256 | sed 's/^.*= *//' | cut -c1-16)"
}

check_passfile() {
  [ -n "$1" ] || dr_refuse "--passphrase-file is required"
  [ -s "$1" ] || dr_refuse "the passphrase file is missing or empty: $1"
  local mode
  mode="$(stat -c %a "$1" 2>/dev/null || echo 600)"
  case "$mode" in *00) ;; *) dr_warn "the passphrase file $1 has mode $mode; make it readable by its owner only (chmod 600)" ;; esac
}

CMD="${1:-}"
[ -n "$CMD" ] || { usage; exit 2; }
shift

ENV_FILE=""
IN=""
OUT=""
PASSFILE=""
AGAINST=()
while [ $# -gt 0 ]; do
  case "$1" in
    --env-file) [ $# -ge 2 ] || dr_refuse "--env-file needs a file"; ENV_FILE="$2"; shift 2 ;;
    --against) [ $# -ge 2 ] || dr_refuse "--against needs a backup set folder"; AGAINST+=("$2"); shift 2 ;;
    --in) [ $# -ge 2 ] || dr_refuse "--in needs a file"; IN="$2"; shift 2 ;;
    --out) [ $# -ge 2 ] || dr_refuse "--out needs a file"; OUT="$2"; shift 2 ;;
    --passphrase-file) [ $# -ge 2 ] || dr_refuse "--passphrase-file needs a file"; PASSFILE="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) dr_refuse "unknown argument: $1" ;;
  esac
done

case "$CMD" in
  fingerprint)
    load_key "$ENV_FILE"
    fingerprint_of "$KEY"
    echo
    ;;

  check)
    load_key "$ENV_FILE"
    fp="$(fingerprint_of "$KEY")"
    dr_say "the key is well formed; its fingerprint is $fp"
    bad=0
    for sd in "${AGAINST[@]}"; do
      have="$(dr_kv "$sd/manifest.env" KEY_FINGERPRINT)"
      if [ -z "$have" ]; then
        dr_err "$sd has no manifest.env or no KEY_FINGERPRINT"
        bad=1
      elif [ "$have" = "unset" ]; then
        dr_warn "$sd records no fingerprint (DLAP_ENCRYPTION_KEY_FINGERPRINT was not set when it was taken)"
      elif [ "$have" = "$fp" ]; then
        dr_say "$sd: matches"
      else
        dr_err "$sd was taken with key fingerprint $have, not $fp"
        bad=1
      fi
    done
    exit "$bad"
    ;;

  seal)
    [ -n "$IN" ] && [ -n "$OUT" ] || dr_refuse "seal needs --in and --out"
    [ -f "$IN" ] || dr_refuse "not a file: $IN"
    [ ! -e "$OUT" ] || dr_refuse "$OUT exists; sealing never overwrites"
    check_passfile "$PASSFILE"
    # shellcheck disable=SC2034  # read by lib.sh
    DR_PASSFILE="$PASSFILE"
    tmp="$(mktemp "$OUT.XXXXXX")"
    trap 'rm -f "$tmp"' EXIT
    {
      printf 'DLAP-ESCROW-V1 check=%s\n' "$(dr_sha256 "$IN" | cut -c1-16)"
      dr_encrypt < "$IN"
    } > "$tmp"
    mv "$tmp" "$OUT"
    trap - EXIT
    dr_say "sealed $(stat -c %s "$IN") bytes into $OUT. Test it now: key-escrow.sh unseal, then compare."
    ;;

  unseal)
    [ -n "$IN" ] && [ -n "$OUT" ] || dr_refuse "unseal needs --in and --out"
    [ -f "$IN" ] || dr_refuse "not a file: $IN"
    [ ! -e "$OUT" ] || dr_refuse "$OUT exists; unsealing never overwrites"
    check_passfile "$PASSFILE"
    # shellcheck disable=SC2034  # read by lib.sh
    DR_PASSFILE="$PASSFILE"
    header="$(head -n 1 "$IN")"
    [[ "$header" =~ ^DLAP-ESCROW-V1\ check=([0-9a-f]{16})$ ]] || dr_refuse "$IN is not a sealed escrow file (no DLAP-ESCROW-V1 header)"
    want="${BASH_REMATCH[1]}"
    tmp="$(mktemp "$OUT.XXXXXX")"
    trap 'rm -f "$tmp"' EXIT
    if ! tail -n +2 "$IN" | dr_decrypt > "$tmp" 2> /dev/null; then
      DR_EXIT=1 dr_die "cannot decrypt $IN: wrong passphrase or damaged file"
    fi
    got="$(dr_sha256 "$tmp" | cut -c1-16)"
    if [ "$got" != "$want" ]; then
      DR_EXIT=1 dr_die "the decrypted content does not match the check value in the header: wrong passphrase or damaged file"
    fi
    mv "$tmp" "$OUT"
    trap - EXIT
    dr_say "unsealed into $OUT"
    ;;

  *) dr_refuse "unknown command '$CMD' (fingerprint, check, seal, unseal)" ;;
esac
