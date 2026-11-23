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
# Self-test for the CI guard framework: the hook runner (run.sh), the shipped
# hooks (the dash and action-pin hooks are tested here; every other hook that has
# a --self-test mode is run here, and a hook whose self-test is not wired fails
# the run), the CI planner (plan.py) and the security scanner wiring (the Semgrep
# report policy, the dependency and image scan summary, the workflow's job list). Runs in about a minute at worst (the
# coverage-spec-lock self-test builds scratch git repositories), needs bash, git
# and python3. The compliance job runs it before the hooks so a broken guard fails
# loudly instead of passing silently.
#
#   bash scripts/ci-guards/selftest.sh
set -uo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"
PY="$(command -v python3 || command -v python || true)"
[ -n "$PY" ] || { echo "selftest: python3 not found" >&2; exit 2; }

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# A clean environment, so a developer's shell cannot change the outcome.
unset BASE_SHA HEAD_SHA ACTOR IS_DRAFT INPUT_MODULES INPUT_INTEGRATION GITHUB_OUTPUT \
      GITHUB_STEP_SUMMARY GITHUB_ACTIONS DLAP_CI_CHANGED_FILES DLAP_CI_RECENT_COMMITS \
      DLAP_CI_BASE_SHA DLAP_CI_EVENT

passed=0
failed=0
ok()  { passed=$((passed + 1)); echo "  ok    $*"; }
bad() { failed=$((failed + 1)); echo "  FAIL  $*" >&2; }

# expect_rc <description> <wanted exit code> <command...>: output kept in $tmp/out.
expect_rc() {
  local desc="$1" want="$2"
  shift 2
  "$@" >"$tmp/out" 2>&1
  local rc=$?
  if [ "$rc" -eq "$want" ]; then
    ok "$desc"
  else
    bad "$desc (exit $rc, wanted $want)"
    sed 's/^/        | /' "$tmp/out" >&2
  fi
}

# expect_out <description> <fixed text>: the last expect_rc output contains the text.
expect_out() {
  if grep -qF -- "$2" "$tmp/out"; then ok "$1"; else bad "$1 (missing '$2')"; sed 's/^/        | /' "$tmp/out" >&2; fi
}

new_repo() {
  rm -rf "$1" && mkdir -p "$1" && git -C "$1" init -q
  git -C "$1" config user.email selftest@example.invalid
  git -C "$1" config user.name selftest
  git -C "$1" config commit.gpgsign false
  git -C "$1" config core.autocrlf false
}

commit_all() { git -C "$1" add -A && git -C "$1" commit -q -m "$2"; }

# ---------------------------------------------------------------------------
echo "run.sh (hook runner)"
rs="$tmp/runner"
mkdir -p "$rs/scripts/ci-guards/pre" "$rs/scripts/ci-guards/post-test"
cp "$here/run.sh" "$rs/scripts/ci-guards/run.sh"
printf '#!/usr/bin/env bash\necho hook-a-ran\nexit 0\n'  >"$rs/scripts/ci-guards/pre/10-pass.sh"
printf '#!/usr/bin/env bash\necho hook-b-ran\nexit 77\n' >"$rs/scripts/ci-guards/pre/20-skip.sh"

expect_rc "pass and skip hooks give exit 0" 0 bash "$rs/scripts/ci-guards/run.sh" pre
expect_out "reports the passing hook" "PASS pre/10-pass.sh"
expect_out "reports the skipped hook" "SKIP pre/20-skip.sh"

printf '#!/usr/bin/env bash\necho hook-c-ran\nexit 1\n' >"$rs/scripts/ci-guards/pre/30-fail.sh"
printf '#!/usr/bin/env bash\necho hook-d-ran\nexit 0\n' >"$rs/scripts/ci-guards/pre/40-after-fail.sh"
expect_rc "a failing hook gives exit 1" 1 bash "$rs/scripts/ci-guards/run.sh" pre
expect_out "reports the failing hook" "FAIL pre/30-fail.sh"
expect_out "hooks after a failure still run" "hook-d-ran"

expect_rc "an empty phase passes" 0 bash "$rs/scripts/ci-guards/run.sh" post-test
expect_rc "an unknown phase is a usage error" 2 bash "$rs/scripts/ci-guards/run.sh" bogus
expect_rc "a missing phase is a usage error" 2 bash "$rs/scripts/ci-guards/run.sh"

