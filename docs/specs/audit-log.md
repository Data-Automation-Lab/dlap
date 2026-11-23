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

# DLAP audit log v1 specification

Status: v1, normative (AUD-01). RET-02, RET-03, AUD-02a and AUD-02b write to it; DR-02 verifies it.

| Artifact | Path |
|---|---|
| Schema of record | `deployment/platform/database/init-scripts/02-create-common-schema.sql`, block "Tamper-evident audit log"; delta `upgrades/2026-10-21_audit_log.sql` |
| Writer, reader, verifier, checkpoint format | [`da-protos/src/main/java/com/dlap/common/audit/`](../../da-protos/src/main/java/com/dlap/common/audit/) |
| Checkpoint signer, export, API | [`da-admin-service/src/main/java/com/dlap/adminservice/audit/`](../../da-admin-service/src/main/java/com/dlap/adminservice/audit/) and `V2__audit_checkpoints.sql` |
| Independent verifier | [`scripts/audit-verify.sh`](../../scripts/audit-verify.sh) |
| Conformance tests | `AuditChainTest`, `AuditChainVerifierTest`, `CheckpointSignaturesTest`, `AuditLogSchemaContainerTest` (da-protos); `AuditCheckpointContainerTest` and the sink tests (da-admin-service) |

The key words MUST, MUST NOT, SHOULD and MAY are used as in RFC 2119.

## 1. Purpose

Destructive lifecycle actions (delete, archive), approvals, policy changes and access changes
must leave a record that an administrator cannot quietly change afterwards. The audit log is
that record. It is tamper-evident, not tamper-proof: a database superuser can still change
rows, but no change goes unnoticed by a verifier that holds a signed checkpoint taken after the
row was written.

Three mechanisms stack:

1. **Hash chain.** Every entry carries the SHA-256 of its own content and of the previous
   entry's hash, one chain per stream. Changing, inserting or removing a row breaks the chain.
2. **Database rules.** The database refuses UPDATE, DELETE and TRUNCATE for every role, accepts
   an INSERT only if it extends the head of its stream with a correct hash, and lets only the
   stream's writer role insert.
3. **Signed checkpoints in write-once storage.** On a schedule, da-admin-service signs the head of
   each stream with an Ed25519 key and copies the signature to object storage that nobody can
   overwrite or delete before its retention ends. A rewrite of the whole chain after a
   checkpoint no longer matches the signed hash.

## 2. Storage

Both tables live in the shared `dlap_common` database and schema. They are owned by the
bootstrap superuser; no service role owns them (an owner could disable the triggers).

`dlap_common.audit_streams`: one row per stream.

| Column | Type | Rule |
|---|---|---|
| `stream_id` | VARCHAR(64), PK | `^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$` |
| `writer_role` | VARCHAR(63) | the only database role that may append to the stream |
| `description` | TEXT | what the stream records |

`dlap_common.audit_log`: one row per entry.

| Column | Type | Rule |
|---|---|---|
| `stream_id` | VARCHAR(64) | FK to `audit_streams` |
| `seq` | BIGINT | position in the stream, 1, 2, 3 and so on without gaps; PK with `stream_id` |
| `prev_hash` | CHAR(64) | `entry_hash` of entry `seq - 1`; 64 zeros for `seq = 1` |
| `entry_hash` | CHAR(64) | section 5 |
| `record` | TEXT | the canonical record, section 4; printable ASCII only (CHECK `ck_audit_log_record_ascii`), a JSON object |
| `event_type`, `target_id`, `correlation_id`, `idempotency_key` | TEXT, generated | copies of record members for queries; derived from `record`, so they cannot disagree with it |
| `inserted_at` | TIMESTAMPTZ | database clock at insert; informational, not hashed |

Indexes: the primary key; a unique partial index on `(stream_id, idempotency_key)`; indexes on
`target_id` and `correlation_id`.

## 3. Streams

A stream is one writer's chain. Each stream has exactly one writer role. The registry rows are
append-only like the log: a stream keeps its writer for life. A new stream is a new row, added
by a `dlap_common` delta (CLAUDE.md section 6). Seeded streams:

