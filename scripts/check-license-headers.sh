#!/usr/bin/env bash
#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file. Apache-2.0.
#
# Apache 2.0 license-header enforcement (CLAUDE.md §10.5). Fails the build if any
# first-party source file lacks the ASF header. Network-free by design (a
# lightweight alternative to the Apache RAT plugin, which needs plugin-portal
# resolution at build time); run locally or as a CI gate.
#
# A file passes when its first 30 lines hold both the line "Licensed to the Apache Software
# Foundation" and the licence URL www.apache.org/licenses/LICENSE-2.0. A mention further down, in
# a comment or a string, does not count. An empty source file fails.
#
# Scope (the .java, .kt, .ts and .tsx files that CLAUDE.md §10.5 names):
#   da-*/                      Java and Kotlin of the services and libraries
#   new-ui/frontend/src/       TypeScript of the UI
#   new-ui/frontend/           TypeScript configs at its root (for example vite.config.ts)
#   new-ui/src/                Java of the UI's Spring Boot shell
# The UI is a git submodule. When it is not checked out the UI part is skipped with a notice and
# the run still passes; use --require-ui where the UI must be present.
# Excluded: build output, node_modules, generated code, and vendored third-party files (the
# Maven wrapper and Vite's generated env stub carry their own headers).
#
# Usage:  ./scripts/check-license-headers.sh               check the repository
#         ./scripts/check-license-headers.sh --require-ui  fail when new-ui is not checked out
#         ./scripts/check-license-headers.sh --self-test   prove the check itself works
# Environment: DLAP_UI_DIR  the UI checkout (default: new-ui under the repo root)
# Exit:   0 = all headers present (or self-test passed); 1 = one or more missing (listed on
#         stderr), or --require-ui without a UI; 2 = self-test failed.

set -euo pipefail

MARKER="Licensed to the Apache Software Foundation"
LICENSE_URL="www.apache.org/licenses/LICENSE-2.0"
HEAD_LINES=30

# list_files <ui dir>: NUL-separated first-party source files under the current directory.
list_files() {
  local ui="$1"
  {
    find da-* -type f \( -name '*.java' -o -name '*.kt' \) -print0 2>/dev/null || true
    if [ -d "$ui/frontend" ]; then
      find "$ui/frontend/src" -type f \( -name '*.ts' -o -name '*.tsx' \) -print0 2>/dev/null || true
      find "$ui/frontend" -maxdepth 1 -type f \( -name '*.ts' -o -name '*.tsx' \) -print0 2>/dev/null || true
    fi
    if [ -d "$ui/src" ]; then
      find "$ui/src" -type f -name '*.java' -print0 2>/dev/null || true
    fi
  } \
    | { grep -zvE '/(build|node_modules|generated|\.mvn/wrapper)/' || true; } \
    | { grep -zvE '(vite-env\.d\.ts|MavenWrapperDownloader\.java)$' || true; }
}

# scan <repo root> <ui dir>: print "TOTAL <n>" then every file without the header, one per line.
scan() {
  local base="$1" ui="$2"
  (
    cd "$base"
    local tmp
    tmp="$(mktemp)"
    list_files "$ui" | sort -zu >"$tmp"
    printf 'TOTAL %s\n' "$(tr -cd '\0' <"$tmp" | wc -c | tr -d ' ')"
    # Empty files never reach awk's line loop, so find them here (bash builtins only, no spawn).
    local f
    while IFS= read -r -d '' f; do
      [ -s "$f" ] || echo "$f"
    done <"$tmp"
    # The awk program remembers the header state per file and prints the file when the next one
    # starts (and at the end), so one awk process serves a whole batch of files.
    xargs -0 -r awk -v mark="$MARKER" -v url="$LICENSE_URL" -v maxline="$HEAD_LINES" '
      function report() { if (seen && !(m && u)) print file }
      FNR == 1 { report(); file = FILENAME; seen = 1; m = 0; u = 0 }
      FNR <= maxline { if (index($0, mark)) m = 1; if (index($0, url)) u = 1 }
      END { report() }
    ' <"$tmp"
    rm -f "$tmp"
  )
}

# check_tree <repo root> <ui dir> <require ui 0|1>: the gate. Returns 0 or 1.
check_tree() {
  local base="$1" ui="$2" require_ui="$3" out total
  if ! (cd "$base" && [ -d "$ui/frontend/src" ]); then
    if [ "$require_ui" = 1 ]; then
      echo "ERROR: the UI checkout ($ui) is required but not present." >&2
      return 1
    fi
    echo "NOTE: the UI checkout ($ui) is not present; UI sources are not checked."
  fi
  out="$(scan "$base" "$ui")"
  total="$(printf '%s\n' "$out" | sed -n 's/^TOTAL //p')"
  mapfile -t missing < <(printf '%s\n' "$out" | grep -v '^TOTAL ' | grep -v '^$' | sort -u || true)
  if [ "${#missing[@]}" -gt 0 ]; then
    echo "ERROR: ${#missing[@]} source file(s) missing the Apache 2.0 license header:" >&2
    printf '  %s\n' "${missing[@]}" >&2
    echo "Add the standard ASF header (see any existing .java) before committing." >&2
    return 1
  fi
  echo "OK: all ${total} first-party source files carry the Apache 2.0 license header."
}

