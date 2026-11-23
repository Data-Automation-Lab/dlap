# DLAP Asset Descriptor (spec)

> v1.3. Normative. The canonical, cloud-neutral representation of a data asset in
> the DLAP Open Standard. Every other spec (Label, Policy DSL, Access Model) and
> every lifecycle stage operates on this descriptor. Owned by `da-protos`
> (`com.dlap.common.model.entity.Asset`), the single source of truth, never
> duplicated in a service. Decisions: ADR-0046 (granularity, time, size and
> retention), ADR-0047 (canonical asset IDs, [asset-id.md](asset-id.md)), ADR-0055
> (catalog event ingest: idempotency, event time, matching). The retention sweep,
> its rule store and its API are specified under "Retention in the catalog (CAT-03)".

## Principle

An Asset Descriptor is **provider-neutral**: a GCS bucket, a BigQuery table, an S3
bucket, and a Unity Catalog table are all Assets with the same shape. Provider
specifics live in `technicalMetadata`/`detailsJson`, never in the core fields. The
descriptor is **log-enriched**: its owner, lineage, usage and activity times are
reconstructed from audit/application logs, not read from the tool that emits them.

The field names below are the Java property names and the asset API names. The
`dlap_common.dlap_assets` column is the snake_case form (`lastAccessedAt` is
`last_accessed_at`).

## Granularity (ADR-0046)

1. **Assets are containers, datasets, tables and jobs**: buckets, storage accounts and
   blob containers, BigQuery datasets and tables, Glue databases and tables, Unity
   Catalog catalogs, schemas, tables and volumes, Snowflake tables, database instances,
   and processing jobs.
2. **Objects get a row only under a governed prefix.** An object kind (`S3_OBJECT`,
   `GCS_OBJECT`, `BLOB_OBJECT`) has its own row only when the object lies under a
   bucket-relative key prefix that an operator has declared governed in DLAP
   configuration (`dlap.catalog.ingest.governed-prefixes`, a list of canonical
   object-URI prefixes such as `gs://finance/pii/`; empty by default, so no object rows).
   Everywhere else an object event updates its container.
3. **Object events roll up.** The container's activity times are the maxima over its
   objects' events (`CanonicalAssetId.rollUp()`, asset-id.md section 10). Under a
   governed prefix the event updates the object row and also rolls up. Size never rolls
   up from events: a container's size comes only from a container-level observation
   (inventory report, storage metrics, crawler).
4. **Unknown targets change nothing.** An event whose rolled-up target matches no row
   is counted in `dlap_catalog_unmatched_target_total{provider,kind,category}` and is
   kept in da-logingest's event store. Activity events (reads, writes, permission
   changes) never create rows, because a binding's log can name another account's
   bucket or a public dataset. Rows come from crawlers, from CREATE events in a
   binding's own log, and from operators. When a row appears later, a replay of the
   stored events (`dlap-ingest-mode=REPLAY`) applies the missed activity; per-consumer
   dedup makes the replay safe (log-event.md section 5).

## Identity

| Field | Type | Required | Notes |
|---|---|---|---|
| `assetId` | UUID | yes | DLAP-internal primary key. |
| `assetUri` | string(2048) | yes | **Unique, canonical cross-cloud identifier**: the join key for lineage, labels, policy, events. The canonical URI from `AssetIdNormalizer` (ADR-0047), for example `gs://bucket`, `bq://project/dataset/table`, `uc://metastore/catalog/schema/table`. Since CAT-02a the catalog writes the canonical URI for every row it creates. Rows written earlier may hold older forms; the catalog matches them on `normalize(stored assetUri)` (Catalog event ingest, Matching) until ID-03 re-keys them. |
| `nativeAssetId` | string(500) | no | The provider-native id/ARN/full-name, as first seen: the event's raw `targetResource.id` when the catalog created the row. |
| `cloudProvider` | string(20) | yes | Closed set (`ck_asset_cloud_provider_valid`): `AWS`, `GCP`, `AZURE`, `OCI`, `UNITY`, `DATABRICKS`, `SNOWFLAKE`. |
| `serviceType` | string(50) | yes | The asset kind, an `AssetKind` name (ADR-0047). Closed set (`ck_asset_service_type_valid`): the 22 kinds that predate ADR-0046 plus `S3_OBJECT`, `GLUE_DATABASE`, `GLUE_TABLE`, `GCS_OBJECT`, `BLOB_CONTAINER`, `BLOB_OBJECT`, `UNITY_VOLUME`, `SNOWFLAKE_TABLE`. |
| `assetType` | string(50) | no | Free classification. The Policy DSL `assetType` clause matches this field; producers should set it to the kind. Seed data still carries coarse values (`BUCKET`, `TABLE`). |
| `region` | string(50) | no | Normalized region. |
| `cloudConnectionId` | UUID | no | The server-side connection the asset was discovered through. |

v1.0 of this spec called `serviceType` a provider service (`storage`, `s3`) and
`assetType` the normalized kind. The schema and the code hold the kind in
`serviceType` (`02-create-common-schema.sql`, `DiscoveryAssetDiscoveredListener.java:133-211`);
v1.1 follows the code.

## Descriptive and governance

| Field | Type | Notes |
|---|---|---|
| `assetName` | string(255) | Human/display name. |
| `description` | text | Business description. |
| `ownerInfo` | text | Resolved owner (email / team). Harvested from logs (see below); no longer "Unknown". |
| `status` | string(20) | Lifecycle status: `ACTIVE`, `ARCHIVED`, `PENDING_DELETION`, `DELETED` (indexed). Transitions follow the lifecycle ordering rule below. |
| **Labels** | n/a | Applied via `AssetLabelAssignment` (see the Label spec). The Policy DSL matches `labels`. |

## JSON metadata bags (schema-on-read)

| Field | Type | Notes |
|---|---|---|
| `businessMetadataJson` | jsonb | Business context. Carries a nested **`harvested`** sub-object mined from log events (see below), alongside any manually-entered keys. |
| `technicalMetadata` | jsonb | Schema, format, storage class, provider-native holds and other provider-specific detail. Creation time and size have first-class fields (below). |
| `detailsJson` | jsonb | Additional provider details. |
| `dlapUsageMetrics` | jsonb | **Deprecated** legacy bag. Before CAT-02a its `lastAccessedAt` key held DLAP processing time and `accessCount` counted redeliveries. Since CAT-02a the catalog writes only event-time values into it, for the asset API, which still reads it: each successful read sets `lastAccessedAt` to the `lastAccessedAt` field and adds one to `accessCount` per deduplicated recorded read (a row from before CAT-02a keeps its earlier count as the base; a new incarnation resets it to 0, and a read older than `recreatedAt` does not add to it). CAT-02b moves the readers to the field and to `asset_usage_daily`; the keys are then dropped. |
| `dlapPolicyStatus` | jsonb | Which policies currently apply and their outcome. |

### Harvested business metadata (log-enriched)

On each normalized log event that succeeded (or is a crawler observation), DLAP
harvests governance context from the event's `principal` and `targetResource` into
`businessMetadataJson.harvested` of the event's own target row (never a container an
object rolls up to, and never overwriting manually-entered keys). The harvested values
are those of the latest event by event time: an older event arriving later changes
nothing. Each event is harvested once per row (processed-event consumer
`catalog-enrichment`).