| Stream | Writer role | Records | Package |
|---|---|---|---|
| `retire.delete` | `da_autodelete_user` | delete requests, executions, restores | RET-02 |
| `retire.archive` | `da_autoarchival_user` | archive requests, executions, restores | RET-03 |
| `govern.approval` | `da_policyengine_user` | approval requests and decisions | APPR-01 |
| `govern.policy` | `da_policyengine_user` | policy versions and changes | AUD-02a |
| `admin.access` | `da_admin_user` | role changes, identity-provider events | AUD-02b |
| `report.export` | `da_reporting_user` | report and CSV exports | AUD-02b |

The Java constants are in `com.dlap.common.audit.AuditStreams`.

## 4. Record

A record is a JSON object with these members. The writer fills the four marked "writer"; the
caller states the rest in an `AuditEvent`.

| Member | Required | Rule |
|---|---|---|
| `schema` | yes (writer) | `dlap-audit-record/1` |
| `eventType` | yes | dotted lower case, 2 to 8 segments, at most 128 characters, for example `retire.delete.executed` |
| `occurredAt` | yes | when the event happened: business (event) time, never the write time (work-plan rule R6) |
| `recordedAt` | yes (writer) | the writer's injected `Clock` when the record was built |
| `actor` | yes | `{"kind": "USER" or "SERVICE" or "SYSTEM", "id": ...}`; `id` 1 to 256 characters without control characters |
| `target` | no | `{"kind": ..., "id": ...}`; `kind` matches `^[A-Z][A-Z0-9_]{0,31}$`; for `ASSET` the id is the canonical asset URI (ADR-0047, passed through `AssetIdNormalizer`) |
| `outcome` | yes | `REQUESTED`, `SUCCEEDED`, `FAILED` or `DENIED` |
| `correlationId` | no | links the records of one action (for example the approval id); 1 to 128 printable ASCII characters without spaces |
| `idempotencyKey` | no | same character rule; see section 6 |
| `details` | yes (writer writes `{}` when empty) | object; keys `^[A-Za-z][A-Za-z0-9_]{0,63}$`; values string (at most 4096 characters), boolean, integer in the signed 64-bit range, object or array, nested at most 4 deep; no `null`, no floating point |

Instants are UTC with exactly six fraction digits: `2026-10-21T08:15:30.123456Z` (truncated to
microseconds). A whole record is at most 32768 characters.

**No personal data, no secrets.** Rows can never be erased, so a record MUST NOT hold an e-mail
address, a display name, a credential, a token or the content of data. `actor.id` is the JWT
`sub` of a person or the client id of a service account; a person's identity is resolved through
the identity provider when it is needed.

### 4.1 Canonical JSON

A record is stored, hashed and exported in exactly one text form:

- object members sorted by key in plain string order (keys are ASCII), no insignificant white
  space;
- integers in plain decimal;
- strings escaped as `\"`, `\\`, `\b`, `\f`, `\n`, `\r`, `\t`, and every other character outside
  U+0020 to U+007E as `\uXXXX` with four lower-case hex digits per UTF-16 code unit;
- so the text is printable ASCII and has the same bytes in every client encoding.

Verifiers MUST hash the stored text as it is and MUST NOT re-serialize it.

## 5. Hash chain

```
hash_input = "DLAP-AUDIT-1" LF stream_id LF decimal(seq) LF prev_hash LF record
entry_hash = lower-case hex of SHA-256(UTF-8 bytes of hash_input)
prev_hash(seq = 1) = "0" repeated 64 times
prev_hash(seq = n) = entry_hash(seq = n - 1)
```

LF is the single byte 0x0A. There is no LF after `record`.

Worked example (`AuditChainTest`, computed with `sha256sum` and by PostgreSQL 15.1). The record

```
{"actor":{"id":"5f0c6f3e-1111-4a1b-9c3d-000000000001","kind":"USER"},"correlationId":"appr-7","details":{"bytes":1024,"dryRun":false,"note":"caf\u00e9 \"x\"\n"},"eventType":"retire.delete.executed","occurredAt":"2026-10-21T08:15:30.123456Z","outcome":"SUCCEEDED","recordedAt":"2026-10-21T08:15:31.000000Z","schema":"dlap-audit-record/1","target":{"id":"policy-42","kind":"POLICY"}}
```

