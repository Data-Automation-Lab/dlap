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
# ---------------------------------------------------------------------------
# DLAP asset_uri re-key rehearsal (ID-03) on a copy of a harness database.
#
# Follows the runbook in docs/guides/upgrade.md ("Re-key asset URIs") against the real catalog
# image, on a COPY of the dlap_common database that cloud-pipeline-e2e.sh filled:
#
#   1. cloud-pipeline-e2e.sh all          fills dlap_common through the real spine
#   2. drain                              stop da-logingest, wait until every consumer group has
#                                         no lag, stop da-discovery, wait again, stop da-catalog
#   3. copy                               pg_dump dlap_common | psql dlap_common_rekey_e2e
#   4. legacy rows                        add rows in pre-canonical forms to the copy: an older
#                                         bare GCS bucket name and a newer S3 ARN that collide
#                                         with pipeline rows, with labels, lineage and usage, and
#                                         a lone BigQuery "p:d.t" row
#   5. da-catalog --rekey dry-run         report only: the copy is unchanged
#   6. da-catalog --rekey apply           merges and re-keys; the oldest asset_id survives
#   7. da-catalog --rekey apply (again)   nothing to do; the copy is unchanged
#   8. da-catalog --rekey revert          the copy equals its state before step 6
#   9. da-catalog --rekey apply           re-applied; the original dlap_common never changed
#
# PASS when every check holds. The stack is left running with the writers started again.
#
# Usage:
#   ./asset-uri-rekey-e2e.sh                 # full run, pipeline included
#   ./asset-uri-rekey-e2e.sh --no-build      # skip the gradle jar build of the pipeline run
#   ./asset-uri-rekey-e2e.sh --reuse-stack   # skip step 1: use the stack a pipeline run left up
#   ./asset-uri-rekey-e2e.sh --keep-copy     # keep the copy database afterwards
#
# Isolation (HARN-01, harness-lib.sh): the run holds the host-wide lock (the nested pipeline run
# sees it held and does not wait), runs in this worktree's compose project and reads the host
# ports from DLAP_PORT_<SERVICE>. Logs: ${TMPDIR:-/tmp}/asset-uri-rekey-e2e-<step>.log.
# ---------------------------------------------------------------------------
set -uo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
DEV="$REPO/deployment/environments/dev"
E2E="$REPO/e2e-tests"
COMPOSE=(docker compose \
  -f "$DEV/docker-compose.yml" \
  -f "$DEV/docker-compose.microservices.yml" \
  -f "$E2E/docker-compose.e2e.yml")
# shellcheck source=harness-lib.sh
source "$E2E/harness-lib.sh"

say(){ echo "[rekey-e2e] $*"; }
fail(){ echo "[rekey-e2e] FAIL: $*" >&2; exit 1; }

FIXDIR="$E2E/fixtures/replay"
command -v cygpath >/dev/null 2>&1 && FIXDIR="$(cygpath -m "$FIXDIR")"
export DLAP_E2E_FIXTURES_DIR="$FIXDIR"

NO_BUILD=""; REUSE=""; KEEP=""
for a in "$@"; do
  case "$a" in
    --no-build) NO_BUILD=1 ;;
    --reuse-stack) REUSE=1 ;;
    --keep-copy) KEEP=1 ;;
    -h|--help) sed -n '/^# DLAP asset_uri re-key/,/^# -----/p' "$0" | sed 's/^# \{0,1\}//' | sed '$d'; exit 0 ;;
    *) fail "unknown argument '$a' (want --no-build, --reuse-stack, --keep-copy)" ;;
  esac
done

PY="$(command -v python3 || command -v python || true)"
[[ -n "$PY" ]] || fail "python3 (or python) is needed to read the JSON reports"

harness_init asset-uri-rekey "$REPO"
LOGS="${TMPDIR:-/tmp}"
SRC_DB=dlap_common
COPY_DB=dlap_common_rekey_e2e
checks=0

