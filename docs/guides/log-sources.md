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

# Guide: log sources, bindings and the registry pipeline

da-logingest reads audit logs through **bindings**: one binding is one scope of one log source
(an AWS account, a GCP project, an Azure subscription, a Databricks workspace), with the
transports that pull it and the format version it is expected to deliver. This guide covers the
bindings file, its validation, the two ingest pipelines, and how records are stored.

Decisions: [ADR-0045](../DECISIONS.md) (log layer), ADR-0054 (frozen parser contract), ADR-0060
(bindings, registry ingest path, partitioned store). Contracts:
[log-event.md](../specs/log-event.md), [log-parser-contract.md](../specs/log-parser-contract.md).
An annotated example file: [deployment/examples/log-sources](../../deployment/examples/log-sources/README.md).

## 1. Two pipelines

| `dlap.logingest.pipeline` (`DLAP_LOGINGEST_PIPELINE`) | What runs |
|---|---|
| `legacy` (default) | The per-cloud sources under `source/**` (`dlap.logingest.aws.enabled`, `dlap.logingest.gcp.enabled`) and the DTO ingest service. REST ingest and the fixture replay behave exactly as before. Bindings are validated and mirrored but do not run. |
| `registry` | Every record goes through the parser registry with its binding's settings: transports pulling configured bindings, REST ingest (`POST /api/v1/logingest/events`) and the fixture replay. The legacy sources must be switched off. |

Any other value fails startup. The default changes to `registry` only after the wave-3 families
pass their contract tests (design B8 step 4.3).

## 2. The bindings file

The file is optional and is imported by `application.properties`:

```properties
spring.config.import=optional:file:${DLAP_LOGINGEST_SOURCES_FILE:/etc/dlap/log-sources.yml}
```

Set `DLAP_LOGINGEST_SOURCES_FILE` to the file's path, or mount it at `/etc/dlap/log-sources.yml`.
Keys in the file override `application.properties`. Spring Boot refuses an import placeholder
that resolves to nothing, which is why the default path exists.

### 2.1 Hierarchy

```
dlap.logingest
  defaults            deployment-wide settings
  sources[]           id, kind (source kind), settings
    bindings[]        id, scope, settings
      stream | streams[]   id, transport, location, settings
```

Every level can set the keys below; the nearest level wins, then the family's mapping-YAML
`settings`, then the contract default. `enabled: false` disables the level and everything under
it. `field-overrides` merge (nearer keys win); `operation-overrides` concatenate, nearest first.

| Key | Values | Default |
|---|---|---|
| `enabled` | `true`, `false` | `true` |
| `format.envelope` | the envelope id (`s3-file`, `lookup-events`, `api-proto`) | the family's only envelope; required when it has several |
| `format.version` | `auto` or a pinned version (quote it: `"1.08"`) | `auto` |
| `format.on-mismatch`, `on-mismatch` | `QUARANTINE`, `PARSE_WITH_PIN`, `PARSE_WITH_DETECTED` | `PARSE_WITH_DETECTED` |
| `on-unknown-major` | `QUARANTINE` only: a version no parser reads is always quarantined | |
| `filters` | `include-services`, `exclude-services`, `include-operations`, `exclude-operations` | none |
| `field-overrides` | canonical field (`principal.display-name`) to a JSON pointer | none |
| `operation-overrides` | rows of `service`, `operation` or `operation-prefix`, `change-type`, `category`, optional `asset-kind`, `extractor` | none |
| `overlap` | duration (`PT20M`, `20m`): re-read window of a time-cursored transport | family setting, else 0 |
| `poll-interval` | duration | family setting, else `PT1M` |
| `rate-limit.requests-per-second` | positive number | none |
| `credentials-ref` | an environment variable name (`env:NAME`) or an IAM role ARN; never a secret. GCP: the variable names a credentials file. AWS S3 transports: a role ARN (or a variable holding one) is assumed with STS, section 5.2.1 | none |
| `backfill.allowed` | `true`, `false` | `false` |
| `persist-raw` | `ALL`, `QUARANTINE_ONLY`, `SAMPLED` | `QUARANTINE_ONLY` for data events, `ALL` otherwise |
| `persist-raw-sample-one-in` | integer at least 1 | 100 |
| `max-record-bytes` | 1 to 16 MiB | 1 MiB |
| `dedup` | `none`, `cross-stream` | `none` |

Binding-only keys:

