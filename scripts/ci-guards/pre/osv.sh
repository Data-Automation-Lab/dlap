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
# OSV-Scanner gate (work package CI-02b): known vulnerabilities in the dependencies that ship.
#
# What it scans
#   1. Every module's runtime classpath. The root build already resolves it for the licence gate
#      (generateLicenseReport, LIC-01), so this hook turns that report into a CycloneDX SBOM and
#      hands the SBOM to OSV-Scanner. No build file changes and no extra Gradle plugin.
#   2. Every tracked package-lock.json and gradle.lockfile, and new-ui/frontend/package-lock.json
#      when the UI submodule is checked out.
#
# Policy
#   A vulnerability whose CVSS base score is 7.0 or higher fails the gate (DLAP_OSV_FAIL_CVSS
#   changes the bound). Two kinds of finding are printed but never fail: a package that only a
#   development tool pulls in (the lockfile marks it "dev"), and an advisory that carries no score.
#   A finding that is wrong for this project is suppressed in .github/osv-scanner.toml with a reason
#   and an expiry date (IgnoredVulns); scripts/ci-guards/pre/scan-suppressions.sh rejects an entry
#   without either.
#
# Needs the network (api.osv.dev) and Docker, or an osv-scanner binary on the path (OSV_SCANNER),
# plus python3 and, for the Gradle part, Java 21 (JAVA_HOME). Runs in about two minutes after the
# licence report exists. Exit 77 (skip) when no scanner can run locally; in CI that is a failure.
#
# Usage:  scripts/ci-guards/pre/osv.sh                  scan, then apply the policy
#         scripts/ci-guards/pre/osv.sh --report FILE    apply the policy to an existing OSV JSON, offline
#         scripts/ci-guards/pre/osv.sh --self-test      prove the SBOM conversion and the policy, offline
# Environment:
#   OSV_SCANNER          osv-scanner executable; when unset and none is on the path, Docker is used
#   OSV_IMAGE            container image; the default is pinned by release and digest below, and is
#                        the one place to bump it
#   DLAP_OSV_FAIL_CVSS   score bound (default 7.0)
#   DLAP_GRADLE_ARGS     extra Gradle arguments for the licence report
# Exit:   0 = pass; 1 = vulnerabilities at or above the bound; 2 = unusable input or scanner error;
#         77 = skipped (locally only).

set -uo pipefail

OSV_IMAGE_DEFAULT="ghcr.io/google/osv-scanner:v2.6.0@sha256:afd838850ac1a0fcc15ff4a041dc9ba11123c3f0d2666217a5f0fcf9222b55fa"
OSV_IMAGE="${OSV_IMAGE:-$OSV_IMAGE_DEFAULT}"
FAIL_CVSS="${DLAP_OSV_FAIL_CVSS:-7.0}"
LICENSE_REPORT="build/reports/dependency-license/licenses.json"
OSV_CONFIG=".github/osv-scanner.toml"
DEFAULT_GRADLE_ARGS="--no-daemon --console=plain --no-watch-fs --no-configuration-cache --max-workers=2"

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../../.." && pwd)"
cd "$root" || exit 2

# Git Bash: paths handed to docker.exe or to a Windows python go through cygpath, and
# MSYS_NO_PATHCONV=1 keeps Git Bash from rewriting the container-side paths.
export MSYS_NO_PATHCONV=1
native() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

PY="$(command -v python3 || command -v python || true)"
[ -n "$PY" ] || { echo "osv: ERROR: python3 is required" >&2; exit 2; }

