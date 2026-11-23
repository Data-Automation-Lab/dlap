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
# Disabled-tests guard (hardening package QA-01).
#
# A test that is switched off with @Disabled is a hole in the suite that nobody sees once the
# commit that disabled it has scrolled away. Rule: every Java test file under da-*/src/test that
# holds an @Disabled must be named in an open entry of docs/OPEN_QUESTIONS.md. The entry says why
# the test is off and who turns it back on. An entry whose heading says RESOLVED does not count.
#
# The match is on the file's class name (for example AutoLabelOrchestratorRulesProviderTest), so
# the check survives question renumbering and needs no edit to the test file.
#
# Not covered, on purpose: conditional skips (assumptions, @Testcontainers(disabledWithoutDocker),
# custom conditions such as @RequiresDocker). They show as SKIPPED in the test report.
#
# Needs GNU grep and awk; present on the CI runners and in Git Bash.
#
# Usage:  scripts/ci-guards/pre/disabled-tests-tracked.sh              scan the repository (the CI hook)
#         scripts/ci-guards/pre/disabled-tests-tracked.sh --list       print "file<TAB>tracked|UNTRACKED"
#         scripts/ci-guards/pre/disabled-tests-tracked.sh --self-test  prove the guard itself works
# Exit:   0 = pass (or self-test passed); 1 = untracked @Disabled tests listed on stderr; 2 = self-test failed.

set -euo pipefail
export LC_ALL=C

QUESTIONS_REL="docs/OPEN_QUESTIONS.md"

# disabled_files <root>: print every test source under da-*/src/test that has an @Disabled annotation.
disabled_files() {
  local root="$1"
  (
    cd "$root"
    find . -type f -name '*.java' -path './da-*/src/test/*' -print0 2>/dev/null \
      | { xargs -0 -r grep -laE '^[[:space:]]*(@Test[[:space:]]+)?@(org\.junit\.jupiter\.api\.)?Disabled\b' || true; } \
      | sed 's|^\./||' | sort
  )
}

# tracked <questions file> <class name>: exit 0 when an open entry names the class.
tracked() {
  local questions="$1" cls="$2"
  [ -f "$questions" ] || return 1
  awk -v cls="$cls" '
    function finish() { if (inq && hit && !resolved) found = 1 }
    /^## Q-/ { finish(); inq = 1; hit = 0; resolved = ($0 ~ /RESOLVED/) }
    inq && index($0, cls) { hit = 1 }
    END { finish(); exit(found ? 0 : 1) }' "$questions"
}

# report <root> <questions file>: print "file<TAB>tracked|UNTRACKED" for every file with an @Disabled.
report() {
  local root="$1" questions="$2" f cls state
  while IFS= read -r f; do
    [ -n "$f" ] || continue
    cls="$(basename "$f" .java)"
    if tracked "$questions" "$cls"; then state="tracked"; else state="UNTRACKED"; fi
    printf '%s\t%s\n' "$f" "$state"
  done < <(disabled_files "$root")
}

self_test() {
  local tmp
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' RETURN
  local root="$tmp/repo" t="$tmp/repo/da-fake/src/test/java/x"
  mkdir -p "$t" "$tmp/repo/docs"
  printf 'class A {\n    @Test\n    @Disabled("why")\n    void a() {}\n}\n'                 >"$t/ATest.java"
  printf '@Disabled("class level")\nclass BTest {\n}\n'                                          >"$t/BTest.java"
  printf '/**\n * mentions @Disabled in a comment only\n */\nclass CTest {\n}\n'                 >"$t/CTest.java"
  printf 'class DTest {\n    @org.junit.jupiter.api.Disabled\n    void d() {}\n}\n'             >"$t/DTest.java"
  printf 'class ETest {\n    void e() { String s = "@Disabled"; }\n}\n'                         >"$t/ETest.java"
  local q="$root/docs/OPEN_QUESTIONS.md"
  {
    printf '# Open questions\n\n'
    printf '## Q-001 - ATest is off [OPEN, QA-01]\n\nATest.a needs a broker.\n\n'
    printf '## Q-002 - BTest was fixed [RESOLVED 2026-10-19]\n\nBTest is back on.\n\n'
    printf '## Q-003 - unrelated [OPEN]\n\nNothing here.\n'
  } >"$q"

  local got want
  got="$(report "$root" "$q")"
  want="$(printf 'da-fake/src/test/java/x/ATest.java\ttracked\nda-fake/src/test/java/x/BTest.java\tUNTRACKED\nda-fake/src/test/java/x/DTest.java\tUNTRACKED')"
  if [ "$got" != "$want" ]; then
    echo "SELF-TEST FAILED: expected" >&2; printf '%s\n' "$want" >&2
    echo "but got" >&2; printf '%s\n' "$got" >&2
    return 2
  fi
  # A name that is only a prefix of a longer word is a hit by substring; the entry must name the class.
  tracked "$q" "ATest" || { echo "SELF-TEST FAILED: ATest should be tracked" >&2; return 2; }
  tracked "$q" "BTest" && { echo "SELF-TEST FAILED: a RESOLVED entry must not count" >&2; return 2; }
  tracked "$tmp/missing.md" "ATest" && { echo "SELF-TEST FAILED: a missing file tracks nothing" >&2; return 2; }
  echo "SELF-TEST OK: flags an @Disabled test with no open entry; ignores comments, strings and RESOLVED entries."
}

mode="${1:-check}"
case "$mode" in
  --self-test) self_test; exit $? ;;
  check|--list) ;;
  *) echo "usage: disabled-tests-tracked.sh [--list|--self-test]" >&2; exit 2 ;;
esac

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
QUESTIONS="$ROOT/$QUESTIONS_REL"
if [ "$mode" = "--list" ]; then
  report "$ROOT" "$QUESTIONS"
  exit 0
fi

untracked="$(report "$ROOT" "$QUESTIONS" | awk -F'\t' '$2 == "UNTRACKED" { print $1 }')"
if [ -n "$untracked" ]; then
  echo "ERROR: @Disabled tests that no open entry of $QUESTIONS_REL names:" >&2
  printf '  %s\n' $untracked >&2
  echo "Re-enable the test, or add an open question that names its class and says who turns it back on." >&2
  exit 1
fi
total="$(disabled_files "$ROOT" | grep -c . || true)"
echo "OK: every @Disabled test file ($total) is named in an open entry of $QUESTIONS_REL."
