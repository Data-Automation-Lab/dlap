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
# Guard: the fixture corpus (da-log-fixtures) is test support only and every case
# has valid provenance.
#
#   1. No runtime source set (src/main of any other module) mentions the fixtures
#      package or the corpus directory.
#   2. No other module's build file depends on da-log-fixtures outside a test
#      configuration (a fast text check; the Gradle task below is authoritative).
#   3. The Gradle gates pass: verifyFixtureProvenance (every case has meta.yaml, an
#      allowed licence and attribution), fixtureCoverage (supported entries reach
#      -Pdlap.fixtures.minScore, default 0.99) and verifyFixturesTestOnly.
#
# Exit 77 (skip) when the module is absent. Set DLAP_FIXTURES_SKIP_GRADLE=1 to run
# only the two text checks (quick local run); DLAP_FIXTURES_GRADLE_ARGS replaces the
# default Gradle arguments.
set -uo pipefail

root="${DLAP_CI_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)}"
cd "$root" || exit 2

module="da-log-fixtures"
if [ ! -f "$module/build.gradle.kts" ]; then
  echo "fixtures: $module is absent; skipping"
  exit 77
fi

failed=0

# 1. Runtime source sets must not reach for the fixtures.
mapfile -t main_dirs < <(find da-* -maxdepth 3 -type d -path '*/src/main' ! -path "$module/*" 2>/dev/null | sort)
if [ "${#main_dirs[@]}" -gt 0 ]; then
  runtime_hits="$(grep -rIlE 'com\.dlap\.fixtures|dlap-fixtures' "${main_dirs[@]}" 2>/dev/null | sort || true)"
  if [ -n "$runtime_hits" ]; then
    echo "fixtures: runtime code must not use the test-only fixture corpus:" >&2
    echo "$runtime_hits" | sed 's/^/  /' >&2
    failed=1
  fi
fi

# 2. Build files may use the module only from test configurations.
build_hits=""
while IFS= read -r file; do
  [ "$file" = "$module/build.gradle.kts" ] && continue
  while IFS= read -r line; do
    text="${line#*:}"
    trimmed="${text#"${text%%[![:space:]]*}"}"
    case "$trimmed" in
      //*|\**) continue ;;
    esac
    if ! printf '%s' "$text" | grep -qE 'test(Implementation|RuntimeOnly|CompileOnly)'; then
      build_hits="$build_hits$file:${line%%:*}: $trimmed"$'\n'
    fi
  done < <(grep -nE "$module" "$file" 2>/dev/null || true)
done < <(find . -maxdepth 3 -name 'build.gradle.kts' -not -path '*/node_modules/*' -not -path '*/build/*' | sed 's|^\./||' | sort)
if [ -n "$build_hits" ]; then
  echo "fixtures: $module may only be a test dependency (testImplementation, testRuntimeOnly):" >&2
  printf '%s' "$build_hits" | sed 's/^/  /' >&2
  failed=1
fi

if [ "$failed" -ne 0 ]; then
  exit 1
fi
echo "fixtures: no runtime use of $module in sources or build files"

# 3. The Gradle gates.
if [ "${DLAP_FIXTURES_SKIP_GRADLE:-}" = "1" ]; then
  echo "fixtures: DLAP_FIXTURES_SKIP_GRADLE=1; Gradle gates not run"
  exit 0
fi
# shellcheck disable=SC2206
gradle_args=( ${DLAP_FIXTURES_GRADLE_ARGS:---no-daemon --console=plain --no-watch-fs --max-workers=2} )
bash ./gradlew "${gradle_args[@]}" \
  ":$module:verifyFixtureProvenance" \
  ":$module:fixtureCoverage" \
  ":$module:verifyFixturesTestOnly"
rc=$?
if [ "$rc" -ne 0 ]; then
  echo "fixtures: a fixture gate failed (exit $rc); see the Gradle output above" >&2
  exit 1
fi
echo "fixtures: provenance, coverage and test-only gates passed"
