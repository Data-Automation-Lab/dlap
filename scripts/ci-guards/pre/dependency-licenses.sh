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
# Dependency licence gate (hardening package LIC-01; CLAUDE.md section 10.5).
#
# What it enforces
#   1. Gradle: no GPL, LGPL or AGPL licence on any runtime classpath (the jars that ship).
#      The root build runs `generateLicenseReport` (dependency-license-report plugin) over every
#      module's runtimeClasspath; this script applies the policy to build/reports/dependency-license/
#      licenses.json. A module that offers a permissive licence next to a copyleft one (dual
#      licensing, for example logback: EPL or LGPL) passes. A module whose only licences are copyleft
#      fails, unless it is on the dated exception list in the policy below.
#      Test-scope drivers such as the MySQL Connector/J (GPLv2) are out of scope on purpose
#      (ADR-0030, docs/DECISIONS.md): only runtimeClasspath is read. The gate fails if that driver
#      ever reaches a runtime classpath.
#   2. NOTICE names every shipped dependency that relies on an ASF Category B licence (EPL, CDDL,
#      MPL) or on an accepted exception, so the attribution cannot silently drift.
#   3. npm: the production dependencies of new-ui/frontend carry no GPL, LGPL or AGPL licence,
#      checked with a pinned license-checker-rseidelsohn. Its --failOn flag matches exact strings,
#      so the plain words GPL;LGPL;AGPL never match a real SPDX identifier such as GPL-3.0-only.
#      This script therefore passes the full identifier list to --failOn and also applies the
#      SPDX-expression policy (OR / AND aware) to the tool's JSON output.
#
# Needs network (Maven Central and the Gradle plugin portal for the Gradle stage, the npm registry
# for the npm stage), Java 21 for Gradle (set JAVA_HOME), python3, and node/npm for the UI stage.
# Runs in about two to five minutes. Every full run starts with the policy self-test (seconds).
#
# Usage:  scripts/ci-guards/pre/dependency-licenses.sh                 Gradle stage, then npm stage
#         scripts/ci-guards/pre/dependency-licenses.sh --gradle-only   skip the UI stage
#         scripts/ci-guards/pre/dependency-licenses.sh --npm-only      skip the Gradle stage
#         scripts/ci-guards/pre/dependency-licenses.sh --report FILE   apply the Gradle policy to an
#                                                                      existing licenses.json, offline
#         scripts/ci-guards/pre/dependency-licenses.sh --self-test     prove the policy itself works
# Environment:
#   DLAP_GRADLE_ARGS   extra Gradle arguments (default: --no-daemon --console=plain --no-watch-fs
#                      --no-configuration-cache --max-workers=2)
#   DLAP_UI_DIR        directory that holds frontend/ (default: <repo>/new-ui)
# Exit:   0 = pass (or self-test passed); 77 = skipped (nothing to check); other = fail.
# The exit-code contract is the one in scripts/ci-guards/README.md.

set -uo pipefail

LICENSE_CHECKER="license-checker-rseidelsohn@4.4.2"
DEFAULT_GRADLE_ARGS="--no-daemon --console=plain --no-watch-fs --no-configuration-cache --max-workers=2"
# Exact SPDX identifiers for license-checker's --failOn (it does not match substrings). The first
# three are the plain words from the work-package plan; the rest are what packages really declare.
NPM_FAIL_ON="GPL;LGPL;AGPL"
for _id in GPL-1.0 GPL-2.0 GPL-3.0 GPL-2.0-only GPL-3.0-only GPL-2.0-or-later GPL-3.0-or-later \
           GPL-2.0+ GPL-3.0+ LGPL-2.0 LGPL-2.1 LGPL-3.0 LGPL-2.0-only LGPL-2.1-only LGPL-3.0-only \
           LGPL-2.0-or-later LGPL-2.1-or-later LGPL-3.0-or-later LGPL-2.0+ LGPL-2.1+ LGPL-3.0+ \
           AGPL-1.0 AGPL-3.0 AGPL-1.0-only AGPL-3.0-only AGPL-1.0-or-later AGPL-3.0-or-later; do
  NPM_FAIL_ON="$NPM_FAIL_ON;$_id"
