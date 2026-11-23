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

# da-autodelete: the Retire stage delete executor

da-autodelete carries out deletes that policies or people asked for, and only after the approvals,
holds and coverage checks allow them (RET-02, decision D20). It prefers the platform's own lifecycle
primitive over deleting objects itself, and it calls a delete executed only when the platform's own
log says so.

## The path of one delete

1. **Approved fire.** A policy fires a `delete` action. The policy engine holds it until the
   approvers decide (docs/specs/approvals.md) and then publishes it on `dlap.policies.actions` with an
   `approval` block.
2. **Admission into the soft-delete window** (`retire/SoftDeleteAdmission`). The executor re-reads the
   approval from the policy engine and refuses the delete unless:
   - the approval is `APPROVED` or `APPROVED_BREAK_GLASS` and executable, for this asset and fire;
   - its class covers the delete (`SOFT_DELETE` or `HARD_DELETE`; an immediate hard delete needs
     `HARD_DELETE`);
   - it has enough distinct approvers, and none of them is the requester, the submitter or another
     excluded party;
   - the Kafka block agrees with the policy engine's record;
   - the catalog knows the asset and says it is not on legal hold (an unknown hold counts as a hold);
   - the asset has a canonical URI (ADR-0047) on which the action and the catalog agree.

   The window is at least `dlap.autodelete.window-floor-days` (default 7): a shorter window, a zero
   window or `hardDelete: true` is raised to the floor. Nothing in the cloud is touched yet; the row
   is `SOFT_DELETED` and can be restored.
3. **Hard-delete approval** (`retire/HardDeleteExecutor`). When the window ends, the sweeper plans
   the deletion (a bucket, prefix or container rule must be age-based and its bucket unversioned, below)
   and asks the policy engine for a `HARD_DELETE` approval with at least two approvers
   (`dlap.autodelete.hard-delete-required-approvals`, never fewer than 2), as the service account,
   and for a task on behalf of its submitter, who therefore cannot approve it.
4. **Execution.** Once the approval is executable, every rule is checked again with fresh facts,
   plus read coverage: the catalog's `readCoverage` must be `FULL` (COV-01; `PARTIAL` and unknown
   refuse). Then:
   - **Dry run** (`dlap.autodelete.dry-run-cloud-delete=true`, the default, decision D23): the plan
     and the rule document are recorded, the row ends `DRY_RUN_COMPLETE`, nothing is applied.
   - Otherwise the bucket's versioning is read again, the intent is audited, then the rule is applied
     (or, for a single object, the engine deletes it) and the row waits in `AWAITING_PROOF`.
5. **Log proof** (`proof/`). The disposition becomes `EXECUTED` only when the provider's own delete
   event arrives on `dlap.discovery.asset.deleted` for the target (or an object below it), after the
   apply time, from the provider's logs (never a crawler observation or a replayed fixture), and,
   for a native rule, with the platform's lifecycle signature. Without a proof by
   `dlap.autodelete.proof.overdue-after` (default 3 days after the rule takes effect) the disposition
   is `PROOF_OVERDUE` and the executor keeps listening. What the proof does to the record depends on
   what the target is (below).

## What a proof means: `HARD_DELETED` and `RULE_IN_FORCE` (Q-631, ADR-0107)

| Target | Record after the proof | Meaning |
|---|---|---|
| one object, blob or table (engine delete, or a BigQuery table expiration) | `HARD_DELETED`, `hard_deleted_at` = the provider's event time | the target is gone |
| a whole bucket, prefix or container (S3, GCS, Azure Blob lifecycle rule) | `RULE_IN_FORCE`, `hard_deleted_at` empty | the provider's rule is **proven to fire**: its first child delete was seen in the provider's log. The bucket or container itself stays, and other objects may not have expired yet |

