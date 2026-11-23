# DLAP Enforcement Contract: v1.1 specification

> ADR-0040. How Policy Enforcement Points (PEPs) - MCP gateways, OPA
> sidecars, platform adapters, or any consumer-side interceptor - obtain
> access verdicts from DLAP and how those verdicts are audited. DLAP is
> the Policy Decision Point (PDP); it ships the decision, not the doorway.

The wire contract is **OpenID AuthZEN Authorization API 1.0**
(<https://openid.net/specs/authorization-api-1_0.html>). This document
profiles it for DLAP: which fields DLAP reads, what DLAP returns, the
obligation semantics, and the decision-log event every verdict emits.
Anything not constrained here follows the AuthZEN spec verbatim.

## 1. Endpoints

Served by `da-policyengine` (port 8083), Keycloak-authenticated like every
other DLAP endpoint. PEPs authenticate as M2M service accounts
(`ROLE_SERVICE_ACCOUNT`, ADR-0034).

| Method + path | Purpose |
|---|---|
| `POST /access/v1/evaluation` | Single access evaluation |
| `POST /access/v1/evaluations` | Batch evaluation (AuthZEN boxcarring) |

## 2. Evaluation request: DLAP profile

```json
{
  "subject": {
    "type": "agent",
    "id": "svc-marketing-agent",
    "properties": {
      "roles": ["DATA_ANALYST"],
      "tier": "PRACTITIONER",
      "on_behalf_of": "user-uuid-or-subject"
    }
  },
  "action": { "name": "read" },
  "resource": {
    "type": "asset",
    "id": "9f6d…-asset-uuid",
    "properties": {
      "assetUri": "bq://project/dataset/table",
      "labels": ["PII", "EU_RESIDENT"]
    }
  },
  "context": {
    "purpose": "campaign-analytics",
    "consent": true,
    "region": "eu-west1"
  }
}
```

Profile rules:

- `subject.type`: `agent`, `user`, or `service`. `subject.properties.roles`
  carries realm roles; `tier` carries the ADR-0032 persona tier. When the
  caller is a Keycloak-authenticated principal, the PDP resolves roles from
  the token and `subject.properties` supplements only.
- `action.name`: DLAP recognizes `read`, `query`, `export`, `write`,
  `delete`. Unrecognized actions evaluate against policies that name them
  explicitly; otherwise the default decision applies.
- `resource.id`: the DLAP catalog asset UUID. If absent, `resource.
  properties.assetUri` is resolved via the catalog (`GET /assets/resolve`).
  `resource.properties.labels` MAY be supplied by the PEP as a hint; the
  PDP's catalog view is authoritative when the asset is resolvable.
- `context`: free-form per AuthZEN; DLAP evaluates the keys named in
  `AccessPolicy.spec.condition` clauses (`purpose`, `consent`, `region`
  are the v1 vocabulary).

## 3. Evaluation response: DLAP profile

```json
{
  "decision": true,
  "context": {
    "id": "dec-0191c2…",
    "reason_admin": { "en": "allowed by access-eu-marketing-aggregate" },
    "obligations": [
      { "type": "mask", "labels": ["PII"] }
    ]
  }
}
```

- `decision`: boolean per AuthZEN. `false` carries `reason_admin` naming
  the denying policy (or `default-decision`).
- `context.id`: the decision id; the same id appears in the decision-log
  event (§6) for replay/audit joins.
- `context.obligations`: DLAP extension (AuthZEN permits additional
  context members). A PEP that cannot honor a returned obligation MUST
  treat the decision as `false` (fail-closed on obligations).

Obligation types (v1):

| Type | Semantics |
|---|---|
| `mask` | Redact/blank values in columns/fields carrying the named labels before returning data |
| `redact_rows` | Drop rows matching the named label predicate |
| `log_only` | No transformation; verdict is advisory (observe mode) |

## 4. Policy model

Verdicts evaluate `kind: AccessPolicy` documents in the dlap/v1 DSL
(ADR-0021, ADR-0031, ADR-0040), stored and validated like lifecycle
policies:

```yaml
apiVersion: dlap/v1
kind: AccessPolicy
metadata:
  id: access-eu-marketing-aggregate
  name: "EU marketing data: aggregate analytics under consent"
  owner: "cdo-office@example.com"
  enabled: true
spec:
  subjects:
    tiers: [PRACTITIONER]          # ADR-0032 persona tiers
    roles: [DATA_ANALYST]          # OR realm roles; either clause may stand alone
  actions: [read, query]
  match:                           # same clause set as lifecycle policies
    labels:
      anyOf: [EU_RESIDENT]
      noneOf: [LEGAL_HOLD]
  condition:                       # request-context clauses (all must hold)
    purpose:
      anyOf: [campaign-analytics]
    consent: true
  effect: allow                    # allow | deny
  obligations:
    - type: mask
      labels: [PII]
```

Combining algorithm (fixed, ADR-0040): evaluate all ENABLED
AccessPolicies whose `subjects` + `actions` + `match` + `condition` hold;
**explicit deny overrides any allow**; obligations of all contributing
allows union (most restrictive); if nothing matches, the configured
default applies (`dlap.pdp.default-decision`, shipped `deny`).

## 5. Determinism requirements

- The verdict path is a deterministic evaluation of parsed policy - no
  LLM, no network calls in the hot path. Policies and label snapshots are
  cached in memory and refreshed on policy-change events / catalog reads.
- Identical `(subject, action, resource, context, policy-set)` inputs MUST
  produce identical decisions - decisions are replayable from the
  decision log plus the policy history.

## 6. Decision-log event

Every evaluation, allow or deny, publishes one event to
`dlap.access.decision` (`DlapConstants.KafkaTopic.ACCESS_DECISION`):

```json
{
  "decisionId": "dec-0191c2…",
  "timestamp": "2026-07-05T18:02:11Z",
  "subjectType": "agent",
  "subjectId": "svc-marketing-agent",
  "onBehalfOf": "user-uuid",
  "action": "read",
  "assetId": "9f6d…",
  "assetUri": "bq://project/dataset/table",
  "decision": "ALLOW",
  "policyIds": ["access-eu-marketing-aggregate"],
  "obligations": [{ "type": "mask", "labels": ["PII"] }],
  "defaultApplied": false,
  "latencyMs": 3
}
```

This is the single audit trail for the Consumed stage. Enforcement-point
access logs ingested by `da-logingest` are joined against it (by canonical
asset, mapped principal and time window, section 6.1) to detect drift between
verdicts and actual reads: the same detect-and-verify loop the platform
already runs for lifecycle actions.

`timestamp` is required: it is the decision's business time. `assetUri`
SHOULD be present whenever the resource resolves (the PDP fills it from the
request or the catalog); it is the field the drift join keys on.

### 6.1 Drift join (da-reporting)

Added in v1.1 (RPT-03). Normative for any consumer of the decision log that
correlates it with reads; `da-reporting` implements it in
`drift/DriftEventProjector`, `drift/DriftDetectionOrchestrator` and
`identity/**`.

**Inputs.**

| Side | Topic | Accepted when | Business time |
|---|---|---|---|
| Decision | `dlap.access.decision` | `decisionId`, `subjectId`, `timestamp` and an asset are present and `decision` is `ALLOW` or `DENY` (any case) | `timestamp` (ISO-8601 instant or offset date-time) |
| Read | `dlap.discovery.asset.accessed` | A `DlapLogEvent` (any encoding the shared codec reads) whose effective `event_category` is `EVENT_CATEGORY_DATA_READ`, whose `result_status` is not `FAILURE` or `PENDING`, whose `parse_status` is not `PARSE_STATUS_PARTIAL`, with `event_id`, `event_time`, a target and `principal.id` | `event_time` |

Neither side is ever stamped with the consumer's clock. An event that is not
accepted is acknowledged, logged and counted on `dlap.drift.events.skipped`
with tags `stream` (`read`, `decision`) and `reason`: `null_payload`,
`undecodable`, `missing_event_id`, `missing_decision_id`,
`missing_event_time`, `unparsable_event_time`, `future_event_time`,
`missing_asset`, `missing_principal`, `missing_subject`, `unknown_decision`,
`not_a_data_read`, `not_successful`, `partial_parse`, `field_too_long`. The
service clock serves one check: a business time more than
`dlap.drift.max-future-skew-seconds` (default 300; negative disables) ahead of
it is `future_event_time`, because a future-stamped ALLOW would authorize
reads that have not happened. An observed read (`DATA_READ_OBSERVED`, from
inventory or metadata state) names no requester and is `not_a_data_read`.

**Asset key.** Both sides pass through `AssetIdNormalizer` (ADR-0047) and roll
up to the catalog asset (ADR-0046: an object read counts against its bucket or
container). Decision: `assetUri`, else `assetId`. Read:
`targetResource.canonicalUri`, else `targetResource.id` with the event's
provider as the hint. An identifier the normalizer rejects is kept as
written, so producers that already agree on an id still join; counter
`dlap.drift.asset.keys` (tags `stream`, `form=canonical|native`) shows how
often that happens.

**Subject key.** The decision side is `subjectId`, the AuthZEN `subject.id`,
as written. The read side is `principal.id` attributed through the principal
identity map:

1. The principal is normalized: an IAM member prefix (`user:`,
   `serviceAccount:`, `group:`) is removed; an email or a GUID (Entra object
   id) is lower-cased; anything else (ARN, `principal://` subject) is kept.
2. Candidates are that key and, for an AWS assumed-role session ARN
   (`arn:<p>:sts::<acct>:assumed-role/<role>/<session>`), the role ARN
   `arn:<p>:iam::<acct>:role/<role>` (role paths are not in the session ARN;
   map the session or a path-less role ARN).
3. The first candidate with an entry wins; for one candidate an entry for the
   event's `source_provider` wins over an `ANY` entry.
4. With no entry the normalized key is the subject, so logs that already
   carry DLAP subjects join; the lookup counts as `outcome=unmapped` on
   `dlap.drift.principal.resolutions`.

The map is table `dlap_reporting.principal_identity_map` (Flyway V4):
`provider` (`ANY`, `AWS`, `GCP`, `AZURE`, `OCI`, `UNITY`, `DATABRICKS`,
`SNOWFLAKE`), `principal_key` (normalized), `subject_id`, `source`
(`SEED`, `MANUAL`), unique on `(provider, principal_key)`. Operators add
`MANUAL` rows with SQL. A seed file named by
`dlap.reporting.identity-map.seed-file` (env
`DLAP_REPORTING_IDENTITY_MAP_SEED_FILE`) is applied at start-up and owns every
key it names; `SEED` rows it no longer names are removed. A configured file
that is missing or invalid fails the start. Format:

```text
# comments and blank lines are ignored
provider,principal,subject
GCP,serviceAccount:marketing-agent@acme-prod.iam.gserviceaccount.com,svc-marketing-agent
AWS,arn:aws:iam::123456789012:role/analytics-reader,svc-analytics
ANY,alice@example.com,alice
```

**Correlation.** For a read at `t`, the applicable decisions are those for the
same `(asset key, subject key)` with a decision time in `[t - window, t]`
(`dlap.drift.window-seconds`, default 300). Any applicable DENY gives
`ENFORCEMENT_BYPASS` (correlated to the latest DENY); else no applicable ALLOW
gives `UNENFORCED_READ`; else there is no finding.

**Order independence.** Findings are a function of the buffered events, not of
their arrival order. A read reconciles its findings against the decisions
buffered so far; a decision reconciles every buffered read of its key in
`[decision time, decision time + window]`. So an ALLOW consumed after its read
retracts the read's `UNENFORCED_READ` (counter `dlap.drift.findings.retracted`),
and a DENY consumed late turns it into an `ENFORCEMENT_BYPASS`. Each
transaction first takes the PostgreSQL advisory lock of its key, so a read and
a decision committed concurrently by two listeners or two replicas still see
each other. Redelivery is idempotent on `eventId` and `decisionId`.

The buffered rows and the findings carry the join keys, so the findings API
(`GET /api/v1/reporting/drift/findings`) filters on the canonical asset URI
and the DLAP subject.

## 7. Adoption modes

| Mode | What the customer runs | DLAP artifact |
|---|---|---|
| Pull (primary) | Existing MCP gateway / OPA sidecar / AuthZEN-capable IAM calls the evaluation endpoint | This contract |
| Push-down (supplement) | Platform-native controls (UC grants, BQ policy tags…) | `PolicyProjectionSink` SPI |
| Observe | Any of the above with `log_only` obligations / `default-decision: allow` | Decision log only |

Prevention belongs at high-sensitivity choke points; detection everywhere
else. A deployment that starts in observe mode and graduates label-by-label
to enforcement is the expected enterprise rollout path.

### 7.1 Push-down projection (`PolicyProjectionSink`)

Push-down compiles `kind: AccessPolicy` documents into **platform-native
access controls** so a platform self-enforces even when it never calls the
PDP. It is a supplement, not a replacement: **the AuthZEN endpoint (§4) stays
the source of truth**; a projection is detection-grade / best-effort and may
lag or under-approximate the live decision. It cannot express per-request
`condition` context (purpose, consent, region), so those clauses are dropped
on projection and remain PDP-only.

Modeled on the ADR-0035 compliance-sink pattern, the feature is off by
default (`dlap.policy.projection.enabled`, default `false`) and built in
`da-policyengine`'s `projection` package:

- **`AccessPolicyToGrantTranslator`** (pure): compiles an AccessPolicy plus
  its resolved securables into a `CompiledAccessPolicy` of neutral
  `GrantIntent`s (`principal × privilege × securable`, grant/revoke).
  Subjects map to principals (roles verbatim; tiers as `tier:<TIER>`);
  actions map to privileges by a fixed table (`read`/`query`/`export`/`list`
  → `SELECT`, `write`/`update`/`delete`/`create` → `MODIFY`, unknown →
  `SELECT`); `effect: allow` → grants, `effect: deny` → revokes.
- **`PolicyProjectionSink`** (SPI): `project(...)` / `revoke(...)`. The
  default **`LoggingPolicyProjectionSink`** is observe-only (logs what it
  would project) and yields to any deployment-supplied sink
  (`@ConditionalOnMissingBean`).
- **`PolicyProjectionService`**: created only when
  `dlap.policy.projection.enabled=true`. On startup and on each committed
  `PolicyChangedEvent` it re-projects the ENABLED AccessPolicy set through the
  sink. Additive; does not alter PDP evaluation.

The first concrete sink is **Unity Catalog** (`dlap.policy.projection.unity.enabled`,
Q-022): each `GrantIntent` renders to a UC `GRANT`/`REVOKE` statement
(`` GRANT SELECT ON TABLE <cat>.<schema>.<name> TO `<principal>` ``). Where DLAP
has only a label selector (no resolved table), it grants at `SCHEMA` scope with
the originating label in a trailing comment. By default (no live
`UnityCatalogGrantClient` bean supplied; DLAP ships only the offline stub) a
stub client records the statements instead of executing them, mirroring the
da-catalog UC metastore integration (ADR-0029).