done

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../../.." && pwd)"

# native <path>: a path the Windows python/node can open (Git Bash paths such as /d/git/x are not).
native() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi
}

PY=""
find_python() {
  local c
  for c in python3 python; do
    if command -v "$c" >/dev/null 2>&1 && "$c" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 8) else 1)' >/dev/null 2>&1; then
      PY="$c"
      return 0
    fi
  done
  echo "dependency-licenses: ERROR: python3 (3.8 or newer) is required" >&2
  return 1
}

# policy <mode> [args...]: the licence policy. Modes: gradle, npm. Exit 0 pass, 1 policy failure,
# 2 unusable input. Kept in one place so the self-test exercises exactly what the gate runs.
policy() {
  "$PY" -I - "$@" <<'PYEOF'
import argparse
import datetime
import json
import re
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover - very old interpreters
    pass

# A licence entry is "deny" when it names the GPL family and offers nothing else. The ALT words
# catch single strings that bundle a choice ("CDDL + GPLv2 with classpath exception").
DENY = re.compile(
    r"(?i)(\b(?:a|l)?gpl\b|\b(?:a|l)?gpl-?v?\d|"
    r"gnu[\s-]+(?:affero[\s-]+|lesser[\s-]+|library[\s-]+)?general[\s-]+public|gnu\.org/licenses)"
)
ALT = re.compile(r"(?i)\b(?:cddl|common development|epl|eclipse public|apache|mit|bsd|mpl|mozilla)\b")
# ASF Category B (weak copyleft): allowed in binaries, must be attributed.
CAT_B = re.compile(
    r"(?i)(eclipse public|\bepl\b|common development and distribution|\bcddl\b|mozilla public|"
    r"\bmpl\b|common public license|\bcpl\b)"
)
# ASF Category A and public-domain style licences.
CAT_A = re.compile(
    r"(?i)(apache|\bmit\b|\bmit-0\b|bsd|\bisc\b|public domain|creative commons legal code|\bcc0\b|"
    r"\bupl\b|universal permissive|go license|indiana university|zlib|unicode|bouncy|"
    r"eclipse distribution|\bedl\b|postgresql|\bw3c\b|unlicense|boost|\bpsf\b|python software)"
)
NONE = {"", "none", "null", "unknown", "license", "unlicensed", "see license in license"}

# Accepted exceptions: shipped runtime modules that are copyleft-only today. Each has a reference,
# a reason and an expiry date. After the date the gate fails again, which forces the decision.
# Add nothing here without an entry in docs/OPEN_QUESTIONS.md or docs/DECISIONS.md.
# Empty since BOOT-01 (ADR-0065): Spring Boot 4.1 brings Hibernate ORM 7, which is Apache-2.0, and
# Hibernate ORM 7 no longer uses hibernate-commons-annotations, so the two Q-105 rows are gone.
# The self-test passes a table of its own with --exceptions, which keeps this mechanism covered.
EXCEPTIONS = []

# ADR-0030: the MySQL JDBC driver is GPLv2 and must stay test-scope.
MYSQL = re.compile(r"^(com\.mysql:mysql-connector-j|mysql:mysql-connector-java)$")


def classify(name, url=""):
    """Return deny, A, B, review or none for one licence entry."""
    n = (name or "").strip()
    if n.lower() in NONE:
        return "none"
    if DENY.search(n + " " + (url or "")) and not ALT.search(n):
        return "deny"
    if CAT_B.search(n):
        return "B"
    if CAT_A.search(n):
        return "A"
    return "review"


def gradle_entries(dep):
    lics = dep.get("moduleLicenses")
    if not lics and dep.get("moduleLicense"):
        lics = [{"moduleLicense": dep["moduleLicense"], "moduleLicenseUrl": dep.get("moduleLicenseUrl", "")}]
    return [(str(x.get("moduleLicense") or ""), str(x.get("moduleLicenseUrl") or "")) for x in (lics or [])]


def load(path):
    try:
        with open(path, encoding="utf-8") as fh:
            return json.load(fh)
    except (OSError, ValueError) as exc:
        print("dependency-licenses: ERROR: cannot read %s: %s" % (path, exc))
        sys.exit(2)


def run_gradle(args):
    data = load(args.report)
    deps = data.get("dependencies") if isinstance(data, dict) else None
    if not isinstance(deps, list):
        print("dependency-licenses: ERROR: %s has no 'dependencies' array" % args.report)
        return 2
    try:
        today = datetime.date.fromisoformat(args.today) if args.today else datetime.date.today()
    except ValueError:
        print("dependency-licenses: ERROR: bad --today value")
        return 2
    table = load(args.exceptions) if args.exceptions else EXCEPTIONS
    if not isinstance(table, list):
        print("dependency-licenses: ERROR: %s is not a list of exceptions" % args.exceptions)
        return 2
    try:
        with open(args.notice, encoding="utf-8") as fh:
            notice = fh.read()
    except OSError as exc:
        print("dependency-licenses: ERROR: cannot read NOTICE %s: %s" % (args.notice, exc))
        return 2

    denied, unknown, review, cat_b = {}, {}, {}, {}
    modules, dual_deny, permissive = set(), set(), 0
    mysql_hit = set()
    for dep in deps:
        coord = str(dep.get("moduleName", ""))
        ver = str(dep.get("moduleVersion", ""))
        entries = gradle_entries(dep)
        modules.add(coord)
        if MYSQL.match(coord):
            mysql_hit.add(coord + ":" + ver)
        classes = [classify(n, u) for n, u in entries]
        real = [c for c in classes if c != "none"]
        if not real:
            unknown.setdefault(coord, set()).add(ver)
            continue
        if all(c == "deny" for c in real):
            denied.setdefault(coord, {"versions": set(), "licences": set()})
            denied[coord]["versions"].add(ver)
            denied[coord]["licences"].update(n for (n, _), c in zip(entries, classes) if c == "deny")
            continue
        if "deny" in real:
            dual_deny.add(coord)
        if "A" not in real and "B" in real:
            cat_b.setdefault(coord, set()).update(n for (n, _), c in zip(entries, classes) if c == "B")
        elif "A" in real:
            permissive += 1
        if "review" in real:
            review.setdefault(coord, set()).update(n for (n, _), c in zip(entries, classes) if c == "review")

    failures = []
    if len(modules) < args.min_modules:
        failures.append(
            "report covers only %d module(s), expected at least %d: the report is empty or the "
            "projects list in build.gradle.kts is wrong" % (len(modules), args.min_modules)
        )
    for item in sorted(mysql_hit):
        failures.append("ADR-0030: %s is on a runtime classpath; the GPLv2 MySQL driver must stay test-scope" % item)

    accepted, expired = {}, {}
    for coord, info in sorted(denied.items()):
        if MYSQL.match(coord):
            continue  # already reported with the ADR-0030 message
        match = next((e for e in table if re.search(e["pattern"], coord)), None)
        if match is None:
            failures.append(
                "%s %s: only copyleft licences (%s)" % (coord, ",".join(sorted(info["versions"])), "; ".join(sorted(info["licences"])))
            )
        elif today > datetime.date.fromisoformat(match["expires"]):
            expired[coord] = match
            failures.append(
                "%s: exception %s expired on %s (%s). Decide, then remove or renew it in this script."
                % (coord, match["ref"], match["expires"], match["why"])
            )
        else:
            accepted[coord] = (match, info)

    stale = [e for e in table if not any(re.search(e["pattern"], c) for c in denied)]

    required = sorted(set(cat_b) | set(accepted))
    missing = [c for c in required if c not in notice]
    for coord in missing:
        failures.append("NOTICE does not name %s (Category B or accepted exception)" % coord)

    print("dependency-licenses: %d report entries, %d distinct modules (runtime classpaths)" % (len(deps), len(modules)))
    print("  permissive, or a permissive option beside a copyleft one: %d entries" % permissive)
    print("  dual-licensed with a copyleft option left unused: %d modules" % len(dual_deny))
    print("  ASF Category B reliance, named in NOTICE: %d modules" % len(cat_b))
    for coord in sorted(cat_b):
        print("    B  %s  (%s)" % (coord, "; ".join(sorted(cat_b[coord]))))
    for coord, (match, info) in sorted(accepted.items()):
        print(
            "  WARN accepted exception %s until %s: %s %s (%s). %s"
            % (match["ref"], match["expires"], coord, ",".join(sorted(info["versions"])), match["licence"], match["why"])
        )
    for e in stale:
        print("  WARN exception %s for %s matches nothing any more; remove it" % (e["ref"], e["pattern"]))
    for coord in sorted(review):
        print("  review: %s lists %s" % (coord, "; ".join(sorted(review[coord]))))
    for coord in sorted(unknown):
        print("  WARN no licence declared: %s %s" % (coord, ",".join(sorted(unknown[coord]))))
    if failures:
        for f in failures:
            print("  FAIL %s" % f)
        print("dependency-licenses: FAIL (%d problem(s))" % len(failures))
        return 1
    print("dependency-licenses: PASS (gradle)")
    return 0


def split_spdx(expr):
    """Split an SPDX-like expression into OR alternatives, each a list of AND terms."""
    text = expr.replace("*", " ").strip().strip("()")
    text = re.sub(r"[()]", " ", text)
    alts = re.split(r"(?i)\s+OR\s+", text)
    return [[t.strip() for t in re.split(r"(?i)\s+AND\s+", a) if t.strip()] for a in alts if a.strip()]


def run_npm(args):
    data = load(args.report)
    if not isinstance(data, dict):
        print("dependency-licenses: ERROR: %s is not a license-checker JSON object" % args.report)
        return 2
    failures, unknown, denied = [], [], []
    if len(data) < args.min_modules:
        failures.append(
            "license-checker listed only %d package(s), expected at least %d: node_modules is missing or the "
            "wrong directory was scanned" % (len(data), args.min_modules)
        )
    for pkg, info in sorted(data.items()):
        raw = info.get("licenses") if isinstance(info, dict) else None
        if isinstance(raw, list):
            alts = [[str(x)] for x in raw]
        else:
            alts = split_spdx(str(raw or ""))
        if not alts or all(c == "none" for a in alts for c in [classify(" ".join(a))]):
            unknown.append((pkg, str(raw)))
            continue
        # The package is usable when at least one OR alternative has no GPL-family term.
        usable = any(all(classify(t) != "deny" for t in a) for a in alts)
        if not usable:
            denied.append((pkg, str(raw)))
    for pkg, lic in denied:
        failures.append("%s: %s" % (pkg, lic))
    print("dependency-licenses: %d npm production packages checked" % len(data))
    for pkg, lic in unknown:
        print("  WARN no licence declared: %s (%s)" % (pkg, lic))
    if failures:
        for f in failures:
            print("  FAIL %s" % f)
        print("dependency-licenses: FAIL (npm, %d problem(s))" % len(failures))
        return 1
    print("dependency-licenses: PASS (npm)")
    return 0


def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="mode", required=True)
    g = sub.add_parser("gradle")
    g.add_argument("--report", required=True)
    g.add_argument("--notice", required=True)
    g.add_argument("--today")
    # Self-test only: a JSON list in the EXCEPTIONS shape replaces the table above. The gate itself
    # never passes it.
    g.add_argument("--exceptions")
    g.add_argument("--min-modules", type=int, default=100)
    n = sub.add_parser("npm")
    n.add_argument("--report", required=True)
    n.add_argument("--min-modules", type=int, default=10)
    args = parser.parse_args()
    sys.exit(run_gradle(args) if args.mode == "gradle" else run_npm(args))