The disposition is `EXECUTED` with the proof in both cases. A record is never `HARD_DELETED` for a
whole bucket or container. A `RULE_IN_FORCE` record stays on the would-delete list, holds the asset (a
second delete of the same asset joins it) and is restorable: the restore **removes the rule** (only
DLAP's own `dlap-retire-` rule; the owner's rules stay) and says that objects the rule already expired
are gone and are not restored. If the platform refuses the removal the record stays `RULE_IN_FORCE`
and the failure is audited. A container rule that was applied and still waits for its proof
(`AWAITING_PROOF`) is in force on the platform just the same, and is restorable the same way (RET-06).
The task list shows these records as `RULE_IN_FORCE` with the rule's age, and dry-run records as
`DRY_RUN` (never `COMPLETED`, with an empty error list).

## How a rule in force ends (Q-677, RET-06; Q-695, RET-08)

A bucket, prefix or container rule keeps deleting matching objects for as long as it is on the
platform, objects written after the delete included. Three things bound it:

1. **Age-based only.** The rule deletes an object `dlap.autodelete.native-rule.container-expiration-days`
   (default 1, never less) after it was written: S3 `Expiration.Days`, GCS `age`, Azure
   `daysAfterModificationGreaterThan`. A date-based rule (S3 `Expiration.Date`, GCS `createdBefore`)
   or a zero-day rule would delete new objects the day they arrive; it is refused when the hard delete
   is planned and again before it is applied, reason `DATE_RULE_UNSUPPORTED` (`date-rule-unsupported`).
2. **A maximum age.** A `RULE_IN_FORCE` record whose rule was applied longer ago than
   `dlap.autodelete.rule-in-force.max-age` (default `P30D`, on the injected clock) has the rule removed by
   the executor itself, and the record ends **`RULE_ENDED`**. Removing a rule deletes nothing, so no
   approval is asked; it is audit-chained as `retire.delete.rule_ended` (REQUESTED, then SUCCEEDED or
   FAILED). The job (`autodelete-rule-in-force-end`) runs every `dlap.autodelete.rule-in-force.interval`
   (default one hour) under ShedLock on database time, and claims each row with `FOR UPDATE SKIP
   LOCKED`, so it never races a restore. A platform that refuses or does not answer leaves the record
   `RULE_IN_FORCE`; it is tried again after `dlap.autodelete.rule-in-force.retry-after` (default `PT6H`),
   kept in the record's own `rule_end_retry_at` (Flyway V9), never in the sweeper's `next_check_at`.

   **A rule that is never proven ends too (Q-695, decision D38).** The same maximum age covers a container
   rule DLAP applied that no provider event ever proved: the record is still `AWAITING_PROOF` and its
   disposition `AWAITING_PROOF` or `PROOF_OVERDUE`. The executor removes the rule without an approval and
   the record ends **`RULE_ENDED_UNPROVEN`** (Flyway V9), an end state: whatever the rule expired is gone,
   nothing deletes any more, and nothing was proven. The removal is audit-chained as
   `retire.delete.rule_ended_unproven` (REQUESTED, then SUCCEEDED or FAILED, `details.proven = false`),
   logged as a WARN (check the bucket and its log source: why was no delete ever seen?) and counted by
   the counter `dlap.autodelete.rules.ended_unproven`. A refused removal waits for its retry time like any
   other; a rule that is not found ends `RESTORE_FAILED`. An engine delete or a BigQuery table expiration
   waiting for its proof is not a container rule and is never touched by this job. A proof that arrives
   later still proves the disposition (EXECUTED) but does not move the record.
3. **Visible.** The task list shows each rule in force with `ruleAppliedAt`, `ruleAgeDays` and
   `ruleEndsAt`. The gauge `dlap.autodelete.rules.in_force` counts the records in force, and
   `dlap.autodelete.rules.restore_failed` the records whose rule DLAP could not find.

**The apply record comes first (Q-699, RET-08).** When the provider call of an apply succeeded but its
`retire.delete.apply` SUCCEEDED record could not be written, the disposition waits with
`success_audit_pending` for the sweeper to write it (Q-633). A restore or the end of the maximum age
writes that pending record before anything else, then its own intent, then removes the rule. When the
pending record cannot be written, nothing is removed and the record does not change.

