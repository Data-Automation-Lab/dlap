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
# Runs Semgrep Community Edition the same way on a laptop and in CI (work package CI-02b).
#
#   bash scripts/ci-guards/semgrep/run.sh test         prove the rules: every rule in .semgrep.yml
#                                                      against its positive and negative fixtures
#   bash scripts/ci-guards/semgrep/run.sh scan         scan the repository with .semgrep.yml;
#                                                      ERROR findings fail, WARNING findings are listed
#   bash scripts/ci-guards/semgrep/run.sh community    advisory: the community packs p/java,
#                                                      p/owasp-top-ten and p/github-actions; always
#                                                      exits 0, needs the Semgrep registry
#
# Needs Docker, or a semgrep binary named by SEMGREP_BIN (then no container is started).
# Environment:
#   SEMGREP_BIN    path of a semgrep executable; when set, Docker is not used
#   SEMGREP_IMAGE  the container image; the default is pinned by release and digest (below). To
#                  bump it, change the default here and nowhere else.
#   SEMGREP_OUT    directory for the JSON results (default: a fresh temporary directory)
# Exit:   0 = pass; 1 = ERROR findings, a failed fixture or a blind scan; 2 = Semgrep could not run.
#
# Metrics and version checks are off, and scan and test use no registry rules: the run depends on
# nothing but this repository and the pinned image, so the same commit gives the same result.

set -uo pipefail

SEMGREP_IMAGE_DEFAULT="semgrep/semgrep:1.180.0@sha256:529ee8a277ec8adc5b534d7c74eea0a47e9de21d62852b6ba7ac6ba9566845c3"
SEMGREP_IMAGE="${SEMGREP_IMAGE:-$SEMGREP_IMAGE_DEFAULT}"
FIXTURES="scripts/ci-guards/semgrep/fixtures"

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../../.." && pwd)"
cd "$root" || exit 2

# shellcheck source=../scan/lib.sh
source "$root/scripts/ci-guards/scan/lib.sh"

PY="$(find_python || true)"
[ -n "$PY" ] || { echo "semgrep: python3 is required" >&2; exit 2; }

out="${SEMGREP_OUT:-}"
if [ -z "$out" ]; then
  out="$(mktemp -d)"
  trap 'rm -rf "$out"' EXIT
fi
mkdir -p "$out"

# stage <full|fixtures>: print the directory Semgrep may see. "full" is the working tree as a git-aware
# scan reads it (see stage_tree in lib.sh); "fixtures" is just the rules file and the fixture tree.
stage() {
  local tree="$out/tree"
  if [ "$1" = "fixtures" ]; then
    rm -rf "$tree" && mkdir -p "$tree/$FIXTURES"
    cp .semgrep.yml "$tree/"
    cp -R "$FIXTURES/." "$tree/$FIXTURES/"
    printf '%s' "$tree"
  else
    stage_tree "$root" "$tree"
  fi
}

# semgrep_run <tree> <semgrep args...>: run Semgrep with <tree> as the working directory; the JSON
# goes to $out/result.json.
semgrep_run() {
  local tree="$1"
  shift
  rm -f "$out/result.json"
  if [ -n "${SEMGREP_BIN:-}" ]; then
    (cd "$tree" && "$SEMGREP_BIN" "$@" --json --output "$(native "$out")/result.json" \
      --metrics=off --disable-version-check)
    return $?
  fi
  command -v docker >/dev/null 2>&1 || { echo "semgrep: neither SEMGREP_BIN nor docker is available" >&2; return 2; }
  ensure_image "$SEMGREP_IMAGE" || return 2
  docker run --rm -v "$(native "$tree"):/src:ro" -v "$(native "$out"):/out" -w /src \
    "$SEMGREP_IMAGE" semgrep "$@" --json --output /out/result.json \
    --metrics=off --disable-version-check
}

report() { "$PY" "$(native "$here/report.py")" "$@"; }

mode="${1:-}"
case "$mode" in
  test)
    tree="$(stage fixtures)"
    semgrep_run "$tree" scan --config .semgrep.yml --quiet "$FIXTURES"
    rc=$?
    [ "$rc" -le 1 ] || { echo "semgrep: exited with $rc" >&2; exit 2; }
    report fixtures "$(native "$out")/result.json" "$FIXTURES" .semgrep.yml
    ;;
  scan)
    tree="$(stage full)"
    semgrep_run "$tree" scan --config .semgrep.yml --quiet --exclude "$FIXTURES" .
    rc=$?
    [ "$rc" -le 1 ] || { echo "semgrep: exited with $rc" >&2; exit 2; }
    report summary "$(native "$out")/result.json" --label "project rules"
    ;;
  community)
    tree="$(stage full)"
    semgrep_run "$tree" scan --config p/java --config p/owasp-top-ten --config p/github-actions --quiet \
      --exclude "$FIXTURES" .
    rc=$?
    if [ "$rc" -gt 1 ]; then
      echo "::warning title=Semgrep community packs::semgrep exited with $rc (advisory step, not a failure)"
      exit 0
    fi
    report summary "$(native "$out")/result.json" --label "community packs, advisory" || true
    exit 0
    ;;
  *)
    echo "usage: run.sh test|scan|community" >&2
    exit 2
    ;;
esac