as `seq = 1` of `retire.delete` has the entry hash
`a698fa7c1879e091ad33b5288f719beb95e1b7bea11d788d769842830660f7ca`, and the same record as
`seq = 2` chained to it has `7095302b7951f5b8d937b32ac7693cf0398ca22ecef2639927362e18a00701ee`.

## 6. Writing

Writers use `com.dlap.common.audit.AuditLogWriter` with a `DataSource` of the `dlap_common`
database that connects as the stream's writer role, and an injected `Clock`.

- `append(streamId, event)` runs in its own transaction on its own connection (READ COMMITTED)
  and commits before it returns. It is not part of the caller's transaction, because the
  caller's own tables live in another database.
- Inside the transaction it takes the stream's advisory lock
  (`pg_advisory_xact_lock(hashtextextended('dlap_common.audit_log:' || stream_id, 0))`), reads
  the head, builds the record, computes the hash and inserts. Concurrent writers to one stream
  are serialised; the result is one gapless chain (`concurrentWritersProduceOneContiguousChain`).
- **Intent, then outcome.** A writer that audits an action with an external effect appends a
  `REQUESTED` record before the effect and `SUCCEEDED`, `FAILED` or `DENIED` after it, with the
  same `correlationId`. If the append of the intent fails, the action MUST NOT run (fail
  closed). A `REQUESTED` without a final record means the outcome is unknown and must be
  reconciled from the provider's own logs.
- **Idempotency.** With an `idempotencyKey`, an append whose key is already in the stream
  returns the stored entry instead of writing a second one, provided it records the same event
  (everything but `recordedAt`); a different event under the same key is refused
  (`IllegalArgumentException`). Use it for retries, for example `<taskId>:executed`.
- A failed append throws `AuditException`; the caller decides whether to retry.

## 7. What the database enforces

| Rule | How | Test |
|---|---|---|
| No UPDATE, DELETE or TRUNCATE on either table, for any role including the owner | BEFORE UPDATE OR DELETE row triggers and BEFORE TRUNCATE statement triggers raising `insufficient_privilege` | `updateDeleteAndTruncateAreRejectedForServiceRolesAndForTheOwner` |
| The triggers also fire under `session_replication_role = replica` | `ENABLE ALWAYS TRIGGER` | same test |
| An insert must extend the head: next `seq`, previous hash, correct `entry_hash` | BEFORE INSERT trigger `audit_log_before_insert`, which takes the same advisory lock and recomputes the hash | `theInsertTriggerRejectsAForkAWrongHashAndANonAsciiRecord` |
| Only the stream's writer role may insert; an unregistered stream or a missing role accepts nobody | the same trigger, `pg_has_role(current_user, writer_role, 'MEMBER')` | `onlyTheWriterRoleMayAppendAndOnlyToARegisteredStream` |
| Service roles have SELECT and INSERT on `audit_log` and SELECT on `audit_streams`, nothing else; PUBLIC nothing; no service role owns the tables | REVOKE of every other grant, then narrow GRANTs, in 02 and the delta | `privilegesAreSelectAndInsertOnlyAndNoServiceRoleOwnsTheTables`, also after a re-run of 02 |
| The delta runs only as a superuser | guard at the top of `2026-10-21_audit_log.sql` | `theDeltaRefusesToRunAsAServiceRole` |

The init-script block and the delta block are identical text (`theDeltaEqualsTheInitScriptBlockAndBothCanBeRerun`),
both are idempotent, and `AssetSchemaUpgradeContainerTest` proves that an upgraded database has
the same schema as a fresh one.

## 8. Checkpoints

A checkpoint states "the entry at `seq` of `streamId` has `entryHash`", signed with Ed25519
(RFC 8032). The signed payload is the canonical JSON (section 4.1) of exactly these members:

```
{"entryHash":"<64 hex>","format":"dlap-audit-checkpoint/1","keyId":"<16 hex>","seq":<n>,"signedAt":"<instant>","streamId":"<id>"}
```

- `keyId` is the first 16 hex characters of the SHA-256 of the public key's X.509 DER encoding.
  It names the key; it does not authenticate it.
- The signature is over the payload's ASCII bytes and travels as standard Base64 of its 64
  bytes.
- A verifier MUST accept only the exact canonical text (`AuditCheckpoint.parse`).