**A rule that is not found (Q-682).** When a restore or the end of the maximum age finds no rule on the
platform that matches DLAP's stored rule, nothing is reported as removed: the restore answers 409 with
`ruleRemoved: false`, the record ends **`RESTORE_FAILED`** with reason `rule-not-found`, and the
outcome is logged and audit-chained (`FAILED`, `details.reason = rule-not-found`). The rule was removed
by someone else, or it is there in a form that no longer matches and may still delete: a person has to
check the bucket. GCS rules have no name, so DLAP finds its own by their normalized fields (action
type, storage class, age, prefixes, `createdBefore` and the other condition fields, dates as calendar
dates, lists sorted), never by SDK object equality. S3 and Azure rules are found by their id or name.

## Versioned buckets are refused (Q-656, RET-06)

An S3 expiration on a versioned bucket only adds delete markers, and a delete marker is an UPDATE, not
a DELETE (ADR-0104): the rule could never be proven. Until a live capture settles how such a rule is
proven, a native delete rule on a bucket whose versioning is `Enabled` or `Suspended` is refused with
reason `VERSIONED_BUCKET_UNSUPPORTED` (`versioned-bucket-unsupported`). The versioning is read when the
hard delete is planned (before anyone is asked to approve) and again right before the rule is applied.
An answer the platform did not give is never read as unversioned: the row waits for a later sweep; only
under dry run, which applies nothing, is it recorded as `UNKNOWN`. S3 is read with
`GetBucketVersioning`, GCS with the bucket's `versioning.enabled`, Azure with the storage account's blob
service properties (`isVersioningEnabled`, RET-08, Q-694): an Azure account with blob versioning
enabled is refused the same way, because a lifecycle delete there turns the current version into a
previous version, which the provider's log does not show as a delete. Azure has no suspended state;
`isVersioningEnabled` false or absent (never turned on) reads `UNVERSIONED`, and the previous versions
an account kept from before are not considered (as for GCS). The Azure read is tested against mocks only
(Azurite has no Resource Manager API): **a live capture of an Azure account with blob versioning is still
needed** before this is relied on. `S3.CREATE.DELETEMARKER` is no lifecycle signature (Q-657).

**The versioning read under dry run (Q-698, decision D38: accepted).** Dry run is the default, and it
plans a container rule exactly as a real run would: the bucket's versioning is read with the executor's
own credentials when the approval is asked for, and once more before the dry run is recorded. It is a
read and changes nothing. Without credentials (a deployment that never deletes) the read fails (S3 and
GCS at once, Azure after its credential chain gives up) and is recorded as `UNKNOWN` in the
`hard_delete_requested` and `dry_run` audit records; a dry-run row is never held back for it. With
credentials it shows, before anyone turns dry run off, which buckets a real run would refuse as versioned.
No code change: the read stays.

## Read coverage is enforced where data is destroyed (Q-617, ADR-0101 decision 3)

Read coverage `FULL` is required at the **hard delete**, the step that touches data, and not at
soft-delete admission. Admission touches nothing and is reversible, so it only records the coverage
the catalog reported; this also keeps assets whose coverage is still unknown (the e2e harness) able
to enter their window. An asset whose coverage is `PARTIAL`, `NONE` or unknown when its window ends
is refused (`coverage-not-full`, audited) and stays restorable. Moving the check to admission would
turn every such delete into a refusal; that decision is closed.

Every executed or refused action writes a record to the `retire.delete` stream of the tamper-evident
audit log (docs/specs/audit-log.md): `retire.delete.soft_deleted`, `.task_requested`,
`.hard_delete_requested`, `.refused` (with `details.reason`), `.dry_run`, `.apply` (REQUESTED before,
SUCCEEDED or FAILED after), `.executed`, `.proof_overdue`, `.restored` (REQUESTED, then SUCCEEDED or
FAILED, when a restore removes a rule).

Further records: `.rule_ended` (REQUESTED, then SUCCEEDED or FAILED) when the executor removes a rule
whose maximum age passed (RET-06).

Nothing is silent (Q-632): a task asset with a non-UUID id, a task row that cannot be processed, an
approval that could not be requested and a restore that is refused each write `.refused`; a policy
dry run (`dryRun: true` on `dlap.policies.actions`) executes nothing and writes `.dry_run` with
`details.stage = policy_dry_run`. Request validation errors that name no asset (no scope, too many
assets) are HTTP 400 answers, not refused actions.