main()
PYEOF
}

gradle_stage() {
  local report="$root/build/reports/dependency-license/licenses.json"
  local gradlew="$root/gradlew"
  if [ ! -f "$gradlew" ]; then
    echo "dependency-licenses: ERROR: $gradlew not found" >&2
    return 1
  fi
  echo "dependency-licenses: generating the runtime licence report (Gradle)"
  rm -f "$report"
  # shellcheck disable=SC2086
  if ! bash "$gradlew" -p "$root" generateLicenseReport ${DLAP_GRADLE_ARGS:-$DEFAULT_GRADLE_ARGS}; then
    echo "dependency-licenses: ERROR: generateLicenseReport failed (is JAVA_HOME a JDK 21?)" >&2
    return 1
  fi
  policy gradle --report "$(native "$report")" --notice "$(native "$root/NOTICE")" --min-modules 100
}

npm_stage() {
  local ui="${DLAP_UI_DIR:-$root/new-ui}/frontend"
  if [ ! -f "$ui/package.json" ]; then
    echo "dependency-licenses: SKIP npm stage: $ui/package.json not found (new-ui is not checked out)"
    return 77
  fi
  if ! command -v npm >/dev/null 2>&1 || ! command -v npx >/dev/null 2>&1; then
    echo "dependency-licenses: ERROR: npm and npx are required for the UI stage" >&2
    return 1
  fi
  if [ ! -d "$ui/node_modules" ]; then
    echo "dependency-licenses: installing the UI dependencies (npm ci --ignore-scripts)"
    (cd "$ui" && npm ci --ignore-scripts --no-audit --no-fund) || {
      echo "dependency-licenses: ERROR: npm ci failed" >&2
      return 1
    }
  fi
  echo "dependency-licenses: checking the UI production dependencies ($LICENSE_CHECKER)"
  (cd "$ui" && npx --yes "$LICENSE_CHECKER" --production --excludePrivatePackages --failOn "$NPM_FAIL_ON" >/dev/null) || {
    echo "dependency-licenses: FAIL npm: license-checker --failOn matched a GPL-family licence (rerun without >/dev/null to list it)" >&2
    return 1
  }
  local tmp rc
  tmp="$(mktemp -d)"
  (cd "$ui" && npx --yes "$LICENSE_CHECKER" --production --excludePrivatePackages --json >"$tmp/npm-licenses.json") || {
    rm -rf "$tmp"
    echo "dependency-licenses: ERROR: license-checker failed" >&2
    return 1
  }
  policy npm --report "$(native "$tmp/npm-licenses.json")" --min-modules 10
  rc=$?
  rm -rf "$tmp"
  return $rc
}

