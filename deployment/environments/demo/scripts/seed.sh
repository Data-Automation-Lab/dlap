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
# Seed the demo with fixture data and take the golden dump (DEMO-01).
#
# Starts the stack once with the fixture replay switched on. da-logingest then replays
# e2e-tests/fixtures/replay/*.json (fictional AWS, GCP and Azure audit events) through the real spine:
#
#   da-logingest -> dlap.log.event.normalized -> da-discovery (router) -> typed topics
#     -> da-catalog (asset rows) -> dlap.catalog.asset.upserted -> da-autolabel (rules labels)
#
# The script waits until the catalog holds the expected assets of every cloud, gives the labelling and
# reporting consumers time to settle, takes the golden dump (golden-dump.sh), and recreates
# da-logingest with the replay off, so a later start never replays again. The nightly reset restores
# the golden dump instead of replaying, which is why replay is a one-time step.
#
# Usage:  seed.sh [options]
#   --env-file FILE   environment file (default .env.demo)
#   --no-build        do not build the images (they must exist)
#   --fresh           delete the demo's volumes first (down -v). Destroys its data.
#   --settle SECS     seconds to let labelling and reporting settle after the assets appear (default 60)
#   --timeout SECS    give up waiting for the assets after this long (default 600)
#   -h, --help        this text
#
# Needs the jars and the SPA (up.sh checks). Fixtures only: no cloud is called.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$SCRIPT_DIR/lib.sh"

BUILD_FLAG=""
FRESH=0
SETTLE=60
TIMEOUT=600

usage() { awk '/^# Seed the demo/ { on = 1 } on && /^#/ { sub(/^# ?/, ""); print; next } on { exit }' "${BASH_SOURCE[0]}"; }

while [ $# -gt 0 ]; do
  case "$1" in
    --env-file) [ $# -ge 2 ] || demo_die "--env-file needs a file name"; DEMO_ENV_FILE="$2"; shift 2 ;;
    --no-build) BUILD_FLAG="--no-build"; shift ;;
    --fresh) FRESH=1; shift ;;
    --settle) [ $# -ge 2 ] || demo_die "--settle needs seconds"; SETTLE="$2"; shift 2 ;;
    --timeout) [ $# -ge 2 ] || demo_die "--timeout needs seconds"; TIMEOUT="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) demo_die "unknown argument: $1" ;;
  esac
done

demo_init "$DEMO_ENV_FILE"
UP=("$DEMO_DIR/up.sh" --env-file "$DEMO_ENV_FILE")

# What the fixtures must produce, the same predicates as e2e-tests/cloud-pipeline-e2e.sh.
PRED_GCP="cloud_provider='GCP' AND (asset_uri LIKE 'gs://dlap-gcp-e2e%' OR asset_uri LIKE 'bq://dlap-gcp-e2e/%' OR asset_uri LIKE 'projects/dlap-gcp-e2e/%')"
PRED_AWS="cloud_provider='AWS' AND asset_uri LIKE 's3://dlap-aws-e2e%'"
PRED_AZURE="cloud_provider='AZURE' AND (asset_uri LIKE 'az://dlapazuree2e%' OR asset_uri LIKE '%dlapazuree2e.blob.core.windows.net%')"
MIN_GCP=5; MIN_AWS=3; MIN_AZURE=3

seed_start=$SECONDS

if [ "$FRESH" = "1" ]; then
  demo_say "--fresh: deleting the volumes of project $(demo_project)"
  demo_harness_prepare
  demo_dc down -v --remove-orphans
fi

# 1) Start everything with the replay on. The environment of this process outranks the file.
demo_say "starting the stack with the fixture replay ON"
t0=$SECONDS
DEMO_REPLAY_ENABLED=true "${UP[@]}" $BUILD_FLAG
demo_say "stack healthy after $((SECONDS - t0))s"

# 2) Wait for the assets of every cloud.
count() { demo_pq dlap_common "SELECT count(*) FROM dlap_common.dlap_assets WHERE $1"; }
deadline=$((SECONDS + TIMEOUT))
g=0; a=0; z=0
while (( SECONDS < deadline )); do
  g="$(count "$PRED_GCP")"; a="$(count "$PRED_AWS")"; z="$(count "$PRED_AZURE")"
  [[ "$g" =~ ^[0-9]+$ ]] || g=0; [[ "$a" =~ ^[0-9]+$ ]] || a=0; [[ "$z" =~ ^[0-9]+$ ]] || z=0
  if (( g >= MIN_GCP && a >= MIN_AWS && z >= MIN_AZURE )); then break; fi
  sleep 5
done
demo_say "assets from the replay: gcp=$g (>= $MIN_GCP) aws=$a (>= $MIN_AWS) azure=$z (>= $MIN_AZURE)"
if (( g < MIN_GCP || a < MIN_AWS || z < MIN_AZURE )); then
  demo_die "the replay did not reach the catalog within ${TIMEOUT}s; see: up.sh logs da-logingest da-discovery da-catalog"
fi

# 3) Let labelling and reporting settle, then report what the consumers produced.
demo_say "settling ${SETTLE}s for labelling and reporting"
sleep "$SETTLE"
total_assets="$(demo_pq dlap_common 'SELECT count(*) FROM dlap_common.dlap_assets')"
rule_labels="$(demo_pq dlap_common "SELECT count(*) FROM dlap_common.dlap_asset_label_assignments WHERE source = 'SMART_LABEL_RULE'")"
all_labels="$(demo_pq dlap_common 'SELECT count(*) FROM dlap_common.dlap_asset_label_assignments')"
demo_say "catalog: $total_assets assets, $all_labels label assignments ($rule_labels from the rules provider)"
if [ "${rule_labels:-0}" = "0" ]; then
  demo_warn "the rules provider has labelled nothing; the demo still has the seeded labels. Check: up.sh logs da-autolabel"
fi

# 4) The golden dump.
"$SCRIPT_DIR/golden-dump.sh" --env-file "$DEMO_ENV_FILE"

# 5) Replay off: recreate da-logingest with the file's environment only.
demo_say "recreating da-logingest with the replay OFF"
"${UP[@]}" --no-build up -d --no-build --wait --wait-timeout 600 da-logingest

demo_say "seeded in $((SECONDS - seed_start))s; the golden dump is in $(demo_state_dir)/golden"