**An apply is never repeated (Q-633, Q-683).** The provider call is followed by the `.apply` SUCCEEDED
append. If that append fails, the disposition is saved as applied with `success_audit_pending`, the
sweep does not roll the apply back, and the next sweep writes the missing record without calling the
provider again. A disposition that was applied (`applied_at` set) is never applied a second time,
whatever its status (idempotency by disposition id): found `AWAITING_PROOF` or `PROOF_OVERDUE` the row
goes back to waiting for its proof; found in any other status the row is refused `already-applied`,
audited, and the provider is not called.

## Native rules (decision D20)

| Asset kind | Primitive | Rule | Lifecycle signature in the logs |
|---|---|---|---|
| `S3_BUCKET` | S3 lifecycle configuration | `Expiration.Days` N (age-based, N at least 1), noncurrent versions after 1 day, incomplete uploads after 1 day; refused on a versioned bucket | CloudTrail `s3lifecycle.amazonaws.com` or `lifecycle.s3.amazonaws.com`; server access log `S3.EXPIRE.OBJECT` (never `S3.CREATE.DELETEMARKER`) |
| `GCS_BUCKET` | GCS bucket lifecycle | `Delete` at `age` N; noncurrent versions 1 day after they became noncurrent; refused on a versioned bucket | usage log user agent `GCS Lifecycle Management` [unverified: needs a live capture] |
| `BLOB_CONTAINER` | Azure Blob lifecycle management | `delete` of base blobs `daysAfterModificationGreaterThan` N, snapshots and versions `daysAfterCreationGreaterThan` N, under `container/`, block blobs | user agent `ObjectLifecycleManagement` or a system principal [unverified: needs a live capture] |
| `BIGQUERY_TABLE` | Table expiration | `expirationTime` one hour after planning (an earlier one is kept) | system event `InternalTableExpired` |
| `S3_OBJECT`, `GCS_OBJECT`, `BLOB_OBJECT` | none exact (a prefix also matches siblings) | the engine's own delete | any provider delete event on the object |
| anything else | none | refused with `no-executor` before anyone is asked to approve | |

Appliers read the platform's current configuration, keep every rule DLAP did not write and replace
only DLAP's own (rule names start with `dlap-retire-`, `dlapretire` on Azure). Least privilege:

| Platform | Permissions |
|---|---|
| S3 | `s3:GetLifecycleConfiguration`, `s3:PutLifecycleConfiguration` (which also covers `DeleteBucketLifecycle`, used when a restore removes the last rule), `s3:GetBucketLocation`, `s3:GetBucketVersioning` (the versioned-bucket check) |
| GCS | `storage.buckets.get` (which also answers the versioning check), `storage.buckets.update` |
| Azure | `Microsoft.Storage/storageAccounts/managementPolicies/read` and `/write`, plus `/delete` for a restore that removes the last rule, and `Microsoft.Storage/storageAccounts/blobServices/read` for the versioning check (RET-08, Q-694); list the accounts in `dlap.autodelete.native-rule.azure-storage-account-ids` |
| BigQuery | `bigquery.tables.get`, `bigquery.tables.update` |

## Restore and the pending hard-delete approval (Q-621, Q-676)

A record in `HARD_DELETE_PENDING_APPROVAL` has a `HARD_DELETE` request waiting in the policy engine.
A restore withdraws it through the policy engine's service-account-only operation
`POST /api/v1/policyengine/approvals/{id}/withdraw` (APPR-03), called through the approval Feign client
with the executor's **own** client-credentials token, never the restoring person's. The body is
`{"reason": "...", "onBehalfOf": "<the restorer's JWT sub>"}`; the reason names the restored record.
The withdrawal is allowed only from `PENDING`. The outcome is in the restore answer and the audit record
as `approvalWithdrawal`:

| Outcome | When |
|---|---|
| `WITHDRAWN` | 200, the request is now `WITHDRAWN` |
| `APPROVAL_ALREADY_DECIDED:<status>` | 409 (or a 200 with another status): someone approved, rejected or released it, or it expired, first |
| `REQUEST_NOT_FOUND` | 404 whose body reason is `not-found` (approvals.md section 8): the policy engine does not know the request (RET-08, Q-700); logged and noted in the restore details |
| `ENDPOINT_NOT_AVAILABLE:http-404` or `:http-405` | any other 404, and every 405: the policy engine has no withdraw operation (an older release); logged and recorded in the restore details (`approvalWithdrawalNote`) |
| `REFUSED:<reason>` | 400, 401 or 403 |
| `UNREACHABLE:<detail>` | no usable answer |
| `NOT_ATTEMPTED` | no pending approval |

The restore does not depend on it: a `RESTORED` record is never executed, and a request that stays
pending expires on its own.

## Storage reclaimed (Q-619)

The dashboard KPI "storage reclaimed" (and the total with its month and year parts) sums
`soft_delete_records.size_bytes` over the records in `HARD_DELETED`: the catalog's size of the asset
as of the hard-delete step (Flyway V7), counted only after the provider's log proved the deletion. An
unknown size counts as zero, and a `RULE_IN_FORCE` bucket counts nothing because the bucket is not
deleted, so the figure is a lower bound. The legacy `dlap_deletion_audit` table is not written by the
executor and is no longer read.

## Two replicas

The sweeper is one ShedLock job (`autodelete-retire-sweep`, database time, Flyway V5 table), and each
row is claimed with `SELECT ... FOR UPDATE SKIP LOCKED` in its own transaction, so two replicas, or a
replica whose lease ran out, never act on the same row (Q-548, `RetireSweeperLockContainerTest`). The
rule-end job (`autodelete-rule-in-force-end`) works the same way (`RuleInForceExpiryContainerTest`).

## API

| Method and path | Roles | What |
|---|---|---|
| `GET /api/v1/autodelete/would-delete?page=0&size=50` | ADMIN, CDO, DIRECTOR, POLICY_MANAGER, AUDITOR | assets held for deletion (including rules in force), soonest first, with the rule that would delete each |
| `POST /api/v1/autodelete/restores/{recordId}` | ADMIN, POLICY_MANAGER, DIRECTOR | restore while nothing was touched in the cloud, or remove the rule of a `RULE_IN_FORCE` record or of an applied container rule still `AWAITING_PROOF`; the restorer is the JWT subject. The answer has `ruleRemoved`, `approvalWithdrawal` and, after a rule removal, a `message` that objects already expired are gone. A rule that is not found answers 409 with `status: RESTORE_FAILED`, `ruleRemoved: false`, `reason: rule-not-found` |
| `GET /api/v1/deletion/tasks` | ADMIN, CDO, DATA_ENGINEER, POLICY_MANAGER, DIRECTOR (+ the service account) | the soft-delete records (the restore list), with `status` `QUEUED`, `IN_PROGRESS`, `COMPLETED`, `DRY_RUN`, `RULE_IN_FORCE`, `RULE_ENDED`, `RULE_ENDED_UNPROVEN`, `RESTORE_FAILED`, `REJECTED`, `FAILED` or `CANCELLED`; a `RULE_IN_FORCE` row carries `ruleAppliedAt`, `ruleAgeDays` and `ruleEndsAt`; everyone who can restore can list (Q-624) |
| `POST /api/v1/deletion/tasks` | ADMIN, CDO, DATA_ENGINEER | ask for deletes: one approval per asset on behalf of the caller; nothing is deleted here |
| `POST /api/v1/deletion/tasks/{id}/approve`, `/reject` | ADMIN, CDO | retired: answers 409 and names the policy engine approval API |

## Settings