| Key | Meaning |
|---|---|
| `scope.account-id` | AWS account (12 digits, quote it), Databricks account (UUID) or Snowflake account |
| `scope.project-id` | GCP project id |
| `scope.subscription-id`, `scope.resource-group`, `scope.storage-account` | Azure |
| `scope.workspace-id` | Databricks workspace (numeric) |
| `scope.metastore-id` | Unity Catalog metastore |
| `scope.regions` | AWS regions; empty means every region the transport reads |
| `stream.transport` | the transport id, upper snake case (`S3_TRAIL_FILES`, `LOOKUP_EVENTS`, `LOGGING_API`) |
| `stream.location` | transport keys (`bucket`, `prefix`, `dataset`); each transport declares the ones it requires |
| `stream.id` | unique in the binding; defaults to the transport in kebab case |

Write an empty scope by leaving `scope` out; `scope: {}` does not bind.

### 2.2 Validation (design B2)

Validation runs at startup, before the web server accepts requests. Each finding names its rule,
its path and the binding it concerns; it never prints a value that could be a secret.

| Rule | Severity | What it checks |
|---|---|---|
| `DUPLICATE_ID`, `INVALID_ID` | error | source and binding ids unique in the file, stream ids unique in a binding; ids match `[A-Za-z0-9][A-Za-z0-9._:-]{0,127}` |
| `RESERVED_ID` | error | `dlap-*` binding ids, `DLAP_NORMALIZED_*` source kinds and the `REST` and `FIXTURE_REPLAY` transports belong to the built-in channels |
| `UNKNOWN_SOURCE_KIND` | error | a registered parser or mapping reads the source kind |
| `SCOPE_MISSING`, `SCOPE_SYNTAX` | error | AWS 12-digit account, GCP project id, Azure subscription GUID, Databricks numeric workspace (or account UUID), Unity metastore, Snowflake account; AWS region syntax |
| `NO_STREAM` | error | each binding has a stream |
| `UNKNOWN_TRANSPORT`, `MISSING_LOCATION` | error | the transport is registered and its required location keys are set |
| `UNKNOWN_LOCATION_KEY` | warning | a location key the transport does not read |
| `ENVELOPE_UNRESOLVED`, `UNKNOWN_ENVELOPE` | error | the envelope is set when the family has several, and a detector serves it |
| `VERSION_SYNTAX`, `PIN_NOT_READ` | error | a pinned version is a valid version id and a registered parser reads it on the stream's envelope |
| `PIN_NEWER_MINOR` | warning | the pin is above the highest minor the parser knows; such records parse as the highest known with `PARSE_WARNING_NEWER_MINOR` |
| `FIELD_OVERRIDE_KEY`, `FIELD_OVERRIDE_POINTER` | error | override keys are canonical fields and values RFC 6901 pointers |
| `OPERATION_OVERRIDE` | error | override rows are complete and use known values |
| `CREDENTIALS_REF_SYNTAX`, `CREDENTIALS_REF_MISSING` | error | the reference is a variable name or role ARN, and the variable is set |
| `OVERLAPPING_STREAMS` | error | two enabled streams of one source kind read the same scope (same primary scope id, intersecting regions) unless one declares `dedup: cross-stream` |
| `PARSE_SETTINGS_CONFLICT` | error | two streams of one binding with one envelope resolve the same parse settings |
| `INVALID_VALUE`, `CONFIG_MODE`, `UNKNOWN_MAJOR_POLICY` | error | enumerations, durations, sizes; `config-mode` is `FILE`; `on-unknown-major` is `QUARANTINE` |
| `LEGACY_SOURCE_ON_REGISTRY` | error | no legacy source is enabled on the registry pipeline |
| `INACTIVE_ON_LEGACY` | warning | bindings are configured but the legacy pipeline does not read them |

What an error does depends on `dlap.logingest.config-validation` (`DLAP_LOGINGEST_CONFIG_VALIDATION`):

| Value | Under `prod` | Under any other profile |
|---|---|---|
| `auto` (default) | startup fails, listing every error | warning; bindings with an error are `INVALID` and do not run |
| `strict` | startup fails | startup fails |
| `warn` | warning, binding does not run | warning, binding does not run |

## 3. What the service shows and stores

**Configuration hash.** The effective configuration (every binding with inherited settings
resolved, credential references as names) is serialized with sorted keys and hashed with
SHA-256. `/actuator/info` shows it:

