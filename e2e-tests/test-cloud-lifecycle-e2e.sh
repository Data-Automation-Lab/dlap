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
# Self-test for the CI lean mode of cloud-lifecycle-e2e.sh (CI-03, Q-787, Q-765). No stack is started.
#
#   bash e2e-tests/test-cloud-lifecycle-e2e.sh
#
# Covers: the memory figure parsing and the peak arithmetic of lean-lib.sh; the lean-mode budget;
# clone_record (Q-765: the copy must exist, its status is not checked); the list of services stopped
# before the retire phase against the services the phase calls; the number of gates (15) and the self-approval verdict (Q-592); and, when
# a Docker CLI with compose is installed, the merged lean compose files (every container capped, every
# Java service with an -Xmx below its mem_limit, no collector flag that would stop the JVM).
# Exit 0 when every case passed or was skipped with a printed reason, 1 otherwise.
# ---------------------------------------------------------------------------
# shellcheck disable=SC1090,SC1091,SC2034  # the library paths are variables; the library reads LEAN and the budget
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
SCRIPT="$HERE/cloud-lifecycle-e2e.sh"
DEV="$REPO/deployment/environments/dev"

PASS=0; FAIL=0; SKIP=0
t_ok()   { PASS=$((PASS + 1)); echo "  ok    $1"; }
t_fail() { FAIL=$((FAIL + 1)); echo "  FAIL  $1${2:+ : $2}"; }
t_skip() { SKIP=$((SKIP + 1)); echo "  skip  $1${2:+ : $2}"; }
check()  { if [[ "$2" == "$3" ]]; then t_ok "$1"; else t_fail "$1" "expected '$2', got '$3'"; fi; }
section() { echo; echo "== $1"; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# The library wants these from its caller.
say() { :; }; warn() { :; }; fail() { echo "fail: $*" >&2; return 1; }
LEAN=0
source "$HERE/lean-lib.sh"

# ---------------------------------------------------------------------------
section "memory figures"
# ---------------------------------------------------------------------------
check "MiB stays MiB" "412.3" "$(mem_to_mib 412.3MiB)"
check "GiB to MiB" "1228.8" "$(mem_to_mib 1.2GiB)"
check "KiB to MiB" "1.0" "$(mem_to_mib 1024KiB)"
check "B to MiB" "0.5" "$(mem_to_mib 524288B)"
check "kB spelling" "1.0" "$(mem_to_mib 1024kB)"
out="$(mem_to_mib 12parsecs 2>/dev/null)"; rc=$?
if [[ $rc -ne 0 && "$out" == "NaN" ]]; then t_ok "an unknown unit is refused"; else t_fail "an unknown unit" "rc=$rc out=$out"; fi

lines="$(printf '%s\n' 'dlap-kafka 411.2MiB / 640MiB' 'dlap-postgresql 1.5GiB / 2GiB' 'garbage' 'dlap-x -- / --' '' | mem_sample_lines 100)"
check "sample lines keep good rows only" $'100 dlap-kafka 411.2\n100 dlap-postgresql 1536.0' "$lines"

# ---------------------------------------------------------------------------
section "peak arithmetic"
# ---------------------------------------------------------------------------
cat > "$TMP/samples" <<'EOS'
# 1000 start
1005 a 100.0
1005 b 200.0
1010 a 150.0
1010 b 250.0
# 1012 retire
1015 a 120.0
1015 b 90.0
1015 c 60.0
EOS
rep="$(mem_peak_report < "$TMP/samples")"
check "peak total is the largest sum of one sample" "400" "$(sed -n 's/^peak_mib=//p' <<<"$rep")"
check "the peak sample is the one at 1010" "1010" "$(sed -n 's/^peak_epoch=//p' <<<"$rep")"
check "three samples" "3" "$(sed -n 's/^samples=//p' <<<"$rep")"
check "per container peak" "150" "$(awk '$1 == "container" && $2 == "a" { print $3 }' <<<"$rep")"
check "peak of the start phase" "400" "$(awk '$1 == "phase" && $2 == "start" { print $3 }' <<<"$rep")"
check "peak of the retire phase" "270" "$(awk '$1 == "phase" && $2 == "retire" { print $3 }' <<<"$rep")"
if : | mem_peak_report >/dev/null 2>&1; then t_fail "no samples must fail"; else t_ok "no samples is a failure, not a zero peak"; fi

# ---------------------------------------------------------------------------
section "lean budget"
# ---------------------------------------------------------------------------
budget_case() { # budget_case <LEAN> <budget> <peak line total>
  MEM_LOG="$TMP/budget-log"; MEM_SAMPLER_PID=""
  printf '# 1000 start\n1005 a %s\n' "$3" > "$MEM_LOG"
  ( LEAN="$1"; DLAP_E2E_MEM_BUDGET_MIB="$2"; mem_sampler_report >"$TMP/budget-out" 2>&1 ); echo $?
}
check "lean mode over budget fails" "1" "$(budget_case 1 6000 6001.0)"
check "lean mode at budget passes" "0" "$(budget_case 1 6000 6000.0)"
check "lean mode under budget passes" "0" "$(budget_case 1 6000 4200.5)"
check "a run without lean mode never fails on memory" "0" "$(budget_case 0 6000 9000.0)"
# A log with only the start mark holds no sample: lean mode must fail, other runs only warn.
nosample_case() { # nosample_case <LEAN>
  MEM_LOG="$TMP/budget-log"; MEM_SAMPLER_PID=""
  printf '# 1000 start
' > "$MEM_LOG"
  ( LEAN="$1"; DLAP_E2E_MEM_BUDGET_MIB=6000; mem_sampler_report >"$TMP/budget-out" 2>&1 ); echo $?
}
check "lean mode with no sample fails" "1" "$(nosample_case 1)"
if grep -q 'FAIL: lean mode took no memory sample' "$TMP/budget-out"; then t_ok "the no-sample failure is named"; else t_fail "no-sample text" "$(cat "$TMP/budget-out")"; fi
check "a run without lean mode and no sample passes" "0" "$(nosample_case 0)"
MEM_LOG="$TMP/missing-log"; rm -f "$MEM_LOG"
check "lean mode with no log at all fails" "1" "$( ( LEAN=1; mem_sampler_report >/dev/null 2>&1 ); echo $? )"
budget_case 1 6000 5000.0 >/dev/null
if grep -q 'peak 5000 MiB (budget 6000 MiB)' "$TMP/budget-out"; then t_ok "the report prints the peak and the budget"; else t_fail "report text" "$(cat "$TMP/budget-out")"; fi

# ---------------------------------------------------------------------------
section "clone_record (Q-765)"
# ---------------------------------------------------------------------------
# Take the function out of the script and run it against stand-ins for rpq and rec.
eval "$(awk '/^clone_record\(\)\{/ { p = 1 } p { print } p && /"\$nid"; \}[[:space:]]*$/ { exit }' "$SCRIPT")"
if declare -F clone_record >/dev/null; then t_ok "clone_record is defined in the script"; else t_fail "clone_record not found in $SCRIPT"; fi
NEW_ID="11111111-2222-3333-4444-555555555555"
rpq() { case "$1" in *gen_random_uuid*) printf '%s' "$NEW_ID" ;; *) : ;; esac; }
rec() { # rec <id> <column>
  [[ "${STUB_ROW:-1}" == 1 && "$1" == "$NEW_ID" ]] || return 0
  case "$2" in id) printf '%s' "$NEW_ID" ;; status) printf '%s' "${STUB_STATUS:-SOFT_DELETED}" ;; esac; }
