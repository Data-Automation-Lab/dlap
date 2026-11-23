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
# No-Instant.now() guard (hardening package QA-01, work-plan rule R6).
#
# Business time is event time. Code takes its time from an injected java.time.Clock, so a test can
# pin it and a replay of old events keeps the time the source recorded. A bare Instant.now() in
# production code reads the wall clock instead; it makes a read from last month look like a read
# from today, and it cannot be tested without sleeping.
#
# The rule: a zero-argument Instant.now() call in da-*/src/main (Java and Kotlin) is a violation.
# Instant.now(clock) is fine. Comment-only lines and test sources are not scanned.
#
# The repository already holds many such calls, so the guard is a ratchet:
#   - scripts/ci-guards/pre/no-instant-now.allowlist lists, per file, the most calls that file may
#     keep (columns: path|max|category|owner|reason).
#   - A file with MORE calls than its entry (or any call and no entry) fails the build.
#   - A file with FEWER calls than its entry passes with a note. Run with --tighten to lower the
#     entry, so the number can only go down. --strict turns the note into a failure.
#   - The allowlist shrinks to empty as packages replace the calls with a Clock. Category
#     "business" marks a call that decides a business field (an R6 violation to fix); "processing"
#     marks an audit stamp or a response timestamp, tolerated until the module has a Clock bean.
#
# Needs GNU grep, sed, awk and sort; present on the CI runners and in Git Bash.
#
# Usage:  scripts/ci-guards/pre/no-instant-now.sh              check the repository (the CI hook)
#         scripts/ci-guards/pre/no-instant-now.sh --strict     also fail on an allowlist entry that is too high
#         scripts/ci-guards/pre/no-instant-now.sh --tighten    lower or drop allowlist entries to match the code
#         scripts/ci-guards/pre/no-instant-now.sh --list       print "path<TAB>count" for every file with a call
#         scripts/ci-guards/pre/no-instant-now.sh --self-test  prove the guard itself works
# Exit:   0 = pass (or self-test passed); 1 = violations listed on stderr; 2 = usage error or self-test failed.

set -euo pipefail
export LC_ALL=C

ALLOWLIST_REL="scripts/ci-guards/pre/no-instant-now.allowlist"

