# DlapLogEvent v1 specification

Status: v1, revised for ADR-0045 (log layer). Supersedes the 2026-07 text.

| Artifact | Path |
|---|---|
| Schema of record | [`da-protos/src/main/proto/dlap/log/v1/log_event.proto`](../../da-protos/src/main/proto/dlap/log/v1/log_event.proto) |
| Codec, ids, consumer rules | [`da-protos/src/main/java/com/dlap/common/logevent/`](../../da-protos/src/main/java/com/dlap/common/logevent/) |
| Wire-contract baseline | [`da-protos/src/test/resources/com/dlap/common/logevent/log_event_v1.contract.txt`](../../da-protos/src/test/resources/com/dlap/common/logevent/log_event_v1.contract.txt) |
| Topic names | `DlapConstants.KafkaTopic` |

This document states what the fields mean and the rules producers and
consumers share. Where it describes code that does not exist yet, it names
the package that builds it.

## 1. Why this exists

Each cloud emits audit and activity records in its own shape: CloudTrail
JSON, GCP Cloud Audit Logs, Azure Activity, Databricks system tables. DLAP
reconstructs lifecycle truth from those logs, so every downstream service
(discovery, catalog, label, policy, archive, delete, compliance, reporting)
reads one canonical stream instead of chasing provider schemas.
`DlapLogEvent` is that stream's record: operation, actor, target, result,
three clocks, the provider's original record, and how DLAP parsed it.

## 2. Transport

| Item | Rule |
|---|---|
| Topic | `dlap.log.event.normalized` (`DlapConstants.KafkaTopic.LOG_EVENT_NORMALIZED`) |
| Key | `target_resource.canonical_uri`, else `target_resource.id`, else `event_id` (`LogEventSemantics.partitionKey`). Events for one asset share a partition. |
| Value | UTF-8 proto3 JSON object, written by `DlapLogEventCodec.encode` and sent with a `StringSerializer` or `ByteArraySerializer`. Every producer does (SPINE-01). |
| Delivery | A producer sends the record and waits for the broker's acknowledgement before it acknowledges its own input or moves a cursor (section 2.1). |
| Size | A record whose event would exceed the topic's `max.message.bytes` (Kafka default 1 MiB) is quarantined as `OVERSIZE_RECORD`. |
| Reserved header | `dlap-ingest-mode`: `STREAM`, `BACKFILL` or `REPLAY`. With `REPLAY`, da-logingest republishes an `event_id` it already stored. |

**One encoding.** The writer prints lowerCamelCase field names, every
non-message field including default values (so `changeType` is always
present), map keys sorted, no insignificant whitespace. An empty
`schema_version` is written as `v1`. Binary protobuf is deferred.

**The legacy double encoding (retired by SPINE-01).** Before SPINE-01 the three
active producers serialized the event to a String and sent that String through
Spring Kafka's `JsonSerializer`, which wraps it in quotes: the value was a JSON
string whose text is the event, and the event was serialized twice. The router
forwarded a parsed tree, so the typed topics always carried single-encoded
objects. SPINE-01 switched all three producers at once to the single encoding:
each prints the event once with `DlapLogEventCodec.encode` and sends the text
through a String-valued producer, so the value on the topic is the JSON object
itself. The only readers of `dlap.log.event.normalized` are the router
(DISC-02) and the catalog's enrichment listener (CAT-02), and both adopted the
codec first, so a rolling deploy never meets a reader that cannot parse the new
form. `DlapLogEventCodec.decode` still reads the quoted form (section 12) for
records written before the switch and for older producer builds; the router
counts it as `legacy`.

A producer that builds its event as a flat map or tree (the legacy REST path,
the crawler bridge, the log-analyzer publisher) reads it through
`DlapLogEventCodec.decode` and prints the result with `encode`, so what reaches
the topic is canonical whatever shape the producer built: enum names are
canonical, the `source` marker is `origin` plus `source_format`, and an unset
string is written as `""` (which every reader takes as absent). A value the
contract cannot hold fails the publish; it is not sent.

### 2.1 Delivery

`KafkaTemplate.send` is asynchronous. Before SPINE-01 no producer looked at the
returned future: a broker outage left rows in `log_events` (or crawler events
acknowledged), the stream cursor moved on, and the events never reached the
spine. Now every producer waits for the acknowledgement:

| Producer | Waits in | On failure |
|---|---|---|
| da-logingest, both pipelines | `LogEventPublisher.publish`, inside the transaction that stores the row | Throws `LogEventPublishException`. The row rolls back, so a retry is not mistaken for a stored duplicate and never published; `StreamRunner` stops the partition poll and leaves its cursor where it was. |
| Crawler bridge, log-analyzer publisher | `SpineEvents.sendAndAwait` | Throws `SpinePublishException`, so the Kafka listener that called the processor can retry the record and dead-letter it instead of acknowledging it with its event lost. |

The wait is bounded by `dlap.logingest.publish-timeout` (da-logingest) and
`dlap.discovery.spine-publish-timeout` (da-discovery), both 30 s by default. A
timeout can leave the record in flight, so a later duplicate is possible; the
`event_id` is deterministic and every consumer is idempotent per `event_id`
(section 5.4), so it is harmless.

The wait costs one broker round trip per event, so a producer thread is paced by
the broker and not by the producer's batching. Measured on 2026-10-20 against
a single local Kafka broker in Docker Desktop (acks=all, idempotent, 2224 byte
events, 2000 sequential sends): 764 events/s (1.31 ms per event) with
`linger.ms=0`, which da-logingest's publisher sets; 62 events/s (16 ms per
event) with the Kafka 4 default of `linger.ms=5` (one run, on another stack of
the same compose files); and
24,933 events/s when the same 2000 sends are issued first and awaited together.
The log-event store already costs several database round trips per event, so the
await does not change the order of magnitude of a stream poll, but a 10 million
event backfill on one thread is hours, not minutes. Awaiting per batch would be
faster and is not safe while the store commits one row per event (a failure
after the commit would leave a stored row that is never published), so it needs
the store and the ingest service to change together.

**Producers today**

