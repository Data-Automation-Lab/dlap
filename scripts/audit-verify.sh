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
# Independent verifier of the DLAP audit log (AUD-01, docs/specs/audit-log.md section
# "Verification"). It needs no DLAP service: it recomputes every hash chain in
# dlap_common.audit_log inside PostgreSQL with pg_catalog functions only (it never calls
# dlap_common.audit_entry_hash, which a database owner could replace), then checks each signed
# checkpoint that was exported to write-once storage: the Ed25519 signature with OpenSSL against a
# public key you trust, and the signed hash against the row at that position.
#
# Usage:
#   bash scripts/audit-verify.sh [--stream ID]... [--checkpoints DIR] [--public-key PEM]...
#                                [--require-checkpoints] [--quiet]
#
#   --stream ID             Verify this stream only (repeatable). Default: every registered stream.
#   --checkpoints DIR       A local copy of the exported checkpoints: DIR/<stream>/<seq>.json with
#                           the .sig next to it, as the export prefix holds them (for example
#                           "aws s3 sync s3://bucket/dlap-audit DIR" or "gcloud storage rsync").
#   --public-key PEM        A trusted Ed25519 public key (repeatable); GET /api/v1/audit/keys lists
#                           them. Required with --checkpoints.
#   --require-checkpoints   Fail a stream that has entries but no verified checkpoint in DIR.
#   --quiet                 Print only problems and the summary line.
#
# Connection, as for deployment/platform/database/upgrades/run-upgrades.sh:
#   DLAP_COMMON_DB_URL   libpq URI or conninfo of dlap_common (a leading "jdbc:" is dropped);
#                        without it psql uses PGHOST, PGPORT, PGUSER, PGPASSWORD, PGDATABASE
#                        (default dlap_common). Any role with SELECT on the audit tables works.
#   DLAP_PSQL            The psql command, default "psql", for example
#                        DLAP_PSQL="docker exec -i dlap-postgresql psql -U dlap".
#
# Output: one line per stream, "OK ..." or "BROKEN ... problem=<P> seq=<N>", then a summary.
# Problems: SEQ_GAP, PREV_HASH_MISMATCH, RECORD_NOT_ASCII, ENTRY_HASH_MISMATCH (the chain),
# BAD_CHECKPOINT_FILE, UNKNOWN_KEY, BAD_SIGNATURE, ANCHOR_MISMATCH, ANCHOR_BEYOND_HEAD,
# NO_CHECKPOINT, UNKNOWN_STREAM (the checkpoints).
#
# Exit codes: 0 every stream verified; 1 at least one stream is BROKEN; 2 usage or tool error.
#
# Needs bash 4 or newer, psql, openssl 3 (Ed25519 with -rawin), base64 and sha256sum or shasum.

set -euo pipefail
export LC_ALL=C
# Git Bash: native openssl must get Windows paths for mktemp files, so path conversion stays on.
unset MSYS_NO_PATHCONV MSYS2_ARG_CONV_EXCL

STREAMS=()
CHECKPOINT_DIR=""
PUBLIC_KEYS=()
REQUIRE_CHECKPOINTS=0
QUIET=0
STREAM_PATTERN='^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$'

die() { printf 'audit-verify: ERROR: %s\n' "$*" >&2; exit 2; }
say() { [ "$QUIET" -eq 1 ] || printf '%s\n' "$*"; }
usage() { sed -n '/^# Usage:/,/^# Needs bash/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }

while [ $# -gt 0 ]; do
  case "$1" in
    --stream) [ $# -ge 2 ] || die "--stream needs an id"; STREAMS+=("$2"); shift ;;
    --checkpoints) [ $# -ge 2 ] || die "--checkpoints needs a directory"; CHECKPOINT_DIR="$2"; shift ;;
    --public-key) [ $# -ge 2 ] || die "--public-key needs a file"; PUBLIC_KEYS+=("$2"); shift ;;
    --require-checkpoints) REQUIRE_CHECKPOINTS=1 ;;
    --quiet) QUIET=1 ;;
    -h|--help) usage; exit 0 ;;
    *) printf 'audit-verify: unknown argument: %s\n' "$1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done

