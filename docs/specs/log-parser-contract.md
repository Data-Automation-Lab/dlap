# Log parser contract

Status: v1, frozen by ADR-0054 (LOG-02a, hardening wave 2A). Builds on
ADR-0045 (log layer), ADR-0047 (canonical asset ids) and
[log-event.md](log-event.md), which it does not change.

| Artifact | Path |
|---|---|
| Contract types | [`da-logingest/src/main/java/com/dlap/logingest/parse/core/`](../../da-logingest/src/main/java/com/dlap/logingest/parse/core/) |
| Mapping-YAML model, loader, validator, mapping-driven parser | [`parse/core/mapping/`](../../da-logingest/src/main/java/com/dlap/logingest/parse/core/mapping/) |
| JSON Schema of the mapping YAML | [`da-logingest/src/main/resources/dlap/log-formats/schema/log-format-mapping.schema.json`](../../da-logingest/src/main/resources/dlap/log-formats/schema/log-format-mapping.schema.json) |
| Fixture contract test base | [`da-logingest/src/test/java/com/dlap/logingest/parse/core/contract/AbstractParserContractTest.java`](../../da-logingest/src/test/java/com/dlap/logingest/parse/core/contract/AbstractParserContractTest.java) |
| Sample family mapping | [`da-logingest/src/test/resources/com/dlap/logingest/parse/core/contract/selftest-mapping.yaml`](../../da-logingest/src/test/resources/com/dlap/logingest/parse/core/contract/selftest-mapping.yaml) |
| Boundary test | [`da-logingest/src/test/java/com/dlap/logingest/parse/ParseLayerArchitectureTest.java`](../../da-logingest/src/test/java/com/dlap/logingest/parse/ParseLayerArchitectureTest.java) |

This document states the interfaces every log family builds on. Wave 3
parser packages (GCP-01, CT-01 and the rest), LOG-02b (bindings and
validator) and LOG-02c (transports, cursors, migrations) depend on it and
may run in parallel because it is frozen.

## 1. The frozen rules

| # | Rule | Where it is encoded |
|---|---|---|
| 1 | Source kinds, envelopes, versions and binding ids are strings, never Java enums. Syntax: kinds upper snake case, envelopes lower kebab case, versions `[A-Za-z0-9][A-Za-z0-9._-]*` of at most 64 characters, binding ids without whitespace or `\|`. | `FormatNames`, `FormatKey`, `EnvelopeKey` |
| 2 | Parsers, version detectors and transports self-register as Spring beans. No central switch names a family. | `ParserContractConfiguration` collects every `LogRecordParser` and `VersionDetector` bean; every mapping-YAML envelope gets a declarative detector. LOG-02c registers transports the same way. |
| 3 | Per-family settings live in the family YAML, not in `application.properties` (critic item 12). | `MappingSpec.Settings`, `MappingSpec#defaultBinding` |
| 4 | A detected version no parser reads is quarantined as `UNKNOWN_MAJOR_VERSION`, whatever the binding says. A minor above the highest known is parsed as the highest known with `PARSE_WARNING_NEWER_MINOR`. | `DefaultParserRegistry`, `VersionRange` |
| 5 | `event_id` is ADR-0045 decision 4: lowercase hex `SHA-256("<SOURCE_PROVIDER>\|<native key>")`, where a source kind that is not its provider's primary audit stream namespaces its key as `<SOURCE_KIND>\|...`. This is how the plan's shorthand `SHA-256(source_kind\|native_key)` is realized without breaking ids the legacy pipeline stored. A stored id is not republished, except in `REPLAY` mode. | `CanonicalEvent#eventId`, `IngestMode#shouldPublish`, `MappingSpec.NativeKey` |
| 6 | `persist-raw` defaults to `QUARANTINE_ONLY` for data events (`DATA_READ`, `DATA_WRITE`, `DATA_READ_OBSERVED`) and `ALL` for the rest. | `PersistRaw` |
| 7 | Usage dedup uses `shared_event_id` plus the target when CloudTrail `sharedEventID` is present, otherwise `event_id` (critic item 14). | `LogEventIds.usageDedupKey`, exposed as `CanonicalEventMapper#usageDedupKey` |
| 8 | `dlap.logingest.pipeline` is `legacy` or `registry`, default `legacy`. Any other value fails startup. The registry is not wired into the live ingest path by this package. | `PipelineMode`, `@ConditionalOnLegacyPipeline`, `@ConditionalOnRegistryPipeline` |