# counts <root>: print "path<TAB>count" (calls per file, sorted) for da-*/src/main under <root>.
counts() {
  local root="$1"
  (
    cd "$root"
    # One awk process for the whole batch (awk reads every file as text, so a file that grep
    # would misclassify as binary is still scanned). Comment-only lines are skipped and a
    # trailing // comment is cut off before the calls on the line are counted.
    find . -type f \( -name '*.java' -o -name '*.kt' \) -path './da-*/src/main/*' -print0 2>/dev/null \
      | xargs -0 -r awk '
          /^[ \t]*(\/\/|\/\*|\*)/ { next }
          {
            line = $0
            sub(/\/\/.*$/, "", line)
            n = gsub(/Instant\.now\([ \t]*\)/, "&", line)
            if (n > 0) { f = FILENAME; sub(/^\.\//, "", f); cnt[f] += n }
          }
          END { for (f in cnt) printf "%s\t%d\n", f, cnt[f] }' \
      | sort
  ) || true
}

# allow_pairs <allowlist>: print "path<TAB>max" for every entry.
allow_pairs() {
  [ -f "$1" ] || return 0
  awk -F'|' '/^[[:space:]]*(#|$)/ { next } { gsub(/^[ \t]+|[ \t]+$/, "", $1); gsub(/[ \t]/, "", $2); print $1 "\t" $2 }' "$1"
}

# compare <allow pairs file> <count pairs file>: print "UP path cur max" and "DOWN path cur max" lines.
compare() {
  awk -F'\t' '
    NR == FNR { max[$1] = $2 + 0; next }
    { cur[$1] = $2 + 0 }
    END {
      for (p in cur) { m = (p in max) ? max[p] : 0; if (cur[p] > m) print "UP\t" p "\t" cur[p] "\t" m }
      for (p in max) { c = (p in cur) ? cur[p] : 0; if (c < max[p]) print "DOWN\t" p "\t" c "\t" max[p] }
    }' "$1" "$2" | sort
}

# check <root> <allowlist> <strict 0|1>: the CI hook. Exit 0 or 1.
check() {
  local root="$1" allow="$2" strict="$3" tmp up down
  tmp="$(mktemp -d)"
  allow_pairs "$allow" >"$tmp/allow"
  counts "$root" >"$tmp/cur"
  compare "$tmp/allow" "$tmp/cur" >"$tmp/diff"
  up="$(awk -F'\t' '$1 == "UP"' "$tmp/diff")"
  down="$(awk -F'\t' '$1 == "DOWN"' "$tmp/diff")"
  local total
  total="$(awk -F'\t' '{ s += $2 } END { print s + 0 }' "$tmp/cur")"
  rm -rf "$tmp"

  if [ -n "$up" ]; then
    echo "ERROR: zero-argument Instant.now() in production code (rule R6, business time is event time):" >&2
    printf '%s\n' "$up" | awk -F'\t' '{ printf "  %s: %s call(s), allowlist permits %s\n", $2, $3, $4 }' >&2
    echo "Inject a java.time.Clock and call Instant.now(clock), or take the time from the event." >&2
    echo "Do not raise the allowlist; it only shrinks. Allowlist: $ALLOWLIST_REL" >&2
    return 1
  fi
  if [ -n "$down" ]; then
    printf '%s\n' "$down" | awk -F'\t' '{ printf "  %s: %s call(s), allowlist still permits %s\n", $2, $3, $4 }' >&2
    if [ "$strict" = "1" ]; then
      echo "ERROR: allowlist entries are higher than the code needs. Run: bash scripts/ci-guards/pre/no-instant-now.sh --tighten" >&2
      return 1
    fi
    echo "NOTE: the allowlist can be lowered. Run: bash scripts/ci-guards/pre/no-instant-now.sh --tighten" >&2
  fi
  echo "OK: no new zero-argument Instant.now() in da-*/src/main ($total call(s) remain on the allowlist)."
}

# tighten <root> <allowlist>: rewrite the allowlist so each max equals the current count; drop zeros.
# Refuses (exit 1) when any file has more calls than its entry.
tighten() {
  local root="$1" allow="$2" tmp
  [ -f "$allow" ] || { echo "ERROR: no allowlist at $allow" >&2; return 2; }
  tmp="$(mktemp -d)"
  allow_pairs "$allow" >"$tmp/allow"
  counts "$root" >"$tmp/cur"
  if compare "$tmp/allow" "$tmp/cur" | grep -q '^UP'; then
    rm -rf "$tmp"
    echo "ERROR: --tighten only lowers entries; a file has more calls than its entry. Fix the code first." >&2
    return 1
  fi
  awk -F'|' -v OFS='|' '
    NR == FNR { split($0, kv, "\t"); cur[kv[1]] = kv[2] + 0; next }
    /^[[:space:]]*(#|$)/ { print; next }
    {
      p = $1; gsub(/^[ \t]+|[ \t]+$/, "", p)
      c = (p in cur) ? cur[p] : 0
      if (c == 0) next
      $2 = c
      print
    }' "$tmp/cur" "$allow" >"$tmp/new"
  cat "$tmp/new" >"$allow"
  rm -rf "$tmp"
  echo "OK: allowlist tightened ($allow)."
}

self_test() {
  local tmp rc
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' RETURN
  local root="$tmp/repo" allow="$tmp/allow.txt"
  local main="$root/da-fake/src/main/java/x"
  mkdir -p "$main" "$root/da-fake/src/test/java/x"
  printf 'class Clean { java.time.Instant a(java.time.Clock c) { return java.time.Instant.now(c); } }\n' >"$main/Clean.java"
  printf '// Instant.now() is banned here\n/* Instant.now() */\n/**\n * uses Instant.now()\n */\nclass Note { }\n' >"$main/Note.java"
  printf 'class Tail { void a() { int x = 1; // was Instant.now()\n } }\n' >"$main/Tail.java"
  printf 'class T { Object o = java.time.Instant.now(); }\n' >"$root/da-fake/src/test/java/x/T.java"
  printf 'class Old { Object a = java.time.Instant.now(); Object b = Instant.now( ); }\n' >"$main/Old.java"
  printf '# header\nda-fake/src/main/java/x/Old.java|2|business|P-1|legacy\n' >"$allow"

  expect() { # expect <desc> <wanted rc> <cmd...>
    local desc="$1" want="$2"; shift 2
    set +e; "$@" >"$tmp/out" 2>&1; rc=$?; set -e
    if [ "$rc" -ne "$want" ]; then
      echo "SELF-TEST FAILED: $desc (exit $rc, wanted $want)" >&2; sed 's/^/    | /' "$tmp/out" >&2; return 2
    fi
  }
  expect_text() { grep -qF -- "$2" "$tmp/out" || { echo "SELF-TEST FAILED: $1 (missing '$2')" >&2; sed 's/^/    | /' "$tmp/out" >&2; return 2; }; }

  local got; got="$(counts "$root")"
  [ "$got" = "$(printf 'da-fake/src/main/java/x/Old.java\t2')" ] \
    || { echo "SELF-TEST FAILED: counts gave [$got]" >&2; return 2; }

  expect "the exact allowance passes" 0 check "$root" "$allow" 0 || return 2
  printf 'class New { Object a = java.time.Instant.now(); }\n' >"$main/New.java"
  expect "a call in an unlisted file fails" 1 check "$root" "$allow" 0 || return 2
  expect_text "names the file" "x/New.java: 1 call(s), allowlist permits 0" || return 2
  rm -f "$main/New.java"
  printf 'class Old { Object a = java.time.Instant.now(); Object b = Instant.now(); Object c = Instant.now(); }\n' >"$main/Old.java"
  expect "growth in a listed file fails" 1 check "$root" "$allow" 0 || return 2
  expect "tighten refuses growth" 1 tighten "$root" "$allow" || return 2
  printf 'class Old { Object a = java.time.Instant.now(); }\n' >"$main/Old.java"
  expect "fewer calls pass by default" 0 check "$root" "$allow" 0 || return 2
  expect_text "tells how to lower" "--tighten" || return 2
  expect "fewer calls fail under --strict" 1 check "$root" "$allow" 1 || return 2
  expect "tighten lowers the entry" 0 tighten "$root" "$allow" || return 2
  grep -qF 'da-fake/src/main/java/x/Old.java|1|business|P-1|legacy' "$allow" \
    || { echo "SELF-TEST FAILED: tighten did not lower to 1: $(cat "$allow")" >&2; return 2; }
  grep -qF '# header' "$allow" || { echo "SELF-TEST FAILED: tighten dropped the comment line" >&2; return 2; }
  expect "strict passes after tighten" 0 check "$root" "$allow" 1 || return 2
  printf 'class Old { }\n' >"$main/Old.java"
  expect "tighten drops a cleared file" 0 tighten "$root" "$allow" || return 2
  grep -q 'Old.java' "$allow" && { echo "SELF-TEST FAILED: tighten kept a zero entry" >&2; return 2; }
  expect "an empty allowlist and a clean tree pass" 0 check "$root" "$allow" 1 || return 2
  echo "SELF-TEST OK: flags new and growing calls; ignores Instant.now(clock), comments and tests; ratchets down."
}

mode="${1:-check}"
case "$mode" in
  --self-test) self_test; exit $? ;;
  check|--strict|--tighten|--list) ;;
  *) echo "usage: no-instant-now.sh [--strict|--tighten|--list|--self-test]" >&2; exit 2 ;;
esac

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
ALLOW="$ROOT/$ALLOWLIST_REL"
case "$mode" in
  check)      check "$ROOT" "$ALLOW" 0 ;;
  --strict)   check "$ROOT" "$ALLOW" 1 ;;
  --tighten)  tighten "$ROOT" "$ALLOW" ;;
  --list)     counts "$ROOT" ;;
esac
