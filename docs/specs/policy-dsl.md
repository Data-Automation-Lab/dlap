# DLAP Policy DSL v1.6 specification

> v1.6 (POL-05): a `kind: Policy` document that states `spec.effect` is refused when
> it is written (HTTP 400) and reported by the start-up check; a database trigger
> refuses a `LEGACY_RULE` row that holds a document and a change of a row from `DSL`
> to `LEGACY_RULE` (section "A policy row without a document"); the lifecycle event
> consumer reads the catalog through a bounded cache of at most 30 seconds (section
> "Event-path catalog cache"); the scheduled sweep pages the activity report by
> keyset when the catalog supports it (section "Schedule triggers").
> v1.5 (POL-04): the last two ways a broken policy failed open are closed. A broken
> document whose `spec.effect` reads `deny` is an invalid deny whatever its `kind`
> says (a `kind: Policy` document is ignored only when it states no effect), and an
> ENABLED policy row of kind `DSL` whose document is null or blank is an invalid deny
> with no readable scope (`policy-store-invalid`); a `LEGACY_RULE` row is unchanged
> (section "Unparseable AccessPolicies").
> v1.4 (POL-03): an ENABLED AccessPolicy whose document does not parse no longer fails
> open: unless its effect reads `allow` it denies its readable scope (`policy-invalid`),
> or every request when no scope can be read (`policy-store-invalid`); the coverage
> freshness rule now applies on the event and PDP paths; `readCoverageSince` proves an
> asset unread since coverage began (section "Unparseable AccessPolicies", section
> "Read coverage and destructive actions").
> v1.3 (POL-02): a malformed clause in a deny AccessPolicy is `UNKNOWN`, so the deny
> fails closed, and stored AccessPolicies and schedule crons are re-validated at
> start-up; `setRetention` counts as destructive for the read-coverage rule (no
> approval); on the event path the catalog's read coverage wins for those policies;
> a `FULL` older than `dlap.policy.coverage.max-age` counts as unknown for them; a
> schedule slot whose sends fail stays due; observe mode keeps the explicit allow
> default. A malformed operator map that v1.2 ignored is now a defect.
> v1.2 (POL-01): schedule triggers fire; a `createdAt` match clause; three-valued
> evaluation (unknown is never stale or fresh); read coverage gates destructive
> actions; every action carries the asset identity; shadow mode. v1.1 (ADR-0021)
> added the full lifecycle trigger set and the action set. Both changes are
> additive to v1.0, except that a match on a value the snapshot does not carry,
> which v1.1 treated as "no match", now has a stated meaning (section "Unknown
> values").

R-P0-2 / M5. The YAML policy language that drives lifecycle actions (archive,
delete, label, alert, retention) on assets and, as `kind: AccessPolicy`, access
decisions (ADR-0040, `enforcement-contract.md`). Policies live in the
`da-policyengine` service. Lifecycle policies fire on a schedule and on typed
lifecycle events. Each fire publishes one `PolicyActionRequest` on
`dlap.policies.actions`, which the lifecycle services (da-autoarchival,
da-autodelete, da-autolabel, da-catalog for `setRetention`) consume.

This document specifies the v1 wire format. Adopters who write their own policy
evaluators should produce events that match the `PolicyActionRequest` shape in
the Wire format section below.

## Anatomy of a policy

```yaml
apiVersion: dlap/v1
kind: Policy
metadata:
  id: archive-stale-pii
  name: "Archive stale PII data"
  description: "Move PII assets to Archive storage class after 90 days
                of no access, when not under legal hold."
  owner: "data-governance-team@example.com"
  enabled: true
spec:
  # Triggers: when the evaluator considers this policy.
  triggers:
    - type: schedule
      cron: "0 0 2 * * ?"      # 02:00 UTC daily
    - type: event
      lifecycle: accessed
  # Match: the asset filter expression. AND of all clauses.
  match:
    cloudProvider: GCP
    assetType:
      anyOf: [GCS_BUCKET, BIGQUERY_TABLE]
    labels:
      anyOf: [PII, PHI]
      noneOf: [LEGAL_HOLD]
    lastAccessedAt:
      olderThan: P90D       # ISO-8601 duration
  # Action: what to do when the match holds.
  action:
    type: archive
    requireApproval: false
    dryRun: false
    parameters:
      targetStorageClass: ARCHIVE
      auditNote: "Stale PII auto-archive per policy archive-stale-pii"
```

## Field reference

### metadata

| Field | Type | Required | Notes |
|---|---|---|---|
| `id` | string | yes | Stable kebab-case id. Used as topic key. |
| `name` | string | yes | Human display. |
| `description` | string | no | Free text. |
| `owner` | string | yes | Email or team handle. Audit log captures this. |
| `enabled` | boolean | yes | Disabled policies are loaded but never fire. |

### spec.triggers

Mixed list. At least one trigger required.

- `type: schedule`: `cron` is required and must have six fields, seconds first
  (Spring `CronExpression` syntax, which accepts `?`, `L`, `W` and `#`; Quartz's
  optional seventh year field is not accepted). A document whose schedule trigger
  has no cron or a cron that does not parse is refused when it is saved (HTTP 400).
  A stored document with such a cron (saved before v1.2) fails closed: it does not
  parse, so it is inert on every path. The start-up check logs it with its id and
  counts it (`policyengine.policies.invalid{check="schedule-cron"}`); the operator
  fixes the cron and saves the policy again. Nothing is rewritten.
  At each slot the evaluator runs the policy against the catalog's assets; see
  "Schedule triggers" below.
- `type: event`: fires on a Kafka event. The evaluator identifies the asset the
  event names and runs the policy against that single asset. Supported topics
  (post-ADR-0021):
  - `dlap.discovery.asset.discovered` (lifecycle = CREATED)
  - `dlap.discovery.asset.modified` (lifecycle = MODIFIED)
  - `dlap.discovery.asset.deleted` (lifecycle = DELETED)
  - `dlap.discovery.asset.accessed` (lifecycle = ACCESSED)
  - `dlap.discovery.asset.permission-changed` (lifecycle = PERMISSION_CHANGED)
  - `dlap.discovery.asset.archived` (lifecycle = ARCHIVED)

  Convenience shorthand: `lifecycle: created | modified | deleted |
  accessed | permission-changed | archived` is sugar for the
  matching typed topic. The consumer subscribes to the six typed topics only; a
  `topic:` naming another topic is stored but never fires.

### spec.match

Conjunction of clauses. All clauses must hold for an asset to match. Each clause
is evaluated three-valued (section "Unknown values").

| Clause | Reads | Semantics | Example |
|---|---|---|---|
| `cloudProvider` | `cloudProvider` | string equality OR `anyOf` / `noneOf` | `GCP` |
| `assetType` | `assetType` | string equality OR `anyOf` / `noneOf` | `BIGQUERY_TABLE` |
| `region` | `region` | string equality OR `anyOf` / `noneOf` | `us-central1` |
| `labels` | `labels` | set membership; `anyOf` / `noneOf` / `allOf` | `anyOf: [PII]` |
| `lastAccessedAt` | `lastAccessedAt`, `readCoverage` | age of the latest recorded read: `olderThan` / `youngerThan` (ISO-8601 duration) | `olderThan: P90D` |
| `createdAt` | `cloudCreatedAt` | age of the provider's creation of the asset: `olderThan` / `youngerThan` | `olderThan: P365D` |
| `sizeBytes` | `sizeBytes` | numeric comparator: `gte` / `lte` / `eq` | `gte: 1073741824` |
| `nameMatches` | `assetName` (else `name`) | regular expression, see below | `^prod-.*` |
| `tagMatches` | `tags` | map equality on cloud-native tags | `env: production` |

The "Reads" column names the asset snapshot key (the catalog asset's field,
`asset-descriptor.md`). Ages are measured against the evaluation instant: the
engine's clock for event and on-demand evaluation, the sweep's as-of instant for a
schedule fire. Both `lastAccessedAt` and `createdAt` are event times (ADR-0046):
`createdAt` reads the provider's creation time (`cloudCreatedAt`), never the
catalog row's insert time or `discoveredAt`, and `lastAccessedAt` is the time of the
read in the source log, never the time DLAP processed it.

`nameMatches` takes a regular expression in RE2 syntax (RE2/J, ADR-0057): no
lookaround and no backreferences, at most 256 characters, matched with find
semantics (anywhere in the name; anchor with `^` and `$`). A pattern that breaks
these rules is refused when the policy is saved (HTTP 400) and fails closed (no
match) if one is ever evaluated.

A clause whose value is malformed or a clause name not in this table is a policy defect.
Malformed means: a string clause that is neither a string nor an operator map; an operator map
that is not a map, names no operator, or names an operator the clause does not define (for
example `anyof`, `olderthan`); an operator value of the wrong type (`anyOf`, `noneOf` and `allOf`
take a list, `olderThan` and `youngerThan` an ISO-8601 duration, `gte`, `lte` and `eq` a whole
number); a `nameMatches` that is not a bounded RE2 pattern; an empty or non-map `tagMatches`.
A defect evaluates `UNKNOWN` in an AccessPolicy with `effect: deny`, so the deny applies (fails
closed), and `FALSE` in every other policy. Until v1.2 an unknown operator in a map was ignored,
so `labels: {anyof: [PII]}` matched every asset; it is a defect now. Two operators on one string
clause (`anyOf` and `noneOf`) both apply.

The service re-validates every stored policy once it is ready (`StoredPolicyValidator`). Nothing
is rewritten. It logs at WARN, with the policy's row id and `metadata.id`:

- each AccessPolicy, enabled or not, that does not parse (the PDP does not load it) or has a
  malformed clause, with the reasons;
- each document with a schedule cron that does not parse (see `spec.triggers`).

The counts are the gauge `policyengine.policies.invalid{check}`: `access-policy`,
`schedule-cron`, and `unreadable` for a document that is not policy YAML. The gauge holds the
counts of the start-up check. Saving a policy does not yet check its clauses (only
`nameMatches` and crons); the start-up check and the deny rule above cover what a save lets
through.

### Unknown values

A match clause is `TRUE`, `FALSE` or `UNKNOWN`. It is `UNKNOWN` when the asset
snapshot does not carry the value the clause reads: a missing `labels` key (a
catalog asset always has a known, possibly empty, label set; a PEP hint or an event
may not), a size, creation time or last read the catalog does not know, tags the
snapshot does not carry. Unknown is never stale, fresh, zero or empty.

The match block is the conjunction: `FALSE` when any clause is `FALSE`, else
`UNKNOWN` when any clause is `UNKNOWN`, else `TRUE`.

| Policy | Applies when the match is |
|---|---|
| `kind: Policy` (lifecycle) | `TRUE` only. An unknown value matches nothing: no action is published. |
| `kind: AccessPolicy`, `effect: allow` | `TRUE` only. |
| `kind: AccessPolicy`, `effect: deny` | `TRUE` or `UNKNOWN`. A deny-if-stale policy denies data whose age is unknown, and a malformed clause is `UNKNOWN` here. |

On-demand evaluations (`POST /api/v1/policyengine/evaluations`) record an
unknown match as `FAIL` with `outcome: UNKNOWN` and the unknown clause names in
the evaluation details.

### Read coverage and destructive actions

A recorded `lastAccessedAt` proves the asset was read then. It proves that
nothing read it since only when read logging covers the asset fully
(`readCoverage: FULL`, set per asset by COV-01; asset-descriptor.md "Read
coverage"). The full-coverage rule applies to:

- a lifecycle policy whose `action.type` archives or deletes (any type containing
  `archive` or `delete`, ignoring case);
- a lifecycle policy whose action can shorten the asset's effective retention:
  `setRetention`, compared without case, dots, underscores, hyphens or spaces. The
  engine does not see the asset's retention rules, and a `setRetention` shortens the
  effective retention whenever the asset had none or the policy's own earlier rule
  was longer, so every `setRetention` counts. It needs no approval (approvals.md);
- every AccessPolicy.

Under the rule, a recorded read under `PARTIAL`, `NONE` or unknown coverage is a
lower bound of the true last read:

| Clause | Recorded read | Coverage `FULL` | Coverage `PARTIAL`, `NONE` or unknown |
|---|---|---|---|
| `olderThan: d` | older than `d` | `TRUE` | `UNKNOWN` |
| `olderThan: d` | within `d` | `FALSE` | `FALSE` |
| `youngerThan: d` | within `d` | `TRUE` | `TRUE` |
| `youngerThan: d` | older than `d` | `FALSE` | `UNKNOWN` |
| either | none recorded | `UNKNOWN` | `UNKNOWN` |

Under the rule, coverage counts as `FULL` only while it is fresh: a `FULL` whose
`readCoverageObservedAt` (the catalog activity row, COV-02) is older than
`dlap.policy.coverage.max-age` (ISO-8601, default `P2D`, env
`DLAP_POLICY_COVERAGE_MAX_AGE`) counts as unknown coverage, because a collector that
failed as a whole leaves the last value in place. An observation time that does not
parse counts as stale. A `FULL` with no observation time keeps the v1.2 behaviour
(taken at face value) and is logged at WARN once per asset per sweep or event; the
PDP logs it at WARN once per asset per policy refresh (`dlap.pdp.refresh-interval-ms`,
default 30 s), for at most 1000 assets per refresh. The rule is the same on every
path (v1.4): the sweep reads the observation time from the activity row, and the
event path and the PDP read it from the catalog's single-asset read
(`GET /api/v1/catalog/assets/{id}`, field `readCoverageObservedAt`).

On the event path the snapshot is the event's, completed from the catalog. For a
policy under the rule, the catalog's `readCoverage` (with its observation time and
its coverage start) replaces the event's, so an event cannot claim coverage the catalog does not record;
when the catalog does not answer, coverage is unknown. `lastAccessedAt` is the later
of the event's and the catalog's recorded reads. The published `matchSnapshot` is
the snapshot the policy was judged on.

Other actions (`addLabel`, `alert.*`, `tagAsset`, ...) read a recorded last
read at face value, and on the event path keep the event's values.

**Coverage start (v1.4).** The catalog sends `readCoverageSince`, the start of the
current unbroken run of `FULL` coverage, while coverage is `FULL` (COV-02). When the
coverage counts as `FULL` (fresh under the rule, at face value for other actions)
and its start is known, every read since the start is recorded. If no read is
recorded at or after the start, the true last read lies before the start, or there
was none: the start is an exclusive upper bound, and a recorded earlier read is a
lower bound. Then:

| Clause | Start at least `d` old | Start within `d`, recorded read within `d` | Start within `d`, no recorded read within `d` |
|---|---|---|---|
| `olderThan: d` | `TRUE` | `FALSE` | `UNKNOWN` |
| `youngerThan: d` | `FALSE` | `TRUE` | `UNKNOWN` |

So an asset never read since coverage began more than `d` ago is `TRUE` for
`olderThan: d`: the policy engine can retire never-read data. A recorded read at or
after the start is exact, as before. Outside the rule a recorded read keeps its face
value, and the start is used only when no read is recorded. Without a known start
(not sent, not `FULL`, stale under the rule, or not an instant) an asset with no
recorded read stays `UNKNOWN` for `lastAccessedAt`, as in v1.3; the catalog's
retention evaluation (`setRetention` with basis `LAST_ACCESSED`) is the other way to
retire such data.

### Schedule triggers

`ScheduledPolicySweeper` in `da-policyengine` fires schedule triggers. It runs every
`dlap.policyengine.schedule.tick` (default `PT30S`) as a cluster-wide singleton
(ShedLock lock `policy-schedule-sweeper`, database time).

- **When a policy fires.** A policy is due when one of its crons has a slot after
  the policy's stored cursor and at or before now. Crons are read in
  `dlap.policyengine.schedule.zone` (default `UTC`). A policy the sweeper sees for
  the first time starts its cursor at that instant: it fires from its next slot,
  never retroactively. Missed slots (service down, policy disabled) coalesce into
  one fire for the latest missed slot.
- **What a fire evaluates.** One sweep serves every policy due in the tick. It pages
  the catalog activity report (`GET /api/v1/catalog/assets/activity`, sorted by
  `assetUri`, statuses `dlap.policyengine.schedule.statuses`, default `ACTIVE` and
  `ARCHIVED`) as of the sweep instant, and evaluates each row against that instant.
  Deleted and pending-deletion assets are never acted on by a schedule fire. The
  activity rows carry no tags, so `tagMatches` is `UNKNOWN` in a sweep.
- **Paging (v1.6).** The first request asks for page 0 by offset. When the answer
  carries `nextAfter` (the catalog's keyset contract), every later request is
  `?after=<nextAfter>&size=N` (rows in `assetUri` order) and the sweep ends on the page
  whose `nextAfter` is null, so a row written during a long sweep cannot make it skip
  or repeat an asset. A cursor that repeats itself, or that follows an empty page,
  aborts the sweep and leaves the slot due. The cursors are not compared for order,
  because the catalog sorts by its database collation. An answer without `nextAfter`
  (a catalog that pages by offset only) is read by offset as before. The keyset mode is
  counted in `policyengine.schedule.paging{mode=keyset}`.
- **Delivery.** A slot is recorded (table `dlap_policyengine.policy_schedule_state`)
  only after the sweep has read every page. If the catalog fails mid-sweep, or
  answers a page that carries no paging data, nothing is recorded and the next tick runs the slot again; the `fireId` of a schedule fire
  is a function of (policy, slot, asset), so a repeat carries the same id. A sweep
  that reaches `dlap.policyengine.schedule.max-assets-per-sweep` (default 100000)
  stops with a warning and records the slot.
- **Failed sends.** After the last page the sweep waits, up to
  `dlap.policyengine.schedule.send-timeout` in total, for the broker to acknowledge
  every action it published. A policy's slot is not recorded when any of its actions
  failed before the send (`FAILED`), failed asynchronously, or was not acknowledged in
  time; the next sweep inside the slot window runs the slot again (same fire ids;
  consumers treat a repeated `fireId` as the same decision). Each such send is counted
  in the fire's `failed` count, logged at WARN and counted in
  `policyengine.schedule.sends{result=failed}`; `policyengine.schedule.slots{result}`
  counts `recorded` and `send-failed` slots. Held approval requests and shadowed
  actions need no acknowledgement. A failure that repeats on every attempt keeps the
  slot due until the next cron slot, which then takes its place.

| Property | Default | Meaning |
|---|---|---|
| `dlap.policyengine.schedule.enabled` | `true` | `false` removes the sweeper; schedule triggers then never fire |
| `dlap.policyengine.schedule.tick` | `PT30S` | interval between checks for due slots |
| `dlap.policyengine.schedule.initial-delay` | `PT30S` | delay before the first check |
| `dlap.policyengine.schedule.lock-at-most-for` | `PT1H` | lock bound; keep it above twice the longest sweep |
| `dlap.policyengine.schedule.lock-at-least-for` | `PT5S` | minimum lock hold |
| `dlap.policyengine.schedule.zone` | `UTC` | time zone of the cron expressions |
| `dlap.policyengine.schedule.page-size` | `200` | catalog page size (1 to 200) |
| `dlap.policyengine.schedule.max-assets-per-sweep` | `100000` | bound on assets one sweep reads |
| `dlap.policyengine.schedule.statuses` | `ACTIVE,ARCHIVED` | lifecycle statuses a sweep evaluates |
| `dlap.policyengine.schedule.send-timeout` | `PT60S` | total wait for the broker to acknowledge a sweep's actions; an action not acknowledged in time keeps its slot due |
| `dlap.policy.coverage.max-age` | `P2D` | under the full-coverage rule, a `FULL` observed longer ago counts as unknown coverage (section "Read coverage and destructive actions") |

### Event-path catalog cache

The lifecycle event consumer (`LifecyclePolicyConsumer`) asks the catalog twice per
event when a policy listens on the topic: the asset id of the event's URI
(`GET /api/v1/catalog/assets/resolve`), then the asset (`GET /api/v1/catalog/assets/{id}`).
On `dlap.discovery.asset.accessed` that is one pair of calls per read, so
`EventPathCatalogLookup` answers repeats from memory (v1.6).

- **Never older than the TTL.** An entry is served only while its age on the policy
  clock is at least zero and below `dlap.policyengine.catalog-cache.ttl` (default
  `PT30S`). An entry as old as the TTL, or stored before the clock stepped back, is
  looked up again and replaced. The read coverage freshness rule (`P2D`, section "Read
  coverage and destructive actions") is therefore decided on catalog data at most one
  TTL old. A legal hold, label or retention state set in the catalog is seen by the
  event path after at most one TTL. A destructive action still waits for approval
  (`ApprovalGate`), and da-autodelete's soft-delete admission refuses an asset the
  catalog says is on legal hold (`RefusalReason.LEGAL_HOLD`) when it runs.
- **Only answers are stored.** An unknown asset, a 404, a catalog that is down or a
  circuit that is open is never stored, so a newly created asset or a recovered catalog
  is seen on the next event.
- **Bounded.** At most `dlap.policyengine.catalog-cache.max-entries` entries (default
  `10000`) over both kinds of key (asset id and URI); the least recently used entry is
  dropped to make room. `ttl=PT0S` turns the cache off.
- **Event path only.** The PDP (`POST /access/v1/evaluation`) and the scheduled sweep read the
  catalog directly and never use the cache.
- **Metrics.** `policyengine.catalog.cache.lookups{lookup=asset|uri, result=hit|miss}`
  counts every lookup (a lookup with the cache off counts as a miss) and the gauge
  `policyengine.catalog.cache.entries` is the number of entries held.

| Property | Default | Meaning |
|---|---|---|
| `dlap.policyengine.catalog-cache.ttl` | `PT30S` | how long a catalog answer may be served on the event path; `PT0S` turns the cache off |
| `dlap.policyengine.catalog-cache.max-entries` | `10000` | most entries held |

### Observe mode (AccessPolicies)

`dlap.pdp.default-decision=allow` is observe mode (authzen-interop.md,
enforcement-contract.md); the shipped default is `deny`. The default applies only
when no AccessPolicy contributes. An allow AccessPolicy whose match is `UNKNOWN`
does not contribute, so in observe mode the request falls to the operator's
explicit allow default: there is no implicit deny for unknown data. This is
intended (decision D42 on Q-431): the operator chose `allow` to watch decisions
before enforcing them. A deny AccessPolicy still applies on `UNKNOWN` (and on a
malformed clause) in observe mode, so a deny-if-stale policy denies data of unknown
age whatever the default. To enforce on unknown data, switch the default to `deny`
or write the rule as a deny policy. An invalid deny (next section) also applies in
observe mode.

### Unparseable AccessPolicies

The API refuses to save or enable a document that does not parse, but a stored
document can still stop parsing (a direct database edit, a restore of an old row, a
stricter parser after an upgrade). Before v1.4 the PDP skipped such a document, so a
deny that did not parse failed open. Since v1.4 (decision D43 on Q-842) the PDP reads
every ENABLED document that does not parse again as a plain YAML tree:

| What the document reads | What the PDP does |
|---|---|
| `kind` reads `Policy` (any case) and `spec.effect` is absent | Ignores it: a lifecycle document. |
| `spec.effect` reads `allow` (any case), whatever the `kind` | Skips it. Skipping an allow can only deny more, never less. |
| `spec.effect` reads `deny`, whatever the `kind`, or cannot be read (missing on a document that is not a lifecycle `kind: Policy`, empty, not text, another value, or the document is not YAML) | Keeps it as an **invalid deny**. |

A `kind: Policy` document is ignored only when it states no effect at all (v1.5, Q-870).
A lifecycle document has no `spec.effect`, so a document that has one was meant as an
access decision, and one whose only fault is its `kind` still denies.

An invalid deny is scoped by what of its selectors can be read:

- **Subject selector:** `spec.subjects` is a map whose `roles` and `tiers` (each
  optional) are lists of non-blank plain values, at least one of them non-empty. It
  matches subjects as in a valid policy. Any other shape is unreadable as a whole,
  because a malformed half could name anyone.
- **Resource selector:** `spec.match` is a non-empty map. Its clauses are evaluated
  as in a deny AccessPolicy, so a malformed clause or an unknown value applies.
- An unreadable selector covers everything. `spec.actions`, `spec.condition` and
  `metadata.enabled` are not read: they could only narrow a deny whose document is
  broken.

| Scope | Decision |
|---|---|
| Subject or resource selector readable | Every request inside the scope is denied, whatever its action and whatever any allow says. `reason_admin` reads `policy-invalid: <id>`, and the id is in the decision's policy ids. Requests outside the scope are decided by the other policies. |
| Neither selector readable | Every request is denied with `reason_admin` `policy-store-invalid: <id>` until the document is fixed or the policy is disabled. |

#### A policy row without a document (v1.5)

Each policy row has a `policy_kind` (Flyway V10 of da-policyengine): `DSL` for a row
whose behaviour is its dlap/v1 document and `LEGACY_RULE` for a row from before the
DSL, which never had one (ADR-0057). Every row the API creates is `DSL`, and a
`LEGACY_RULE` row that is updated with a document becomes `DSL`. The migration set the
kind from the data: a row with a non-blank `policy_document` became `DSL`, every other
row `LEGACY_RULE`.

| ENABLED row | What the PDP does |
|---|---|
| `DSL`, `policy_document` null or blank | Keeps it as an invalid deny with no readable scope: every request is denied with `reason_admin` `policy-store-invalid: <row id>` until the document is restored or the policy is disabled. Applies in observe mode too. |
| `LEGACY_RULE`, `policy_document` null or blank | Skips it, as before. It was never an access policy. |

The gauge and the per-refresh WARN count and name a blank row like any other invalid
deny (scope `unreadable`, parse error "the policy document is missing or blank").

Since v1.6 a database trigger (`trg_policies_kind_guard`, Flyway V11) keeps the kind
honest against a direct edit. It refuses a `LEGACY_RULE` row that holds a non-blank
`policy_document`, on insert and on update, and it refuses a change of `policy_kind`
from `DSL` to `LEGACY_RULE`. An edit that set `LEGACY_RULE` and blanked the document in
one statement used to hide an ENABLED deny from the PDP; it now fails with a
`check_violation`, and a `DSL` row whose document is blanked stays `DSL` and is an
invalid deny. A `LEGACY_RULE` row may still be updated with a document if the same
statement makes it `DSL`, which is what `updatePolicy` does.

#### A lifecycle policy states no effect (v1.6)

`spec.effect` belongs to `kind: AccessPolicy`. A `kind: Policy` document has no effect,
and the PDP keeps only AccessPolicy documents, so a lifecycle document that said
`effect: deny` parsed, looked like a deny and denied nothing. `POST` and `PUT
/api/v1/policyengine/policies` now refuse it with HTTP 400 ("spec.effect is not allowed
on kind Policy"); any stated effect is refused, `allow` and an empty text included, and
an explicit `effect: ~` counts as absent. A stored document of that shape (written before
v1.6, or by a direct edit) is logged at start-up with its row id and counted in
`policyengine.policies.invalid{check="access-policy"}`. Its behaviour is unchanged
(ADR-0136: ignored while it parses, judged by its effect when it does not).

The id is the document's `metadata.id` when it reads as non-blank text, else the
stored row id. When several denies apply, `reason_admin` lists
`policy-store-invalid`, then `policy-invalid`, then `denied by` (valid deny
policies), separated by `; `. Both kinds apply in observe mode.

The gauge `dlap.pdp.policies.invalid.deny{scope}` counts the invalid denies of the
latest refresh (`scope="readable"` or `"unreadable"`), and each refresh
(`dlap.pdp.refresh-interval-ms`, on start-up and after every policy change) logs
them, with the skipped allows, in one WARN line naming each id, row id, readable
scope and parse error. The start-up check (`policyengine.policies.invalid{check}`)
names the same consequence for each stored document, and since v1.4 also reports a
document whose `kind` is neither `Policy` nor `AccessPolicy` under
`check="unreadable"`, because the PDP treats it as a possible AccessPolicy. To
recover, fix the document through the API or disable the policy; the next refresh
picks the change up.

### Shadow mode

`dlap.policyengine.actions.enabled=false` (env `DLAP_POLICYENGINE_ACTIONS_ENABLED`)
publishes nothing. Evaluation, schedule cursors and evaluation records are
unchanged; each action that would have been published is logged at INFO (policy,
action type, asset, fire id) and counted in `policyengine.actions.shadowed{actionType}`.
Run new policies this way against a real estate and read what they would do before
any consumer acts. The default is `true`.

### spec.action

| Field | Required | Notes |
|---|---|---|
| `type` | yes | One of: `archive` / `delete` / `addLabel` / `removeLabel` / `alert.email` / `alert.slack` / `alert.webhook` / `setRetention` / `tagAsset` / `evaluateCompliance` / `runDiscovery`. v1.0 also accepts plain `alert` (defaults to `alert.email`). |
| `requireApproval` | no | Default `false`. If `true`, the evaluator publishes a normal event with `requireApproval: true`; archive and delete consumers do not execute it (Known gaps 2). |
| `dryRun` | no | Default `false`. If `true`, the evaluator publishes a normal event with `dryRun: true`; the lifecycle service logs intent without performing. |
| `parameters` | per-action | See below. |

#### `archive` parameters

| Field | Notes |
|---|---|
| `targetStorageClass` | `ARCHIVE` (GCS), `GLACIER` (S3), `ARCHIVE` (Azure Blob). |
| `auditNote` | Free-text reason captured in audit log. |

#### `delete` parameters

| Field | Notes |
|---|---|
| `softDeleteWindowDays` | Default 30. Asset is moved to soft-delete state; hard delete after window. |
| `auditNote` | Required for any `delete` action. |

#### `addLabel` / `removeLabel` parameters

| Field | Notes |
|---|---|
| `label` | Label name. Required. da-autolabel also reads `labelName`. |
| `category` | Optional label category. |

#### `alert.*` parameters

| Field | Notes |
|---|---|
| `recipients` | Provider-specific recipient list (email addresses for `alert.email`, channel ids for `alert.slack`, URLs for `alert.webhook`). |
| `severity` | `INFO` / `WARN` / `ERROR`. Default `WARN`. |
| `message` | Free-form template; supports `{{ asset.name }}` / `{{ asset.id }}` / `{{ policy.name }}` interpolation. |

#### `setRetention` parameters

da-catalog's `SetRetentionActionListener` consumes this action (CAT-03,
asset-descriptor.md "setRetention actions"). It keys the rule by (asset,
`policyDbId`) and orders a policy's actions by `firedAt`.

| Field | Notes |
|---|---|
| `retentionDays` | Integer, 0 to 365000. Required. The period, counted from the basis time. |
| `basis` | `CREATED`, `LAST_MODIFIED` or `LAST_ACCESSED`. Default `LAST_ACCESSED` (the later of the last read and the creation). |
| `mode` | `soft` (recommended; sets metadata only) or `hard` (overrides cloud-native retention if supported). Default `soft`. Kept with the rule. |

#### `tagAsset` parameters

| Field | Notes |
|---|---|
| `tags` | Map of key to value. Merged into `Asset.businessMetadataJson.tags`. |
| `replace` | If `true`, overwrites existing tags; default `false` (merge). |

#### `evaluateCompliance` parameters

| Field | Notes |
|---|---|
| `framework` | One of `GDPR` / `HIPAA` / `SOC2` / `PCI-DSS` / `CCPA`, the framework identifiers da-autocompliance carries on `ComplianceSignal.framework`. Required. |
| `controlIds` | Optional list of control ids (for example `GDPR-RESIDENCY-001`). Default: every control of the framework. |
| `auditNote` | Free-text reason captured in the audit log. |

> **Reconstructed 2026-09-17.** The v1.1 rewrite (`44b9d42`) was committed
> truncated at the `framework` row; the rows above are derived from the compliance
> code. Post-ADR-0035 the primary compliance path is a control policy that stamps a
> `compliance:<framework>:<status>` label through `addLabel`
> (`docs/specs/compliance-labels.md`).

#### `runDiscovery` parameters

| Field | Notes |
|---|---|
| `scope` | `asset` (default): rescan only the matched asset; `project`: rescan the asset's containing project, account or subscription. |
| `auditNote` | Free-text reason captured in the audit log. |

> **Reconstructed 2026-09-17**, same truncation. The expected transport is
> `dlap.discovery.scan.requested` (`DlapConstants.KafkaTopic.DISCOVERY_SCAN_REQUESTED`),
> which has no producer or consumer today.

## Wire format: `PolicyActionRequest` on `dlap.policies.actions`

The evaluator publishes one event per fire. This is the shape adopters who write
their own evaluators must produce.

- **Topic**: `dlap.policies.actions`, `DlapConstants.KafkaTopic.POLICY_ACTION_EVENT`.
  ADR-0021 and older text call it `dlap.policy.action.event`; that is the design
  name, the constant is what runs. Producer key `app.kafka.topic.policy-action-event`,
  consumer key `dlap.kafka.topics.policy-actions`, both set to the same value.
- **Encoding**: one JSON object per record, written as a string by
  `PolicyActionProducer` from the record `PolicyActionEvent`
  (`da-policyengine/src/main/java/com/dlap/policyengine/event/PolicyActionEvent.java`).
  Partition key is `assetId`. There is no protobuf mirror.
- **Producers**: every path of `da-policyengine` builds the event through
  `PolicyActionRequests` (`forEvent`, `forSchedule`, `forManual`): the event
  consumer `LifecyclePolicyConsumer`, the schedule `ScheduledPolicySweeper` and the
  on-demand `PolicyEvaluationService`. A test (`PolicyActionRequestSpecTest`) holds the
  example below to the fields an event fire writes.

```json
{
    "schemaVersion": "v1",
    "policyId": "archive-stale-pii",
    "policyDbId": "6c1e0d2a-0000-4000-8000-000000000001",
    "policyName": "Archive stale PII data",
    "fireId": "0d4f9c40-1a2b-3c4d-8e5f-000000000002",
    "firedAt": "2026-10-20T02:00:00Z",
    "triggerType": "event",
    "triggerLifecycle": "accessed",
    "sourceEventId": "GCP_CLOUD_AUDIT|abc123",
    "assetId": "1f2d3a4b-0000-4000-8000-000000000001",
    "assetUri": "gs://prod-customer-exports",
    "provider": "GCP",
    "serviceType": "GCS_BUCKET",
    "actionType": "archive",
    "actionParameters": {
        "targetStorageClass": "ARCHIVE",
        "auditNote": "Stale PII auto-archive per policy archive-stale-pii"
    },
    "requireApproval": false,
    "dryRun": false,
    "matchSnapshot": {
        "assetId": "1f2d3a4b-0000-4000-8000-000000000001",
        "assetUri": "gs://prod-customer-exports",
        "cloudProvider": "GCP",
        "serviceType": "GCS_BUCKET",
        "assetType": "GCS_BUCKET",
        "labels": ["PII"],
        "lastAccessedAt": "2025-12-15T08:42:00Z",
        "readCoverage": "FULL"
    }
}
```

### Fields

| Field | Type | Set to | Read by |
|---|---|---|---|
| `schemaVersion` | string | Constant `v1`. | - |
| `policyId` | string | `metadata.id` of the policy document. On the on-demand REST path the policy row UUID, as before. | da-autodelete and da-autolabel store it; da-reporting records it; da-catalog uses it when `policyDbId` is absent and it is a UUID. |
| `policyDbId` | string (UUID) | The policy row's id. | da-catalog keys `setRetention` rules by it. |
| `policyName` | string | `metadata.name` (the row name on the REST path). | da-autolabel (labeling-job record). |
| `fireId` | string (UUID) | Name-based UUID of the trigger instance: (policy, inbound `eventId`, asset) for an event fire, (policy, slot, asset) for a schedule fire, (evaluation, action type) for a REST fire; random only for an event without an `eventId`. A redelivered or re-run fire carries the same id. | da-autodelete stores it as `policyFireId`. |
| `firedAt` | string (ISO-8601 instant) | The decision instant: the engine clock for event and REST fires, the sweep's as-of instant for a schedule fire. | da-catalog orders `setRetention` actions by it. |
| `triggerType` | string | `event`, `schedule` or `manual` (on-demand REST evaluation). | - |
| `triggerLifecycle` | string / null | `created` / `modified` / `deleted` / `accessed` / `permission-changed` / `archived` for an event fire; null otherwise. | - |
| `scheduledFor` | string (ISO-8601 instant) | Schedule fires only: the cron slot. Omitted otherwise. | - |
| `sourceEventId` | string | Event fires only, when the inbound event has an `eventId`. Omitted otherwise. | - |
| `evaluationId` | string (UUID) | REST fires only: the evaluation record. Omitted otherwise. | da-reporting. |
| `assetId` | string (UUID) | The catalog asset id. Never null on a published action: an event whose asset neither carries a catalog id nor resolves through `GET /api/v1/catalog/assets/resolve` publishes nothing (`policyengine.actions.skipped{reason=unresolved-asset}`). | Every consumer; archive and delete fail without it. |
| `assetUri` | string / null | The canonical asset URI (ADR-0047). Null when unknown. | da-autoarchival and da-autodelete locate the asset by it (also read from `matchSnapshot.assetUri`); da-catalog falls back to it. |
| `provider` | string / null | The asset's cloud provider (`GCP`, `AWS`, `AZURE`, ...). Null when unknown. | - (consumers read `matchSnapshot.cloudProvider` today) |
| `serviceType` | string / null | The asset kind (`GCS_BUCKET`, `BIGQUERY_TABLE`, ...). Null when unknown. | - |
| `actionType` | string | `spec.action.type` verbatim, or the legacy actions-map key on the REST path. | Consumers match case-insensitively: archive = contains `ARCHIVE`; delete = `DELETE` or `AUTO_DELETE`; addLabel = `ADDLABEL` / `ADD_LABEL`; setRetention compared without case, dots, underscores or spaces. da-reporting records every value. |
| `actionParameters` | object | `spec.action.parameters` verbatim (the map value on the REST path); `{}` when absent. | delete: `softDeleteWindowDays`, `auditNote`. addLabel: `labelName`, else `label`. setRetention: `retentionDays`, `basis`, `mode`. archive: nothing. |
| `requireApproval` | boolean | `spec.action.requireApproval` (on every path, the REST path included). | archive and delete acknowledge and skip when `true`; da-catalog records nothing. addLabel ignores it. |
| `dryRun` | boolean | `spec.action.dryRun` (on every path). | archive and delete log intent and skip; da-catalog records nothing. addLabel ignores it. |
| `matchSnapshot` | object | The snapshot the match ran against (keys of section "spec.match", plus `assetId`, `assetUri`, `serviceType`, `status`, `legalHold`, `retentionStatus` when known). On the event path: the inbound `matchSnapshot`, or the log-spine `targetResource` (`canonicalUri` as `assetUri`, `sourceProvider` as `cloudProvider`, `kind` as `assetType`, `name`, `region`, `createdTime`, `sizeBytes`, `labels` as `tags`), completed from the catalog for every key the event does not carry. | archive resolves the asset from `assetUri` + `cloudProvider` here before falling back to da-catalog; delete stores `cloudProvider` and `assetUri`. |

The core fields are always written, as null when unknown; `scheduledFor`,
`sourceEventId` and `evaluationId` only when set.

### Other producers on the same topic

| Producer | Fields | Notes |
|---|---|---|
| `CatalogEventComplianceLabelSink` (da-autocompliance) | `assetId`, `actionType: addLabel`, `source: compliance`, `detail`, `actionParameters.labelName` | ADR-0035 compliance labels ride the `addLabel` pipeline. Consumers must accept this subset. |

### Consumer rules

Every consumer of this topic:

- Subscribes via its service's `MANUAL_ACK_FACTORY` (manual acknowledgment,
  DLQ on exhausted retries).
- Filters on `actionType` and acknowledges anything it does not own, so an
  unconsumed action is never an error.
- Acknowledges only after the action is durably queued or completed, and
  re-throws on failure so the error handler retries and then dead-letters.
- Honours `dryRun: true` by logging intent without calling the cloud, and
  `requireApproval: true` by not executing.
- Treats a repeated `fireId` as the same decision.
- Writes its own audit record (da-autodelete's soft-delete record,
  da-autoarchival's archival task, da-autolabel's labeling job, da-catalog's
  retention rule). da-reporting additionally records every event, whatever the
  `actionType`, in `dlap_reporting.policy_action_events`.

### Known gaps (verified against code, 2026-10-20)

1. **Resolved by POL-01: `assetId` on the log-spine path.** The typed lifecycle
   topics carry the normalised `DlapLogEvent` JSON, which names the asset by
   `targetResource.canonicalUri` and has no `assetId`. The event consumer now
   resolves the catalog id through `GET /api/v1/catalog/assets/resolve`, completes
   the snapshot from the catalog, and publishes nothing for an asset it cannot
   identify.
2. **No approval bridge.** v1.0 said a `requireApproval` fire publishes a
   `PENDING_APPROVAL` event; the evaluator publishes a normal event with
   `requireApproval: true`, and nothing re-publishes an approved action.
   An approval-gated `archive` or `delete` never executes (APPR-01).
3. **`addLabel` ignores the safety flags.** `PolicyActionLabelConsumer` reads
   neither `dryRun` nor `requireApproval`; a dry-run `addLabel` applies the label.

## Versioning

This is policy-DSL v1. Backward-compatible additions (new clauses, new action
types, new action parameters, new event fields) are allowed within v1 and bump
the minor (v1.0 to v1.1 to v1.2). Backward-incompatible changes (rename, remove,
re-type) bump to v2 in a side-by-side package.

## Open standard track

External adopters writing their own policy evaluators against DLAP are expected
to produce events that match the wire format above. The reference evaluator in
`da-policyengine` ships under Apache 2.0 and is the canonical implementation.

---

## Drafting from natural language

`POST /api/v1/policyengine/policies/draft-from-text` turns a plain-English
request into a `dlap/v1` document (ADR-0044).

**It is a draft, and only a draft.** The endpoint persists nothing and
publishes nothing. The UI renders the result in the visual builder so a
human inspects and corrects it; creating the policy still goes through
`POST /api/v1/policyengine/policies`.

Request:

```json
{ "text": "Archive GCS buckets tagged PII that nobody has touched in 90 days", "owner": "governance@example.com" }
```

Response (abridged):

```
{
  "dslYaml": "apiVersion: dlap/v1\n...",
  "structured": { "apiVersion": "dlap/v1", "kind": "Policy", "metadata": {...}, "spec": {...} },
  "confidence": 0.88,
  "assumptions": ["Read 'nobody has touched' as lastAccessedAt olderThan P90D."],
  "warnings": ["Draft only: nothing was saved. Review every clause before creating the policy."]
}
```

### Contract

| Rule | Behaviour |
|---|---|
| Grammar is closed | Only the match fields, operators, action types and trigger types the drafting service lists are accepted. Anything else is HTTP `422` with `warnings` and **no** `dslYaml`. The drafting grammar does not yet offer `createdAt`. |
| No invented defaults | A missing or unsupported clause is reported, never back-filled with a guess, matching the translator, for which an absent `spec.action.type` is a hard error. |
| Destructive drafts are disarmed | A drafted `delete` or `archive` always returns `dryRun: true` and `requireApproval: true`, whatever the model proposed. Clearing either is a deliberate human edit. |
| Drafts are inert | Every draft carries `metadata.enabled: false`, so an unreviewed document cannot fire even if applied verbatim. |
| No provider, no policy | With no LLM provider configured the endpoint returns HTTP `503` naming the property to set. It never returns a fabricated policy. The `MOCK` provider counts as unconfigured. |

Authorization: `ROLE_ADMIN` or `ROLE_POLICY_MANAGER`. `text` is required,
4000 characters maximum.

### Configuring a provider

| Property | Notes |
|---|---|
| `llm.provider` | `openai` or `ollama`. Blank (the default) disables drafting. |
| `llm.openai.api-key` | Required for `openai`. Supplied via `OPENAI_API_KEY`; never logged. |
| `llm.openai.model` | Default `gpt-3.5-turbo`. |
| `llm.openai.max-tokens` / `llm.openai.temperature` | Completion bounds. |
| `llm.ollama.base-url` | Default `http://localhost:11434`. |
| `llm.ollama.model` | Required for `ollama`. Default `llama2`. |
| `llm.ollama.timeout` | Request timeout in milliseconds. |

---

## Implementation status (as of 2026-10-20)

This document specifies the full v1.2 surface. Only some of it has a live
consumer today. Everything else parses and validates but nothing acts on it.

| Action | Live consumer | Where |
|---|---|---|
| `archive` | **yes** | `da-autoarchival` `PolicyActionEventListener` to `ArchivalTaskServiceImpl.processArchivalAction`. Matches any `actionType` containing `ARCHIVE`. |
| `delete` | **yes** | `da-autodelete` `PolicyActionEventListener` to `DeletionServiceImpl.queueSoftDelete`. Matches `DELETE` / `AUTO_DELETE`. |
| `addLabel` | **yes** | `da-autolabel` `PolicyActionLabelConsumer` to catalog label POST. |
| `setRetention` | **yes** | `da-catalog` `SetRetentionActionListener` (CAT-03): stores a `POLICY` retention rule and re-evaluates the asset. |
| `removeLabel` | specified only | No consumer filters for it. |
| `alert.email` / `alert.slack` / `alert.webhook` / `alert` | specified only | Events are published and recorded by `da-reporting`'s `PolicyActionConsumer`, but no notifier sends anything. |
| `tagAsset` | specified only | - |
| `evaluateCompliance` | specified only | - |
| `runDiscovery` | specified only | - |

Unconsumed action types are not errors: each lifecycle consumer filters on
`actionType` and acknowledges anything it does not own, so a policy using them
fires and is recorded without any effect on the asset.

### Triggers

- `type: event` (and the `lifecycle:` shorthand) is **live**:
  `LifecyclePolicyConsumer` evaluates policies against the six typed lifecycle
  topics.
- `type: schedule` with `cron` is **live** (POL-01): `ScheduledPolicySweeper` fires
  each slot against the catalog activity report (section "Schedule triggers").