# ---------------------------------------------------------------------------
echo "pre/no-dashes.sh"
dr="$tmp/dash"
new_repo "$dr"
mkdir -p "$dr/scripts/ci-guards/pre"
cp "$here/pre/no-dashes.sh" "$dr/scripts/ci-guards/pre/no-dashes.sh"
em=$'\xe2\x80\x94'
en=$'\xe2\x80\x93'
printf 'old %s text\n' "$em" >"$dr/legacy.txt"
printf 'clean\n' >"$dr/b.txt"
commit_all "$dr" base
base="$(git -C "$dr" rev-parse HEAD)"
hook="$dr/scripts/ci-guards/pre/no-dashes.sh"

printf 'another clean line\n' >>"$dr/b.txt"
commit_all "$dr" clean
expect_rc "clean added lines pass, an old dash is ignored" 0 env DLAP_CI_BASE_SHA="$base" bash "$hook"

printf 'x %s y\n' "$em" >"$dr/c.txt"
commit_all "$dr" em
expect_rc "an added em-dash fails" 1 env DLAP_CI_BASE_SHA="$base" bash "$hook"
expect_out "names the file and line" "c.txt:1"

git -C "$dr" reset -q --hard HEAD~1
printf 'one\ntwo %s three\n' "$en" >"$dr/d.txt"
commit_all "$dr" en
expect_rc "an added en-dash fails" 1 env DLAP_CI_BASE_SHA="$base" bash "$hook"
expect_out "reports the en-dash line" "d.txt:2"

git -C "$dr" reset -q --hard HEAD~1
printf '{"a": "x %s y"}\n' "$em" >"$dr/package-lock.json"
commit_all "$dr" lock
expect_rc "a dash inside package-lock.json is excluded" 0 env DLAP_CI_BASE_SHA="$base" bash "$hook"

expect_rc "no base commit skips" 77 env DLAP_CI_BASE_SHA= bash "$hook"
expect_rc "an all-zero base skips" 77 env DLAP_CI_BASE_SHA=0000000000000000000000000000000000000000 bash "$hook"
expect_rc "an unknown base skips" 77 env DLAP_CI_BASE_SHA=deadbeefdeadbeefdeadbeefdeadbeefdeadbeef bash "$hook"

# --all: every tracked file, honouring scripts/ci-guards/pre/no-dashes.allowlist.
echo "pre/no-dashes.sh --all"
ar="$tmp/dashall"
new_repo "$ar"
mkdir -p "$ar/scripts/ci-guards/pre" "$ar/sub"
cp "$here/pre/no-dashes.sh" "$ar/scripts/ci-guards/pre/no-dashes.sh"
ahook="$ar/scripts/ci-guards/pre/no-dashes.sh"
alist="$ar/scripts/ci-guards/pre/no-dashes.allowlist"
printf 'clean\nalso clean\n' >"$ar/a.txt"
commit_all "$ar" clean
expect_rc "--all passes a clean tree without an allowlist" 0 bash "$ahook" --all

printf 'one\nx %s y\nz %s w\n' "$em" "$en" >"$ar/sub/b.txt"
commit_all "$ar" dashes
expect_rc "--all fails on an unlisted file" 1 bash "$ahook" --all
expect_out "--all names the file and the count" "sub/b.txt: 2 line(s) with a dash, not in the allowlist"

printf 'sub/b.txt|2|data|test fixture\n' >"$alist"
commit_all "$ar" allow
expect_rc "--all passes when the file is listed with a sufficient ceiling" 0 bash "$ahook" --all

printf 'three %s\n' "$em" >>"$ar/sub/b.txt"
commit_all "$ar" spread
expect_rc "--all fails when a listed file exceeds its ceiling" 1 bash "$ahook" --all
expect_out "--all reports the ceiling" "the allowlist ceiling is 2"

printf 'sub/b.txt|3|data|test fixture\nghost.txt|1|data|gone\n' >"$alist"
commit_all "$ar" ceiling
expect_rc "--all passes at the new ceiling and notes a stale entry" 0 bash "$ahook" --all
expect_out "--all notes the stale entry" "ghost.txt: in the allowlist but holds no dash"

printf '# comment\n\nsub/b.txt|3|bogus|reason\n' >"$alist"
expect_rc "--all rejects an unknown category" 2 bash "$ahook" --all
printf 'sub/b.txt|3|data|\n' >"$alist"
expect_rc "--all rejects an entry without a reason" 2 bash "$ahook" --all
printf 'sub/b.txt|many|data|reason\n' >"$alist"
expect_rc "--all rejects a ceiling that is not a number" 2 bash "$ahook" --all
printf 'sub/b.txt|3|data|reason\n' >"$alist"