check "a copy still SOFT_DELETED is returned" "$NEW_ID" "$(STUB_STATUS=SOFT_DELETED clone_record src bucket asset)"
check "a copy the sweeper already moved to HARD_DELETE_PENDING_APPROVAL is returned" "$NEW_ID" \
  "$(STUB_STATUS=HARD_DELETE_PENDING_APPROVAL clone_record src bucket asset)"
check "a copy the sweeper already refused is returned" "$NEW_ID" "$(STUB_STATUS=HARD_DELETE_REFUSED clone_record src bucket asset)"
out="$(STUB_ROW=0 clone_record src bucket asset)"; rc=$?
if [[ -z "$out" && $rc -ne 0 ]]; then t_ok "a copy that was never inserted gives no id and a failure"; else t_fail "missing row" "rc=$rc out=$out"; fi
if declare -f clone_record | grep -q 'rec "\$nid" status'; then
  t_fail "clone_record must not compare the status"; else t_ok "clone_record no longer compares the status"; fi
unset -f rpq rec clone_record

# ---------------------------------------------------------------------------
section "services stopped before the retire phase"
# ---------------------------------------------------------------------------
mapfile -t STOPS < <(bash -c 'src=$(awk "/^LEAN_STOP_SERVICES=/" "$1"); eval "$src"; printf "%s\n" "${LEAN_STOP_SERVICES[@]}"' _ "$SCRIPT")
# What the retire phase uses (read from the code over HTTP and Kafka, CI-03): the approvals REST API of
# da-policyengine, the asset facts of da-catalog (CatalogFactsClient), the executors, the trail-file reader
# and da-discovery, which turns the lifecycle delete in the log into the delete proof.
KEEP=(postgresql kafka keycloak da-policyengine da-catalog da-autodelete da-autoarchival da-logingest da-discovery)
for k in "${KEEP[@]}"; do
  if printf '%s\n' "${STOPS[@]}" | grep -qx "$k"; then t_fail "$k is called by the retire phase and must keep running"; else t_ok "$k keeps running"; fi