| Key | Source | Example |
|---|---|---|
| `harvestedOwner` | `principal.displayName`/`id` | `alice@data-eng.co` |
| `harvestedOwnerType` | `principal.type` | `USER` / `SERVICE_ACCOUNT` |
| `harvestedTeam` | inferred from the owner's email/account | `data-eng` |
| `harvestedRegion` | `targetResource.region` | `us-central1` |
| `harvestedProvider` | `sourceProvider` | `GCP` |
| `harvestedAccount` | `principal.accountId` / `targetResource.project` | `proj-123` |
| `harvestedAt` | DLAP processing time (operational) | ISO-8601 |
| `harvestedEventTime` | `event_time` of the event the values come from | ISO-8601 |

## Time, size and activity (ADR-0046)

### Rules

1. **Business time.** Every field in this section except `retentionEvaluatedAt` comes
   from provider evidence: an event's `event_time`, or a resource time the provider
   reports (`targetResource.created_time`, an inventory date). Never ingest time, never a
   consumer's wall clock ([log-event.md](log-event.md) section 3).
2. **Unknown is null.** A null time or size is unknown, and unknown never reads as stale
   or as fresh. `0` is a real size.
3. **Monotonic.** `last*` fields take the maximum event time seen, `cloudCreatedAt` the
   minimum. A late event never moves a value backwards. Only `SUCCESS` events count.
4. **Lifecycle ordering.** A status transition (`ACTIVE`, `ARCHIVED`, `DELETED`) applies
   only when its `event_time >= lifecycleEventTime` (null counts as never). An older
   transition updates only the time fields.
5. **Incarnation boundary.** A CREATE applied to a `DELETED` row (bucket or table name
   reuse) starts a new incarnation: status `ACTIVE`, `recreatedAt` and `cloudCreatedAt`
   set to the CREATE's event time, and the activity maxima, size and retention
   evaluation cleared. Evidence older than `recreatedAt` never changes the row again, so
   the old incarnation's reads cannot make the new one look idle.

### Fields

| Field | Type | Meaning | Written by |
|---|---|---|---|
| `cloudCreatedAt` | timestamptz | Provider creation time of the current incarnation: the least value known from crawler `createdAt` (forwarded as `targetResource.created_time`) and CREATE events. | DISC-02, CAT-02 |
| `cloudCreatedSource` | string(64) | Evidence channel for `cloudCreatedAt`. | DISC-02, CAT-02 |
| `sizeBytes` | int64, >= 0 | Latest observed size in bytes. | DISC-02, CAT-02 |
| `sizeObservedAt` | timestamptz | Business time at which `sizeBytes` was true. A newer observation replaces an older one; an older one is ignored. | DISC-02, CAT-02 |
| `lastAccessedAt` | timestamptz | Maximum event time of a successful `DATA_READ` or `DATA_READ_OBSERVED` event, after roll-up. | CAT-02 |
| `lastAccessSource` | string(64) | Evidence channel for `lastAccessedAt`. | CAT-02 |
| `lastModifiedAt` | timestamptz | Maximum event time of a successful `DATA_WRITE` event, or a provider-reported content modification time. Not `lastModifiedTs`, the DLAP row time. | CAT-02, DISC-02 |
| `lastPermissionChangeAt` | timestamptz | Maximum event time of a successful `PERMISSION` event. | CAT-02 |
| `lifecycleEventTime` | timestamptz | Event time of the last status transition applied. | CAT-02 |
| `recreatedAt` | timestamptz | Event time of a CREATE applied to a `DELETED` row. | CAT-02 |
| `discoverySource` | string(64) | Channel through which DLAP first learned of the asset. | CAT-02, DISC-02 |
| `discoveredAt` / `dlapDiscoveredTs` | timestamptz | First seen by DLAP. Unchanged by ADR-0046. | catalog |
| `lastScannedAt` / `dlapLastScannedTs` | timestamptz | Last reconciled by DLAP. | catalog |
| `createdByUserId` | UUID | The DLAP actor that created the record, distinct from the asset's real-world owner. | catalog |

**Evidence channels** (`cloudCreatedSource`, `lastAccessSource`, `discoverySource`):
`<CHANNEL>` or `<CHANNEL>:<SOURCE_KIND>`, where `SOURCE_KIND` is the first part of the
event's `source_format` (log-event.md), for example `AUDIT_LOG:GCP_CLOUD_AUDIT`.

| Channel | Evidence |
|---|---|
| `CRAWLER` | A metadata API read by a da-discovery crawler |
| `AUDIT_LOG` | A provider audit-log record |
| `INVENTORY` | A provider inventory report (S3 Inventory, Azure blob inventory) |
| `PROVIDER_METADATA` | Provider-maintained state, such as a last-access time |
| `USAGE_QUERY` | A warehouse usage view (Databricks system tables, Snowflake ACCOUNT_USAGE) |
| `MANUAL` | An operator through the API |

The vocabulary is open: readers accept values they do not know.

## Retention (ADR-0046)

### Rule

A retention rule is a period in days counted from a basis time, or, with basis
`FIXED_DATE`, an expiry instant. Several rules can apply to one asset.