printf '{"a": "x %s y"}\n' "$em" >"$ar/package-lock.json"
printf 'x %s y\n' "$em" >"$ar/app.min.js"
commit_all "$ar" excluded
expect_rc "--all excludes lockfiles and minified bundles" 0 bash "$ahook" --all
expect_rc "an unknown argument is a usage error" 2 bash "$ahook" --bogus

# ---------------------------------------------------------------------------
echo "pre/actions-pinned.sh"
pr="$tmp/pin"
new_repo "$pr"
mkdir -p "$pr/scripts/ci-guards/pre" "$pr/.github/workflows"
cp "$here/pre/actions-pinned.sh" "$pr/scripts/ci-guards/pre/actions-pinned.sh"
hook="$pr/scripts/ci-guards/pre/actions-pinned.sh"
sha="0123456789abcdef0123456789abcdef01234567"
digest="0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

expect_rc "no workflow files skips" 77 bash "$hook"

write_wf() { printf 'jobs:\n  j:\n    steps:\n%s\n' "$1" >"$pr/.github/workflows/w.yml"; }

write_wf "      - uses: actions/checkout@$sha # v7.0.1
      - uses: ./local-action
      - uses: docker://alpine@sha256:$digest"
expect_rc "SHA pins, a local action and a digest pass" 0 bash "$hook"

write_wf "      - uses: actions/checkout@v4"
expect_rc "a tag reference fails" 1 bash "$hook"
expect_out "names the offender" "actions/checkout@v4"

write_wf "      - uses: foo/bar@main"
expect_rc "a branch reference fails" 1 bash "$hook"

write_wf "      - uses: actions/checkout@${sha:0:39}"
expect_rc "a short SHA fails" 1 bash "$hook"

write_wf "      - uses: docker://alpine:3"
expect_rc "an unpinned docker reference fails" 1 bash "$hook"

write_wf "      - uses: actions/cache@$sha # v3.5.0"
expect_rc "a pinned cache v3 fails" 1 bash "$hook"

write_wf "      - uses: actions/upload-artifact@$sha # v4.6.2"
expect_rc "a pinned upload-artifact v4 passes" 0 bash "$hook"

expect_rc "the repo's own workflows pass" 0 bash "$here/pre/actions-pinned.sh"

# ---------------------------------------------------------------------------
echo "plan.py (module matrix and job gating)"
planner() {
  # planner <event> <changed files or '*'> [VAR=value ...]
  local event="$1" changed="$2"
  shift 2
  env EVENT_NAME="$event" DLAP_CI_CHANGED_FILES="$changed" DLAP_CI_ROOT="$root" "$@" "$PY" "$here/plan.py"
}
field() { grep -E "^$2=" "$1" | head -n 1 | cut -d= -f2-; }
json_len() { printf '%s' "$1" | "$PY" -c 'import json,sys; print(len(json.load(sys.stdin)))'; }
json_has() { printf '%s' "$1" | "$PY" -c 'import json,sys; sys.exit(0 if sys.argv[1] in json.load(sys.stdin) else 1)' "$2"; }

planner schedule '' DLAP_CI_RECENT_COMMITS=5 >"$tmp/p_sched" 2>&1
all="$(field "$tmp/p_sched" modules)"
includes="$(grep -cE '^[[:space:]]*include\("' "$root/settings.gradle.kts")"
if grep -qE '^[[:space:]]*include\("new-ui"\)' "$root/settings.gradle.kts"; then includes=$((includes - 1)); fi
if [ "$(json_len "$all")" -eq "$includes" ] && [ "$includes" -ge 1 ]; then
  ok "the matrix is derived from settings.gradle.kts ($includes modules, new-ui excluded)"
else
  bad "module count $(json_len "$all") differs from settings.gradle.kts includes ($includes)"
fi
if json_has "$all" new-ui; then bad "new-ui must not be in the Gradle matrix"; else ok "new-ui is not in the Gradle matrix"; fi
[ "$(field "$tmp/p_sched" integration)" = true ] && ok "nightly runs integration" || bad "nightly must run integration"

planner schedule '' DLAP_CI_RECENT_COMMITS=0 >"$tmp/p"
[ "$(field "$tmp/p" modules)" = "[]" ] && [ "$(field "$tmp/p" integration)" = false ] \
  && ok "nightly with no commit in 26 hours: skipped" || bad "nightly on an idle repository must be skipped"