# sbom <licenses.json> <out.cdx.json>: CycloneDX 1.5 components, one per distinct group:artifact@version.
# Exit 2 when the report is unusable or suspiciously small (DLAP_OSV_MIN_COMPONENTS, default 100).
sbom() {
  "$PY" -I - "$@" <<'PYEOF'
import json
import os
import sys

src, dst = sys.argv[1], sys.argv[2]
minimum = int(os.environ.get("DLAP_OSV_MIN_COMPONENTS", "100"))
try:
    with open(src, encoding="utf-8") as handle:
        report = json.load(handle)
    deps = report["dependencies"]
except (OSError, ValueError, KeyError, TypeError) as exc:
    sys.stderr.write("osv: ERROR: %s is not a licence report: %s\n" % (src, exc))
    sys.exit(2)

seen, components = set(), []
for dep in deps:
    name, version = str(dep.get("moduleName", "")), str(dep.get("moduleVersion", "")).strip()
    if ":" not in name or not version or version.lower() == "unspecified":
        continue
    group, artifact = name.split(":", 1)
    if (group, artifact, version) in seen:
        continue
    seen.add((group, artifact, version))
    components.append({
        "type": "library", "group": group, "name": artifact, "version": version,
        "purl": "pkg:maven/%s/%s@%s" % (group, artifact, version),
    })
if len(components) < minimum:
    sys.stderr.write("osv: ERROR: only %d runtime components in %s (expected at least %d); the licence report looks wrong\n"
                     % (len(components), src, minimum))
    sys.exit(2)
bom = {"bomFormat": "CycloneDX", "specVersion": "1.5", "version": 1,
       "metadata": {"component": {"type": "application", "name": "dlap", "version": "0"}},
       "components": components}
with open(dst, "w", encoding="utf-8") as handle:
    json.dump(bom, handle, indent=1)
sys.stdout.write("osv: SBOM with %d runtime components\n" % len(components))
PYEOF
}

# policy <osv.json> <bound>: the gate. Exit 0 pass, 1 findings at or above the bound, 2 unusable input.
policy() {
  "$PY" -I - "$@" <<'PYEOF'
import json
import os
import sys

path, bound = sys.argv[1], float(sys.argv[2])
try:
    with open(path, encoding="utf-8") as handle:
        data = json.load(handle)
    results = data.get("results", [])
except (OSError, ValueError, AttributeError) as exc:
    sys.stderr.write("osv: ERROR: cannot read the OSV result %s: %s\n" % (path, exc))
    sys.exit(2)

blocking, dev_high, unscored, below = [], [], [], 0
for result in results:
    source = result.get("source", {})
    where = "runtime classpath (Gradle)" if source.get("type") == "sbom" else \
        str(source.get("path", "?")).replace("/repo/", "", 1)
    for pkg in result.get("packages", []):
        info = pkg.get("package", {})
        groups = pkg.get("dependency_groups") or []
        dev_only = bool(groups) and all(g == "dev" for g in groups)
        by_id = {v.get("id"): v for v in pkg.get("vulnerabilities", [])}
        for group in pkg.get("groups", []):
            ids = group.get("ids", [])
            cve = next((a for a in group.get("aliases", []) if a.startswith("CVE-")), ids[0] if ids else "?")
            summary = " ".join(str(by_id.get(ids[0], {}).get("summary", "")).split())[:90] if ids else ""
            try:
                score = float(group.get("max_severity", ""))
            except (TypeError, ValueError):
                score = None
            row = (score if score is not None else -1.0, info.get("name", "?"), info.get("version", "?"),
                   cve, summary, where)
            if score is None:
                unscored.append(row)
            elif score < bound:
                below += 1
            elif dev_only:
                dev_high.append(row)
            else:
                blocking.append(row)

def show(title, rows, limit=60):
    if not rows:
        return
    print(title)
    for score, name, version, cve, summary, where in sorted(set(rows), key=lambda r: (-r[0], r[1], r[2], r[3]))[:limit]:
        print("  %4s  %s@%s  %s  %s  [%s]" % ("n/a" if score < 0 else "%.1f" % score, name, version, cve, summary, where))
    if len(set(rows)) > limit:
        print("  ... and %d more" % (len(set(rows)) - limit))

print("OSV-Scanner: %d advisory group(s) at CVSS %.1f or higher in shipped dependencies, %d in development-only "
      "packages, %d without a score, %d below the bound" % (len(set(blocking)), bound, len(set(dev_high)),
                                                            len(set(unscored)), below))
show("BLOCKING (shipped dependency, CVSS >= %.1f)" % bound, blocking)
show("REPORTED, not blocking (development-only package)", dev_high, 15)
show("REPORTED, not blocking (advisory has no CVSS score)", unscored, 15)

summary_file = os.environ.get("GITHUB_STEP_SUMMARY")
if summary_file:
    lines = ["### OSV-Scanner", "",
             "**%d** advisory group(s) at CVSS %.1f or higher in shipped dependencies (blocking), %d in "
             "development-only packages, %d without a score." % (len(set(blocking)), bound, len(set(dev_high)),
                                                                  len(set(unscored)))]
    if blocking:
        lines += ["", "| CVSS | Package | Advisory | Summary |", "|---|---|---|---|"]
        for score, name, version, cve, summary, _where in sorted(set(blocking), key=lambda r: (-r[0], r[1]))[:25]:
            lines.append("| %.1f | `%s@%s` | %s | %s |" % (score, name, version, cve, summary.replace("|", "/")))
    with open(summary_file, "a", encoding="utf-8") as handle:
        handle.write("\n".join(lines) + "\n")
if os.environ.get("GITHUB_ACTIONS") == "true" and blocking:
    print("::error title=OSV-Scanner::%d vulnerability group(s) at CVSS %.1f or higher in shipped dependencies; "
          "upgrade the dependency, or add a reasoned, dated entry to .github/osv-scanner.toml" % (len(set(blocking)), bound))
sys.exit(1 if blocking else 0)
PYEOF
}

