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
# Guard: the coverage specs are the independent denominator of the fixture coverage score
# (SPEC-01, hardening critic item 6), so a parser author cannot move the goalposts and score
# against them in one change. No single commit may change a coverage spec
#
#   da-log-fixtures/src/main/resources/dlap-fixtures/<family>/coverage-spec.yaml
#
# together with a parser-side file:
#
#   da-logingest/src/**                                    parsers, transports, mapping YAML, their tests
#   da-discovery/src/main/java/com/dlap/discovery/log/**   the discovery log analyzers
#   da-log-fixtures/src/main/java/com/dlap/fixtures/gen/** fixture generators
#   da-log-fixtures/src/main/resources/dlap-fixtures/<family>/**
#                                                          every other file of a family: cases,
#                                                          catalog.yaml, NOTICE.md
#
# A spec change is a commit of its own. Every non-merge commit in DLAP_CI_BASE_SHA..HEAD is
# checked; merge commits are skipped, because merging a spec-only branch with a parser branch is
# how the two legitimately meet. Package ownership (parser packages never own a coverage spec)
# is enforced by the integrator; this hook enforces the commit boundary.
#
#   DLAP_CI_BASE_SHA=feature/deployment bash scripts/ci-guards/post-test/coverage-spec-lock.sh
#   bash scripts/ci-guards/post-test/coverage-spec-lock.sh --self-test
#
# Exit 0 pass, 1 violation, 2 usage error, 77 skip (no usable base commit).
set -uo pipefail

SPEC_RE='^da-log-fixtures/src/main/resources/dlap-fixtures/[^/]+/coverage-spec\.yaml$'
PARSER_RE='^(da-logingest/src/|da-discovery/src/main/java/com/dlap/discovery/log/|da-log-fixtures/src/main/java/com/dlap/fixtures/gen/|da-log-fixtures/src/main/resources/dlap-fixtures/[^/]+/)'

check() {
  local root base commits c files specs parser violations=0 checked=0
  root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
  cd "$root" || exit 2

  base="${DLAP_CI_BASE_SHA:-}"
  if [ -z "$base" ] || [ -z "${base//0/}" ]; then
    echo "coverage-spec-lock: no base commit for event '${DLAP_CI_EVENT:-local}'; skipping"
    exit 77
  fi
  if ! git cat-file -e "${base}^{commit}" 2>/dev/null; then
    echo "coverage-spec-lock: base '$base' is not a known commit; skipping"
    exit 77
  fi

  commits="$(git rev-list --no-merges --reverse "${base}..HEAD")" || exit 2
  for c in $commits; do
    checked=$((checked + 1))
    files="$(git -c core.quotepath=off diff-tree --root --no-commit-id --name-only -r --no-renames "$c")"
    specs="$(printf '%s\n' "$files" | grep -E "$SPEC_RE" || true)"
    [ -n "$specs" ] || continue
    parser="$(printf '%s\n' "$files" | grep -E "$PARSER_RE" | grep -vE "$SPEC_RE" || true)"
    [ -n "$parser" ] || continue
    violations=$((violations + 1))
    echo "coverage-spec-lock: commit $(git log -1 --format='%h %s' "$c") changes a coverage spec together with parser-side files:" >&2
    printf '%s\n' "$specs" | sed 's/^/  spec:   /' >&2
    printf '%s\n' "$parser" | head -n 10 | sed 's/^/  parser: /' >&2
    if [ "$(printf '%s\n' "$parser" | wc -l)" -gt 10 ]; then
      echo "  parser: ... and more" >&2
    fi
  done

  if [ "$violations" -gt 0 ]; then
    echo "coverage-spec-lock: $violations commit(s) mix a coverage spec with parser code or graded cases." >&2
    echo "Split the spec change into a commit of its own (an orchestrator spec package); parser packages never edit it." >&2
    exit 1
  fi
  echo "coverage-spec-lock: $checked commit(s) since $base checked; no coverage spec changed together with parser code"
  exit 0
}

