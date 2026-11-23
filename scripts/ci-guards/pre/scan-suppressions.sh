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
# Scanner suppression hygiene (work package CI-02b): a finding is never silenced without a reason and a
# date on which the decision is made again.
#
# Checked, offline, in the files the scanners read:
#   .github/dependency-check-suppressions.xml   every <suppress> has an "until" date, <notes> that explain,
#                                               and a narrow matcher (not just a CVSS bound)
#   .github/osv-scanner.toml                    every [[IgnoredVulns]] has id, ignoreUntil and reason;
#                                               every [[PackageOverrides]] has effectiveUntil and reason
#   .github/trivy-ignore.yaml                   every entry has id, statement and expired_at
#   source files                                every inline "nosemgrep" names a rule and gives a reason:
#                                               nosemgrep: <rule-id> -- <reason of at least 10 characters>
# A date in the past fails (the decision is due), and so does one more than a year ahead.
#
# Usage:  scripts/ci-guards/pre/scan-suppressions.sh               check the repository
#         scripts/ci-guards/pre/scan-suppressions.sh --self-test   prove the check itself, offline
# Environment: DLAP_TODAY=YYYY-MM-DD fixes "today" (the self-test uses it).
# Exit:   0 = pass; 1 = violations (listed on stderr); 2 = self-test failed or unusable input.

set -uo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../../.." && pwd)"
cd "$root" || exit 2

native() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
PY="$(command -v python3 || command -v python || true)"
[ -n "$PY" ] || { echo "scan-suppressions: ERROR: python3 is required" >&2; exit 2; }

