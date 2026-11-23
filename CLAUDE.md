# CLAUDE.md - Engineering rules

The engineering rules of the DLAP repository, for contributors and for coding agents
(Claude Code loads this file at the start of a session). Source comments cite it by
section number, for example "CLAUDE.md section 9", so the numbering is stable.

## 1. Start here

Before writing code:

1. Read this file.
2. Read `docs/architecture/overview.md` and the ADRs in `docs/DECISIONS.md` that touch
   the area you are about to change.
3. Audit the actual source files of any service you are about to change. Do not trust
   docs or notes. Read the code.
4. State the scope of your change in one line in the pull request.

## 2. Project identity

DLAP (Data Lifecycle Automation Platform) is an open-source reference implementation,
under Apache 2.0, of end-to-end data lifecycle management. It was built by Data
Automation Labs (DALab).

Cloud-neutral. Log-enriched. Five lifecycle stages (canon: docs/GLOSSARY.md): Discovery (Born),
Label (Enriched), Govern (Governed), Consume (Consumed, the AuthZEN access-decision
plane, ADR-0040), Retire (Retired). The older Auto-Discovery / Auto-Label /
Auto-Archive / Auto-Delete / Auto-Report names are superseded; Reporting is
cross-cutting. Audit logs and
application logs are the core differentiator - we reconstruct truth
from logs, not from the tools that emit them.

Eleven Spring Boot services (ten da-* services plus da-mcp-pep), two shared
libraries (da-protos, da-llm-client) and one test-fixture module
(da-log-fixtures), React frontend, Postgres, Kafka (KRaft),
Keycloak.

## 3. Repository layout
da-protos/           Shared entities: Asset, Label, User - imported by all services
da-catalog/          Central asset catalog                  port 8082
da-discovery/        Multi-cloud scanner                    port 8091  [Stage: Discovery]
da-policyengine/     Policy management + Event Center       port 8083
da-reporting/        Cross-service dashboards               port 8084  [Stage: Report]
da-autolabel/        LLM-powered auto-labeling              port 8085  [Stage: Label]
da-autoarchival/     Smart archival                         port 8086  [Stage: Archive]
da-autodelete/       Secure deletion                        port 8087  [Stage: Delete]
da-autocompliance/   Compliance monitoring                  port 8088
da-admin-service/    User management + system admin         port 8089
da-logingest/        Audit-log ingest + normalization       port 8090  [Stage: Discovery]
da-mcp-pep/          Reference MCP policy-enforcement point (standalone; not in dev compose) [Stage: Consume]
da-llm-client/       Shared LLM client library (ILLMClient + providers)
da-log-fixtures/     Log fixture corpus, coverage specs and gates (test scope only)
new-ui/              React 18 + TypeScript frontend         port 3000
e2e-tests/           End-to-end test framework
deployment/          Docker Compose, Terraform, CI/CD
docs/                Architecture, API, user documentation

## 4. Documentation protocol

Project documentation lives in `/docs`. Do not create new top-level docs; extend
existing ones.

| File | Purpose | Write mode |
|---|---|---|
| `DECISIONS.md` | ADR-style, one decision per entry | Append-only |
| `OPEN_QUESTIONS.md` | Pointer to the issue tracker; entries for switched-off tests | Maintain |
| `GLOSSARY.md` | Canonical terms | Capitalize lifecycle stages |

The session log, operating plan and audit documents of the pre-release period are
working documents and are not published.

## 5. Non-negotiable principles

- **Short, Slow, Strong.** Concise, definitive. No hedging, no filler.
- **Clarity beats cleverness.** If a design can't be explained simply,
  it isn't done.
- **Reality wins.** If docs contradict code, code is the source of truth.
  Flag the gap; do not paper over it.
- **No invention.** Every architectural or status claim cites a file path
  and line range. "Unknown" is acceptable; fabrication is not.
- **Cloud neutrality.** No provider SDK imports in `core/`, `api/`,
  `common/`, or `da-protos/`. Provider code lives only in
  `providers/<cloud>/` packages.
- **Decide; don't escalate.** Make architectural decisions yourself and
  log them to `DECISIONS.md`. Escalate only when a decision has
  significant product, licensing, or community implications.

## 6. Architectural decisions - do not re-debate

- Each service owns its own PostgreSQL schema. Shared entities live in
  `dlap_common`, managed through `da-protos`.
- Kafka runs in KRaft mode. Topic names are constants in
  `DlapConstants.KafkaTopic`.
- `da-protos` is the single source of truth for `Asset`, `Label`,
  `User`, `AssetLabelAssignment`. Never duplicate these in a service.
