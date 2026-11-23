# GLOSSARY - DLAP

Canonical terms. Use these spellings exactly. Lifecycle stages are
capitalized. Flag new terms before introducing them into code or docs.

---

## Lifecycle stages (capitalized)

**One canon.** `docs/architecture/overview.md` §1 is the source of truth
and this section matches it. The lifecycle has **five stages**. Each has a
vision-facing name (the published five-part vision) and an
operator-facing agent name; use the operator name in code, UI and docs,
and the vision name only in narrative framing.

| Stage (operator) | Stage (vision) | What happens | Owner service |
|---|---|---|---|
| **Discovery** | Born | Assets are found across clouds and normalized into the catalog | `da-discovery` → `da-catalog` |
| **Label** | Enriched | Sensitivity, compliance, domain and governance labels are applied by rules + LLM | `da-autolabel` |
| **Govern** | Governed | Policies match assets and fire lifecycle actions | `da-policyengine` |
| **Consume** | Consumed | Per-request access verdicts at read time (AuthZEN PDP) | `da-policyengine` PDP + PEPs |
| **Retire** | Retired | Policy-driven archival and secure deletion | `da-autoarchival`, `da-autodelete` |

Full chain: **Born → Enriched → Governed → Consumed → Retired**, or
**Discovery → Label → Govern → Consume → Retire**.

### Mapping from the older five-stage names

Earlier revisions of this glossary, and CLAUDE.md §2, use an `Auto-*`
taxonomy that pre-dates the Consumed stage (ADR-0040). It is superseded.
Translate as follows:

| Superseded name | Current canon |
|---|---|
| **Auto-Discovery** | **Discovery** (Born) |
| **Auto-Label** | **Label** (Enriched) |
| **Auto-Archive** | **Retire** (Archive) - `da-autoarchival` |
| **Auto-Delete** | **Retire** (Delete) - `da-autodelete` |
| **Auto-Report** | *Not a lifecycle stage.* Reporting (`da-reporting`) is a cross-cutting concern over all five stages, not a sixth step. |
| - *(no equivalent)* | **Govern** - `da-policyengine`; implicit in the old list, now named |
| - *(no equivalent)* | **Consume** - the AuthZEN access-decision plane added by ADR-0040 |

The **Consume / Consumed** stage is the one stage that answers the
per-request "may this agent read this asset now?" question. It is
implemented as an extension of `da-policyengine`, **not** a new service.

When referring to the lifecycle as a whole, use **"End-to-End Data
Lifecycle Management"**. The short form **"DLM"** is acceptable after
first full mention.

---

## Services

Ports are the **host-published** ports from the canonical dev quickstart
(`deployment/environments/dev/docker-compose.microservices.yml`). Inside
the container network most services listen on `8080`.

- **da-admin-service** - User management + system admin. Port 8089.
- **da-autoarchival** - Retire (Archive) reference implementation. Port
  8086.
- **da-autocompliance** - Continuous compliance monitoring. Port 8088.
- **da-autodelete** - Retire (Delete) reference implementation. Port
  8087.
- **da-autolabel** - Label reference implementation. Port 8085.
- **da-catalog** - Central asset catalog; system of record for `Asset`.
  Port 8082.
- **da-discovery** - Discovery reference implementation. Port **8091** -
  host 8081 is the nginx API gateway, so the service is remapped.
- **da-llm-client** - Shared LLM provider abstraction (`ILLMClient`,
  `LLMClientFactory`) extracted as a library in ADR-0044. Not a running
  service.
- **da-logingest** - Cloud audit-log ingest; normalizes to the
  `DlapLogEvent` spine. Port 8090. **Shipped** (`da-logingest/`,
  ADR-0010 / R-P1-10) - earlier revisions of this glossary described it
  as proposed and not yet scaffolded; that is stale.
- **da-mcp-pep** - Reference MCP Policy Enforcement Point (ADR-0040).
  Fronts an MCP server and enforces AuthZEN verdicts from the PDP.
  Stateless: no database, no Kafka, no Flyway. **Not** in the dev
  compose; its own default is `server.port=8091`, which collides with
  the compose's host mapping for `da-discovery` - override it if you run
  both on one host.
- **da-policyengine** - Policy definition + Event Center + the AuthZEN
  PDP (`/access/v1/evaluation`). Port 8083. Owns both Govern and
  Consume.
- **da-protos** - Shared entity module (`Asset`, `Label`, `User`,
  `AssetLabelAssignment`, `CloudResource`, `DlapConstants`). Not a
  running service.
- **da-reporting** - Cross-service dashboards and notifications. Port
  8084; actuator on the separate, unpublished management port 8380.

---

## Platform terms

- **dlap_common** - Shared PostgreSQL schema. Bootstrapped by the SQL
  init scripts under `deployment/platform/database/init-scripts/`
  (`00`-`03`), the single source mounted by both dev and prod compose.
  Not managed by Flyway - see CLAUDE.md §6.
- **DlapConstants** - The central constants class in `da-protos`
  (`da-protos/src/main/java/com/dlap/common/constants/DlapConstants.java`).
  Kafka topics, role names, schema names and other magic strings live
  here. Spelled `DalabConstants` in pre-rename docs (ADR-0027).
- **ILLMClient** - Language-model provider abstraction
  (`MockLLMClient`, `OllamaLLMClient`, `OpenAiLLMClient`; Gemini
  deferred to 0.2.0 per ADR-0009). Resolved via `LLMClientFactory`.
  Lives in the `da-llm-client` library since ADR-0044.
- **ILogAnalyzer** - Per-cloud log-analysis abstraction. Provider
  registry + `LogEntryAdapter` per cloud (AWS, GCP, Azure, OCI).