# check <repo root>: the policy. Prints one line per violation to stderr and a summary to stdout.
check() {
  "$PY" -I - "$@" <<'PYEOF'
import datetime
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

root = sys.argv[1]
today = datetime.date.fromisoformat(os.environ["DLAP_TODAY"]) if os.environ.get("DLAP_TODAY") else datetime.date.today()
horizon = today + datetime.timedelta(days=366)
problems, summary = [], []

MIN_NOTES = 20
MIN_REASON = 10


def parse_date(text):
    match = re.match(r"^\s*['\"]?(\d{4}-\d{2}-\d{2})", str(text))
    if not match:
        return None
    try:
        return datetime.date.fromisoformat(match.group(1))
    except ValueError:
        return None


def check_date(where, label, text):
    when = parse_date(text) if text is not None else None
    if when is None:
        problems.append("%s: %s is missing or not a YYYY-MM-DD date" % (where, label))
    elif when < today:
        problems.append("%s: %s %s is in the past; decide again (renew with a reason, or fix the finding)" % (where, label, when))
    elif when > horizon:
        problems.append("%s: %s %s is more than a year away" % (where, label, when))


def words(text):
    return " ".join(str(text or "").split())


# --- OWASP Dependency-Check -------------------------------------------------------------------------
path = os.path.join(root, ".github", "dependency-check-suppressions.xml")
if os.path.isfile(path):
    try:
        tree = ET.parse(path)
    except ET.ParseError as exc:
        problems.append(".github/dependency-check-suppressions.xml: not well-formed XML (%s)" % exc)
        tree = None
    if tree is not None:
        suppress = [e for e in tree.getroot().iter() if e.tag.split("}")[-1] == "suppress"]
        for index, element in enumerate(suppress, start=1):
            where = ".github/dependency-check-suppressions.xml entry %d" % index
            check_date(where, "until", element.get("until"))
            notes = next((c for c in element if c.tag.split("}")[-1] == "notes"), None)
            if notes is None or len(words(notes.text)) < MIN_NOTES:
                problems.append("%s: <notes> missing or shorter than %d characters" % (where, MIN_NOTES))
            matchers = {"packageUrl", "vulnerabilityName", "cve", "cpe", "gav", "sha1"}
            found = [c for c in element if c.tag.split("}")[-1] in matchers]
            if not found:
                problems.append("%s: no packageUrl, vulnerabilityName, cve, cpe, gav or sha1 matcher "
                                "(a CVSS bound alone suppresses everything)" % where)
            for c in found:
                if c.get("regex") == "true" and re.fullmatch(r"\^?\(?\.[*+]\)?\$?", (c.text or "").strip()):
                    problems.append("%s: the %s regex %r matches everything" % (where, c.tag.split("}")[-1], (c.text or "").strip()))
        summary.append("dependency-check: %d suppression(s)" % len(suppress))

# --- OSV-Scanner -----------------------------------------------------------------------------------
path = os.path.join(root, ".github", "osv-scanner.toml")
if os.path.isfile(path):
    text = open(path, encoding="utf-8").read()
    blocks = re.split(r"(?m)^\s*\[\[(IgnoredVulns|PackageOverrides)\]\]\s*$", text)
    entries = []
    for i in range(1, len(blocks), 2):
        entries.append((blocks[i], blocks[i + 1]))

    def field(body, name):
        match = re.search(r"(?m)^\s*%s\s*=\s*(.+?)\s*(?:#.*)?$" % re.escape(name), body)
        return match.group(1).strip().strip("'\"") if match else None

    for index, (kind, body) in enumerate(entries, start=1):
        where = ".github/osv-scanner.toml %s %d" % (kind, index)
        if kind == "IgnoredVulns":
            if not field(body, "id"):
                problems.append("%s: id is missing" % where)
            check_date(where, "ignoreUntil", field(body, "ignoreUntil"))
        else:
            check_date(where, "effectiveUntil", field(body, "effectiveUntil"))
        reason = field(body, "reason")
        if reason is None or len(words(reason)) < MIN_NOTES:
            problems.append("%s: reason missing or shorter than %d characters" % (where, MIN_NOTES))
    summary.append("osv-scanner: %d suppression(s)" % len(entries))

# --- Trivy -----------------------------------------------------------------------------------------
path = os.path.join(root, ".github", "trivy-ignore.yaml")
if os.path.isfile(path):
    lines = open(path, encoding="utf-8").read().splitlines()
    entries, current = [], None
    for line in lines:
        if re.match(r"^\s*#", line) or not line.strip():
            continue
        start = re.match(r"^(\s*)-\s+id:\s*(\S+)", line)
        if start:
            current = {"id": start.group(2), "statement": "", "expired_at": None, "indent": len(start.group(1))}
            entries.append(current)
            continue
        if current is None:
            continue
        stmt = re.match(r"^\s+statement:\s*(.*)$", line)
        if stmt:
            current["in_statement"] = True
            current["statement"] = stmt.group(1).strip().lstrip(">|-").strip()
            continue
        expiry = re.match(r"^\s+expired_at:\s*(\S+)", line)
        if expiry:
            current["expired_at"] = expiry.group(1)
            current["in_statement"] = False
            continue
        if re.match(r"^\s+[A-Za-z_]+:", line):
            current["in_statement"] = False
            continue
        if current.get("in_statement"):
            current["statement"] += " " + line.strip()
    for index, entry in enumerate(entries, start=1):
        where = ".github/trivy-ignore.yaml entry %d (%s)" % (index, entry["id"])
        if len(words(entry["statement"])) < MIN_NOTES:
            problems.append("%s: statement missing or shorter than %d characters" % (where, MIN_NOTES))
        check_date(where, "expired_at", entry["expired_at"])
    summary.append("trivy: %d suppression(s)" % len(entries))

# --- Semgrep inline suppressions -------------------------------------------------------------------
GOOD = re.compile(r"nosemgrep:\s*[A-Za-z0-9_.\-]+(?:\s*,\s*[A-Za-z0-9_.\-]+)*\s+--\s+\S.{%d,}" % (MIN_REASON - 1))
SKIP = re.compile(r"^(docs/|\.semgrep\.yml$|scripts/ci-guards/(semgrep|pre/scan-suppressions)|.*\.md$|.*\.json$)")
EXT = (".java", ".kt", ".yml", ".yaml", ".ts", ".tsx", ".js", ".py", ".sh", ".properties", ".xml", ".gradle", ".kts")
try:
    listing = subprocess.run(["git", "ls-files", "-z"], cwd=root, capture_output=True, check=True).stdout
    files = [f for f in listing.decode("utf-8", "replace").split("\0") if f]
except (OSError, subprocess.CalledProcessError):
    files = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in (".git", "node_modules", "build")]
        files += [os.path.relpath(os.path.join(dirpath, f), root).replace(os.sep, "/") for f in filenames]
inline = 0
for rel in sorted(files):
    if not rel.endswith(EXT) or SKIP.match(rel):
        continue
    try:
        content = open(os.path.join(root, rel), encoding="utf-8", errors="replace").read()
    except OSError:
        continue
    if "nosemgrep" not in content:
        continue
    for number, line in enumerate(content.splitlines(), start=1):
        if "nosemgrep" not in line:
            continue
        inline += 1
        if not GOOD.search(line):
            problems.append("%s:%d: nosemgrep without 'nosemgrep: <rule-id> -- <reason>'" % (rel, number))