self_test() {
  local script tmp repo hook base trunk f passed=0 failed=0 out rc
  script="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
  tmp="$(mktemp -d)"
  # shellcheck disable=SC2064
  trap "rm -rf '$tmp'" EXIT
  repo="$tmp/repo"
  mkdir -p "$repo/scripts/ci-guards/post-test"
  git -C "$repo" init -q
  git -C "$repo" config user.email selftest@example.invalid
  git -C "$repo" config user.name selftest
  git -C "$repo" config commit.gpgsign false
  git -C "$repo" config core.autocrlf false
  cp "$script" "$repo/scripts/ci-guards/post-test/coverage-spec-lock.sh"
  hook="$repo/scripts/ci-guards/post-test/coverage-spec-lock.sh"

  local fx="da-log-fixtures/src/main/resources/dlap-fixtures"
  local spec="$fx/gcpaudit/coverage-spec.yaml"
  local spec2="$fx/bqjobs/coverage-spec.yaml"
  local parser="da-logingest/src/main/java/com/dlap/logingest/parse/gcpaudit/GcpAuditParser.java"
  local mapping="da-logingest/src/main/resources/dlap/log-formats/gcpaudit/json/auditlog-2025.yaml"
  local parser_test="da-logingest/src/test/java/com/dlap/logingest/parse/gcpaudit/GcpAuditParserTest.java"
  local fixture_case="$fx/gcpaudit/json/auditlog-2025/objects-get/meta.yaml"
  local catalog="$fx/gcpaudit/catalog.yaml"
  local generator="da-log-fixtures/src/main/java/com/dlap/fixtures/gen/gcpaudit/GcpAuditGenerator.java"
  local analyzer="da-discovery/src/main/java/com/dlap/discovery/log/service/gcp/GcpLogAnalyzer.java"
  local scorer="da-log-fixtures/src/main/java/com/dlap/fixtures/CoverageReport.java"
  local corpus_readme="$fx/README.md"

  put() { mkdir -p "$(dirname "$repo/$1")" && printf '%s\n' "$2" >>"$repo/$1"; }
  commit() { git -C "$repo" add -A && git -C "$repo" commit -q -m "$1"; }
  reset() { git -C "$repo" checkout -q "$trunk" && git -C "$repo" reset -q --hard "$base"; }
  expect() {
    # expect <description> <wanted exit code> <base> [text the output must contain]
    out="$(env -u DLAP_CI_EVENT DLAP_CI_BASE_SHA="$3" bash "$hook" 2>&1)"
    rc=$?
    if [ "$rc" -ne "$2" ]; then
      failed=$((failed + 1)); echo "  FAIL  $1 (exit $rc, wanted $2)" >&2; printf '%s\n' "$out" | sed 's/^/        | /' >&2
    elif [ -n "${4:-}" ] && ! printf '%s\n' "$out" | grep -qF -- "$4"; then
      failed=$((failed + 1)); echo "  FAIL  $1 (output lacks '$4')" >&2; printf '%s\n' "$out" | sed 's/^/        | /' >&2
    else
      passed=$((passed + 1)); echo "  ok    $1"
    fi
  }

  echo "coverage-spec-lock.sh self-test"
  for f in "$spec" "$spec2" "$parser" "$mapping" "$parser_test" "$fixture_case" "$catalog" "$generator" \
           "$analyzer" "$scorer" "$corpus_readme" docs/notes.md; do
    put "$f" "v0"
  done
  commit "base"
  base="$(git -C "$repo" rev-parse HEAD)"
  trunk="$(git -C "$repo" symbolic-ref --short HEAD)"

  put "$spec" "v1"; put "$spec2" "v1"; commit "spec: revise two coverage specs"
  expect "a spec-only commit passes" 0 "$base" "1 commit(s)"
  reset

  put "$parser" "v1"; put "$mapping" "v1"; put "$fixture_case" "v1"; put "$catalog" "v1"; commit "feat: parser and cases"
  expect "parser and fixture changes without a spec change pass" 0 "$base"
  reset

  put "$spec" "v1"; put "$parser" "v1"; commit "feat: parser that also edits its spec"
  expect "spec plus parser code in one commit fails" 1 "$base" "feat: parser that also edits its spec"
  expect "the failure names the spec file" 1 "$base" "spec:   $spec"
  expect "the failure names the parser file" 1 "$base" "parser: $parser"
  reset

  for f in "$fixture_case" "$catalog" "$mapping" "$parser_test" "$generator" "$analyzer"; do
    put "$spec" "v1"; put "$f" "v1"; commit "mix $f"
    expect "spec plus $f fails" 1 "$base" "parser: $f"
    reset
  done

  put "$spec" "v1"; put "$scorer" "v1"; put "$corpus_readme" "v1"; put docs/notes.md "v1"; commit "docs: spec with scorer and docs"
  expect "spec plus scorer, corpus README and docs passes (not parser-side)" 0 "$base"
  reset

  git -C "$repo" rm -q "$spec"; put "$parser" "v1"; commit "feat: drop the spec and change the parser"
  expect "deleting a spec together with parser code fails" 1 "$base" "spec:   $spec"
  reset

  put "$spec" "v1"; commit "spec: revise"; put "$parser" "v1"; commit "feat: parser"
  expect "spec and parser in separate commits pass" 0 "$base" "2 commit(s)"
  reset

  put "$spec" "v1"; put "$parser" "v1"; commit "feat: mixed"; put docs/notes.md "v2"; commit "docs: clean"
  expect "an earlier mixed commit is still reported when later ones are clean" 1 "$base" "feat: mixed"
  reset

  git -C "$repo" checkout -q -b spec-branch; put "$spec" "v1"; commit "spec: revise"
  git -C "$repo" checkout -q -b parser-branch "$base"; put "$parser" "v1"; commit "feat: parser"
  git -C "$repo" merge -q --no-edit --no-ff spec-branch -m "merge: spec into parser"
  expect "a merge commit that joins a spec branch and a parser branch is skipped" 0 "$base" "2 commit(s)"
  git -C "$repo" checkout -q --detach "$base"; git -C "$repo" branch -q -D spec-branch parser-branch
  reset

  put docs/coverage-spec.yaml "v1"; put "$parser" "v1"; commit "docs: a file that is only named like a spec"
  expect "a coverage-spec.yaml outside the corpus is not a spec" 0 "$base"
  reset

  put "$fx/gcpaudit/nested/coverage-spec.yaml" "v1"; put "$parser" "v1"; commit "feat: a nested file named like a spec"
  expect "only <family>/coverage-spec.yaml is a spec" 0 "$base"
  reset

  expect "no base commit skips" 77 ""
  expect "an all-zero base skips" 77 "0000000000000000000000000000000000000000"
  expect "an unknown base skips" 77 "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef"

  out="$(bash "$hook" --bogus 2>&1)"; rc=$?
  if [ "$rc" -eq 2 ]; then passed=$((passed + 1)); echo "  ok    an unknown argument is a usage error"
  else failed=$((failed + 1)); echo "  FAIL  an unknown argument is a usage error (exit $rc)" >&2; fi

  echo "coverage-spec-lock.sh self-test: $passed passed, $failed failed"
  [ "$failed" -eq 0 ]
}

case "${1:-}" in
  "") check ;;
  --self-test) self_test ;;
  *) echo "usage: coverage-spec-lock.sh [--self-test]" >&2; exit 2 ;;
esac