- LLM provider abstraction (`ILLMClient` → `LLMClientFactory`) is
  intentional and correct. Preserve it.
- Auth role mapping: each service has its own `SecurityConfiguration.java`
  mapping Keycloak roles to Spring Security authorities.
- Container base: Distroless Java 21 for production images.

### Migration tooling

- **Per-service schemas:** Flyway SQL. Migrations live at
  `<service>/src/main/resources/db/migration/V{N}__short_name.sql`.
  Numbering is sequential per service; do not skip numbers.
  First migration is always `V1__init_<service>_schema.sql`.
- **Shared `dlap_common` schema:** Bootstrapped by raw SQL init scripts in
  `deployment/platform/database/init-scripts/` (`00` to `03`), mounted by
  the dev compose pair, the only compose stack (ADR-0037; the pilot overlay
  reuses it, ADR-0058). The former duplicate under
  `environments/dev/docker/init-scripts/` was removed as orphaned.
  Flyway is not used because multiple services must read the schema
  before any one of them runs migrations. Changes to `dlap_common` go in
  `02-create-common-schema.sql` plus a delta SQL under
  `deployment/platform/database/upgrades/`.
  Existing databases are moved forward only by
  `deployment/platform/database/upgrades/run-upgrades.sh` (ledger
  `dlap_common.schema_upgrades`, ADR-0056); a shipped delta is never edited.
- **Hibernate `ddl-auto`:** `validate` in production, `none` allowed in
  tests. Never `update` or `create`.
- **Single migration runner:** Flyway SQL per service. Adopting a second
  migration stack would rewrite every existing migration - non-goal
  unless an RFC proposes it.

## 7. How to work

### Audit before you act
Read the relevant source files before writing any code. Do not assume
current state matches documentation, prior session notes, or anything
in `/docs`. Check what is actually there.

### Write precise change briefs
One logical change per brief. Each brief includes:
- Exact file paths
- The specific change required
- What must not change
- The verification command to run after

### Verify every change
After each change:
1. `./gradlew :<service>:compileJava` - must pass clean
2. `./gradlew :<service>:test` - must pass, or pre-existing failures
   documented in session log
3. Confirm the specific behavior you changed works as intended
4. Periodically run `./gradlew build -x test` from repo root to catch
   cross-module regressions

**Database change workflow:**
- Per-service: create
  `<service>/src/main/resources/db/migration/V{N+1}__short_name.sql`.
  Read the highest existing `V{N}` first. Use `CREATE TABLE IF NOT EXISTS`
  and `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` for idempotency.
- Shared `dlap_common`: edit `deployment/platform/database/init-scripts/`
  `02-create-common-schema.sql` (single source - mounted by dev + prod) AND
  add a delta script under `deployment/platform/database/upgrades/`. Note the
  change in the PR description.

### Recovering from a broken state
1. Do not push forward. Revert the change.
2. Say what happened and the root cause in the pull request or issue.
3. Re-audit the affected code before trying again.
4. If blocked on a human decision, open an issue and stop.

## 8. Commit discipline

- Small, atomic commits. Conventional Commits: `feat:`, `fix:`,
  `refactor:`, `docs:`, `test:`, `chore:`.
- Imperative subject line ≤ 72 chars. Body explains *why*, not *what*.
- DCO sign-off required on every commit (Apache incubation requirement).
- New code requires tests unless the change is docs-only.
- New dependencies require justification in the session log.
- Never force-push. Never rewrite shared history.

## 9. Prohibited patterns (open-standard guardrails)

- Cloud-provider SDK imports in `core/`, `api/`, `common/`, or
  `da-protos/`.
- Hardcoded bucket names, project IDs, regions, endpoints, credentials.
  Use `ProviderConfig` injection.
- Google-internal utilities (google3, borgcfg, internal protos, Stubby).
- Copy-pasted OSS code without SPDX license header and attribution.
- Business logic in frontend code.
- In-memory state that should be persisted (catalog, policy, ML config).
- Controller methods without `@PreAuthorize` (except `/actuator/health` and
  Swagger). There is no other exception: since SEC-07 the da-mcp-pep endpoint
  `POST /mcp/v1/authorize` is a JWT resource server route (issuer and audience
  checked, 401 without a token). Since FU-3C any authenticated caller whose
  token carries the PEP audience may call it, and the subject comes from the
  token unless the caller is `ROLE_SERVICE_ACCOUNT` (ADR-0076, ADR-0084,
  Q-459); the da-mcp-pep handler-coverage test allowlist is empty.

## 10. Apache OSS launch checklist

