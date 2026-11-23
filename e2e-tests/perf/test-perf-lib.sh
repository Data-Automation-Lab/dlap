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
# Offline self-test of perf-lib.sh and of run.sh's argument handling (E2E-02). No Docker, no network.
#
#   bash e2e-tests/perf/test-perf-lib.sh
# ---------------------------------------------------------------------------
set -uo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=perf-lib.sh
source "$here/perf-lib.sh"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
passed=0; failed=0
ok()  { passed=$((passed + 1)); echo "  ok    $*"; }
bad() { failed=$((failed + 1)); echo "  FAIL  $*" >&2; }
eq()  { if [[ "$2" == "$3" ]]; then ok "$1"; else bad "$1 (got '$2', wanted '$3')"; fi; }

echo "profiles"
( perf_profile lite; echo "$PERF_CT_COUNT $PERF_GCP_COUNT $PERF_AZURE_COUNT $PERF_TOTAL" ) >"$tmp/o"
eq "lite is 200k CloudTrail, 50k GCP, 50k Azure" "$(cat "$tmp/o")" "200000 50000 50000 300000"
( perf_profile hosted; echo "$PERF_CT_COUNT $PERF_GCP_COUNT $PERF_AZURE_COUNT $PERF_TOTAL" ) >"$tmp/o"
eq "hosted is 1M CloudTrail, 250k GCP, 250k Azure" "$(cat "$tmp/o")" "1000000 250000 250000 1500000"
( perf_profile hosted; echo "$PERF_CT_ACCOUNTS $PERF_POST_THREADS $PERF_CT_PER_FILE $PERF_SPAN_HOURS" ) >"$tmp/o"
eq "hosted keeps the shape of the measured lite run (4 accounts, 8 threads, 1000 per file, 12 hours)" "$(cat "$tmp/o")" "4 8 1000 12"
( perf_profile hosted; echo $(( PERF_CT_COUNT % PERF_CT_ACCOUNTS )) ) >"$tmp/o"
eq "hosted CloudTrail records divide evenly over the accounts" "$(cat "$tmp/o")" "0"
# D41 / Q-780: the default load must fit a 4 hour job at 29 percent of the measured 486.2 events/s (139/s),
# and the load budget must leave room for build, backfill and flaws.cloud inside the 240 minute job limit.
( perf_profile hosted; awk -v n="$PERF_TOTAL" -v m="$PERF_TIMEOUT_MIN" 'BEGIN { printf "%d %d\n", n / (m * 60), m <= 180 }' ) >"$tmp/o"
eq "the hosted load budget holds down to 138 events/s and is at most 180 minutes" "$(cat "$tmp/o")" "138 1"
( perf_profile hosted; awk -v n="$PERF_TOTAL" 'BEGIN { printf "%.2f\n", n * 1482 / 1e9 }' ) >"$tmp/o"
eq "hosted stores about 2.22 GB at 1482 bytes per event" "$(cat "$tmp/o")" "2.22"
( PERF_CT_COUNT=4000 perf_profile hosted; echo "$PERF_TOTAL" ) >"$tmp/o"
eq "hosted counts can still be overridden" "$(cat "$tmp/o")" "504000"
( perf_profile full; echo "$PERF_CT_COUNT $PERF_GCP_COUNT $PERF_AZURE_COUNT $PERF_TOTAL" ) >"$tmp/o"
eq "full is 10M CloudTrail, 2M GCP, 2M Azure" "$(cat "$tmp/o")" "10000000 2000000 2000000 14000000"
( PERF_CT_COUNT=1000 perf_profile lite; echo "$PERF_TOTAL" ) >"$tmp/o"
eq "a variable set first wins" "$(cat "$tmp/o")" "101000"
( perf_profile lite; echo "$PERF_CT_ACCOUNTS $PERF_POST_THREADS" ) >"$tmp/o"
eq "lite reads four CloudTrail accounts and posts with eight threads" "$(cat "$tmp/o")" "4 8"
( perf_profile full; echo "$PERF_CT_ACCOUNTS $PERF_POST_THREADS" ) >"$tmp/o"
eq "full reads eight CloudTrail accounts and posts with sixteen threads" "$(cat "$tmp/o")" "8 16"
perf_profile bogus >/dev/null 2>&1; eq "an unknown mode is refused" "$?" "1"

echo "kafka output parsing"
cat >"$tmp/describe" <<'EOF'

GROUP           TOPIC                    PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG    CONSUMER-ID  HOST  CLIENT-ID
dlap-discovery  dlap.log.event.normalized 0          900             1000            100    c1           /h    x
dlap-discovery  dlap.log.event.normalized 1          800             800             0      c1           /h    x
dlap-discovery  dlap.log.event.normalized 2          10              40              30     c1           /h    x
dlap-catalog    dlap.log.event.normalized 0          1000            1000            0      c2           /h    x
dlap-catalog    other.topic               0          5               9               4      c2           /h    x
dlap-fresh      dlap.log.event.normalized 0          -               1000            -      -            -     -
EOF
perf_lag_by_group dlap.log.event.normalized <"$tmp/describe" >"$tmp/lag"
eq "lag is summed per group for the topic only" "$(tr '\n' ';' <"$tmp/lag")" "dlap-catalog 0;dlap-discovery 130;"
eq "the worst group's lag" "$(perf_lag_max <"$tmp/lag")" "130"
eq "no groups means a lag of 0" "$(printf '' | perf_lag_by_group t | perf_lag_max)" "0"

