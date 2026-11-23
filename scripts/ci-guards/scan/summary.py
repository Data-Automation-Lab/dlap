#!/usr/bin/env python3
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
"""Reads the reports of the dependency and image scanners and applies the DLAP policy (work package CI-02b).

  odc    REPORT.json [--bound 7.0]   OWASP Dependency-Check: any vulnerability with a CVSS base score at
                                     or above the bound fails (exit 1). Suppressed findings are not in
                                     the report; an unscored finding is listed and never fails.
  trivy  REPORT.json [--label TEXT]  Trivy: the report was already limited to HIGH and CRITICAL findings
                                     that have a fix and are not in the reasoned ignore file; any
                                     vulnerability or failed configuration check left in it fails.
  --self-test                        Offline proof of both commands on canned reports.

Output goes to stdout, to $GITHUB_STEP_SUMMARY as Markdown when it is set, and as GitHub annotations
when $GITHUB_ACTIONS is "true". Exit 2: the report is unusable (a scanner that did not produce one
must never read as a pass).
"""

import io
import json
import os
import sys

MAX_ROWS = 40
MAX_ANNOTATIONS = 10


def load(path):
    """Read a JSON report, or raise ValueError."""
    try:
        with open(path, encoding="utf-8") as handle:
            return json.load(handle)
    except (OSError, json.JSONDecodeError) as exc:
        raise ValueError("cannot read the report %s: %s" % (path, exc))


def odc_score(vuln):
    """The highest CVSS base score the finding carries (v4, v3 or v2), or None.

    Dependency-Check writes a score two ways: cvssData.baseScore (v3 and v4, and v2 in older reports) and,
    for CVSS v2 in version 13, a plain "score" next to the vector fields (Q-327). Both are read, so a v2
    finding such as CVE-2004-0455 at 7.2 is never reported as unscored while --failOnCVSS fails on it.
    """
    best = None
    for key in ("cvssv4", "cvssv3", "cvssv2"):
        node = vuln.get(key)
        if not isinstance(node, dict):
            continue
        data = node.get("cvssData", node)
        candidates = []
        if isinstance(data, dict):
            candidates += [data.get("baseScore"), data.get("score")]
        candidates.append(node.get("score"))
        for score in candidates:
            if isinstance(score, bool) or not isinstance(score, (int, float, str)):
                continue
            try:
                value = float(score)
            except ValueError:
                continue
            best = value if best is None else max(best, value)
    return best


def library_of(file_name):
    """The nested jar or file name of a Dependency-Check entry, and the boot jar that holds it."""
    outer, _, inner = str(file_name).partition(": ")
    outer = os.path.basename(outer)
    return (inner or outer), (outer if inner else "")


def write_step_summary(lines):
    """Append Markdown to the GitHub step summary when running in Actions."""
    target = os.environ.get("GITHUB_STEP_SUMMARY")
    if target:
        with open(target, "a", encoding="utf-8") as handle:
            handle.write("\n".join(lines) + "\n")


def annotate(kind, title, message):
    """Print a GitHub annotation when running in Actions."""
    if os.environ.get("GITHUB_ACTIONS") == "true":
        print("::%s title=%s::%s" % (kind, title, message))


