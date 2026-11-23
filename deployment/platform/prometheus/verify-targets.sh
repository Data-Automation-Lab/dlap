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

# OBS-01: asks a running Prometheus whether the monitoring stack works end to end.
#
# Needs the dev stack up with the monitoring profile and the monitoring overlay
# (deployment/environments/dev/docker-compose.monitoring.yml). It checks that
#   1. every scrape target of prometheus.yml answers (up == 1), da-mcp-pep excepted because the dev
#      compose does not run it;
#   2. every rule group loaded and no rule failed to evaluate;
#   3. Alertmanager is connected.
# Exit 0 when all three hold, 1 otherwise. Reads only; it changes nothing.
#
# Environment: PROMETHEUS_URL (default http://localhost:$DLAP_PORT_PROMETHEUS, 9090 when unset).

set -euo pipefail

prom="${PROMETHEUS_URL:-http://localhost:${DLAP_PORT_PROMETHEUS:-9090}}"

expected=(
  da-admin-service:9091 da-autoarchival:9091 da-autocompliance:9091 da-autodelete:9091
  da-autolabel:9091 da-catalog:9091 da-discovery:9091 da-logingest:9091
  da-policyengine:9091 da-reporting:9091
  postgres-exporter:9187 kafka-exporter:9308 alertmanager:9093 grafana:3000
)

fail=0

if ! up_json="$(curl -fsS --max-time 15 --get "$prom/api/v1/query" --data-urlencode 'query=up')"; then
  echo "FAIL: cannot reach Prometheus at $prom" >&2
  exit 1
fi

echo "== scrape targets"
for target in "${expected[@]}"; do
  if grep -Eq "\"instance\":\"$target\"[^}]*\}[^}]*\"value\":\[[0-9.]+,\"1\"\]" <<<"$up_json"; then
    printf '  up    %s\n' "$target"
  else
    printf '  DOWN  %s\n' "$target"
    fail=1
  fi
done

echo "== rules"
if ! rules_json="$(curl -fsS --max-time 15 "$prom/api/v1/rules")"; then
  echo "FAIL: cannot read $prom/api/v1/rules" >&2
  exit 1
fi
for group in dlap-availability dlap-http dlap-jvm dlap-data-plane dlap-lifecycle \
             dlap-service-recording dlap-data-plane-recording dlap-lifecycle-recording; do
  if grep -q "\"name\":\"$group\"" <<<"$rules_json"; then
    printf '  loaded  %s\n' "$group"
  else
    printf '  MISSING %s\n' "$group"
    fail=1
  fi
done
if grep -q '"health":"err"' <<<"$rules_json"; then
  echo "  FAIL: at least one rule has health err (open $prom/rules)"
  fail=1
fi

echo "== alertmanager"
if am_json="$(curl -fsS --max-time 15 "$prom/api/v1/alertmanagers")" && grep -q '"activeAlertmanagers":\[{' <<<"$am_json"; then
  echo "  connected"
else
  echo "  NOT connected"
  fail=1
fi

if [[ $fail -eq 0 ]]; then
  echo "OK: all targets up, all rule groups loaded, Alertmanager connected"
else
  echo "FAIL: see the lines marked DOWN, MISSING or NOT connected above" >&2
fi
exit "$fail"
