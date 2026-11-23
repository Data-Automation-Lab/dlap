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
"""Applies the DLAP policy to a Semgrep JSON result (work package CI-02b).

Semgrep itself only reports. The policy lives here so that it is the same on a laptop and in CI,
and so that it is tested:

  summary  RESULTS.json [--label TEXT]   ERROR findings fail (exit 1), WARNING findings are listed
                                         and never fail; a scan that could not parse a large share
                                         of the Java files fails (exit 1) because it would be
                                         silently blind. Exit 2: the result file is unusable.
  fixtures RESULTS.json FIXTURE_DIR RULES_FILE
                                         The rules still do what the fixtures say: every
                                         "// ruleid: <id>" line produces that finding, every
                                         "// ok: <id>" line does not, no finding is unannotated,
                                         and every rule in RULES_FILE has both kinds of fixture.
  --self-test                            Offline proof of the two commands above on canned results.

Annotations apply to the next line, like "semgrep test":
    // ruleid: dlap-java-os-command        a finding of this rule must start on the next line
    // ok: dlap-java-os-command            no finding of this rule may start on the next line
Several ids may follow one "ruleid:" or "ok:", separated by commas.
"""

import json
import os
import re
import sys
import tempfile
from pathlib import Path

# A scan that cannot parse more than this share of the files of one language is blind in that language.
MAX_PARSE_FAILURE_SHARE = 0.02
# GitHub allows 10 annotations of each kind per step in the UI; keep a margin.
MAX_ANNOTATIONS = 10

RULE_ID = re.compile(r"(dlap-[a-z0-9]+(?:-[a-z0-9]+)*)$")
ANNOTATION = re.compile(r"^\s*(?://|#)\s*(ruleid|ok)\s*:\s*([A-Za-z0-9_,\-\s]+?)\s*$")
RULE_DECL = re.compile(r"^\s*-\s+id:\s*(\S+)\s*$")


def short_id(check_id):
    """Return the rule id without the path prefix Semgrep adds to ids of nested config files."""
    match = RULE_ID.search(check_id)
    return match.group(1) if match else check_id


def load(path):
    """Read a Semgrep JSON result, or raise ValueError when it is not one."""
    try:
        with open(path, encoding="utf-8") as handle:
            data = json.load(handle)
    except (OSError, json.JSONDecodeError) as exc:
        raise ValueError("cannot read the Semgrep result %s: %s" % (path, exc))
    if not isinstance(data, dict) or "results" not in data:
        raise ValueError("%s is not a Semgrep result (no 'results' key)" % path)
    return data


def finding(result):
    """Flatten one result into (rule id, severity, path, line, message)."""
    extra = result.get("extra", {})
    return (
        short_id(result.get("check_id", "")),
        str(extra.get("severity", "")).upper(),
        result.get("path", ""),
        int(result.get("start", {}).get("line", 0)),
        " ".join(str(extra.get("message", "")).split()),
    )


def parse_failures(data):
    """Return {extension: (failed files, files)} from the errors and the scanned-path list."""
    failed = {}
    for err in data.get("errors", []):
        kind = err.get("type", "")
        if isinstance(kind, list) and kind:
            kind = kind[0]
        kind = str(kind)
        if "Syntax" in kind or "Pars" in kind or "Lexical" in kind:
            path = err.get("path", "")
            if path:
                failed.setdefault(os.path.splitext(path)[1], set()).add(path)
    scanned = data.get("paths", {}).get("scanned", [])
    totals = {}
    for path in scanned:
        ext = os.path.splitext(path)[1]
        totals[ext] = totals.get(ext, 0) + 1
    return {ext: (len(files), max(totals.get(ext, 0), len(files))) for ext, files in failed.items()}


