# CI/CD

CI for DLAP runs in GitHub Actions, defined in a single file:
[`.github/workflows/dlap-ci-cd.yml`](../../.github/workflows/dlap-ci-cd.yml).
That file is authoritative. This page states only what stays true as the
workflow is edited, so check the workflow itself for the exact triggers, module
matrix and job list before relying on any of them. In particular, read the
`on:` block before assuming a push to a feature branch is built.

## What the pipeline does

- **Compliance gate:** Apache license-header check
  (`scripts/check-license-headers.sh`) and a dependency audit.
- **Per-module build and test:** each backend module builds and tests on
  Temurin **JDK 21** with Gradle caching, driven by a `matrix:` of module names
  (`./gradlew :<module>:build -x test`, then `./gradlew :<module>:test`).
  Treat the workflow's `matrix:` as the list of modules CI covers; this page does
  not repeat it, because an earlier copy of the list here had drifted from the
  file.
- **Docker image check:** each module's `src/main/docker/Dockerfile` is built on
  the runner to prove it builds. Images are not pushed to any registry: the
  workflow has no `docker push` step.

## What the pipeline does not do

- It does not deploy to a hosted environment. At commit `7984d1d` the
  `deploy-test` and `deploy-dev` jobs only run `docker compose up -d` on the
  throwaway CI runner and curl a local port. No customer or shared environment is
  touched.
- It does not publish release artifacts, sign anything, or produce an SBOM.
- Test coverage and green status are what the workflow reports; there is no
  published uptime or reliability figure behind a green build.

## Local equivalent

```bash
./gradlew build          # compile + test everything (needs JDK 21 + Docker
                         # for the Testcontainers-backed repository tests)
```

On a machine whose default JDK is 24 or newer, point `JAVA_HOME` at a JDK 21
first: Gradle 8.7 does not run on JDK 24.

## History

v0.1.x carried a HuggingFace Spaces deployment pipeline ("Three-Space
control plane": `hf-spaces-deploy.yml`, `deploy-microservices.yml`,
`deployment/hf-spaces/`). Those files were removed in v0.2.0 (Q-008): HF Spaces
is not on the DLAP roadmap, and the legacy guide
(`docs/DALab-CICD-Complete-Guide.md`) documented only that pipeline. At commit
`7984d1d` the main workflow still carried `HF_SPACE_*` environment variables and
"Notify Hugging Face Space" steps (`.github/workflows/dlap-ci-cd.yml`, the
top-level `env:` block and the deploy and performance jobs). Treat any such
reference as a leftover, not a supported deployment path.

Deployment now targets Docker Compose for the quickstart (`deployment/environments/`);
production is a documented checklist in `deployment/PRODUCTION.md`, not a shipped
topology.
