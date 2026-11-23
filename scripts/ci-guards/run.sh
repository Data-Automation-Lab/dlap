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
# Runs every guard hook of one phase, in lexical order, from the repo root.
#
#   bash scripts/ci-guards/run.sh pre         # before any build (compliance job)
#   bash scripts/ci-guards/run.sh post-test   # after the module tests
#
# Hook contract (details in scripts/ci-guards/README.md):
#   exit 0   pass
#   exit 77  skip (a prerequisite is absent, for example the new-ui checkout)
#   other    fail
# Every hook runs even after an earlier failure, so one run reports all of them.
# The script exits 1 when at least one hook failed.
set -uo pipefail

phase="${1:-}"
case "$phase" in
  pre|post-test) ;;
  *) echo "usage: run.sh pre|post-test" >&2; exit 2 ;;
esac

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"
cd "$root" || exit 2

export DLAP_CI_PHASE="$phase"
export DLAP_CI_ROOT="$root"
: "${DLAP_CI_EVENT:=local}"
: "${DLAP_CI_BASE_SHA:=}"
export DLAP_CI_EVENT DLAP_CI_BASE_SHA

grouping=""
[ "${GITHUB_ACTIONS:-}" = "true" ] && grouping=1

shopt -s nullglob
hooks=("$here/$phase"/*.sh)
if [ "${#hooks[@]}" -eq 0 ]; then
  echo "ci-guards: no $phase hooks"
  exit 0
fi

failed=()
skipped=()
passed=0
for hook in "${hooks[@]}"; do
  name="$phase/$(basename "$hook")"
  [ -n "$grouping" ] && echo "::group::$name"
  bash "$hook"
  rc=$?
  [ -n "$grouping" ] && echo "::endgroup::"
  case "$rc" in
    0)  echo "ci-guards: PASS $name"; passed=$((passed + 1)) ;;
    77) echo "ci-guards: SKIP $name"; skipped+=("$name") ;;
    *)  echo "ci-guards: FAIL $name (exit $rc)"
        [ -n "$grouping" ] && echo "::error title=ci-guard failed::$name exited $rc"
        failed+=("$name") ;;
  esac
done

echo "ci-guards: $phase: ${passed} passed, ${#skipped[@]} skipped, ${#failed[@]} failed"
[ "${#failed[@]}" -eq 0 ]
