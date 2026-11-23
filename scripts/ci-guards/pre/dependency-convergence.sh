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
# Dependency convergence gate (hardening package DEPS-01, critic item 13).
#
# What it enforces
#   protobuf-java, gRPC, Netty, Jackson and Guava resolve, in the runtime classpath of every boot
#   jar, to the declared alignment: the Spring Boot BOM for the first four, the declared Guava line
#   for the last, plus the dated rows of CONVERGENCE_EXCEPTIONS. The policy and the alignment live
#   in the root build.gradle.kts (DEPS-01 block); this hook runs its Gradle task,
#   verifyDependencyConvergence, which first runs dependencyConvergenceSelfTest (the policy against
#   injected conflicts, 15 cases). The root check and build tasks run the same task.
#
# Needs Java 21 for Gradle (set JAVA_HOME) and the network (Maven Central) unless the Gradle cache
# already holds the dependency graph. Runs in about one minute.
#
# Usage:  scripts/ci-guards/pre/dependency-convergence.sh           the gate
#         scripts/ci-guards/pre/dependency-convergence.sh --prove   end-to-end proof, three Gradle
#           runs (about three minutes): the real build passes; a real protobuf-java 3.25.5 added to
#           da-logingest fails the check; a real Guava 32.1.3-jre added to da-catalog fails it as a
#           conflict between modules. The conflicts are added by a temporary Gradle init script, so
#           no build file changes.
# Environment:
#   DLAP_GRADLE_ARGS   extra Gradle arguments (default: --no-daemon --console=plain --no-watch-fs
#                      --max-workers=2)
# Exit:   0 = pass; other = fail. The exit-code contract is the one in scripts/ci-guards/README.md.

set -uo pipefail

DEFAULT_GRADLE_ARGS="--no-daemon --console=plain --no-watch-fs --max-workers=2"

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../../.." && pwd)"
gradlew="$root/gradlew"

# check [init-script]: runs the Gradle task, output in $out. Returns Gradle's exit code.
check() {
  local init=()
  [ -n "${1:-}" ] && init=(-I "$1")
  # shellcheck disable=SC2086
  bash "$gradlew" -p "$root" "${init[@]}" verifyDependencyConvergence ${DLAP_GRADLE_ARGS:-$DEFAULT_GRADLE_ARGS} >"$out" 2>&1
}

if [ ! -f "$gradlew" ]; then
  echo "dependency-convergence: ERROR: $gradlew not found" >&2
  exit 1
fi

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
out="$tmp/gradle.log"

case "${1:-}" in
  "")
    echo "dependency-convergence: verifying the boot jar runtime classpaths (Gradle)"
    if check; then
      grep -F "dependency convergence" "$out"
      exit 0
    fi
    sed -n '/What went wrong/,/Report:/p' "$out" >&2
    grep -qF "dependency convergence failed" "$out" \
      || { echo "dependency-convergence: ERROR: Gradle failed before the check ran (is JAVA_HOME a JDK 21?)" >&2; tail -40 "$out" >&2; }
    exit 1
    ;;
  --prove) ;;
  *) echo "usage: dependency-convergence.sh [--prove]" >&2; exit 2 ;;
esac

passed=0
failed=0
ok()  { passed=$((passed + 1)); echo "  ok    $*"; }
bad() { failed=$((failed + 1)); echo "  FAIL  $*" >&2; sed 's/^/        | /' "$out" | tail -40 >&2; }

# inject <file> <project path> <group:name:version>: an init script that adds one runtime dependency.
inject() {
  cat >"$1" <<EOF
allprojects {
  if (path == "$2") {
    pluginManager.withPlugin("java") { dependencies { add("runtimeOnly", "$3") } }
  }
}
EOF
}

echo "dependency-convergence --prove"
if check; then ok "the real build converges"; else bad "the real build converges"; fi

inject "$tmp/protobuf.gradle" ":da-logingest" "com.google.protobuf:protobuf-java:3.25.5"
if check "$tmp/protobuf.gradle"; then
  bad "an injected protobuf-java 3.25.5 in da-logingest fails the check (the build passed)"
elif grep -qF ":da-logingest: com.google.protobuf:protobuf-java resolves 3.25.5; the declared alignment of family protobuf is" "$out"; then
  ok "an injected protobuf-java 3.25.5 in da-logingest fails the check"
else
  bad "an injected protobuf-java 3.25.5 in da-logingest fails the check (another failure)"
fi

inject "$tmp/guava.gradle" ":da-catalog" "com.google.guava:guava:32.1.3-jre"
if check "$tmp/guava.gradle"; then
  bad "an injected Guava 32.1.3-jre in da-catalog fails the check (the build passed)"
elif grep -qF ":da-catalog: com.google.guava:guava resolves 32.1.3-jre" "$out" && grep -qF "other boot jars: 33.7.2 in" "$out"; then
  ok "an injected Guava 32.1.3-jre in da-catalog fails the check as a conflict between modules"
else
  bad "an injected Guava 32.1.3-jre in da-catalog fails the check (another failure)"
fi

echo "dependency-convergence --prove: $passed passed, $failed failed"
[ "$failed" -eq 0 ]
