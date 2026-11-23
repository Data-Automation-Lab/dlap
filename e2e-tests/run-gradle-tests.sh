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

# Run all module tests and write a log under build/test-run-logs/
# Usage: from repo root, bash e2e-tests/run-gradle-tests.sh

set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
RESULT_DIR="$ROOT/build/test-run-logs"
mkdir -p "$RESULT_DIR"
TS="$(date -u +%Y%m%dT%H%M%SZ)"
LOG="$RESULT_DIR/gradle-test-${TS}.log"

echo "Logging to $LOG"
# Use bash so execute bit on gradlew is not required (WSL/Git sometimes lose +x).
bash ./gradlew test --no-daemon --continue 2>&1 | tee "$LOG"
code="${PIPESTATUS[0]}"
echo "Gradle exit code: $code" | tee -a "$LOG"
exit "$code"