done
STACK_ALL="$(bash -c 'eval "$(sed -n "/^INFRA=(/,/^STACK=(/p" "$1")"; echo "${STACK[*]}"' _ "$SCRIPT")"
for st in "${STOPS[@]}"; do
  if [[ " $STACK_ALL " == *" $st "* ]]; then t_ok "$st is a service of the stack"; else t_fail "$st is not in INFRA or SERVICES"; fi
done
check "four services and ollama are stopped" "5" "${#STOPS[@]}"

# ---------------------------------------------------------------------------
section "gates"
# ---------------------------------------------------------------------------
retire_line="$(grep -E '^  RETIRE_GATES=\(' "$SCRIPT")"
n_retire="$(sed -E 's/.*\(([^)]*)\).*/\1/' <<<"$retire_line" | wc -w | tr -d ' ')"
n_base="$(grep -E '^EMPTY=\(\); for g in ' "$SCRIPT" | sed -E 's/.*for g in ([^"]*)"\$\{RETIRE_GATES.*/\1/' | wc -w | tr -d ' ')"
check "six retire gates" "6" "$n_retire"
check "nine stage gates" "9" "$n_base"
check "15 gates in all" "15" "$((n_retire + n_base))"
check "the self_approval gate is in the stage list" "1" "$(grep -E '^EMPTY=\(\); for g in ' "$SCRIPT" | grep -c ' held self_approval approval ')"
check "the self_approval gate is in the tally" "1" "$(grep -E "^for s in label " "$SCRIPT" | grep -c ' held self_approval approval ')"

# ---------------------------------------------------------------------------
section "self-approval verdict (Q-592)"
# ---------------------------------------------------------------------------
# The verdict function and the JSON reader it uses, taken from the script itself.
eval "$(sed -n '/^json_str(){/,/^$/p' "$SCRIPT")"
eval "$(sed -n '/^self_approval_verdict(){/,/}$/p' "$SCRIPT")"
refusal='{"timestamp":"2026-11-03T10:00:00Z","status":403,"error":"Forbidden","message":"the requester or submitter of a request cannot decide it","reason":"self-approval","path":"/api/v1/policyengine/approvals/x/decisions"}'
check "403 self-approval with the request still PENDING passes" "1" "$(self_approval_verdict 403 "$refusal" PENDING)"
check "403 self-approval with whitespace in the JSON passes" "1" "$(self_approval_verdict 403 '{ "reason" : "self-approval" }' PENDING)"
check "HTTP 200 (the admin decided their own request) fails" "0" "$(self_approval_verdict 200 '{"status":"APPROVED"}' APPROVED)"
check "403 for another reason fails" "0" "$(self_approval_verdict 403 '{"status":403,"reason":"not-an-approver"}' PENDING)"
check "403 mfa-required is not the self-approval refusal" "0" "$(self_approval_verdict 403 '{"status":403,"reason":"mfa-required"}' PENDING)"
check "a plain 403 with no reason fails" "0" "$(self_approval_verdict 403 '{"status":403,"error":"Forbidden"}' PENDING)"
check "409 (not pending) fails" "0" "$(self_approval_verdict 409 '{"status":409,"reason":"not-pending"}' APPROVED)"
check "403 self-approval but the request moved on fails" "0" "$(self_approval_verdict 403 "$refusal" APPROVED)"
check "no response at all fails" "0" "$(self_approval_verdict "" "" "")"

# ---------------------------------------------------------------------------
section "lean compose files"
# ---------------------------------------------------------------------------
if ! command -v docker >/dev/null 2>&1 || ! docker compose version >/dev/null 2>&1; then
  t_skip "merged lean compose" "no docker compose CLI"