def summary(data, label, out=sys.stdout, step_summary=None, annotate=False):
    """Print the findings and return the exit code (0 pass, 1 blocking findings or blind scan)."""
    findings = [finding(r) for r in data.get("results", [])]
    errors = [f for f in findings if f[1] == "ERROR"]
    warnings = [f for f in findings if f[1] == "WARNING"]
    other = [f for f in findings if f[1] not in ("ERROR", "WARNING")]
    scanned = len(data.get("paths", {}).get("scanned", []))
    blind = {
        ext: (failed, total)
        for ext, (failed, total) in parse_failures(data).items()
        if ext == ".java" and total and failed / total > MAX_PARSE_FAILURE_SHARE
    }

    lines = ["Semgrep (%s): %d file(s) scanned, %d ERROR, %d WARNING, %d other finding(s)"
             % (label, scanned, len(errors), len(warnings), len(other))]
    for group, name in ((errors, "ERROR (blocking)"), (warnings, "WARNING (reported)"), (other, "OTHER")):
        if not group:
            continue
        lines.append("")
        lines.append(name)
        for rule, _sev, path, line, message in sorted(group, key=lambda f: (f[0], f[2], f[3])):
            lines.append("  %s:%d  %s" % (path, line, rule))
            lines.append("      %s" % message)
    for ext, (failed, total) in blind.items():
        lines.append("")
        lines.append("BLIND: %d of %d %s files could not be parsed, so the rules did not see them"
                     % (failed, total, ext))
    out.write("\n".join(lines) + "\n")

    if step_summary:
        md = ["### Semgrep (%s)" % label, "",
              "%d file(s) scanned: **%d ERROR** (blocking), %d WARNING (reported), %d other." %
              (scanned, len(errors), len(warnings), len(other)), ""]
        by_rule = {}
        for rule, sev, path, line, _msg in findings:
            by_rule.setdefault((rule, sev), []).append("%s:%d" % (path, line))
        if by_rule:
            md += ["| Rule | Severity | Count | First locations |", "|---|---|---|---|"]
            for (rule, sev), where in sorted(by_rule.items(), key=lambda kv: (kv[0][1] != "ERROR", kv[0][0])):
                md.append("| `%s` | %s | %d | %s |" % (rule, sev, len(where), ", ".join("`%s`" % w for w in where[:3])))
        for ext, (failed, total) in blind.items():
            md += ["", "**Blind scan:** %d of %d `%s` files could not be parsed." % (failed, total, ext)]
        with open(step_summary, "a", encoding="utf-8") as handle:
            handle.write("\n".join(md) + "\n")

    if annotate:
        for kind, group in (("error", errors), ("warning", warnings)):
            for rule, _sev, path, line, message in group[:MAX_ANNOTATIONS]:
                out.write("::%s file=%s,line=%d,title=%s::%s\n" % (kind, path, line, rule, message[:200]))
    return 1 if (errors or blind) else 0


def read_annotations(fixture_dir):
    """Return ({(rel path, line): [(kind, rule)]}, [rules with annotations]) from a fixture tree."""
    expected = {}
    for path in sorted(Path(fixture_dir).rglob("*")):
        if not path.is_file() or path.suffix not in (".java", ".yml", ".yaml", ".properties", ".sh", ".py"):
            continue
        text = path.read_text(encoding="utf-8").splitlines()
        for number, line in enumerate(text, start=1):
            match = ANNOTATION.match(line)
            if not match:
                continue
            for rule in [r.strip() for r in match.group(2).split(",") if r.strip()]:
                expected.setdefault((path.as_posix(), number + 1), []).append((match.group(1), rule))
    return expected


def declared_rules(rules_file):
    """Return the rule ids declared in the rules file, in order."""
    ids = []
    for line in Path(rules_file).read_text(encoding="utf-8").splitlines():
        match = RULE_DECL.match(line)
        if match:
            ids.append(match.group(1))
    return ids


def fixtures(data, fixture_dir, rules_file, out=sys.stdout):
    """Compare the findings on the fixture tree with its annotations; return the exit code."""
    expected = read_annotations(fixture_dir)
    found = {}
    for result in data.get("results", []):
        rule, _sev, path, line, _msg = finding(result)
        found.setdefault((Path(path).as_posix(), line), set()).add(rule)

    problems = []
    seen_ruleid, seen_ok = set(), set()
    for key, annotations in sorted(expected.items()):
        got = found.get(key, set())
        for kind, rule in annotations:
            if kind == "ruleid":
                seen_ruleid.add(rule)
                if rule not in got:
                    problems.append("%s:%d expected a finding of %s, got none" % (key[0], key[1], rule))
            else:
                seen_ok.add(rule)
                if rule in got:
                    problems.append("%s:%d must not produce %s but did" % (key[0], key[1], rule))
    for key, rules in sorted(found.items()):
        expected_rules = {rule for kind, rule in expected.get(key, []) if kind == "ruleid"}
        for rule in sorted(rules - expected_rules):
            if (("ok", rule)) in expected.get(key, []):
                continue  # already reported above
            problems.append("%s:%d unexpected finding of %s (no matching '// ruleid:' above it)" % (key[0], key[1], rule))
    for rule in declared_rules(rules_file):
        if rule not in seen_ruleid:
            problems.append("rule %s has no '// ruleid:' fixture" % rule)
        if rule not in seen_ok:
            problems.append("rule %s has no '// ok:' fixture" % rule)

    positives = sum(1 for anns in expected.values() for kind, _ in anns if kind == "ruleid")
    negatives = sum(1 for anns in expected.values() for kind, _ in anns if kind == "ok")
    if problems:
        out.write("Semgrep rule fixtures: %d problem(s)\n" % len(problems))
        for problem in problems:
            out.write("  FAIL  %s\n" % problem)
        return 1
    out.write("Semgrep rule fixtures: OK (%d rules, %d positive and %d negative cases)\n"
              % (len(declared_rules(rules_file)), positives, negatives))
    return 0