pq(){ # pq <database> <sql>: one value, whitespace trimmed
  docker exec dlap-postgresql psql -U dlap -d "$1" -v ON_ERROR_STOP=1 -tAc "$2" 2>&1 | tr -d '[:space:]'
}
check(){ # check <description> <actual> <expected>
  if [[ "$2" == "$3" ]]; then say "ok: $1"; checks=$((checks + 1)); else fail "$1: expected '$3', got '$2'"; fi
}
# md5 over every row of the tables a revert restores (last_modified_ts is the row time the asset
# trigger sets on every update, so it is left out).
fingerprint(){ # fingerprint <database>
  pq "$1" "SELECT md5(string_agg(t, '#' ORDER BY t)) FROM (
      SELECT 'a' || md5(COALESCE(string_agg((to_jsonb(x) - 'last_modified_ts')::text, '|' ORDER BY (to_jsonb(x) - 'last_modified_ts')::text), '')) AS t FROM dlap_common.dlap_assets x
      UNION ALL SELECT 'l' || md5(COALESCE(string_agg(to_jsonb(x)::text, '|' ORDER BY to_jsonb(x)::text), '')) FROM dlap_common.dlap_asset_label_assignments x
      UNION ALL SELECT 'c' || md5(COALESCE(string_agg((to_jsonb(x) - 'last_modified_ts')::text, '|' ORDER BY (to_jsonb(x) - 'last_modified_ts')::text), '')) FROM dlap_common.dlap_cloud_resources x
      UNION ALL SELECT 'g' || md5(COALESCE(string_agg(to_jsonb(x)::text, '|' ORDER BY to_jsonb(x)::text), '')) FROM dlap_catalog.dlap_asset_lineage x
      UNION ALL SELECT 'u' || md5(COALESCE(string_agg(to_jsonb(x)::text, '|' ORDER BY to_jsonb(x)::text), '')) FROM dlap_catalog.asset_usage_daily x
      UNION ALL SELECT 'p' || md5(COALESCE(string_agg(to_jsonb(x)::text, '|' ORDER BY to_jsonb(x)::text), '')) FROM dlap_catalog.processed_event x
      UNION ALL SELECT 'r' || md5(COALESCE(string_agg(to_jsonb(x)::text, '|' ORDER BY to_jsonb(x)::text), '')) FROM dlap_catalog.retention_rule x) f"
}
# Total lag of the consumer groups whose name matches <regex>; "-" (no committed offset) counts 0.
group_lag(){ # group_lag <regex>
  docker exec dlap-kafka kafka-consumer-groups --bootstrap-server kafka:29092 --describe --all-groups 2>/dev/null \
    | awk -v re="$1" '$1 ~ re && $6 ~ /^[0-9]+$/ { s += $6 } END { print s + 0 }'
}
wait_drained(){ # wait_drained <regex> <label>
  local lag i
  for i in $(seq 1 60); do
    lag="$(group_lag "$1")"
    if [[ "$lag" == "0" ]]; then say "drained: $2 (lag 0)"; return 0; fi
    sleep 2
  done
  fail "$2 still has lag $lag after 120 s"
}
# Runs the catalog image once in re-key mode against the copy and leaves the report in
# $LOGS/asset-uri-rekey-e2e-<step>.json. Prints the exit code.
rekey(){ # rekey <command> <step>
  local log="$LOGS/asset-uri-rekey-e2e-$2.log" rc
  "${COMPOSE[@]}" run --rm --no-deps -T \
      -e SPRING_DATASOURCE_URL="jdbc:postgresql://postgresql:5432/$COPY_DB" \
      -e DLAP_CATALOG_REKEY_COMMAND="$1" \
      -e DLAP_CATALOG_REKEY_OPERATOR=asset-uri-rekey-e2e \
      -e SPRING_MAIN_WEB_APPLICATION_TYPE=none \
      -e SPRING_KAFKA_LISTENER_AUTO_STARTUP=false \
      -e DLAP_CATALOG_INGEST_MATCH_INDEX_ON_STARTUP=false \
      -e DLAP_CATALOG_RETENTION_ENABLED=false \
      -e DLAP_CATALOG_RETENTION_PUBLISH_ENABLED=false \
      -e DLAP_CATALOG_PROCESSED_EVENT_PRUNE_ENABLED=false \
      da-catalog >"$log" 2>&1
  rc=$?
  tr -d '\r' <"$log" | sed -n '/^----- BEGIN DLAP ASSET URI REKEY REPORT -----$/,/^----- END DLAP ASSET URI REKEY REPORT -----$/p' \
    | sed '1d;$d' >"$LOGS/asset-uri-rekey-e2e-$2.json"
  echo "$rc"
}
jget(){ # jget <step> <python expression over r>
  "$PY" -c "import json,sys; r=json.load(open(sys.argv[1])); print($2)" "$LOGS/asset-uri-rekey-e2e-$1.json"
}

# 1) A harness database.
if [[ -z "$REUSE" ]]; then
  say "step 1: cloud-pipeline-e2e.sh all (fills $SRC_DB through the spine)"
  bash "$E2E/cloud-pipeline-e2e.sh" all ${NO_BUILD:+--no-build} >"$LOGS/asset-uri-rekey-e2e-pipeline.log" 2>&1 \
    || fail "the pipeline run failed (see $LOGS/asset-uri-rekey-e2e-pipeline.log)"
  say "pipeline PASS"