**Signer.** da-admin-service runs the checkpoint job every `dlap.audit.checkpoint.interval`
(default one hour) and on `POST /api/v1/audit/checkpoints`. For each stream it:

1. checks the newest stored checkpoint's payload and signature against the trusted keys;
2. verifies the entries appended since that checkpoint (or the whole stream if there is none),
   including that the checkpointed entry still has the signed hash;
3. if that holds, signs the verified head and stores the checkpoint in
   `dlap_admin.audit_checkpoints`; if not, signs nothing for that stream and logs an ERROR.

Then it exports every checkpoint that has no write-once copy yet (section 9), after the sink's
preflight. A run holds `pg_try_advisory_xact_lock(hashtextextended('dlap_admin.audit_checkpoints:run', 0))`
in `dlap_common`, so two replicas never run at once; the `(stream_id, seq)` key makes a
repeated run harmless. The signer does not re-verify entries older than its last checkpoint;
the verifiers of section 10 do.

`dlap_admin.audit_checkpoints` (Flyway V2): the signed part (`stream_id`, `seq`, `entry_hash`,
`key_id`, `signed_at`, `payload`, `signature`) never changes and rows are never deleted
(trigger `tr_audit_checkpoints_guard`); only the export bookkeeping (`export_sink`,
`export_location`, `exported_at`, `export_attempts`, `last_export_error`) is updated.

## 9. Export

Each checkpoint becomes two objects below the configured prefix (default `dlap-audit`):

```
<prefix>/<streamId>/<seq as 20 digits>.sig    Base64 signature text
<prefix>/<streamId>/<seq as 20 digits>.json   payload bytes
```

The signature object is written first. An existing object is never replaced: when it already
holds the same bytes the export counts as done (a retry), when it holds other bytes the export
fails. Every sink runs a preflight before each export run and refuses storage that is not
write-once.

| Sink | Write-once mechanism | Preflight | Least privilege | Credentials |
|---|---|---|---|---|
| `S3` | S3 Object Lock: each PUT carries `x-amz-object-lock-mode` (`COMPLIANCE` by default, or `GOVERNANCE`) and a retain-until date `retention-days` ahead; `If-None-Match: *`; SHA-256 checksum | `GET /?object-lock` must report `ObjectLockEnabled=Enabled` | `s3:PutObject`, `s3:PutObjectRetention`, `s3:GetObject`, `s3:GetBucketObjectLockConfiguration` | `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` / `AWS_SESSION_TOKEN`, else the EC2 instance profile through IMDSv2 |
| `GCS` | a locked bucket retention policy; uploads with `ifGenerationMatch=0` | `buckets.get` must show `retentionPolicy.isLocked=true` and a period of at least `min-retention-days` | `storage.objects.create`, `storage.objects.get`, `storage.buckets.get` | `access-token-file`, else the metadata server token of the attached service account |
| `AZURE` | version-level immutability: each blob gets a `Locked` immutability policy `retention-days` ahead; `If-None-Match: *` | container properties must show `x-ms-immutable-storage-with-versioning-enabled: true` | Storage Blob Data Contributor on the container | `access-token-file`, else the managed identity token from IMDS (`managed-identity-client-id` for a user-assigned one) |
| `FILESYSTEM` | none: files are created, never replaced, and set read-only | the directory is writable | n/a | n/a |

`FILESYSTEM` is for development and tests, and for a directory that a write-once volume backs.
A pilot that deletes or archives MUST use a cloud sink. The cloud sinks speak each provider's
REST API with the JDK HTTP client (SigV4 for S3), so the service carries no cloud SDK; redirects
are not followed.