for s in "${STREAMS[@]+"${STREAMS[@]}"}"; do
  [[ $s =~ $STREAM_PATTERN ]] || die "not a stream id: $s"
done
if [ -n "$CHECKPOINT_DIR" ]; then
  [ -d "$CHECKPOINT_DIR" ] || die "checkpoint directory not found: $CHECKPOINT_DIR"
  [ "${#PUBLIC_KEYS[@]}" -gt 0 ] || die "--checkpoints needs at least one --public-key"
fi
[ "$REQUIRE_CHECKPOINTS" -eq 0 ] || [ -n "$CHECKPOINT_DIR" ] || die "--require-checkpoints needs --checkpoints"

# --- tools ---------------------------------------------------------------------------------
read -r -a PSQL <<< "${DLAP_PSQL:-psql}"
command -v "${PSQL[0]}" >/dev/null 2>&1 || die "${PSQL[0]} not found (set DLAP_PSQL to reach the database)"
if command -v sha256sum >/dev/null 2>&1; then SHA=(sha256sum); elif command -v shasum >/dev/null 2>&1; then
  SHA=(shasum -a 256); else die "need sha256sum or shasum"; fi
if [ -n "$CHECKPOINT_DIR" ]; then
  command -v openssl >/dev/null 2>&1 || die "openssl not found"
  command -v base64 >/dev/null 2>&1 || die "base64 not found"
fi
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

CONN="${DLAP_COMMON_DB_URL:-${PGDATABASE:-dlap_common}}"
CONN="${CONN#jdbc:}"

# sql VAR=VALUE... : runs the SQL on stdin with pg_catalog first on the search path, prints
# unaligned rows with "|" between columns. Stream ids are passed as psql variables.
sql() {
  local args=()
  for v in "$@"; do args+=(-v "$v"); done
  { printf 'SET search_path TO pg_catalog;\n'; cat; } \
    | "${PSQL[@]}" -X -q -At -F '|' -v ON_ERROR_STOP=1 "${args[@]+"${args[@]}"}" -d "$CONN"
}

if ! out="$(sql <<< 'SELECT 1' 2>&1)" || [ "$out" != "1" ]; then
  printf '%s\n' "$out" >&2
  die "cannot query dlap_common (check DLAP_COMMON_DB_URL, the PG* variables or DLAP_PSQL)"
fi

# query OUTVAR [VAR=VALUE]... : sql into a variable. Any SQL error stops the run with exit 2, so a
# check that could not run never reads as a check that passed.
query() {
  local out_var="$1" text
  shift
  if ! text="$(sql "$@" | tr -d '\r')"; then
    die "a verification query failed; nothing was verified"
  fi
  printf -v "$out_var" '%s' "$text"
}

query registered_text <<< 'SELECT stream_id FROM dlap_common.audit_streams ORDER BY stream_id;'
REGISTERED=()
while IFS= read -r line; do
  if [ -n "$line" ]; then REGISTERED+=("$line"); fi
done <<< "$registered_text"
if [ "${#STREAMS[@]}" -eq 0 ]; then
  STREAMS=("${REGISTERED[@]+"${REGISTERED[@]}"}")
fi

# --- trusted keys --------------------------------------------------------------------------
declare -A KEYS=()
for pem in "${PUBLIC_KEYS[@]+"${PUBLIC_KEYS[@]}"}"; do
  [ -f "$pem" ] || die "public key not found: $pem"
  der="$WORK/key.der"
  openssl pkey -pubin -in "$pem" -outform DER -out "$der" 2>/dev/null || die "not a public key: $pem"
  openssl pkey -pubin -inform DER -in "$der" -noout -text 2>/dev/null | grep -q 'ED25519' \
    || die "not an Ed25519 key: $pem"
  kid="$("${SHA[@]}" < "$der" | cut -c1-16)"
  KEYS[$kid]="$pem"
