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

# Archive on the cloud's own lifecycle rule (RET-03)

Decision D20: DLAP does not copy objects to a colder class itself when the cloud can do it. It writes
the cloud's lifecycle rule, the cloud moves the objects on its own schedule, and DLAP marks the archive
**executed** only when the cloud's own log says a transition happened.

Contracts this service implements: [`docs/specs/approvals.md`](../../docs/specs/approvals.md) section 9
(executor rules) and [`docs/specs/audit-log.md`](../../docs/specs/audit-log.md) (stream `retire.archive`).

## 1. The path of one archive

```
policy fires ARCHIVE --> policy engine holds it (ApprovalGate) --> people approve (APPR-01)
   --> dlap.policies.actions carries an `approval` object
   --> PolicyActionEventListener --> ArchivalTaskServiceImpl.processArchivalAction
         1. approvals.md 9.1  the action must carry approval.status APPROVED or APPROVED_BREAK_GLASS
                              for class ARCHIVE; otherwise: refused, audited DENIED, acknowledged
         2. 9.5               a repeated approvalId or fireId is the same decision (unique key)
         3. ArchiveExecutor.prepare   plan the rule, store a PLANNED row (dlap_archive_rules)
         4. ArchiveExecutor.execute
              9.3   re-read GET /approvals/{id} as ROLE_SERVICE_ACCOUNT; act only if executable,
                    for ARCHIVE, for this asset, AND for this place and rule: the approval's own
                    assetUri, provider, prefix, tier and afterDays must match the stored row
                    (a forged event cannot move an approval to another bucket or a wider prefix;
                    refused ASSET_MISMATCH, audited DENIED, provider never called)
              claim the row: one conditional UPDATE PLANNED -> EXECUTING (Q-609). Of several workers
                    racing for the row (policy event, approval-decided event, POST /rules/{id}/retry)
                    the database lets one through; the others return without a record or a provider call.
                    Every status write made before the claim (refused, approval check failed, task
                    rejected) is the same kind of conditional update (ArchiveRuleRepository.endIfPlanned),
                    so a worker holding an old copy of the row cannot overwrite the winner's claim or outcome
              audit retire.archive.requested   (REQUESTED; if this fails, nothing runs)
              provider.apply  (merge into the bucket's rules, read back)
              audit retire.archive.rule_applied   (or rule_rendered / failed)
         5. row status APPLIED        "the rule is in place"; the task shows RULE_APPLIED
   ... later ...
   ArchiveProofListener reads dlap.log.event.normalized
         a SUCCESS provider event in scope, after the apply began --> row EXECUTED
         audit retire.archive.executed (occurredAt = the provider's event time)
```

An archive requested through `POST /api/v1/archival/tasks` asks the policy engine for one approval per
asset (`onBehalfOf` the caller, so the caller can never decide it), stores the approval id, and runs when
the `dlap.approvals.decided` event arrives (approvals.md 9.4). The event is only a prompt: the approval is
re-read before anything runs. The older single-administrator `POST /tasks/{id}/approve` no longer starts
an archive; it answers with a pointer to the approvals API.

Rejecting a waiting task (`POST /tasks/{id}/reject`) refuses its waiting rows so a later approval cannot run
them, and writes a `retire.archive.refused` record (reason `TASK_REJECTED`, actor the operator) for each row.
An approval that names no location cannot be bound to an archive and is refused. When an approval is silent on
the tier (the usual case for a policy whose action sets no `targetStorageClass`), it is bound to the tier the
planner would have used: the configured default tier (`COLD` when unset), read when the action runs. A row on any
other class is refused `ASSET_MISMATCH`, and so is a row planned under a default that has since changed. The age
is then the minimum of that default class, not of the row's own class.

## 2. What is written, per cloud