self_test() {
  local tmp rc
  tmp="$(mktemp -d)"
  self_test_run "$tmp"
  rc=$?
  rm -rf "$tmp"
  return $rc
}

self_test_run() { # <scratch dir>
  local t="$1" failed=0
  local good
  good=$(printf '/*\n * Licensed to the Apache Software Foundation (ASF) under one\n * http://www.apache.org/licenses/LICENSE-2.0\n */\nclass X {}\n')

  mk() { mkdir -p "$(dirname "$t/$1")"; printf '%s\n' "$2" >"$t/$1"; }
  mk da-a/src/main/java/Good.java "$good"
  mk da-a/src/main/java/Bad.java 'class Bad {}'
  mk da-a/src/main/java/NoUrl.java '/* Licensed to the Apache Software Foundation */'
  mk da-a/src/main/java/OnlyUrl.java '/* http://www.apache.org/licenses/LICENSE-2.0 */'
  mk da-a/src/main/java/Late.java "$(printf 'class Late {}\n%.0s' $(seq 1 40); printf '%s\n' "$good")"
  : >"$t/da-a/src/main/java/Empty.java"
  mk da-a/src/main/kotlin/Bad.kt 'fun main() {}'
  mk da-a/build/Gen.java 'class Gen {}'
  mk da-a/src/main/generated/G.java 'class G {}'
  mk da-a/.mvn/wrapper/MavenWrapperDownloader.java 'class W {}'
  mk new-ui/frontend/src/Good.tsx "$good"
  mk new-ui/frontend/src/Bad.ts 'export {}'
  mk new-ui/frontend/src/vite-env.d.ts '/// <reference types="vite/client" />'
  mk new-ui/frontend/vite.config.ts 'export default {}'
  mk new-ui/frontend/node_modules/x/y.ts 'export {}'
  mk new-ui/frontend/build/a.ts 'export {}'
  mk new-ui/src/main/java/App.java 'class App {}'

  local expected got
  expected='da-a/src/main/java/Bad.java
da-a/src/main/java/Empty.java
da-a/src/main/java/Late.java
da-a/src/main/java/NoUrl.java
da-a/src/main/java/OnlyUrl.java
da-a/src/main/kotlin/Bad.kt
new-ui/frontend/src/Bad.ts
new-ui/frontend/vite.config.ts
new-ui/src/main/java/App.java'
  got="$(scan "$t" new-ui | grep -v '^TOTAL ' | sort -u)"
  if [ "$got" != "$expected" ]; then
    echo "SELF-TEST FAIL: flagged set differs." >&2
    printf 'expected:\n%s\ngot:\n%s\n' "$expected" "$got" >&2
    failed=$((failed + 1))
  fi

  # No UI: passes with a note; --require-ui fails.
  rm -rf "$t/new-ui"
  rm -f "$t"/da-a/src/main/java/{Bad,Empty,Late,NoUrl,OnlyUrl}.java "$t/da-a/src/main/kotlin/Bad.kt"
  if ! (cd "$t" && check_tree "$t" new-ui 0 >/dev/null 2>&1); then
    echo "SELF-TEST FAIL: a clean tree without the UI must pass." >&2
    failed=$((failed + 1))
  fi
  if (cd "$t" && check_tree "$t" new-ui 1 >/dev/null 2>&1); then
    echo "SELF-TEST FAIL: --require-ui must fail without the UI." >&2
    failed=$((failed + 1))
  fi
  local note
  note="$(cd "$t" && check_tree "$t" new-ui 0 2>&1)" || true
  if ! printf '%s' "$note" | grep -qF 'UI checkout (new-ui) is not present'; then
    echo "SELF-TEST FAIL: the skipped UI must be announced." >&2
    failed=$((failed + 1))
  fi

  if [ "$failed" -ne 0 ]; then
    echo "check-license-headers: self-test FAILED ($failed case(s))" >&2
    return 2
  fi
  echo "check-license-headers: self-test OK"
}

main() {
  local require_ui=0 arg
  for arg in "$@"; do
    case "$arg" in
      --require-ui) require_ui=1 ;;
      --self-test) self_test; return $? ;;
      -h | --help) sed -n '/^# Apache 2.0 license-header enforcement/,/^# Exit:/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; return 0 ;;
      *) echo "check-license-headers: unknown argument: $arg" >&2; return 2 ;;
    esac
  done
  local root
  root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
  cd "$root"
  check_tree "$root" "${DLAP_UI_DIR:-new-ui}" "$require_ui"
}

main "$@"
