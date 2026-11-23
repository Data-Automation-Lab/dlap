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
# Guard: no em-dash (U+2014) or en-dash (U+2013) in the repository
# (CLAUDE.md section 11). Two modes:
#
#   default   Checks only the lines a change adds, so existing text is not a
#             failure. CI runs this mode. The base commit comes from
#             DLAP_CI_BASE_SHA (the pull request base or the push "before"
#             commit). Locally:
#               DLAP_CI_BASE_SHA=feature/deployment bash scripts/ci-guards/pre/no-dashes.sh
#             Exit 77 (skip) when no usable base is given.
#
#   --all     Checks every tracked file (the new-ui gitlink is a separate
#             repository and is not entered). A file may keep dashes only if
#             scripts/ci-guards/pre/no-dashes.allowlist lists it with a ceiling
#             (the number of lines that hold a dash) and a reason. A listed file
#             fails when it holds more lines than its ceiling, so a kept dash can
#             never spread. Needs no base commit.
#               bash scripts/ci-guards/pre/no-dashes.sh --all
#
# Lockfiles, source maps, minified bundles and binary image/archive/font files are
# excluded in both modes.
#
# Allowlist line format (pipe separated, '#' starts a comment):
#   path|max|category|reason
# Categories: append-only (history files that keep their past text), checksummed
# (a shipped migration or upgrade delta whose checksum must not change), data (a
# fixture value or parser input that is the thing under test).
set -uo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "$root" || exit 2

em=$'\xe2\x80\x94'
en=$'\xe2\x80\x93'

excludes=(':(exclude)*package-lock.json' ':(exclude)*.lock' ':(exclude)*.map' ':(exclude)*.min.js'
          ':(exclude)*.png' ':(exclude)*.jpg' ':(exclude)*.jpeg' ':(exclude)*.gif' ':(exclude)*.ico'
          ':(exclude)*.pdf' ':(exclude)*.jar' ':(exclude)*.zip' ':(exclude)*.gz' ':(exclude)*.woff'
          ':(exclude)*.woff2' ':(exclude)*.ttf' ':(exclude)*.eot' ':(exclude)*.class' ':(exclude)*.jks'
          ':(exclude)*.p12' ':(exclude)*.keystore' ':(exclude)*.webp')

mode="added"
case "${1:-}" in
  "") ;;
  --all) mode="all" ;;
  *) echo "no-dashes: unknown argument '$1' (use no argument or --all)" >&2; exit 2 ;;
esac

if [ "$mode" = "all" ]; then
  allowlist="$root/scripts/ci-guards/pre/no-dashes.allowlist"
  allow_rows=""
  if [ -f "$allowlist" ]; then
    n=0
    while IFS= read -r line || [ -n "$line" ]; do
      n=$((n + 1))
      line="${line%$'\r'}"
      case "$line" in ""|\#*) continue ;; esac
      IFS='|' read -r a_path a_max a_cat a_reason <<<"$line"
      case "$a_max" in ""|*[!0-9]*) echo "no-dashes: allowlist line $n: max must be a number" >&2; exit 2 ;; esac
      case "$a_cat" in
        append-only|checksummed|data) ;;
        *) echo "no-dashes: allowlist line $n: category must be append-only, checksummed or data" >&2; exit 2 ;;
      esac
      if [ -z "$a_path" ] || [ -z "$a_reason" ]; then
        echo "no-dashes: allowlist line $n: path and reason are required" >&2
        exit 2
      fi
      allow_rows="${allow_rows}${a_path}|${a_max}"$'\n'
    done <"$allowlist"
  fi

  report="$(
    git grep -a -c -F -e "$em" -e "$en" -- . "${excludes[@]}" 2>/dev/null \
      | awk -v allow="$allow_rows" '
          BEGIN {
            n = split(allow, rows, "\n")
            for (i = 1; i <= n; i++) {
              if (rows[i] == "") continue
              k = rows[i]; sub(/\|[0-9]+$/, "", k)
              m = rows[i]; sub(/^.*\|/, "", m)
              max[k] = m + 0
            }
          }
          {
            c = $0; sub(/^.*:/, "", c)
            f = $0; sub(/:[0-9]+$/, "", f)
            seen[f] = 1
            if (!(f in max))     { print "FAIL  " f ": " c " line(s) with a dash, not in the allowlist" }
            else if (c + 0 > max[f]) { print "FAIL  " f ": " c " line(s) with a dash, the allowlist ceiling is " max[f] }
          }
          END { for (f in max) if (!(f in seen)) print "NOTE  " f ": in the allowlist but holds no dash; remove the entry" }
        '
  )"

  fail=0
  while IFS= read -r r; do
    case "$r" in
      FAIL*) echo "  ${r#FAIL  }" >&2; fail=1 ;;
      NOTE*) echo "no-dashes: note: ${r#NOTE  }" >&2 ;;
    esac
  done <<<"$report"

  if [ "$fail" -ne 0 ]; then
    echo "no-dashes: em-dash or en-dash found (above). Use a comma, colon, period or a plain hyphen." >&2
    exit 1
  fi
  echo "no-dashes: OK (--all: no em-dash or en-dash outside the allowlist)"
  exit 0
fi

base="${DLAP_CI_BASE_SHA:-}"
if [ -z "$base" ] || [ -z "${base//0/}" ]; then
  echo "no-dashes: no base commit for event '${DLAP_CI_EVENT:-local}'; skipping"
  exit 77
fi
if ! git cat-file -e "${base}^{commit}" 2>/dev/null; then
  echo "no-dashes: base '$base' is not a known commit; skipping"
  exit 77
fi

hits="$(
  git diff -U0 --no-color --no-renames --diff-filter=AM "$base" HEAD -- . \
      ':(exclude)*package-lock.json' ':(exclude)*.lock' ':(exclude)*.map' ':(exclude)*.min.js' \
    | awk -v em="$em" -v en="$en" '
        /^\+\+\+ b\// { file = substr($0, 7); next }
        /^\+\+\+ /    { file = ""; next }
        /^@@/         { if (match($0, /\+[0-9]+/)) line = substr($0, RSTART + 1, RLENGTH - 1) + 0; next }
        /^\+/         { if (index($0, em) || index($0, en)) print file ":" line; line++ }
      '
)"

if [ -n "$hits" ]; then
  echo "no-dashes: em-dash or en-dash added at:" >&2
  echo "$hits" | sed 's/^/  /' >&2
  echo "Use a comma, colon, period or a plain hyphen instead." >&2
  exit 1
fi
echo "no-dashes: OK (no em-dash or en-dash on added lines since $base)"