def odc(report, bound, out=sys.stdout):
    """Summarize a Dependency-Check report; return 1 when a finding reaches the bound."""
    deps = report.get("dependencies")
    if not isinstance(deps, list):
        raise ValueError("no 'dependencies' list: not a Dependency-Check JSON report")
    high, unscored = {}, {}
    scanned = len(deps)
    for dep in deps:
        library, holder = library_of(dep.get("fileName", "?"))
        for vuln in dep.get("vulnerabilities") or []:
            score = odc_score(vuln)
            target = unscored if score is None else (high if score >= bound else None)
            if target is None:
                continue
            row = target.setdefault(library, {"modules": set(), "cves": {}, "max": -1.0})
            if holder:
                row["modules"].add(holder.replace(".jar", ""))
            row["cves"][vuln.get("name", "?")] = score
            row["max"] = max(row["max"], score if score is not None else -1.0)

    total_high = sum(len(r["cves"]) for r in high.values())
    out.write("Dependency-Check: %d dependency entries scanned, %d finding(s) at CVSS %.1f or higher in %d "
              "librar%s, %d unscored finding(s)\n" % (scanned, total_high, bound, len(high),
                                                      "y" if len(high) == 1 else "ies",
                                                      sum(len(r["cves"]) for r in unscored.values())))
    rows = sorted(high.items(), key=lambda kv: (-kv[1]["max"], kv[0]))
    for library, row in rows[:MAX_ROWS]:
        top = sorted(row["cves"].items(), key=lambda kv: -(kv[1] or 0))[:3]
        out.write("  %4.1f  %s  (%d finding(s); %s; in %s)\n" % (
            row["max"], library, len(row["cves"]), ", ".join(name for name, _ in top),
            ", ".join(sorted(row["modules"])) or "-"))
    if len(rows) > MAX_ROWS:
        out.write("  ... and %d more librar%s\n" % (len(rows) - MAX_ROWS, "y" if len(rows) - MAX_ROWS == 1 else "ies"))
    if unscored:
        out.write("Unscored (reported, not blocking): %s\n" % ", ".join(sorted(unscored)[:15]))

    md = ["### Dependency-Check", "",
          "**%d** finding(s) at CVSS %.1f or higher in **%d** librar%s (%d dependency entries scanned)." %
          (total_high, bound, len(high), "y" if len(high) == 1 else "ies", scanned)]
    if rows:
        md += ["", "| CVSS | Library | Findings | Examples | Modules |", "|---|---|---|---|---|"]
        for library, row in rows[:25]:
            top = sorted(row["cves"].items(), key=lambda kv: -(kv[1] or 0))[:3]
            md.append("| %.1f | `%s` | %d | %s | %s |" % (row["max"], library, len(row["cves"]),
                                                          ", ".join(name for name, _ in top),
                                                          ", ".join(sorted(row["modules"])) or "-"))
    write_step_summary(md)
    if high:
        annotate("error", "Dependency-Check",
                 "%d finding(s) at CVSS %.1f or higher in %d librar%s; upgrade, or add a reasoned, dated "
                 "suppression to .github/dependency-check-suppressions.xml" %
                 (total_high, bound, len(high), "y" if len(high) == 1 else "ies"))
    return 1 if high else 0


