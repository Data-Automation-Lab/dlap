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

# Lifecycle State specification

Version 0.1 | Status: normative draft | Schema: [`schemas/lifecycle-state-0.1.json`](schemas/lifecycle-state-0.1.json) |
Examples: [`examples/lifecycle-state/`](examples/lifecycle-state/) | Source of field meanings:
[Asset Descriptor](asset-descriptor.md) v1.3, [Asset ID](asset-id.md) 1.0, [Log Event](log-event.md) v1,
ADR-0046, ADR-0047 and ADR-0055 in [`docs/DECISIONS.md`](../DECISIONS.md).

The key words MUST, MUST NOT, SHOULD and MAY are used as in RFC 2119.

## 1. Purpose

A Lifecycle State object is a small JSON record of one data asset's lifecycle state at one instant.
It says when the asset was created, last read and last written, how complete the read history is,
how big the asset is, what retention says, whether a legal hold applies, and whether the asset was
archived or deleted. Every value carries the log events that produced it.

The object is derived from the Asset Descriptor. It is vendor neutral: any engine that reads
provider audit logs can produce it, and any tool can consume it without knowing the engine.

The record exists so that three questions get the same honest answer everywhere:

1. When was this asset last used, and how sure are we?
2. May it be archived or deleted now?
3. Which log events prove each answer?

Terms used below:

| Term | Meaning |
|---|---|
| Engine | A producer of Lifecycle State objects. "The reference engine" is the implementation in this repository. |
| Consumer | Anything that reads a Lifecycle State object: a catalog, a policy decision point, a report, a person. |
| Log Event | A normalized event as [log-event.md](log-event.md) defines it, with an `event_id` and an `event_time`. |
| Observation | A Log Event that records provider state read through an API or a report (a crawl, an inventory report, a logging configuration), not a provider audit record. |
| Disposition | An archive or a delete of the asset. |

## 2. The object

The members, in this order (section 5, R6):

```json
{
  "specVersion": "0.1",
  "asset": "s3://example-finance-raw",
  "platform": { "provider": "AWS", "kind": "S3_BUCKET" },
  "asOf": "2026-10-31T12:00:00Z",
  "createdAt": "2021-04-12T17:03:22Z",
  "lastReadAt": "2026-10-02T14:27:09Z",
  "lastWrittenAt": "2026-10-29T22:10:41Z",
  "readCoverage": "PARTIAL",
  "readCoverageReasons": ["CloudTrail data events cover a prefix subset"],
  "readCoverageSince": null,
  "sizeBytes": 1879048192000,
  "sizeObservedAt": "2026-10-30T00:00:00Z",
  "retention": {
    "status": "ON_HOLD",
    "basis": "LAST_ACCESSED",
    "days": 365,
    "fixedDate": null,
    "expiresAt": "2027-10-02T14:27:09Z",
    "source": "POLICY"
  },
  "legalHold": true,
  "disposition": null,
  "provenance": ["... one entry per field with evidence, section 3.8 ..."]
}
```

The complete object, with its provenance, is
[`valid-s3-bucket-partial-coverage-legal-hold.json`](examples/lifecycle-state/valid-s3-bucket-partial-coverage-legal-hold.json).

Every member is required. A value that is not known is `null`, never omitted (R1).

## 3. Fields

Each field maps to an Asset Descriptor field. The descriptor is the source of the meaning; this
section only states what the object adds or narrows.

| Field | Type | Asset Descriptor source |
|---|---|---|
| `specVersion` | string, `"0.1"` | none: the version of this specification (section 6) |
| `asset` | string | `assetUri` |
| `platform` | object | `cloudProvider`, `serviceType` |
| `asOf` | timestamp | the evaluation instant (`retentionEvaluatedAt` of the evaluation the object reports) |
| `createdAt` | timestamp or null | `cloudCreatedAt` |
| `lastReadAt` | timestamp or null | `lastAccessedAt` |
| `lastWrittenAt` | timestamp or null | `lastModifiedAt` |
| `readCoverage` | `FULL`, `PARTIAL`, `NONE` or null | `readCoverage` |
| `readCoverageReasons` | array of strings | `readCoverageReasons` |
| `readCoverageSince` | timestamp or null | `readCoverageSince` |
| `sizeBytes` | integer or null | `sizeBytes` |
| `sizeObservedAt` | timestamp or null | `sizeObservedAt` |
| `retention` | object | `retentionStatus`, `retentionBasis`, `retentionDays`, `retentionExpiresAt`, `retentionSource`, and the deciding rule |
| `legalHold` | boolean | `legalHold`, and `retentionStatus` `ON_HOLD` |
| `disposition` | object or null | `status` (`ARCHIVED`, `DELETED`) and `lifecycleEventTime`, with the Log Events that applied them |
| `provenance` | array | the evidence channels (`cloudCreatedSource`, `lastAccessSource`) and the Log Event ids behind each value |

A timestamp is an RFC 3339 instant in UTC with a `Z` suffix and 0, 3, 6 or 9 fraction digits
(section 5, R6).

### 3.1 asset and platform

- `asset` MUST be the canonical URI of [asset-id.md](asset-id.md) (ADR-0047), for example
  `gs://bucket`, `bq://project/dataset/table`, `az://account/container`. An engine whose stored
  `assetUri` is in an older form MUST normalize it first. Two objects describe the same asset
  exactly when their `asset` values are equal.
- `platform.provider` is `cloudProvider`: `AWS`, `GCP`, `AZURE`, `OCI`, `UNITY`, `DATABRICKS` or
  `SNOWFLAKE`.
