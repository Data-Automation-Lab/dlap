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
# OWASP Dependency-Check the same way on a laptop and in CI (work package CI-02b).
#
#   bash scripts/ci-guards/scan/odc.sh [--build]
#   bash scripts/ci-guards/scan/odc.sh --list        print the jars a scan would read, and exit
#   bash scripts/ci-guards/scan/odc.sh --self-test   prove the jar selection on a scratch tree, offline
#
# Scans the Spring Boot jar of every service, <module>/build/libs/<module>.jar when it is a boot jar (it
# has BOOT-INF/), which is what ships, including the libraries nested inside it. The -plain jars, the
# library jars of da-protos and da-llm-client and the da-log-fixtures test-fixture jar are not scanned:
# they ship only inside a boot jar or not at all (Q-326). --build runs "gradlew bootJar -x test" first;
# without it the jars must exist. A finding with a CVSS base score of 7.0 or higher fails (ODC --failOnCVSS 7, exit 15, and the
# same bound again in scripts/ci-guards/scan/summary.py). A finding that is wrong for this project goes in
# .github/dependency-check-suppressions.xml with notes and an expiry date;
# scripts/ci-guards/pre/scan-suppressions.sh rejects an entry without either.
#
# Data: the NVD vulnerability data comes from the Dependency-Check project's own daily mirror of the NVD
# (no NVD API key needed; set NVD_API_KEY to use the NVD API directly instead). The database is rebuilt
# from the mirror on each run, which measured 76 seconds on a laptop. The Maven Central and OSS Index
# analyzers are off: the first needs search.maven.org, which timed out from the build host, and the
# second needs a credential; the Jar analyzer reads the coordinates from the pom data inside each jar.
#
# Needs Docker, Java 21 for --build, python3. Environment:
#   ODC_IMAGE      container image; the default is pinned by release and digest below, the one place to bump it
#   ODC_DATA       host directory for the database (default on Linux: a temporary directory; elsewhere a
#                  docker volume named dlap-odc-data)
#   ODC_OUT        directory for the JSON and HTML reports (default: build/reports/dependency-check)
#   NVD_API_KEY    optional NVD API key; switches the update from the mirror to the NVD API
#   DLAP_GRADLE_ARGS  extra Gradle arguments for --build
# Exit:   0 = pass; 1 = findings at or above the bound; 2 = scanner or report unusable (or a failed
#         self-test).

set -uo pipefail

ODC_IMAGE_DEFAULT="owasp/dependency-check:13.0.0@sha256:0e8f69ad5b00d62000937f4e57189812343f6903d9c83ee712012d51542a020a"
ODC_IMAGE="${ODC_IMAGE:-$ODC_IMAGE_DEFAULT}"
NVD_MIRROR='https://dependency-check.github.io/DependencyCheck_Builder/nvd_cache/nvdcve-{0}.json.gz'
BOUND="7.0"
SUPPRESSIONS=".github/dependency-check-suppressions.xml"
DEFAULT_GRADLE_ARGS="--no-daemon --console=plain --no-watch-fs --max-workers=2"

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../../.." && pwd)"
cd "$root" || exit 2
# shellcheck source=lib.sh
source "$here/lib.sh"

PY="$(find_python || true)"
[ -n "$PY" ] || { echo "odc: python3 is required" >&2; exit 2; }

# boot_jars <tree>: the jars to scan, relative to <tree>, one per line: <module>/build/libs/<module>.jar of
# each da-* module, and only when the jar is a Spring Boot jar (BOOT-INF/ inside).
boot_jars() {
  local tree="$1" dir module jar
  for dir in "$tree"/da-*/; do
    module="$(basename "$dir")"
    jar="$module/build/libs/$module.jar"
    [ -f "$tree/$jar" ] || continue
    if "$PY" -I - "$(native "$tree/$jar")" <<'EOF'
import sys
import zipfile

try:
    with zipfile.ZipFile(sys.argv[1]) as archive:
        sys.exit(0 if any(name.startswith("BOOT-INF/") for name in archive.namelist()) else 1)
except (OSError, zipfile.BadZipFile):
    sys.exit(1)
EOF
    then
      printf '%s\n' "$jar"
    fi
  done
}

self_test() {
  local tmp fails=0 got want
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' RETURN
  # mkjar <path> <entry>: a zip with one entry.
  mkjar() {
    mkdir -p "$(dirname "$tmp/t/$1")"
    "$PY" -I -c 'import sys, zipfile; zipfile.ZipFile(sys.argv[1], "w").writestr(sys.argv[2], "x")' \
      "$(native "$tmp/t/$1")" "$2"
  }
  mkjar da-a/build/libs/da-a.jar BOOT-INF/lib/x.jar
  mkjar da-a/build/libs/da-a-plain.jar com/dlap/A.class
  mkjar da-b/build/libs/da-b.jar BOOT-INF/classes/B.class
  mkjar da-lib/build/libs/da-lib-0.0.1-SNAPSHOT.jar com/dlap/L.class
  mkjar da-fx/build/libs/da-fx.jar fixtures/x.json
  mkjar da-fx/build/libs/da-fx-0.0.1-SNAPSHOT.jar fixtures/x.json
  mkdir -p "$tmp/t/da-c/build/libs" && echo "not a zip" >"$tmp/t/da-c/build/libs/da-c.jar"
  mkjar other/build/libs/other.jar BOOT-INF/lib/x.jar
  got="$(boot_jars "$tmp/t" | tr '\n' ' ')"
  want="da-a/build/libs/da-a.jar da-b/build/libs/da-b.jar "
  if [ "$got" = "$want" ]; then
    echo "  ok    only <module>/build/libs/<module>.jar boot jars are selected"
  else
    echo "  FAIL  jar selection: got '$got', wanted '$want'" >&2; fails=$((fails + 1))
  fi
  rm -rf "$tmp/t" && mkdir -p "$tmp/t"
  got="$(boot_jars "$tmp/t")"
  if [ -z "$got" ]; then echo "  ok    no module, no jar"; else echo "  FAIL  empty tree selected '$got'" >&2; fails=$((fails + 1)); fi
  if [ "$fails" -gt 0 ]; then echo "odc: self-test FAILED ($fails)" >&2; return 2; fi
  echo "odc: self-test OK"
}

