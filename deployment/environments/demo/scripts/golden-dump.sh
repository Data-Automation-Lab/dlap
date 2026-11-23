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
# Take the golden dump of the demo (DEMO-01).
#
# The golden state is what a seeded boot produced: the quickstart seed plus the replayed fixtures,
# after the services have labelled and reported on them. nightly-reset.sh restores it every night.
# One custom-format pg_dump per database, each with CREATE DATABASE (-C) so a restore recreates the
# database whole, plus SHA256SUMS and manifest.env. The dump is written to a new folder and swapped
# in, so a failed run never damages the previous golden state (it is kept as <folder>.prev).
#
# Usage:  golden-dump.sh [options]
#   --env-file FILE    environment file (default .env.demo)
#   --out DIR          golden folder (default: <state>/golden)
#   --databases LIST   space separated database names (default: the shared schema and the Lite services)
#   -h, --help         this text
#
# The stack must be up (at least postgresql). Nothing is stopped: pg_dump reads a consistent snapshot
# of each database. Run it after the cascade has settled, which seed.sh checks.
# Nothing here is committed: the folder holds the data of a demo, never of a customer.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

OUT=""
DBS=""

usage() { awk '/^# Take the golden dump/ { on = 1 } on && /^#/ { sub(/^# ?/, ""); print; next } on { exit }' "${BASH_SOURCE[0]}"; }

while [ $# -gt 0 ]; do
  case "$1" in
    --env-file) [ $# -ge 2 ] || demo_die "--env-file needs a file name"; DEMO_ENV_FILE="$2"; shift 2 ;;
    --out) [ $# -ge 2 ] || demo_die "--out needs a folder"; OUT="$2"; shift 2 ;;
    --databases) [ $# -ge 2 ] || demo_die "--databases needs a list"; DBS="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) demo_die "unknown argument: $1" ;;
  esac
done

demo_init "$DEMO_ENV_FILE"
STATE="$(demo_state_dir)"
[ -n "$OUT" ] || OUT="$STATE/golden"
[ -n "$DBS" ] || DBS="$DEMO_DUMP_DATABASES_DEFAULT"
demo_lock golden

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

[ "$(demo_health postgresql)" = "healthy" ] || demo_die "postgresql is not healthy; start the stack first (up.sh)"

TMP="$OUT.tmp.$$"
rm -rf "$TMP"
mkdir -p "$TMP"
trap 'rm -rf "$TMP"' EXIT

demo_say "dumping: $DBS"
: > "$TMP/SHA256SUMS"
for db in $DBS; do
  exists="$(demo_pq postgres "SELECT 1 FROM pg_database WHERE datname = '$db'")"
  [ "$exists" = "1" ] || demo_die "database $db does not exist"
  # -C puts CREATE DATABASE (owner, encoding) in the archive; -Fc is the compressed custom format.
  demo_dc exec -T postgresql pg_dump -U dlap -Fc -C "$db" > "$TMP/$db.dump"
  [ -s "$TMP/$db.dump" ] || demo_die "pg_dump of $db produced no data"
  # The archive must be readable: list its table of contents.
  demo_dc exec -T postgresql pg_restore -l < "$TMP/$db.dump" > /dev/null || demo_die "the dump of $db is not a readable archive"
  printf '%s  %s\n' "$(sha256_of "$TMP/$db.dump")" "$db.dump" >> "$TMP/SHA256SUMS"
  demo_say "  $db: $(wc -c < "$TMP/$db.dump" | tr -d ' ') bytes"
done

assets="$(demo_pq dlap_common "SELECT count(*) FROM dlap_common.dlap_assets")"
labels="$(demo_pq dlap_common "SELECT count(*) FROM dlap_common.dlap_asset_label_assignments")"
rev="$(git -C "$DEMO_REPO_ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)"
{
  echo "CREATED_UTC=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "PROJECT=$(demo_project)"
  echo "GIT_REV=$rev"
  echo "DATABASES=\"$DBS\""
  echo "ASSETS=${assets:-0}"
  echo "LABEL_ASSIGNMENTS=${labels:-0}"
} > "$TMP/manifest.env"

# Swap in. The previous golden folder is kept once.
if [ -d "$OUT" ]; then
  rm -rf "$OUT.prev"
  mv "$OUT" "$OUT.prev"
fi
mv "$TMP" "$OUT"
trap - EXIT
demo_say "golden dump written to $OUT (assets=${assets:-0}, label assignments=${labels:-0})"