| Cloud | Rule | DLAP applies it | Limit used |
|---|---|---|---|
| AWS S3 | lifecycle rule with a `Transition` to `STANDARD_IA`, `ONEZONE_IA`, `GLACIER_IR`, `GLACIER` or `DEEP_ARCHIVE`, filter `Prefix` | yes: read all rules, keep them, add or replace only `dlap-archive-<12 hex>`, write, read back | 1000 rules per bucket |
| GCP GCS | bucket lifecycle rule, action `SetStorageClass`, condition `age` and `matchesPrefix` | yes: same merge, with a metageneration precondition and one retry | 100 rules per bucket |
| Azure Blob | management policy rule `tierToCool`, `tierToCold` or `tierToArchive`, `daysAfterModificationGreaterThan`, `prefixMatch` | **no**: a policy is written through Azure Resource Manager, which this service has no permission or SDK for. The task ends `RULE_RENDERED` with the document ready to apply (`az storage account management-policy create`) | 100 rules per account policy, unknown to DLAP |

A single object (a key that does not end in `/`) is not a rule: a prefix rule on `a/b` would also move
`a/b2`. It gets a direct storage class change of exactly that object (`DIRECT_OBJECT`), under the same
approval, audit and proof rules.

## 3. Tiers

`COOL`, `COLD` and `ARCHIVE` are the neutral tiers. The default is `COLD`. A provider class name or one
of the older AWS-flavoured names is accepted too. Anything that is not an archive class (`STANDARD`,
`HOT`, and the old shipped default `STANDARD_ARCHIVE`, which no cloud has) is refused, never guessed.

| Neutral | AWS | GCP | Azure |
|---|---|---|---|
| `COOL` | `STANDARD_IA` (30 days minimum) | `NEARLINE` | `Cool` |
| `COLD` (default) | `GLACIER_IR` | `COLDLINE` | `Cold` |
| `ARCHIVE` | `DEEP_ARCHIVE` | `ARCHIVE` | `Archive` |

Other accepted names: `ONEZONE_IA`, `GLACIER` (AWS Glacier Flexible; GCP and Azure archive), `DEEP_ARCHIVE`,
`GLACIER_IR`, `NEARLINE`, `COLDLINE`, `STANDARD_IA`. The saved archival configuration refuses a default tier
that does not resolve on all three clouds (`PUT /api/v1/archival/config` answers 400).

Action parameters read from `actionParameters`: `tier` (or `targetStorageClass`, `targetTier`),
`prefix` (narrows a bucket or folder asset), `afterDays` (default: the class minimum, 0 for most).

## 4. Limits (all enforced before anything is written)

| Limit | Value | Source |
|---|---|---|
| Rules per bucket the provider allows | S3 1000, GCS 100, Azure 100 | provider |
| Rules DLAP keeps per bucket | 20 (`rules.max-dlap-rules-per-bucket`, at most 90) | configuration |
| Prefix length | 1024 bytes UTF-8 (`rules.max-prefix-bytes`) | S3 and GCS key limit |
| Age | at least the class minimum (S3 IA 30 days), at most 3650 (`rules.max-after-days`) | provider and configuration |
| Whole-bucket rule | allowed; `rules.allow-bucket-wide=false` requires a prefix | configuration |
| Dry-run listing | 50 objects by default, 1000 at most | configuration |
| Locations | an `http(s)` URL with a query or fragment, key edge white space and non-storage assets are refused | `LocationSafety` |

The limits are checked when the rule is planned and again right before it is applied.

**Duplicate scope (Q-673).** A GCS lifecycle rule has no id, so two DLAP rows with the same bucket, prefix,
class and age would share one provider rule, and restoring one would remove the rule the other relies on. A
second row for an identical live scope (a row of the same provider, bucket, prefix, class and age in
`PLANNED`, `EXECUTING`, `APPLIED` or `EXECUTED`, not mocked) is refused with reason `DUPLICATE_SCOPE`, and the
message names the existing rule, its status and its approval. The check also runs in the dry run (409) and
before an approval is asked, so nobody approves a rule that planning would refuse. S3 and Azure rules carry the
row's own id, so they do not collide.