planner push 'docs/readme.md' >"$tmp/p"
[ "$(field "$tmp/p" modules)" = "[]" ] && [ "$(field "$tmp/p" run_build)" = false ] \
  && ok "push, docs only: no module" || bad "push, docs only: expected no module"

planner push 'da-catalog/src/main/java/Foo.java' >"$tmp/p"
[ "$(field "$tmp/p" modules)" = '["da-catalog"]' ] && [ "$(field "$tmp/p" integration)" = false ] \
  && ok "push, one module: that module only, no integration" || bad "push, one module: got $(field "$tmp/p" modules)"

planner push 'da-llm-client/src/main/java/Foo.java' >"$tmp/p"
m="$(field "$tmp/p" modules)"
if json_has "$m" da-llm-client && json_has "$m" da-autolabel && json_has "$m" da-policyengine && ! json_has "$m" da-catalog; then
  ok "push, library module: the module and its Gradle dependents"
else
  bad "push, library module: got $m"
fi

for path in settings.gradle.kts build.gradle.kts da-protos/src/main/proto/log_event.proto gradle/wrapper/gradle-wrapper.properties .github/workflows/dlap-ci-cd.yml; do
  planner push "$path" >"$tmp/p"
  [ "$(field "$tmp/p" modules)" = "$all" ] && ok "push, build-wide file '$path': full matrix" || bad "push, build-wide file '$path': got $(field "$tmp/p" modules)"
done

planner push '*' >"$tmp/p"
[ "$(field "$tmp/p" modules)" = "$all" ] && ok "push, unknown range: full matrix" || bad "push, unknown range: expected full matrix"

planner pull_request 'da-reporting/src/main/java/Foo.java' IS_DRAFT=false >"$tmp/p"
[ "$(field "$tmp/p" modules)" = "$all" ] && [ "$(field "$tmp/p" integration)" = false ] \
  && ok "PR, non-spine module: full matrix, no integration" || bad "PR, non-spine module: got $(field "$tmp/p" modules) integration=$(field "$tmp/p" integration)"

planner pull_request 'da-catalog/src/main/java/Foo.java' IS_DRAFT=false >"$tmp/p"
[ "$(field "$tmp/p" modules)" = "$all" ] && [ "$(field "$tmp/p" integration)" = true ] \
  && ok "PR, spine module: full matrix and integration" || bad "PR, spine module: integration=$(field "$tmp/p" integration)"

planner pull_request 'da-catalog/src/main/java/Foo.java' IS_DRAFT=true >"$tmp/p"
[ "$(field "$tmp/p" integration)" = false ] && ok "draft PR: integration skipped" || bad "draft PR: integration must be skipped"

planner pull_request 'e2e-tests/cloud-pipeline-e2e.sh' IS_DRAFT=false >"$tmp/p"
[ "$(field "$tmp/p" modules)" = "[]" ] && [ "$(field "$tmp/p" integration)" = true ] \
  && ok "PR, e2e harness only: integration without a module build" || bad "PR, e2e harness only: unexpected plan"

planner pull_request 'docs/specs/log-event.md' IS_DRAFT=false >"$tmp/p"
[ "$(field "$tmp/p" modules)" = "[]" ] && [ "$(field "$tmp/p" integration)" = false ] \
  && ok "PR, docs only: nothing to build" || bad "PR, docs only: unexpected plan"

planner pull_request 'da-reporting/build.gradle.kts' IS_DRAFT=false 'ACTOR=dependabot[bot]' >"$tmp/p"
[ "$(field "$tmp/p" modules)" = '["da-reporting"]' ] && [ "$(field "$tmp/p" integration)" = false ]   && ok "bot PR: touched module only" || bad "bot PR: got $(field "$tmp/p" modules)"

planner pull_request 'settings.gradle.kts' IS_DRAFT=false 'ACTOR=dependabot[bot]' >"$tmp/p"
[ "$(field "$tmp/p" modules)" = "$all" ] && ok "bot PR, build-wide file: full matrix" || bad "bot PR, build-wide file: got $(field "$tmp/p" modules)"

planner workflow_dispatch '' INPUT_MODULES=da-catalog,da-reporting INPUT_INTEGRATION=false >"$tmp/p"
[ "$(field "$tmp/p" modules)" = '["da-catalog","da-reporting"]' ] && [ "$(field "$tmp/p" integration)" = false ] \
  && ok "manual run: selected modules, integration off" || bad "manual run: got $(field "$tmp/p" modules)"