summary.append("semgrep: %d inline suppression(s)" % inline)

print("scan-suppressions: " + "; ".join(summary) if summary else "scan-suppressions: nothing to check")
for problem in problems:
    sys.stderr.write("  FAIL  %s\n" % problem)
sys.exit(1 if problems else 0)
PYEOF
}

self_test() {
  local tmp fails=0
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' RETURN
  export DLAP_TODAY=2026-10-19
  expect() { # expect <description> <wanted rc> <dir>
    local rc
    check "$(native "$3")" >"$tmp/out" 2>&1; rc=$?
    if [ "$rc" = "$2" ]; then echo "  ok    $1"; else echo "  FAIL  $1 (exit $rc, wanted $2)" >&2; sed 's/^/        | /' "$tmp/out" >&2; fails=$((fails + 1)); fi
  }
  mkgit() { rm -rf "$1" && mkdir -p "$1/.github" && git -C "$1" init -q; }
  seed() { # seed <dir>
    mkgit "$1"
  }
  local d="$tmp/t"

  seed "$d"; expect "an empty repository passes" 0 "$d"

  # --- Dependency-Check
  seed "$d"
  cat >"$d/.github/dependency-check-suppressions.xml" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<suppressions xmlns="https://jeremylong.github.io/DependencyCheck/dependency-suppression.1.3.xsd">
  <suppress until="2027-06-30Z">
    <notes>CVE-2099-0001 concerns another product of the same name; checked against the advisory text.</notes>
    <packageUrl regex="true">^pkg:maven/org\.example/lib@.*$</packageUrl>
    <vulnerabilityName>CVE-2099-0001</vulnerabilityName>
  </suppress>
</suppressions>
EOF
  expect "dependency-check: a reasoned, dated, narrow entry passes" 0 "$d"
  sed -i 's/ until="2027-06-30Z"//' "$d/.github/dependency-check-suppressions.xml"
  expect "dependency-check: no until date fails" 1 "$d"
  sed -i 's/<suppress>/<suppress until="2025-01-01Z">/' "$d/.github/dependency-check-suppressions.xml"
  expect "dependency-check: an expired date fails" 1 "$d"
  sed -i 's/until="2025-01-01Z"/until="2031-01-01Z"/' "$d/.github/dependency-check-suppressions.xml"
  expect "dependency-check: a date more than a year away fails" 1 "$d"
  sed -i 's/until="2031-01-01Z"/until="2027-06-30Z"/; s#<notes>.*</notes>#<notes>false positive</notes>#' "$d/.github/dependency-check-suppressions.xml"
  expect "dependency-check: a thin reason fails" 1 "$d"
  cat >"$d/.github/dependency-check-suppressions.xml" <<'EOF'
<suppressions xmlns="https://jeremylong.github.io/DependencyCheck/dependency-suppression.1.3.xsd">
  <suppress until="2027-06-30Z"><notes>Suppresses everything below a CVSS score, with a long enough reason.</notes><cvssBelow>11</cvssBelow></suppress>
</suppressions>
EOF
  expect "dependency-check: a blanket CVSS-only entry fails" 1 "$d"
  cat >"$d/.github/dependency-check-suppressions.xml" <<'EOF'
<suppressions xmlns="https://jeremylong.github.io/DependencyCheck/dependency-suppression.1.3.xsd">
  <suppress until="2027-06-30Z"><notes>Suppresses every vulnerability of the library, with a long enough reason.</notes>
    <packageUrl regex="true">^pkg:maven/org\.example/lib@.*$</packageUrl><vulnerabilityName regex="true">.*</vulnerabilityName></suppress>
</suppressions>
EOF
  expect "dependency-check: a match-everything regex fails" 1 "$d"
  echo '<suppressions><suppress' >"$d/.github/dependency-check-suppressions.xml"
  expect "dependency-check: broken XML fails" 1 "$d"

  # --- OSV-Scanner
  seed "$d"
  cat >"$d/.github/osv-scanner.toml" <<'EOF'
[[IgnoredVulns]]
id = "GHSA-test-0001"
ignoreUntil = 2027-03-01
reason = "The vulnerable class is never loaded; verified with a class-path scan on 2026-10-19."
EOF
  expect "osv: a reasoned, dated entry passes" 0 "$d"
  sed -i '/^ignoreUntil/d' "$d/.github/osv-scanner.toml"
  expect "osv: no ignoreUntil fails" 1 "$d"
  printf '[[IgnoredVulns]]\nid = "GHSA-test-0002"\nignoreUntil = 2027-03-01\n' >"$d/.github/osv-scanner.toml"
  expect "osv: no reason fails" 1 "$d"
  printf '[[IgnoredVulns]]\nid = "GHSA-test-0003"\nignoreUntil = 2020-03-01\nreason = "an old decision that should be revisited by now"\n' >"$d/.github/osv-scanner.toml"
  expect "osv: an expired ignore fails" 1 "$d"
  printf '[[PackageOverrides]]\nname = "lib"\nignore = true\nreason = "a long enough reason for this override to count"\n' >"$d/.github/osv-scanner.toml"
  expect "osv: an override without effectiveUntil fails" 1 "$d"

  # --- Trivy
  seed "$d"
  cat >"$d/.github/trivy-ignore.yaml" <<'EOF'
misconfigurations:
  - id: DS-0002
    paths:
      - "a/Dockerfile"
    statement: >-
      The base image runs as uid 65532; the user comes from the base image configuration, which
      Trivy cannot see from the Dockerfile.
    expired_at: 2027-04-30
vulnerabilities:
  - id: CVE-2099-0001
    statement: Not reachable from any entry point; checked by the call graph on 2026-10-19.
    expired_at: 2027-01-31
EOF
  expect "trivy: reasoned, dated entries pass (folded and one-line statements)" 0 "$d"
  sed -i '/expired_at: 2027-01-31/d' "$d/.github/trivy-ignore.yaml"
  expect "trivy: an entry without expired_at fails" 1 "$d"
  printf 'vulnerabilities:\n  - id: CVE-2099-0002\n    expired_at: 2027-01-31\n' >"$d/.github/trivy-ignore.yaml"
  expect "trivy: an entry without a statement fails" 1 "$d"
  printf 'vulnerabilities:\n  - id: CVE-2099-0002\n    statement: not applicable to this build at all, reviewed\n    expired_at: 2026-01-31\n' >"$d/.github/trivy-ignore.yaml"
  expect "trivy: an expired entry fails" 1 "$d"

  # --- Semgrep inline suppressions
  seed "$d"; mkdir -p "$d/src"
  printf 'class A {\n  void f() { run(); } // nosemgrep: dlap-java-os-command -- fixed argument list, no input reaches it\n}\n' >"$d/src/A.java"
  git -C "$d" add -A >/dev/null 2>&1
  expect "semgrep: nosemgrep with a rule and a reason passes" 0 "$d"
  printf 'class A {\n  void f() { run(); } // nosemgrep\n}\n' >"$d/src/A.java"; git -C "$d" add -A >/dev/null 2>&1
  expect "semgrep: a bare nosemgrep fails" 1 "$d"
  printf 'class A {\n  void f() { run(); } // nosemgrep: dlap-java-os-command\n}\n' >"$d/src/A.java"; git -C "$d" add -A >/dev/null 2>&1
  expect "semgrep: nosemgrep without a reason fails" 1 "$d"
  printf 'class A {\n  void f() { run(); } // nosemgrep: dlap-java-os-command -- ok\n}\n' >"$d/src/A.java"; git -C "$d" add -A >/dev/null 2>&1
  expect "semgrep: a one-word reason fails" 1 "$d"
  printf 'class A {\n  void f() { run(); } // nosemgrep: dlap-java-os-command -- fixed argument list\n}\n' >"$d/src/A.java"; git -C "$d" add -A >/dev/null 2>&1
  mkdir -p "$d/docs" && printf 'Use // nosemgrep: rule to suppress.\n' >"$d/docs/guide.md"; git -C "$d" add -A >/dev/null 2>&1
  expect "semgrep: documentation that mentions nosemgrep is not checked" 0 "$d"

  # The repository itself.
  if [ "$fails" -gt 0 ]; then
    echo "scan-suppressions: self-test FAILED ($fails)" >&2
    return 2
  fi
  echo "scan-suppressions: self-test OK"
}

case "${1:-}" in
  --self-test) self_test; exit $? ;;
  "") check "$(native "$root")"; exit $? ;;
  *) echo "usage: scan-suppressions.sh [--self-test]" >&2; exit 2 ;;
esac