- `platform.kind` is `serviceType`, the asset kind, one of the 30 kinds of
  [asset-id.md](asset-id.md) section 9 (for example `S3_BUCKET`, `BIGQUERY_TABLE`,
  `BLOB_CONTAINER`).

### 3.2 Times

- `createdAt` is the provider creation time of the current incarnation: the least value from
  CREATE events and reported creation times (Asset Descriptor, Time rules 3 and 5).
- `lastReadAt` is the greatest event time of a successful `DATA_READ` or `DATA_READ_OBSERVED`
  event, after object events roll up to their container.
- `lastWrittenAt` is the greatest event time of a successful `DATA_WRITE` event, or a
  provider-reported content modification time.
- `asOf` is the instant at which the engine evaluated the object. It is the only value taken from
  the engine's clock (R3).

A time field is a maximum or minimum over evidence. A late event never moves it backwards.

### 3.3 Read coverage

`readCoverage` says how complete the engine's read history of the asset is
(Asset Descriptor, "Read coverage (COV-01)"):

| Value | Meaning | A consumer may say |
|---|---|---|
| `FULL` | The provider configuration proves every data read is recorded, and the engine captures those records. | "not read since D", for D at or after `readCoverageSince` |
| `PARTIAL` | Some reads are recorded and captured, others are not. | nothing beyond the recorded reads |
| `NONE` | No read of the asset reaches the engine. | nothing about reads |
| `null` | Unknown: no observation yet, or the observer lacked a permission. | nothing about reads |

- `readCoverageReasons` names the gaps for `PARTIAL`, `NONE` and `null`, and the evidence for
  `FULL`. At most 20 strings of at most 500 characters. Reasons give counts and resource names,
  never principals or tag values.
- `readCoverageSince` is the start of the current unbroken run of `FULL`. It is non-null exactly
  when `readCoverage` is `FULL`.

### 3.4 Size

- `sizeBytes` is the latest observed size in bytes. `0` is a real size. Size never rolls up from
  object events; a container's size comes only from a container-level observation.
- `sizeObservedAt` is the time at which `sizeBytes` was true. It is null exactly when `sizeBytes`
  is null.

### 3.5 retention

The retention object is the result of the retention evaluation at `asOf`
(Asset Descriptor, "Retention"). Rules are kept at their source; these members name the rule that
decided.

| Member | Type | Meaning |
|---|---|---|
| `status` | `UNKNOWN`, `NO_POLICY`, `RETAINED`, `DUE`, `ON_HOLD` | `retentionStatus` |
| `basis` | `CREATED`, `LAST_MODIFIED`, `LAST_ACCESSED`, `FIXED_DATE` or null | `retentionBasis` of the deciding rule |
| `days` | integer 0 to 365000, or null | `retentionDays` of the deciding rule; null for `FIXED_DATE` |
| `fixedDate` | timestamp or null | the expiry a `FIXED_DATE` rule carries; null for the other bases |
| `expiresAt` | timestamp or null | `retentionExpiresAt` |
| `source` | string or null | `retentionSource`: `POLICY`, `LABEL_DEFAULT`, `PROVIDER`, `MANUAL`, or another upper snake case source |

Constraints, all checked by the schema except the last:

- `basis` null: `days`, `fixedDate` and `source` are null.
- `basis` `FIXED_DATE`: `days` is null, `fixedDate` and `source` are set.
- Any other `basis`: `days` and `source` are set, `fixedDate` is null.
- `NO_POLICY`: `basis` and `expiresAt` are null.
- `UNKNOWN`: `expiresAt` is null. `basis` names an undecided rule, or is null when nothing was
  evaluated yet.
