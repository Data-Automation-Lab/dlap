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
# Row counts of every table of every database (DR-01): the evidence a restore is compared with.
#
# Prints one line per table: database, table, rows (tab separated), sorted. With --digest a fourth
# column is a checksum of the content of all rows (the sum of the first 64 bits of each row's MD5, hashed
# again), so two databases can be shown to hold the same content, not only the same number of rows. It does
# not depend on the order of the rows and it streams, so it works on tables of any size. Run it on the live database before a disaster and on
# the restored one after it, and compare the two outputs (diff), or let restore-drill.sh do it.
#
# Usage:  rowcounts.sh [--databases LIST] [--digest] [--compare FILE]
#   --databases LIST   comma or space separated (default: every connectable non-template database
#                      except postgres)
#   --digest           add the MD5 column
#   --compare FILE     compare with a file written by an earlier run (same options) instead of
#                      printing: exit 0 when equal, 1 and the differing lines otherwise
#   -h, --help         this text
# Environment: PGHOST, PGPORT, PGUSER, PGPASSWORD.
# Exit: 0 printed or equal; 1 differs; 2 bad argument.

set -euo pipefail

# shellcheck disable=SC2034  # read by lib.sh
DR_PROG=rowcounts
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

DBS=""
DIGEST=0
COMPARE=""

usage() { awk '/^# Row counts of every table/ { on = 1 } on && /^#/ { sub(/^# ?/, ""); print; next } on { exit }' "${BASH_SOURCE[0]}"; }

while [ $# -gt 0 ]; do
  case "$1" in
    --databases) [ $# -ge 2 ] || dr_refuse "--databases needs a list"; DBS="$2"; shift 2 ;;
    --digest) DIGEST=1; shift ;;
    --compare) [ $# -ge 2 ] || dr_refuse "--compare needs a file"; COMPARE="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) dr_refuse "unknown argument: $1" ;;
  esac
done
if [ -n "$COMPARE" ] && [ ! -f "$COMPARE" ]; then dr_refuse "not a file: $COMPARE"; fi

if [ -z "$DBS" ]; then DBS="$(dr_databases | tr '\n' ' ')"; else DBS="$(printf '%s' "$DBS" | tr ', ' '  ')"; fi

# digests <database>: database, table, rows, content checksum.
digests() {
  dr_psql -d "$1" -F $'\t' <<'SQL'
SELECT format('SELECT current_database(), %L, count(*), md5(coalesce(sum((''x'' || substr(md5(t::text), 1, 16))::bit(64)::bigint::numeric)::text, ''0'')) FROM %s t',
              format('%I.%I', n.nspname, c.relname), format('%I.%I', n.nspname, c.relname))
  FROM pg_class c
  JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE c.relkind = 'r'
   AND n.nspname NOT IN ('pg_catalog', 'information_schema')
   AND n.nspname !~ '^pg_toast'
   AND n.nspname !~ '^pg_temp'
   AND NOT EXISTS (SELECT 1 FROM pg_depend d
                    WHERE d.classid = 'pg_class'::regclass AND d.objid = c.oid AND d.deptype = 'e')
 ORDER BY n.nspname, c.relname
\gexec
SQL
}

out="$(mktemp)"
trap 'rm -f "$out"' EXIT
for db in $DBS; do
  if [ "$DIGEST" -eq 1 ]; then digests "$db" >> "$out"; else dr_target_rowcounts "$db" >> "$out"; fi
done
LC_ALL=C sort "$out" -o "$out"

if [ -z "$COMPARE" ]; then
  cat "$out"
  exit 0
fi
if diff <(LC_ALL=C sort "$COMPARE") "$out" > /dev/null; then
  dr_say "equal: $(wc -l < "$out" | tr -d ' ') tables, $(awk -F'\t' '{ s += $3 } END { printf "%.0f", s }' "$out") rows"
  exit 0
fi
diff <(LC_ALL=C sort "$COMPARE") "$out" | grep '^[<>]' | sed 's/^</expected:/; s/^>/actual:  /' >&2 || true
dr_err "the row counts differ from $COMPARE"
exit 1