# The scan's temporary folder (SBOM, scanner log, osv.json). It lives at script level, not inside main(),
# because the EXIT trap runs after main has returned: a function-local name would be unbound there under
# set -u, and the folder would never be removed (Q-325).
OSV_WORK=""
cleanup_work() { if [ -n "${OSV_WORK:-}" ]; then rm -rf "$OSV_WORK"; fi; }

# make_work: create the temporary folder in OSV_WORK and remove it when the shell exits.
make_work() {
  OSV_WORK="$(mktemp -d)" || return 2
  trap cleanup_work EXIT
}

# pull_image <reference>: three attempts, so one registry hiccup does not fail the gate.
pull_image() {
  local image="$1" attempt
  docker image inspect "$image" >/dev/null 2>&1 && return 0
  for attempt in 1 2 3; do
    if docker pull --quiet "$image" >/dev/null; then return 0; fi
    echo "osv: pull of $image failed (attempt $attempt of 3); retrying in $((attempt * 10))s" >&2
    sleep $((attempt * 10))
  done
  return 1
}

# find_scanner: set SCANNER_KIND to bin or docker; return 1 when neither exists.
find_scanner() {
  if [ -n "${OSV_SCANNER:-}" ] && [ -x "$OSV_SCANNER" ]; then SCANNER_KIND=bin; SCANNER="$OSV_SCANNER"; return 0; fi
  if command -v osv-scanner >/dev/null 2>&1; then SCANNER_KIND=bin; SCANNER="$(command -v osv-scanner)"; return 0; fi
  if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then SCANNER_KIND=docker; return 0; fi
  return 1
}

# run_scan <work dir> <lockfile>...: writes <work>/osv.json; returns the scanner's exit code.
run_scan() {
  local work="$1" lock args=() cfg=()
  shift
  for lock in "$@"; do args+=(-L "$lock"); done
  if [ "$SCANNER_KIND" = bin ]; then
    [ -f "$OSV_CONFIG" ] && cfg=(--config "$(native "$root/$OSV_CONFIG")")
    "$SCANNER" scan source --no-resolve --format json --output-file "$(native "$work")/osv.json" \
      "${cfg[@]}" "${args[@]}" "$(native "$work")/sbom"
  else
    [ -f "$OSV_CONFIG" ] && cfg=(--config "/repo/$OSV_CONFIG")
    docker run --rm -v "$(native "$root"):/repo:ro" -v "$(native "$work"):/work" -w /repo "$OSV_IMAGE" \
      scan source --no-resolve --format json --output-file /work/osv.json \
      "${cfg[@]}" "${args[@]}" /work/sbom
  fi
}