```json
"logSources": {
  "configHash": "3f1c...",
  "pipeline": "registry",
  "configMode": "FILE",
  "validation": { "errors": 0, "warnings": 1 },
  "bindings": [ { "id": "aws-111111111111", "sourceKind": "AWS_CLOUDTRAIL", "origin": "FILE",
                  "state": "ACTIVE", "configHash": "9a0b...",
                  "streams": [ { "id": "s3-trail-files", "transport": "S3_TRAIL_FILES",
                                 "envelope": "s3-file", "version": "auto" } ] } ]
}
```

Scope values, locations and credential references are not shown; binding ids are, so do not put
anything secret in a binding id.

**Mirror.** At startup the synchronizer writes every binding to `dlap_logingest.source_binding`
with its effective configuration, its hash and the deployment hash. States: `ACTIVE`, `INACTIVE`
(legacy pipeline), `DISABLED`, `INVALID`, `REMOVED` (left the file; kept because other tables
reference it), `MIGRATED` (Flyway V4, below). The file stays authoritative.

**Runtime tables (Flyway V3).** `stream_cursor` (per binding, stream and partition: low and high
event-time watermarks and transport state), `processed_object` (per object version of a file
transport), `format_observation` (formats, versions and shape fingerprints seen per binding; a
new row is logged as "New log format observed"), `quarantine_record` and `backfill_job` (written
by later packages), `shedlock`.

**V4 watermark migration.** The legacy `source_watermarks` rows are copied into cursors:
AWS rows become binding `legacy-aws-<region>` (stream `lookup-events`, partition `<region>`), GCP
rows `legacy-gcp-<project>` (stream `logging-api`). They are `MIGRATED`, run nothing, and give a
registry transport a starting point. `source_watermarks` itself is unchanged: the legacy sources
keep using it.

## 4. Records on the registry pipeline

1. The record's binding and envelope select its stream; the registry parses it with the stream's
   settings (version pin, mismatch policy, overrides, size limit).
2. Events failing the binding's filters are dropped (`SKIPPED:FILTERED_BY_BINDING`).
3. Each event is stored once in `log_events` and published to `dlap.log.event.normalized` unless
   its `event_id` is already stored. In `REPLAY` mode a stored id is published again
   (log-event spec section 5.4).
4. A quarantined record goes to every registered quarantine sink; without one it is logged (id,
   binding, reason, hash, never the excerpt). Its `PARSE_STATUS_PARTIAL` pass-through event, when
   there is one, is stored and published like any event and never changes state downstream.
5. `persist-raw` decides whether the row keeps `raw_event`; the published event always carries it.

### 4.1 Built-in channels

Normalized records (`IngestRequestDTO`) use built-in bindings, one per provider and channel:

| Binding | Channel | Mode, origin | Source format |
|---|---|---|---|
| `dlap-rest-<provider>` | `POST /api/v1/logingest/events` | `STREAM`, `ORIGIN_CLOUD_LOG` | `DLAP_NORMALIZED_<PROVIDER>/ingest-dto` |
| `dlap-replay-<provider>` | `FixtureReplaySeeder` | `REPLAY`, `ORIGIN_REPLAY` | `DLAP_NORMALIZED_<PROVIDER>/ingest-dto` |

The `event_id` is the one the legacy pipeline computes for the same DTO, so an event stored by
one pipeline is a duplicate on the other. The category is derived from the change type
(`ACCESS` is `DATA_READ`, `PERMISSION` is `PERMISSION`, the rest `MANAGEMENT`) and flagged
`PARSE_WARNING_HEURISTIC_CLASSIFICATION`. `binding_id` and `source_format` tell REST pushes and
fixture replays apart from provider logs.

REST answers on the registry pipeline:

| Status | Body `status` | When |
|---|---|---|
| 202 | `PUBLISHED` | first delivery |
| 202 | `DUPLICATE` | the event id is already stored; nothing is published |
| 422 | `QUARANTINED` (with `quarantineId`) | for example no `resultStatus`, or an unknown `changeType` |
| 422 | `SKIPPED` | the record yields no event (REST validation already requires `eventTime`) |
| 400 | | validation failure, or `sourceProvider` is not a known provider |

The fixture replay runs on every start and replays once: stored events are skipped.
`dlap.logingest.replay.republish-stored=true` re-publishes them on purpose.

## 5. Transports