## 2. Types

All types are pure Java in `com.dlap.logingest.parse.core` and use only
JSON (Jackson) and the da-protos contract (`com.dlap.proto.log.v1`,
`com.dlap.common.logevent`, `com.dlap.common.assetid`).

| Type | Role |
|---|---|
| `RawRecord` | One provider record as delivered: binding, source kind, envelope, `RawRef`, `IngestMode`, fetch time, optional provider observed time, the exact bytes. Decodes on first use; a decode failure is a quarantine reason, never an exception. |
| `RawRef` | Where the record lives (mirrors `RawRecordRef`): transport, container URI, record index or -1, cursor. |
| `FormatKey`, `EnvelopeKey` | Registry keys: (source kind, envelope, schema version) and (source kind, envelope). |
| `VersionRange`, `VersionMatch`, `MajorMinor`, `VersionScheme` | What a parser reads and how a version is judged (section 4). |
| `VersionDetector` | Recognizes an envelope's records and reads their version. Pure and total. |
| `LogRecordParser` | Turns one decoded record into a `ParseResult`. Declares envelope, `VersionRange`, provider, `parserId`, `parserSemver` and optionally the JSON paths it declares. |
| `ParseResult` | Sealed: `Parsed` (one or more `CanonicalEvent`s plus record-level warnings), `Skipped` (`SkipReason`), `Quarantined` (`QuarantineReason`, detail of at most 1024 bytes that never quotes values, optional pass-through event). |
| `ParseOutcome` | What the registry returns: the result plus detected and effective format, pin, parser version, provider and shape fingerprint. |
| `ParserRegistry`, `DefaultParserRegistry` | The one parse path. Stateless, thread safe, never throws for a record. |
| `CanonicalEvent` | A parser's event; enforces the producer rules at construction (section 6). |
| `CanonicalEventMapper` | Writes outcomes onto the wire: `DlapLogEvent`s and `LogQuarantineRecord`s. |
| `BindingConfig` | The parser's view of a binding: pin, `OnMismatch`, field overrides, operation overrides, `PersistRaw`, record size limit, asset-id hints. LOG-02b builds it from the bindings file. |
| `ShapeFingerprint` | Present JSON paths of a record (section 9). |
| `Classification`, `ClassificationRule`, `VerbHeuristics` | The classification table and its one shared fallback. |
| `CanonicalField`, `FieldPath` | The 20 canonical fields a mapping or override may point at, and the pointer syntax. |
| `IngestMode`, `PipelineMode`, `PersistRaw`, `OnMismatch`, `SkipReason` | Frozen enumerations of section 1. |

Contract enums (`ChangeType`, `EventCategory`, `ResultStatus`,
`ParseWarning`, `QuarantineReason`, `PrincipalType`, `ResourceRelation`,
`SourceProvider`) are the proto enums themselves, so there is no second
vocabulary to drift.

## 3. Resolution

`DefaultParserRegistry.parse(record, binding)`; the binding's id and source
kind must equal the record's (a caller defect otherwise).