# ---------------------------------------------------------------------------------------------
# Self-test: synthetic reports prove the policy fails when it must and passes when it must.
# ---------------------------------------------------------------------------------------------

dep_json() { # <group:artifact> <version> <licence>...
  local coords="$1" ver="$2" lics="" l
  shift 2
  for l in "$@"; do
    lics="${lics:+$lics,}{\"moduleLicense\":\"$l\",\"moduleLicenseUrl\":\"\"}"
  done
  printf '{"moduleName":"%s","moduleVersion":"%s","moduleLicenses":[%s]}' "$coords" "$ver" "$lics"
}

report_json() { # <dep json>...
  local out="" d
  for d in "$@"; do out="${out:+$out,}$d"; done
  printf '{"dependencies":[%s]}\n' "$out"
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
  local tmp="$1" failed=0 n=0
  local apache="Apache License, Version 2.0" mit="MIT License"
  local notice="$tmp/NOTICE" empty_notice="$tmp/NOTICE.empty"
  printf 'ch.qos.logback:logback-classic\norg.hibernate.orm:hibernate-core\n' >"$notice"
  : >"$empty_notice"

  # check <name> <expected rc> <expected text or -> <mode> <args...>
  check() {
    local name="$1" want_rc="$2" want_text="$3" out rc
    shift 3
    n=$((n + 1))
    out="$(policy "$@" 2>&1)"
    rc=$?
    if [ "$rc" -ne "$want_rc" ] || { [ "$want_text" != "-" ] && ! printf '%s' "$out" | grep -qF -- "$want_text"; }; then
      echo "SELF-TEST FAIL: $name (rc=$rc, wanted rc=$want_rc and text '$want_text')" >&2
      printf '%s\n' "$out" | sed 's/^/    /' >&2
      failed=$((failed + 1))
    fi
  }
  gradle_check() { # <name> <rc> <text> <report file> <notice file> [extra args]
    local name="$1" rc="$2" text="$3" rep="$4" nt="$5"
    shift 5
    check "$name" "$rc" "$text" gradle --report "$(native "$rep")" --notice "$(native "$nt")" --min-modules 1 "$@"
  }

  report_json "$(dep_json org.apache.commons:commons-lang3 3.13.0 "$apache")" \
    "$(dep_json org.slf4j:slf4j-api 2.0.13 None "$mit")" >"$tmp/clean.json"
  gradle_check "clean report passes" 0 "PASS (gradle)" "$tmp/clean.json" "$empty_notice"

  report_json "$(dep_json org.example:gpl-lib 1.0 "GNU GENERAL PUBLIC LICENSE, Version 3")" >"$tmp/gpl.json"
  gradle_check "GPL-only module fails" 1 "org.example:gpl-lib" "$tmp/gpl.json" "$empty_notice"

  report_json "$(dep_json org.example:lgpl-lib 1.0 "GNU LESSER GENERAL PUBLIC LICENSE, Version 2.1")" >"$tmp/lgpl.json"
  gradle_check "LGPL-only module fails" 1 "org.example:lgpl-lib" "$tmp/lgpl.json" "$empty_notice"

  report_json "$(dep_json org.example:agpl-lib 1.0 "GNU AFFERO GENERAL PUBLIC LICENSE, Version 3")" >"$tmp/agpl.json"
  gradle_check "AGPL-only module fails" 1 "org.example:agpl-lib" "$tmp/agpl.json" "$empty_notice"

  report_json "$(dep_json org.example:gpl-cpe 1.0 "GNU GENERAL PUBLIC LICENSE, Version 2 + Classpath Exception")" >"$tmp/cpe.json"
  gradle_check "GPL with classpath exception alone fails" 1 "org.example:gpl-cpe" "$tmp/cpe.json" "$empty_notice"

  report_json "$(dep_json org.example:dual 1.0 "$apache" "GNU LESSER GENERAL PUBLIC LICENSE, Version 3")" >"$tmp/dual.json"
  gradle_check "Apache or LGPL passes (dual licensing)" 0 "PASS (gradle)" "$tmp/dual.json" "$empty_notice"

  report_json "$(dep_json org.example:cddl-gpl 1.0 "CDDL + GPLv2 with classpath exception")" >"$tmp/cddl.json"
  gradle_check "CDDL plus GPL in one string is Category B and needs NOTICE" 1 "NOTICE does not name org.example:cddl-gpl" "$tmp/cddl.json" "$empty_notice"
  printf 'org.example:cddl-gpl\n' >"$tmp/NOTICE.cddl"
  gradle_check "Category B passes when NOTICE names it" 0 "PASS (gradle)" "$tmp/cddl.json" "$tmp/NOTICE.cddl"

  report_json "$(dep_json ch.qos.logback:logback-classic 1.4.14 "Eclipse Public License - v 1.0" "GNU LESSER GENERAL PUBLIC LICENSE, Version 2.1")" >"$tmp/logback.json"
  gradle_check "EPL or LGPL needs the NOTICE entry" 1 "NOTICE does not name ch.qos.logback:logback-classic" "$tmp/logback.json" "$empty_notice"
  gradle_check "EPL or LGPL passes with the NOTICE entry" 0 "PASS (gradle)" "$tmp/logback.json" "$notice"

  report_json "$(dep_json com.mysql:mysql-connector-j 8.3.0 "The GNU General Public License, v2 with Universal FOSS Exception, v1.0")" >"$tmp/mysql.json"
  gradle_check "MySQL driver on a runtime classpath fails (ADR-0030)" 1 "ADR-0030" "$tmp/mysql.json" "$empty_notice"
  report_json "$(dep_json com.mysql:mysql-connector-j 8.3.0 "$apache")" >"$tmp/mysql-fake-ok.json"
  gradle_check "MySQL driver fails even if mislabelled" 1 "ADR-0030" "$tmp/mysql-fake-ok.json" "$empty_notice"

  # The exception mechanism, with a table of the self-test's own (the shipped table is empty).
  printf '%s\n' '[{"pattern": "^org\\.example:lgpl-accepted$", "licence": "LGPL-2.1", "ref": "Q-TEST", "expires": "2026-12-31", "why": "self-test"}]' >"$tmp/exceptions.json"
  printf 'ch.qos.logback:logback-classic\norg.example:lgpl-accepted\n' >"$tmp/NOTICE.exc"
  report_json "$(dep_json org.example:lgpl-accepted 1.0 "GNU LESSER GENERAL PUBLIC LICENSE, Version 2.1")" >"$tmp/exc.json"
  local exc
  exc="$(native "$tmp/exceptions.json")"
  gradle_check "accepted exception passes before its expiry" 0 "WARN accepted exception" "$tmp/exc.json" "$tmp/NOTICE.exc" --today 2026-10-19 --exceptions "$exc"
  gradle_check "accepted exception expires" 1 "expired on" "$tmp/exc.json" "$tmp/NOTICE.exc" --today 2027-01-01 --exceptions "$exc"
  gradle_check "accepted exception must be in NOTICE" 1 "NOTICE does not name org.example:lgpl-accepted" "$tmp/exc.json" "$empty_notice" --today 2026-10-19 --exceptions "$exc"
  gradle_check "an exception that matches nothing is reported stale" 0 "matches nothing any more" "$tmp/clean.json" "$empty_notice" --today 2026-10-19 --exceptions "$exc"

  # BOOT-01 (Q-105): no Hibernate exception ships any more. Hibernate ORM 6 (LGPL-2.1 only) fails the
  # gate again, and Hibernate ORM 7 (Apache-2.0) passes.
  report_json "$(dep_json org.hibernate.orm:hibernate-core 6.4.4.Final "GNU LESSER GENERAL PUBLIC LICENSE, Version 2.1")" >"$tmp/hib6.json"
  gradle_check "Hibernate ORM 6 (LGPL only) fails without an exception" 1 "org.hibernate.orm:hibernate-core 6.4.4.Final: only copyleft" "$tmp/hib6.json" "$notice" --today 2026-10-19
  report_json "$(dep_json org.hibernate.orm:hibernate-core 7.4.5.Final "$apache")" >"$tmp/hib7.json"
  gradle_check "Hibernate ORM 7 (Apache-2.0) passes" 0 "PASS (gradle)" "$tmp/hib7.json" "$empty_notice" --today 2026-10-19

  report_json "$(dep_json org.example:nolicence 1.0 None)" >"$tmp/none.json"
  gradle_check "module with no licence warns" 0 "WARN no licence declared: org.example:nolicence" "$tmp/none.json" "$empty_notice"

  report_json "$(dep_json io.confluent:kafka-protobuf-serializer 7.5.0 "$apache" "Confluent Community License")" >"$tmp/confluent.json"
  gradle_check "unrecognised licence is listed for review" 0 "review: io.confluent:kafka-protobuf-serializer" "$tmp/confluent.json" "$empty_notice"

  printf '{"dependencies":[]}\n' >"$tmp/emptyrep.json"
  gradle_check "empty report fails (no vacuous pass)" 1 "report covers only 0 module" "$tmp/emptyrep.json" "$empty_notice"
  gradle_check "missing report fails" 2 "cannot read" "$tmp/does-not-exist.json" "$empty_notice"
  printf 'not json' >"$tmp/garbage.json"
  gradle_check "garbage report fails" 2 "cannot read" "$tmp/garbage.json" "$empty_notice"

  # npm: license-checker --json shape.
  printf '%s\n' '{"a@1.0.0":{"licenses":"MIT"},"b@1.0.0":{"licenses":"(MIT OR GPL-3.0)"},"c@1.0.0":{"licenses":"Apache-2.0"}}' >"$tmp/npm-ok.json"
  check "npm: MIT, MIT-or-GPL and Apache pass" 0 "PASS (npm)" npm --report "$(native "$tmp/npm-ok.json")" --min-modules 1
  printf '%s\n' '{"a@1.0.0":{"licenses":"GPL-3.0-only"}}' >"$tmp/npm-gpl.json"
  check "npm: GPL-3.0-only fails" 1 "a@1.0.0" npm --report "$(native "$tmp/npm-gpl.json")" --min-modules 1
  printf '%s\n' '{"a@1.0.0":{"licenses":"LGPL-2.1+"}}' >"$tmp/npm-lgpl.json"
  check "npm: LGPL-2.1+ fails" 1 "a@1.0.0" npm --report "$(native "$tmp/npm-lgpl.json")" --min-modules 1
  printf '%s\n' '{"a@1.0.0":{"licenses":"AGPL-3.0-or-later"}}' >"$tmp/npm-agpl.json"
  check "npm: AGPL-3.0-or-later fails" 1 "a@1.0.0" npm --report "$(native "$tmp/npm-agpl.json")" --min-modules 1
  printf '%s\n' '{"a@1.0.0":{"licenses":"(Apache-2.0 AND GPL-2.0)"}}' >"$tmp/npm-and.json"
  check "npm: Apache AND GPL fails (both apply)" 1 "a@1.0.0" npm --report "$(native "$tmp/npm-and.json")" --min-modules 1
  printf '%s\n' '{"a@1.0.0":{"licenses":["GPL-2.0","MIT"]}}' >"$tmp/npm-arr.json"
  check "npm: licence array is a choice" 0 "PASS (npm)" npm --report "$(native "$tmp/npm-arr.json")" --min-modules 1
  printf '%s\n' '{"a@1.0.0":{"licenses":"UNKNOWN"}}' >"$tmp/npm-unk.json"
  check "npm: unknown licence warns" 0 "WARN no licence declared: a@1.0.0" npm --report "$(native "$tmp/npm-unk.json")" --min-modules 1
  printf '{}\n' >"$tmp/npm-empty.json"
  check "npm: empty scan fails (no vacuous pass)" 1 "listed only 0 package" npm --report "$(native "$tmp/npm-empty.json")" --min-modules 1

  if [ "$failed" -ne 0 ]; then
    echo "dependency-licenses: self-test FAILED ($failed of $n cases)" >&2
    return 2
  fi
  echo "dependency-licenses: self-test OK ($n cases)"
  return 0
}