A transport implements `com.dlap.logingest.transport.LogTransport` and is a Spring bean; it is
found by its `TransportDescriptor.transportId`. SDK-backed transports live under
`providers/<cloud>/` (an ArchUnit test enforces it). The stream runner polls each stream at its
`poll-interval`:

1. it takes the stream's PostgreSQL advisory lock, so one replica polls a stream at a time;
2. per partition it reads `[low watermark - overlap, now - settle)`, or the transport's initial
   lookback on the first poll;
3. per batch it skips objects already read, ingests the records, marks the object done, then
   commits the cursor;
4. on a failure the object is marked failed and the cursor stays, so the next poll reads the
   batch again; event-id dedup absorbs what was already stored;
5. an object that cannot be read at all is retried the same way, up to `max-read-attempts` times
   (section 5.3), then quarantined and skipped. A backfill job (LOG-04) applies the same cap and
   quarantine to the objects it reads.

### 5.1 Registered transports

| Transport | Source kind and envelope | Required location keys | Optional location keys | Switch |
|---|---|---|---|---|
| `LOGGING_API` | `GCP_CLOUD_AUDIT`, `api-proto` | none | `project`, `filter`, `page-size` | none |
| `GCS_SINK` | `GCP_CLOUD_AUDIT`, `json` | `bucket` | `prefix`, `log-ids`, `late-shard-lookback`, `max-object-bytes`, `project` | none |
| `BQ_SINK` | `GCP_CLOUD_AUDIT`, `bq-sink-row` | `dataset` | `project`, `tables`, `include-export-errors`, `page-size` | none |
| `BQ_JOBS_VIEW` | `GCP_BQ_JOBS_VIEW` | `location` | `billing-project` | none |
| `S3_TRAIL_FILES` | `AWS_CLOUDTRAIL`, `s3-file` | `bucket` | see 5.2 | `DLAP_LOGINGEST_TRANSPORTS_AWS_ENABLED=true` |
| `S3_SERVER_ACCESS_LOGS` | `AWS_S3_SERVER_ACCESS_LOG`, `cwl-json` | `bucket` | see 5.2 | `DLAP_LOGINGEST_TRANSPORTS_AWS_ENABLED=true` |

A stream that names a transport the build does not register fails validation as
`UNKNOWN_TRANSPORT`; the AWS S3 transports are registered only with their switch on. The legacy
`LOOKUP_EVENTS` source of the `legacy` pipeline is not a registry transport.

### 5.2 The AWS S3 transports (ADR-0105)

`S3_TRAIL_FILES` reads the gzip files CloudTrail delivers to its S3 bucket
(`AWSLogs/[<organization-id>/]<account>/CloudTrail/<region>/yyyy/MM/dd/...json.gz`); each file's
`Records` array is split into one record per event. This is the transport that carries S3 data events,
which the legacy `LookupEvents` source never returns. `S3_SERVER_ACCESS_LOGS` reads S3 server access
logs delivered to a log bucket; each text line is split into the CloudWatch Logs field names and
parsed by the unchanged `s3accesslog` mapping. Both need `dlap.logingest.pipeline=registry` and the
switch `dlap.logingest.transports.aws.enabled` (`DLAP_LOGINGEST_TRANSPORTS_AWS_ENABLED`), which
defaults to `false`. They read the log bucket only, never the data buckets; the reader role grants
exactly that (IAM-03, ADR-0105).

Both use an object cursor: one batch per object, `processed_object` checkpoints each object version
by key and etag, the low watermark is the delivery time in the object name, and
`late-object-lookback` re-lists a window behind it so a file written late is still found after a
restart. Partitions of `S3_TRAIL_FILES` are regions.