case "${1:-}" in
  --self-test) self_test; exit $? ;;
  --list) boot_jars "$root"; exit 0 ;;
esac

command -v docker >/dev/null 2>&1 || { echo "odc: docker is required" >&2; exit 2; }
ensure_image "$ODC_IMAGE" || exit 2

if [ "${1:-}" = "--build" ]; then
  echo "odc: building the Spring Boot jars (gradlew bootJar -x test)"
  # shellcheck disable=SC2086
  if ! bash "$root/gradlew" -p "$root" bootJar -x test ${DLAP_GRADLE_ARGS:-$DEFAULT_GRADLE_ARGS}; then
    echo "odc: ERROR: the jar build failed (is JAVA_HOME a JDK 21?)" >&2
    exit 2
  fi
elif [ -n "${1:-}" ]; then
  echo "usage: odc.sh [--build | --list | --self-test]" >&2
  exit 2
fi

jars=()
while IFS= read -r jar; do
  [ -n "$jar" ] && jars+=("$jar")
done < <(boot_jars "$root")
if [ "${#jars[@]}" -eq 0 ]; then
  echo "odc: ERROR: no Spring Boot jar <module>/build/libs/<module>.jar to scan (run with --build)" >&2
  exit 2
fi
echo "odc: ${#jars[@]} boot jar(s) to scan: ${jars[*]}"
scan_args=()
for jar in "${jars[@]}"; do scan_args+=(--scan "/src/$jar"); done

out="${ODC_OUT:-$root/build/reports/dependency-check}"
rm -rf "$out" && mkdir -p "$out"

data="${ODC_DATA:-}"
user_args=()
if [ "$(uname -s)" = "Linux" ]; then
  [ -n "$data" ] || data="$(mktemp -d)"
  mkdir -p "$data"
  # The container user must own what it writes; the data directory is the runner's.
  user_args=(-u "$(id -u):$(id -g)")
fi
[ -n "$data" ] || data="dlap-odc-data"
case "$data" in
  /*|?:*|./*) data_arg="$(native "$data")" ;;
  *) data_arg="$data" ;;
esac

# odc_run <extra args...>: the container with the repository read-only, the database and the report mounted.
odc_run() {
  docker run --rm "${user_args[@]}" \
    -v "$data_arg:/usr/share/dependency-check/data" \
    -v "$(native "$root"):/src:ro" -v "$(native "$out"):/report" \
    "$ODC_IMAGE" "$@"
}

echo "odc: updating the vulnerability data"
update_args=(--updateonly)
if [ -n "${NVD_API_KEY:-}" ]; then
  update_args+=(--nvdApiKey "$NVD_API_KEY")
else
  update_args+=(--nvdDatafeed "$NVD_MIRROR")
fi
if ! odc_run "${update_args[@]}" >"$out/update.log" 2>&1; then
  echo "odc: the first update failed, retrying once" >&2
  if ! odc_run "${update_args[@]}" >>"$out/update.log" 2>&1; then
    echo "odc: ERROR: cannot update the vulnerability data" >&2
    tail -n 20 "$out/update.log" >&2
    exit 2
  fi
fi

suppress=()
[ -f "$SUPPRESSIONS" ] && suppress=(--suppression "/src/$SUPPRESSIONS")

echo "odc: scanning"
odc_run "${scan_args[@]}" --noupdate --disableCentral --disableOssIndex \
  --failOnCVSS "${BOUND%.*}" "${suppress[@]}" --format JSON --format HTML --out /report --project DLAP \
  >"$out/scan.log" 2>&1
rc=$?
# Dependency-Check exits 15 when a finding reaches --failOnCVSS; any other non-zero exit is a failure to
# scan (14, for example, is an unreadable suppression file, which must never read as a clean pass).
if { [ "$rc" -ne 0 ] && [ "$rc" -ne 15 ]; } || [ ! -s "$out/dependency-check-report.json" ]; then
  echo "odc: ERROR: Dependency-Check failed (exit $rc)" >&2
  tail -n 30 "$out/scan.log" >&2
  exit 2
fi

"$PY" "$(native "$here/summary.py")" odc "$(native "$out")/dependency-check-report.json" --bound "$BOUND"
srx=$?
echo "odc: reports in $out (dependency-check-report.html, dependency-check-report.json)"
if [ "$srx" -eq 2 ]; then exit 2; fi
# Either source of truth failing fails the gate.
[ "$rc" -eq 15 ] && exit 1
exit "$srx"