- **DlapLogEvent** - The canonical normalized log-event shape; the
  "spine" every lifecycle signal is normalized onto. Protobuf schema of
  record at `da-protos/src/main/proto/dlap/log/v1/log_event.proto`; the
  running producer publishes Jackson JSON on
  `dlap.log.event.normalized`. Spec: `docs/specs/log-event.md`.
  Spelled `DalabLogEvent` in pre-rename docs.
- **ProviderConfig** - Injection point for cloud provider configuration
  (project IDs, regions, credentials). Replaces hardcoded values - see
  CLAUDE.md §9.

---

## Access-decision terms (Consumed stage, ADR-0040)

- **Consumed stage** (operator name **Consume**) - The lifecycle stage
  that answers the per-request question "may this principal read this
  asset now?" at read time, rather than acting on assets in the
  background. Implemented as an extension of `da-policyengine`, not a
  new service. See `docs/architecture/overview.md` §4.
- **AuthZEN** - The OpenID **Authorization API 1.0** standard that
  DLAP's access-decision endpoints implement, so that any conforming
  enforcement point can call DLAP without DLAP-specific client code.
  Interop notes: `docs/specs/authzen-interop.md`.
- **PDP** (Policy Decision Point) - The component that *decides*.
  `da-policyengine` is DLAP's PDP, serving `POST /access/v1/evaluation`
  (single) and `POST /access/v1/evaluations` (batch) on port 8083.
  Evaluation is deterministic - no LLM in the verdict path.
- **PEP** (Policy Enforcement Point) - The component that *enforces* a
  PDP verdict at the doorway. DLAP ships the decision, not the doorway:
  PEPs are pluggable, and a customer's existing MCP gateway, OPA
  sidecar or Keycloak can be the PEP.
- **AccessPolicy** - The access-intent document kind in the `dlap/v1`
  DSL (`kind: AccessPolicy`), evaluated by `AccessDecisionService`.
  Carries `spec.subjects`, `spec.actions`, `spec.match` (the same
  asset-selector clauses lifecycle policies use - labels are the join
  point), optional `spec.condition`, plus `spec.effect` and
  `spec.obligations`. One DSL, two kinds. Combining algorithm: explicit
  **deny overrides allow**; the fallback when nothing matches is
  `dlap.pdp.default-decision`, which ships `deny`.
- **Obligation** - A condition a PEP must apply to an *allowed* request
  before returning data. Types in v1
  (`docs/specs/enforcement-contract.md` §3): `mask` (blank values
  carrying the named labels), `redact_rows` (drop rows matching the
  label predicate), `log_only` (advisory; observe mode). Obligations
  from all contributing allows union to the most restrictive. A PEP that
  cannot honor a returned obligation **must** treat the decision as
  deny - fail closed.
- **Decision log** - The replayable audit trail for the Consumed stage.
  Every AuthZEN verdict emits an event on the Kafka topic
  **`dlap.access.decision`** (`DlapConstants.KafkaTopic.ACCESS_DECISION`,
  `DlapConstants.java:266`), produced by `AccessDecisionEventProducer`.
  Enforcement-point access logs returning via `da-logingest` are joined
  against it to detect drift between verdicts and actual reads.
- **MCP** (Model Context Protocol) - The agent-to-tool protocol DLAP's
  reference PEP fronts. `da-mcp-pep` maps an MCP access request to an
  AuthZEN evaluation, POSTs it to the PDP, caches the verdict briefly
  and applies obligations. It fails **closed** (deny) when the PDP is
  unavailable in `enforce` mode; `observe` mode fails open with an audit
  flag.

---

## Governance terms

- **Open Standard** - The published specifications that any
  implementation may target. Current set, under `docs/specs/`: Asset
  Descriptor, Cloud Resource, Label, Log Event, Policy DSL, Access
  Model, Enforcement Contract, AuthZEN Interop, Compliance Labels. See
  `docs/specs/README.md`.
- **Reference Implementation** - DLAP's services as a working
  embodiment of the Open Standard: the ten `da-*` services in the
  canonical dev compose, plus `da-mcp-pep` as the reference enforcement
  point. The standard is independent of the implementation; the
  implementation must not fork the spec.
- **Cloud-neutral** - No cloud-provider SDK imports in `core/`, `api/`,
  `common/`, or `da-protos/`. Provider code lives only under
  `providers/<cloud>/`. See CLAUDE.md §9 and ADR-0004.
- **Log-enriched** - Lifecycle decisions (archive, delete, re-label)
  are driven by audit-log and application-log signals, not solely by
  static metadata. This is the project's core differentiator.

---

## Apache / OSS terms

- **DCO** - Developer Certificate of Origin. `Signed-off-by` footer on
  every commit. Required for Apache incubation.
- **ADR** - Architecture Decision Record. One per entry in
  `DECISIONS.md`.
- **NOTICE** - Apache-required root file listing third-party
  attributions. Distinct from `LICENSE`.
- **Incubation** - Apache Software Foundation pre-TLP phase. Requires
  proposal, mentors, ≥3 initial committers from ≥2 orgs, IP clearance.

---

## Non-terms (things we do not say)

- "DAL" - ambiguous with "Data Access Layer". Use "DLAP" or "DLM".
- "Policies" vs "Rules" - use **Policy** for the user-facing contract,
  **Rule** for the evaluator primitive.
- The `Auto-*` stage names (**Auto-Discovery**, **Auto-Label**,
  **Auto-Archive**, **Auto-Delete**, **Auto-Report**) in new writing.
  They are superseded - use the canon above and the mapping table.
- "Legacy" to describe current code - use "pre-v0.1.0" or cite the
  specific finding ID.