fi
pipeline_rows="$(pq "$SRC_DB" "SELECT count(*) FROM dlap_common.dlap_assets")"
[[ "$pipeline_rows" =~ ^[0-9]+$ ]] && (( pipeline_rows > 0 )) || fail "no catalog rows in $SRC_DB (got '$pipeline_rows')"

# 2) Drain, edge inward, as the runbook says.
say "step 2: drain the topics"
"${COMPOSE[@]}" stop da-logingest >/dev/null 2>&1 || fail "could not stop da-logingest"
wait_drained '^da-discovery' "da-discovery groups"
"${COMPOSE[@]}" stop da-discovery >/dev/null 2>&1 || fail "could not stop da-discovery"
wait_drained '^da-catalog' "da-catalog groups"
"${COMPOSE[@]}" stop da-catalog >/dev/null 2>&1 || fail "could not stop da-catalog"
SRC_BEFORE="$(fingerprint "$SRC_DB")"

# 3) Copy.
say "step 3: copy $SRC_DB to $COPY_DB"
pq postgres "DROP DATABASE IF EXISTS $COPY_DB" >/dev/null
pq postgres "CREATE DATABASE $COPY_DB" >/dev/null
pq postgres "GRANT CONNECT, CREATE ON DATABASE $COPY_DB TO da_catalog_user" >/dev/null
docker exec dlap-postgresql sh -c "pg_dump -U dlap $SRC_DB | psql -q -v ON_ERROR_STOP=1 -U dlap -d $COPY_DB" \
    >"$LOGS/asset-uri-rekey-e2e-copy.log" 2>&1 || fail "copy failed (see $LOGS/asset-uri-rekey-e2e-copy.log)"
check "copy has every row" "$(pq "$COPY_DB" "SELECT count(*) FROM dlap_common.dlap_assets")" "$pipeline_rows"

# 4) Legacy rows. GCS: an OLDER row stored under the bare bucket name of a pipeline bucket, so it
#    survives and absorbs the pipeline row. S3: a NEWER row stored as the bucket ARN of a pipeline
#    bucket, so the pipeline row survives. BigQuery: a lone "p:d.t" row.
GCS_URI="$(pq "$COPY_DB" "SELECT asset_uri FROM dlap_common.dlap_assets WHERE asset_uri ~ '^gs://[a-z0-9-]+\$' ORDER BY asset_uri LIMIT 1")"
S3_URI="$(pq "$COPY_DB" "SELECT asset_uri FROM dlap_common.dlap_assets WHERE asset_uri ~ '^s3://[A-Za-z0-9.-]+\$' ORDER BY asset_uri LIMIT 1")"
[[ -n "$GCS_URI" && -n "$S3_URI" ]] || fail "the pipeline left no plain gs:// or s3:// bucket row to collide with"
GCS_BUCKET="${GCS_URI#gs://}"; S3_BUCKET="${S3_URI#s3://}"
GCS_ID="$(pq "$COPY_DB" "SELECT asset_id FROM dlap_common.dlap_assets WHERE asset_uri = '$GCS_URI'")"
S3_ID="$(pq "$COPY_DB" "SELECT asset_id FROM dlap_common.dlap_assets WHERE asset_uri = '$S3_URI'")"
LEGACY_GCS=00000000-0000-0000-0e2e-000000000001
LEGACY_S3=00000000-0000-0000-0e2e-000000000002
LEGACY_BQ=00000000-0000-0000-0e2e-000000000003
say "step 4: legacy rows in the copy (collide with $GCS_URI and $S3_URI)"
docker exec -i dlap-postgresql psql -q -v ON_ERROR_STOP=1 -U dlap -d "$COPY_DB" >"$LOGS/asset-uri-rekey-e2e-seed.log" 2>&1 <<SQL || fail "seeding the copy failed (see $LOGS/asset-uri-rekey-e2e-seed.log)"
INSERT INTO dlap_common.dlap_assets (asset_id, asset_uri, cloud_provider, service_type, asset_name, created_ts)
SELECT '$LEGACY_GCS', '$GCS_BUCKET', 'GCP', 'GCS_BUCKET', NULL, created_ts - interval '1 day'
  FROM dlap_common.dlap_assets WHERE asset_id = '$GCS_ID';