planner workflow_dispatch '' INPUT_MODULES=all INPUT_INTEGRATION=true >"$tmp/p"
[ "$(field "$tmp/p" modules)" = "$all" ] && [ "$(field "$tmp/p" integration)" = true ] \
  && ok "manual run: all modules, integration on" || bad "manual run: all modules expected"

expect_rc "manual run with an unknown module fails" 1 planner workflow_dispatch '' INPUT_MODULES=da-bogus

# Dependency-Check and the script self-tests are gated by what changed (work package CI-02b).
expect_field() { # expect_field <description> <output file> <name> <wanted value>
  if [ "$(field "$2" "$3")" = "$4" ]; then ok "$1"; else bad "$1 ($3=$(field "$2" "$3"), wanted $4)"; fi
}
planner push 'da-catalog/build.gradle.kts' >"$tmp/p"
expect_field "push, a module build file: dependency scan on" "$tmp/p" scan_deps true
planner push 'da-catalog/src/main/java/Foo.java' >"$tmp/p"
expect_field "push, module source only: dependency scan off" "$tmp/p" scan_deps false
planner push 'docs/readme.md' >"$tmp/p"
expect_field "push, docs only: dependency scan off" "$tmp/p" scan_deps false
for path in build.gradle.kts gradle/wrapper/gradle-wrapper.properties new-ui new-ui/frontend/package-lock.json \
            .github/dependency-check-suppressions.xml .github/osv-scanner.toml .github/trivy-ignore.yaml \
            scripts/ci-guards/scan/odc.sh .github/workflows/dlap-ci-cd.yml; do
  planner pull_request "$path" IS_DRAFT=false >"$tmp/p"
  expect_field "PR, '$path': dependency scan on" "$tmp/p" scan_deps true
done
planner push '*' >"$tmp/p"
expect_field "push, unknown range: dependency scan on" "$tmp/p" scan_deps true
planner schedule '' DLAP_CI_RECENT_COMMITS=5 >"$tmp/p"
expect_field "nightly: dependency scan on" "$tmp/p" scan_deps true
expect_field "nightly: script tests on" "$tmp/p" script_tests true
planner schedule '' DLAP_CI_RECENT_COMMITS=0 >"$tmp/p"
expect_field "idle nightly: dependency scan off" "$tmp/p" scan_deps false
expect_field "idle nightly: script tests off" "$tmp/p" script_tests false
planner workflow_dispatch '' INPUT_MODULES=all >"$tmp/p"
expect_field "manual run: dependency scan on by default" "$tmp/p" scan_deps true
planner workflow_dispatch '' INPUT_MODULES=all INPUT_SCANS=false >"$tmp/p"
expect_field "manual run, scans=false: dependency scan off" "$tmp/p" scan_deps false
planner pull_request 'deployment/environments/pilot/up.sh' IS_DRAFT=false >"$tmp/p"
expect_field "PR, pilot script: script tests on" "$tmp/p" script_tests true
planner pull_request 'e2e-tests/harness-lib.sh' IS_DRAFT=false >"$tmp/p"
expect_field "PR, e2e harness: script tests on" "$tmp/p" script_tests true
planner pull_request 'da-reporting/src/main/java/Foo.java' IS_DRAFT=false >"$tmp/p"
expect_field "PR, module source only: script tests off" "$tmp/p" script_tests false
planner push 'docs/api/openapi/catalog.json' >"$tmp/p"
[ "$(field "$tmp/p" modules)" = "$all" ] && ok "push, an OpenAPI contract: full matrix, so the export tests run (Q-275)" \
  || bad "push, an OpenAPI contract: got $(field "$tmp/p" modules)"

GITHUB_OUTPUT="$tmp/gh_out" planner push 'da-catalog/x.java' >/dev/null 2>&1
if grep -q '^modules=\["da-catalog"\]$' "$tmp/gh_out" && grep -q '^run_build=true$' "$tmp/gh_out"; then
  ok "writes step outputs to GITHUB_OUTPUT"
else
  bad "GITHUB_OUTPUT content unexpected: $(cat "$tmp/gh_out" 2>/dev/null)"
fi

# ---------------------------------------------------------------------------
# These hooks carry their own scenario tests (a temporary tree with violations and clean
# input); run them here so the compliance job proves them before it trusts them.
echo "pre/no-instant-now.sh (built-in self-test)"
expect_rc "no-instant-now flags new and growing calls, ratchets down" 0   bash "$here/pre/no-instant-now.sh" --self-test
expect_out "no-instant-now reports its self-test" "SELF-TEST OK"