def trivy(report, label, out=sys.stdout):
    """Summarize a Trivy JSON report; return 1 when any vulnerability or failed check is left."""
    results = report.get("Results")
    if results is None:
        results = []
    if not isinstance(results, list):
        raise ValueError("'Results' is not a list: not a Trivy JSON report")
    vulns, misconfigs = [], []
    for result in results:
        target = result.get("Target", "?")
        for v in result.get("Vulnerabilities") or []:
            vulns.append((v.get("Severity", "?"), v.get("PkgName", "?"), v.get("InstalledVersion", "?"),
                          v.get("FixedVersion", "?"), v.get("VulnerabilityID", "?"), target))
        for m in result.get("Misconfigurations") or []:
            if str(m.get("Status", "FAIL")).upper() == "PASS":
                continue
            misconfigs.append((m.get("Severity", "?"), m.get("ID", "?"), m.get("Title", "?"), target))
    order = {"CRITICAL": 0, "HIGH": 1}
    vulns = sorted(set(vulns), key=lambda r: (order.get(r[0], 9), r[1], r[4]))
    misconfigs = sorted(set(misconfigs), key=lambda r: (order.get(r[0], 9), r[1], r[3]))

    out.write("Trivy (%s): %d vulnerabilit%s with a fix, %d failed configuration check(s)\n" % (
        label, len(vulns), "y" if len(vulns) == 1 else "ies", len(misconfigs)))
    for sev, pkg, installed, fixed, vid, target in vulns[:MAX_ROWS]:
        out.write("  %-8s %s %s -> %s  %s  [%s]\n" % (sev, pkg, installed, fixed, vid, target))
    if len(vulns) > MAX_ROWS:
        out.write("  ... and %d more\n" % (len(vulns) - MAX_ROWS))
    for sev, mid, title, target in misconfigs[:MAX_ROWS]:
        out.write("  %-8s %s %s  [%s]\n" % (sev, mid, title, target))
    if len(misconfigs) > MAX_ROWS:
        out.write("  ... and %d more\n" % (len(misconfigs) - MAX_ROWS))

    md = ["### Trivy (%s)" % label, "",
          "**%d** vulnerabilit%s with a fix (HIGH or CRITICAL), **%d** failed configuration check(s)." % (
              len(vulns), "y" if len(vulns) == 1 else "ies", len(misconfigs))]
    if vulns:
        md += ["", "| Severity | Package | Installed | Fixed | Advisory |", "|---|---|---|---|---|"]
        for sev, pkg, installed, fixed, vid, _target in vulns[:25]:
            md.append("| %s | `%s` | %s | %s | %s |" % (sev, pkg, installed, fixed, vid))
    if misconfigs:
        md += ["", "| Severity | Check | Title | File |", "|---|---|---|---|"]
        for sev, mid, title, target in misconfigs[:25]:
            md.append("| %s | %s | %s | `%s` |" % (sev, mid, title, target))
    write_step_summary(md)
    if vulns or misconfigs:
        annotate("error", "Trivy (%s)" % label,
                 "%d vulnerabilit%s with a fix and %d failed configuration check(s); update the dependency or "
                 "base image, or add a reasoned, dated entry to .github/trivy-ignore.yaml" % (
                     len(vulns), "y" if len(vulns) == 1 else "ies", len(misconfigs)))
    return 1 if (vulns or misconfigs) else 0