printf 'dlap.log.event.normalized:0:100000\ndlap.log.event.normalized:1:98000\ndlap.log.event.normalized:2:102000\n' >"$tmp/offsets"
perf_skew <"$tmp/offsets" >"$tmp/skew"
eq "skew counts the partitions" "$(grep '^partitions=' "$tmp/skew")" "partitions=3"
eq "skew total" "$(grep '^total=' "$tmp/skew")" "total=300000"
eq "skew ratio is fullest over emptiest" "$(grep '^ratio=' "$tmp/skew")" "ratio=1.04"
eq "skew share of the fullest" "$(grep '^maxshare=' "$tmp/skew")" "maxshare=0.340"
eq "topic total" "$(perf_topic_total <"$tmp/offsets")" "300000"
printf 'a:0:5\na:1:0\n' | perf_skew >"$tmp/skew2"; eq "an empty partition gives an infinite ratio" "$(grep '^ratio=' "$tmp/skew2")" "ratio=inf"
printf 'garbage\n' | perf_skew >/dev/null 2>&1; eq "no partitions is an error" "$?" "1"

echo "throughput"
cat >"$tmp/samples.csv" <<'EOF'
elapsed_s,stored,aws,gcp,azure,lag_max,lag_sum,pg_bytes
0,0,0,0,0,0,0,100
15,0,0,0,0,0,0,100
30,3000,2000,500,500,0,0,200
45,9000,6000,1500,1500,50,50,300
60,15000,10000,2500,2500,10,10,400
75,21000,14000,3500,3500,0,0,500
90,27000,18000,4500,4500,0,0,600
105,30000,20000,5000,5000,0,0,700
120,30000,20000,5000,5000,0,0,700
EOF
perf_rates "$tmp/samples.csv" 30000 >"$tmp/rates"
eq "first sample with events" "$(grep '^first_s=' "$tmp/rates")" "first_s=30"
eq "first sample at the expected total" "$(grep '^done_s=' "$tmp/rates")" "done_s=105"
eq "end to end rate" "$(grep '^overall_eps=' "$tmp/rates")" "overall_eps=285.7"
eq "window is 60 s" "$(grep '^window_s=' "$tmp/rates")" "window_s=60"
eq "peak rate over a window" "$(grep '^peak_eps=' "$tmp/rates")" "peak_eps=400.0"
eq "median rate over the windows" "$(grep '^median_eps=' "$tmp/rates")" "median_eps=375.0"
eq "largest lag column" "$(perf_col_max "$tmp/samples.csv" 6)" "50"
eq "largest size column" "$(perf_col_max "$tmp/samples.csv" 8)" "700"
printf 'elapsed_s,stored\n0,0\n15,10\n' >"$tmp/short.csv"
perf_rates "$tmp/short.csv" 100 >"$tmp/r2"; eq "never reaching the total leaves done_s empty" "$(grep '^done_s=' "$tmp/r2")" "done_s="
if grep -q '^overall_eps=' "$tmp/r2"; then bad "no end to end rate without a finish"; else ok "no end to end rate without a finish"; fi

echo "formatting"
eq "percent" "$(perf_pct 3 400)" "0.75"
eq "percent of nothing" "$(perf_pct 0 0)" "0.00"
eq "bytes" "$(perf_bytes 1536)" "1.5 KiB"
eq "small bytes" "$(perf_bytes 12)" "12 B"
eq "a missing figure is not a number" "$(perf_fig '' 'ev/s')" "not measured"
eq "a figure with a unit" "$(perf_fig 12 'ev/s')" "12 ev/s"

echo "verified download"
printf 'archive bytes' >"$tmp/src.bin"
sha="$(sha256sum "$tmp/src.bin" | cut -d' ' -f1)"; size="$(wc -c <"$tmp/src.bin" | tr -d '[:space:]')"
eq "a matching file is accepted" "$(perf_fetch_verified "file://$tmp/src.bin" "$tmp/d1" "$size" "$sha" 0 2>/dev/null)" "sha256=$sha"
perf_fetch_verified "file://$tmp/src.bin" "$tmp/d2" "$size" "$(printf '0%.0s' {1..64})" 0 >/dev/null 2>&1
eq "a wrong checksum is refused" "$?" "6"
[[ ! -e "$tmp/d2" ]] && ok "and the file is removed" || bad "and the file is removed"
perf_fetch_verified "file://$tmp/src.bin" "$tmp/d3" 999 "$sha" 0 >/dev/null 2>&1; eq "a wrong size is refused" "$?" "5"
perf_fetch_verified "file://$tmp/src.bin" "$tmp/d4" "$size" UNPINNED 0 >/dev/null 2>&1; eq "an unpinned checksum is refused by default" "$?" "3"
eq "an unpinned checksum is accepted when allowed and printed" "$(perf_fetch_verified "file://$tmp/src.bin" "$tmp/d5" "$size" UNPINNED 1 2>/dev/null)" "sha256=$sha"
perf_fetch_verified "http://example.invalid/x" "$tmp/d6" any "$sha" 0 >/dev/null 2>&1; eq "plain http is refused" "$?" "2"
perf_fetch_verified "file://$tmp/missing" "$tmp/d7" any "$sha" 0 >/dev/null 2>&1; eq "a missing file fails" "$?" "4"

echo "run.sh arguments"
bash "$here/run.sh" >/dev/null 2>&1; eq "no mode is a usage error" "$?" "2"
bash "$here/run.sh" bogus >/dev/null 2>&1; eq "an unknown mode is a usage error" "$?" "2"
bash "$here/run.sh" --help >/dev/null 2>&1; eq "--help succeeds" "$?" "0"

echo
echo "perf-lib self-test: $passed passed, $failed failed"
[[ "$failed" -eq 0 ]]