elif ! command -v python3 >/dev/null 2>&1 && ! command -v python >/dev/null 2>&1; then
  t_skip "merged lean compose" "no python"
else
  PY="$(command -v python3 || command -v python)"
  export DLAP_E2E_FIXTURES_DIR=/x DLAP_E2E_AWS_FIXTURES_DIR=/y DLAP_E2E_RULE_MAX_AGE=PT4M
  if docker compose -f "$DEV/docker-compose.yml" -f "$DEV/docker-compose.microservices.yml" -f "$HERE/docker-compose.e2e.yml" \
       -f "$HERE/docker-compose.lifecycle-aws.yml" -f "$HERE/docker-compose.ci-lean.yml" -f "$HERE/docker-compose.ci-lean-localstack.yml" \
       config --format json > "$TMP/lean.json" 2> "$TMP/lean.err"; then
    t_ok "the merged lean configuration is valid"
    "$PY" -I - "$TMP/lean.json" > "$TMP/lean.report" <<'PYEOF'
import json, re, sys
cfg = json.load(open(sys.argv[1], encoding="utf-8"))["services"]
caps = ["postgresql", "kafka", "keycloak", "ollama", "e2e-localstack"]
java = ["da-logingest", "da-discovery", "da-catalog", "da-policyengine", "da-autolabel", "da-autoarchival",
        "da-autodelete", "da-autocompliance", "da-reporting", "da-admin-service"]
for name in caps + java:
    svc = cfg.get(name)
    if svc is None:
        print("FAIL %s is missing" % name); continue
    mem, swap = int(svc.get("mem_limit", 0)), int(svc.get("memswap_limit", 0))
    print(("ok" if mem > 0 else "FAIL") + " %s has mem_limit %d MiB" % (name, mem // 1048576))
    print(("ok" if swap == mem and mem > 0 else "FAIL") + " %s memswap_limit equals mem_limit" % name)
for name in java:
    env = cfg[name].get("environment", {})
    opts = env.get("JAVA_TOOL_OPTIONS", "")
    m = re.search(r"-Xmx(\d+)m", opts)
    mem = int(cfg[name].get("mem_limit", 0)) // 1048576
    print(("ok" if m else "FAIL") + " %s sets -Xmx" % name)
    if m:
        print(("ok" if int(m.group(1)) + 128 <= mem else "FAIL") + " %s leaves 128 MiB or more above the heap (Xmx %s, limit %d)" % (name, m.group(1), mem))
    # A collector flag here would clash with -XX:+UseG1GC of the Dockerfile and stop the JVM.
    print(("FAIL" if re.search(r"-XX:\+Use\w+GC", opts) else "ok") + " %s names no collector" % name)
    print(("ok" if "-XX:+ExitOnOutOfMemoryError" in opts else "FAIL") + " %s exits on an out-of-memory error" % name)
for name, key in (("kafka", "KAFKA_HEAP_OPTS"), ("keycloak", "JAVA_OPTS_KC_HEAP")):
    print(("ok" if "-Xmx" in cfg[name].get("environment", {}).get(key, "") else "FAIL") + " %s sets %s" % (name, key))
cmd = " ".join(cfg["postgresql"].get("command", []))
print(("ok" if "shared_buffers=128MB" in cmd else "FAIL") + " postgresql shared_buffers is 128MB")
PYEOF
    while IFS= read -r l; do
      case "$l" in ok\ *) t_ok "${l#ok }" ;; FAIL\ *) t_fail "${l#FAIL }" ;; esac
    done < "$TMP/lean.report"
    # The always-on set of the retire phase plus LocalStack must fit the budget by its limits alone.
    sum="$("$PY" -I - "$TMP/lean.json" <<'PYEOF'
import json, sys
cfg = json.load(open(sys.argv[1], encoding="utf-8"))["services"]
keep = ["postgresql", "kafka", "keycloak", "da-policyengine", "da-catalog", "da-autodelete", "da-autoarchival", "da-logingest", "da-discovery", "e2e-localstack"]
print(sum(int(cfg[k]["mem_limit"]) for k in keep) // 1048576)
PYEOF
)"
    if (( sum <= 6000 )); then t_ok "the retire phase limits add up to $sum MiB (at most 6000)"; else t_fail "retire phase limits" "$sum MiB over 6000"; fi
  else
    t_fail "the merged lean configuration is valid" "$(head -c 400 "$TMP/lean.err")"
  fi
fi

echo
echo "passed=$PASS failed=$FAIL skipped=$SKIP"
(( FAIL == 0 ))
