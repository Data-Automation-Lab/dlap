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
"""CI planner for .github/workflows/dlap-ci-cd.yml.

Decides which Gradle modules and which optional jobs a run executes, so that
the private-repo Actions minutes are spent only where a change can matter
(decision D15). The module list is never hard-coded: it is read from
settings.gradle.kts, so a module added there (for example da-log-fixtures)
joins the matrix without a workflow edit.

Policy (D15):
  pull_request       full module matrix when any code or build path changed,
                     none for docs-only changes; a bot author (Dependabot opens
                     bursts of PRs) is planned like a push, to protect the quota
  push               only the touched modules plus their Gradle dependents,
                     the full matrix when a build-wide file changed
  schedule           full matrix plus integration (nightly); skipped when no
                     commit landed in the last 26 hours, so an idle repository
                     spends one minute instead of a full run
  workflow_dispatch  modules from the "modules" input, integration from the
                     "integration" input

Integration (cloud-pipeline-e2e.sh) never runs on push. On a pull request it
runs only for a non-draft PR that touches the log, discovery or catalog spine.

Dependency scan (scan_deps): the OWASP Dependency-Check job builds every Spring Boot jar and scans
them, which costs minutes, so it runs nightly, on a manual run (input "scans"), and on a push or pull
request that can change the dependency set or the scanner configuration (build files, lockfiles, the
suppression files, the scan scripts, the workflow). The other scanners run on every event.

Script tests (script_tests): the offline self-tests of the harness lock library, the pilot up
script and the demo launcher run when e2e-tests/, deployment/environments/, deployment/platform/ or the
workflow change, nightly, and on a manual run.

Inputs (environment): EVENT_NAME, ACTOR, BASE_SHA, HEAD_SHA, IS_DRAFT,
INPUT_MODULES, INPUT_INTEGRATION, INPUT_SCANS. For the self-test, DLAP_CI_CHANGED_FILES (newline
separated, or "*" for unknown) replaces the git diff and DLAP_CI_RECENT_COMMITS
replaces the recent-commit count.
Outputs (GITHUB_OUTPUT, or stdout when unset): modules, run_build,
integration, post_test, scan_deps, script_tests, reason.
"""

import json
import os
import re
import subprocess
import sys
from pathlib import Path

# Modules that are not built by the Gradle matrix: the UI is built and tested
# by the ui repo's own workflow (ui-ci.yml).
NON_JVM_MODULES = {"new-ui"}

# Modules whose change can break the log -> discovery -> catalog spine.
SPINE_MODULES = {"da-catalog", "da-logingest", "da-discovery"}

# A change here can affect every module, so it selects the full matrix.
GLOBAL_FILES = {
    "build.gradle.kts",
    "settings.gradle.kts",
    "gradle.properties",
    "gradlew",
    "gradlew.bat",
    ".github/workflows/dlap-ci-cd.yml",
    "scripts/ci-guards/plan.py",
    "scripts/ci-guards/run.sh",
}
# docs/api/openapi/ holds the committed OpenAPI contracts: the drift hook compares them with what the
# controllers produce, so a contract-only change must run the export tests (Q-275).
GLOBAL_PREFIXES = ("gradle/", "da-protos/", "scripts/ci-guards/post-test/", "docs/api/openapi/")

# Non-module paths that still exercise the integration stack.
SPINE_PREFIXES = ("e2e-tests/", "deployment/environments/dev/", "deployment/platform/")

# Paths the offline script self-tests cover: the harness library, the pilot up script and the demo launcher.
SCRIPT_TEST_PREFIXES = ("e2e-tests/", "deployment/environments/", "deployment/platform/")

# A change here can alter the dependency set or the dependency scanners' verdict (see scan_deps above).
SCAN_FILES = {
    "build.gradle.kts",
    "settings.gradle.kts",
    "gradle.properties",
    ".gitmodules",
    "new-ui",
    ".github/workflows/dlap-ci-cd.yml",
    ".github/dependency-check-suppressions.xml",
    ".github/osv-scanner.toml",
    ".github/trivy-ignore.yaml",
    "scripts/ci-guards/plan.py",
}
SCAN_PREFIXES = ("gradle/", "scripts/ci-guards/scan/")
SCAN_SUFFIXES = ("/build.gradle.kts", "package-lock.json", "gradle.lockfile")