done

CHAIN_SQL="
WITH l AS (
  SELECT seq, prev_hash, entry_hash, record,
         pg_catalog.lag(seq) OVER w AS prev_seq,
         pg_catalog.lag(entry_hash) OVER w AS prev_entry
  FROM dlap_common.audit_log WHERE stream_id = :'s'
  WINDOW w AS (ORDER BY seq)
), c AS (
  SELECT seq, entry_hash,
    CASE
      WHEN seq <> COALESCE(prev_seq, 0) + 1 THEN 'SEQ_GAP'
      WHEN prev_hash::text <> COALESCE(prev_entry::text, pg_catalog.repeat('0', 64)) THEN 'PREV_HASH_MISMATCH'
      WHEN record !~ '^[ -~]+\$' THEN 'RECORD_NOT_ASCII'
      WHEN entry_hash::text <> pg_catalog.encode(pg_catalog.sha256(pg_catalog.convert_to(
             'DLAP-AUDIT-1' || pg_catalog.chr(10) || :'s' || pg_catalog.chr(10) || seq::text || pg_catalog.chr(10)
             || prev_hash::text || pg_catalog.chr(10) || record, 'UTF8')), 'hex') THEN 'ENTRY_HASH_MISMATCH'
    END AS problem
  FROM l
)
SELECT 'ROWS', pg_catalog.count(*)::text, COALESCE(pg_catalog.max(seq), 0)::text FROM c
UNION ALL
(SELECT 'BAD', problem, seq::text FROM c WHERE problem IS NOT NULL ORDER BY seq LIMIT 1)
UNION ALL
(SELECT 'HEAD', entry_hash::text, seq::text FROM c ORDER BY seq DESC LIMIT 1);
"

PAYLOAD_PATTERN='^\{"entryHash":"([0-9a-f]{64})","format":"dlap-audit-checkpoint/1","keyId":"([0-9a-f]{16})","seq":([1-9][0-9]{0,18}),"signedAt":"([0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{6}Z)","streamId":"([a-z0-9_.]+)"\}$'

BROKEN=0
VERIFIED=0
report_broken() { printf 'BROKEN stream=%s problem=%s seq=%s%s\n' "$1" "$2" "$3" "${4:+ detail=$4}"; BROKEN=$((BROKEN + 1)); }

