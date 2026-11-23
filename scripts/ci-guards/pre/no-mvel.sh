#!/usr/bin/env bash
#
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
# No-MVEL guard (hardening package SEC-04, ADR-0057). MVEL compiles and runs expressions
# as code; the policy engine used it (through Easy Rules) for policy and subscription
# rule conditions, which let a caller run code on the server. Both are removed. This guard
# fails when either comes back, in any module.
#
# Default scan (source tree only, offline, a few seconds):
#   1. Build declarations: *.gradle.kts, *.gradle, pom.xml and *.toml files that name the
#      group org.mvel or org.jeasy, or the artifacts mvel2 or easy-rules-*. An exclude(...)
#      line is allowed (it removes the library), and so are comment-only lines.
#   2. Imports of org.mvel / org.mvel2 / org.jeasy in any Java, Kotlin or Groovy source.
#   3. Fully qualified org.mvel. / org.mvel2. / org.jeasy. references in production sources
#      (src/main), outside comment-only lines.
# --resolved also resolves runtimeClasspath and testRuntimeClasspath of every Gradle module in
# settings.gradle.kts (new-ui excluded) and fails on any org.mvel or org.jeasy entry. It needs
# Java 21 (JAVA_HOME) and network access for a cold Gradle cache.
#
# Usage:  scripts/ci-guards/pre/no-mvel.sh              static scan of the repository
#         scripts/ci-guards/pre/no-mvel.sh --resolved   static scan plus resolved classpaths
#         scripts/ci-guards/pre/no-mvel.sh --self-test  prove the static scan works
# Exit:   0 = clean (or self-test passed); 1 = violations listed on stderr;
#         2 = self-test failed or bad usage; 77 = --resolved without gradlew (skipped).
# The exit-code contract is the one in scripts/ci-guards/README.md.

set -uo pipefail

# Build-file patterns (extended regex, case-sensitive: group IDs are lower case).
DECL_PATTERN='["'"'"']org\.(mvel|jeasy)[^"'"'"']*:|group[[:space:]]*[=:][[:space:]]*["'"'"']org\.(mvel|jeasy)["'"'"']|<groupId>[[:space:]]*org\.(mvel|jeasy)[[:space:]]*</groupId>|["'"'"':](mvel2|easy-rules-[a-z]+)["'"'"':]'
IMPORT_PATTERN='^[[:space:]]*import[[:space:]]+(static[[:space:]]+)?org\.(mvel2?|jeasy)\.'
FQN_PATTERN='\borg\.(mvel2?|jeasy)\.[A-Za-z]'
COMMENT_LINE='^[^:]+:[0-9]+:[[:space:]]*(//|/\*|\*|#|<!--)'

# list_files <root> <name-args...>: print matching files under <root>, skipping build output,
# dependency caches and VCS metadata.
list_files() {
  local root="$1"
  shift
  (
    cd "$root" || exit 0
    find . \( -name .git -o -name build -o -name node_modules -o -name .gradle -o -name out \) -prune -o \
      -type f \( "$@" \) -print0 2>/dev/null
  )
}

# scan_root <root>: print "file:line:text" for every violation under <root>.
scan_root() {
  local root="$1"
  (
    cd "$root" || exit 0
    # Force text mode (-a): some real source files are misclassified as binary by grep.
    list_files . -name '*.gradle.kts' -o -name '*.gradle' -o -name 'pom.xml' -o -name '*.toml' \
      | { xargs -0 -r grep -HnaE "$DECL_PATTERN" || true; } \
      | { grep -vE "$COMMENT_LINE" || true; } \
      | { grep -vE '\bexclude[[:space:]]*\(' || true; }
    list_files . -name '*.java' -o -name '*.kt' -o -name '*.groovy' \
      | { xargs -0 -r grep -HnaE "$IMPORT_PATTERN" || true; }
    list_files . -name '*.java' -o -name '*.kt' \
      | { grep -zE '/src/main/' || true; } \
      | { xargs -0 -r grep -HnaE "$FQN_PATTERN" || true; } \
      | { grep -vE "$COMMENT_LINE" || true; } \
      | { grep -vE '^[^:]+:[0-9]+:[[:space:]]*import[[:space:]]' || true; }
  ) | sort -u
}

