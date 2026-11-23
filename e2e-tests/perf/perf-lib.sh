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
# Pure helpers of the scale and soak harness (E2E-02). SOURCE this file. Nothing here starts a
# container or touches the network except perf_fetch_verified, so test-perf-lib.sh covers all of it
# offline.
#
#   perf_profile <mode>        the sizes of a run (lite, hosted or full), overridable per variable
#   perf_lag_by_group <topic>  consumer lag per group, from `kafka-consumer-groups --describe` on stdin
#   perf_skew                  partition skew, from `GetOffsetShell` lines on stdin
#   perf_rates <csv>           throughput figures from the sample file
#   perf_fetch_verified ...    download with a size and checksum check (the flaws.cloud archive)
#
# Measured numbers only: a figure is written to a results file only when a run produced it.
# ---------------------------------------------------------------------------

# Sizes of a run. D39: lite is 200k CloudTrail + 50k GCP + 50k Azure; full is the plan's 10M + 2M + 2M.
# D41 (CI-03, Q-780): hosted is what a GitHub-hosted runner can take (2 vCPU, 7.9 GB of memory, about 14 GB
# of disk; the nightly soak default): 1M CloudTrail + 250k GCP + 250k Azure, in the shape of the lite run
# whose rate was measured (4 CloudTrail accounts, 1000 records per file, 8 REST threads, 12 hours of event
# time). Expected time and size, from the lite run of 2026-11-02 (e2e-tests/perf/results/lite-2026-11-02.md):
#   rate        300,000 events in 617 s = 486.2 events/s end to end, on a 20 CPU machine
#   hosted      1,500,000 events / 486.2 = 3,085 s = 51 min at the measured rate; the 180 minute load
#               budget (PERF_TIMEOUT_MIN) holds down to 1,500,000 / 10,800 s = 139 events/s, 29 percent of
#               the measured rate, which is the room left for a 2 vCPU runner (not measured there)
#   Postgres    1,500,000 events x 1,482 bytes per event (423.9 MiB / 300,000) = 2.22 GB
#   disk        Postgres 2.22 GB, plus the Kafka log and the generated data, each bounded by the same
#               2.22 GB (not measured), plus 6.4 GB of images (the sum of the image sizes of the seven
#               images on a developer machine; shared layers make the real figure lower) = at most
#               13.1 GB of the 14 GB; the workflow also frees the preinstalled toolchains first (Q-787)
#   total       about 51 min of load, 5 to 10 min of backfill resume, up to 10 min for flaws.cloud and 20 min
#               of build and start: well inside the 4 hour (240 minute) job limit at the measured rate
# full stays the plan's 14,000,000 events: 14,000,000 / 486.2 = 28,794 s = 8.0 h (a hosted job stops at 6 h)
# and 14,000,000 x 1,482 = 20.7 GB of Postgres alone, so it needs a larger runner.
# Every variable can be set in the environment first (a developer machine may want fewer).
perf_profile() { # perf_profile lite|hosted|full
  case "$1" in
    lite) PERF_CT_COUNT="${PERF_CT_COUNT:-200000}"; PERF_GCP_COUNT="${PERF_GCP_COUNT:-50000}"
          PERF_AZURE_COUNT="${PERF_AZURE_COUNT:-50000}"; PERF_CT_PER_FILE="${PERF_CT_PER_FILE:-1000}"
          PERF_REPLAY_PER_FILE="${PERF_REPLAY_PER_FILE:-25000}"; PERF_SPAN_HOURS="${PERF_SPAN_HOURS:-12}"
          PERF_CT_ACCOUNTS="${PERF_CT_ACCOUNTS:-4}"; PERF_POST_THREADS="${PERF_POST_THREADS:-8}"
          PERF_TIMEOUT_MIN="${PERF_TIMEOUT_MIN:-60}" ;;
    hosted) PERF_CT_COUNT="${PERF_CT_COUNT:-1000000}"; PERF_GCP_COUNT="${PERF_GCP_COUNT:-250000}"
          PERF_AZURE_COUNT="${PERF_AZURE_COUNT:-250000}"; PERF_CT_PER_FILE="${PERF_CT_PER_FILE:-1000}"
          PERF_REPLAY_PER_FILE="${PERF_REPLAY_PER_FILE:-25000}"; PERF_SPAN_HOURS="${PERF_SPAN_HOURS:-12}"
          PERF_CT_ACCOUNTS="${PERF_CT_ACCOUNTS:-4}"; PERF_POST_THREADS="${PERF_POST_THREADS:-8}"
          PERF_TIMEOUT_MIN="${PERF_TIMEOUT_MIN:-180}" ;;
    full) PERF_CT_COUNT="${PERF_CT_COUNT:-10000000}"; PERF_GCP_COUNT="${PERF_GCP_COUNT:-2000000}"
          PERF_AZURE_COUNT="${PERF_AZURE_COUNT:-2000000}"; PERF_CT_PER_FILE="${PERF_CT_PER_FILE:-5000}"
          PERF_REPLAY_PER_FILE="${PERF_REPLAY_PER_FILE:-50000}"; PERF_SPAN_HOURS="${PERF_SPAN_HOURS:-20}"
          PERF_CT_ACCOUNTS="${PERF_CT_ACCOUNTS:-8}"; PERF_POST_THREADS="${PERF_POST_THREADS:-16}"
          PERF_TIMEOUT_MIN="${PERF_TIMEOUT_MIN:-270}" ;;
    *) echo "perf_profile: unknown mode '$1' (want lite, hosted or full)" >&2; return 1 ;;
  esac
  PERF_SEED="${PERF_SEED:-20261101}"
  # The backfill resume test reads a separate trail (another account) written five days back, so the
  # stream's one day initial lookback never reads it and only the backfill job does.
  PERF_BF_COUNT="${PERF_BF_COUNT:-40000}"; PERF_BF_PER_FILE="${PERF_BF_PER_FILE:-1000}"
  PERF_BF_BATCHES_PER_SEC="${PERF_BF_BATCHES_PER_SEC:-0.5}"
  PERF_SAMPLE_SECS="${PERF_SAMPLE_SECS:-15}"
  PERF_TOTAL=$((PERF_CT_COUNT + PERF_GCP_COUNT + PERF_AZURE_COUNT))
  export PERF_CT_COUNT PERF_GCP_COUNT PERF_AZURE_COUNT PERF_CT_PER_FILE PERF_REPLAY_PER_FILE PERF_SPAN_HOURS \
         PERF_TIMEOUT_MIN PERF_CT_ACCOUNTS PERF_POST_THREADS PERF_SEED PERF_BF_COUNT PERF_BF_PER_FILE PERF_BF_BATCHES_PER_SEC PERF_SAMPLE_SECS PERF_TOTAL
}