echo "pre/disabled-tests-tracked.sh (built-in self-test)"
expect_rc "disabled-tests-tracked flags an untracked @Disabled" 0   bash "$here/pre/disabled-tests-tracked.sh" --self-test
expect_out "disabled-tests-tracked reports its self-test" "SELF-TEST OK"

echo "pre/no-invented-data.sh (built-in self-test)"
expect_rc "no-invented-data flags invented data, allows demo and test code" 0   bash "$here/pre/no-invented-data.sh" --self-test
expect_out "no-invented-data reports its self-test" "SELF-TEST OK"

echo "pre/dependency-licenses.sh (built-in self-test)"
expect_rc "dependency-licenses policy cases pass" 0   bash "$here/pre/dependency-licenses.sh" --self-test
expect_out "dependency-licenses reports its self-test" "self-test OK"

# no-mvel.sh (SEC-04, ADR-0057, Q-152): its built-in self-test, then the hook itself on scratch trees, so
# the wiring is proved from outside as well. A copy with a dead import pattern must fail its own
# self-test, which shows the self-test is not vacuous. --resolved needs Gradle and the network and is
# not run here; the compliance job runs it as its own step (Q-152, Q-252).
echo "pre/no-mvel.sh"
expect_rc "no-mvel built-in self-test passes" 0 bash "$here/pre/no-mvel.sh" --self-test
expect_out "no-mvel reports its self-test" "SELF-TEST OK"

mv="$tmp/mvel"
mkdir -p "$mv/scripts/ci-guards/pre" "$mv/da-x/src/main/java/x"
cp "$here/pre/no-mvel.sh" "$mv/scripts/ci-guards/pre/no-mvel.sh"
hook="$mv/scripts/ci-guards/pre/no-mvel.sh"
printf 'dependencies {\n    implementation("com.google.re2j:re2j:1.8")\n}\n' >"$mv/da-x/build.gradle.kts"
printf 'class Fine { String note = "no expression language"; }\n' >"$mv/da-x/src/main/java/x/Fine.java"
expect_rc "no-mvel: a clean tree passes" 0 bash "$hook"
expect_rc "no-mvel: --resolved without gradlew skips" 77 bash "$hook" --resolved
expect_rc "no-mvel: an unknown argument is a usage error" 2 bash "$hook" --bogus

printf 'dependencies {\n    implementation("org.jeasy:easy-rules-mvel:4.1.0")\n}\n' >"$mv/da-x/build.gradle.kts"
expect_rc "no-mvel: an Easy Rules dependency fails" 1 bash "$hook"
expect_out "no-mvel: names the build file" "da-x/build.gradle.kts"
printf 'dependencies {\n    implementation("com.google.re2j:re2j:1.8")\n}\n' >"$mv/da-x/build.gradle.kts"

printf 'import org.mvel2.MVEL;\nclass Bad { }\n' >"$mv/da-x/src/main/java/x/Bad.java"
expect_rc "no-mvel: an MVEL import fails" 1 bash "$hook"
expect_out "no-mvel: names the source file" "da-x/src/main/java/x/Bad.java"
rm -f "$mv/da-x/src/main/java/x/Bad.java"

sed 's/^IMPORT_PATTERN=.*/IMPORT_PATTERN='"'"'^never-matches$'"'"'/' "$here/pre/no-mvel.sh" >"$mv/scripts/ci-guards/pre/no-mvel-broken.sh"
expect_rc "no-mvel: a copy with a dead import pattern fails its own self-test" 2   bash "$mv/scripts/ci-guards/pre/no-mvel-broken.sh" --self-test
expect_out "no-mvel: the broken copy says why" "SELF-TEST FAILED"

# coverage-spec-lock.sh (SPEC-01, Q-123): a post-test hook with a 22-case built-in self-test that
# builds scratch git repositories (a spec and a parser file in one commit fails, in two commits passes,
# merge commits are skipped).
echo "post-test/coverage-spec-lock.sh (built-in self-test)"
expect_rc "coverage-spec-lock built-in self-test passes" 0 bash "$here/post-test/coverage-spec-lock.sh" --self-test
expect_out "coverage-spec-lock reports no failed case" " 0 failed"
expect_rc "coverage-spec-lock: no base commit skips" 77 env DLAP_CI_BASE_SHA= bash "$here/post-test/coverage-spec-lock.sh"
expect_rc "coverage-spec-lock: an unknown argument is a usage error" 2 bash "$here/post-test/coverage-spec-lock.sh" --bogus