Audit first, then fix. Do not mix areas.

### 10.1 Functional correctness
- No service throws unhandled exceptions on documented happy paths
- Kafka consumers use manual acknowledgment and have DLQ handling
- All API endpoints return documented response shapes - no mock or stub
  data on production paths
- ML configuration persists across restarts
- LLM pipeline receives real asset data, not hardcoded strings

### 10.2 Security hardening
- Zero hardcoded credentials, tokens, or API keys anywhere in the repo
- All secrets use `${ENV_VAR:placeholder}` pattern
- Every service's `SecurityConfiguration.java` is complete and enforced
- Audit:
  `grep -r -E "(password|secret|api.key|token)\s*[=:]\s*[^\$\{]" --include="*.properties" --include="*.yml" --include="*.json" . | grep -v node_modules`

### 10.3 Code quality
- No empty implementation files
- No TODO comments representing missing critical functionality on
  production paths
- Repository query methods that declare parameters must use them
- Service layer methods call real implementations - not
  `Collections.emptyList()` or hardcoded strings

### 10.4 Test coverage
- Unit tests for all service layer classes covering primary business logic
- Repository tests using `@DataJpaTest`
- Controller tests using `@WebMvcTest` with mocked services
- At least one integration test per lifecycle agent
  (discover → catalog → label, etc.)
- All existing tests pass

### 10.5 Apache license compliance
- Apache 2.0 header on every `.java`, `.kt`, `.ts`, `.tsx` source file
- `LICENSE` at repo root (full Apache 2.0 text)
- `NOTICE` at repo root
- All dependencies Apache 2.0-compatible -
  `./gradlew dependencyInsight`
- No GPL or LGPL statically-linked dependencies

### 10.6 OSS community files
- `README.md` - what it is, architecture overview, quickstart under 5 min
- `CONTRIBUTING.md` - process, coding standards, DCO sign-off
- `CODE_OF_CONDUCT.md` - Apache community standards
- `SECURITY.md` - vulnerability disclosure
- `CHANGELOG.md` - starting at 0.1.0
- GitHub issue templates (bug, feature) and PR template

### 10.7 Operational readiness
- Docker Compose brings up full stack cleanly from a fresh clone with
  zero manual steps
- README quickstart is accurate and verified
- Health check endpoints (`/actuator/health`) return 200 on all services
- Flyway migrations apply cleanly on a fresh database per service;
  `dlap_common` bootstraps from SQL init scripts
- No service fails to start due to missing configuration

## 11. Code standards

- Java 21 features encouraged: records, sealed classes, pattern matching,
  text blocks
- All public methods have Javadoc
- Constants belong in `DlapConstants` - no magic strings scattered
  across services
- Exception handling: catch specific exceptions, log with context,
  never swallow silently
- Logging: SLF4J, structured messages, never log credentials or PII
- Punctuation: never use the em-dash character anywhere (code, comments, docs,
  commit messages, marketing copy); use commas, colons, periods or a hyphen
- Kafka: manual acknowledgment, idempotent producers
  (`ENABLE_IDEMPOTENCE_CONFIG=true`), DLQ on all consumers
- REST: consistent response shapes, correct HTTP status codes, input
  validation on all endpoints
- Security: `@PreAuthorize` on every controller method

## 12. Tech stack reference

- **Backend**: Java 21, Spring Boot 4.1 (Spring Framework 7, Spring
  Security 7), Spring Data JPA with Hibernate ORM 7, Spring for Apache
  Kafka 4; JSON on Jackson 2 through spring-boot-jackson2 until the
  Jackson 3 move (ADR-0066)
- **Database**: PostgreSQL 15, Flyway SQL, Hibernate ORM 7
- **Messaging**: Apache Kafka (KRaft)
- **Auth**: Keycloak, JWT, RBAC (roles in `DlapConstants.UserRole`)
- **AI/ML**: OpenAI, Ollama via `ILLMClient` (Gemini deferred to 0.2.0 per ADR-0009)
- **Frontend**: React 18, TypeScript, Tailwind, Vite
- **Build**: Gradle 8.14 (wrapper 8.14.6), Kotlin DSL; Java 21 toolchain
- **Monitoring**: Prometheus, Grafana, Alertmanager

## 13. State lives in the repository

Nothing outside the repository is a source of truth about past work: not chat history,
not memory of an earlier session. If context seems missing, read the code and the ADRs,
then ask in an issue. Never assume.

## 14. Tone

Pragmatic. Technical. No preambles ("Great question…"), no apologies,
no hedging ("I think…", "It seems…"). State the fact