# `kafka-consumer-groups --describe --all-groups` prints, per partition:
#   GROUP TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG CONSUMER-ID HOST CLIENT-ID
# This prints "<group> <total lag of <topic>>" for every group that reads the topic. A lag of "-" (no
# committed offset yet) counts as the whole backlog being unknown, so it is skipped, not zero.
perf_lag_by_group() { # perf_lag_by_group <topic>  (stdin: the describe output)
  awk -v topic="$1" '
    $2 == topic && $3 ~ /^[0-9]+$/ && $6 ~ /^[0-9]+$/ { lag[$1] += $6; seen[$1] = 1 }
    END { for (g in seen) printf "%s %d\n", g, lag[g] }' | sort
}

# Highest lag of any group, 0 when no group reports one.
perf_lag_max() { # stdin: perf_lag_by_group output
  awk 'BEGIN { m = 0 } $2 + 0 > m { m = $2 + 0 } END { print m }'
}

# `GetOffsetShell --time -1` prints "<topic>:<partition>:<end offset>" per partition. Skew is the ratio
# of the fullest partition to the emptiest (1.00 is perfectly even), and the fullest partition's share.
perf_skew() { # stdin: GetOffsetShell output; prints key=value lines
  awk -F: '
    NF >= 3 && $2 ~ /^[0-9]+$/ && $3 ~ /^[0-9]+$/ {
      n++; v = $3 + 0; total += v
      if (n == 1 || v < min) min = v
      if (n == 1 || v > max) max = v
      parts = parts (parts == "" ? "" : ",") $2 "=" v
    }
    END {
      if (n == 0) exit 1
      printf "partitions=%d\n", n
      printf "offsets=%s\n", parts
      printf "total=%d\n", total
      printf "min=%d\n", min
      printf "max=%d\n", max
      if (min > 0) printf "ratio=%.2f\n", max / min; else printf "ratio=inf\n"
      if (total > 0) printf "maxshare=%.3f\n", max / total; else printf "maxshare=0\n"
    }'
}

# Sum of the offsets of a GetOffsetShell output (the messages ever written to the topic).
perf_topic_total() { # stdin: GetOffsetShell output
  awk -F: 'NF >= 3 && $3 ~ /^[0-9]+$/ { t += $3 } END { print t + 0 }'
}

