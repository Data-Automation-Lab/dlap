<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
-->

# CI guards and CI planning

Scripts that `.github/workflows/dlap-ci-cd.yml` runs. A guard is a small shell
script that fails the build when a repo rule is broken. Anyone can run the same
scripts locally.

## Layout

| Path | Purpose |
|---|---|
| `pre/*.sh` | Guards that need only the source tree. Run in the compliance job, before any build. |
| `post-test/*.sh` | Guards that need test output (coverage, fixture and API drift). Run once, after every module test job passed. |
| `run.sh` | Runs every hook of one phase: `bash scripts/ci-guards/run.sh pre` or `post-test`. |
| `plan.py` | The CI planner: picks the Gradle modules and optional jobs for a run. |
| `semgrep/` | Semgrep Community Edition: the wrapper `run.sh` (`test`, `scan`, `community`), the result policy `report.py` and one fixture file per rule under `fixtures/`. The rules themselves are `/.semgrep.yml`. |
| `scan/` | The dependency and image scanner wrappers: `odc.sh` (OWASP Dependency-Check), `trivy.sh` (Trivy), the shared `lib.sh` and the result policy `summary.py`. |
| `selftest.sh` | Tests `run.sh`, `plan.py` and the dash and action-pin hooks, and runs the `--self-test` of every other hook that has one. It fails when a hook offers `--self-test` and the file does not run it. It also runs the self-tests of `semgrep/report.py` and `scan/summary.py`, and checks the security wiring: every job is in `ci-gate`, every scanner is called, every scanner image is pinned by digest, Dependabot still has its ecosystems. The compliance job runs it first. |

## Hook contract

- A hook is any `*.sh` file in `pre/` or `post-test/`. Hooks run with `bash`, in
  lexical order, with the repository root as the working directory. The
  executable bit is not needed.
- Exit `0` passes. Exit `77` skips (a prerequisite is absent, for example the
  `new-ui` checkout or a base commit). Any other exit code fails the job.
- Every hook runs even after an earlier failure, so one run lists all problems.
- Environment: `DLAP_CI_PHASE`, `DLAP_CI_ROOT`, `DLAP_CI_EVENT` (the GitHub event
  name, or `local`), `DLAP_CI_BASE_SHA` (the pull request base or the push
  "before" commit; empty on schedule and manual runs).
- `post-test` hooks also get `DLAP_CI_REPORTS_DIR=build/ci-reports`. Each module
  that passed uploaded `test-results`, `reports/jacoco` and `jacoco` (when they
  exist) and they land in `build/ci-reports/ci-reports-<module>/`.
- Hooks must be deterministic and network-free unless they say otherwise.
- New `.sh` files carry the Apache header (see any file here).

To add a hook, drop the file in the right folder and add a case to
`selftest.sh` that proves it fails on a violation and passes on clean input. A
hook with a `--self-test` mode is run from `selftest.sh` too, and `selftest.sh`
fails while one is missing. No workflow edit is needed; the `post-test` job
appears once `post-test/` holds a `*.sh` file.

## Hooks shipped