| Producer | Path | Notes |
|---|---|---|
| da-logingest | `LogEventPublisher.java` (both pipelines) | Legacy pipeline (`LogIngestServiceImpl`): no `origin`, `source_format`, `event_category` or `parse_status`. Registry pipeline: all of them. |
| Crawler bridge | `CrawlerAssetSpineBridge.java` | Builds `source: "crawler"` and `targetResource.technicalMetadata`; the codec prints `origin = ORIGIN_CRAWLER` and `source_format = DLAP_CRAWLER/resource-event`. `eventId` may be `uri:CHANGE:ts`. |
| Log-analyzer publisher | `LogAnalyzerSpinePublisher.java` | Builds `source: "loganalyzer"`; the codec prints `origin = ORIGIN_CLOUD_LOG` and `source_format = DLAP_LOGANALYZER/resource-change`. `changeType` may be `UNKNOWN`. |
| Dormant analyzers (AWS, Oracle) | `AWSLogAnalyzer.java:295-299`, `OracleLogAnalyzer.java:296-300` | Only with `cloud.provider.aws.enabled=true` or `cloud.provider.oci.enabled=true`, when an analysis runs; the scheduled call in `LogAnalyzerCallable.java:100-118` is commented out. Sends a da-discovery `LogEvent` object (`LogEvent.java:34-41`) through the JSON template (a JSON object, not the contract shape; outside the producer contract test): `eventId`, `eventTimestamp` (the analyzer's own clock), `source: "LogAnalyzer"`, `cloudProvider`, `accountId`, `payload`. No top-level `changeType` or `eventTime`, so it changes no state (section 12). ADR-0045 deletes this stack (`da-discovery/.../log/**`) once the parser registry covers its sources. |

## 3. Three clocks and the business-time rule

| Clock | Field | Meaning | Used for |
|---|---|---|---|
| Event time | `event_time` | When the action happened, by the provider's clock (CloudTrail `eventTime`, GCP `timestamp`, Azure `eventTimestamp` or `time`, Databricks `event_time`). | All business state |
| Observed time | `observed_time` | When the provider received or delivered the record (GCP `receiveTimestamp`, CloudTrail log-file `LastModified`). Optional. | Delay metrics, late-arrival analysis |
| Ingest time | `ingest_time` | When DLAP normalized the record. | Operations only: lag, processing windows, log-row retention |

**Business-time rule.** Every business field (`last_accessed_at`,
`last_modified_at`, `last_permission_change_at`, `cloud_created_at`,
`lifecycle_event_time`, daily usage buckets, retention inputs) derives from
`event_time` or from a resource time the event carries
(`target_resource.created_time`). Never from `ingest_time`, never from a
consumer's wall clock. A source may name one documented fallback provider
clock (GCP: `receiveTimestamp` when `timestamp` is absent); the parser then
adds `PARSE_WARNING_EVENT_TIME_FALLBACK`. With no provider clock the record
is skipped and counted, never stamped with DLAP's time. An event without
`event_time` changes no state (`LogEventSemantics.mayChangeState`).

**Monotonic state.** `last_*` fields take the maximum event time seen;
lifecycle transitions apply only when `event_time >= lifecycle_event_time`.
Late events therefore never move state backwards. CAT-02 implements this in
the catalog.

## 4. Field reference

The `.proto` comments are canonical. Fields 1 to 13 are the original v1
fields; 14 and up were added by ADR-0045.

### 4.1 DlapLogEvent

| # | Field | Rule |
|---|---|---|
| 1 | `event_id` | Idempotency id, section 5. |
| 2 | `event_time` | Provider event time, section 3. |
| 3 | `ingest_time` | DLAP normalization time, section 3. |
| 4 | `source_provider` | `GCP`, `AWS`, `AZURE`, `OCI`, `UNITY`, `DATABRICKS`, `SNOWFLAKE`. |
| 5 | `source_service` | Provider service name, for example `storage.googleapis.com`, `s3.amazonaws.com`. |
| 6 | `change_type` | Routing key onto the typed lifecycle topics (ADR-0021): `CREATE`, `UPDATE`, `DELETE`, `ACCESS`, `PERMISSION`, `ARCHIVE`. `CHANGE_TYPE_UNSPECIFIED` is never routed. |
| 7 | `operation` | Provider operation name, exactly as the provider spells it. |
| 8 | `result_status` | `SUCCESS`, `FAILURE`, `PENDING`. A denied or failed call is `FAILURE` (for example GCP `status.code != 0` or `authorizationInfo.granted=false`; CloudTrail `errorCode` present). |
| 9 | `principal` | Who acted. `id` is the stable principal (AWS: `sessionIssuer.arn` for AssumedRole, not the access key id). |
| 10 | `target_resource` | What the operation acted on. State-changing consumers act on it alone. |
| 11 | `request_metadata` | Caller IP, user agent, provider request id, trace id. |
| 12 | `raw_event` | The provider record, section 6. Consumers never mutate it. |
| 13 | `schema_version` | Always `v1`. |
| 14 | `source_format` | `<SOURCE_KIND>/<envelope>`, for example `AWS_CLOUDTRAIL/s3-file`, `GCP_CLOUD_AUDIT/api-proto`. Source kinds are open upper snake case strings (decision D1), envelopes lower kebab case; neither contains `/` (`SourceFormats`). Empty on the legacy pipeline. |
| 15 | `source_schema_version` | Version detected on the record: CloudTrail `eventVersion` (`1.11`), GCP payload type (`auditlog`, `bq-auditmetadata`, `bq-auditdata-legacy`), Databricks `version`. |
| 16 | `parser_version` | `<parser-id>@<semver>`. |
| 17 | `binding_id` | The configured binding (one account, project, subscription or workspace) the record came through. |
| 18 | `native_event_id` | The native key before hashing, section 5. |
| 19 | `observed_time` | Section 3. |
| 20 | `event_category` | Section 4.3. |
| 21 | `origin` | `ORIGIN_CLOUD_LOG` (live polling or a log sink), `ORIGIN_CRAWLER`, `ORIGIN_REPLAY` (stored events, fixtures, demo seeding), `ORIGIN_BACKFILL`. Unspecified on the legacy pipeline. |
| 22 | `parse_status` | `PARSE_STATUS_FULL`, `PARSE_STATUS_WARNINGS`, `PARSE_STATUS_PARTIAL`. Unspecified on the legacy pipeline. |
| 23 | `related_resources` | Further resources the operation touched, each with a `relation`, section 4.4. |
| 24 | `supersedes_event_id` | `event_id` this event replaces (CloudTrail addendum `UPDATED_DATA`: `event_id` computed from `originalEventID`). Consumers apply the replacement and ignore the superseded event. |
| 25 | `shared_event_id` | CloudTrail `sharedEventID`: copies of one action delivered to several accounts share it. Usage dedup, section 5.4. |
| 26 | `parse_warnings` | Non-fatal findings, section 4.5. |

### 4.2 TargetResource

| # | Field | Rule |
|---|---|---|
| 1 | `kind` | Provider resource kind (`AWS::S3::Bucket`, `gcs_bucket`) or, from crawlers, the DLAP service type. |
| 2 | `id` | Provider-native id as the source names it. Kept for audit; joins use `canonical_uri`. |
| 3 to 6 | `name`, `project`, `region`, `resource_path` | Provider-native forms. Regions are never re-based across providers. |
| 7 | `labels` | Resource tags. |
| 8 | `canonical_uri` | Canonical asset id from `AssetIdNormalizer` (ADR-0047, ID-01), the join key with the catalog's `asset_uri`. Empty when unresolvable. |
| 9 | `created_time` | Provider creation time when the source knows it (crawler `createdAt`, inventory `Creation-Time`). |
| 10 | `size_bytes` | Size in bytes as observed. Explicit presence: absent means unknown, `0` is a real size. Printed as a JSON string (proto3 int64). |
| 11 | `technical_metadata` | Observed technical metadata (storage class, versioning, encryption, public access), string values. The catalog stores it in `technical_metadata`. |
| 12 | `relation` | Only inside `related_resources`, section 4.4. |

### 4.3 change_type and event_category

`change_type` routes; `event_category` says what kind of fact the event
records and which business fields it may move.

| Category | Meaning | May update | Typical change_type |
|---|---|---|---|
| `EVENT_CATEGORY_MANAGEMENT` | Control-plane operation on configuration or metadata | `lifecycle_event_time`, status | `CREATE`, `UPDATE`, `DELETE`, `ARCHIVE` |
| `EVENT_CATEGORY_DATA_READ` | Recorded read of content, one request | `last_accessed_at`, usage counts | `ACCESS` |
| `EVENT_CATEGORY_DATA_WRITE` | Recorded write of content | `last_modified_at` | `CREATE`, `UPDATE`, `DELETE` |
| `EVENT_CATEGORY_PERMISSION` | Change to who can access | `last_permission_change_at` | `PERMISSION` |
| `EVENT_CATEGORY_DATA_READ_OBSERVED` | A read known to have happened at or before `event_time`, taken from provider state (an inventory or metadata last-access time), not from a per-request record | `last_accessed_at` only; never a usage count | `ACCESS` |

Only `SUCCESS` events move business fields. A metadata read (list,
describe, get-policy) is `MANAGEMENT`, not a data read. Parsers classify
from an explicit table keyed by service and operation; operations missing
from the table fall back to verb heuristics and carry
`PARSE_WARNING_HEURISTIC_CLASSIFICATION`.

**Legacy events** carry `EVENT_CATEGORY_UNSPECIFIED`. Consumers derive the
category from `change_type` (`LogEventSemantics.effectiveCategory`):
`ACCESS` is `DATA_READ`, `PERMISSION` is `PERMISSION`, `CREATE`, `UPDATE`,
`DELETE` and `ARCHIVE` are `MANAGEMENT`. Reports mark derived categories as
heuristic.

### 4.4 related_resources

| Relation | Meaning |
|---|---|
| `RESOURCE_RELATION_PARENT` | Container of the target: the bucket of an object, the dataset of a table. Consumers may roll target activity up to it. |
| `RESOURCE_RELATION_SOURCE` | Data the operation read (lineage upstream). |
| `RESOURCE_RELATION_DESTINATION` | Data the operation wrote (lineage downstream). When an event has `SOURCE` entries and no `DESTINATION` entry, the target is downstream. |
| `RESOURCE_RELATION_OTHER` | Referenced without a read or write role. |

A job event puts the job in `target_resource` and both sides of the lineage
in `related_resources`. Related resources never receive state changes from
the event; a parser that needs a read recorded against each table emits one
event per table (section 5.3).

### 4.5 parse_status and parse_warnings

- `PARSE_STATUS_FULL`: every declared field mapped.
- `PARSE_STATUS_WARNINGS`: mapped, with warnings: `UNKNOWN_FIELDS`, `NEWER_MINOR` (minor version above the parser's highest, parsed as the highest), `HEURISTIC_CLASSIFICATION`, `PROVIDER_TRUNCATED` (CloudTrail omits `requestParameters` above 100 KB), `VERSION_PIN_MISMATCH` (binding allows parsing on mismatch), `EVENT_TIME_FALLBACK`.
- `PARSE_STATUS_PARTIAL`: minimal pass-through of a quarantined record when provider, event time, operation and native id are extractable. `change_type` is unspecified. The audit fact is kept and reportable; it never mutates lifecycle state.

## 5. Idempotency

### 5.1 event_id

```
event_id = lowercase_hex(SHA-256(UTF-8(source_provider_name + "|" + native_key)))
```

`source_provider_name` is the `SourceProvider` enum name (`AWS`, `GCP`, ...).
This is byte-for-byte the formula da-logingest has used since v0.1
(`LogIngestServiceImpl.java:143-159` with a non-blank `requestId`), so ids
the legacy pipeline stored stay valid after the parser registry takes over.
The same native key from two envelopes (CloudTrail LookupEvents and S3
files; GCP API and a GCS sink) gives the same `event_id`.
`LogEventIds.eventId` is the one implementation.

### 5.2 Native keys

The primary audit stream of a provider uses the bare native id. Every other
source kind prefixes its key with `<SOURCE_KIND>|`, so keys of different
sources of one provider cannot collide (`LogEventIds.namespacedNativeKey`).

| Source kind | Native key | Notes |
|---|---|---|
| `AWS_CLOUDTRAIL` (all envelopes) | `eventID` | A GUID; files may repeat it. `sharedEventID` is a link, not the key. |
| `GCP_CLOUD_AUDIT` (all envelopes) | `logName\|timestamp\|insertId` | `logName` is the full resource name as in LogEntry JSON, `projects/{id}/logs/{url-encoded log id}` (or `organizations/`, `folders/`, `billingAccounts/`); a transport that returns the short log id rebuilds the full name. `timestamp` is rendered by `java.time.Instant#toString` (UTC, 0, 3, 6 or 9 fraction digits). `LogEventIds.gcpAuditNativeKey`. |
| `GCP_BQ_JOBS_VIEW` | `GCP_BQ_JOBS_VIEW\|project\|location\|job_id` | A JOBS row and the audit entry of the same job are distinct events. |
| `AZURE_ACTIVITY` | `eventDataId` (REST envelope); resource-log envelope: `AZURE_ACTIVITY\|` + lowercased `resourceId` + `\|` + `operationName` upper case + `\|` + `time` + `\|` + `correlationId` + `\|` + `resultType` | Resource-log uniqueness [unverified]; the two envelopes of one event do not dedupe. |
| `AZURE_STORAGE_RESOURCE_LOG` | `AZURE_STORAGE_RESOURCE_LOG\|correlationId\|operationCount` | Uniqueness [unverified]. |
| `DATABRICKS_AUDIT` | `event_id` (system table); delivered files, which lack `event_id`: `DATABRICKS_AUDIT\|requestId\|timestamp\|actionName` | The two envelopes of one event do not dedupe. |
| `DATABRICKS_LINEAGE` | `DATABRICKS_LINEAGE\|record_id` | |
| `SNOWFLAKE_ACCESS_HISTORY` | `SNOWFLAKE_ACCESS_HISTORY\|query_id`, fanned out per object (section 5.3) | |
| `DLAP_CRAWLER` | `DLAP_CRAWLER\|` + the crawler `ResourceEvent.eventId` | Applies when DISC-02 moves the bridge to computed ids; until then the bridge sends its own `eventId`. |
| `DLAP_NORMALIZED` (REST ingest) | The caller's `requestId`; without one, `operation\|eventTime\|targetResourceId\|principalId` | Legacy fallback, `LogIngestServiceImpl.java:144-149`. |

**Legacy pipeline note.** Until LOG-02, the native key travels in
`IngestRequestDTO.requestId` (the GCP and CloudTrail sources put their keys
there), so on legacy events `request_metadata.request_id` equals the
native key. From LOG-02 on, `native_event_id` carries the key and
`request_metadata.request_id` carries the provider's own request id.

### 5.3 One record, several events

A parser that emits more than one event from one record (CloudTrail
`DeleteObjects`, a BigQuery job reading several tables, a Snowflake query
touching several objects) keeps the record's native key on the primary
event, the one whose target is the record's primary resource. Each
additional event uses `native_key + "#" + target canonical_uri`
(`LogEventIds.fanOutNativeKey`). The primary event's id therefore matches
what the one-event-per-record legacy pipeline produced.

### 5.4 Dedup layers

1. da-logingest stores `event_id` as the primary key of `log_events` and does
   not republish an id it already stored unless `dlap-ingest-mode=REPLAY`
   (GCP-00 for the legacy pipeline, LOG-02 for the registry). One exception upgrades a row
   instead of dropping the write: a `REPLAY` write of a full event replaces the stored
   `PARSE_STATUS_PARTIAL` row of the same `event_id` in place and keeps that row's `ingest_time`
   (`LogEventStore.java:103-105`, `:123-138`; section 5.5).
2. Every state-changing consumer records `(event_id, consumer)` in its own
   processed-event table in the same transaction as the state change
   (CAT-02).
3. Usage counters dedupe on `LogEventIds.usageDedupKey`: when
   `shared_event_id` is set, `SHA-256(provider|"shared"|shared_event_id|asset key)`,
   so the requester's and the bucket owner's copies of one cross-account
   read count once per object; otherwise `event_id`. `last_accessed_at` is a
   maximum and needs no dedup.

### 5.5 A crash can publish an event twice

da-logingest stores an event and publishes it inside one database transaction (the publish is
synchronous, so a failed send rolls the row back and a retry is not mistaken for a duplicate). A
process killed after the broker accepted the message and before the commit leaves the message on the
topic and no row, so the retry publishes the same `event_id` again. In one shake-down run, killing a
backfill left 40001 messages for 40000 stored events.

The log therefore delivers each event at least once, and a consumer must treat `event_id` as its
idempotency key:

- A state-changing consumer claims `(event_id, consumer)` in the same transaction as its change
  (section 5.4, layer 2) and skips an event it has claimed. A second message of one `event_id` in
  either wire form changes nothing.
- A consumer that keeps no state of its own (the da-discovery router) forwards a duplicate exactly as
  it forwarded the first copy: same topic, same partition key, same payload.
- A pass-through event (`PARSE_STATUS_PARTIAL`) and the full event a replay makes of it share an
  `event_id`. A pass-through changes no state, so it is not claimed, and the full event still applies
  once. In the replay store, a `REPLAY` write of a full event replaces the stored pass-through row of
  that `event_id` and keeps its `ingest_time`; every other duplicate is dropped.

## 6. rawEvent conventions

`raw_event` holds the provider's record as JSON, so a stored event can be
re-parsed by a newer parser without re-fetching. It is a
`google.protobuf.Struct`: numbers are doubles on the wire (`12` reads back as
`12.0`, integers above 2^53 lose precision), so parsers take exact values
(sizes, counts) from the source before conversion.

| Source | raw_event |
|---|---|
| GCP Cloud Audit Logs, every envelope | The LogEntry as JSON in REST field naming: `logName`, `resource` `{type, labels}`, `timestamp`, `receiveTimestamp`, `severity`, `insertId`, `labels`, `operation`, `trace`, `spanId`, `split`, and `protoPayload`. `protoPayload` is the `AuditLog` printed with `JsonFormat` plus `"@type": "type.googleapis.com/google.cloud.audit.AuditLog"`. `metadata` (a Struct, for example `BigQueryAuditMetadata`) prints as JSON. `serviceData` (an `Any`, legacy BigQuery `AuditData`) is `{"typeUrl": "<type url>", "base64": "<standard base64 of the Any value bytes>"}` when the transport hands DLAP the proto (the logging API client); sinks that deliver it expanded keep the expanded JSON with its `@type`. |
| AWS CloudTrail, every envelope | The original CloudTrail record JSON: an element of the S3 file's `Records` array, or the parsed `CloudTrailEvent` string of a LookupEvents result. Identical across envelopes. |
| Azure Activity Log | The event object as the envelope delivers it: the REST `EventData` object, or one record of a diagnostic export. Provisional until AZ-01. |
| Azure StorageBlobLogs | One JSON-lines record of the diagnostic export. Provisional until AZ-01. |
| Databricks system tables | The row as a JSON object keyed by column name; struct and map columns as nested objects. Provisional until DBX-01. |
| Crawler events | Absent. Crawler facts travel in `target_resource`. |
| REST ingest | The caller's `rawEvent` object. |

A producer that holds a raw value that is not a JSON object stores
`{"value": <raw>}`.

**GCP from the logging API client.** The client hands DLAP
`Payload.ProtoPayload` holding an `Any` that packs
`google.cloud.audit.AuditLog`. Unpack it, clear `service_data` and print the
rest with `JsonFormat.printer()` (`metadata`, `request` and `response` are
`Struct`s and need no type registry), add `"@type"`, then set `serviceData`
to the `{typeUrl, base64}` object built from the cleared `Any`. The only
other `Any` in an `AuditLog` is `status.details`; an entry the printer cannot
resolve is written in the same `{typeUrl, base64}` form. Printing an `Any`
whose type is not registered throws, so it must never reach the printer.
The client's `LogEntry.getLogName()` returns only the log id
(`LogName.getLog()` in google-cloud-logging 3.16.0) and the parent comes back
as `getDestination()`; rebuild the full `logName`
(`projects/{id}/logs/{log id with "/" written as "%2F"}`) for `raw_event` and
for the native key (section 5.2).

## 7. Consumer rules

Every consumer of `dlap.log.event.normalized` and of the typed lifecycle
topics:

- Reads with `DlapLogEventCodec.decode`, which takes either encoding, a
  Jackson tree or a Map. It drops unknown fields and unknown enum values and
  reports them as warnings, so an older consumer keeps working when a
  producer adds fields. Count `DecodedLogEvent.isLegacy()` to track the
  migration.
- Subscribes with manual acknowledgment and a dead-letter topic (each
  service's `MANUAL_ACK_FACTORY` container factory). A
  `LogEventDecodeException` goes to the dead-letter topic.
- Treats every `*_UNSPECIFIED` value as unknown, never as a default
  meaning. Unknown is never stale and never fresh.
- Is idempotent per section 5.4.
- Changes state only when `LogEventSemantics.mayChangeState` holds, takes
  business time from `event_time` only, and moves `last_accessed_at` only on
  `LogEventSemantics.isSuccessfulRead`.
- Applies `supersedes_event_id` replacements.
- Never mutates `raw_event`.

## 8. Producer rules

- Write with `DlapLogEventCodec.encode`, once per event, and send the text
  (or `encodeToBytes`) through a `StringSerializer` or `ByteArraySerializer`.
  Never pass the text to a JSON serializer: it would quote it (section 2). A
  producer contract test asserts `DlapLogEventCodec.decodeStrict` accepts the
  bytes the broker receives: `LogEventWireContractTest` and
  `GcpAuditWireContractTest` (da-logingest), `SpineProducersWireContractTest`
  (da-discovery).
- Wait for the broker's acknowledgement before acknowledging the input record or
  moving a cursor, and fail the unit of work when it does not come (section 2.1).
- Set `event_id` with `LogEventIds` from the native key of section 5.2, and
  set `native_event_id`.
- Set `event_time` from the provider clock only (section 3); set
  `ingest_time` when normalizing.
- Fill `source_format`, `source_schema_version`, `parser_version`,
  `binding_id`, `origin`, `parse_status` and `event_category` (registry
  pipeline, LOG-02 on).
- Copy `operation` exactly as the provider names it.
- Set `target_resource.canonical_uri` through `AssetIdNormalizer` (ID-01);
  keep the provider form in `id`.
- Set `schema_version = "v1"`.
- Missing string fields are the empty string (proto3 default); never invent
  a value to fill one.

## 9. Quarantine topic

| Item | Rule |
|---|---|
| Topic | `dlap.log.event.quarantine` (`DlapConstants.KafkaTopic.LOG_EVENT_QUARANTINE`) |
| Key | `binding_id` |
| Value | `dlap.log.v1.LogQuarantineRecord`, proto3 JSON (`DlapLogEventCodec.encodeMessage` and `decodeMessage`) |
| Producer | da-logingest (LOG-03) |

Every source record ends in exactly one outcome: parsed, parsed with
warnings, skipped, or quarantined. A quarantined record carries the
`reason` (`QuarantineReason`: `INVALID_ENCODING`, `INVALID_JSON`,
`UNKNOWN_ENVELOPE`, `UNKNOWN_MAJOR_VERSION`, `VERSION_PIN_MISMATCH`,
`MISSING_REQUIRED_FIELD`, `FIELD_TYPE_MISMATCH`, `UNRESOLVABLE_ASSET_ID`,
`PARSER_EXCEPTION`, `OVERSIZE_RECORD`), a `reason_detail` of at most 1024
bytes that names fields and never quotes values, a `raw_ref` pointing at the
record at its source, a redacted and bounded `raw_excerpt`, and the SHA-256
of the full raw bytes. `quarantine_id` is
`SHA-256(binding_id|raw_ref.uri|raw_ref.record_index|raw_sha256)`
(`LogEventIds.quarantineId`), so quarantining the same record twice is
idempotent. When a `PARSE_STATUS_PARTIAL` pass-through was published,
`pass_through_event_id` names it.

## 10. Binding-status topic

| Item | Rule |
|---|---|
| Topic | `dlap.logingest.binding.status` (`DlapConstants.KafkaTopic.LOGINGEST_BINDING_STATUS`) |
| Key | `binding_id`; the latest message per key is the binding's current state, so the topic may be compacted |
| Value | `dlap.log.v1.LogBindingStatus`, proto3 JSON |
| Producer | da-logingest (LOG-03); consumers include the catalog retention sweep (CAT-03) |

A status carries the binding's scope, state, effective-config hash, event-time
watermarks, outcome counts for a window, the formats and versions observed,
and the binding's read coverage:

| ReadCoverage | Meaning |
|---|---|
| `READ_COVERAGE_CAPTURED` | Data reads are captured: read events seen, or data-access logging verified on. |
| `READ_COVERAGE_NONE_OBSERVED` | The transport can carry reads but none arrived in the lookback; read logging may be off. |
| `READ_COVERAGE_NOT_CAPTURED` | The transport cannot carry data reads (CloudTrail LookupEvents), or the provider configuration shows data-access logging off. |
| `READ_COVERAGE_UNSPECIFIED` | Not yet known. |

**Report rule.** An asset may be called "not read since D" only when its
binding is `READ_COVERAGE_CAPTURED` and `read_coverage_since <= D`.
Otherwise the report says that read logging is not captured, never that the
asset was not read.

## 11. Retention-due topic

`dlap.catalog.asset.retention-due`
(`DlapConstants.KafkaTopic.CATALOG_ASSET_RETENTION_DUE`) is reserved for the
catalog's retention transitions (ADR-0046, CAT-03). Its payload is
specified with the retention model in [asset-descriptor.md](asset-descriptor.md).

## 12. Legacy shapes

`DlapLogEventCodec.decode` maps the pre-ADR-0045 keys as follows and records a
warning for each mapping.

| Legacy input | Read as |
|---|---|
| A JSON string holding the event (the double encoding no producer writes since SPINE-01) | The event (unwrapped up to twice) |
| `source: "crawler"` (any case) | `origin = ORIGIN_CRAWLER`, `source_format = DLAP_CRAWLER/resource-event` |
| `source: "loganalyzer"` (any case, so also `LogAnalyzer`) | `origin = ORIGIN_CLOUD_LOG`, `source_format = DLAP_LOGANALYZER/resource-change` |
| Top-level `targetResourceId`, `assetUri`, `asset_uri` | `target_resource.id` when unset |
| Top-level `cloudProvider`, `cloud_provider` | `source_provider` when unset |
| Top-level `technicalMetadata`, `technical_metadata` | `target_resource.technical_metadata` when unset |
| Enum names in another case (`aws`), unprefixed (`DATA_READ`), or numbers | The canonical value |
| Provider identity types in `principal.type`, matched without case or separators: `Root`, `IAMUser`, `FederatedUser`, `IdentityCenterUser` | `USER` |
| `AssumedRole` | `ROLE` |
| `AWSService` | `SYSTEM` |
| `ServicePrincipal`, `SERVICE_PRINCIPAL`, `ManagedIdentity`, `serviceAccount` | `SERVICE_ACCOUNT` |
| The dormant analyzers' `LogEvent` (section 2) | `source` and `cloudProvider` as above; `eventTimestamp`, `accountId` and `payload` dropped as unknown fields. `eventTimestamp` is never read as `event_time`: it is DLAP's clock. |
| `""`, `null` | Absent |
| Unknown enum values (`UNKNOWN` provider, `UNKNOWN` change type) | Unspecified |
| Proto field names (`event_id`) | Accepted, as by every proto3 JSON parser |

Rejected: a timestamp that is not RFC 3339 with a zone, a non-integer in an
integer field, an object where a string or timestamp belongs, and payloads
that are not a JSON object. The exception names the field path, never the
value.

## 13. Versioning

- This document and the `.proto` are v1. Breaking changes go to a
  `dlap.log.v2` package and topic; v1 keeps working during the rollover.
- Within v1, changes are additive only: new fields, new enum values, new
  messages. No field or value is removed, renumbered, renamed or retyped.
- `LogEventProtoContractTest` enforces this against the committed baseline;
  an additive change appends its lines to the baseline in the same commit.
- Enums added after the first release prefix their values with the enum
  name (`EVENT_CATEGORY_DATA_READ`), because proto3 enum values are
  package-scoped; the codec also accepts the unprefixed spelling.
- A defect in a frozen part of the contract is fixed by an additive change
  and an ADR, and every consumer contract test re-runs before it ships.

## 14. Storage

da-logingest persists each event in `dlap_logingest.log_events` for replay
(Flyway `V1__init_logingest_schema.sql`). The sweeper keeps
`dlap.logingest.retention-days` (default 90) of rows. Today it prunes by
`event_time` (`LogEventRetentionSweeper.java:103-104`), which would delete a
backfill the night it lands; LOG-02 moves it to `ingest_time`.

## 15. Open standard track

This spec is part of DLAP's published surface. An adopter who writes an
adapter produces events that conform to this schema, these keys and this
topic, and checks them with `DlapLogEventCodec.decodeStrict` or any proto3
JSON parser built from the `.proto`.