self_test() {
  local tmp rc fails=0
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' RETURN
  check() { # check <description> <wanted rc> <actual rc>
    if [ "$2" = "$3" ]; then echo "  ok    $1"; else echo "  FAIL  $1 (exit $3, wanted $2)" >&2; fails=$((fails + 1)); fi
  }
  contains() { # contains <description> <text> <file>
    if grep -qF -- "$2" "$3"; then echo "  ok    $1"; else echo "  FAIL  $1 (missing '$2')" >&2; fails=$((fails + 1)); fi
  }
  mk() { # mk <name> <type> <score|""> <dependency_groups json>
    local score='' sev="$3"
    [ -n "$sev" ] && score=", \"max_severity\": \"$sev\""
    cat >"$tmp/$1.json" <<EOF
{"results":[{"source":{"path":"/repo/x/package-lock.json","type":"$2"},"packages":[{"package":{"name":"pkg","version":"1.0","ecosystem":"npm"},"dependency_groups":$4,"vulnerabilities":[{"id":"GHSA-test-0001","summary":"canned advisory"}],"groups":[{"ids":["GHSA-test-0001"],"aliases":["CVE-2099-0001","GHSA-test-0001"]$score}]}]}]}
EOF
  }
  echo '{"results":[]}' >"$tmp/clean.json"
  policy "$(native "$tmp/clean.json")" 7.0 >"$tmp/out" 2>&1; check "no findings pass" 0 $?
  mk high sbom 9.8 '[]';          policy "$(native "$tmp/high.json")" 7.0 >"$tmp/out" 2>&1; rc=$?
  check "a 9.8 in the runtime SBOM fails" 1 $rc; contains "names the CVE" "CVE-2099-0001" "$tmp/out"
  mk low sbom 6.9 '[]';           policy "$(native "$tmp/low.json")" 7.0 >"$tmp/out" 2>&1; check "6.9 passes at the 7.0 bound" 0 $?
  mk edge sbom 7.0 '[]';          policy "$(native "$tmp/edge.json")" 7.0 >"$tmp/out" 2>&1; check "7.0 fails (the bound is inclusive)" 1 $?
  policy "$(native "$tmp/low.json")" 5.0 >"$tmp/out" 2>&1;                                  check "a lower bound makes 6.9 fail" 1 $?
  mk dev lockfile 9.8 '["dev"]';  policy "$(native "$tmp/dev.json")" 7.0 >"$tmp/out" 2>&1; rc=$?
  check "a development-only 9.8 does not fail" 0 $rc; contains "reports the development-only finding" "development-only" "$tmp/out"
  mk mixed lockfile 9.8 '["dev","prod"]'; policy "$(native "$tmp/mixed.json")" 7.0 >"$tmp/out" 2>&1; check "dev plus production counts as shipped" 1 $?
  mk unscored sbom "" '[]';       policy "$(native "$tmp/unscored.json")" 7.0 >"$tmp/out" 2>&1; rc=$?
  check "an unscored advisory does not fail" 0 $rc; contains "reports the unscored advisory" "without a score" "$tmp/out"
  echo 'not json' >"$tmp/bad.json"
  policy "$(native "$tmp/bad.json")" 7.0 >"$tmp/out" 2>&1;                                  check "unreadable input is an error, not a pass" 2 $?
  policy "$(native "$tmp/absent.json")" 7.0 >"$tmp/out" 2>&1;                               check "a missing file is an error" 2 $?

  cat >"$tmp/lic.json" <<'EOF'
{"dependencies":[
 {"moduleName":"org.springframework:spring-core","moduleVersion":"6.1.6"},
 {"moduleName":"org.springframework:spring-core","moduleVersion":"6.1.6"},
 {"moduleName":"org.springframework:spring-core","moduleVersion":"6.1.1"},
 {"moduleName":"no-colon-name","moduleVersion":"1.0"},
 {"moduleName":"org.example:no-version","moduleVersion":""},
 {"moduleName":"org.example:unspecified","moduleVersion":"unspecified"}
],"importedModules":[]}
EOF
  DLAP_OSV_MIN_COMPONENTS=1 sbom "$(native "$tmp/lic.json")" "$(native "$tmp/out.cdx.json")" >"$tmp/out" 2>&1; check "SBOM conversion succeeds" 0 $?
  contains "keeps two versions of one artifact, drops the duplicate" "with 2 runtime components" "$tmp/out"
  contains "writes a maven purl" "pkg:maven/org.springframework/spring-core@6.1.1" "$tmp/out.cdx.json"
  sbom "$(native "$tmp/lic.json")" "$(native "$tmp/out2.cdx.json")" >"$tmp/out" 2>&1;      check "a tiny report is rejected at the default minimum" 2 $?
  echo '[]' >"$tmp/notlic.json"
  sbom "$(native "$tmp/notlic.json")" "$(native "$tmp/out3.cdx.json")" >"$tmp/out" 2>&1;   check "a file that is not a licence report is rejected" 2 $?

  # Q-325: the temporary folder is removed when the shell exits, and the EXIT trap does not trip set -u.
  (make_work && printf '%s' "$OSV_WORK" >"$tmp/workdir" && touch "$OSV_WORK/osv.json") 2>"$tmp/trap.err"
  rc=$?
  check "the scan folder is created" 0 $rc
  if [ -s "$tmp/workdir" ] && [ ! -e "$(cat "$tmp/workdir")" ]; then echo "  ok    the scan folder is removed on exit"
  else echo "  FAIL  the scan folder is removed on exit ($(cat "$tmp/workdir" 2>/dev/null) still exists)" >&2; fails=$((fails + 1)); fi
  if grep -q "unbound variable" "$tmp/trap.err"; then echo "  FAIL  the EXIT trap trips set -u" >&2; fails=$((fails + 1))
  else echo "  ok    the EXIT trap does not trip set -u"; fi
  bash "$here/osv.sh" --report "$(native "$tmp/clean.json")" >"$tmp/out" 2>&1; check "--report on a clean result passes" 0 $?
  if grep -q "unbound variable" "$tmp/out"; then echo "  FAIL  an exit through main reports an unbound variable" >&2; fails=$((fails + 1))
  else echo "  ok    an exit through main reports no unbound variable"; fi

  if [ "$fails" -gt 0 ]; then
    echo "osv: self-test FAILED ($fails)" >&2
    return 2
  fi
  echo "osv: self-test OK"
}