INSERT INTO dlap_common.dlap_assets (asset_id, asset_uri, cloud_provider, service_type, asset_name, created_ts)
SELECT '$LEGACY_S3', 'arn:aws:s3:::$S3_BUCKET', 'AWS', 'S3_BUCKET', 'legacy-arn', created_ts + interval '1 day'
  FROM dlap_common.dlap_assets WHERE asset_id = '$S3_ID';
INSERT INTO dlap_common.dlap_assets (asset_id, asset_uri, cloud_provider, service_type, asset_name)
VALUES ('$LEGACY_BQ', 'rekeye2eproj:sales.orders', 'GCP', 'BIGQUERY_TABLE', 'orders');
-- Labels: the same label on both GCS rows (one is dropped), a second only on the pipeline row.
INSERT INTO dlap_common.dlap_asset_label_assignments (asset_id, label_id, source)
SELECT '$LEGACY_GCS', label_id, 'SYSTEM' FROM dlap_common.dlap_labels WHERE label_name = 'PII';
INSERT INTO dlap_common.dlap_asset_label_assignments (asset_id, label_id, source)
SELECT '$GCS_ID', label_id, 'USER' FROM dlap_common.dlap_labels WHERE label_name IN ('PII', 'GDPR')
ON CONFLICT DO NOTHING;
INSERT INTO dlap_common.dlap_asset_label_assignments (asset_id, label_id, source)
SELECT '$LEGACY_S3', label_id, 'SYSTEM' FROM dlap_common.dlap_labels WHERE label_name = 'Internal';
-- Lineage: pipeline GCS bucket -> S3 bucket, and the same edge from the legacy S3 row's side.
INSERT INTO dlap_catalog.dlap_asset_lineage (lineage_id, created_at, relationship_type, upstream_asset_id, downstream_asset_id)
VALUES (gen_random_uuid(), now(), 'COPIES', '$GCS_ID', '$S3_ID'),
       (gen_random_uuid(), now(), 'COPIES', '$GCS_ID', '$LEGACY_S3'),
       (gen_random_uuid(), now(), 'READS', '$LEGACY_S3', '$GCS_ID');
-- Usage on one day on both GCS rows, and one more day on the pipeline row.
INSERT INTO dlap_catalog.asset_usage_daily (asset_id, day, reads, writes) VALUES
  ('$LEGACY_GCS', DATE '2026-10-01', 2, 0), ('$GCS_ID', DATE '2026-10-01', 3, 1), ('$GCS_ID', DATE '2026-10-02', 5, 0)
ON CONFLICT (asset_id, day) DO UPDATE SET reads = dlap_catalog.asset_usage_daily.reads + EXCLUDED.reads;
SQL
COPY_SEEDED="$(fingerprint "$COPY_DB")"
USAGE_TOTAL="$(pq "$COPY_DB" "SELECT sum(reads) FROM dlap_catalog.asset_usage_daily WHERE asset_id IN ('$LEGACY_GCS', '$GCS_ID')")"

# 5) Dry run.
say "step 5: dry run"
check "dry run exit code" "$(rekey dry-run dry-run)" "0"
check "dry run changes nothing" "$(fingerprint "$COPY_DB")" "$COPY_SEEDED"
check "dry run: GCS collision survivor is the oldest row" \
  "$(jget dry-run "[c['survivor']['assetId'] for c in r['collisions'] if c['canonicalUri']=='$GCS_URI'][0]")" "$LEGACY_GCS"
check "dry run: S3 collision survivor is the pipeline row" \
  "$(jget dry-run "[c['survivor']['assetId'] for c in r['collisions'] if c['canonicalUri']=='$S3_URI'][0]")" "$S3_ID"
check "dry run: lone BigQuery row is re-keyed in place" \
  "$(jget dry-run "[x['to'] for x in r['rekeys'] if x['assetId']=='$LEGACY_BQ'][0]")" "bq://rekeye2eproj/sales/orders"
check "dry run: no blocker" "$(jget dry-run "len(r['blockers'])")" "0"
say "dry run summary: $(jget dry-run "r['summary']")"

# 6) Apply.
say "step 6: apply"
check "apply exit code" "$(rekey apply apply)" "0"
RUN_ID="$(jget apply "r['runId']")"
check "GCS survivor keeps the oldest asset_id and takes the canonical URI" \
  "$(pq "$COPY_DB" "SELECT asset_id FROM dlap_common.dlap_assets WHERE asset_uri = '$GCS_URI'")" "$LEGACY_GCS"
check "GCS survivor native_asset_id is the bare name it was stored under" \
  "$(pq "$COPY_DB" "SELECT native_asset_id FROM dlap_common.dlap_assets WHERE asset_id = '$LEGACY_GCS'")" "$GCS_BUCKET"
