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
#
# ---------------------------------------------------------------------------
# Run any command under the host-wide e2e harness lock (HARN-01).
#
# The four *.sh harnesses take the lock themselves. Use this wrapper for everything else that
# drives the shared Docker host or the fixed dev ports, for example the AuthZEN PDP check:
#
#   bash e2e-tests/with-harness-lock.sh -- powershell -NoProfile -File e2e-tests/authzen-pdp-verify.ps1
#   bash e2e-tests/with-harness-lock.sh --label authzen -- bash e2e-tests/cloud-pipeline-e2e.sh gcp
#
# The command runs as a child, so the lock is released when it ends, however it ends. A
# harness script started inside the wrapper sees DLAP_HARNESS_LOCK_HELD and does not wait
# on the lock it is already covered by.
#
# Exit status: the command's, or 1 when the lock could not be taken within
# DLAP_HARNESS_LOCK_TIMEOUT seconds (default 3600). See harness-lib.sh for the knobs.
# ---------------------------------------------------------------------------
set -uo pipefail

label="wrapper"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --label) label="${2:?--label needs a value}"; shift 2 ;;
    --) shift; break ;;
    -h|--help) sed -n '/^# Run any command/,/^# -----/p' "$0" | sed 's/^# \{0,1\}//' | sed '$d'; exit 0 ;;
    *) break ;;
  esac
done
[[ $# -gt 0 ]] || { echo "usage: with-harness-lock.sh [--label NAME] -- command [args...]" >&2; exit 2; }

# shellcheck source=harness-lib.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/harness-lib.sh"
harness_lock_acquire "$label"
"$@"
exit $?