What is proven where (critique items 18 and 19): the request shapes, the SigV4 signature (the
AWS documentation's published example, `AwsSigV4Test`), the preflight refusals and the
no-overwrite handling are tested against loopback stubs (`S3ObjectLockCheckpointSinkTest`,
`GcsBucketLockCheckpointSinkTest`, `AzureImmutableBlobCheckpointSinkTest`). No emulator in the
repository enforces Object Lock, bucket lock or blob immutability, so the provider's refusal to
delete a locked object is a live check on a real bucket, recorded as an open item until a
pilot account runs it.

## 10. Verification

Three verifiers apply the same rules and report the first problem.

| Problem | Meaning |
|---|---|
| `SEQ_GAP` | a position is missing or repeated |
| `PREV_HASH_MISMATCH` | an entry does not link to the previous one (a row was rewritten together with its own hash, or removed) |
| `RECORD_NOT_ASCII` | the record holds a character outside printable ASCII |
| `ENTRY_HASH_MISMATCH` | the stored hash is not the hash of the stored fields: the row was changed |
| `ANCHOR_MISMATCH` | a signed checkpoint names another hash for that position: the chain was rewritten consistently |
| `ANCHOR_BEYOND_HEAD` | a signed checkpoint covers a position past the head: entries were removed |
| `WRONG_STREAM` | an entry of another stream was read (Java verifier) |
| `BAD_CHECKPOINT_FILE`, `UNKNOWN_KEY`, `BAD_SIGNATURE`, `NO_CHECKPOINT`, `UNKNOWN_STREAM` | exported checkpoint problems (script) |

1. **`AuditChainVerifier`** (da-protos): recomputes a stream from its first entry, in pages, and
   checks it against anchors (positions and hashes from checkpoints whose signature verified).
2. **`GET /api/v1/audit/streams/{streamId}/verification`** (da-admin-service): the Java verifier
   over the whole stream with every stored checkpoint as an anchor. A stored checkpoint whose
   payload, row or signature does not verify makes the stream `BROKEN` too. The report also
   gives `unanchoredEntries`, the entries after the newest verified checkpoint; those are
   protected by the chain and the database rules only until the next checkpoint.
3. **`scripts/audit-verify.sh`**: independent of every DLAP service. It recomputes each chain
   inside PostgreSQL with `pg_catalog` functions only (never with `dlap_common.audit_entry_hash`,
   which an owner could replace), then checks the exported checkpoint files with OpenSSL against
   public keys you trust and compares each signed hash with the row at that position. Any SQL
   error stops it with exit 2, so a check that could not run never passes.

```
# Copy the exported checkpoints first, for example:
aws s3 sync s3://<bucket>/dlap-audit ./checkpoints
# Then verify every stream against them:
DLAP_PSQL="docker exec -i dlap-postgresql psql -U dlap" \
  bash scripts/audit-verify.sh --checkpoints ./checkpoints --public-key audit-checkpoint.pub.pem --require-checkpoints
```

Exit codes: 0 every stream verified; 1 at least one stream is `BROKEN`; 2 usage, tool or query
error. DR-02 runs it after a restore drill.

## 11. API

All endpoints are in da-admin-service under `/api/v1/audit`. None changes an audit row.

| Method and path | Roles | Answer |
|---|---|---|
| `GET /streams` | ADMIN, CDO, AUDITOR | each stream with its head and newest checkpoint |
| `GET /streams/{streamId}/entries?afterSeq=0&limit=100` | ADMIN, CDO, AUDITOR | entries in order, `record` as the exact stored text; `limit` at most 500 |
| `GET /streams/{streamId}/verification` | ADMIN, CDO, AUDITOR | the report of section 10 |
| `GET /streams/{streamId}/checkpoints?limit=50` | ADMIN, CDO, AUDITOR | stored checkpoints, newest first, with payload, signature and export state |
| `GET /keys` | ADMIN, CDO, AUDITOR | the trusted public keys (PEM) and which one signs |
| `POST /checkpoints` | ADMIN | runs the checkpoint job now; 409 when no key or sink is configured |

A malformed stream id is 400, an unknown stream 404, an unreachable `dlap_common` 503 (without
the driver's message). The contract is `docs/api/openapi/admin-service.json`.

## 12. Operating

Every setting has a default in `AuditProperties`; none needs to be in `application.properties`.

| Setting (env) | Default | Meaning |
|---|---|---|
| `dlap.audit.datasource.url` (`DLAP_AUDIT_DATASOURCE_URL`) | derived | blank: `spring.datasource.url` with the database name replaced by `dlap_common` |
| `dlap.audit.datasource.username` / `.password` | blank | blank: the service's own `spring.datasource.*` (da_admin_user) |
| `dlap.audit.checkpoint.enabled` (`DLAP_AUDIT_CHECKPOINT_ENABLED`) | `false` | the scheduled signer; `true` requires a key and a sink or the service does not start |
| `dlap.audit.checkpoint.interval` / `.initial-delay` | `PT1H` / `PT2M` | schedule |
| `dlap.audit.signing.private-key-file` / `.public-key-file` | blank | the Ed25519 pair as PEM files; both or neither; a mismatched pair stops the service |
| `dlap.audit.signing.trusted-public-key-files` | empty | older public keys still trusted after a rotation |
| `dlap.audit.export.sink` | `NONE` | `S3`, `GCS`, `AZURE` or `FILESYSTEM` |
| `dlap.audit.export.prefix` / `.timeout` | `dlap-audit` / `PT30S` | object prefix, HTTP timeout |
| `dlap.audit.export.s3.*` | | `bucket`, `region`, `retention-days` (required), `mode`, `endpoint`, `path-style` |
| `dlap.audit.export.gcs.*` | | `bucket`, `min-retention-days` (default 1), `access-token-file`, `endpoint` |
| `dlap.audit.export.azure.*` | | `account-url`, `container`, `retention-days` (required), `access-token-file`, `managed-identity-client-id` |
| `dlap.audit.export.filesystem.directory` | | target directory |

With checkpoints off the service logs a WARN at startup: the log is chained but nothing anchors
it outside the database.

**Keys.** Generate the pair on an operator machine and mount the private key as a secret file
readable only by the service user:

```
openssl genpkey -algorithm ed25519 -out audit-checkpoint.key.pem
openssl pkey -in audit-checkpoint.key.pem -pubout -out audit-checkpoint.pub.pem
```

Keep the public key outside the deployment too (the auditor's copy is what `audit-verify.sh`
trusts). To rotate, deploy the new pair and list the old public key in
`trusted-public-key-files`; old checkpoints stay verifiable.

**Storage.** S3: create the bucket with versioning and Object Lock enabled and pick
`retention-days` to match the audit retention; COMPLIANCE mode cannot be shortened, even by the account root user. GCS: set and lock
a bucket retention policy. Azure: enable version-level immutability on the container (or the
account). Choose the retention deliberately: locked objects cost storage until it ends.

## 13. Threat model

| Threat | Detected by |
|---|---|
| A service role edits or deletes a row | refused by privileges and triggers |
| A service writes into another service's stream | refused by the writer-role check |
| A buggy writer stores a broken link or hash | refused by the insert trigger |
| A superuser edits a row after disabling a trigger | `ENTRY_HASH_MISMATCH` (any verifier) |
| A superuser deletes a row from the middle of a chain | `SEQ_GAP` at the next remaining seq (any verifier, no checkpoint needed) |
| A superuser reorders rows by swapping their `seq` values | `PREV_HASH_MISMATCH` at the lower moved seq (any verifier, no checkpoint needed) |
| A superuser rewrites rows and recomputes all later hashes | `ANCHOR_MISMATCH` against a checkpoint signed after the original rows |
| A superuser deletes the newest rows | `ANCHOR_BEYOND_HEAD` once a checkpoint covered them |
| Someone edits the checkpoint table | the signature check (API, signer) |
| Someone deletes checkpoint rows or the whole admin database | the exported copies, which the script verifies |

Residual risks, stated plainly:

- Entries after the newest checkpoint can be rewritten or removed by a superuser without
  detection until a later checkpoint signs what is there. Shorten the interval to shrink the
  window; `unanchoredEntries` shows it.
- Whoever holds the signing key and database superuser rights together can forge history.
  Keep the key in a secret store that the database administrators cannot read.
- A clean restore of an older database is indistinguishable from truncation unless the newest
  exported checkpoint is checked (`ANCHOR_BEYOND_HEAD`): DR-02 MUST run the script with the
  exported checkpoints.
- `dlap_admin.audit_checkpoints` is created by Flyway as `da_admin_user`, which therefore owns
  it and can drop `tr_audit_checkpoints_guard`. The table is a working copy: the exported
  write-once objects are the anchor, and only they protect the checkpoints against that role.
- The checkpoint run itself is not audited into a stream in v1.

## 14. Changes to this contract

The hash input prefix (`DLAP-AUDIT-1`), the record `schema` and the checkpoint `format` carry
versions. A change to any of them is a new version, written alongside the old one; existing
entries are never rewritten. Contract changes after this freeze go through the orchestrator's
contract-change package (critique item 23).