# Throughput from the sample file. Columns: elapsed_s,stored,aws,gcp,azure,lag_max,lag_sum,pg_bytes.
# Prints key=value lines:
#   first_s        first sample with a stored event
#   done_s         first sample at the expected total (empty when never reached)
#   overall_eps    stored events / done_s: end to end, JVM start and migrations included
#   window_s       the window of the two figures below, at least 60 s
#   peak_eps       best rate over any window
#   median_eps     median rate over the windows that start past 10 percent of the expected total and end at the finish
perf_rates() { # perf_rates <csv> <expected total>
  local csv="$1" expected="$2" out rc
  out="$(awk -F, -v expected="$expected" '
    NR == 1 && $1 !~ /^[0-9]/ { next }
    $1 ~ /^[0-9]+$/ { n++; t[n] = $1 + 0; s[n] = $2 + 0 }
    END {
      if (n < 2) exit 1
      first = ""; done = ""
      for (i = 1; i <= n; i++) {
        if (first == "" && s[i] > 0) first = t[i]
        if (done == "" && s[i] >= expected) { done = t[i]; doneIdx = i }
      }
      interval = (t[n] - t[1]) / (n - 1)
      step = int(60 / interval + 0.999); if (step < 1) step = 1
      peak = 0
      last = (doneIdx > 0) ? doneIdx : n
      for (i = 1; i + step <= last; i++) {
        j = i + step
        if (s[i] < expected * 0.1 || t[j] == t[i]) continue
        r = (s[j] - s[i]) / (t[j] - t[i])
        if (r < 0) continue
        printf "rate %.3f\n", r
        if (r > peak) peak = r
      }
      printf "first_s=%s\n", first
      printf "done_s=%s\n", done
      if (done != "" && done > 0) printf "overall_eps=%.1f\n", s[doneIdx] / done
      printf "window_s=%d\n", step * interval
      printf "peak_eps=%.1f\n", peak
    }' "$csv")"
  rc=$?
  printf '%s\n' "$out" | grep -v '^rate '
  printf '%s\n' "$out" | awk '/^rate / { print $2 }' | sort -n | awk '
    { a[NR] = $1 }
    END { if (NR == 0) exit; printf "median_eps=%.1f\n", (NR % 2) ? a[(NR + 1) / 2] : (a[NR / 2] + a[NR / 2 + 1]) / 2 }'
  return $rc
}

# Largest value of a CSV column (1-based) of the sample file.
perf_col_max() { # perf_col_max <csv> <column>
  awk -F, -v c="$2" 'NR > 1 && $c ~ /^[0-9]+$/ { if ($c + 0 > m) m = $c + 0 } END { print m + 0 }' "$1"
}

# Rate in percent with two decimals: perf_pct <part> <whole>; "0.00" for an empty whole.
perf_pct() {
  awk -v p="$1" -v w="$2" 'BEGIN { if (w + 0 == 0) print "0.00"; else printf "%.2f\n", 100 * p / w }'
}

# Human bytes: perf_bytes 1536 -> "1.5 KiB".
perf_bytes() {
  awk -v b="$1" 'BEGIN {
    split("B KiB MiB GiB TiB", u, " "); i = 1; v = b + 0
    while (v >= 1024 && i < 5) { v /= 1024; i++ }
    if (i == 1) printf "%d B\n", v; else printf "%.1f %s\n", v, u[i] }'
}

# Download a file and refuse it unless its size and SHA-256 match the pins. The checksum pin may be the
# word UNPINNED (nobody has downloaded the file yet): that fails unless allow_unpinned is 1, and then
# the computed checksum is printed so it can be pinned. Prints "sha256=<computed>" on success.
perf_fetch_verified() { # perf_fetch_verified <url> <dest> <want size> <want sha256|UNPINNED> <allow unpinned 0|1>
  local url="$1" dest="$2" want_size="$3" want_sha="$4" allow="${5:-0}" got_size got_sha
  case "$url" in
    https://*|file://*) ;;
    *) echo "perf_fetch_verified: only https:// and file:// URLs are fetched (got '$url')" >&2; return 2 ;;
  esac
  if [[ "$want_sha" == "UNPINNED" && "$allow" != "1" ]]; then
    echo "perf_fetch_verified: the checksum of $url is not pinned; set DLAP_FLAWS_ALLOW_UNPINNED=1 for the first run and pin the printed value" >&2
    return 3
  fi
  if [[ "$url" == file://* ]]; then
    cp -- "${url#file://}" "$dest" || return 4
  else
    curl -fsSL --retry 3 --retry-delay 5 --max-time 1800 -o "$dest" -- "$url" || { echo "perf_fetch_verified: download failed" >&2; return 4; }
  fi
  got_size="$(wc -c < "$dest" | tr -d '[:space:]')"
  if [[ "$want_size" != "any" && "$got_size" != "$want_size" ]]; then
    echo "perf_fetch_verified: size $got_size, pinned size $want_size" >&2; rm -f "$dest"; return 5
  fi
  got_sha="$(sha256sum "$dest" | cut -d' ' -f1)"
  if [[ "$want_sha" != "UNPINNED" && "$got_sha" != "$want_sha" ]]; then
    echo "perf_fetch_verified: sha256 $got_sha, pinned $want_sha" >&2; rm -f "$dest"; return 6
  fi
  [[ "$want_sha" == "UNPINNED" ]] && echo "perf_fetch_verified: WARNING checksum not pinned; computed value follows" >&2
  echo "sha256=$got_sha"
}

# A figure for a results file: the value, or the words "not measured" when the run produced none.
perf_fig() { # perf_fig <value> [unit]
  if [[ -z "${1:-}" ]]; then echo "not measured"; else echo "$1${2:+ $2}"; fi
}
