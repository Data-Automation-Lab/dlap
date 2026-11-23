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
# Trivy the same way on a laptop and in CI (work package CI-02b).
#
#   bash scripts/ci-guards/scan/trivy.sh fs            vulnerabilities in lockfiles and misconfigurations in
#                                                       Dockerfiles, compose, Terraform and Kubernetes files
#   bash scripts/ci-guards/scan/trivy.sh image NAME     vulnerabilities in a built Docker image (OS packages
#                                                       and the jars inside it)
#
# Only HIGH and CRITICAL findings that have a fix are reported (--ignore-unfixed): a vulnerability nobody
# can patch is not a change anyone can make. Secrets are not scanned here; the Secret scan job owns that
# (gitleaks, CI-02a). A finding that is wrong for this project goes in .github/trivy-ignore.yaml with a
# statement and an expiry date; scripts/ci-guards/pre/scan-suppressions.sh rejects an entry without either.
# The pass or fail decision is made by scripts/ci-guards/scan/summary.py, not by Trivy's exit code, so the
# same policy is tested offline (summary.py --self-test).
#
# Needs Docker. Trivy downloads its vulnerability and Java databases on first use (about 1 GB, from
# mirror.gcr.io and ghcr.io); TRIVY_CACHE_DIR keeps them between runs, and CI caches that directory.
# Environment:
#   TRIVY_IMAGE      container image; the default is pinned by release and digest below, the one place to bump it
#   TRIVY_CACHE_DIR  host directory for the databases (default: a docker volume named dlap-trivy-cache)
#   TRIVY_OUT        directory for the JSON report (default: a fresh temporary directory)
#   TRIVY_TIMEOUT    limit for one Trivy run (default 30m, passed as --timeout). Trivy's own default of 5m
#                    is too short for the da-discovery image on a cold cache ("semaphore acquire: context
#                    deadline exceeded", exit 2, Q-328); with 30m it finished in 3m53s with 0 findings.
# Exit:   0 = pass; 1 = findings; 2 = Trivy or the report is unusable.

set -uo pipefail

TRIVY_IMAGE_DEFAULT="ghcr.io/aquasecurity/trivy:0.75.0@sha256:af6acf9a6b85dfe389a1941505c0ce9efef52a4719635e1a962f022a3d855daa"
TRIVY_IMAGE="${TRIVY_IMAGE:-$TRIVY_IMAGE_DEFAULT}"
TRIVY_TIMEOUT="${TRIVY_TIMEOUT:-30m}"
IGNORE_FILE=".github/trivy-ignore.yaml"

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../../.." && pwd)"
cd "$root" || exit 2
# shellcheck source=lib.sh
source "$here/lib.sh"

PY="$(find_python || true)"
[ -n "$PY" ] || { echo "trivy: python3 is required" >&2; exit 2; }
command -v docker >/dev/null 2>&1 || { echo "trivy: docker is required" >&2; exit 2; }
ensure_image "$TRIVY_IMAGE" || exit 2

out="${TRIVY_OUT:-}"
if [ -z "$out" ]; then
  out="$(mktemp -d)"
  trap 'rm -rf "$out"' EXIT
fi
mkdir -p "$out"

cache="${TRIVY_CACHE_DIR:-dlap-trivy-cache}"
case "$cache" in
  /*|?:*|./*) mkdir -p "$cache"; cache_arg="$(native "$cache")" ;;   # a directory
  *) cache_arg="$cache" ;;                                            # a docker volume name
esac

user_args=()
if [ "$(uname -s)" = "Linux" ]; then
  # Keep files the container writes (the cache, the report) owned by the caller, so the CI cache step can read them.
  user_args=(-u "$(id -u):$(id -g)" -e HOME=/tmp)
fi

# trivy_run <extra docker args> -- <trivy args...>; the report lands in $out/trivy.json.
trivy_run() {
  local docker_args=()
  while [ "$1" != "--" ]; do docker_args+=("$1"); shift; done
  shift
  rm -f "$out/trivy.json"
  docker run --rm "${user_args[@]}" -v "$cache_arg:/cache" -v "$(native "$out"):/out" "${docker_args[@]}" \
    "$TRIVY_IMAGE" "$@" --cache-dir /cache --quiet --no-progress --skip-version-check --timeout "$TRIVY_TIMEOUT" \
    --severity HIGH,CRITICAL --ignore-unfixed --format json --output /out/trivy.json
}

report() { "$PY" "$(native "$here/summary.py")" trivy "$(native "$out")/trivy.json" --label "$1"; }

mode="${1:-}"
case "$mode" in
  fs)
    tree="$(stage_tree "$root" "$out/tree")"
    ignore=()
    [ -f "$IGNORE_FILE" ] && ignore=(--ignorefile "/src/$IGNORE_FILE")
    trivy_run -v "$(native "$tree"):/src:ro" -- \
      fs --scanners vuln,misconfig "${ignore[@]}" /src
    rc=$?
    [ "$rc" -eq 0 ] || { echo "trivy: exited with $rc" >&2; exit 2; }
    report "files"
    ;;
  image)
    name="${2:-}"
    [ -n "$name" ] || { echo "usage: trivy.sh image NAME[:TAG]" >&2; exit 2; }
    docker image inspect "$name" >/dev/null 2>&1 || { echo "trivy: no local image $name" >&2; exit 2; }
    mkdir -p "$out/img"
    docker save "$name" -o "$(native "$out")/img/image.tar" || { echo "trivy: docker save failed" >&2; exit 2; }
    # The ignore file is a volume for docker and a flag for trivy.
    docker_extra=(-v "$(native "$out")/img:/img:ro")
    trivy_extra=(image --scanners vuln --input /img/image.tar)
    if [ -f "$IGNORE_FILE" ]; then
      docker_extra+=(-v "$(native "$root/$IGNORE_FILE"):/ignore.yaml:ro")
      trivy_extra+=(--ignorefile /ignore.yaml)
    fi
    trivy_run "${docker_extra[@]}" -- "${trivy_extra[@]}"
    rc=$?
    [ "$rc" -eq 0 ] || { echo "trivy: exited with $rc" >&2; exit 2; }
    report "image $name"
    ;;
  *)
    echo "usage: trivy.sh fs | image NAME[:TAG]" >&2
    exit 2
    ;;
esac