def self_test():
    """Offline proof of the policy on canned reports. Returns 0 when every case behaves."""
    failures = []

    def check(name, got, want):
        if got != want:
            failures.append("%s: got %r, wanted %r" % (name, got, want))

    sink = type("Sink", (), {"write": lambda self, text: None})()

    def dep(file_name, *scores):
        return {"fileName": file_name,
                "vulnerabilities": [{"name": "CVE-2099-%04d" % i, "cvssv3": {"cvssData": {"baseScore": s}}}
                                    if s is not None else {"name": "CVE-2099-%04d" % i}
                                    for i, s in enumerate(scores)]}

    check("odc: no vulnerabilities pass", odc({"dependencies": [dep("a.jar")]}, 7.0, sink), 0)
    check("odc: 6.9 passes", odc({"dependencies": [dep("/x/da-a.jar: lib-1.jar", 6.9)]}, 7.0, sink), 0)
    check("odc: 7.0 fails (the bound is inclusive)", odc({"dependencies": [dep("/x/da-a.jar: lib-1.jar", 7.0)]}, 7.0, sink), 1)
    check("odc: one high among low fails", odc({"dependencies": [dep("/x/da-a.jar: lib-1.jar", 2.0, 9.8)]}, 7.0, sink), 1)
    check("odc: unscored does not fail", odc({"dependencies": [dep("/x/da-a.jar: lib-1.jar", None)]}, 7.0, sink), 0)
    v2_only = {"fileName": "l.jar", "vulnerabilities": [{"name": "CVE-2099-1", "cvssv2": {"score": 1, "cvssData": {"baseScore": 7.5}}}]}
    check("odc: a CVSS v2 score counts", odc({"dependencies": [v2_only]}, 7.0, sink), 1)
    # Q-327: Dependency-Check 13 writes CVSS v2 as cvssv2.score (no cvssData), seen for CVE-2004-0455 at 7.2.
    v2_plain = {"fileName": "/x/da-a.jar: proto-google-cloud-sql-v1beta4-0.1.jar",
                "vulnerabilities": [{"name": "CVE-2004-0455", "cvssv2": {"score": 7.2, "accessVector": "NETWORK"}}]}
    check("odc: cvssv2.score alone is read", odc_score(v2_plain["vulnerabilities"][0]), 7.2)
    check("odc: cvssv2.score at the bound fails", odc({"dependencies": [v2_plain]}, 7.0, sink), 1)
    check("odc: cvssv2.score below the bound passes", odc({"dependencies": [{"fileName": "l.jar", "vulnerabilities": [{"name": "c", "cvssv2": {"score": 6.9}}]}]}, 7.0, sink), 0)
    check("odc: a v2 score as a string is read", odc_score({"cvssv2": {"score": "7.5"}}), 7.5)
    check("odc: an unreadable score is unscored, not a crash", odc_score({"cvssv2": {"score": "n/a"}, "cvssv3": {"cvssData": {"baseScore": None}}}), None)
    check("odc: a v3 baseScore still wins over a lower v2 score", odc_score({"cvssv2": {"score": 5.0}, "cvssv3": {"cvssData": {"baseScore": 9.8}}}), 9.8)
    scored = io.StringIO()
    odc({"dependencies": [v2_plain]}, 7.0, scored)
    check("odc: the v2 finding is listed, not unscored", ("CVE-2004-0455" in scored.getvalue() and ", 0 unscored" in scored.getvalue()), True)
    check("odc: a v3 7.0 beats a v2 5.0",
          odc({"dependencies": [{"fileName": "l.jar", "vulnerabilities": [{"name": "c", "cvssv2": {"cvssData": {"baseScore": 5.0}}, "cvssv3": {"cvssData": {"baseScore": 7.0}}}]}]}, 7.0, sink), 1)
    try:
        odc({"nope": []}, 7.0, sink)
        failures.append("odc: a non-report must raise")
    except ValueError:
        pass
    check("odc: library name comes from the nested jar", library_of("/src/da-a/build/libs/da-a.jar: netty-4.1.jar"), ("netty-4.1.jar", "da-a.jar"))

    check("trivy: empty report passes", trivy({"Results": []}, "t", sink), 0)
    check("trivy: a report with no Results key passes", trivy({"SchemaVersion": 2}, "t", sink), 0)
    check("trivy: a vulnerability fails",
          trivy({"Results": [{"Target": "img", "Vulnerabilities": [{"Severity": "HIGH", "PkgName": "p", "VulnerabilityID": "CVE-1"}]}]}, "t", sink), 1)
    check("trivy: a failed misconfiguration fails",
          trivy({"Results": [{"Target": "Dockerfile", "Misconfigurations": [{"Severity": "HIGH", "ID": "DS-0002", "Status": "FAIL"}]}]}, "t", sink), 1)
    check("trivy: a passed check does not fail",
          trivy({"Results": [{"Target": "Dockerfile", "Misconfigurations": [{"Severity": "HIGH", "ID": "DS-0002", "Status": "PASS"}]}]}, "t", sink), 0)
    try:
        trivy({"Results": "x"}, "t", sink)
        failures.append("trivy: a non-report must raise")
    except ValueError:
        pass

    if failures:
        for failure in failures:
            sys.stderr.write("SELF-TEST FAIL: %s\n" % failure)
        return 2
    sys.stdout.write("SELF-TEST OK\n")
    return 0


def main(argv):
    """Entry point."""
    if len(argv) >= 2 and argv[1] == "--self-test":
        return self_test()
    if len(argv) >= 3 and argv[1] in ("odc", "trivy"):
        try:
            report = load(argv[2])
            if argv[1] == "odc":
                bound = float(argv[argv.index("--bound") + 1]) if "--bound" in argv else 7.0
                return odc(report, bound)
            label = argv[argv.index("--label") + 1] if "--label" in argv else "scan"
            return trivy(report, label)
        except ValueError as exc:
            sys.stderr.write("scan summary: %s\n" % exc)
            return 2
    sys.stderr.write(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