| Step | Condition | Outcome |
|---|---|---|
| 1 | record bytes above `binding.maxRecordBytes` (default 1 MiB, the Kafka default message size) | `QUARANTINED:OVERSIZE_RECORD` |
| 2 | no detector for (kind, envelope) | `QUARANTINED:UNKNOWN_ENVELOPE` |
| 3 | bytes are not UTF-8 / not one JSON value / empty (a leading byte order mark is ignored) | `QUARANTINED:INVALID_ENCODING` / `QUARANTINED:INVALID_JSON` |
| 4 | detector throws, or answers for another envelope | `QUARANTINED:PARSER_EXCEPTION` |
| 5 | detector does not recognize the body | `QUARANTINED:UNKNOWN_ENVELOPE` |
| 6 | no registered parser reads the detected version | `QUARANTINED:UNKNOWN_MAJOR_VERSION`, for every `on-mismatch` policy |
| 7 | the binding pins a different version: `QUARANTINE` | `QUARANTINED:VERSION_PIN_MISMATCH` |
| 7 | `PARSE_WITH_PIN` | parse as the pin with `PARSE_WARNING_VERSION_PIN_MISMATCH`; a pin no parser reads is `VERSION_PIN_MISMATCH` |
| 7 | `PARSE_WITH_DETECTED` (default) | parse as detected with `PARSE_WARNING_VERSION_PIN_MISMATCH` |
| 8 | minor above the highest known | parse as the highest known with `PARSE_WARNING_NEWER_MINOR` |
| 9 | the parser throws (including `StackOverflowError`), returns `null`, or emits an event of another provider | `QUARANTINED:PARSER_EXCEPTION`; the detail names the exception class, never its message |
| 10 | the parser declares paths and the record has others | `PARSE_WARNING_UNKNOWN_FIELDS` on a parsed record |

Pins compare under the envelope's scheme (`1.08` equals `1.8`). Record-level
warnings (steps 7, 8 and 10) are added to every event of the record.

Construction fails fast on two detectors for one envelope, two parsers whose
ranges overlap on one envelope (same major, shared named versions, or mixed
schemes), a parser without a detector, one source kind claimed by two
providers, or a parser id or version outside `[a-z0-9]+(-[a-z0-9]+)*` and
semantic versioning.

## 4. Versions

| Scheme | Range | Match |
|---|---|---|
| `major-minor` (CloudTrail `eventVersion`, Databricks `version`) | one major, `[floor, highest known]`, for example `[1.0, 1.11]` | same major and floor or above: supported; above the highest known: newer minor, parsed as the highest known; another major or below the floor: unsupported. `1` reads as `1.0`. |
| `named` (GCP `auditlog`, `bq-auditmetadata`, `bq-auditdata-legacy`; shape fingerprints) | a set of names | exact name only |

A parser that reads two majors registers as two beans.
`DlapLogEvent.source_schema_version` is always the version detected on the
record; the parser receives the effective version.

## 5. Outcomes

Every record ends in exactly one outcome (ADR-0045 decision 7), spelled as
fixture `expected_outcome` spells it:

| Outcome code | Meaning |
|---|---|
| `PARSED` | events, no warnings |
| `PARSED_WITH_WARNINGS` | events, at least one record or event warning |
| `SKIPPED:<SkipReason>` | a valid record that is no event: `MISSING_EVENT_TIME` (no provider clock and no documented fallback; DLAP never stamps business time), `NOT_AN_EVENT` (declared shapes such as digests or heartbeats), `FILTERED_BY_BINDING` |
| `QUARANTINED:<reason>` | the `QuarantineReason` without its `QUARANTINE_REASON_` prefix |

A quarantine may carry a pass-through event: `PARSE_STATUS_PARTIAL`, change
type unspecified, built when provider, event time, operation and native key
were extractable. It keeps the audit fact and never changes state
(`LogEventSemantics.mayChangeState` is false for it).

## 6. CanonicalEvent

A parser builds events with `CanonicalEvent.builder()`. Construction refuses:

- a blank native key, or one longer than 4096 characters;
- an unspecified or unrecognized provider;
- a missing event time, or one outside the protobuf `Timestamp` range;
- a blank operation;
- an unspecified change type, category or result on a parsed event;
- a pass-through event with a change type;
- a `canonical_uri` that `AssetIdNormalizer.isCanonical` rejects. Set it
  with `ResourceBuilder.canonicalFrom(raw, hints)`, which leaves it empty when
  the id does not normalize (ADR-0047, plan rule R8);
- a relation on the target, or a related resource without one;
- a negative size (`null` is unknown, `0` is a real size).

Missing strings are empty, never invented.

`CanonicalEventMapper.events(outcome)` writes each event as `DlapLogEvent`:

| Field | Value |
|---|---|
| `event_id` | `LogEventIds.eventId(provider, native key)` |
| `native_event_id` | the native key |
| `event_time`, `observed_time` | from the event; `observed_time` falls back to the transport's `RawRecord.observedTime` |
| `ingest_time` | the injected `Clock`, the only DLAP clock on the event |
| `source_format`, `source_schema_version` | the detected format |
| `parser_version` | `<parser-id>@<semver>` |
| `binding_id` | the binding |
| `origin` | `STREAM` gives `ORIGIN_CLOUD_LOG`, `BACKFILL` gives `ORIGIN_BACKFILL`, `REPLAY` gives `ORIGIN_REPLAY` |
| `parse_status` | `PARTIAL` for a pass-through, `WARNINGS` with any warning, else `FULL` |
| `parse_warnings` | record and event warnings, sorted by enum number |
| `supersedes_event_id` | `eventId(provider, supersedes native key)` when set |
| `raw_event` | the parser's raw value or the record body as a `Struct`; a non-object is `{"value": ...}` |
| `schema_version` | `v1` |

Every mapped event passes `DlapLogEventCodec.decodeStrict`.

## 7. Identity

- Native keys follow log-event.md section 5.2. A mapping declares the key in
  `native-key`: parts joined with `|`, a part `as: instant` rendered by
  `Instant#toString`, and `namespaced: true` for a non-primary source kind.
- A record that yields several events keeps its own key on the primary
  event and uses `LogEventIds.fanOutNativeKey(key, canonical uri)` on the
  others. The contract base fails a case whose event ids repeat.
- `supersedes` comes from the replaced record's native key (CloudTrail
  addendum `originalEventID`).
- Dedup after parsing belongs to the ingest service: `log_events` keeps
  `event_id` as primary key, and `IngestMode#shouldPublish(alreadyStored)`
  is false for a stored id unless the mode is `REPLAY`.

## 8. Raw records, framing, storage and quarantine

- `RecordFraming.split(unit, framing, records-pointer)` splits a delivered
  unit: a JSON array (`json-array`), an object's record array
  (`json-object-array`, for example `/Records`), JSON lines (`json-lines`,
  each line kept as exact text so a bad line is quarantined alone), or one
  record (`record`). Record indexes are array indexes or line numbers.
- `PersistRaw` decides whether a parsed event keeps `raw_event` in its
  `log_events` row: a configured `ALL`, `QUARANTINE_ONLY` or `SAMPLED`
  (one in `sampleOneIn`, chosen from the event id so every replica and replay
  agrees), else the default of rule 6. The policy governs storage, not the
  wire: `CanonicalEventMapper` always sets `raw_event` on the published
  event, and the `log_events` writer (LOG-02b or LOG-03, whichever wires the
  registry path to the store) calls `PersistRaw.persists` before it keeps
  the raw payload.
- `CanonicalEventMapper.quarantine(outcome)` builds the
  `LogQuarantineRecord`: `quarantine_id = LogEventIds.quarantineId(binding,
  raw_ref, raw_sha256)`, provider when the source kind has a registered
  parser, detected and pinned versions, parser version, reason and bounded
  detail, raw reference, `raw_sha256` and size of the exact bytes, and a
  `raw_excerpt` of at most 2048 bytes with email and IP addresses masked
  (`RawExcerpts`), plus the pass-through event's id, native key and event
  time. Publishing it is LOG-03.

## 9. Shape fingerprint

`ShapeFingerprint.of(body, declared)` collects present paths to depth 3,
array elements as `*`. A present path is known when a declared path equals
it, lies above it (the declared path covers its subtree, as for
`/requestParameters`) or lies below it. The rest are unknown; only the
highest unknown path of a subtree is reported, never a value. `hash` is the
SHA-256 of the known present paths, independent of key order and values.
`MappingDrivenParser` declares every path its mapping reads plus
`known-paths`. Unknown paths go to the unknown-fields counter and drift
report, never to a metric label (LOG-03).

## 10. Mapping YAML