# openapi-drift.sh (API-01, ADR-0064, Q-275): a post-test hook with a 26-case built-in self-test on
# scratch trees (missing test or contract, orphan contract, shared block drift, regeneration that
# changes, adds or fails, the breaking-change warning rules). The hook itself runs Gradle in CI.
echo "post-test/openapi-drift.sh (built-in self-test)"
expect_rc "openapi-drift built-in self-test passes" 0 bash "$here/post-test/openapi-drift.sh" --self-test
expect_out "openapi-drift reports no failed case" " 0 failed"

# coverage.sh (QA-02): the 70% changed-line coverage gate. Its built-in self-test builds scratch git
# repositories and JaCoCo-shaped XML reports (boundary at exactly 70%, no rounding up, renames,
# deletions, two modules, a module without a report, an unreadable report, the base-commit skips).
echo "post-test/coverage.sh (built-in self-test)"
expect_rc "coverage built-in self-test passes" 0 bash "$here/post-test/coverage.sh" --self-test
expect_out "coverage reports no failed case" " 0 failed"
expect_rc "coverage: no base commit skips" 77 env DLAP_CI_BASE_SHA= bash "$here/post-test/coverage.sh"
expect_rc "coverage: an unknown argument is a usage error" 2 bash "$here/post-test/coverage.sh" --bogus

# osv.sh and scan-suppressions.sh (CI-02b): the OSV-Scanner gate and the rule that no scanner finding is
# suppressed without a reason and an expiry date. Both self-tests are offline.
echo "pre/osv.sh (built-in self-test)"
expect_rc "osv: SBOM conversion and the CVSS policy pass" 0 bash "$here/pre/osv.sh" --self-test
expect_out "osv reports its self-test" "self-test OK"

echo "pre/scan-suppressions.sh (built-in self-test)"
expect_rc "scan-suppressions: reasoned, dated entries pass; the rest fail" 0 bash "$here/pre/scan-suppressions.sh" --self-test
expect_out "scan-suppressions reports its self-test" "self-test OK"

echo "semgrep/report.py and scan/summary.py (built-in self-tests)"
expect_rc "semgrep report policy and fixture checker pass" 0 "$PY" "$here/semgrep/report.py" --self-test
expect_out "semgrep report reports its self-test" "SELF-TEST OK"
expect_rc "scan summary policy passes (Dependency-Check and Trivy)" 0 "$PY" "$here/scan/summary.py" --self-test
expect_out "scan summary reports its self-test" "SELF-TEST OK"

# trivy.sh passes --timeout (Q-328). A stand-in docker records the arguments of the scanner run, so no
# image is pulled and no database is downloaded: the test is the argument list, not Trivy.
echo "scan/trivy.sh arguments"
fb="$tmp/fakebin"
mkdir -p "$fb"
cat >"$fb/docker" <<'FAKEEOF'
#!/usr/bin/env bash
case "$1" in
  image) exit 0 ;;
  save) shift; while [ $# -gt 0 ]; do if [ "$1" = "-o" ]; then : > "$2"; fi; shift; done; exit 0 ;;
  run) printf '%s\n' "$@" > "$FAKE_DOCKER_ARGS"; printf '{"Results":[]}' > "$TRIVY_OUT/trivy.json"; exit 0 ;;
esac
exit 0
FAKEEOF
chmod +x "$fb/docker"
mkdir -p "$tmp/trivy-out"
FAKE_DOCKER_ARGS="$tmp/trivy-args-default" TRIVY_OUT="$tmp/trivy-out" PATH="$fb:$PATH"   bash "$here/scan/trivy.sh" image selftest:image >"$tmp/out" 2>&1
trc=$?
if [ "$trc" -eq 0 ] && [ -f "$tmp/trivy-args-default" ]; then ok "trivy.sh runs against the stand-in docker"; else bad "trivy.sh against the stand-in docker (exit $trc)"; sed 's/^/        | /' "$tmp/out" >&2; fi
if tr -d '\r' <"$tmp/trivy-args-default" | grep -Fx -A1 -- '--timeout' | tail -n 1 | grep -Fxq '30m'; then
  ok "trivy.sh passes --timeout 30m by default (Q-328)"
else
  bad "trivy.sh does not pass --timeout 30m by default"; sed 's/^/        | /' "$tmp/trivy-args-default" >&2