# resolved_scan <root>: print banned coordinates found in resolved classpaths.
resolved_scan() {
  local root="$1"
  [ -x "$root/gradlew" ] || [ -f "$root/gradlew" ] || return 77
  local modules m conf out rc reports
  modules="$(sed -nE 's/^[[:space:]]*include\("([^"]+)"\).*/\1/p' "$root/settings.gradle.kts" | grep -vx 'new-ui')"
  [ -n "$modules" ] || return 77
  # One Gradle run per configuration: a task named twice on one command line runs once, with
  # the last --configuration only.
  for conf in runtimeClasspath testRuntimeClasspath; do
    local args=()
    for m in $modules; do
      args+=(":$m:dependencies" --configuration "$conf")
    done
    out="$(bash "$root/gradlew" -p "$root" "${args[@]}" --no-daemon --console=plain --no-watch-fs \
      --no-configuration-cache --max-workers=2 2>&1)"
    rc=$?
    if [ $rc -ne 0 ]; then
      printf '%s\n' "$out" | tail -n 20 >&2
      echo "ERROR: dependency resolution of $conf failed (exit $rc); see above." >&2
      return 1
    fi
    reports="$(printf '%s\n' "$out" | grep -c "^$conf")"
    if [ "$reports" -ne "$(printf '%s\n' "$modules" | wc -l)" ]; then
      echo "ERROR: expected one $conf report per module, got $reports." >&2
      return 1
    fi
    # A banned module that fails to resolve is printed with FAILED and still matches here.
    printf '%s\n' "$out" | grep -nE 'org\.(mvel|jeasy)' | sed "s/^/$conf: /" || true
  done
}

self_test() {
  local tmp
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' RETURN
  mkdir -p "$tmp/da-a/src/main/java/x" "$tmp/da-a/src/test/java/x" "$tmp/da-b" "$tmp/da-c/build" "$tmp/gradle"

  # Violations.
  printf 'dependencies {\n    implementation("org.jeasy:easy-rules-mvel:4.1.0")\n}\n' > "$tmp/da-a/build.gradle.kts"
  printf 'dependencies {\n  implementation group: "org.mvel", name: "mvel2", version: "2.5.2.Final"\n}\n' > "$tmp/da-b/build.gradle"
  printf '<project><dependency><groupId>org.mvel</groupId></dependency></project>\n' > "$tmp/da-b/pom.xml"
  printf '[libraries]\nrules = { module = "org.jeasy:easy-rules-core", version = "4.1.0" }\n' > "$tmp/gradle/libs.versions.toml"
  printf 'import org.jeasy.rules.api.Rules;\nclass Bad1 { }\n' > "$tmp/da-a/src/main/java/x/Bad1.java"
  printf 'class Bad2 { Object o = new org.mvel2.ParserContext(); }\n' > "$tmp/da-a/src/main/java/x/Bad2.java"
  printf 'import org.mvel2.MVEL\nclass Bad3\n' > "$tmp/da-a/src/test/java/x/Bad3.kt"

  # Allowed.
  printf '// org.mvel:mvel2 was removed (ADR-0057)\nconfigurations.all {\n  exclude(group = "org.mvel")\n  if (requested.group == "org.jeasy") { throw GradleException("banned") }\n}\n' \
    > "$tmp/da-c/build.gradle.kts"
  printf 'implementation("org.jeasy:easy-rules-core:4.1.0")\n' > "$tmp/da-c/build/generated.gradle.kts"
  printf '/** Replaces org.jeasy.rules.mvel.MVELRule (retired). */\nclass Note { }\n' > "$tmp/da-a/src/main/java/x/Note.java"
  printf 'class Probe { String s = "org.mvel2.MVEL"; }\n' > "$tmp/da-a/src/test/java/x/Probe.java"
  printf 'class Fine { String mvel = "retired"; }\n' > "$tmp/da-a/src/main/java/x/Fine.java"

  local hits files expected
  hits="$(scan_root "$tmp")"
  files="$(printf '%s\n' "$hits" | sed -nE 's|^\./([^:]+):.*|\1|p' | sort -u | tr '\n' ' ')"
  expected="da-a/build.gradle.kts da-a/src/main/java/x/Bad1.java da-a/src/main/java/x/Bad2.java da-a/src/test/java/x/Bad3.kt da-b/build.gradle da-b/pom.xml gradle/libs.versions.toml "
  if [ "$files" != "$expected" ]; then
    echo "SELF-TEST FAILED: expected violations in [$expected]" >&2
    echo "                  but found            [$files]" >&2
    printf '%s\n' "$hits" >&2
    return 2
  fi
  echo "SELF-TEST OK: flagged 7 files; allowed comments, exclude(), a resolution guard, build output, string literals in tests."
}

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
mode="${1:-}"
case "$mode" in
  --self-test)
    self_test
    exit $?
    ;;
  ""|--resolved)
    ;;
  *)
    echo "usage: $0 [--resolved|--self-test]" >&2
    exit 2
    ;;
esac

status=0
hits="$(scan_root "$ROOT")"
if [ -n "$hits" ]; then
  echo "ERROR: MVEL or Easy Rules is declared or imported (ADR-0057 retired both):" >&2
  printf '%s\n' "$hits" >&2
  echo "Policies evaluate through the dlap/v1 DSL; subscriptions use typed filters (com.dlap.policyengine.filter)." >&2
  status=1
else
  echo "OK: no org.mvel or org.jeasy declaration or import in the source tree."
fi

if [ "$mode" = "--resolved" ]; then
  resolved="$(resolved_scan "$ROOT")"
  rc=$?
  if [ $rc -eq 77 ]; then
    echo "SKIP: --resolved needs gradlew and settings.gradle.kts." >&2
    [ $status -eq 0 ] && exit 77
  elif [ $rc -ne 0 ]; then
    status=1
  elif [ -n "$resolved" ]; then
    echo "ERROR: org.mvel or org.jeasy is on a resolved classpath:" >&2
    printf '%s\n' "$resolved" >&2
    status=1
  else
    echo "OK: no org.mvel or org.jeasy on any runtimeClasspath or testRuntimeClasspath."
  fi
fi
exit $status
