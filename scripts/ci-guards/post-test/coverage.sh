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
# Guard (QA-02): at least 70% of the Java lines a change adds or modifies are executed by the tests.
#
# Changed lines are the lines added or modified in DLAP_CI_BASE_SHA..HEAD under
# da-*/src/main/java/**/*.java (tests, resources and generated sources do not count). Coverage comes
# from the JaCoCo XML report of the module (build/reports/jacoco/test/jacocoTestReport.xml, produced
# by the root build.gradle.kts for every Java module). Only lines the JaCoCo report lists are
# executable: comments, imports, blank lines, braces, annotations and abstract methods never count
# either way. A line is covered when at least one instruction on it ran (JaCoCo "ci" > 0); branch
# coverage is not part of the rule.
#
# The ratio is taken over the whole change, not per file, and not over the whole project: legacy
# code with low coverage does not block unrelated work, and new code cannot hide behind it.
#
# Where the reports are read from:
#   $DLAP_CI_REPORTS_DIR/ci-reports-<module>/**/jacocoTestReport.xml   CI (artifacts of the module jobs)
#   <module>/build/reports/jacoco/test/jacocoTestReport.xml            a local run (gradlew :<module>:test)
# A module with changed Java lines and no report fails the hook: either no test ran in it or JaCoCo
# failed, and either way the change is unmeasured.
#
# Usage:
#   DLAP_CI_BASE_SHA=feature/deployment bash scripts/ci-guards/post-test/coverage.sh
#   bash scripts/ci-guards/post-test/coverage.sh --self-test
#
# Environment (all optional):
#   DLAP_CI_BASE_SHA        the base commit (CI hook contract); empty or all zeros skips (77)
#   DLAP_CI_REPORTS_DIR     the folder with the downloaded module reports (default build/ci-reports)
#   DLAP_COVERAGE_MIN       the required percentage, 0 to 100 (default 70)
#   DLAP_COVERAGE_MODULES   comma list of modules to judge, for a local run that built only some of them
#
# Exit 0 pass, 1 below the threshold or a module without a report, 2 usage error, 77 skip (no usable
# base commit).
set -uo pipefail

# The analysis. Embedded so the hook is one file. Subcommand: analyze ROOT BASE REPORTS_DIR MIN MODULES.
read -r -d '' PYCODE <<'PY'
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from fractions import Fraction