main() {
  local do_gradle=1 do_npm=1 report="" self=0
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --self-test) self=1 ;;
      --gradle-only) do_npm=0 ;;
      --npm-only) do_gradle=0 ;;
      --report)
        shift
        report="${1:-}"
        [ -n "$report" ] || { echo "dependency-licenses: --report needs a file" >&2; return 2; }
        ;;
      -h | --help)
        sed -n '/^# Dependency licence gate/,/^# The exit-code contract/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
        return 0
        ;;
      *) echo "dependency-licenses: unknown argument: $1" >&2; return 2 ;;
    esac
    shift
  done

  find_python || return 2
  if [ "$self" = 1 ]; then
    self_test
    return $?
  fi
  if [ -n "$report" ]; then
    policy gradle --report "$(native "$report")" --notice "$(native "$root/NOTICE")" --min-modules 1
    return $?
  fi

  # A broken policy would pass silently, so prove it on synthetic reports before the real run.
  self_test || return 2

  local rc=0 ran=0 s
  if [ "$do_gradle" = 1 ]; then
    ran=1
    gradle_stage || rc=1
  fi
  if [ "$do_npm" = 1 ]; then
    npm_stage
    s=$?
    case "$s" in
      0) ran=1 ;;
      77) ;;
      *) ran=1; rc=1 ;;
    esac
  fi
  if [ "$ran" = 0 ]; then
    return 77
  fi
  return "$rc"
}

main "$@"
exit $?