check "the merged pipeline GCS row is gone" \
  "$(pq "$COPY_DB" "SELECT count(*) FROM dlap_common.dlap_assets WHERE asset_id IN ('$GCS_ID', '$LEGACY_S3')")" "0"
check "labels moved to the GCS survivor (PII once, GDPR)" \
  "$(pq "$COPY_DB" "SELECT string_agg(l.label_name, ',' ORDER BY l.label_name) FROM dlap_common.dlap_asset_label_assignments x JOIN dlap_common.dlap_labels l USING (label_id) WHERE x.asset_id = '$LEGACY_GCS'")" "GDPR,PII"
check "label moved to the S3 survivor" \
  "$(pq "$COPY_DB" "SELECT count(*) FROM dlap_common.dlap_asset_label_assignments x JOIN dlap_common.dlap_labels l USING (label_id) WHERE x.asset_id = '$S3_ID' AND l.label_name = 'Internal'")" "1"
check "lineage: one COPIES edge and one READS edge between the survivors, no self edge" \
  "$(pq "$COPY_DB" "SELECT string_agg(relationship_type, ',' ORDER BY relationship_type) FROM dlap_catalog.dlap_asset_lineage WHERE (upstream_asset_id, downstream_asset_id) IN (('$LEGACY_GCS', '$S3_ID'), ('$S3_ID', '$LEGACY_GCS'))")" "COPIES,READS"
check "usage conserved on the GCS survivor" \
  "$(pq "$COPY_DB" "SELECT sum(reads) FROM dlap_catalog.asset_usage_daily WHERE asset_id = '$LEGACY_GCS'")" "$USAGE_TOTAL"
check "BigQuery row re-keyed" \
  "$(pq "$COPY_DB" "SELECT asset_uri || '|' || native_asset_id FROM dlap_common.dlap_assets WHERE asset_id = '$LEGACY_BQ'")" "bq://rekeye2eproj/sales/orders|rekeye2eproj:sales.orders"
check "map: the merged GCS row points at its survivor" \
  "$(pq "$COPY_DB" "SELECT survivor_asset_id || ':' || action FROM dlap_catalog.asset_uri_rekey_map WHERE run_id = '$RUN_ID' AND asset_id = '$GCS_ID'")" "$LEGACY_GCS:MERGED"
check "every row that normalizes now holds its canonical URI" \
  "$(pq "$COPY_DB" "SELECT count(*) FROM dlap_catalog.asset_uri_canonical i JOIN dlap_common.dlap_assets a USING (asset_id) WHERE i.asset_uri = a.asset_uri AND i.canonical_uri IS NOT NULL AND i.canonical_uri <> a.asset_uri")" "0"
APPLIED="$(fingerprint "$COPY_DB")"

# 7) Apply again.
say "step 7: apply again"
check "second apply exit code" "$(rekey apply apply-again)" "0"
check "second apply: nothing re-keyed" "$(jget apply-again "r['summary']['rekeyed'] + r['summary']['mergeGroups']")" "0"
check "second apply records no run" "$(pq "$COPY_DB" "SELECT count(*) FROM dlap_catalog.asset_uri_rekey_run")" "1"
check "second apply changes nothing" "$(fingerprint "$COPY_DB")" "$APPLIED"

# 8) Revert.
say "step 8: revert"
check "revert exit code" "$(rekey revert revert)" "0"
check "revert restores the copy as it was before the apply" "$(fingerprint "$COPY_DB")" "$COPY_SEEDED"
check "the run is marked reverted" \
  "$(pq "$COPY_DB" "SELECT status FROM dlap_catalog.asset_uri_rekey_run WHERE run_id = '$RUN_ID'")" "REVERTED"

# 9) Re-apply, then prove the original never changed.
say "step 9: re-apply"
check "re-apply exit code" "$(rekey apply reapply)" "0"
check "re-apply gives the first apply's result" "$(fingerprint "$COPY_DB")" "$APPLIED"
check "the original $SRC_DB is untouched" "$(fingerprint "$SRC_DB")" "$SRC_BEFORE"

if [[ -z "$KEEP" ]]; then pq postgres "DROP DATABASE IF EXISTS $COPY_DB" >/dev/null; fi
"${COMPOSE[@]}" start da-logingest da-discovery da-catalog >/dev/null 2>&1 || say "WARN: could not restart the writers"
say "OVERALL PASS - $checks checks; re-key rehearsed on a copy of $SRC_DB ($pipeline_rows pipeline rows)"