One file per family at `dlap/log-formats/<family>/mapping.yaml` on the
classpath, contract version `spec-version: 1`, syntax fixed by the JSON
Schema above. `MappingSpecValidator` enforces the schema at startup without
a schema library and adds the rules a JSON Schema cannot express (unique
envelope and version ids, `1.8` equal to `1.08`; every named version reached
by a detection rule and every rule naming a listed version; required fields
mapped in every version; no repeated classification row; `asset-kind` an
`AssetKind`; `principal-types` keys distinct without case and separators;
family equal to its directory; durations positive). YAML is read with
SnakeYAML's safe constructor: duplicate keys, custom tags, non-string keys
and unquoted dates are refused. Quote version ids (`"1.10"`).

| Key | Meaning |
|---|---|
| `family`, `source-kind`, `source-provider`, `description` | identity; `source-provider` enters every event id; `schema` is reserved |
| `settings` | family defaults a binding inherits: `on-mismatch`, `persist-raw`, `persist-raw-sample-one-in`, `max-record-bytes` (at most 16 MiB), `overlap`, `poll-interval` (ISO-8601) |
| `envelopes[].id`, `framing`, `records-pointer` | envelope id and container framing (`records-pointer` exactly when `json-object-array` is listed) |
| `version-detection` | `scheme: major-minor` with `pointer` (text, or an integral number), or `scheme: named` with `rules` (first rule whose `when` conditions all hold) |
| `native-key` | `namespaced`, `parts[]` of `pointer` and `as: text\|instant` |
| `fields` | canonical field to a pointer, a fallback list of pointers, or `{const: text}`; `event-time` and `operation` are required |
| `required` | fields whose absence is `MISSING_REQUIRED_FIELD` |
| `time-fallback` | documented fallback clocks (GCP `receiveTimestamp`); use adds `PARSE_WARNING_EVENT_TIME_FALLBACK` |
| `known-paths` | declared paths the mapping does not read |
| `result.failure-when`, `result.pending-when` | any condition gives `FAILURE` / `PENDING`, else `SUCCESS` |
| `skip-when` | `reason` (`NOT_AN_EVENT`, `FILTERED_BY_BINDING`) and `when` conditions |
| `principal-types` | provider identity type to `USER`, `SERVICE_ACCOUNT`, `GROUP`, `ROLE`, `SYSTEM` |
| `classification.rules` | `service`, `operation` or `operation-prefix`, `change-type`, `category` (without the `EVENT_CATEGORY_` prefix), optional `asset-kind` and `extractor` |
| `versions[]` | supported versions (the range is lowest to highest per major) with per-version `fields`, `required` and `known-paths` deltas; for major-minor, deltas of every listed version up to the asked one apply in order |
| `extensions` | family-specific object, validated by the family's own code |

A condition is `{pointer, present: true|false}`, `{pointer, equals: v}`,
`{pointer, not-equals: v}` or `{pointer, in: [v, ...]}` with scalar `v`.
Pointers are RFC 6901; a segment that is exactly `*` selects every array
element or member value. Values compare by JSON type, numbers numerically.

`MappingDrivenParser` executes a mapping (order in its Javadoc): skips, event
time with fallback, native key, operation and required fields, then
classification (binding `operation-overrides`, then the table's most specific
row, then `VerbHeuristics` with `PARSE_WARNING_HEURISTIC_CLASSIFICATION`),
result, principal, and target through `AssetIdNormalizer`. A data-category
row with an `asset-kind` whose target does not normalize is
`UNRESOLVABLE_ASSET_ID`. Binding `field-overrides` are tried before the
mapping. Families override `target`, `related` and `rawEvent` where records
need code.

`VerbHeuristics` reads the last segment of the operation as words and
follows log-event.md section 4.3 (a metadata read is `MANAGEMENT`, not a
data read). First match wins:

1. A metadata-read verb (`list`, `describe`, `lookup`, `search`) gives
   `ACCESS`/`MANAGEMENT`.
2. A content-read verb (`get`, `read`, `head`, `download`, `select`,
   `query`, `scan`, `batch get`) with a permission noun (`policy`, `acl`,
   `iam`, `permission`, `grant`, and plurals) is a get-policy and gives
   `ACCESS`/`MANAGEMENT`.