# ---------------------------------------------------------------------------
def _result(rule, severity, path, line, message="m"):
    return {"check_id": "x.y." + rule, "path": path, "start": {"line": line},
            "extra": {"severity": severity, "message": message}}


def self_test():
    """Offline proof of the policy on canned results. Returns 0 when every case behaves."""
    failures = []

    def check(name, got, want):
        if got != want:
            failures.append("%s: got %r, wanted %r" % (name, got, want))

    sink = type("Sink", (), {"write": lambda self, text: None})()
    clean = {"results": [], "errors": [], "paths": {"scanned": ["a.java"]}}
    warn = {"results": [_result("dlap-x", "WARNING", "a.java", 3)], "errors": [], "paths": {"scanned": ["a.java"]}}
    err = {"results": [_result("dlap-x", "ERROR", "a.java", 3)], "errors": [], "paths": {"scanned": ["a.java"]}}
    check("clean passes", summary(clean, "t", sink), 0)
    check("warning does not block", summary(warn, "t", sink), 0)
    check("error blocks", summary(err, "t", sink), 1)
    check("rule id prefix is stripped", short_id("home.runner.work.repo.dlap-java-os-command"), "dlap-java-os-command")
    check("plain id is kept", short_id("dlap-java-os-command"), "dlap-java-os-command")

    scanned = ["f%d.java" % i for i in range(100)]
    blind = {"results": [], "paths": {"scanned": scanned},
             "errors": [{"type": "Syntax error", "path": "f%d.java" % i} for i in range(3)]}
    check("a blind Java scan fails", summary(blind, "t", sink), 1)
    # The real result carries the error type as a string or as a list; both must be understood.
    listed = {"results": [], "paths": {"scanned": scanned},
              "errors": [{"type": ["PartialParsing", []], "path": "f%d.java" % i} for i in range(3)]}
    check("a list-typed parse error counts too", summary(listed, "t", sink), 1)
    few = {"results": [], "paths": {"scanned": scanned},
           "errors": [{"type": "Syntax error", "path": "f0.java"}, {"type": "Syntax error", "path": "f1.java"}]}
    check("two unparsed files in a hundred pass", summary(few, "t", sink), 0)

    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        (root / "fx").mkdir()
        (root / "fx" / "A.java").write_text(
            "class A {\n// ruleid: dlap-a\nbad();\n// ok: dlap-a\ngood();\n}\n", encoding="utf-8")
        rules = root / "rules.yml"
        rules.write_text("rules:\n  - id: dlap-a\n    severity: ERROR\n", encoding="utf-8")
        fx = (root / "fx").as_posix()

        def run(results):
            return fixtures({"results": results}, fx, rules, sink)

        hit = _result("dlap-a", "ERROR", fx + "/A.java", 3)
        check("fixtures: positive found, negative clear", run([hit]), 0)
        check("fixtures: a missing positive fails", run([]), 1)
        check("fixtures: a hit on an ok line fails", run([hit, _result("dlap-a", "ERROR", fx + "/A.java", 5)]), 1)
        check("fixtures: an unannotated finding fails", run([hit, _result("dlap-a", "ERROR", fx + "/A.java", 1)]), 1)
        check("fixtures: another rule on an annotated line fails",
              run([hit, _result("dlap-b", "ERROR", fx + "/A.java", 3)]), 1)
        rules.write_text("rules:\n  - id: dlap-a\n  - id: dlap-b\n", encoding="utf-8")
        check("fixtures: a rule without fixtures fails", run([hit]), 1)

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
    if len(argv) >= 3 and argv[1] == "summary":
        label = "project rules"
        if "--label" in argv:
            label = argv[argv.index("--label") + 1]
        try:
            data = load(argv[2])
        except ValueError as exc:
            sys.stderr.write("semgrep report: %s\n" % exc)
            return 2
        return summary(data, label, sys.stdout, os.environ.get("GITHUB_STEP_SUMMARY"),
                       os.environ.get("GITHUB_ACTIONS") == "true")
    if len(argv) == 5 and argv[1] == "fixtures":
        try:
            data = load(argv[2])
        except ValueError as exc:
            sys.stderr.write("semgrep report: %s\n" % exc)
            return 2
        return fixtures(data, argv[3], argv[4])
    sys.stderr.write(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