| Property (env) | Default | Meaning |
|---|---|---|
| `dlap.autodelete.dry-run-cloud-delete` (`DLAP_AUTODELETE_DRY_RUN_CLOUD_DELETE`) | `true` | `false` applies rules and engine deletes; only in a pilot that deletes |
| `dlap.autodelete.soft-delete-window-days` | `30` | window when none is named |
| `dlap.autodelete.window-floor-days` | `7` | shortest window, at least 1 |
| `dlap.autodelete.hard-delete-required-approvals` | `2` | 2 to 10 |
| `dlap.autodelete.sweep.interval` / `.initial-delay` / `.lock-at-most-for` | `PT1M` / `PT1M` / `PT10M` | the sweeper |
| `dlap.autodelete.proof.overdue-after` / `.clock-skew` | `P3D` / `PT10M` | the log proof |
| `dlap.autodelete.native-rule.azure-storage-account-ids` | empty | ARM ids of the storage accounts DLAP may change |
| `dlap.autodelete.native-rule.container-expiration-days` (`DLAP_AUTODELETE_CONTAINER_EXPIRATION_DAYS`) | `1` | age in days at which a bucket, prefix or container rule deletes an object; 1 to 3650 |
| `dlap.autodelete.rule-in-force.max-age` (`DLAP_AUTODELETE_RULE_IN_FORCE_MAX_AGE`) | `P30D` | how long after the apply the executor removes its own container rule, proven (`RULE_ENDED`) or not (`RULE_ENDED_UNPROVEN`) |
| `dlap.autodelete.rule-in-force.interval` / `.initial-delay` / `.retry-after` / `.batch-size` / `.lock-at-most-for` | `PT1H` / `PT5M` / `PT6H` / `50` / `PT10M` | the rule-end job |
| `dlap.autodelete.test-endpoint.s3` / `.s3-path-style` (`DLAP_AUTODELETE_TEST_ENDPOINT_S3`, `DLAP_AUTODELETE_TEST_ENDPOINT_S3_PATH_STYLE`) | empty / `true` | TEST ONLY (RET-08, Q-713): the S3 applier talks to this emulator (LocalStack, moto) instead of AWS, path-style by default; default credentials when they resolve, else anonymous |
| `dlap.autodelete.test-endpoint.gcs` (`DLAP_AUTODELETE_TEST_ENDPOINT_GCS`) | empty | TEST ONLY: the GCS applier talks to this host (fake-gcs-server) without credentials |
| `dlap.autodelete.test-endpoint.azure` (`DLAP_AUTODELETE_TEST_ENDPOINT_AZURE`) | empty | TEST ONLY: the Azure applier sends its Resource Manager calls to this https endpoint with a placeholder token |
| `dlap.autodelete.aws.credentials-ref` (`DLAP_AUTODELETE_AWS_CREDENTIALS_REF`) | empty | EXEC-01 (Q-722, ADR-0110): an IAM role ARN, or the name of a variable that holds one (optionally `env:NAME`). Empty: the SDK default chain (instance profile, environment keys, shared profile, or an EKS web identity token through IRSA) is the Actor. Set: the executor assumes that role with STS (session name `dlap-autodelete`) for every S3, RDS and DynamoDB call. A value that is no role ARN stops the start; the role and the external id are never logged |
| `dlap.autodelete.aws.external-id` / `.sts-endpoint` / `.sts-region` (`DLAP_AUTODELETE_AWS_EXTERNAL_ID`, `..._STS_ENDPOINT`, `..._STS_REGION`) | empty | the `sts:ExternalId` of the role's trust policy; an STS endpoint URL (a VPC endpoint or an emulator); the region of the STS call (empty: the default region chain, else us-east-1) |
| `dlap.policyengine-service.url` (`POLICYENGINE_SERVICE_URL`) | `http://da-policyengine:8080` | the approval API |
| `dlap.audit.datasource.url` / `.username` / `.password` | derived | `dlap_common` for the audit writer; defaults to `spring.datasource.*` with the database replaced |

A value below its floor stops the service at start-up with the property's name. A test endpoint that is
not an absolute http or https URI (https for Azure) stops it too. While any test endpoint is set, its
applier logs a WARN at start-up naming it, and the `prod` profile refuses to start with any of them set
(`TestEndpointGuard`, EXEC-01, Q-745), naming the property and never the value. da-autoarchival uses the same
scheme, `dlap.autoarchival.test-endpoint.*`. The lifecycle
harness (`e2e-tests/cloud-lifecycle-e2e.sh aws`) points the S3 applier at LocalStack this way.
