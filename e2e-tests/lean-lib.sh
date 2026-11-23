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
# Memory measurement for the lifecycle harness in CI lean mode (CI-03, Q-787, decision D41).
# SOURCE this file from cloud-lifecycle-e2e.sh; the caller provides say, warn and fail, the LEAN
# flag (1 in lean mode) and COMPOSE_PROJECT_NAME. Nothing here needs Docker except the sampler
# itself, so test-cloud-lifecycle-e2e.sh covers the parsing and the peak arithmetic offline.
#
#   mem_to_mib <figure>            "412.3MiB", "1.2GiB", "980KiB", "512B" (kB, MB, GB too) to MiB
#   mem_sample_lines <epoch>       stdin "<name> <used> / <limit>" to "<epoch> <name> <MiB>"
#   mem_peak_report                stdin sample lines (and "# <epoch> <label>" marks) to key=value
#   mem_mark <label>               start a new phase in the sample log
#   mem_sampler_start              sample `docker stats` of the compose project in the background
#   mem_sampler_report             print the peak; return 1 when lean mode went over the budget
#
# Knobs: DLAP_E2E_MEM_SAMPLE_SECS (default 5), DLAP_E2E_MEM_BUDGET_MIB (default 6000, enforced only
# in lean mode). The unit is MiB, which is what `free -m` and `docker stats` print.
# ---------------------------------------------------------------------------

mem_to_mib() {
  awk -v v="$1" 'BEGIN {
    n = v + 0; u = v; sub(/^[0-9.]+/, "", u)
    f["B"] = 1 / 1048576; f["KiB"] = 1 / 1024; f["kB"] = 1 / 1024; f["KB"] = 1 / 1024
    f["MiB"] = 1; f["MB"] = 1; f["GiB"] = 1024; f["GB"] = 1024
    if (!(u in f)) { print "NaN"; exit 1 }
    printf "%.1f\n", n * f[u] }'
}

mem_sample_lines() { # mem_sample_lines <epoch>
  local epoch="$1" name used rest mib
  while read -r name used rest; do
    [[ -n "$name" && "$used" =~ ^[0-9] ]] || continue
    mib=$(mem_to_mib "$used") || continue
    printf '%s %s %s\n' "$epoch" "$name" "$mib"
  done
}

# Output: samples=N, peak_mib=M, peak_epoch=E, "container <name> <peak MiB>", "phase <label> <peak MiB>".
# A sample is one epoch second; the total of an epoch is the sum of its containers.
mem_peak_report() {
  awk '
    $1 == "#" { phase = $3; next }
    NF == 3 && $1 ~ /^[0-9]+$/ && $3 ~ /^[0-9.]+$/ {
      tot[$1] += $3; ph[$1] = phase
      if ($3 + 0 > cmax[$2] + 0) cmax[$2] = $3 + 0
    }
    END {
      for (e in tot) {
        n++
        if (tot[e] > peak) { peak = tot[e]; pe = e }
        if (tot[e] > pmax[ph[e]]) pmax[ph[e]] = tot[e]
      }
      if (n == 0) { print "samples=0"; exit 1 }
      printf "samples=%d\npeak_mib=%.0f\npeak_epoch=%s\n", n, peak, pe
      for (c in cmax) printf "container %s %.0f\n", c, cmax[c]
      for (p in pmax) printf "phase %s %.0f\n", (p == "" ? "start" : p), pmax[p]
    }'
}

MEM_LOG=""; MEM_SAMPLER_PID=""

mem_mark() { # mem_mark <label>
  [[ -n "$MEM_LOG" ]] && echo "# $(date +%s) $1" >> "$MEM_LOG"
  return 0
}

# Samples this compose project's containers until the calling script ends (the loop watches its PID).
mem_sampler_start() {
  local every="${DLAP_E2E_MEM_SAMPLE_SECS:-5}" parent=$$
  [[ "$every" =~ ^[1-9][0-9]*$ ]] || fail "DLAP_E2E_MEM_SAMPLE_SECS must be a whole number of seconds"
  MEM_LOG="$(mktemp "${TMPDIR:-/tmp}/dlap-e2e-mem.XXXXXX")"
  mem_mark start
  # fd 9 belongs to the harness lock: the sampler must not keep it open after the run ends.
  (
    while kill -0 "$parent" 2>/dev/null; do
      ids=$(docker ps -q --filter "label=com.docker.compose.project=${COMPOSE_PROJECT_NAME}" 2>/dev/null | tr -d '\r')
      if [[ -n "$ids" ]]; then
        # shellcheck disable=SC2086  # one argument per container id
        docker stats --no-stream --format '{{.Name}} {{.MemUsage}}' $ids 2>/dev/null | tr -d '\r' \
          | mem_sample_lines "$(date +%s)" >> "$MEM_LOG"
      fi
      sleep "$every"
    done
  ) 9>&- >/dev/null 2>&1 &
  MEM_SAMPLER_PID=$!
  say "memory sampler on (every ${every}s, log $MEM_LOG)"
}

mem_sampler_report() {
  local budget="${DLAP_E2E_MEM_BUDGET_MIB:-6000}" out peak t0 pe rc=0
  [[ -n "$MEM_SAMPLER_PID" ]] && kill "$MEM_SAMPLER_PID" 2>/dev/null
  # Lean mode enforces the memory budget, so a run without a single sample must fail: a missing
  # measurement is not a pass. Other runs (DLAP_E2E_MEM_SAMPLE=1) only warn.
  if [[ ! -s "$MEM_LOG" ]]; then
    if (( ${LEAN:-0} )); then echo "[e2e] FAIL: lean mode took no memory sample" >&2; return 1; fi
    return 0
  fi
  out=$(mem_peak_report < "$MEM_LOG") || {
    if (( ${LEAN:-0} )); then echo "[e2e] FAIL: lean mode took no memory sample" >&2; return 1; fi
    warn "the memory sampler took no sample"; return 0; }
  peak=$(sed -n 's/^peak_mib=//p' <<<"$out"); pe=$(sed -n 's/^peak_epoch=//p' <<<"$out")
  t0=$(awk '$1 == "#" && $3 == "start" { print $2; exit }' "$MEM_LOG")
  echo
  echo "============== peak total container memory =============="
  printf '  peak %s MiB (budget %s MiB) at +%ss, %s samples\n' "$peak" "$budget" "$(( pe - ${t0:-0} ))" "$(sed -n 's/^samples=//p' <<<"$out")"
  grep '^phase ' <<<"$out" | awk '{ printf "  phase %-14s peak %s MiB\n", $2, $3 }'
  grep '^container ' <<<"$out" | sort -k3 -n -r | awk '{ printf "  %-28s peak %s MiB\n", $2, $3 }'
  echo "========================================================="
  if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
    { echo "### Peak total container memory of the lifecycle run: ${peak} MiB (budget ${budget} MiB)"
      echo '```'; grep -E '^(phase|container) ' <<<"$out"; echo '```'; } >> "$GITHUB_STEP_SUMMARY"
  fi
  if (( ${LEAN:-0} )) && (( peak > budget )); then
    echo "[e2e] FAIL: peak total container memory ${peak} MiB is over the lean budget ${budget} MiB" >&2; rc=1
  fi
  return "$rc"
}