- `DUE` and `RETAINED`: `basis` and `expiresAt` are set. For `DUE`, `expiresAt` is the latest
  possible expiry ("due no later than"); for `RETAINED`, the earliest possible expiry ("retained at
  least until").
- `ON_HOLD`: `expiresAt` is what the rules alone give, for information only.
- With basis `FIXED_DATE` and status `DUE` or `RETAINED`, `expiresAt` equals `fixedDate`.

### 3.6 legalHold

`legalHold` is true when the asset is held: the Asset Descriptor `legalHold` is true, or its
`retentionStatus` is `ON_HOLD` for another reason (a configured hold label). So `legalHold` is true
exactly when `retention.status` is `ON_HOLD`; the schema checks both directions.

`legalHold: false` does not prove that no provider-native hold exists (S3 Object Lock, GCS object
holds, Azure immutability policies). Executors check those at the provider and fail closed
(Asset Descriptor, "Legal hold").

### 3.7 disposition

`disposition` records an archive or a delete that was executed. It is null when none is known.

| Member | Type | Meaning |
|---|---|---|
| `action` | `archive` or `delete` | `archive` for the Asset Descriptor `status` `ARCHIVED`, `delete` for `DELETED` |
| `executedAt` | timestamp | `lifecycleEventTime`: the event time of the provider event that records the action |
| `executedBy` | `native-rule` or `engine` | `native-rule`: the provider acted on its own rule (a lifecycle rule, a table expiration). `engine`: a lifecycle engine's executor issued the request. |
| `evidence` | non-empty array of Log Event ids | the events that record the action, ascending, no duplicates |

- A disposition without evidence is not a disposition. An engine that cannot name the event
  MUST emit `null`.
- `PENDING_DELETION` is not a disposition.
- The engine decides `executedBy` from the evidence events: the request of its own executor is
  `engine`; an action the provider records as its own is `native-rule`. An action by any other
  actor (a person, another tool) has no value in 0.1; the engine MUST emit `null` for it
  (section 10).

### 3.8 provenance

`provenance` says which log bindings and Log Event ids produced each field.

| Member | Type | Meaning |
|---|---|---|
| `field` | `createdAt`, `lastReadAt`, `lastWrittenAt`, `readCoverage`, `sizeBytes`, `retention`, `legalHold`, `disposition` | the field the entry explains |
| `channel` | string or null | the evidence channel, `<CHANNEL>` or `<CHANNEL>:<SOURCE_KIND>` (Asset Descriptor, evidence channels), for example `AUDIT_LOG:GCP_CLOUD_AUDIT`, `INVENTORY`, `CRAWLER` |
| `bindingIds` | array of strings | the `binding_id` values of the events; empty when they carry none (observations) |
| `eventIds` | non-empty array of Log Event ids | the events that determine the value |

Rules:

- One entry per field, at most. Entries appear in the order of the `field` list above.
- `createdAt`, `lastReadAt`, `lastWrittenAt`, `sizeBytes` and `disposition` have an entry exactly
  when they are non-null.
- `readCoverage` has an entry when it is non-null, and MAY have one when it is null because an
  observation reported a refused permission.
- `retention` has an entry when the deciding rule came from a Log Event (a `PROVIDER` rule read
  by a crawl). A rule from configuration or an operator has none.
- `legalHold` has an entry only when a Log Event carries the hold. A hold placed through an
  engine's audited API has none; the engine's own audit trail holds that record.
- For a maximum or a minimum, `eventIds` lists every applied event whose time equals the value.
  For `sizeBytes`, the observation at `sizeObservedAt`. For `readCoverage`, the latest applied
  observation. For `disposition`, the same ids as `disposition.evidence`.
- `eventIds` and `bindingIds` are in ascending order with no duplicates. When the events of one
  entry come through different channels, `channel` is the channel of the first id.

## 4. Derivation from the Asset Descriptor

An engine that keeps Asset Descriptors builds the object like this:

1. Evaluate retention at `asOf` (Asset Descriptor, "Evaluation"). Use that result for `retention`
   and that instant for `asOf`.
2. Copy the fields of section 3 from the descriptor row, normalizing `asset`.
3. Set `fixedDate` from the deciding `FIXED_DATE` rule at its source (a BigQuery table expiration
   time in `technicalMetadata`, or a `MANUAL` rule). The descriptor has no column for it.
4. Set `legalHold` from `legalHold` and `retentionStatus` (section 3.6).
5. Build `disposition` from `status`, `lifecycleEventTime` and the Log Events that applied the
   transition (section 3.7).
6. Build `provenance` from the evidence channels and the processed Log Event ids (section 3.8).

An engine that does not keep Asset Descriptors MUST produce the same object from the same Log
Events (R4).

## 5. Normative rules

**R1. Null means unknown.** A null value is unknown. It is never a default, never zero, never
"never". A consumer MUST NOT treat a null `lastReadAt` as "never read", a null `lastWrittenAt` as
"never written", a null `sizeBytes` as empty, or a null `disposition` as proof that the asset was
never archived or deleted. Unknown is never stale and never fresh.

**R2. Coverage for destructive decisions.** `PARTIAL`, `NONE` or null `readCoverage` counts as
unknown for any destructive decision (archive, delete, or anything that loses data). Only `FULL`
lets a consumer treat a missing read as "not read", and only for the window that starts at
`readCoverageSince`. A recorded read is a lower bound under every coverage value: the asset was
read at least then.

**R3. Time sources.** `createdAt`, `lastReadAt`, `lastWrittenAt`, `readCoverageSince`,
`sizeObservedAt`, `retention.fixedDate`, `retention.expiresAt` and `disposition.executedAt` come
from provider events or observations: a Log Event `event_time`, or a resource time the provider
reports. They never come from the engine's clock, from ingest time, or from a consumer's wall
clock. `asOf` is the only exception. An observation's time is the time the provider state was read
(Asset Descriptor, "Wire form"); it is never later than that read.

**R4. Determinism.** Two conforming engines given the same Log Events produce the same object,
apart from `asOf`. Every field outside `retention` depends on the Log Events alone. `retention`
also depends on the retention rules in force and on `asOf`, because a rule becomes `DUE` with
time; two engines compare `retention` at the same `asOf` and with the same rules.

**R5. A legal hold blocks every disposition.** No engine executes an archive or a delete while
`legalHold` is true. An object with `legalHold: true` MUST have `retention.status` `ON_HOLD`, and its
`disposition` MUST be null or an `archive`. An archive on a held asset was executed before the hold
and stays visible, so the record never drops history; a held asset is never deleted. A consumer MUST
NOT act destructively on an asset whose `legalHold` is true.

**R6. One form.** So that R4 can be checked byte for byte after parsing:

- Timestamps are rendered as Java `Instant#toString` renders them: UTC, `Z`, seconds always
  present, and the shortest of 0, 3, 6 or 9 fraction digits that is exact.
- Members appear in the order of section 2. Array orders are those of section 3: reasons in the
  order of the observation, provenance in field order, ids ascending.
- Integers are JSON numbers. `sizeBytes` fits in a signed 64-bit integer.

**R7. Retire gate.** An engine executes a disposition only when `retention.status` is `DUE` and
`legalHold` is false, both read again just before the action, because a hold can arrive after the
last evaluation (Asset Descriptor, "Legal hold", retire gate). Approval and fail-closed dispatch
apply on top. A provider's native rule is outside the engine's control; the engine records it as
`executedBy: native-rule`.

## 6. Versioning

- `specVersion` is `MAJOR.MINOR`. This document is `0.1`.
- Each version has its own schema file, `schemas/lifecycle-state-<version>.json`, with `$id`
  `urn:lifecycle-state:schema:<version>`. A published file never changes; a fix is a new version.
- Before 1.0, a minor version may add, remove or change fields. From 1.0, a minor version only
  adds optional members and enum values; a breaking change needs a new major version.
- A consumer MUST reject an object whose `specVersion` it does not support. It MUST NOT guess the
  meaning of an unknown member.
- A change to this specification is recorded as an ADR in `docs/DECISIONS.md`.

## 7. Conformance

**Producer.** An engine conforms when every object it emits:

1. validates against `schemas/lifecycle-state-0.1.json`;
2. follows R1 to R7;
3. derives each field as sections 3 and 4 state.

**Consumer.** A consumer conforms when it rejects unsupported versions (section 6) and follows R1,
R2 and R5.

**Mapping.** A producer of a mapping in section 8 conforms when it emits exactly the form that
section states, including the rendering of null.

**Test set.** [`examples/lifecycle-state/`](examples/lifecycle-state/) holds three valid and two
invalid objects:

| File | Shows |
|---|---|
| `valid-bigquery-table-full-coverage-expired.json` | A BigQuery table with `FULL` coverage, a `FIXED_DATE` retention date in the past, and a native-rule delete with evidence |
| `valid-s3-bucket-partial-coverage-legal-hold.json` | An S3 bucket with `PARTIAL` coverage and a legal hold |
| `valid-azure-container-unknown-read.json` | An Azure container with unknown read time and null coverage |
| `invalid-last-read-at-number.json` | `lastReadAt` as a number: rejected |
| `invalid-disposition-without-evidence.json` | A disposition without `evidence`: rejected |

`LifecycleStateSchemaTest` in `da-protos` validates every example against the schema, expects the
invalid ones to fail on the named field, and checks that each cross-field rule of the schema
rejects its violation. Run it with `./gradlew :da-protos:test`.

## 8. Mappings

Each mapping carries the same facts in another system's form. None of them changes the meaning
of a field. Where the target cannot hold `null`, the mapping states the rendering, and that
rendering still means unknown (R1).

### 8.1 OpenLineage dataset facet `lifecycle`

Schema: [`schemas/openlineage-lifecycle-facet-0.1.json`](schemas/openlineage-lifecycle-facet-0.1.json),
in OpenLineage facet style: a `$defs` entry `LifecycleDatasetFacet` that extends
`https://openlineage.io/spec/2-0-2/OpenLineage.json#/$defs/DatasetFacet`, and a top-level
property `lifecycle`.

- The facet goes under the dataset's `facets` as `lifecycle`.
- Its members are the Lifecycle State members, unchanged, plus `_producer` and `_schemaURL` from
  the OpenLineage base facet.
- `_schemaURL` is `urn:lifecycle-state:openlineage-lifecycle-facet:0.1#/$defs/LifecycleDatasetFacet`.
  A producer that publishes the schema at a URL MAY use that URL instead.
- With `_producer`, `_schemaURL` and `_deleted` removed, the facet MUST validate against the
  Lifecycle State schema. The facet schema repeats the member types; the Lifecycle State schema
  holds the cross-field rules.
- The dataset `namespace` and `name` follow [asset-id.md](asset-id.md) section 11. `asset` keeps
  the canonical URI, which can differ from them.
- `lifecycle` is not an OpenLineage standard facet name. It is distinct from the standard
  `lifecycleStateChange` facet, which records a DDL-style change (`CREATE`, `DROP`, and so on) of
  one run.

Example, the Azure container of `valid-azure-container-unknown-read.json`:

```json
{
  "namespace": "abfss://archive-2019@examplestore.dfs.core.windows.net",
  "name": "/",
  "facets": {
    "lifecycle": {
      "_producer": "urn:lifecycle-state:reference-engine:0.1",
      "_schemaURL": "urn:lifecycle-state:openlineage-lifecycle-facet:0.1#/$defs/LifecycleDatasetFacet",
      "specVersion": "0.1",
      "asset": "az://examplestore/archive-2019",
      "platform": { "provider": "AZURE", "kind": "BLOB_CONTAINER" },
      "asOf": "2026-10-31T12:00:00Z",
      "createdAt": "2019-02-01T10:00:00Z",
      "lastReadAt": null,
      "lastWrittenAt": null,
      "readCoverage": null,
      "readCoverageReasons": ["permission Microsoft.Insights/diagnosticSettings/read was refused"],
      "readCoverageSince": null,
      "sizeBytes": null,
      "sizeObservedAt": null,
      "retention": {
        "status": "UNKNOWN",
        "basis": "LAST_ACCESSED",
        "days": 730,
        "fixedDate": null,
        "expiresAt": null,
        "source": "LABEL_DEFAULT"
      },
      "legalHold": false,
      "disposition": null,
      "provenance": [
        {
          "field": "createdAt",
          "channel": "CRAWLER",
          "bindingIds": [],
          "eventIds": ["78af4ac4a17dabd70b3c40597431af482b0965190a917b48e8cfee70bea96df1"]
        },
        {
          "field": "readCoverage",
          "channel": "CRAWLER",
          "bindingIds": [],
          "eventIds": ["693b8502f97d8c49dec9e422c0dd4f055491fb25843cbf198c64140c965a8b93"]
        }
      ]
    }
  }
}
```

### 8.2 Open Knowledge Format v0.1

Open Knowledge Format (OKF) v0.1 is a directory of Markdown files with YAML frontmatter. Only
`type` is required; `title`, `description`, `resource`, `tags` and `timestamp` are optional.
The engine emits one concept file per asset.

- **Path.** `lifecycle-state/<id>.md`, where `<id>` is the lowercase hex SHA-256 of the UTF-8
  `asset` URI. The URI itself can hold `/` and `:`, so it is not used as a file name.
- **Frontmatter.** Exactly these keys, in this order:

  | Key | Value |
  |---|---|
  | `type` | `lifecycle-state` |
  | `title` | `asset`, double-quoted |
  | `description` | `"Lifecycle State of <provider> <kind> <asset> as of <asOf>."` |
  | `resource` | `asset`, double-quoted |
  | `tags` | a YAML list, in this order: `lifecycle-state`; `provider` in lower case; `kind` in lower case with `_` written as `-`; `read-coverage-full`, `-partial`, `-none` or `-unknown`; `retention-` plus `status` in lower case with `_` written as `-`; `legal-hold` only when `legalHold` is true; `disposition-archive`, `disposition-delete` or `disposition-unknown` |
  | `timestamp` | `asOf`, double-quoted |

- **Body.** A level-one heading with `asset`, one fixed sentence, a two-column table, and the
  object as a fenced `json` block, printed with two-space indentation in member order (R6). The
  JSON block is the record; the table is for people. Table rows, in order:

  | Row | Value; `unknown` when the source is null |
  |---|---|
  | Platform | `<provider> <kind>` |
  | As of | `asOf` |
  | Created | `createdAt` |
  | Last read | `lastReadAt` |
  | Last written | `lastWrittenAt` |
  | Read coverage | `FULL since <readCoverageSince>`, `PARTIAL`, `NONE` or `unknown`; then `: ` and the reasons joined by `; ` when there are any |
  | Size | `<sizeBytes> bytes at <sizeObservedAt>` |
  | Retention | `<status>`; then `, <basis> <days> days` or `, FIXED_DATE <fixedDate>` when `basis` is set; then `, expires <expiresAt>` when set; then `, source <source>` when set |
  | Legal hold | `true` or `false` |
  | Disposition | `<action> at <executedAt> by <executedBy>` |

  A `|` inside a value is written `\|`.

Example, `lifecycle-state/6fa1a9936837aaaa5c9f0fc1318140a4dbf75bc8ffb97ce244311bf349f32028.md`
for the Azure container:

````markdown
---
type: lifecycle-state
title: "az://examplestore/archive-2019"
description: "Lifecycle State of AZURE BLOB_CONTAINER az://examplestore/archive-2019 as of 2026-10-31T12:00:00Z."
resource: "az://examplestore/archive-2019"
tags:
  - lifecycle-state
  - azure
  - blob-container
  - read-coverage-unknown
  - retention-unknown
  - disposition-unknown
timestamp: "2026-10-31T12:00:00Z"
---

# az://examplestore/archive-2019

A value of unknown means the engine has no evidence. It never means never.

| Field | Value |
|---|---|
| Platform | AZURE BLOB_CONTAINER |
| As of | 2026-10-31T12:00:00Z |
| Created | 2019-02-01T10:00:00Z |
| Last read | unknown |
| Last written | unknown |
| Read coverage | unknown: permission Microsoft.Insights/diagnosticSettings/read was refused |
| Size | unknown |
| Retention | UNKNOWN, LAST_ACCESSED 730 days, source LABEL_DEFAULT |
| Legal hold | false |
| Disposition | unknown |

```json
{
  "specVersion": "0.1",
  "asset": "az://examplestore/archive-2019",
  "platform": {
    "provider": "AZURE",
    "kind": "BLOB_CONTAINER"
  },
  "asOf": "2026-10-31T12:00:00Z",
  "createdAt": "2019-02-01T10:00:00Z",
  "lastReadAt": null,
  "lastWrittenAt": null,
  "readCoverage": null,
  "readCoverageReasons": [
    "permission Microsoft.Insights/diagnosticSettings/read was refused"
  ],
  "readCoverageSince": null,
  "sizeBytes": null,
  "sizeObservedAt": null,
  "retention": {
    "status": "UNKNOWN",
    "basis": "LAST_ACCESSED",
    "days": 730,
    "fixedDate": null,
    "expiresAt": null,
    "source": "LABEL_DEFAULT"
  },
  "legalHold": false,
  "disposition": null,
  "provenance": [
    {
      "field": "createdAt",
      "channel": "CRAWLER",
      "bindingIds": [],
      "eventIds": [
        "78af4ac4a17dabd70b3c40597431af482b0965190a917b48e8cfee70bea96df1"
      ]
    },
    {
      "field": "readCoverage",
      "channel": "CRAWLER",
      "bindingIds": [],
      "eventIds": [
        "693b8502f97d8c49dec9e422c0dd4f055491fb25843cbf198c64140c965a8b93"
      ]
    }
  ]
}
```
````

The `timestamp` key follows OKF v0.1. The OKF repository's current draft (0.2) moves the generation
time to `generated.at` and lets consumers fall back to `timestamp`; this mapping stays on v0.1
until a later version of this specification adopts 0.2.

### 8.3 AuthZEN resource attributes

In an AuthZEN Access Evaluation request, the asset is the `resource`:

| AuthZEN member | Value |
|---|---|
| `resource.type` | `asset` |
| `resource.id` | `asset`, the canonical URI |
| `resource.properties.lifecycleState` | the Lifecycle State object, unchanged |

- A decision point that profiles `resource.id` as another identifier sets `id` to that identifier
  and puts the canonical URI in `resource.properties.assetUri`. The reference engine's decision
  point does this: its `resource.id` is an internal asset id ([enforcement-contract.md](enforcement-contract.md)
  section 2).
- A decision point that reads `lifecycleState` MUST apply R1, R2 and R5: deny a destructive
  action on a held asset, and never read a null time as "never".

Example, a delete request for the held S3 bucket of
`valid-s3-bucket-partial-coverage-legal-hold.json`:

```json
{
  "subject": { "type": "user", "id": "alice@example.com" },
  "action": { "name": "delete" },
  "resource": {
    "type": "asset",
    "id": "s3://example-finance-raw",
    "properties": {
      "lifecycleState": {
        "specVersion": "0.1",
        "asset": "s3://example-finance-raw",
        "platform": { "provider": "AWS", "kind": "S3_BUCKET" },
        "asOf": "2026-10-31T12:00:00Z",
        "createdAt": "2021-04-12T17:03:22Z",
        "lastReadAt": "2026-10-02T14:27:09Z",
        "lastWrittenAt": "2026-10-29T22:10:41Z",
        "readCoverage": "PARTIAL",
        "readCoverageReasons": ["CloudTrail data events cover a prefix subset"],
        "readCoverageSince": null,
        "sizeBytes": 1879048192000,
        "sizeObservedAt": "2026-10-30T00:00:00Z",
        "retention": {
          "status": "ON_HOLD",
          "basis": "LAST_ACCESSED",
          "days": 365,
          "fixedDate": null,
          "expiresAt": "2027-10-02T14:27:09Z",
          "source": "POLICY"
        },
        "legalHold": true,
        "disposition": null,
        "provenance": [
          {
            "field": "createdAt",
            "channel": "AUDIT_LOG:AWS_CLOUDTRAIL",
            "bindingIds": ["aws-cloudtrail-111122223333"],
            "eventIds": ["f63276b4bd50e32d064cfeacfe3bb99c4d0299105fb3d4422c222b530741a9d4"]
          },
          {
            "field": "lastReadAt",
            "channel": "AUDIT_LOG:AWS_CLOUDTRAIL",
            "bindingIds": ["aws-cloudtrail-111122223333"],
            "eventIds": ["67e59681a973efd772c689c8182b6bd66dbb14f2f9ab9d8f283d22ed8f9e6cfd"]
          },
          {
            "field": "lastWrittenAt",
            "channel": "AUDIT_LOG:AWS_CLOUDTRAIL",
            "bindingIds": ["aws-cloudtrail-111122223333"],
            "eventIds": ["689b7a0dc1f82a51d59bfb4bb411ded44aab80a3bbc73ffccf8c4f096484825c"]
          },
          {
            "field": "readCoverage",
            "channel": "CRAWLER",
            "bindingIds": [],
            "eventIds": ["e9fb7ff333a5c55af72b3f907aa03ea99b6e4adc216e0ff4477def37659305d7"]
          },
          {
            "field": "sizeBytes",
            "channel": "INVENTORY",
            "bindingIds": [],
            "eventIds": ["7791abc808dbfc0fe04ea654b85c2267444e75ed27de7e299f7121220af56417"]
          }
        ]
      }
    }
  },
  "context": {}
}
```

Under R5 a conforming decision point denies this request: `legalHold` is true.

### 8.4 Unity Catalog governed tags

Unity Catalog tags are key and value strings on a securable object. Governed tags are account-level
tags whose values come from a defined list. The limits used here, from the Databricks
documentation read on 2026-10-31 (governed tags page updated 2026-10-12; tags page dated
2026-10-26):

- Keys and values are case sensitive and at most 256 characters.
- Tag keys must not contain `. , - = / :`.
- Governed keys and values must not contain `* . / < > % & ? \ =` or ASCII control characters.
- No leading or trailing spaces. At most 50 tags on one securable object. At most 500 allowed
  values per governed tag.

So every key below uses only `a` to `z`, `0` to `9` and `_`, and every governed value uses only
letters, digits and `_`.

**Governed tags.** These fields have a closed set of values, so an account defines each as a
governed tag with exactly these allowed values:

| Key | Allowed values | From |
|---|---|---|
| `lifecycle_spec_version` | `v0_1` | `specVersion` |
| `lifecycle_read_coverage` | `FULL`, `PARTIAL`, `NONE`, `UNKNOWN` | `readCoverage`; null is `UNKNOWN` |
| `lifecycle_retention_status` | `UNKNOWN`, `NO_POLICY`, `RETAINED`, `DUE`, `ON_HOLD` | `retention.status` |
| `lifecycle_legal_hold` | `true`, `false` | `legalHold` |
| `lifecycle_disposition` | `archive`, `delete`, `UNKNOWN` | `disposition.action`; null is `UNKNOWN` |

**Time tags.** Times have no closed set, so they cannot be governed tags, whose values come from
a fixed list. They are ordinary tags. A workspace MUST NOT create a governed tag with these keys:
doing so would govern the existing assignments and reject new times.

| Key | Value |
|---|---|
| `lifecycle_as_of` | `asOf` |
| `lifecycle_created_at` | `createdAt`, or `UNKNOWN` |
| `lifecycle_last_read_at` | `lastReadAt`, or `UNKNOWN` |
| `lifecycle_last_written_at` | `lastWrittenAt`, or `UNKNOWN` |
| `lifecycle_retention_expires_at` | `retention.expiresAt`, or `UNKNOWN` |

All ten tags are always set, so a missing tag never stands in for unknown. Tags carry no
provenance; a reader that needs it reads the object. Unity Catalog applies tags only to Unity
Catalog securables, so this mapping is for assets of provider `UNITY`.

Example, a Unity Catalog table whose Lifecycle State has `asOf` `2026-10-31T12:00:00Z`,
`createdAt` `2025-05-20T11:02:13Z`, `lastReadAt` `2026-10-28T16:45:00.250Z`, `lastWrittenAt`
`2026-10-30T03:00:12Z`, `readCoverage` null, `retention` `RETAINED` on a `LAST_MODIFIED` rule of
90 days (`expiresAt` `2027-01-28T03:00:12Z`), `legalHold` false and `disposition` null:

| Key | Value |
|---|---|
| `lifecycle_spec_version` | `v0_1` |
| `lifecycle_read_coverage` | `UNKNOWN` |
| `lifecycle_retention_status` | `RETAINED` |
| `lifecycle_legal_hold` | `false` |
| `lifecycle_disposition` | `UNKNOWN` |
| `lifecycle_as_of` | `2026-10-31T12:00:00Z` |
| `lifecycle_created_at` | `2025-05-20T11:02:13Z` |
| `lifecycle_last_read_at` | `2026-10-28T16:45:00.250Z` |
| `lifecycle_last_written_at` | `2026-10-30T03:00:12Z` |
| `lifecycle_retention_expires_at` | `2027-01-28T03:00:12Z` |

### 8.5 Google Knowledge Catalog aspect

Knowledge Catalog (formerly Dataplex Universal Catalog) attaches typed metadata to an entry as an
aspect, an instance of an aspect type. The engine uses one custom aspect type, id
`lifecycle-state`, whose metadata template is a `record` with the fields below. The template
types are those the AspectType reference lists: `string`, `int`, `bool`, `double`, `datetime`
(RFC 3339 UTC "Zulu"), `enum`, `array`, `map`, `record`.

| Field | Template type | From | When null |
|---|---|---|---|
| `specVersion` | `string`, required | `specVersion` | never null |
| `asset` | `string`, required | `asset` | never null |
| `provider` | `string`, required | `platform.provider` | never null |
| `kind` | `string`, required | `platform.kind` | never null |
| `asOf` | `datetime`, required | `asOf` | never null |
| `createdAt` | `datetime` | `createdAt` | omitted |
| `lastReadAt` | `datetime` | `lastReadAt` | omitted |
| `lastWrittenAt` | `datetime` | `lastWrittenAt` | omitted |
| `readCoverage` | `enum` `FULL`, `PARTIAL`, `NONE`, `UNKNOWN`, required | `readCoverage` | `UNKNOWN` |
| `readCoverageReasons` | `array` of `string` | `readCoverageReasons` | empty array |
| `readCoverageSince` | `datetime` | `readCoverageSince` | omitted |
| `sizeBytes` | `string` of decimal digits | `sizeBytes` | omitted |
| `sizeObservedAt` | `datetime` | `sizeObservedAt` | omitted |
| `retentionStatus` | `enum` `UNKNOWN`, `NO_POLICY`, `RETAINED`, `DUE`, `ON_HOLD`, required | `retention.status` | never null |
| `retentionBasis` | `enum` `CREATED`, `LAST_MODIFIED`, `LAST_ACCESSED`, `FIXED_DATE` | `retention.basis` | omitted |
| `retentionDays` | `int` | `retention.days` | omitted |
| `retentionFixedDate` | `datetime` | `retention.fixedDate` | omitted |
| `retentionExpiresAt` | `datetime` | `retention.expiresAt` | omitted |
| `retentionSource` | `string` | `retention.source` | omitted |
| `legalHold` | `bool`, required | `legalHold` | never null |
| `dispositionAction` | `enum` `archive`, `delete` | `disposition.action` | omitted |
| `dispositionExecutedAt` | `datetime` | `disposition.executedAt` | omitted |
| `dispositionExecutedBy` | `enum` `native-rule`, `engine` | `disposition.executedBy` | omitted |
| `dispositionEvidence` | `array` of `string` | `disposition.evidence` | omitted |
| `provenance` | `array` of `record` (`field`, `channel`, `bindingIds`, `eventIds`; `channel` omitted when null) | `provenance` | never null |

- An omitted field means unknown (R1), never a default.
- `sizeBytes` is a decimal string because the reference does not state the width of `int`, and a
  size can exceed 2^31.
- The aspect key on the entry is `<project>.<location>.lifecycle-state`, the form the Entry
  reference gives for an aspect attached directly to an entry.

Example, the `aspects` member of the entry of the BigQuery table of
`valid-bigquery-table-full-coverage-expired.json`, with the aspect type in project
`example-governance`, location `global`:

```json
{
  "aspects": {
    "example-governance.global.lifecycle-state": {
      "data": {
        "specVersion": "0.1",
        "asset": "bq://example-analytics/sales/orders_2023",
        "provider": "GCP",
        "kind": "BIGQUERY_TABLE",
        "asOf": "2026-10-31T12:00:00Z",
        "createdAt": "2023-01-09T08:15:02.114Z",
        "lastReadAt": "2026-08-14T09:12:44.512Z",
        "lastWrittenAt": "2024-12-31T23:40:10Z",
        "readCoverage": "FULL",
        "readCoverageReasons": [
          "BigQuery data access logs are on for the project",
          "cross-project job reads verified"
        ],
        "readCoverageSince": "2026-03-02T06:41:00Z",
        "sizeBytes": "52428800000",
        "sizeObservedAt": "2026-09-29T06:00:00Z",
        "retentionStatus": "DUE",
        "retentionBasis": "FIXED_DATE",
        "retentionFixedDate": "2026-09-30T00:00:00Z",
        "retentionExpiresAt": "2026-09-30T00:00:00Z",
        "retentionSource": "PROVIDER",
        "legalHold": false,
        "dispositionAction": "delete",
        "dispositionExecutedAt": "2026-09-30T00:03:51.208Z",
        "dispositionExecutedBy": "native-rule",
        "dispositionEvidence": [
          "a819ac1bb7f43a93c9cc6a1661df8f3edeff9afc62a055e11bd726db3cb531fb"
        ],
        "provenance": [
          { "field": "createdAt", "channel": "AUDIT_LOG:GCP_CLOUD_AUDIT", "bindingIds": ["gcp-audit-example-analytics"], "eventIds": ["9609fd0c0942b0184a2af459da7ef6e72c82104dc61f903b07d7ccac900472c2"] },
          { "field": "lastReadAt", "channel": "AUDIT_LOG:GCP_CLOUD_AUDIT", "bindingIds": ["gcp-audit-example-analytics"], "eventIds": ["4516d1fdb7a68be1de33a8d740b54c4f568713db67ab9e8a8b82bf37d33194f6"] },
          { "field": "lastWrittenAt", "channel": "AUDIT_LOG:GCP_CLOUD_AUDIT", "bindingIds": ["gcp-audit-example-analytics"], "eventIds": ["b40b2e6229e4d27d74095f0b59bcd69b4fe558fcf5a7ee259c7c98bd3c1b5305"] },
          { "field": "readCoverage", "channel": "CRAWLER", "bindingIds": [], "eventIds": ["fd0417ab2ca4f8e54ae837f3814ae828e85cd242570fcc9d81abdff47177003e"] },
          { "field": "sizeBytes", "channel": "CRAWLER", "bindingIds": [], "eventIds": ["ddaf2b01c4013b635ab0b5279ac31b17cff647d84817d4b9e5f4eaef42cae5d3"] },
          { "field": "retention", "channel": "CRAWLER", "bindingIds": [], "eventIds": ["ddaf2b01c4013b635ab0b5279ac31b17cff647d84817d4b9e5f4eaef42cae5d3"] },
          { "field": "disposition", "channel": "AUDIT_LOG:GCP_CLOUD_AUDIT", "bindingIds": ["gcp-audit-example-analytics"], "eventIds": ["a819ac1bb7f43a93c9cc6a1661df8f3edeff9afc62a055e11bd726db3cb531fb"] }
        ]
      }
    }
  }
}
```

`retentionDays` is omitted because the rule is `FIXED_DATE`.

## 9. What this specification does not cover

- **No catalog.** It defines a record, not a store, an index, a search API or a registry of assets.
  Where objects are kept and how they are found is up to each system.
- **No semantics.** It says nothing about what the data means: no business glossary, no
  classification, no labels, no sensitivity. Labels have their own specification
  ([label.md](label.md)).
- **No lineage graph.** It describes one asset. Edges between assets and the jobs that connect them
  are out of scope. OpenLineage and the Asset Descriptor lineage section cover them.
- **No policy language.** It defines no rules, conditions or actions. Retention rules are inputs
  kept at their source; this record holds only the result. Policies are written in a policy
  language ([policy-dsl.md](policy-dsl.md)) or a decision point's own format.
- No transport, no API and no signing. How an object travels and how a reader trusts its producer
  are left to the systems that exchange it.

## 10. Known limits in 0.1

- `executedBy` has two values. A delete by a person or by another tool has no value, so its
  `disposition` is null even when the event is known. A later version adds a value for it.
- The schema cannot check that `expiresAt` equals `fixedDate` for a decided `FIXED_DATE` rule, the
  ordering rules of R6, or that ids name real events. Those are conformance rules, not schema
  rules.
- A held asset may carry an `archive` disposition from before the hold. Without the hold's start time
  a reader cannot prove the order from this record alone; a later version can add it.
- Read coverage observations exist in the reference engine for S3, GCS, BigQuery and Azure Blob
  assets only. Other kinds report null coverage until an observer exists for them.
- Observation times are the time the observer read the provider state (R3). They are evidence of
  provider state at that time, not a provider-stamped event time.

## 11. Relation to the Asset Descriptor

The object narrows the descriptor; it never contradicts it. Where they differ in form:

| This specification | Asset Descriptor | Why |
|---|---|---|
| `retention.fixedDate` | no field; the expiry is read from the rule's source on every run, and `retentionExpiresAt` holds it for a decided `FIXED_DATE` rule | a reader needs the rule's own date, not only the evaluation result |
| `legalHold` true for any `ON_HOLD` | `legalHold` is the API hold only; a hold label gives `ON_HOLD` with `legalHold` false | one boolean that always blocks disposition |
| `disposition` object | `status` and `lifecycleEventTime`; no executor or evidence field | a disposition must name its evidence |
| `provenance.eventIds` | evidence channels only; event ids live in the engine's processed-event store | the object must be checkable against the logs |
| `readCoverage` null | `readCoverage` NULL; the activity report filter spells it `UNKNOWN` | null is the one spelling of unknown here |
| not carried | `retentionPolicyId`, `lastPermissionChangeAt`, `readCoverageObservedAt`, status reasons | out of scope for 0.1 |