| `RetentionBasis` | Basis time |
|---|---|
| `CREATED` | `cloudCreatedAt` |
| `LAST_MODIFIED` | `lastModifiedAt` |
| `LAST_ACCESSED` | the later of the last read and the creation (an asset never read has been idle since it was created) |
| `FIXED_DATE` | none: the rule carries its expiry instant (a BigQuery table expiration time, an operator's end date) |

**Rules are kept at their source**, and the evaluator (CAT-03) reads them there on
every run:

| Source | Where the rule is kept | Bases |
|---|---|---|
| `POLICY` | The policy's `setRetention` action (Policy DSL). CAT-03's `setRetention` listener records each action it receives in the catalog's retention rule store, keyed by asset and policy id. | day-based |
| `LABEL_DEFAULT` | A configured default per label (CAT-03 configuration). | day-based |
| `PROVIDER` | A provider-native setting that a crawler recorded in `technicalMetadata`: a GCS bucket retention period is `CREATED` plus days; a BigQuery table expiration time is `FIXED_DATE`. | any |
| `MANUAL` | The catalog's retention rule store, a table in da-catalog's own schema (CAT-03), written through the API by an operator with a named role and audited. | any |

**The rule fields on the asset are evaluation output.** They name the rule that
decided the last evaluation (Evaluation, step 3), and the evaluator rewrites them on
every run. Nothing writes a rule into them, and the evaluator never reads them back as
input. So an evaluation, a hold or a release cannot overwrite or lose a rule, and an
operator's `MANUAL` rule survives a run in which a `POLICY` rule decides.

| Field | Type | Meaning |
|---|---|---|
| `retentionDays` | int32, >= 0 | Period in days of the deciding rule. Null for `FIXED_DATE`. |
| `retentionBasis` | enum `RetentionBasis` | Basis of the deciding rule. |
| `retentionSource` | string(64) | Source of the deciding rule: `POLICY`, `LABEL_DEFAULT`, `PROVIDER` or `MANUAL`. |
| `retentionPolicyId` | UUID | The policy whose `setRetention` action is the deciding rule, when the source is `POLICY`. Not a foreign key: policies live in da-policyengine's database. |

### Evaluation

The evaluator (CAT-03) runs with an injected `Clock`; `asOf` is its current instant.

1. **Bounds.** For each rule it bounds the true expiry from the evidence,
   `lo <= expiry <= hi`, where `expiry = basis time + the rule's days` (for `FIXED_DATE`,
   the rule's expiry):

   | Basis | `lo`: the basis is no earlier than | `hi`: the basis is no later than |
   |---|---|---|
   | `CREATED` | `cloudCreatedAt` | `cloudCreatedAt`, else `discoveredAt` (the asset existed when DLAP first saw it) |
   | `LAST_MODIFIED` | `lastModifiedAt` | `lastModifiedAt` when its channel is provider state (`CRAWLER`, `INVENTORY`, `PROVIDER_METADATA`); otherwise unbounded, since an unlogged later write cannot be ruled out |
   | `LAST_ACCESSED` | the later of `lastAccessedAt` and `cloudCreatedAt` | when reads are captured for the asset since instant `S` (binding `READ_COVERAGE_CAPTURED`, log-event.md section 10; per-asset coverage from COV-01): the latest of `lastAccessedAt`, `S` and the `CREATED` upper bound. Otherwise unbounded. |
   | `FIXED_DATE` | the rule's expiry | the rule's expiry |

   A null value gives no bound.
2. **Status of one rule.** `DUE` when `hi <= asOf`; `RETAINED` when `lo > asOf`;
   otherwise `UNKNOWN`. So missing evidence never proves an asset stale or fresh.
3. **Several rules.** The asset is `DUE` only when every rule is `DUE`, `RETAINED` when
   any rule is `RETAINED`, otherwise `UNKNOWN`. No rule's period is cut short. The
   evaluator writes the deciding rule to `retentionDays`, `retentionBasis`,
   `retentionSource` and `retentionPolicyId`: for `DUE` the rule with the latest `hi`;
   for `RETAINED` the retaining rule with the latest `lo`; for `UNKNOWN` an undecided
   rule; for `NO_POLICY` it clears all four. Ties go `MANUAL`, then `POLICY`,
   `LABEL_DEFAULT`, `PROVIDER`.
4. **Asset status** (`retentionStatus`, enum `RetentionStatus`, never null, default
   `UNKNOWN`):

   | Status | When |
   |---|---|
   | `ON_HOLD` | `legalHold` is true. Overrides everything below. The evaluator still evaluates the rules and records the deciding rule and the expiry they give, for information. |
   | `NO_POLICY` | Evaluated; no rule applies. |
   | `DUE` | Evaluated; every rule is proven expired. |
   | `RETAINED` | Evaluated; a rule is proven unexpired. |
   | `UNKNOWN` | Not evaluated yet, or the evidence proves neither. |

5. **Stored results.** `retentionExpiresAt` is the expiry the last evaluation found.
   It is exact when `lo = hi`, which includes a `FIXED_DATE` rule's own date, copied
   from the rule's source; for `DUE` the latest possible expiry ("due no later than");
   for `RETAINED` the earliest possible expiry ("retained at least until"); null for
   `UNKNOWN` and `NO_POLICY`; under `ON_HOLD`, what the rules alone give (null when
   they prove neither), for information only. It is output only: the evaluator never
   reads it back, so clearing it loses no rule. `retentionEvaluatedAt` is `asOf`, the
   only non-business time in the descriptor.

### Legal hold

- `legalHold` (boolean, never null, default false) is DLAP's hold. Only an explicit
  action by a named role sets or releases it, and the action is audited.
- While held, `retentionStatus` is `ON_HOLD` (CHECK `ck_asset_legal_hold_status`).
  `Asset.placeLegalHold()` sets both at once and keeps the last result for
  information. `Asset.releaseLegalHold()` returns the status to `UNKNOWN` and clears
  `retentionExpiresAt` and `retentionEvaluatedAt` until the next evaluation. Neither
  touches an evidence field, and neither can lose a rule, because rules are kept at
  their source. Writers change the hold only through these two methods; the plain
  `legalHold` setter does not adjust the status.
- Provider-native holds (S3 Object Lock legal hold, GCS object holds, Azure immutability
  policies) are recorded by crawlers in `technicalMetadata`; retire executors check them
  at the provider and fail closed. A `LEGAL_HOLD` label is advisory unless
  `dlap.catalog.retention.legal-hold-label` names it: then the asset is `ON_HOLD` (reason
  `HOLD_LABEL`) while the label is assigned, and the `legalHold` flag itself still changes only
  through the API.
- **Retire gate.** Retention lets an archive or delete proceed only when
  `Asset.retentionPermitsRetire()` is true: status `DUE` and no hold. Executors re-read
  the row before acting, because a hold can arrive after the last evaluation. Approval
  and fail-closed dispatch rules apply on top.

### Retention-due topic

| Item | Rule |
|---|---|
| Topic | `dlap.catalog.asset.retention-due` (`DlapConstants.KafkaTopic.CATALOG_ASSET_RETENTION_DUE`) |
| Key | `assetUri` |
| Value | JSON object, UTF-8, camelCase keys, null fields omitted |
| Producer | da-catalog retention sweep (CAT-03), once per change of `retentionStatus`, after the change commits |
| Delivery | At least once; consumers dedupe on `eventId` |

| Key | Type | Meaning |
|---|---|---|
| `schemaVersion` | string | `"v1"` |
| `eventId` | string | Lowercase hex SHA-256 of `assetId\|previousStatus\|status\|evaluatedAt` (ISO-8601 instant), so a resent transition keeps its id |
| `assetId` | string (UUID) | |
| `assetUri` | string | Canonical URI |
| `cloudProvider` | string | |
| `serviceType` | string | Asset kind |
| `previousStatus` | string | `RetentionStatus` name before the change |
| `status` | string | `RetentionStatus` name after the change |
| `statusReason` | string | Optional, open vocabulary, for example `EXPIRY_PASSED`, `BASIS_UNKNOWN`, `READ_COVERAGE_NOT_CAPTURED`, `HOLD_PLACED` |
| `retentionDays` | integer | Deciding rule |
| `retentionBasis` | string | Deciding rule |
| `retentionSource` | string | Deciding rule |
| `retentionPolicyId` | string (UUID) | Deciding rule, when the source is `POLICY` |
| `expiresAt` | string (ISO-8601) | `retentionExpiresAt` |
| `legalHold` | boolean | |
| `evaluatedAt` | string (ISO-8601) | `retentionEvaluatedAt` |

Consumers act only on `status = "DUE"` with `legalHold = false`, and re-read the asset
before any retire action.

## Retention in the catalog (CAT-03)

How da-catalog implements the retention model above. The code is
`da-catalog/src/main/java/com/dlap/catalog/retention/`; the tables are in da-catalog's
Flyway `V4__shedlock_and_read_signal.sql`. The calculator (`RetentionCalculator`) is a pure
function of the as-of instant, the evidence columns, the rules and the read coverage; the
sweep and the API call it through `RetentionEvaluator`.

### Rule sources as implemented

| Source | Kept in | Read by the evaluator from |
|---|---|---|
| `POLICY` | `dlap_catalog.retention_rule`, one row per (asset, policy) | the table, on every run |
| `MANUAL` | `dlap_catalog.retention_rule`, any number per asset (at most 20) | the table, on every run |
| `LABEL_DEFAULT` | `dlap.catalog.retention.label-defaults` | the configuration |
| `PROVIDER` | `dlap_assets.technical_metadata` | the two keys below |

**Provider rules.** A crawler records a provider setting under these keys of
`technical_metadata`. No crawler writes them yet, so until one does the source is empty.

| Key | Type | Rule |
|---|---|---|
| `retentionPeriodDays` | whole number, 0 to 365000 | basis `CREATED`, that many days (a GCS bucket retention period, rounded up to days) |
| `expirationTime` | ISO-8601 instant | basis `FIXED_DATE` (a BigQuery table expiration time) |

A key that is present but cannot be read does not disappear: the asset can then never be `DUE` or
`NO_POLICY` (it is `UNKNOWN`, reason `PROVIDER_RULE_UNREADABLE`), because a rule the provider
enforces may exist. `RETAINED` is unaffected.

**`setRetention` actions.** `SetRetentionActionListener` reads `dlap.policies.actions` under the
consumer group `da-catalog-retention-actions` and acts only on `actionType = setRetention`
(compared without case, dots, underscores or spaces). Fields it reads:

| Field | Meaning |
|---|---|
| `assetId` or `targetAssetId` | the asset (UUID); `assetUri` is used when neither is usable |
| `policyDbId`, else `policyId` when it is a UUID | the policy; the rule is keyed by (asset, policy) |
| `actionParameters.retentionDays` | the period, required, 0 to 365000 |
| `actionParameters.basis` | `CREATED`, `LAST_MODIFIED` or `LAST_ACCESSED`; default `LAST_ACCESSED`, as the Policy DSL says "from `lastAccessedAt`" |
| `actionParameters.mode` | `soft` or `hard`, kept with the rule |
| `firedAt` | orders the actions of one policy: a later one replaces the rule, an earlier one is ignored |
| `dryRun`, `requireApproval` | when true nothing is recorded |

An action for an asset the catalog does not know changes nothing and is counted. An action that
cannot be understood goes through the error handler to `dlap.policies.actions.DLT`. After the rule is
stored the asset is evaluated in the same transaction. A policy that is deleted or disabled sends no
event, so its rules stay until an operator removes them.

### Read coverage

`dlap.logingest.binding.status` is read by `BindingStatusListener` (group
`da-catalog-binding-status`) into `dlap_catalog.log_binding_status`, the latest status per
binding; a status reported earlier than the stored one is ignored and a tombstone removes the
binding. An asset's reads are captured when **every enabled binding of its provider and scope
reports `READ_COVERAGE_CAPTURED`** with a start and a low watermark. The scope is the GCP
project, AWS account, Azure subscription, Databricks workspace or Snowflake account, taken from the
asset's canonical URI (`bq://project/...`, an ARN, an ARM id) or from `technical_metadata`
(`projectId`, `accountId`, `subscriptionId`, `workspaceId`). The coverage starts at the latest
start among the bindings. An asset that cannot be placed in a scope has no coverage. This
binding signal is now one half of the read signal: COV-01's `AssetReadCoverageSource` also
requires the asset's own `readCoverage` to be `FULL` (see "Read coverage (COV-01)").

**Refinement of the bounds table.** The upper bound of a `LAST_ACCESSED` rule holds for reads that
reached the log store, and capture is complete only up to the bindings' earliest low watermark `V`.
A `LAST_ACCESSED` rule is therefore `DUE` only when its upper-bound expiry is at or before
`min(asOf, V)`; an expiry between `V` and `asOf` is `UNKNOWN` with reason `READ_COVERAGE_STALE`.
This never makes a rule due that the table above would call unknown. The `LAST_MODIFIED` upper
bound applies only to a value from provider state; the catalog records `lastModifiedAt` from
audit-log write events only and keeps no channel for it, so a `LAST_MODIFIED` rule has no upper
bound and is never `DUE`.

### Status reasons

`statusReason` of the retention-due event is an open vocabulary. The catalog writes:

| Reason | Status | Meaning |
|---|---|---|
| `EXPIRY_PASSED` | `DUE` | every rule is proven expired |
| `NOT_EXPIRED` | `RETAINED` | a rule is proven unexpired |
| `NO_RULE` | `NO_POLICY` | no rule applies |
| `BASIS_UNKNOWN` | `UNKNOWN` | the basis time is not known |
| `READ_COVERAGE_NOT_CAPTURED` | `UNKNOWN` | a last-access rule and no binding captures the asset's reads |
| `READ_COVERAGE_STALE` | `UNKNOWN` | capture is complete only up to an instant before the expiry |
| `MODIFIED_TIME_UNBOUNDED` | `UNKNOWN` | a last-modified rule; later unlogged writes cannot be ruled out |
| `EXPIRY_UNDETERMINED` | `UNKNOWN` | the evidence bounds the expiry on both sides of now |
| `PROVIDER_RULE_UNREADABLE` | `UNKNOWN` | a provider setting is present and unreadable |
| `HOLD_PLACED` | `ON_HOLD` | the DLAP legal hold |
| `HOLD_LABEL` | `ON_HOLD` | the label named by `dlap.catalog.retention.legal-hold-label` |

### The sweep

`RetentionSweepJob` runs `RetentionSweep` on the cron `dlap.catalog.retention.cron` (default minute
15 of every hour) as a cluster-wide singleton: a ShedLock JDBC lock on database time (lock name
`catalog-retention-sweep`, bound `dlap.catalog.retention.lock-at-most-for`, default `PT2H`).

- **Paged.** Assets that are not `DELETED` are read in keyset pages of `asset_id`
  (`page-size`, default 200); labels and stored rules are loaded once per page.
- **Event time and the injected clock.** The as-of instant is the injected `Clock` at the start
  of the pass; nothing else reads a clock. The same catalog state and instant give the same result.
- **Idempotent.** An asset whose result is unchanged and whose `retention_evaluated_at` is younger
  than `evaluated-at-refresh` (default `P1D`) is neither locked nor written. A changed asset is
  re-read and re-evaluated under the `SELECT ... FOR UPDATE` row lock the event consumers take,
  in its own short transaction, and only the `retention_*` columns are written. An evaluation
  never overwrites a newer stored one.
- **One event per transition.** A change of `retention_status` writes one row to
  `dlap_catalog.retention_outbox` in the same transaction (unique `eventId`). A second singleton job,
  `RetentionDuePublisher` (lock `catalog-retention-due-publisher`), sends the rows in order to
  `dlap.catalog.asset.retention-due`, keyed by `assetUri`, and marks a row published only after the
  broker acknowledged it: at least once, never for a change that rolled back.
- **A failure** on one asset is counted and the pass goes on; after 25 failures in a row it stops.

### API

All paths are under `/api/v1/catalog/assets/{assetId}`. Reading is open to every human role of the
Access Model and `ROLE_SERVICE_ACCOUNT`; changing needs `ROLE_ADMIN`, `ROLE_CDO`, `ROLE_DIRECTOR` or
`ROLE_POLICY_MANAGER`. Every change is audited in `dlap_catalog.retention_audit` (who, what, when, the
reason) and evaluates the asset at once.

| Method and path | Effect |
|---|---|
| `GET /retention` | the stored result and every rule that applies (stored, label default, provider) |
| `POST /retention/rules` | add a `MANUAL` rule: `{basis, days}` or `{basis: FIXED_DATE, expiresAt}`, optional `reason`; 201 |
| `DELETE /retention/rules/{ruleId}` | remove a `MANUAL` rule; a policy's rule is not removable here; 204 |
| `PUT /legal-hold` | place the hold: `{reason}` required; repeating it changes nothing |
| `POST /legal-hold/release` | release the hold: `{reason}` required; the asset is evaluated at once |

### Settings

| Property | Default | Meaning |
|---|---|---|
| `dlap.catalog.retention.enabled` | `true` | run the scheduled sweep |
| `dlap.catalog.retention.cron` | `0 15 * * * *` | sweep schedule (Spring cron) |
| `dlap.catalog.retention.lock-at-most-for` | `PT2H` | longest the sweep lock is held |
| `dlap.catalog.retention.page-size` | `200` | assets per page, 1 to 5000 |
| `dlap.catalog.retention.evaluated-at-refresh` | `P1D` | rewrite an unchanged asset when its evaluation is older than this |
| `dlap.catalog.retention.label-defaults[n].{label,days,basis}` | empty | `LABEL_DEFAULT` rules; basis defaults to `LAST_ACCESSED` |
| `dlap.catalog.retention.legal-hold-label` | empty | a label that holds (`ON_HOLD`) while assigned; the `legalHold` flag itself changes only through the API |
| `dlap.catalog.retention.set-retention-listener-enabled` | `true` | read `setRetention` actions |
| `dlap.catalog.retention.binding-status-listener-enabled` | `true` | read binding statuses |
| `dlap.catalog.retention.publish.{enabled,interval,initial-delay,batch-size,send-timeout,lock-at-most-for,outbox-retention}` | `true`, `PT15S`, `PT30S`, 100, `PT30S`, `PT10M`, `P7D` | the retention-due publisher; published outbox rows are pruned only while it runs |

### Metrics

| Meter (Prometheus name) | Tags |
|---|---|
| `dlap_catalog_retention_sweep_assets_total` | `outcome`: changed, unchanged, error |
| `dlap_catalog_retention_sweep_runs_total` | `outcome`: completed, aborted |
| `dlap_catalog_retention_transitions_total` | `from`, `to`: status names |
| `dlap_catalog_retention_actions_total` | `outcome`: recorded, stale, ignored, dry_run, approval_pending, unmatched |
| `dlap_catalog_retention_binding_status_total` | `outcome`: stored, stale, removed, ignored |
| `dlap_catalog_retention_published_total` | `outcome`: published, failed |
| `dlap_catalog_retention_rule_invalid_total` | `source`: PROVIDER |
| `dlap_catalog_retention_outbox_backlog` | gauge: events waiting to be published |

## Read coverage (COV-01)

A missing read history must never look like "not read". Whether an asset's reads are recorded
at all is decided per resource by the provider's logging configuration (CloudTrail event
selectors, GCP audit configs, Azure diagnostic settings), and whether DLAP sees them by its log
bindings. `readCoverage` says how complete DLAP's read history of the asset is.

| `readCoverage` | Meaning | A reader may say |
|---|---|---|
| `FULL` | The provider configuration proves that every data read of the asset is recorded, and an enabled DLAP binding of the recording channel captures reads | "not read since D", within the coverage window |
| `PARTIAL` | Some reads are recorded and reach DLAP, others do not; `readCoverageReasons` names the gap | nothing about reads beyond the recorded ones |
| `NONE` | No read of the asset reaches DLAP's read history | nothing about reads |
| `null` | Unknown: no observation yet, or the collector lacked a permission (named in `readCoverageReasons`) | nothing about reads |

Only `FULL` lets a report, a policy or a retention rule treat a missing read as "not read".
`PARTIAL`, `NONE` and `null` count as unknown for every destructive action (POL-01, RET-02).
`readCoverageReasons` is a list of short strings: the gaps for `PARTIAL`, `NONE` and unknown, the
evidence for `FULL`. Reasons give counts and resource names, never principals or tag values.

### Collectors (da-discovery)

`ReadCoverageCollectionJob` runs the enabled collectors on `dlap.discovery.coverage.cron`
(default `0 41 0/6 * * *`) as a cluster-wide singleton (ShedLock JDBC lock
`discovery-read-coverage` on database time, `lock-at-most-for` default `PT1H`) and publishes one
observation per subject. Provider code is only in `providers/<cloud>/coverage`; the shared
package `com.dlap.discovery.coverage` uses no SDK (`ReadCoverageArchitectureTest`). The
transport is the JDK HTTP client with JSON over HTTPS; no SDK module was added.

| Cloud | Subject (root) | Applies to | Channel | Scope |
|---|---|---|---|---|
| AWS | `s3://{bucket}`, one per bucket | `S3_BUCKET`, `S3_OBJECT` | `AWS_CLOUDTRAIL` | the account (STS GetCallerIdentity) |
| GCP | `bq://{project}`, one per project | `BIGQUERY_DATASET`, `BIGQUERY_TABLE` | `GCP_CLOUD_AUDIT` | the project id |
| GCP | `gs://{bucket}`, one per bucket | `GCS_BUCKET`, `GCS_OBJECT` | `GCP_CLOUD_AUDIT` | the project id |
| Azure | `az://{account}`, one per storage account | `BLOB_STORAGE`, `BLOB_CONTAINER`, `BLOB_OBJECT` | `AZURE_STORAGE_RESOURCE_LOG` | the subscription id |

Other asset kinds get no observation and stay `null`.

**AWS** (`CloudTrailS3CoverageEvaluator`). A trail counts for a bucket when `GetTrailStatus`
says `IsLogging` and the trail is multi-region or homed in the bucket's region. Trails come from
`DescribeTrails` with shadow trails in each bucket region, so organization and multi-region
trails homed elsewhere are seen; each trail is read once in its home region.

- Basic selectors: a `DataResources` entry of type `AWS::S3::Object` with `ReadWriteType`
  `ReadOnly` or `All`. Values are ARN prefixes: `arn:aws:s3` or `arn:aws:s3:::bucket/` give
  `FULL`, `arn:aws:s3:::bucket/prefix` gives `PARTIAL` "CloudTrail data events cover a prefix
  subset".
- Advanced selectors: field selectors are ANDed, the values of one operator ORed.
  `eventCategory` must equal `Data`, `resources.type` must equal `AWS::S3::Object`, and
  `readOnly` must not limit the selector to writes. `resources.ARN` is matched as a string:
  `StartsWith` a prefix of `arn:aws:s3:::bucket/` is `FULL`, a longer one or `Equals` or
  `EndsWith` is `PARTIAL`, and `NotStartsWith`, `NotEquals` and `NotEndsWith` remove what they
  name. A filter on `eventName`, `userIdentity.arn`, `eventType`,
  `sessionCredentialFromConsole` or an event source other than `s3.amazonaws.com` gives at most
  `PARTIAL`.
- Trails and selectors are ORed. Several partial selectors stay `PARTIAL`: their union is not
  proven complete. A trail that could not be read makes the bucket unknown unless another trail
  proves `FULL`. No logging trail: `NONE`.
- Not modelled: CloudTrail Lake event data stores and S3 server access logs (DLAP ingests
  neither), and directory buckets (`AWS::S3Express::Object`).

**GCP** (`GcpAuditConfigEvaluator`). The IAM policies (version 3) of the project, each folder
above it and the organization are read; their `auditConfigs` combine as a union.

- A service's `DATA_READ` logs are on when an entry for the service or for `allServices` lists
  `DATA_READ` at any level. Cloud Storage is off by default; BigQuery data access logs are on by
  default and cannot be switched off.
- Any `exemptedMembers` for `DATA_READ`, at any level, in either entry: `PARTIAL` with the count.
- A level whose policy or ancestry could not be read: unknown, naming the permission, unless an
  exemption already proves `PARTIAL`.
- Cloud Audit Logs does not track access to public objects. A bucket that grants a role to
  `allUsers` or `allAuthenticatedUsers`, or that keeps fine-grained ACLs, is at most `PARTIAL`,
  unless `publicAccessPrevention` is `enforced` (then the bucket IAM policy is not read).
- BigQuery records table reads, `tabledata.list` and Storage Read API read sessions as
  `TableDataRead` (`CREATE_READ_SESSION`). Whether reads by jobs that run in another project land
  in the table's project is not yet verified live, so a BigQuery project is at most `PARTIAL`
  until `dlap.discovery.coverage.gcp.bigquery-cross-project-reads-verified=true`.

**Azure** (`AzureDiagnosticSettingsEvaluator`). The diagnostic settings of
`{account}/blobServices/default` (api-version `2021-05-01-preview`). A setting with a
destination (workspace, storage account, event hub or partner) that enables `StorageRead`, by
category or through `allLogs`, gives `FULL`. Settings that leave out `StorageRead` give
`PARTIAL` "diagnostic setting without StorageRead"; the `audit` group is not documented to hold
`StorageRead`, so it gives `PARTIAL` too. No setting: `NONE`.

**Permissions** the collectors need (input to IAM-03). A refused call makes the affected
subjects unknown and names the permission; a refused listing leaves the subjects without an
observation and is logged.

| Cloud | Permission | Call |
|---|---|---|
| AWS | `s3:ListAllMyBuckets` | ListBuckets |
| AWS | `s3:GetBucketLocation` | GetBucketLocation |
| AWS | `cloudtrail:DescribeTrails` | DescribeTrails (`includeShadowTrails`) |
| AWS | `cloudtrail:GetTrailStatus` | GetTrailStatus |
| AWS | `cloudtrail:GetEventSelectors` | GetEventSelectors |
| AWS | none | STS GetCallerIdentity |
| GCP | `resourcemanager.projects.get` | projects.get (ancestry) |
| GCP | `resourcemanager.folders.get` | folders.get (ancestry) |
| GCP | `resourcemanager.projects.getIamPolicy` | projects.getIamPolicy |
| GCP | `resourcemanager.folders.getIamPolicy` | folders.getIamPolicy |
| GCP | `resourcemanager.organizations.getIamPolicy` | organizations.getIamPolicy |
| GCP | `storage.buckets.list` | buckets.list |
| GCP | `storage.buckets.getIamPolicy` | buckets.getIamPolicy (buckets without enforced public access prevention) |
| Azure | `Microsoft.Storage/storageAccounts/read` | Storage Accounts - List |
| Azure | `Microsoft.Insights/diagnosticSettings/read` | Diagnostic Settings - List |

### Wire form

An observation travels on `dlap.log.event.normalized` (log-event.md), keyed by the root URI, in
the single encoding (a proto3 JSON object through a `StringSerializer`). `change_type` is
unspecified, so the router acknowledges it without routing and no lifecycle consumer changes
state from it. `event_time` is the time the collector read the configuration (the collector's
clock; an observation has no provider time). The `event_id` is
`LogEventIds.eventId(provider, "DLAP_READ_COVERAGE|<root>|<observedAt>")`.

| Field | Value |
|---|---|
| `source_format` | `DLAP_READ_COVERAGE/config-observation` |
| `origin` | `ORIGIN_CRAWLER` |
| `operation` | `READ_COVERAGE` |
| `parser_version` | `<collector-id>@<version>` |
| `target_resource.kind` | `DLAP_READ_COVERAGE` |
| `target_resource.id` | the root URI |
| `target_resource.technical_metadata` | `readCoverage.level` (`FULL`, `PARTIAL`, `NONE`, `UNKNOWN`), `readCoverage.reasons` (a JSON array of strings), `readCoverage.appliesTo` (comma-separated service types), `readCoverage.channel`, `readCoverage.scope` |

The example below is the test file both modules share
(`ReadCoverageEventEncoderTest`, `ReadCoverageWireTest`):

```json
{
  "eventId": "c12699bc80701961012e7fd05fdcf9f5c42e079c565a6ea6c21174baac65abef",
  "eventTime": "2026-10-20T06:41:00Z",
  "ingestTime": "2026-10-20T06:41:00Z",
  "sourceProvider": "AWS",
  "sourceService": "dlap-discovery",
  "changeType": "CHANGE_TYPE_UNSPECIFIED",
  "operation": "READ_COVERAGE",
  "resultStatus": "SUCCESS",
  "targetResource": {
    "kind": "DLAP_READ_COVERAGE",
    "id": "s3://finance-raw",
    "name": "",
    "project": "111122223333",
    "region": "",
    "resourcePath": "",
    "labels": {},
    "canonicalUri": "",
    "technicalMetadata": {
      "readCoverage.appliesTo": "S3_BUCKET,S3_OBJECT",
      "readCoverage.channel": "AWS_CLOUDTRAIL",
      "readCoverage.level": "PARTIAL",
      "readCoverage.reasons": "[\"CloudTrail data events cover a prefix subset\"]",
      "readCoverage.scope": "111122223333"
    },
    "relation": "RESOURCE_RELATION_UNSPECIFIED"
  },
  "schemaVersion": "v1",
  "sourceFormat": "DLAP_READ_COVERAGE/config-observation",
  "sourceSchemaVersion": "",
  "parserVersion": "aws-cloudtrail-coverage@1.0.0",
  "bindingId": "",
  "nativeEventId": "DLAP_READ_COVERAGE|s3://finance-raw|2026-10-20T06:41:00Z",
  "eventCategory": "EVENT_CATEGORY_UNSPECIFIED",
  "origin": "ORIGIN_CRAWLER",
  "parseStatus": "PARSE_STATUS_FULL",
  "relatedResources": [],
  "supersedesEventId": "",
  "sharedEventId": "",
  "parseWarnings": []
}
```

### Applying observations (da-catalog)

`ReadCoverageObservationListener` (group `da-catalog-read-coverage`, manual acknowledgment,
dead-letter topic `dlap.log.event.normalized.DLT`) skips records that are not observations
before decoding them, and `ReadCoverageApplier` writes the result. It is the only writer of the
columns; the JPA entity maps them read-only, so an entity save never overwrites them.

1. **Validation.** The provider must be `AWS`, `GCP` or `AZURE`; the root `s3://{bucket}`,
   `gs://{bucket}`, `bq://{project}[/{dataset}]` or `az://{account}` of that provider; the
   service types those the root may hold. Otherwise the record goes to the dead-letter topic,
   with a message that names the field and never the value. At most 20 reasons of 500
   characters are kept.
2. **Match.** The asset whose `asset_uri` is the root, and every asset whose `asset_uri` starts
   with the root and `/`, of the named service types and provider. No `LIKE`: a bucket named
   like the root's prefix is not matched.
3. **Combine with the bindings** (`ReadCoverageCombiner`) of the observation's provider and
   scope, ignoring disabled ones:
   - an enabled binding of the channel reports `READ_COVERAGE_CAPTURED`: the observation decides;
   - else, for BigQuery, an enabled `GCP_BQ_JOBS_VIEW` binding captures reads: `PARTIAL`,
     "bqjobs only: job reads only" (the JOBS view lists only jobs run in the project);
   - else, a binding of the channel exists but does not capture reads: unknown, naming its state;
   - else `NONE`: no DLAP binding ingests the recorded reads.
4. **Order.** A row that holds a newer observation is left alone; an equal time re-applies.
5. **Coverage start.** `readCoverageSince` is the first observation of the current unbroken run
   of `FULL`; any other level clears it.
6. A new asset stays unknown until the next observation.

Binding changes take effect at the next collector run. The retention signal below re-checks
the binding at every sweep.

### Fields

| Field | Column | Type | Meaning |
|---|---|---|---|
| `readCoverage` | `read_coverage` | `VARCHAR(10)`, `FULL`, `PARTIAL`, `NONE` or NULL | as above |
| `readCoverageReasons` | `read_coverage_reasons` | JSONB array of strings | as above |
| `readCoverageObservedAt` | `read_coverage_observed_at` | timestamptz | when the configuration was read |
| `readCoverageSince` | `read_coverage_since` | timestamptz | start of the current run of `FULL`; NULL unless `FULL` |

The activity report (`GET /api/v1/catalog/assets/activity`) returns `readCoverage`,
`readCoverageReasons`, `readCoverageObservedAt` and `readCoverageSince` on each row from these
columns (`AssetActivityRowDTO.java:119-122`), and the asset DTO returns the same four fields
(`AssetOutputDTO.java:122-135`). A reader that acts on `FULL` compares `readCoverageObservedAt`
with its own clock, because a collector run that failed as a whole leaves the last value in place,
and takes `readCoverageSince` as the instant from which reads are known to be recorded. Its
`readCoverage` filter takes `FULL`, `PARTIAL`,
`NONE` and `UNKNOWN` (NULL). Before COV-01 the report read a `readCoverage` key from
`technical_metadata` that nothing wrote; that key is now ignored.

### Retention

`AssetReadCoverageSource` replaces the binding-only signal of "Retention in the catalog" as the
primary `ReadCoverageSource`: reads are captured for an asset only when its `readCoverage` is
`FULL` and the binding signal captures them. The window starts at the later of the binding's
start and `readCoverageSince`, and is complete up to the earlier of the binding's low watermark
and `readCoverageObservedAt` plus `dlap.catalog.coverage.validity-grace` (default `PT0S`: a
configuration is known only when it was read). So a `PARTIAL`, `NONE` or unknown asset keeps
every `LAST_ACCESSED` rule `UNKNOWN` (`READ_COVERAGE_NOT_CAPTURED`), and an expiry after the last
observation is `READ_COVERAGE_STALE`.

### Settings

| Property | Default | Meaning |
|---|---|---|
| `dlap.discovery.coverage.enabled` | `false` | run the collection job |
| `dlap.discovery.coverage.cron` | `0 41 0/6 * * *` | schedule |
| `dlap.discovery.coverage.lock-at-most-for`, `lock-at-least-for` | `PT1H`, `PT1M` | the run's lock |
| `dlap.discovery.coverage.send-timeout` | `PT60S` | wait for broker acknowledgements |
| `dlap.discovery.coverage.aws.enabled` | `false` | AWS collector; also needs `cloud.provider.aws.enabled=true` |
| `dlap.discovery.coverage.aws.default-region` | `us-east-1` | region of the S3 listing and STS call |
| `dlap.discovery.coverage.aws.cloudtrail-endpoint` | empty | emulator endpoint for CloudTrail and STS |
| `dlap.discovery.coverage.gcp.enabled` | `false` | GCP collector |
| `dlap.discovery.coverage.gcp.projects` | empty (then `gcp.project.id`) | projects to observe |
| `dlap.discovery.coverage.gcp.bigquery-cross-project-reads-verified` | `false` | allow BigQuery `FULL` |
| `dlap.discovery.coverage.azure.enabled` | `false` | Azure collector; also needs `cloud.provider.azure.enabled=true` |
| `dlap.discovery.coverage.azure.subscriptions` | empty (then `azure.subscription-id`) | subscriptions to observe |
| `dlap.catalog.coverage.listener-enabled` | `true` | apply observations |
| `dlap.catalog.coverage.require-asset-coverage` | `true` | retention needs per-asset `FULL`; `false` restores the CAT-03 binding-only signal |
| `dlap.catalog.coverage.validity-grace` | `PT0S` | how long after its observation a `FULL` still proves reads for retention |

### Metrics

| Meter (Prometheus name) | Tags |
|---|---|
| `dlap_discovery_coverage_runs_total` | `outcome`: completed, locked, failed |
| `dlap_discovery_coverage_observations_total` | `collector`; `level`; `outcome`: sent, unacknowledged, failed |
| `dlap_catalog_coverage_observations_total` | `outcome`: applied, stale, unmatched; `level` |

## Storage (dlap_common)

The fields map to nullable columns on `dlap_common.dlap_assets`, except
`retention_status` (`VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN'`) and `legal_hold`
(`BOOLEAN NOT NULL DEFAULT FALSE`): a null there would be a second spelling of
"unknown" and "not held".

- CHECK constraints: `ck_asset_cloud_provider_valid`, `ck_asset_service_type_valid`,
  `ck_asset_retention_status_valid`, `ck_asset_retention_basis_valid`,
  `ck_asset_size_bytes_nonneg`, `ck_asset_retention_days_nonneg`,
  `ck_asset_legal_hold_status`, and for read coverage (COV-01) `ck_asset_read_coverage_valid`,
  `ck_asset_read_coverage_reasons_array`, `ck_asset_read_coverage_since_full`.
- Sort and filter indexes: `cloud_created_at`, `size_bytes`, `last_accessed_at`,
  `last_modified_at`, `retention_expires_at`, `(retention_status, retention_expires_at)`,
  `retention_evaluated_at`, `read_coverage`, and a partial index on held assets.
- Fresh databases get all of it from `init-scripts/02-create-common-schema.sql`; existing
  ones from `upgrades/2026-10-18_asset_time_size_retention.sql` and, for read coverage,
  `upgrades/2026-10-20_asset_read_coverage.sql` (applied by `run-upgrades.sh`). All are idempotent.
- No backfill. The new times start null and fill from event-time evidence;
  `dlapUsageMetrics.lastAccessedAt` is not copied, since it is processing time.
- The `retention_*` columns hold evaluation output only (Retention, Rule). The
  retention rule store for `POLICY` and `MANUAL` rules is not in `dlap_common`: it
  belongs to da-catalog's own schema: `dlap_catalog.retention_rule` (Flyway V4, see "Retention in the
  catalog (CAT-03)").

## Catalog event ingest (CAT-02a, ADR-0055)

How da-catalog applies log events to the fields above. The code is
`da-catalog/src/main/java/com/dlap/catalog/kafka/` (`AssetEventApplier`,
`AssetStateRules`, `AssetMatcher`, `CanonicalUriIndex`, `LogEventEnricher`),
`idempotency/` and `usage/`; the tables are in da-catalog's Flyway
`V3__processed_event_and_usage_daily.sql`.

### Consumers

| Topic | Consumer group | Applies |
|---|---|---|
| `dlap.discovery.asset.discovered` | `da-catalog` | row creation and refresh, lifecycle, creation time, size |
| `dlap.discovery.asset.{modified,deleted,accessed,permission-changed,archived}` | `da-catalog` | lifecycle, activity times, usage, creation time, size |
| `dlap.log.event.normalized` | `da-catalog-lineage` | lineage edges, harvested metadata |

1. **Dual read.** Every record is read with `DlapLogEventCodec` (either encoding,
   legacy keys, the legacy crawler shape; log-event.md section 7). A record that is not a
   log event (`LogEventDecodeException`) goes to `<topic>.DLT` without retries.
2. **One transaction per record.** The change and its processed-event claims commit
   together; the offset is acknowledged only after the commit. Any failure rolls back
   and propagates to the error handler: three retries (1 s, doubling), then
   `<topic>.DLT` with the partition left to Kafka. Nothing is swallowed.
3. **Row locks.** Every `dlap_assets` row is locked (`SELECT ... FOR UPDATE`) before it
   changes, object row before container, so concurrent consumers never overwrite each
   other's fields. A processed-event claim on a row that does not change (the downstream
   asset of a lineage edge) takes no lock: the claim's primary key makes it idempotent.