# A scheduled run is skipped when the checked-out branch has no commit this recent.
IDLE_HOURS = 26

PROJECT_REF = re.compile(r"""project\(\s*["']:([\w.\-]+)["']\s*\)""")


def read_modules(root):
    """Return the Gradle modules named by include(...) in settings.gradle.kts."""
    text = (root / "settings.gradle.kts").read_text(encoding="utf-8")
    found = []
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped.startswith("include("):
            continue
        for name in re.findall(r'"([^"]+)"', stripped):
            name = name.lstrip(":")
            if name and name not in found:
                found.append(name)
    return [m for m in found if m not in NON_JVM_MODULES]


def read_dependents(root, modules):
    """Map each module to the modules that declare a project(...) dependency on it."""
    dependents = {m: set() for m in modules}
    for module in modules:
        for build_file in ("build.gradle.kts", "build.gradle"):
            path = root / module / build_file
            if not path.is_file():
                continue
            for dep in PROJECT_REF.findall(path.read_text(encoding="utf-8")):
                if dep in dependents and dep != module:
                    dependents[dep].add(module)
    return dependents


def closure(seed, dependents):
    """Return seed plus every transitive dependent, keeping a stable order."""
    seen = set(seed)
    queue = list(seed)
    while queue:
        for dependent in sorted(dependents.get(queue.pop(), ())):
            if dependent not in seen:
                seen.add(dependent)
                queue.append(dependent)
    return seen