main() {
  case "${1:-}" in
    --self-test) self_test; exit $? ;;
    --report)
      [ -n "${2:-}" ] || { echo "usage: osv.sh --report FILE" >&2; exit 2; }
      policy "$(native "$2")" "$FAIL_CVSS"; exit $?
      ;;
    "") ;;
    *) echo "usage: osv.sh [--report FILE | --self-test]" >&2; exit 2 ;;
  esac

  if ! find_scanner; then
    if [ "${GITHUB_ACTIONS:-}" = "true" ]; then
      echo "osv: ERROR: neither osv-scanner nor a working docker is available in CI" >&2
      exit 2
    fi
    echo "osv: SKIP: no osv-scanner binary (OSV_SCANNER) and no running docker"
    exit 77
  fi

  if [ "$SCANNER_KIND" = docker ] && ! pull_image "$OSV_IMAGE"; then
    echo "osv: ERROR: cannot pull $OSV_IMAGE" >&2
    exit 2
  fi

  local work report_ok=0
  make_work || { echo "osv: ERROR: cannot create a temporary folder" >&2; exit 2; }
  work="$OSV_WORK"
  mkdir -p "$work/sbom"

  # The runtime classpath: reuse the report the licence hook just wrote when it is newer than every
  # build file, otherwise ask Gradle for a fresh one.
  if [ -f "$LICENSE_REPORT" ] \
     && [ -z "$(find build.gradle.kts settings.gradle.kts gradle.properties gradle da-*/build.gradle.kts \
                  -newer "$LICENSE_REPORT" -type f 2>/dev/null | head -1)" ]; then
    report_ok=1
  fi
  if [ "$report_ok" -eq 0 ]; then
    echo "osv: resolving the runtime classpath with Gradle (generateLicenseReport)"
    # shellcheck disable=SC2086
    if ! bash "$root/gradlew" -p "$root" generateLicenseReport ${DLAP_GRADLE_ARGS:-$DEFAULT_GRADLE_ARGS}; then
      echo "osv: ERROR: generateLicenseReport failed (is JAVA_HOME a JDK 21?)" >&2
      exit 2
    fi
  fi
  sbom "$(native "$root/$LICENSE_REPORT")" "$(native "$work/sbom")/dlap-runtime.cdx.json" || exit 2

  local locks=() f
  while IFS= read -r f; do
    [ -n "$f" ] && locks+=("$f")
  done < <(git ls-files -- '*package-lock.json' '*gradle.lockfile' 2>/dev/null | sort -u)
  if [ -f new-ui/frontend/package-lock.json ]; then locks+=("new-ui/frontend/package-lock.json"); fi
  local scan_locks=()
  for f in "${locks[@]}"; do
    if [ "$SCANNER_KIND" = bin ]; then scan_locks+=("$(native "$root/$f")"); else scan_locks+=("/repo/$f"); fi
  done
  echo "osv: scanning the runtime SBOM and ${#locks[@]} lockfile(s)"

  run_scan "$work" "${scan_locks[@]}" >"$work/scanner.log" 2>&1
  local rc=$?
  if [ "$rc" -gt 1 ] || [ ! -s "$work/osv.json" ]; then
    echo "osv: ERROR: osv-scanner failed (exit $rc)" >&2
    tail -n 20 "$work/scanner.log" >&2
    exit 2
  fi
  policy "$(native "$work/osv.json")" "$FAIL_CVSS"
}

main "$@"