*Already held is already done (Q-711).* A policy that fires again for a scope an identical live row holds, or an
operator request for it, is not a failure and is not refused. The asset ends `ALREADY_HELD` (message: nothing to
plan, naming the holding row), nobody is asked to approve anything, and exactly one audit record is written for the
holding row and asset: `retire.archive.already_held` (SUCCEEDED, correlation id = the holding row's approval id).
Its event is built from the holding row alone, so a later fire appends the identical event and the idempotency key
returns the first entry: nothing more is written on later fires. A policy fire whose every asset is already held
stores no task at all; an operator request stores a task with status `ALREADY_HELD` so the caller sees the answer.
A request that mixes held and new assets keeps a task for the new ones; the held ones count neither as pending nor as
failed. The dry run and the pre-approval check through `POST /rules/plan` still answer 409 `DUPLICATE_SCOPE`.

*The race (Q-710).* The planner reads before the row is stored, so two approvals planned in the same instant could
both pass. Flyway V4 adds the partial unique index `ux_archive_rules_live_gcs_scope` on
`(provider, scope_bucket, scope_prefix, native_class, after_days)` for rows with provider `GCP`, mode
`NATIVE_RULE`, not mocked, in `PLANNED`, `EXECUTING`, `APPLIED` or `EXECUTED`. The database lets one insert through;
the other is reported as the same `DUPLICATE_SCOPE`, naming the winner. V4 first looks for existing duplicates and,
if there are any, stops with a message naming the rows and changes nothing (README, "Duplicate GCS scope"). When
`submitArchivalTask` runs inside one transaction the loser's failed insert aborts that transaction, so the operator
call answers with an error instead of the clean refusal and the request is simply repeated; a policy fire has no
outer transaction and gets the clean answer.

## 5. Dry run

`POST /api/v1/archival/rules/plan` (ADMIN, POLICY_MANAGER, DIRECTOR, DATA_ENGINEER)

```json
{ "assetId": "<catalog asset uuid>", "tier": "COLD", "prefix": "2019/", "afterDays": 0, "listingKeys": 50 }
```

It takes a catalog asset, never a bucket name, resolves its location through the catalog, reads the
provider (rule count, a bounded `ListObjects`) and writes nothing. The answer holds the rule id, the
provider's own rule document, how many rules the bucket has, up to `listingKeys` objects with size and
current class, whether more match, and notes (for example "the rule covers every object, including
objects written later"). A policy `dryRun: true` fire plans the same way and logs a summary.

`GET /api/v1/archival/rules` and `GET /api/v1/archival/rules/{ruleId}` show the records with their
evidence (ADMIN, POLICY_MANAGER, DIRECTOR, DATA_ENGINEER, AUDITOR). `POST /api/v1/archival/rules/{ruleId}/retry`
(ADMIN) runs a rule that stayed `PLANNED` because the approval could not be checked; the approval is
checked again.

## 6. Audit records (stream `retire.archive`, writer role `da_autoarchival_user`)

| Event type | Outcome | When |
|---|---|---|
| `retire.archive.requested` | REQUESTED | before the provider call; if the append fails the action does not run |
| `retire.archive.rule_applied` | SUCCEEDED | the rule or direct change is in place (details: rule counts, `alreadyPresent`, `mock`) |
| `retire.archive.rule_rendered` | SUCCEEDED | the document was produced and not applied (Azure) |
| `retire.archive.failed` | FAILED | the provider refused or the read-back did not show the rule |
| `retire.archive.refused` | DENIED | no executable approval, a limit, or an approval that ended REJECTED or EXPIRED |
| `retire.archive.already_held` | SUCCEEDED | an identical live GCS row already holds the scope (section 4); one record per holding row and asset, nothing more on later fires |
| `retire.archive.executed` | SUCCEEDED | a provider event proved the transition; `occurredAt` is the provider's time |
| `retire.archive.rule_removed`, `retire.archive.restore_*` | | restore requests and outcomes; `rule_removed` carries `providerRuleKept` (see section 8) |
| `retire.archive.reset_requested` | REQUESTED | an administrator resets a row stuck in `EXECUTING` (section 8a); appended before the row changes, and a failed append means no reset |
| `retire.archive.rule_reset` | SUCCEEDED, FAILED | the row left `EXECUTING` (new status and, when absent, `reason=RESET_RULE_ABSENT`), or FAILED with `reason=ROW_CHANGED` when it had left `EXECUTING` already |

Every record has `correlationId` = the approval id and an idempotency key (`<approvalId>:<step>`), so a
retry returns the first entry. Details carry the approval id, the approvers' `sub`, the rule id, the
bucket, prefix, class and age. They never carry a credential, a signed URL or object content.

## 7. The log proof

A row stays `APPLIED` (or `RENDERED`) until a normalized provider log event proves a transition. An event
proves a row only if all of these hold: result `SUCCESS`; not a replay (unless the test switch is on); the row
was really applied (a mocked apply wrote nothing, so no event can be about it); the
target is an object inside the row's bucket and prefix (exactly the key for a direct change); the event
time is not before the apply began; the operation is the provider's transition operation for the mode;
and, when the event reports the resulting class, it equals the row's class. The bucket-level event of
DLAP writing the rule (`PutBucketLifecycleConfiguration`, `storage.buckets.update`) never counts.

| Provider | Rule fired | Direct change |
|---|---|---|
| AWS | CloudTrail `LifecycleTransition`, or one of the six S3 server access log operations below (the operation must name the row's class) | `CopyObject` |
| GCP | **no audit log operation exists**: Cloud Audit Logs do not record Lifecycle Management actions (research/hardening/02-logs-gcp.md). Only a crawler or inventory observation of the object's class counts, and it must report when the class changed (`timeStorageClassUpdated`) | `storage.objects.update`, `rewrite`, `copy` |
| Azure | `SetBlobTier` | `SetBlobTier` |

The S3 server access log operations are those of the AWS page "S3 Lifecycle and logging"
(https://docs.aws.amazon.com/AmazonS3/latest/userguide/lifecycle-and-other-bucket-config.html, retrieved
2026-11-01, Q-644). Anything else, including `S3.TRANSITION_DAA.OBJECT` (not on the page), is no proof:

| Operation | Class it proves |
|---|---|
| `S3.TRANSITION_SIA.OBJECT` | `STANDARD_IA` |
| `S3.TRANSITION_ZIA.OBJECT` | `ONEZONE_IA` |
| `S3.TRANSITION_INT.OBJECT` | `INTELLIGENT_TIERING` |
| `S3.TRANSITION_GIR.OBJECT` | `GLACIER_IR` |
| `S3.TRANSITION.OBJECT` | `GLACIER` (Glacier Flexible Retrieval, the code-less name) |
| `S3.TRANSITION_GDA.OBJECT` | `DEEP_ARCHIVE` |

The operation names come from provider documentation. They have not yet been observed on a live account
(open question Q-600); the tests prove the matching logic against fixture events, not the providers'
spelling. A GCS rule therefore stays `RULE_APPLIED` until an object-level observation source feeds the
spine (Q-601).

## 8. Restore

`POST /api/v1/archival/tasks/{id}/restore` **removes the native lifecycle rule. It does not move objects
back.** A rule that has not fired, or has only been rendered, is taken out (audited `rule_removed`). A rule
that has fired is taken out too, so no further objects move, but the objects the provider already
transitioned stay in their colder class: DLAP does not restore them. Retrieve them with the provider's
restore tooling (`RestoreObject` on S3, a rewrite on GCS, `SetBlobTier` on Azure). The restore response says
this, and the asset carries the same sentence instead of claiming a restore. A direct change restores the
object (`restoreData`). Each restore is audited `restore_requested` before and `restored` or
`restore_failed` after.

**A rule that was already there is never removed (Q-608).** GCS lifecycle rules have no id, so an identical
customer rule (same class, age and prefix) counts as DLAP's rule already in place. `ApplyResult.alreadyPresent`
is stored on the row (`already_present`, Flyway V3) and a restore of that row leaves the provider rule where
it is: the row becomes `REMOVED` (DLAP stops managing it), the audit record `rule_removed` says
`providerRuleKept=true`, and the response says DLAP stopped managing the rule. Rows applied before V3 read
`already_present=false`, because nothing recorded the fact then.

A restore ends a fired rule's asset as `RULE_REMOVED`, a normal end state (Q-670): the rule is gone, the
objects that already moved stay where they are, and the asset keeps the explanatory sentence. The restore
step is audited `restore_rule_removed` (SUCCEEDED), not `restore_failed`, and the task counts the asset as a
normal end. A task whose assets all ended `RULE_REMOVED` ends with its own status `RULE_REMOVED` (Q-708, Flyway V4),
not `RESTORED`, because no object was restored; a task with at least one genuinely restored asset and no failure
still ends `RESTORED`, and any failure makes it `PARTIALLY_COMPLETED`. A `RULE_REMOVED` task cannot be restored
again. The per-asset status and message say what happened.

**A restore never overwrites a concurrent change (Q-674).** The restore marks the row `REMOVED` with one
conditional update on the status it loaded, and the proof update marks it `EXECUTED` the same way. Whichever
runs first wins: a proof that finds the row removed records nothing, and a restore that finds the row
`EXECUTED` (the proof landed after the load) follows it once and ends `REMOVED` from `EXECUTED`. A restore of a
row a worker is still applying (`EXECUTING`) is refused with nothing changed, because marking it removed
would be overwritten by that worker's `APPLIED` while the restore reported success.

### 8a. Resetting a row stuck in `EXECUTING` (Q-669)

`POST /api/v1/archival/rules/{ruleId}/reset` (`ROLE_ADMIN` only). It works only on a row in `EXECUTING`
(otherwise 409 `NOT_EXECUTING`). It **reads the provider first and never applies a rule**:

| Provider rule | Result |
|---|---|
| present | the row becomes `APPLIED` (its apply time is the claim time) and waits for provider proof as usual |
| absent | the row becomes `FAILED` with reason `RESET_RULE_ABSENT` |

A single-object archive and a provider DLAP cannot read (Azure, render only) are refused with 409
`UNREADABLE` rather than guessed at; a provider read failure is 502 and changes nothing. A GCS rule has no
id, so a present GCS rule cannot be told from the customer's own: the reset records `already_present=true`
and a later restore leaves the provider rule in place (the safe direction of Q-608). The intent is appended to
the audit log before the row changes (a failed append is 503 and changes nothing), the outcome after. The
update is conditional on `EXECUTING`, so a worker that was only slow cannot be overwritten.

**The claimer's own outcome is conditional too (Q-712).** The worker that claimed a row writes its outcome
(`APPLIED`, `RENDERED`, `FAILED`) with one conditional update on `EXECUTING`. If an administrator reset the row
while the worker was only slow, the reset keeps the row and the late outcome is not written over it. The effect
at the provider is still recorded: the worker appends its `rule_applied` (or `rule_rendered`, `failed`) audit record,
with `rowUpdated=false` in the details when the row was not changed, and logs an error naming the row. A reset that
found the rule absent and made the row `FAILED` while the slow worker then wrote the rule leaves a live provider
rule behind a `FAILED` row: check the bucket for it (the audit record says so) and clean the row up by hand.

**Rows the reset cannot read (Q-709).** A single-object (`DIRECT_OBJECT`) row and an Azure render-only row stuck in
`EXECUTING` stay a manual fix on purpose; the reset answers 409 `UNREADABLE` for them. The README lists the checks
and the SQL ("Closing a row the reset cannot read").

## 9. Configuration

| Property | Default | Meaning |
|---|---|---|
| `dlap.services.da-policyengine.url` (`DA_POLICYENGINE_URL`) | `http://localhost:8083` | the approval API; set it in every deployment |
| `dlap.autoarchival.rules.*` | section 4 | limits |
| `dlap.autoarchival.rules.default-tier` | `COLD` | tier when nothing names one |
| `dlap.autoarchival.rules.accept-replay-evidence` | `false` | tests only |
| `dlap.autoarchival.gcs.mock-mode`, `...azure.mock-mode` | `false` | development without credentials: the rule is recorded with `mock=true`, nothing is sent |
| `dlap.audit.datasource.url` | derived | the `dlap_common` database; empty derives it from `spring.datasource.url`, same user |
| `dlap.autoarchival.test-endpoint.s3` / `.s3-path-style` / `.gcs` / `.azure` (`DLAP_AUTOARCHIVAL_TEST_ENDPOINT_S3`, `..._S3_PATH_STYLE`, `..._GCS`, `..._AZURE`) | empty / `true` | **Test only (Q-713, EXEC-01 Q-740).** The same scheme as `dlap.autodelete.test-endpoint.*`: an S3-compatible endpoint (path-style by default; the default credentials chain signs, else anonymous), a GCS emulator host, an https Azure blob endpoint of a stub (placeholder token). They reach the lifecycle rule providers and the direct-object storage services. Off by default; `EmulatorEndpointGuard` refuses to start under the `prod` profile when any is set, naming the property and never the value |
| `dlap.autoarchival.aws.endpoint-override`, `...aws.endpoint-access-key`, `...aws.endpoint-secret-key`, `...gcs.endpoint-override` | empty | **Deprecated (RET-03 names), removed in the next release.** They still work as aliases of `test-endpoint.s3` and `test-endpoint.gcs` (the keys sign for the S3 test endpoint); each logs a WARN naming its replacement, and the `prod` profile refuses them. `...gcs.endpoint-project` (the emulator's project) is kept |
| `dlap.autoarchival.aws.credentials-ref` (`DLAP_AUTOARCHIVAL_AWS_CREDENTIALS_REF`) | empty | EXEC-01 (Q-722, ADR-0110): an IAM role ARN, or the name of a variable that holds one (optionally `env:NAME`). Empty: the SDK default chain (instance profile, environment keys, shared profile, or an EKS web identity token through IRSA) is the Actor. Set: the executor assumes that role with STS (session name `dlap-autoarchival`) for every S3 call |
| `dlap.autoarchival.aws.external-id` / `.sts-endpoint` / `.sts-region` | empty | the `sts:ExternalId` of the role's trust policy; an STS endpoint URL; the region of the STS call |
| `dlap.autoarchival.azure.account-endpoint` (`DLAP_AUTOARCHIVAL_AZURE_ACCOUNT_ENDPOINT`) | empty | EXEC-01 (Q-724): the account's blob endpoint, for example `https://myaccount.blob.core.windows.net` (https only). The executor signs in with `DefaultAzureCredential`, so the archive Actor role covers the tier change and the listing. Empty: `https://<account>.blob.core.windows.net` from each asset's location for the tier change; the listing needs this setting |
| `dlap.autoarchival.azure.connection-string` (`DLAP_AUTOARCHIVAL_AZURE_CONNECTION_STRING`) | empty | **Deprecated, removed in the next release.** A fallback when no account endpoint is set. Azure RBAC does not govern it and it may hold an account key. It logs a WARN at start-up and the `prod` profile refuses any value |
| `dlap.autoarchival.reconcile.enabled` (`DLAP_AUTOARCHIVAL_RECONCILE_ENABLED`) | `true` | the reconciliation job below |
| `dlap.autoarchival.reconcile.interval`, `.initial-delay`, `.lock-at-most-for` | `PT1H`, `PT5M`, `PT10M` | schedule and ShedLock lease |
| `dlap.autoarchival.reconcile.max-applied` | `P7D` | a rule `APPLIED` longer than this with no proof is listed |
| `dlap.autoarchival.reconcile.claim-timeout` | `PT30M` | a row `EXECUTING` longer than this lost its worker |
| `dlap.autoarchival.reconcile.log-limit` | `50` | rows written to the log per list and run |

**Reconciliation (Q-607).** A singleton job (ShedLock `autoarchival-rule-reconcile`, database time) that reads
the rule table on the injected clock and changes nothing: it counts the real (not mocked) rules `APPLIED`
longer than `max-applied` plus the row's own `after_days` with no provider proof (Q-672: a 90 day rule cannot fire before its objects are 90 days old, so it is listed only after `max-applied` + 90 days), and the rows `EXECUTING` longer than `claim-timeout`; it
logs the oldest of each (WARN, ERROR) and sets the gauges `dlap_autoarchival_archive_rules_unproven` and
`dlap_autoarchival_archive_rules_stuck`. A rule can still be unproven past its cutoff when its prefix holds
no object old enough, so the list is something to look at, not a failure. One pass reads at most 5000
`APPLIED` rows; if it reads that many it logs it and the count is a floor. The gauge
holds the value the replica that ran last found, so alert on the maximum across replicas.

**The "assets archived" KPI (Q-605)** (`GET /api/v1/archival/overview`) counts rule rows `EXECUTED`, that is,
transitions a provider log event proved, plus the older tasks that finished `COMPLETED` before native rules
(nothing sets that status now). A rule that is only `APPLIED` is counted as pending, never as archived.

## 10. What the deployment must provide

- `dlap.service-auth.enabled=true` with a client that carries `ROLE_SERVICE_ACCOUNT` (to read approvals).
- The policy engine reachable at `dlap.services.da-policyengine.url`.
- Kafka read access for `da-autoarchival` to `dlap.log.event.normalized` and `dlap.approvals.decided`, and
  the consumer groups `da-autoarchival-consumer-group-proof` and `...-approvals` (dead-letter topics
  `<topic>.DLT` as for the existing consumer).
- The database role `da_autoarchival_user` may append to stream `retire.archive` (seeded by AUD-01).
- Cloud permissions, least privilege: S3 `s3:GetLifecycleConfiguration`, `s3:PutLifecycleConfiguration`,
  `s3:ListBucket` (and `s3:GetObject`, `s3:PutObject` only for the direct change of one object); GCS
  `storage.buckets.get`, `storage.buckets.update`, `storage.objects.list` (and `storage.objects.get`,
  `storage.objects.create` for the direct change); Azure a data-plane reader for the listing.

## 11. What is proven where

| Claim | Proven by | Not proven by it |
|---|---|---|
| S3 merge keeps other rules, is idempotent, removes only its own | `S3LifecycleRuleProviderContainerTest` (moto) | that S3 runs the transition |
| GCS merge, precondition retry, read-back, limit | `GcsLifecycleRuleProviderTest` (stateful stand-in; fake-gcs-server does not keep `lifecycle`) | the real service |
| No approval, no run; re-read; approval bound to place, prefix, tier and age; repeated approval; reject is audited | `ApprovalVerifierTest`, `PolicyEngineApprovalClientTest`, `ArchiveExecutorTest`, `ArchivalTaskServiceImplTest`, `ArchivalTaskServiceForgedEventTest` | |
| Intent before effect, chained records | `ArchiveExecutorTest` (call order), `ArchiveFlowContainerTest` (real chain, verified) | |
| EXECUTED only from the provider event | `ArchiveProofServiceTest`, `ArchiveTaskSyncTest` | the providers' real operation names |
| Schema, unique approval, evidence constraint | `ArchiveFlowContainerTest` (Flyway V1 then V2 on PostgreSQL 15.1) | |
| End to end through the stack | `e2e-tests/cloud-lifecycle-e2e.sh gcp` | a real bucket |

### Live check (needs a sandbox bucket; not run in CI)

1. In a sandbox project create a bucket with a few objects under `dlap-ret03/`.
2. Call the dry run for the bucket asset with `prefix=dlap-ret03/`, `tier=COLD`; check the listing.
3. Approve the archive; confirm `gcloud storage buckets describe gs://<bucket> --format="json(lifecycle)"`
   shows the new `SetStorageClass COLDLINE` rule next to the old rules.
4. After the next lifecycle run (up to a day), `gcloud storage objects describe` shows `storageClass`
   `COLDLINE` and `timeStorageClassUpdated`; record the audit log entry GCS writes (or its absence) and
   answer Q-600 and Q-601 from it.

## 12. Known gaps

- Azure rules are rendered, not applied (needs an ARM client and the `Storage Account Contributor` role).
- A GCS rule has no audit-log proof; it needs an object-level observation source.
- A fired rule's transitioned objects are not restored by DLAP (section 8).
- A worker that dies between the claim and its outcome leaves the row `EXECUTING`. Nothing runs it again by
  itself (that could apply a rule twice); the reconciliation (section 9, reconciliation) counts and logs such rows,
  and an administrator closes one with the reset (section 8a).
- Two DLAP rows for an identical live GCS scope are refused at planning with `DUPLICATE_SCOPE` (Q-673) and the
  database holds the line with a partial unique index (Q-710; section 4). A scope already held by a live row is
  answered as already done (Q-711).
- A single-object (`DIRECT_OBJECT`) row or an Azure render-only row stuck in `EXECUTING` cannot be reset; it is a
  manual fix by design (Q-709; section 8a and the README).
- A slow worker whose row an administrator reset to `FAILED` (rule absent) may still have written the rule; the audit
  record says `rowUpdated=false` (section 8a, Q-712).
- S3 lifecycle writes replace the whole configuration; another writer changing the same bucket between
  DLAP's read and write is detected afterwards (`VERIFY_FAILED`), not prevented.
- A policy fire for a non-storage asset (a BigQuery table) is refused (`UNSUPPORTED_ASSET`).