verify_stream() {
  local stream="$1" rows=0 head_seq=0 head_hash="" bad_problem="" bad_seq=""
  if ! printf '%s\n' "${REGISTERED[@]+"${REGISTERED[@]}"}" | grep -qx -- "$stream"; then
    report_broken "$stream" UNKNOWN_STREAM "-" "the stream is not registered in dlap_common.audit_streams"
    return
  fi
  local kind a b chain_text seen_rows=0
  query chain_text "s=$stream" <<< "$CHAIN_SQL"
  while IFS='|' read -r kind a b; do
    case "$kind" in
      ROWS) rows="$a"; head_seq="$b"; seen_rows=1 ;;
      BAD) bad_problem="$a"; bad_seq="$b" ;;
      HEAD) head_hash="$a" ;;
    esac
  done <<< "$chain_text"
  [ "$seen_rows" -eq 1 ] || die "the chain query of $stream returned no summary row"

  if [ -n "$bad_problem" ]; then
    report_broken "$stream" "$bad_problem" "$bad_seq"
    return
  fi

  local checked=0 latest=0
  if [ -n "$CHECKPOINT_DIR" ] && [ -d "$CHECKPOINT_DIR/$stream" ]; then
    local values="" f payload sigfile name seq hash kid
    declare -A WANT=()
    shopt -s nullglob
    for f in "$CHECKPOINT_DIR/$stream"/*.json; do
      name="$(basename "$f" .json)"
      payload="$(tr -d '\r\n' < "$f")"
      if [[ ! $name =~ ^[0-9]{20}$ ]] || [[ ! $payload =~ $PAYLOAD_PATTERN ]] || [ "${BASH_REMATCH[5]}" != "$stream" ] \
          || [ "$((10#$name))" != "${BASH_REMATCH[3]}" ]; then
        report_broken "$stream" BAD_CHECKPOINT_FILE "-" "$(basename "$f")"
        shopt -u nullglob
        return
      fi
      hash="${BASH_REMATCH[1]}"; kid="${BASH_REMATCH[2]}"; seq="${BASH_REMATCH[3]}"
      if [ -z "${KEYS[$kid]+x}" ]; then
        report_broken "$stream" UNKNOWN_KEY "$seq" "key $kid is not among the --public-key files"
        shopt -u nullglob
        return
      fi
      sigfile="${f%.json}.sig"
      printf '%s' "$payload" > "$WORK/payload"
      if [ ! -f "$sigfile" ] || ! tr -d '\r\n' < "$sigfile" | base64 -d > "$WORK/sig" 2>/dev/null \
          || ! openssl pkeyutl -verify -pubin -inkey "${KEYS[$kid]}" -rawin -in "$WORK/payload" \
               -sigfile "$WORK/sig" >/dev/null 2>&1; then
        report_broken "$stream" BAD_SIGNATURE "$seq"
        shopt -u nullglob
        return
      fi
      WANT[$seq]="$hash"
      values="${values:+$values,}($seq)"
      if [ "$seq" -gt "$latest" ]; then latest="$seq"; fi
      checked=$((checked + 1))
    done
    shopt -u nullglob
    if [ "$checked" -gt 0 ]; then
      local got_seq got_hash got_text
      declare -A GOT=()
      query got_text "s=$stream" <<< "SELECT l.seq::text, l.entry_hash::text FROM dlap_common.audit_log l
          JOIN (VALUES $values) AS v(seq) ON v.seq = l.seq WHERE l.stream_id = :'s';"
      while IFS='|' read -r got_seq got_hash; do
        if [ -n "$got_seq" ]; then GOT[$got_seq]="$got_hash"; fi
      done <<< "$got_text"
      for seq in $(printf '%s\n' "${!WANT[@]}" | sort -n); do
        if [ -z "${GOT[$seq]+x}" ]; then
          report_broken "$stream" ANCHOR_BEYOND_HEAD "$seq" "a signed checkpoint covers seq $seq, the stream ends at $head_seq"
          return
        fi
        if [ "${GOT[$seq]}" != "${WANT[$seq]}" ]; then
          report_broken "$stream" ANCHOR_MISMATCH "$seq"
          return
        fi
      done
    fi
  fi
  if [ "$REQUIRE_CHECKPOINTS" -eq 1 ] && [ "$rows" -gt 0 ] && [ "$checked" -eq 0 ]; then
    report_broken "$stream" NO_CHECKPOINT "-"
    return
  fi
  VERIFIED=$((VERIFIED + 1))
  say "OK stream=$stream entries=$rows head=$head_seq${head_hash:+ head_hash=$head_hash} checkpoints=$checked latest_checkpoint=$latest unanchored=$((head_seq - latest))"
}

for stream in "${STREAMS[@]+"${STREAMS[@]}"}"; do
  verify_stream "$stream"
done

# Checkpoints of a stream the database no longer knows mean the stream was removed.
if [ -n "$CHECKPOINT_DIR" ]; then
  for d in "$CHECKPOINT_DIR"/*/; do
    [ -d "$d" ] || continue
    s="$(basename "$d")"
    [[ $s =~ $STREAM_PATTERN ]] || continue
    if ! printf '%s\n' "${REGISTERED[@]+"${REGISTERED[@]}"}" | grep -qx -- "$s"; then
      report_broken "$s" UNKNOWN_STREAM "-" "checkpoints exist for a stream that is not registered"
    fi
  done
fi

printf 'audit-verify: %d stream(s) verified, %d broken\n' "$VERIFIED" "$BROKEN"
[ "$BROKEN" -eq 0 ]