fi
FAKE_DOCKER_ARGS="$tmp/trivy-args-set" TRIVY_OUT="$tmp/trivy-out" TRIVY_TIMEOUT=45m PATH="$fb:$PATH"   bash "$here/scan/trivy.sh" image selftest:image >"$tmp/out" 2>&1
if tr -d '\r' <"$tmp/trivy-args-set" | grep -Fx -A1 -- '--timeout' | tail -n 1 | grep -Fxq '45m'; then
  ok "TRIVY_TIMEOUT overrides the Trivy time limit"
else
  bad "TRIVY_TIMEOUT does not reach Trivy"; sed 's/^/        | /' "$tmp/trivy-args-set" >&2
fi

# Wiring of the security scanners. The scanner behaviour is proved above and by the Semgrep fixtures;
# this proves the pieces are connected: every job is in the CI gate, every image is pinned by digest, the
# scanners the work plan names are all called, and Dependabot is still configured.
echo "security scanner wiring"
wf="$root/.github/workflows/dlap-ci-cd.yml"
"$PY" -I - "$wf" >"$tmp/wiring" 2>&1 <<'PYEOF'
import re
import sys

text = open(sys.argv[1], encoding="utf-8").read()
jobs_block = text.split("\njobs:\n", 1)[1]
jobs = re.findall(r"(?m)^  ([a-z][a-z0-9-]*):\s*$", jobs_block)
gate = re.search(r"(?ms)^  ci-gate:.*?^    needs:\s*\[([^\]]*)\]", jobs_block)
needs = [n.strip() for n in gate.group(1).split(",")] if gate else []
problems = []
for job in jobs:
    if job != "ci-gate" and job not in needs:
        problems.append("job %s is not in the needs list of ci-gate" % job)
for need in needs:
    if need not in jobs:
        problems.append("ci-gate needs %s, which is not a job" % need)
for required in ("sast", "dependency-check", "compliance-scan", "secret-scan", "build-and-test", "script-tests", "pilot-tests"):
    if required not in jobs:
        problems.append("job %s is missing" % required)
for call in ("scripts/ci-guards/semgrep/run.sh test", "scripts/ci-guards/semgrep/run.sh scan",
             "scripts/ci-guards/scan/trivy.sh fs", "scripts/ci-guards/scan/trivy.sh image",
             "scripts/ci-guards/scan/odc.sh", "./gradlew bootJar -x test", "scripts/ci-guards/run.sh pre",
             "pre/no-mvel.sh --resolved", "e2e-tests/test-harness-lib.sh --quick",
             "deployment/environments/pilot/up-test.sh --quick",
             "test-demo.sh --no-docker",
             "deployment/environments/pilot/tls/test-tls.sh --quick",
             "deployment/dr/test-dr.sh --quick"):
    if call not in text:
        problems.append("the workflow never runs: %s" % call)
if "fetch-depth: 0" not in jobs_block.split("  post-test-guards:", 1)[1].split("  ci-gate:", 1)[0]:
    problems.append("post-test-guards does not fetch full history (Q-252)")
for problem in problems:
    print("PROBLEM: " + problem)
print("jobs: " + ", ".join(jobs))
sys.exit(1 if problems else 0)
PYEOF
wrc=$?
if [ "$wrc" -eq 0 ]; then ok "every workflow job is in the CI gate and every scanner is called"; else bad "workflow wiring"; sed 's/^/        | /' "$tmp/wiring" >&2; fi

for script in semgrep/run.sh scan/trivy.sh scan/odc.sh pre/osv.sh; do
  if grep -Eq '@sha256:[0-9a-f]{64}' "$here/$script"; then ok "$script pins its image by digest"; else bad "$script does not pin its image by digest"; fi
done

dep="$root/.github/dependabot.yml"
for eco in gradle npm github-actions; do
  if grep -q "package-ecosystem: \"$eco\"" "$dep"; then ok "Dependabot still updates $eco"; else bad "Dependabot lost the $eco ecosystem"; fi
done

# A hook that offers --self-test but is not run above would repeat Q-123 and Q-152: its guard could rot
# unseen. List every hook with the flag and require the call in this file.
echo "every hook self-test is wired"
for hook in "$here"/pre/*.sh "$here"/post-test/*.sh; do
  name="$(basename "$hook")"
  grep -q -- '--self-test' "$hook" || continue
  if grep -qF -- "${name}\" --self-test" "${BASH_SOURCE[0]}"; then
    ok "$name --self-test is run here"
  else
    bad "$name offers --self-test but selftest.sh does not run it"
  fi
done

# ---------------------------------------------------------------------------
echo
echo "ci-guards selftest: $passed passed, $failed failed"
[ "$failed" -eq 0 ]