Location keys (the stream's `location`):

| Key | Transports | Meaning | Default |
|---|---|---|---|
| `bucket` | both | the bucket that holds the logs (required) | none |
| `prefix` | both | key prefix in front of the log keys | none |
| `organization-id` | trail | organization trail: `AWSLogs/<organization-id>/<account>/...` | none |
| `regions` | trail | comma list of regions to read | the scope region, else the region folders under `AWSLogs/[org/]<account>/CloudTrail/` |
| `key-format` | access logs | `simple` or `partitioned` (date-partitioned keys) | `simple` |
| `late-object-lookback` | both | ISO-8601 duration to re-list behind the watermark | `PT1H` |
| `max-object-bytes` | both | largest object read, packed or unpacked; a value below the binding's `max-record-bytes` is raised to it (5.2.2) | 64 MiB |
| `region` | both | the bucket's region for the S3 client | the scope region, else the SDK region chain |
| `endpoint` | both | an S3 endpoint URL (a VPC endpoint, an S3-compatible store, an emulator) | the regional endpoint |
| `path-style` | both | `true` for path-style addressing | `false` |
| `profile` | both | a profile of the shared credentials and config files | none |
| `external-id` | both | external id sent when a role is assumed (5.2.1) | none |
| `sts-endpoint` | both | an STS endpoint URL, for the role exchange | the regional endpoint |

Every setting comes from the stream or the environment; nothing is hardcoded.

```yaml
dlap:
  logingest:
    sources:
      - id: aws-cloudtrail
        kind: AWS_CLOUDTRAIL
        bindings:
          - id: aws-ct-123456789012
            scope: { account-id: "123456789012", regions: [us-east-1] }
            credentials-ref: arn:aws:iam::123456789012:role/dlap-log-reader
            stream:
              transport: S3_TRAIL_FILES
              location: { bucket: example-trail-bucket, external-id: example-external-id }
```

A second stream reads S3 server access logs from a log bucket in another account, with the role
and the optional keys of the table above. Each stream names its own transport, so one binding per
log kind keeps the checkpoints and the cursor apart:

```yaml
dlap:
  logingest:
    sources:
      - id: aws-s3-access
        kind: AWS_S3_SERVER_ACCESS_LOG
        bindings:
          - id: aws-s3-access-123456789012
            scope: { account-id: "123456789012", regions: [us-east-1] }
            credentials-ref: AWS_LOG_READER_ROLE_ARN   # the name of a variable that holds the role ARN
            stream:
              transport: S3_SERVER_ACCESS_LOGS
              location:
                bucket: example-access-log-bucket
                prefix: s3-access/
                key-format: partitioned
                late-object-lookback: PT2H
                max-object-bytes: "33554432"
                external-id: example-external-id
                sts-endpoint: https://sts.us-east-1.amazonaws.com
```

The switch and the retry cap are service settings, not stream settings (application.properties,
or the environment):

```properties
# the AWS S3 transports register only when this is true (default false)
dlap.logingest.transports.aws.enabled=${DLAP_LOGINGEST_TRANSPORTS_AWS_ENABLED:false}
# failed reads of one object before it is quarantined and skipped (5.3, default 5)
dlap.logingest.transports.max-read-attempts=${DLAP_LOGINGEST_TRANSPORTS_MAX_READ_ATTEMPTS:5}
```

#### 5.2.1 Credentials

- No `credentials-ref`: the AWS SDK default chain, which covers environment keys, Java system
  properties, **web identity (EKS IRSA: `AWS_ROLE_ARN` with `AWS_WEB_IDENTITY_TOKEN_FILE`)**, the
  shared files' default profile, container credentials and the instance profile. The web-identity
  provider needs the STS module, which ships with the service (`software.amazon.awssdk:sts`). Or set
  the location key `profile` to use a named profile.
- `credentials-ref` is an IAM role ARN, or the name of an environment variable (`NAME` or
  `env:NAME`) that holds one: the service calls STS `AssumeRole` for it, using the default chain or
  the named profile as the caller, with the session name `dlap-logingest`, the optional location key
  `external-id` as the external id, and `sts-endpoint` to override the STS endpoint. The SDK refreshes
  the temporary credentials before they expire. A value that is not a role ARN fails the stream with a
  message that never repeats the value. The reference is never a secret: the config validator
  rejects anything else.
- The role needs read access to the log bucket only, and its trust policy must allow the caller (and
  require the external id when one is set).
- An invalid `credentials-ref` fails its partition at every poll until it is fixed (Q-707). The
  validator checks the syntax of the reference and that a named variable is set
  (`CREDENTIALS_REF_SYNTAX`, `CREDENTIALS_REF_MISSING`), but not what the variable holds. A value that
  is not a well-formed IAM role ARN (an `arn:` string of another shape, or a variable that holds
  something else) throws `IllegalStateException` when the S3 client is first built, which is the first
  time the stream lists its bucket. That is a configuration error, not a read failure: it happens
  before any object is read. The poll logs `Fetch of <binding>/<stream> partition ... failed` and
  counts the partition as failed; when the partitions are themselves found by listing (a trail stream
  with no `regions` and no scope region) it logs `Poll of <binding>/<stream> failed` for the whole
  stream. It happens again at every poll. Nothing is quarantined, no attempt is counted and nothing is
  skipped, because no object was involved; other streams go on. The error log and the failed-partition
  count of the poll are the signals. Correct the reference in the bindings file (or the variable) and
  restart the service; the next poll then succeeds.

#### 5.2.2 Malformed objects

An object that cannot be read as UTF-8 text (a gzip file that does not unpack, bytes that are not
UTF-8, more than `max-object-bytes`) is delivered as one record that the registry quarantines
(`INVALID_ENCODING`, `INVALID_JSON` or `OVERSIZE_RECORD`); the object is then checkpointed and never
retried.

An object above `max-object-bytes`, packed or unpacked, is always quarantined as `OVERSIZE_RECORD`,
never as `INVALID_JSON`: the service keeps the first `max-record-bytes` + 1 bytes of it as the
record, which the registry rejects for its size. A `max-object-bytes` below the binding's
`max-record-bytes` would cut that sample short, so the service raises it
to the record limit; an object between the two limits is read like any other.

### 5.3 Unreadable objects and the retry cap

An object that cannot be read at all (AccessDenied, a KMS key the reader may not use, a corrupt GCS
shard, a network error that does not go away) does not move the cursor, and the next poll reads it
again. After `dlap.logingest.transports.max-read-attempts` failed reads of the same object version
(`DLAP_LOGINGEST_TRANSPORTS_MAX_READ_ATTEMPTS`, default 5, counted in `processed_object.attempts`),
the runner:

1. writes a quarantine record for the object (reason `PARSER_EXCEPTION`, detail starting with
   `READ_FAILED` and naming only the exception class; the record's `raw_ref` names the object);
2. checkpoints the object as done (0 records, 1 quarantined), so it is not read again;
3. goes on with the objects behind it in the same poll.

The earlier failures only retry. This applies to the GCS and the AWS transports alike. Fix the
permission or the key, then run a backfill over the window to read the object (the quarantine
record holds no content to replay).

**What the count includes.** `processed_object.attempts` is one counter per object version. Every
time the object is recorded as failed or as done the counter goes up, so an object whose records
failed to ingest (a database outage, say) and a read that failed once on a throttle or a network error
count toward the same cap as an `AccessDenied`. The cap therefore means "five bad outcomes of this
object", not "five consecutive permission failures". There is no separate `read_attempts` column and no
minimum time between attempts: the polls are the spacing (`poll-interval`), so a cap of 5 gives a
transient outage five polls to clear before the object is quarantined. Raise the cap on a stream that
reads over an unreliable network.

**Backfill.** A backfill job uses the same cap and the same quarantine (Q-702). It counts under its
own checkpoints, the pseudo stream `backfill-<job id>`, so a job never touches the stream's counts and
checkpoints. Below the cap a failed read fails the job's attempt and the job retries after its backoff
(`dlap.logingest.backfill.retry-backoff`, doubling up to 15 minutes). At the cap the object is
quarantined, checkpointed under the job, counted in the job's `quarantined` figure and skipped, and the
job goes on. A job fails after `dlap.logingest.backfill.max-attempts` (default 5) consecutive failed
attempts, so keep that setting above `max-read-attempts`, or the job can end as `FAILED` before the
object reaches the cap. A job that is restarted does not quarantine the object a second time: its
checkpoint under the job survives.

**Resetting a quarantined unreadable object.** There is no API for it; it is a documented SQL step on
the service database, run as the schema owner. Use it after fixing the cause, when the stream (not a
new backfill, whose checkpoints start empty) should read the object again:

```sql
UPDATE dlap_logingest.processed_object
   SET status = 'FAILED', attempts = 0
 WHERE binding_id = '<binding id>'
   AND stream_id  = '<stream id>'        -- 'backfill-<job id>' for the checkpoint of a job
   AND object_key = '<object key>'
   AND etag       = '<etag of the object version>';
```

The next poll reads the object again and the cap starts from zero. A poll lists only objects whose
name time lies in `[low watermark - overlap - late-object-lookback, now - settle)`, so an object older
than that window is not found by the stream; read it with a backfill instead. The quarantine row of the
earlier failure stays as the record of what happened.

### 5.4 NUL characters

PostgreSQL text and jsonb values cannot hold U+0000. The service removes it from the quarantine
excerpt, reason detail and references before any quarantine sink stores or publishes them, from the
text columns of `log_events`, and escapes it as the six characters `\u0000` inside `raw_event`, so a
record that carries one is stored and never blocks its partition. The hash `raw_sha256` stays the
hash of the original bytes, so an excerpt that lost a NUL is not replayable from the table.

## 6. The replay store

`log_events` is partitioned by month of `ingest_time` (`log_events_pYYYY_MM`, UTC), plus
`log_events_default` for months without a partition. Its primary key is
`(event_id, ingest_time)`; `event_id` stays unique because every writer takes a transaction
advisory lock on the id before it checks and inserts.

`LogEventRetentionSweeper` runs on `dlap.logingest.retention-cron` as a ShedLock singleton:

- it creates the current month's partition and `retention-partitions-ahead` (default 2) more,
  moving rows that already wait in the default partition;
- it drops every partition whose whole month is older than `now - retention-days` (default 90)
  and deletes default-partition rows older than that.

Retention keys on ingest time, so a backfill of old events is kept for the full window. A row
lives between `retention-days` and `retention-days` plus one month. A non-positive
`retention-days` or `retention-enabled=false` stops pruning; partitions are still created.

## 7. Limits

- Quarantined records are logged, not stored, until the quarantine sinks ship (LOG-03).
- The publisher sends without waiting for the broker, as on the legacy path; synchronous sends
  come with the producer switch (SPINE-01).
- One source kind belongs to one provider (ADR-0054), so the normalized pass-through registers
  one source kind per provider.

## 8. Provider dependencies and convergence

The provider SDKs are declared in `da-logingest/build.gradle.kts` and used only under
`providers/<cloud>/` (and, until it is deleted, the legacy `source/**`); the ArchUnit test
`LogIngestBoundaryArchitectureTest` enforces both. The GCP set (DEPS-01) comes from the Google
Cloud libraries BOM 26.90.0:

| Library | Used for |
|---|---|
| `google-cloud-logging` | Cloud Logging `entries.list` (GCP-01 LoggingApiTransport, the legacy GCP source) |
| `google-cloud-bigquery` | BigQuery queries and tables (GCP-01 BqSinkTransport, GCP-02 `INFORMATION_SCHEMA.JOBS`) |
| `google-cloud-bigquerystorage` | The BigQuery Storage Read API for bulk reads of sink tables and JOBS results |
| `google-cloud-storage` | Listing and reading the GCS sink shards (GCP-01 GcsSinkTransport) |

No Pub/Sub library is included: events are pulled, never pushed (ADR-0023, decision D6). No AWS
or Azure SDK was added; the existing `software.amazon.awssdk:cloudtrail` line predates DEPS-01.

The Spring Boot BOM is imported again after the Google BOM, so protobuf, gRPC, Netty and Jackson
keep the Spring Boot 4.1 versions in this jar too (protobuf 4.35.1 and gRPC 1.83.1, against the
4.33.6 and 1.83.0 the Google BOM was built with). `GcpProviderDependencySetTest` builds every
client offline and round-trips the generated messages on those versions.

**Arrow needs an open `java.nio`.** The Storage Read API returns Arrow (or Avro, whose library
is not on the class path). Arrow 17 allocates through Netty and, on Java 21, fails to start
unless the JVM runs with `--add-opens=java.base/java.nio=ALL-UNNAMED`. The test pins both the
working and the failing case; the service JVM does not set the flag yet.

**The convergence check.** `./gradlew verifyDependencyConvergence` (part of the root `check`
and `build`, and the CI hook `scripts/ci-guards/pre/dependency-convergence.sh`) walks the
runtime class path of every boot jar. It fails when a `protobuf-*`, `grpc-*`, `netty-*`,
`jackson-*` or Guava library resolves to a version other than the declared alignment: the
Spring Boot BOM for the first four, Guava 33.6.0 for the last. The policy and the dated exception
rows live in the DEPS-01 block of the root `build.gradle.kts`; the only rows today are the older
protobuf, gRPC and Guava of da-discovery. The report is
`build/reports/dependency-convergence/report.tsv`. `dependencyConvergenceSelfTest` drives the
policy with injected conflicts on every run, and the hook's `--prove` mode injects a real
conflicting dependency through a Gradle init script and shows the check fail.

To add a provider library: declare it without a version when the BOM manages it, give it a
comment line saying what uses it, then run `verifyDependencyConvergence` and
`scripts/ci-guards/pre/dependency-licenses.sh --gradle-only`.