SOURCE_RE = re.compile(r"^(da-[^/]+)/src/main/java/(.+\.java)$")
HUNK_RE = re.compile(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@")
REPORT_NAME = "jacocoTestReport.xml"


def git_diff(root, base):
    """Added or modified lines per (module, source path relative to src/main/java)."""
    result = subprocess.run(
        ["git", "-c", "core.quotepath=off", "diff", "-U0", "--no-color", "-M", "--diff-filter=AMR",
         base, "HEAD", "--"],
        cwd=root, stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True,
        encoding="utf-8", errors="replace")
    if result.returncode != 0:
        print("coverage: git diff failed: %s" % result.stderr.strip(), file=sys.stderr)
        sys.exit(2)
    changed = {}
    current = None
    for line in result.stdout.splitlines():
        if line.startswith("+++ "):
            target = line[4:].split("\t")[0]
            match = SOURCE_RE.match(target[2:]) if target.startswith("b/") else None
            current = (match.group(1), match.group(2)) if match else None
            continue
        if current is None:
            continue
        hunk = HUNK_RE.match(line)
        if hunk:
            start = int(hunk.group(1))
            count = 1 if hunk.group(2) is None else int(hunk.group(2))
            if count > 0:
                changed.setdefault(current, set()).update(range(start, start + count))
    return changed


def read_report(path):
    """{'com/dlap/x/Foo.java': {line: covered}} for one JaCoCo XML report."""
    files = {}
    tree = ET.parse(path)
    for package in tree.getroot().iter("package"):
        prefix = package.get("name", "")
        for source in package.iter("sourcefile"):
            key = (prefix + "/" if prefix else "") + source.get("name", "")
            lines = files.setdefault(key, {})
            for line in source.iter("line"):
                number = int(line.get("nr"))
                covered = int(line.get("ci", "0")) > 0
                lines[number] = lines.get(number, False) or covered
    return files


def find_reports(root, reports_dir, modules):
    """module -> list of report files; the CI artefact layout first, the local Gradle layout second."""
    found = {}
    base = reports_dir if os.path.isabs(reports_dir) else os.path.join(root, reports_dir)
    if os.path.isdir(base):
        for entry in sorted(os.listdir(base)):
            if not entry.startswith("ci-reports-"):
                continue
            module = entry[len("ci-reports-"):]
            for folder, _dirs, names in os.walk(os.path.join(base, entry)):
                if REPORT_NAME in names:
                    found.setdefault(module, []).append(os.path.join(folder, REPORT_NAME))
    for module in modules:
        if module in found:
            continue
        local = os.path.join(root, module, "build", "reports", "jacoco", "test", REPORT_NAME)
        if os.path.isfile(local):
            found[module] = [local]
    return found


def ranges(numbers):
    out = []
    for number in sorted(numbers):
        if out and number == out[-1][1] + 1:
            out[-1][1] = number
        else:
            out.append([number, number])
    return ", ".join(str(a) if a == b else "%d-%d" % (a, b) for a, b in out)


def analyze(root, base, reports_dir, minimum, only):
    try:
        required = Fraction(minimum)
    except (ValueError, ZeroDivisionError):
        print("coverage: DLAP_COVERAGE_MIN '%s' is not a number" % minimum, file=sys.stderr)
        return 2
    if required < 0 or required > 100:
        print("coverage: DLAP_COVERAGE_MIN must be between 0 and 100, got %s" % minimum, file=sys.stderr)
        return 2

    changed = git_diff(root, base)
    if only:
        changed = {key: value for key, value in changed.items() if key[0] in only}
    modules = sorted({module for module, _path in changed})
    reports = find_reports(root, reports_dir, modules)

    cache = {}
    rows = []
    missing = []
    for module in modules:
        if module not in reports:
            missing.append(module)
            continue
        merged = {}
        for path in reports[module]:
            if path not in cache:
                try:
                    cache[path] = read_report(path)
                except (ET.ParseError, OSError, ValueError) as error:
                    print("coverage: cannot read %s: %s" % (path, error), file=sys.stderr)
                    return 1
            for key, lines in cache[path].items():
                target = merged.setdefault(key, {})
                for number, covered in lines.items():
                    target[number] = target.get(number, False) or covered
        for (mod, rel), numbers in sorted(changed.items()):
            if mod != module:
                continue
            executable = merged.get(rel, {})
            hit = sorted(n for n in numbers if executable.get(n) is True)
            miss = sorted(n for n in numbers if executable.get(n) is False)
            rows.append((mod + "/src/main/java/" + rel, len(hit), len(miss), miss))

    covered = sum(row[1] for row in rows)
    uncovered = sum(row[2] for row in rows)
    total = covered + uncovered
    github = os.environ.get("GITHUB_ACTIONS") == "true"

    for path, hit, miss, missed in rows:
        if hit + miss == 0:
            continue
        print("  %5.1f%%  %3d/%-3d  %s%s" % (100.0 * hit / (hit + miss), hit, hit + miss, path,
                                             ("  uncovered: " + ranges(missed)) if missed else ""))
        if github and missed:
            print("::warning file=%s,line=%d,title=Changed lines not covered::lines %s run in no test"
                  % (path, missed[0], ranges(missed)))
    skipped = [row[0] for row in rows if row[1] + row[2] == 0]
    if skipped:
        print("coverage: %d changed file(s) with no executable line (interface, constants, comments): %s"
              % (len(skipped), ", ".join(skipped[:5]) + (", ..." if len(skipped) > 5 else "")))

    ok = not missing and covered * 100 >= required * total
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        try:
            with open(summary, "a", encoding="utf-8") as handle:
                handle.write("### Changed-line coverage\n")
                if total:
                    handle.write("%d of %d executable changed lines are covered (%.1f%%, required %s%%): %s\n"
                                 % (covered, total, 100.0 * covered / total, minimum, "pass" if ok else "FAIL"))
                else:
                    handle.write("No executable Java line changed.\n")
                for module in missing:
                    handle.write("- `%s` changed and produced no JaCoCo report\n" % module)
        except OSError:
            pass

    if missing:
        for module in missing:
            print("coverage: FAIL %s has changed Java lines and no JaCoCo report (no test ran, or JaCoCo failed)"
                  % module, file=sys.stderr)
            if github:
                print("::error title=No coverage report::%s changed and has no jacocoTestReport.xml" % module)
        return 1
    if total == 0:
        print("coverage: no executable Java line changed in da-*/src/main/java; nothing to measure")
        return 0
    percent = 100.0 * covered / total
    print("coverage: %d of %d executable changed lines covered = %.1f%% (required %s%%)"
          % (covered, total, percent, minimum))
    if not ok:
        print("coverage: FAIL below %s%%. Add tests that execute the lines listed as uncovered above." % minimum,
              file=sys.stderr)
        if github:
            print("::error title=Changed-line coverage::%.1f%% of the changed lines are covered, %s%% required"
                  % (percent, minimum))
        return 1
    return 0


def main(argv):
    if len(argv) != 7 or argv[1] != "analyze":
        print("usage: analyze ROOT BASE REPORTS_DIR MIN MODULES", file=sys.stderr)
        return 2
    only = {m.strip() for m in argv[6].split(",") if m.strip()}
    return analyze(argv[2], argv[3], argv[4], argv[5], only)


sys.exit(main(sys.argv))
PY

check() {
  local root base py
  root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
  cd "$root" || exit 2
  py="$(command -v python3 || command -v python || true)"
  if [ -z "$py" ]; then
    echo "coverage: python3 is needed and was not found" >&2
    exit 2
  fi
  base="${DLAP_CI_BASE_SHA:-}"
  if [ -z "$base" ] || [ -z "${base//0/}" ]; then
    echo "coverage: no base commit for event '${DLAP_CI_EVENT:-local}'; skipping"
    exit 77
  fi
  if ! git cat-file -e "${base}^{commit}" 2>/dev/null; then
    echo "coverage: base '$base' is not a known commit; skipping"
    exit 77
  fi
  "$py" -c "$PYCODE" analyze "$root" "$base" "${DLAP_CI_REPORTS_DIR:-build/ci-reports}" \
    "${DLAP_COVERAGE_MIN:-70}" "${DLAP_COVERAGE_MODULES:-}"
  exit $?
}

self_test() {
  local script tmp repo hook base trunk passed=0 failed=0 out rc
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
  cp "$script" "$repo/scripts/ci-guards/post-test/coverage.sh"
  hook="$repo/scripts/ci-guards/post-test/coverage.sh"
  local reports="$tmp/reports"

  # javafile <path> <number of lines>: a Java file whose line N reads "line N".
  javafile() {
    local path="$repo/$1" n="$2" i
    mkdir -p "$(dirname "$path")"
    : >"$path"
    for ((i = 1; i <= n; i++)); do printf 'line %d\n' "$i" >>"$path"; done
  }
  commit() { git -C "$repo" add -A && git -C "$repo" commit -q -m "$1"; }
  reset() {
    git -C "$repo" checkout -q "$trunk" && git -C "$repo" reset -q --hard "$base0" && git -C "$repo" clean -q -fd
    base="$base0"
    rm -rf "$reports"
  }
  # report <xml path> <package> <File.java> <"nr:ci" ...>: a JaCoCo-shaped XML with the DTD reference.
  report() {
    local file="$1" pkg="$2" name="$3" spec
    shift 3
    mkdir -p "$(dirname "$file")"
    {
      echo '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
      echo '<!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">'
      echo "<report name=\"selftest\"><package name=\"$pkg\"><sourcefile name=\"$name\">"
      for spec in "$@"; do
        echo "<line nr=\"${spec%%:*}\" mi=\"0\" ci=\"${spec##*:}\" mb=\"0\" cb=\"0\"/>"
      done
      echo '</sourcefile></package></report>'
    } >"$file"
  }
  ci_report() { # ci_report <module> <package> <File.java> <specs...>
    local module="$1"
    shift
    report "$reports/ci-reports-$module/reports/jacoco/test/jacocoTestReport.xml" "$@"
  }
  expect() {
    # expect <description> <wanted exit code> <text the output must contain or empty> [env assignments...]
    local desc="$1" want="$2" text="$3"
    shift 3
    out="$(cd "$repo" && env -u DLAP_CI_ROOT -u GITHUB_ACTIONS -u GITHUB_STEP_SUMMARY -u DLAP_COVERAGE_MIN \
      -u DLAP_COVERAGE_MODULES DLAP_CI_BASE_SHA="$base" DLAP_CI_REPORTS_DIR="$reports" "$@" bash "$hook" 2>&1)"
    rc=$?
    if [ "$rc" -ne "$want" ]; then
      failed=$((failed + 1)); echo "  FAIL  $desc (exit $rc, wanted $want)" >&2; printf '%s\n' "$out" | sed 's/^/        | /' >&2
    elif [ -n "$text" ] && ! printf '%s\n' "$out" | grep -qF -- "$text"; then
      failed=$((failed + 1)); echo "  FAIL  $desc (output lacks '$text')" >&2; printf '%s\n' "$out" | sed 's/^/        | /' >&2
    else
      passed=$((passed + 1)); echo "  ok    $desc"
    fi
  }

  local foo="da-demo/src/main/java/com/dlap/demo/Foo.java"
  local bar="da-other/src/main/java/com/dlap/other/Bar.java"
  local base0

  echo "coverage.sh self-test"
  mkdir -p "$repo/docs" && echo base >"$repo/docs/readme.md"
  commit "base"
  base0="$(git -C "$repo" rev-parse HEAD)"
  base="$base0"
  trunk="$(git -C "$repo" rev-parse --abbrev-ref HEAD)"

  # Ten executable lines (3..12) in a new 12-line file; lines 1 and 2 stand for the package and a comment.
  local all_hit=(3:1 4:1 5:1 6:1 7:1 8:1 9:1 10:1 11:1 12:1)
  javafile "$foo" 12
  commit "add Foo"

  ci_report da-demo com/dlap/demo Foo.java "${all_hit[@]}"
  expect "a new file whose executable lines all ran passes" 0 "10 of 10 executable changed lines covered = 100.0%"

  ci_report da-demo com/dlap/demo Foo.java 3:1 4:1 5:1 6:1 7:1 8:1 9:1 10:0 11:0 12:0
  expect "exactly 70% (7 of 10) passes" 0 "7 of 10"
  ci_report da-demo com/dlap/demo Foo.java 3:1 4:1 5:1 6:1 7:1 8:1 9:0 10:0 11:0 12:0
  expect "60% (6 of 10) fails and names the uncovered lines" 1 "uncovered: 9-12"
  expect "the threshold is configurable (60 passes 6 of 10)" 0 "6 of 10" DLAP_COVERAGE_MIN=60
  expect "a threshold of 61 fails 6 of 10" 1 "required 61%" DLAP_COVERAGE_MIN=61
  expect "a threshold above 100 is a usage error" 2 "between 0 and 100" DLAP_COVERAGE_MIN=101
  expect "a non-numeric threshold is a usage error" 2 "not a number" DLAP_COVERAGE_MIN=abc

  # 6 of 9 is 66.7%: the integer comparison must not round up to pass.
  ci_report da-demo com/dlap/demo Foo.java 3:1 4:1 5:1 6:1 7:1 8:1 9:0 10:0 11:0
  expect "66.7% (6 of 9) fails: no rounding up" 1 "6 of 9"

  # Lines 1 and 2 are not executable in the report, so they are ignored although the commit added them.
  ci_report da-demo com/dlap/demo Foo.java 3:5 4:0
  expect "lines absent from the report never count (1 of 2 at a 50% threshold)" 0 "1 of 2 " DLAP_COVERAGE_MIN=50
  expect "one covered of two executable lines is 50%, below the default" 1 "1 of 2"

  ci_report da-demo com/dlap/demo Foo.java
  expect "a file with no executable line (an interface) passes" 0 "no executable Java line changed"

  rm -rf "$reports"
  expect "a changed module with no report fails" 1 "da-demo has changed Java lines and no JaCoCo report"
  expect "a module outside DLAP_COVERAGE_MODULES is not judged" 0 "nothing to measure" DLAP_COVERAGE_MODULES=da-other

  # The local Gradle layout, used when no CI artefact folder exists.
  report "$repo/da-demo/build/reports/jacoco/test/jacocoTestReport.xml" com/dlap/demo Foo.java "${all_hit[@]}"
  expect "the local build/reports/jacoco layout is read" 0 "100.0%" DLAP_CI_REPORTS_DIR="$tmp/none"
  rm -rf "$repo/da-demo/build"

  reset
  # Only tests, resources and non-module paths changed: nothing to measure.
  javafile "da-demo/src/test/java/com/dlap/demo/FooTest.java" 8
  javafile "other/src/main/java/com/dlap/Elsewhere.java" 8
  echo "x" >"$repo/da-demo/notes.txt"
  commit "tests and others"
  expect "test code, non-module paths and resources are not measured" 0 "nothing to measure"
  reset

  # A modification: only the changed lines are judged, not the rest of the file.
  javafile "$foo" 12
  commit "add Foo again"
  base="$(git -C "$repo" rev-parse HEAD)"
  sed -i 's/^line 5$/changed 5/;s/^line 6$/changed 6/' "$repo/$foo"
  commit "touch lines 5 and 6"
  ci_report da-demo com/dlap/demo Foo.java 3:0 4:0 5:1 6:1 7:0 8:0 9:0 10:0 11:0 12:0
  expect "only the modified lines are judged (2 of 2)" 0 "2 of 2 executable changed lines covered = 100.0%"
  ci_report da-demo com/dlap/demo Foo.java 3:0 4:0 5:1 6:0 7:0 8:0
  expect "one of the two modified lines uncovered is 50% and fails" 1 "uncovered: 6"

  # A deleted line adds nothing to judge.
  sed -i '7d' "$repo/$foo"
  commit "delete line 7"
  expect "a deletion alone judges no new line (still 1 of 2)" 1 "1 of 2"
  reset

  # Two modules aggregate: 4 of 4 plus 1 of 6 is 5 of 10 = 50%.
  javafile "$foo" 6
  javafile "$bar" 8
  commit "two modules"
  ci_report da-demo com/dlap/demo Foo.java 3:1 4:1 5:1 6:1
  ci_report da-other com/dlap/other Bar.java 3:1 4:0 5:0 6:0 7:0 8:0
  expect "the ratio is taken over the whole change (5 of 10 fails)" 1 "5 of 10 executable changed lines covered = 50.0%"
  expect "a green module does not hide a red one: the red file is named" 1 "da-other/src/main/java/com/dlap/other/Bar.java"
  ci_report da-other com/dlap/other Bar.java 3:1 4:1 5:1 6:1 7:1 8:0
  expect "9 of 10 across two modules passes" 0 "9 of 10"
  rm -rf "$reports/ci-reports-da-other"
  expect "one module without a report fails even when the other is fully covered" 1 "da-other has changed Java lines and no JaCoCo report"
  reset

  # A rename with one edited line is judged on that line only.
  javafile "$foo" 12
  commit "Foo for rename"
  base="$(git -C "$repo" rev-parse HEAD)"
  mkdir -p "$repo/da-demo/src/main/java/com/dlap/demo/sub"
  git -C "$repo" mv "$foo" "da-demo/src/main/java/com/dlap/demo/sub/Moved.java"
  sed -i 's/^line 4$/edited 4/' "$repo/da-demo/src/main/java/com/dlap/demo/sub/Moved.java"
  commit "move and edit"
  ci_report da-demo com/dlap/demo/sub Moved.java 3:0 4:1 5:0
  expect "a renamed file is judged on its edited lines only" 0 "1 of 1 executable changed lines covered"
  reset

  # Two reports of one module merge: a line covered in either counts.
  javafile "$foo" 6
  commit "Foo again"
  report "$reports/ci-reports-da-demo/reports/jacoco/test/jacocoTestReport.xml" com/dlap/demo Foo.java 3:1 4:0 5:0 6:0
  report "$reports/ci-reports-da-demo/extra/jacocoTestReport.xml" com/dlap/demo Foo.java 3:0 4:1 5:1 6:1
  expect "two reports of the same module are merged" 0 "4 of 4"
  reset

  # A broken report fails loudly instead of passing.
  javafile "$foo" 6
  commit "Foo once more"
  mkdir -p "$reports/ci-reports-da-demo"
  echo '<report>' >"$reports/ci-reports-da-demo/jacocoTestReport.xml"
  expect "an unreadable report fails" 1 "cannot read"
  reset

  # The base commit contract.
  expect "an empty base skips" 77 "no base commit" DLAP_CI_BASE_SHA=
  expect "an all-zero base (a new branch) skips" 77 "no base commit" DLAP_CI_BASE_SHA=0000000000000000000000000000000000000000
  expect "an unknown base skips" 77 "not a known commit" DLAP_CI_BASE_SHA=1111111111111111111111111111111111111111

  echo "coverage.sh self-test: $passed passed, $failed failed"
  [ "$failed" -eq 0 ] && echo "SELF-TEST OK"
  [ "$failed" -eq 0 ]
}

case "${1:-}" in
  --self-test) self_test; exit $? ;;
  "") check ;;
  *) echo "usage: coverage.sh [--self-test]" >&2; exit 2 ;;
esac