4. **Clock.** An injected `java.time.Clock` stamps the operational times only
   (`processed_at`, `lastScannedAt`, `indexed_at`). Business times come from events.

### Targets

The target is `targetResource.canonical_uri` (re-validated) or, if absent or invalid,
`targetResource.id`, normalized with hints from the event: provider from
`source_provider`, kind from `targetResource.kind` (a service type, a da-discovery
crawler type id, a CloudTrail resource type, a GCP resource type), project and region;
plus the operator defaults `dlap.catalog.ingest.match.default-hints.*`.

- A container-level target (and an object under a governed prefix) is the **direct**
  row. An object target also **rolls up** to its container (`CanonicalAssetId.rollUp()`).
  Crawler observations of objects do not roll up: a crawl is not activity.
- On the container, an object event moves only the activity maxima and the usage
  counts. A legacy object event (no `event_category`) with CREATE, UPDATE or DELETE is a
  write of the container's content, never a lifecycle event of the container.
- **Creation.** Only a successful CREATE that is a management event or a crawler
  observation creates the direct row; under a governed prefix an object's creating data
  write does too. The row gets `assetUri` = the canonical URI, `cloudProvider` and
  `serviceType` from the canonical ID (no default provider), `nativeAssetId` = the raw
  form, `status` ACTIVE, `discoveredAt` = the event time, and `discoverySource` = the
  evidence channel. `lifecycleEventTime` is the event time of a CREATE log event; for a
  crawler observation, which is not a transition, it is the reported
  `targetResource.created_time` when that is not after the crawl, else null, so a delayed
  ARCHIVE or DELETE from before the crawl still applies.