3. Any other content-read verb gives `ACCESS`/`DATA_READ`.
4. A permission word (the permission nouns and `revoke`) gives
   `PERMISSION`/`PERMISSION`.
5. A create verb gives `CREATE`/`MANAGEMENT`, a delete verb
   `DELETE`/`MANAGEMENT`, anything else `UPDATE`/`MANAGEMENT`.

A guessed metadata read therefore never moves `last_accessed_at` or a usage
count. The heuristic cannot see what an operation returns: a family lists
in its classification table every operation the verb rule gets wrong, such
as a list verb that returns content (BigQuery `tabledataservice.list` as
`DATA_READ`) or a get verb that returns only configuration
(`GetBucketLocation` as `MANAGEMENT`).

## 11. Wiring

`ParserContractConfiguration` (component-scanned) defines the
`PipelineMode`, the `MappingSpecs` loaded from the classpath, the
`DefaultParserRegistry` and the `CanonicalEventMapper` (the application's
`Clock` bean when it is unique or primary, else UTC). Startup fails on an unknown pipeline
value, an invalid mapping or an ambiguous set of beans. In legacy mode
nothing calls the registry. Gate registry-pipeline beans with
`@ConditionalOnRegistryPipeline` and legacy beans with
`@ConditionalOnLegacyPipeline`.

## 12. Boundary

`ParseLayerArchitectureTest` fails the build when any main class under
`com.dlap.logingest.parse` depends on a provider SDK (AWS, GCP including
`com.google.cloud.audit`, gRPC, Azure, Databricks, Unity Catalog client,
Snowflake, OCI), on `source`, `providers` or `transport`, or on Kafka, JPA,
Spring Data, web, JDBC or HTTP client types. It proves itself on a
deliberate violation kept in test sources.

## 13. Contract tests for a family

A family package adds, in its own test package:

```java
class CloudTrailContractTest extends AbstractParserContractTest {
    protected String family()            { return "cloudtrail"; }        // fixture family
    protected String sourceKind()        { return "AWS_CLOUDTRAIL"; }
    protected Optional<MappingSpec> mapping() { return Optional.of(classpathMapping("cloudtrail")); }
    protected ParserRegistry registry()  { return registryOf(mapping().orElseThrow(), parsers()); }
}
```

and gets, on every case of its family in the `da-log-fixtures` corpus:
expected outcome and events (`expected.json` holds `outcome`, optional
`reason`, and `events` in `DlapLogEvent` proto3 JSON, compared with
JSONAssert `STRICT_ORDER`, so only listed fields count and arrays are
exact), agreement with `meta.yaml` `expected_outcome`, negative cases
quarantined, the wire contract (`decodeStrict`, `event_id` formula, unique
ids, determinism, `ingest_time` from the clock), unknown-field tolerance,
totality under removed fields, random value replacements and random
documents (no `PARSER_EXCEPTION`, seeded, no property-test library per D7),
and a parser for every `supported: true` catalog version. Cases of versions
the catalog does not mark supported are reported as skipped. Override
`binding(case)` for pin-mismatch cases and `projectEvent` only for a legacy
expected shape.

`SelftestParserContractTest` runs this on the corpus `selftest` family with
the sample mapping; `ContractDemoParserContractTest` runs it with the default
projection on a small corpus next to it; `ContractBaseSelfCheckTest` proves
the base fails broken parsers.

## 14. Changing the contract

Additive changes (a new canonical field, a new optional YAML key, a new skip
reason) bump nothing but need an ADR. Anything else is breaking: it goes
through the orchestrator's contract-change package (critic item 23), bumps
`spec-version` or the Java API, and re-runs every family's contract test.
The mapping-YAML contract version is `MappingSpec.SPEC_VERSION`.

## 15. Not in this contract

- Bindings file, `LogSourcesProperties`, validator and synchronizer: LOG-02b.
- `LogTransport` SPI, cursors, processed objects, advisory locks, Flyway V3
  and V4: LOG-02c.
- Quarantine and binding-status publishing, metrics and alerts: LOG-03.
- Runtime dependencies: DEPS-01. This package adds test-scope dependencies
  only.
