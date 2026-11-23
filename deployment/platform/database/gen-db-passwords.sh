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
# The database passwords of an environment file (SEC-12, Q-176, Q-449, Q-455).
#
# The init script (init-scripts/00-create-multiple-databases.sh) takes one password per database role from
# the environment of the PostgreSQL container: <DATABASE>_DB_PASSWORD for each service user, plus
# DLAP_MONITOR_DB_PASSWORD and DLAP_BACKUP_DB_PASSWORD. The pilot and the demo refuse to start until all of
# them are real. This script fills them in:
#
#   gen-db-passwords.sh FILE         append every variable that FILE does not set, and replace every one that
#                                    still has a CHANGE_ME placeholder, with a random 48-digit hex value
#   gen-db-passwords.sh --list       print the variable names, one per line (the launchers read this list)
#
# A variable that already has a value is never touched, so running the script twice changes nothing the
# second time, and a password that a database was created with is not replaced by accident. The values
# are never printed, only the names. The file keeps its mode; a new file gets 0600.
#
# The list of databases is the one of the compose files (POSTGRES_MULTIPLE_DATABASES); a test keeps the two
# equal. POSTGRES_PASSWORD (the superuser) is not generated here: choose it yourself, it is the one secret
# you may need to type.
#
# Exit: 0 done; 2 bad usage; 1 no random source or the file could not be written.

set -euo pipefail

DATABASES=(dlap_common da_discovery da_catalog da_policyengine da_reporting da_autolabel da_autoarchival
  da_autodelete da_autocompliance da_admin da_logingest)

# The variable that holds the password of a database's user (the rule of the init script).
variables() {
  local db name
  for db in "${DATABASES[@]}"; do
    name="${db^^}"
    printf '%s_DB_PASSWORD\n' "${name//[^A-Z0-9]/_}"
  done
  printf '%s\n' DLAP_MONITOR_DB_PASSWORD DLAP_BACKUP_DB_PASSWORD
}

usage() { sed -n '/^# The database passwords of/,/^# Exit:/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }

random_secret() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex 24
  elif [ -r /dev/urandom ]; then
    head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n'
  else
    echo "gen-db-passwords: no random source (openssl or /dev/urandom)" >&2
    exit 1
  fi
}

case "${1:-}" in
  --list) variables; exit 0 ;;
  -h|--help) usage; exit 0 ;;
  "") usage >&2; exit 2 ;;
  -*) echo "gen-db-passwords: unknown option: $1" >&2; exit 2 ;;
esac
[ $# -eq 1 ] || { usage >&2; exit 2; }
FILE="$1"

[ -f "$FILE" ] || { (umask 077; : > "$FILE"); }

# The value of KEY in FILE: the last assignment, quotes and a carriage return removed.
value_of() {
  local line value
  line="$(grep -E "^[[:space:]]*$1=" "$FILE" | tail -n 1 || true)"
  value="${line#*=}"
  value="${value%$'\r'}"
  value="${value#\"}"; value="${value%\"}"; value="${value#\'}"; value="${value%\'}"
  printf '%s' "$value"
}

TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT
cp "$FILE" "$TMP"
created=0
for key in $(variables); do
  current="$(value_of "$key")"
  if [ -n "$current" ] && [[ "$current" != CHANGE_ME* ]]; then
    continue
  fi
  secret="$(random_secret)"
  if grep -qE "^[[:space:]]*$key=" "$TMP"; then
    # Replace every assignment of the key; the value is hex, so it needs no escaping in the replacement.
    sed -E "s|^([[:space:]]*$key=).*|\\1$secret|" "$TMP" > "$TMP.new" && mv "$TMP.new" "$TMP"
  else
    if [ -s "$TMP" ] && [ -n "$(tail -c 1 "$TMP")" ]; then echo >> "$TMP"; fi
    printf '%s=%s\n' "$key" "$secret" >> "$TMP"
  fi
  created=$((created + 1))
  echo "gen-db-passwords: $key set"
done

if [ "$created" -gt 0 ]; then
  cat "$TMP" > "$FILE"
fi
echo "gen-db-passwords: $created of $(variables | wc -l | tr -d ' ') variable(s) generated in $FILE"