- **Unknown targets** (ADR-0046 section 2). When neither the direct nor the container row
  exists, nothing changes, `dlap_catalog_unmatched_target_total{provider,kind,category}`
  counts the event (kind `UNRESOLVED` when the identifier did not normalize, with the
  reason in `dlap_catalog_unresolved_target_total{provider,reason}`), and no claim is
  recorded, so a replay after the row appears applies the event.

### Matching

A target finds its row in this order (`dlap_catalog_asset_match_total{method}`):

1. **exact**: `assetUri` equals the canonical URI;
2. **normalized**: the row's stored `assetUri`, normalized with hints from the row
   (`serviceType`, `cloudProvider`, `region`) and the operator defaults, equals the
   canonical URI. The values are kept in `dlap_catalog.asset_uri_canonical`, built at
   startup (and on the first miss), written through for rows the catalog creates,
   caught up on a miss at most every `dlap.catalog.ingest.match.catch-up-interval`, and
   recomputed when a row's stored URI changed. With duplicates the oldest row wins;
3. **raw**: only when the target did not normalize: `assetUri` equals the raw identifier
   and `cloudProvider` equals the event's provider.

### Rules applied

The rules of "Time, size and activity", with these consumer conditions:

| Field | Moved by |
|---|---|
| `status`, `lifecycleEventTime`, `recreatedAt` | a successful management DELETE or ARCHIVE on the direct row (crawler DELETE included), when `event_time >= lifecycleEventTime`. A CREATE (management event or crawler observation) is a transition only on a `DELETED` row: when `event_time >= lifecycleEventTime` it starts a new incarnation exactly as rule 5 states (`recreatedAt`, `cloudCreatedAt` and `lifecycleEventTime` take the CREATE's event time; the activity maxima, size and retention evaluation are cleared, including evidence delivered before the CREATE; a legal hold stays). On an `ACTIVE`, `ARCHIVED` or `PENDING_DELETION` row a CREATE changes neither `status` nor `lifecycleEventTime`: crawlers report every resource they see as a CREATE, so a crawl never reactivates an archived or pending-deletion row and never makes a delayed ARCHIVE or DELETE stale. No log event moves a row from `ARCHIVED` or `PENDING_DELETION` back to `ACTIVE`. |
| `cloudCreatedAt` | `targetResource.created_time` of any successful event, and the event time of a successful non-crawler CREATE; the least value within the incarnation wins. A crawl time is a creation time only as the start of a new incarnation (rule 5); a `created_time` older than `recreatedAt` is ignored. |
| `sizeBytes`, `sizeObservedAt` | `targetResource.size_bytes` of a successful event on the direct row, observed at the event time; never from objects. |
| `lastAccessedAt`, `lastAccessSource` | `SUCCESS` `DATA_READ` and `DATA_READ_OBSERVED` events, direct or rolled up. |
| `lastModifiedAt` | `SUCCESS` `DATA_WRITE` events, direct or rolled up. |
| `lastPermissionChangeAt` | `SUCCESS` `PERMISSION` events, direct or rolled up. |
| `technicalMetadata`, `assetName`, `region`, `nativeAssetId` | crawler observations and CREATE or UPDATE events on the direct row: technical metadata is merged; a crawler refreshes name and region, other events only fill them when blank; the native id is set once. |

A crawler observation carries no result status; it counts as successful for lifecycle (a
DELETE, or a CREATE on a `DELETED` row), creation time, size and metadata, never for
activity.

### Idempotency and usage

| Table | Key | Meaning |
|---|---|---|
| `dlap_catalog.processed_event` | `(consumer, event_id, asset_id)` | Consumers `catalog-asset-state`, `catalog-usage`, `catalog-enrichment`, `catalog-lineage`. `event_id` is the event id (or `payload:<sha256>` of an event without one; keys over 128 characters are stored as `sha256:<hex>`). An event that supersedes another also claims the superseded id, so a late original is ignored. Pruned after `dlap.catalog.ingest.processed-event.retention` (default 100 days, longer than the log store's 90-day replay window) by a replica-safe job. |
| `dlap_catalog.asset_usage_daily` | `(asset_id, day)` | `reads`: successful recorded reads (`DATA_READ`, not `DATA_READ_OBSERVED`); `writes`: successful data writes. `day` is the UTC date of `event_time`. The counts are a history by day per row, kept across incarnations: a late read of an earlier incarnation counts on its own day. Each increment is guarded by a `catalog-usage` claim on `LogEventIds.usageDedupKey` (the shared key across accounts when CloudTrail `sharedEventID` is set; the superseded id for a correction) and the asset, so redeliveries and cross-account copies count once. |

### Settings

| Property | Default | Meaning |
|---|---|---|
| `dlap.catalog.ingest.governed-prefixes` | empty | Canonical object-URI prefixes whose objects get rows. Validated at startup. |
| `dlap.catalog.ingest.processed-event.retention` | `P100D` | How long claims are kept. |
| `dlap.catalog.ingest.processed-event.prune-enabled` | `true` | Run the pruner. |
| `dlap.catalog.ingest.processed-event.prune-interval` | `PT1H` | Pruner spacing. |
| `dlap.catalog.ingest.match.catch-up-interval` | `PT30S` | Minimum spacing of index catch-up sweeps per replica. |
| `dlap.catalog.ingest.match.default-hints.{account,project,metastore-id,workspace-id}` | empty | Normalization context for stored and event forms that need it. |

## Lineage

Upstream/downstream relationships are **derived from logs**, not declared. A
query/ETL/job event that reads source assets and writes a destination yields a
directed edge `upstream -> downstream` stored in `dlap_asset_lineage`
(`relationshipType`, `detailsJson` = job evidence). Edge endpoints are normalized and
matched like event targets (Catalog event ingest, Matching), and failed or pending
operations give no edges. The lineage graph
(`GET /assets/{id}/lineage`, enhanced graph API) is the reconstructed network of
these edges plus each node's harvested metadata: the substrate the policy engine
and agentic features reason over.

## Conformance

An implementation conforms when: (a) `assetUri` is globally unique and is the join
key across labels/lineage/policy/events, (b) no provider-specific field leaks into
the core identity block (provider details live in the JSON bags), (c) owner and
lineage are reconstructed from log events rather than required as manual input,
(d) the descriptor is emitted on the canonical asset-change events other stages
consume, (e) every time field except `retentionEvaluatedAt` is a business time and an
unknown value is null, never a guess, and (f) nothing retires an asset unless its
retention status is `DUE` and it is not on legal hold.

## Changelog

- v1.3 (2026-11-02, Q-831): the activity row and the asset DTO list `readCoverageObservedAt` and
  `readCoverageSince` beside `readCoverage` and `readCoverageReasons`, as the code returns them
  (`AssetActivityRowDTO.java:119-122`, `AssetOutputDTO.java:122-135`). No field was added; the spec
  now names the two that COV-02 exposed.
