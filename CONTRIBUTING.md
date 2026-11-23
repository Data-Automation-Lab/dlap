# Contributing to DLAP

Thank you for helping improve **DLAP** (`https://github.com/Data-Automation-Lab/dlap`).

## How to contribute

1. **Issues**: use the bug or feature templates under `.github/ISSUE_TEMPLATE/`. Security issues must **not** be filed publicly; see [SECURITY.md](SECURITY.md).
2. **Discussions**: broader questions and design threads at `https://github.com/Data-Automation-Lab/dlap/discussions`.
3. **Pull requests**: small, reviewable diffs against `main` (or the active release branch agreed with maintainers). Fill out the PR template and link issues.

## Development setup

Follow the [README quickstart](README.md#quickstart-fresh-clone-under-5-minutes) for Docker-based infra.

Per-service Spring Boot from the repo root (requires Postgres/Kafka/Keycloak already running or stubbed):

```bash
./gradlew :da-discovery:bootRun
./gradlew :da-catalog:bootRun
./gradlew :da-policyengine:bootRun
./gradlew :da-reporting:bootRun
./gradlew :da-autolabel:bootRun
./gradlew :da-autoarchival:bootRun
./gradlew :da-autodelete:bootRun
./gradlew :da-autocompliance:bootRun
./gradlew :da-admin-service:bootRun
./gradlew :da-logingest:bootRun
```

Use `./gradlew :<module>:compileJava` for faster compile checks.

## Coding standards

Summarized from [CLAUDE.md](CLAUDE.md):

- Java 21 language features are encouraged where they improve clarity.
- Public APIs should have Javadoc.
- Shared string constants belong in `DlapConstants` (`da-protos`); avoid magic strings in services.
- Logging: SLF4J with structured context; never log secrets, tokens, or sensitive PII.
- Database changes: Flyway SQL under each service `src/main/resources/db/migration/`; shared `dlap_common` changes require coordinated SQL init + `deployment/platform/database/upgrades/` (see CLAUDE.md **Migration tooling**).
- REST controllers: `@PreAuthorize` on endpoints except documented actuator health / OpenAPI paths.
- Kafka consumers: manual ack + DLQ patterns as established in existing services.

## Branching model

- `main` is protected.
- Create feature branches from `main`, open PRs back to `main`.
- Prefer **squash merge** unless maintainers request a merge commit for release bookkeeping.

## Commit messages

Use [Conventional Commits](https://www.conventionalcommits.org/) (`feat:`, `fix:`, `docs:`, `chore:`, `refactor:`, …). Reference public issue numbers in the footer when applicable (`Refs #123`).

## Pull request process

1. Complete the PR checklist (tests, docs, **DCO sign-off**).
2. Ensure CI workflows pass (see `.github/workflows/`).
3. Obtain at least **one approving review** from a maintainer.

## Secret scanning

CI runs [gitleaks](https://github.com/gitleaks/gitleaks) on every push and pull request (job **Secret scan** in `.github/workflows/dlap-ci-cd.yml`, part of the required **CI gate**). It uses the default gitleaks rules plus the narrow exceptions in [`.gitleaks.toml`](.gitleaks.toml). A push or pull request is checked on the commits it adds. Nightly and manual runs check the whole history. Findings are printed redacted. Every run first proves, with seeded canary credentials, that the scanner still catches a real-looking secret and passes only the listed placeholders.

### Before you push

Install the pre-commit hook once. It scans the staged changes of each commit with the same configuration as CI:

```bash
pip install pre-commit
pre-commit install
```

To scan commits you have not pushed yet, use the CI image (the tag and digest are in `GITLEAKS_IMAGE` in the workflow). Replace `<base>` with the branch your pull request targets:

```bash
docker run --rm -v "$PWD:/repo" ghcr.io/gitleaks/gitleaks:v8.30.1 git /repo \
  --config /repo/.gitleaks.toml --redact --no-banner --no-color --ignore-gitleaks-allow \
  --log-opts="origin/<base>..HEAD"
```

Notes for the command above:

- In Git Bash on Windows, run `export MSYS_NO_PATHCONV=1` first and mount `"$(pwd -W):/repo"`.
- Run it in a normal clone, not in a `git worktree`. The container cannot follow the `.git` file of a worktree, and gitleaks then reports `0 commits scanned` and exits 0, which is a false pass. The pre-commit hook has no such limit.

### If the scan fails

1. Treat the credential as exposed. Revoke or rotate it first. Deleting it in a later commit does not remove it from history.
2. Remove the value from the tree and read it from the environment, for example `${ENV_VAR:placeholder}` in a properties file.
3. If the finding is a placeholder or a test vector and not a credential, add the narrowest exception to `.gitleaks.toml`: name the rule, the file path and the exact value (or the exact commit for history that cannot change). Never allowlist a path on its own. The policy is at the top of that file.
4. Inline `gitleaks:allow` comments are ignored in CI and in the hook, so every exception is a reviewed change to `.gitleaks.toml`.

In documentation and examples, write placeholders such as `YOUR_TOKEN_HERE`, never a value copied from a real system. To report a credential that has already leaked, follow [SECURITY.md](SECURITY.md).

## DCO sign-off

Every commit must be signed off under the [Developer Certificate of Origin](https://developercertificate.org/) (DCO). This is **required**: DLAP is on a path to Apache Software Foundation incubation, and a DCO sign-off on each commit is how a contributor certifies they have the right to submit the patch under the project's Apache 2.0 license. It creates a clear, per-commit provenance trail for IP clearance.

### What the sign-off means

Adding a sign-off certifies the DCO (paraphrased): you wrote the patch or otherwise have the right to submit it under the project's open-source license, and you agree it may be redistributed under that license. The full text is at [developercertificate.org](https://developercertificate.org/).

### How to sign off

Add the `-s` (`--signoff`) flag to every commit:

```bash
git commit -s -m "fix: describe change"
```

This appends a trailer using your `git config user.name` and `user.email`:

```
Signed-off-by: Your Name <you@example.com>
```

Use your real name and a real email address - the sign-off is a legal attestation, not a handle. Set them once with:

```bash
git config user.name "Your Name"
git config user.email "you@example.com"
```

### Fixing a missing or wrong sign-off

If a commit lacks the trailer (CI will flag it):

```bash
# Amend the most recent commit to add the sign-off:
git commit --amend -s --no-edit
git push --force-with-lease   # only on your own PR branch, never on shared history

# For several commits, rebase and sign off the range (e.g. last 3):
git rebase --signoff HEAD~3
git push --force-with-lease
```

Never force-push shared branches (`main` or release branches) - only your own PR branch.

## Code of Conduct

By participating, you agree to abide by our [Code of Conduct](CODE_OF_CONDUCT.md).

## Reporting security issues

Do **not** file public issues for vulnerabilities. Follow [SECURITY.md](SECURITY.md): report through the repository's **Security** tab (**Report a vulnerability**).

## Getting help

- GitHub Discussions: `https://github.com/Data-Automation-Lab/dlap/discussions`
- Issues with the `question` label if Discussions are unavailable for your topic.