| Hook | Rule |
|---|---|
| `pre/license-headers.sh` | Apache 2.0 header on every first-party source file (CLAUDE.md section 10.5). Delegates to `scripts/check-license-headers.sh`. |
| `pre/no-dashes.sh` | No em-dash or en-dash on any added line (CLAUDE.md section 11). Needs a base commit: `DLAP_CI_BASE_SHA=feature/deployment bash scripts/ci-guards/pre/no-dashes.sh`. CI runs this mode. `--all` checks every tracked file instead and needs no base: `bash scripts/ci-guards/pre/no-dashes.sh --all`. A file may keep dashes only when `pre/no-dashes.allowlist` lists it with a line ceiling, a category (`append-only`, `checksummed`, `data`) and a reason. |
| `pre/actions-pinned.sh` | Every GitHub Action is pinned to a full commit SHA; cache and artifact actions are not on the removed v1 to v3 lines. |
| `pre/no-instant-now.sh` | No zero-argument `Instant.now()` in `da-*/src/main` beyond the per-file ceilings in `pre/no-instant-now.allowlist` (work-plan rule R6: business time is event time, take a `Clock`). The list only shrinks: `--tighten` lowers it after a clean-up, `--strict` fails on a ceiling that is too high, `--self-test` proves the guard. |
| `pre/disabled-tests-tracked.sh` | Every Java test file with an `@Disabled` is named in an open entry of `docs/OPEN_QUESTIONS.md`, so a switched-off test has a reason and an owner. `--list` shows the state per file. |
| `pre/dependency-licenses.sh` | No GPL, LGPL or AGPL licence on a shipped runtime classpath (Gradle and the UI's npm production dependencies), and `NOTICE` names every Category B or excepted dependency (LIC-01, CLAUDE.md section 10.5). The full run needs the network and Java 21; `--self-test` is offline and `--report FILE` applies the policy to an existing licence report. |
| `pre/no-invented-data.sh` | No random-number source (`Random`, `Math.random`, `ThreadLocalRandom` and the like) in `da-*/src/main` outside `demo/` packages and `MockLLMClient`, so no production path invents data (UI-01). `--self-test` proves the scan. |
| `pre/no-mvel.sh` | No MVEL or Easy Rules (`org.mvel`, `org.jeasy`) in a build declaration, an import or a production reference of any module (SEC-04, ADR-0057). The default run is a static scan of the source tree. `--resolved` also resolves the runtime and test classpath of every Gradle module and catches a transitive dependency; it needs Java 21 and the network, and the compliance job runs it as its own step (Q-152, Q-252). `--self-test` proves the scan. |
| `pre/osv.sh` | No known vulnerability with a CVSS base score of 7.0 or higher in a shipped dependency, from OSV-Scanner (CI-02b). It turns the runtime classpath that the licence gate resolves into a CycloneDX SBOM, adds every tracked `package-lock.json` and `gradle.lockfile`, and applies the policy: development-only packages and unscored advisories are listed, never blocking. Needs the network and Docker (or `OSV_SCANNER`); skips locally without them, fails in CI. `--report FILE` applies the policy to an existing OSV JSON, `--self-test` proves the conversion and the policy offline. |
| `pre/scan-suppressions.sh` | No scanner finding is silenced without a reason and an expiry date (CI-02b): every entry of `.github/dependency-check-suppressions.xml`, `.github/osv-scanner.toml` and `.github/trivy-ignore.yaml` has notes and a date that is neither past nor more than a year away, and every inline `nosemgrep` names a rule and gives a reason. `--self-test` runs 23 cases on scratch trees. |
| `post-test/fixtures.sh` | `da-log-fixtures` stays test support: no runtime source set mentions it, no other module depends on it outside a test configuration, and the Gradle gates `verifyFixtureProvenance`, `fixtureCoverage` and `verifyFixturesTestOnly` pass (FIX-01). Skips when the module is absent. |
| `post-test/coverage-spec-lock.sh` | No commit in `DLAP_CI_BASE_SHA..HEAD` changes a `coverage-spec.yaml` together with parser code, fixture generators or cases, so a parser author cannot move the coverage denominator and score against it in one change (SPEC-01, critic items 6 and 23). Merge commits are skipped. It needs the base commit in the clone and skips (77) without it; the `post-test-guards` job fetches full history for that reason (Q-252). `--self-test` runs 22 cases on scratch repositories. |
| `post-test/openapi-drift.sh` | The committed OpenAPI contracts under `docs/api/openapi/` are what the controllers produce: every module with a controller has an `OpenApiSpecExportTest`, no contract is an orphan, the shared support block is identical in every test copy, and regenerating every contract through Gradle (`DLAP_OPENAPI_UPDATE=1`, `--rerun`) leaves the tree unchanged. Likely breaking changes against `DLAP_CI_BASE_SHA` are printed as warnings, never failures (API-01, ADR-0064). `--self-test` runs 26 cases on scratch trees. |
| `post-test/coverage.sh` | At least 70% of the Java lines a change adds or modifies under `da-*/src/main/java` are executed by the tests (QA-02). It reads the JaCoCo XML of each changed module (`ci-reports-<module>/**/jacocoTestReport.xml` in CI, `<module>/build/reports/jacoco/test/` locally), counts only the lines JaCoCo lists as executable, and takes the ratio over the whole change, not per file and not over the project. A changed module with no report fails. `DLAP_COVERAGE_MIN` changes the threshold, `DLAP_COVERAGE_MODULES` limits a local run to the modules you built, `--self-test` runs 28 cases on scratch repositories. |

Later hardening packages add their own hooks here, each with a row in this table.

## The CI run

`dlap-ci-cd.yml` has these jobs:

| Job | Runs when |
|---|---|
| `plan` | always (about one minute) |
| `compliance-scan` | always: guard self-test, `pre` hooks (licences, OSV-Scanner, suppression hygiene and the rest), the resolved no-MVEL check, npm audit |
| `secret-scan` | always: gitleaks over the commits the event adds, the whole history nightly |
| `sast` | always: Semgrep project rules (ERROR fails) after proving them against their fixtures, and Trivy over lockfiles and configuration files. The Semgrep community packs run as advice on nightly and manual runs. |
| `dependency-check` | nightly, manual runs (input `scans`), and a push or pull request that can change the dependency set (`plan.py` output `scan_deps`): builds every Spring Boot jar and scans them with OWASP Dependency-Check, failing at CVSS 7 |
| `script-tests` | when `e2e-tests/` or `deployment/` change, nightly and manual runs (`plan.py` output `script_tests`): the offline self-tests of the harness lock library, the pilot up script and the demo launcher |
| `build-and-test` | one job per module chosen by `plan.py`; `./gradlew :<module>:build`, then on pull requests, nightly and manual runs a Docker image build and a Trivy scan of that image |
| `integration` | `e2e-tests/cloud-pipeline-e2e.sh all`, only when `plan.py` says so (below). It builds its own three jars without tests, so it runs in parallel with the module jobs and still reports while unrelated module tests are red. |
| `post-test-guards` | only when `post-test/` has a hook and every module passed |
| `ci-gate` | always; fails if any job above failed or was cancelled. This is the one check to require. |

### Module matrix

`plan.py` reads `include("...")` from `settings.gradle.kts`, so a module added
there joins the matrix with no workflow edit. `new-ui` is excluded: the UI
repository builds and tests it in its own workflow (`ui-ci.yml`). Today that is
13 modules.

### Cost controls (decision D15)

| Event | Modules | Integration |
|---|---|---|
| pull request | full matrix if code or build files changed; none for docs-only | non-draft PR touching `da-catalog`, `da-logingest`, `da-discovery`, `da-protos`, build-wide files, `e2e-tests/` or `deployment/environments/dev/` or `deployment/platform/` |

The dependency scan (`dependency-check`) is separate from the matrix: it runs nightly, on manual runs and on any push or pull request that touches a build file (`build.gradle.kts` anywhere, `settings.gradle.kts`, `gradle.properties`, `gradle/`), a lockfile, the `new-ui` pointer, the suppression files, the scan scripts or the workflow. A change to module source alone does not start it; the OSV-Scanner gate, the Semgrep rules and the Trivy image scan cover that.
| pull request by a bot (Dependabot) | touched modules and their Gradle dependents | same rule as above |
| push to `main`, `develop`, `feature/deployment` | touched modules and their Gradle dependents; full matrix when a build-wide file changed | never |
| nightly (03:17 UTC) | full matrix; skipped when no commit landed in the last 26 hours | yes |
| manual | the `modules` input (`all` or a comma list) | the `integration` input |

Build-wide files: root `build.gradle.kts`, `settings.gradle.kts`,
`gradle.properties`, `gradle/`, `gradlew*`, `da-protos/`, the workflow itself,
the `post-test` hooks and the committed OpenAPI contracts (`docs/api/openapi/`).

Also: `cancel-in-progress` on everything except the nightly run, a timeout on every
job (5 to 50 minutes), no jar uploads, test reports kept 7 days and only
on failure, Gradle run with `--max-workers=2` to fit the 2-vCPU runner.

The nightly schedule fires only from the default branch. Until this workflow
reaches `main` there is no nightly run; use a manual dispatch meanwhile.

Budget: every job bills at least one minute and the module jobs run several
minutes each, so a full run is expensive against a 2,000 minute pool. Read the
real durations from the first full run. If the pool is too small, change the
cron to weekly or trim the pull-request matrix in `plan.py`; both are one-line
edits.

### Security scanning (work packages CI-02a and CI-02b)

Seven scanners, each run by a wrapper that pins the container image by release and digest (the default
is in the wrapper; change it there and nowhere else), so a laptop and CI give the same verdict.

| Scanner | Looks at | Blocks | Runs | Local command |
|---|---|---|---|---|
| gitleaks | the commits an event adds, the whole history nightly | any finding not in `.gitleaks.toml` | every event | `docker run ... gitleaks` (CONTRIBUTING.md, Secret scanning) |
| Semgrep CE | source and config with the project rules in `.semgrep.yml` | ERROR findings; WARNING findings are listed | every event | `bash scripts/ci-guards/semgrep/run.sh scan` (rules proved first: `... test`) |
| Semgrep community packs | `p/java`, `p/owasp-top-ten`, `p/github-actions` | never, advice only (the packs are not pinned) | nightly, manual | `bash scripts/ci-guards/semgrep/run.sh community` |
| Trivy, files | lockfiles (vulnerabilities) and Dockerfiles, compose, Terraform, Kubernetes files (misconfigurations) | HIGH and CRITICAL with a fix | every event | `bash scripts/ci-guards/scan/trivy.sh fs` |
| Trivy, images | the image built from each module's Dockerfile: OS packages and the jars inside | HIGH and CRITICAL with a fix | pull request, nightly, manual | `bash scripts/ci-guards/scan/trivy.sh image dlap/da-catalog:ci` |
| OSV-Scanner | the runtime classpath of every module (as an SBOM) and every lockfile | CVSS 7.0 or higher in a shipped dependency | every event | `bash scripts/ci-guards/pre/osv.sh` |
| OWASP Dependency-Check | the built Spring Boot jars, nested libraries included | CVSS 7.0 or higher (`--failOnCVSS 7`) | see `dependency-check` above | `bash scripts/ci-guards/scan/odc.sh --build` |

Why several: they read different data. OSV-Scanner matches package coordinates against the OSV database
(GitHub advisories, ecosystem databases). Dependency-Check matches products and versions by CPE against the NVD, which
finds vendor advisories the others lack and also produces false positives. Trivy adds the operating-system layer of
the image, which the other two do not see. Dependabot (`/.github/dependabot.yml`, unchanged) opens the update pull
requests; the scanners tell you which of them matter.

A finding is never silenced without a reason and an expiry date. Fix the dependency; where the finding is wrong for
this project, suppress it in the scanner's own file with the narrowest match you can write:

| Scanner | File | Reason goes in |
|---|---|---|
| Semgrep | inline `// nosemgrep: <rule-id> -- <reason>`, or a narrowed rule in `.semgrep.yml` with a comment | the comment after `--` |
| Trivy | `.github/trivy-ignore.yaml` | `statement`, with `expired_at` |
| OSV-Scanner | `.github/osv-scanner.toml` (`[[IgnoredVulns]]`) | `reason`, with `ignoreUntil` |
| Dependency-Check | `.github/dependency-check-suppressions.xml` | `<notes>`, with `until` on `<suppress>` |

`pre/scan-suppressions.sh` rejects an entry without either, one past its date and one more than a year ahead.

Data and timings measured on the build host (Windows, Docker Desktop; a Linux runner is faster) when this was
written, against `feature/deployment` at `de3277de`: Semgrep project rules 1,151 files in about 2 minutes (13 rules,
51 positive and 35 negative fixture cases, 7 seconds); OSV-Scanner 8 seconds after the licence report exists;
Dependency-Check database rebuild from the mirror 76 seconds, analysis of the 11 boot jars 82 seconds (7 minutes wall clock with the archive extraction over the Windows bind mount);
Trivy files 48 seconds, Trivy image about 2.5 minutes including a 940 MB first-use Java database download (the
databases are cached per week in CI). The Dependency-Check mirror
(`dependency-check.github.io/DependencyCheck_Builder`) is updated daily on a best-effort basis; set the secret
`NVD_API_KEY` in the workflow environment to use the NVD API directly instead.

### Secrets and settings

| Name | Needed for | If absent |
|---|---|---|
| `UI_REPO_TOKEN` | A fine-grained token with read access to `Data-Automation-Lab/dlap-ui`. Checks out `new-ui` in the compliance job so the header check and `npm audit` cover the UI. | The step logs a notice and is skipped; fork pull requests behave the same. |

No other secret is used; the scanners need none (`NVD_API_KEY` is optional, see Security scanning). The old
Hugging Face variables and the deploy, performance and HF-notify jobs are gone.

### Limits found when this was written (2026-10-18)

- The org `Data-Automation-Lab` is on the GitHub Free plan (`gh api
  orgs/Data-Automation-Lab`, field `plan`). The documented Free allowance for
  private repositories is 2,000 Linux minutes a month [unverified for 2026].
- `gh api /orgs/Data-Automation-Lab/settings/billing/actions` returns 410 (the
  endpoint moved) and the token lacks `admin:org`. The newer usage summary shows
  220 Linux minutes used in October, all inside the free allowance. The size of
  the allowance is not returned: treat the quota as unknown.
- Branch protection is unavailable on this private repository: the API answers
  403 "Upgrade to GitHub Pro or make this repository public". Required checks
  need a paid plan or a public repository. `ci-gate` is ready for that day.
- The weekly failing runs on `main` come from Dependabot (`npm_and_yarn in
  /new-ui/frontend`, `submodules in /`), not from this workflow. Fifteen open
  Dependabot pull requests target `main`.
- Action versions: `actions/cache` and `actions/upload-artifact` and
  `download-artifact` are on v4 (the plan's target). Newer majors exist (cache
  v6, upload-artifact v7, download-artifact v8) and Dependabot already opened
  pull requests for them.
- GitHub documents 2 vCPU and 7 GB of memory for a private-repository runner
  [unverified here]. The integration job records
  `free -m` and `docker stats` in its job summary so the stack's real footprint is
  known after the first run.