def git_changed_files(base, head):
    """Return the changed paths between two commits, or None when they are unknown."""
    if not base or not head or set(base) == {"0"}:
        return None
    result = subprocess.run(
        ["git", "diff", "--name-only", "--no-renames", base, head],
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        return None
    return [line for line in result.stdout.splitlines() if line]


def recent_commit_count(hours):
    """Return how many commits landed in the last hours, or None when git cannot tell."""
    override = os.environ.get("DLAP_CI_RECENT_COMMITS")
    if override is not None:
        return int(override)
    result = subprocess.run(
        ["git", "log", "--since=%d hours ago" % hours, "--format=%H"],
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        return None
    return len([line for line in result.stdout.splitlines() if line])


def parse_module_input(raw, modules):
    """Resolve the manual "modules" input ("all" or a comma list) against the known modules."""
    raw = (raw or "all").strip()
    if raw in ("", "all"):
        return list(modules)
    wanted = [item.strip() for item in raw.split(",") if item.strip()]
    unknown = [item for item in wanted if item not in modules]
    if unknown:
        raise SystemExit(
            "unknown module(s) %s; known modules: %s" % (", ".join(unknown), ", ".join(modules))
        )
    return [m for m in modules if m in wanted]


def is_global(path):
    """True when a changed path can affect every module."""
    return path in GLOBAL_FILES or path.startswith(GLOBAL_PREFIXES)


def affects_dependencies(path):
    """True when a changed path can alter the dependency set or what the dependency scanners decide."""
    return path in SCAN_FILES or path.startswith(SCAN_PREFIXES) or path.endswith(SCAN_SUFFIXES)


def touches_script_tests(path):
    """True when a changed path is covered by the offline script self-tests."""
    return path.startswith(SCRIPT_TEST_PREFIXES) or path == ".github/workflows/dlap-ci-cd.yml"


def plan(event, modules, dependents, changed, draft, bot, input_modules, input_integration,
         has_post_hooks, recent_commits=None, input_scans=""):
    """Compute the run plan. changed is a list of paths, or None when unknown."""
    if event == "workflow_dispatch":
        selected = parse_module_input(input_modules, modules)
        integration = str(input_integration).strip().lower() != "false"
        scan_deps = str(input_scans).strip().lower() != "false"
        script_tests = True
        reason = "manual run: modules=%s integration=%s scans=%s" % (",".join(selected) or "none", integration, scan_deps)
    elif event == "schedule" and recent_commits == 0:
        selected = []
        integration = False
        scan_deps = False
        script_tests = False
        reason = "nightly run skipped: no commit in the last %d hours" % IDLE_HOURS
    elif event == "schedule":
        selected = list(modules)
        integration = True
        scan_deps = True
        script_tests = True
        reason = "nightly run: full matrix, integration and dependency scans"
    elif changed is None:
        selected = list(modules)
        integration = event == "pull_request" and not draft
        scan_deps = True
        script_tests = True
        reason = "changed paths unknown: full matrix"
    else:
        touched = {p.split("/", 1)[0] for p in changed} & set(modules)
        build_wide = any(is_global(p) for p in changed)
        spine_touched = (
            build_wide
            or bool(touched & SPINE_MODULES)
            or any(p.startswith(SPINE_PREFIXES) for p in changed)
        )
        if event == "pull_request" and not bot:
            selected = list(modules) if (touched or build_wide) else []
            reason = "pull request: %d changed path(s), %s" % (
                len(changed),
                "full matrix" if selected else "no module affected",
            )
        else:
            if build_wide:
                selected = list(modules)
            else:
                affected = closure(touched, dependents)
                selected = [m for m in modules if m in affected]
            reason = "%s: %d changed path(s), %s" % (
                "bot pull request" if event == "pull_request" else "push",
                len(changed),
                "build-wide change, full matrix" if build_wide else "touched modules and dependents",
            )
        integration = event == "pull_request" and spine_touched and not draft
        scan_deps = any(affects_dependencies(p) for p in changed)
        script_tests = any(touches_script_tests(p) for p in changed)
    return {
        "modules": selected,
        "run_build": bool(selected),
        "integration": bool(integration),
        "post_test": bool(selected) and has_post_hooks,
        "scan_deps": bool(scan_deps),
        "script_tests": bool(script_tests),
        "reason": reason,
    }


def changed_files_for(event, base, head):
    """Resolve the changed paths for the event, honouring the self-test override."""
    override = os.environ.get("DLAP_CI_CHANGED_FILES")
    if override is not None:
        return None if override.strip() == "*" else [l for l in override.splitlines() if l.strip()]
    if event in ("pull_request", "push"):
        return git_changed_files(base, head)
    return None


def emit(result):
    """Write the outputs and a short summary."""
    lines = {
        "modules": json.dumps(result["modules"], separators=(",", ":")),
        "run_build": str(result["run_build"]).lower(),
        "integration": str(result["integration"]).lower(),
        "post_test": str(result["post_test"]).lower(),
        "scan_deps": str(result["scan_deps"]).lower(),
        "script_tests": str(result["script_tests"]).lower(),
        "reason": result["reason"],
    }
    out = os.environ.get("GITHUB_OUTPUT")
    text = "".join("%s=%s\n" % (k, v) for k, v in lines.items())
    if out:
        with open(out, "a", encoding="utf-8") as handle:
            handle.write(text)
    else:
        sys.stdout.write(text)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as handle:
            handle.write("### CI plan\n\n%s\n\n" % result["reason"])
            handle.write("- modules: `%s`\n" % (", ".join(result["modules"]) or "none"))
            handle.write("- integration: `%s`\n" % lines["integration"])
            handle.write("- post-test guards: `%s`\n" % lines["post_test"])


def main():
    """Entry point: read the environment, plan, emit."""
    root = Path(os.environ.get("DLAP_CI_ROOT", ".")).resolve()
    event = os.environ.get("EVENT_NAME", "")
    modules = read_modules(root)
    if not modules:
        raise SystemExit("no Gradle modules found in settings.gradle.kts")
    changed = changed_files_for(event, os.environ.get("BASE_SHA", ""), os.environ.get("HEAD_SHA", ""))
    post_dir = root / "scripts" / "ci-guards" / "post-test"
    has_post_hooks = post_dir.is_dir() and any(post_dir.glob("*.sh"))
    result = plan(
        event,
        modules,
        read_dependents(root, modules),
        changed,
        os.environ.get("IS_DRAFT", "").strip().lower() == "true",
        os.environ.get("ACTOR", "").strip().endswith("[bot]"),
        os.environ.get("INPUT_MODULES", ""),
        os.environ.get("INPUT_INTEGRATION", ""),
        has_post_hooks,
        recent_commit_count(IDLE_HOURS) if event == "schedule" else None,
        os.environ.get("INPUT_SCANS", ""),
    )
    emit(result)


if __name__ == "__main__":
    main()
