<!--
  Licensed to the Apache Software Foundation (ASF) under one or more
  contributor license agreements.  See the NOTICE file distributed with
  this work for additional information regarding copyright ownership.
  The ASF licenses this file to you under the Apache License, Version 2.0
  (the "License"); you may not use this file except in compliance with
  the License.  You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
-->

# da-autoarchival

The archive path of the Retire stage (port 8086). An approved archive action becomes the cloud's own
lifecycle rule (S3 lifecycle transition, GCS `SetStorageClass`, Azure management policy tiering). DLAP
writes the rule next to the bucket's existing rules, reads it back, and then waits for the provider's own
log event before it says any object moved.

The full design, audit records, limits and known gaps are in
[docs/NATIVE_ARCHIVE_RULES.md](docs/NATIVE_ARCHIVE_RULES.md). The approval rules it follows are in
`docs/specs/approvals.md` section 9.

## What the statuses mean

| Row status | Meaning |
|---|---|
| `PLANNED` | the action is planned and waits for an executable approval |
| `EXECUTING` | one worker has claimed the row (one conditional update) and is running it |
| `APPLIED` | the rule is in place at the provider (written and read back); no transition seen yet |
| `RENDERED` | the provider has no API DLAP calls; the rule document is ready for a person to apply |
| `EXECUTED` | a provider log event proved a transition (the only proof; nothing else sets it) |
| `FAILED`, `REFUSED`, `REMOVED` | the attempt failed, the executor refused, or DLAP stopped managing the rule |

A task adds two end states that no row has. `RULE_REMOVED`: a restore took the rule out of every asset after the
rule had fired, so no object was restored (a task with at least one genuinely restored asset is still `RESTORED`).
`ALREADY_HELD`: every asset of the request is already covered by an identical live GCS row, so there was nothing to
plan. Neither is a failure.

The "assets archived" figure in the overview counts `EXECUTED` rows, never `APPLIED` ones.

## Closing a row stuck in `EXECUTING`

A worker that dies between claiming a row and writing its outcome leaves the row `EXECUTING`, and nothing runs it
again (that could apply a rule twice). An administrator closes it with
`POST /api/v1/archival/rules/{ruleId}/reset` (`ROLE_ADMIN`). The call reads the provider first and never applies
anything: a present rule makes the row `APPLIED`, an absent one makes it `FAILED` with reason `RESET_RULE_ABSENT`.
It works only on an `EXECUTING` row and is written to the audit log (`reset_requested` before, `rule_reset` after).
Single-object archives and render-only providers are refused (409 `UNREADABLE`); close those by hand, see the
next section. The worker's own late outcome cannot overwrite a reset: its `APPLIED`, `RENDERED` and `FAILED` writes
are conditional on `EXECUTING`.

## Closing a row the reset cannot read

A `DIRECT_OBJECT` row (one object, changed with a direct storage class call) and an Azure render-only row (DLAP
writes no Azure policy) have no provider rule DLAP can read, so the reset answers 409 `UNREADABLE`. Close them by
hand after the checks below. Both are rare: they need a worker that died between the claim and its outcome.

1. Confirm the row is stuck. `SELECT rule_id, mode, provider, status, native_class, asset_uri, updated_at,
   last_error FROM dlap_autoarchival.dlap_archive_rules WHERE rule_id = '...'` shows `status = 'EXECUTING'` with
   an `updated_at` older than `dlap.autoarchival.reconcile.claim-timeout`, and the worker is gone (no pod
   running that claim).
2. Read the audit log (`retire.archive` stream) for the row's approval id. A `retire.archive.requested` record
   without a `rule_applied` or `failed` record means the outcome is unknown, which is why you check the provider.
3. Check what the provider holds.
   - `DIRECT_OBJECT`: read the object's storage class. `gcloud storage objects describe gs://BUCKET/KEY
     --format="value(storageClass)"`, `aws s3api head-object --bucket BUCKET --key KEY --query StorageClass`
     (STANDARD is reported as empty), or `az storage blob show --container-name C --name KEY --query
     properties.blobTier`. The class equals the row's `native_class`: the change was made. Anything else: it was not.
   - Azure render-only: DLAP applied nothing at Azure, so there is nothing to check there. Whether the person who
     owns the account applied the rendered rule is their record, not DLAP's.
4. Close the row with one conditional statement (it changes nothing unless the row is still `EXECUTING`):
   - the change was made (`DIRECT_OBJECT` only): `UPDATE dlap_autoarchival.dlap_archive_rules SET status =
     'APPLIED', applied_at = updated_at, last_error = NULL, updated_at = now() WHERE rule_id = '...' AND status =
     'EXECUTING'`. The proof service takes it from there.
   - it was not made, and for every Azure render-only row: `UPDATE dlap_autoarchival.dlap_archive_rules SET status
     = 'FAILED', last_error = 'MANUAL_RESET: closed by an operator after checking the provider', updated_at =
     now() WHERE rule_id = '...' AND status = 'EXECUTING'`. Plan the action again afterwards.
5. Record why. The database change is outside the audit chain, so put the ticket or the operator's note in the
   change record and tell the audit owner. The task view is recomputed from its rows at the task's next row change;
   until then it may still show the old state, and the row is the record of truth.

This stays a manual fix because DLAP has no way to read these rows back. Adding a provider read for a single
object or an Azure policy is a feature, not a repair, and has not been needed.

## Restore removes the rule; it does not move objects back

`POST /api/v1/archival/tasks/{id}/restore` takes the native lifecycle rule out, so no further objects move.
**Objects the provider already transitioned stay in their colder class.** DLAP does not move them back;
retrieve them with the provider's restore tooling. The restore response and the asset status say so.

A rule that was already at the provider when DLAP applied it (a GCS rule has no id, so an identical customer
rule counts) is never removed by a restore. The row records this as `already_present`.

A restore of a rule that already fired ends the asset as `RULE_REMOVED`: the rule is gone, the transitioned objects
stay where they are, and the asset says so. That is a normal end state, not a failure.

**Operator check before the first restore of a GCS row applied before Flyway V3 (Q-668).** Rows applied before V3
read `already_present=false` because nothing recorded the fact then. For such a GCS row a restore removes the
provider rule even if it was the customer's own identical rule. No production GCS rows exist, so this is accepted
for the pilot. Before the first restore of any GCS row that was applied before V3, check the bucket:

1. List the bucket's lifecycle rules (`gcloud storage buckets describe gs://BUCKET --format="json(lifecycle_config)"`).
2. If a rule with the row's class, age and prefix existed before DLAP applied it (the rule's creation time in the
   bucket's audit log, or the customer's own configuration), set the row's flag first:
   `UPDATE dlap_archive_rules SET already_present = true WHERE rule_id = '...'`. The restore then leaves the rule.
3. Otherwise restore as usual.

Rows applied after V3 need no check.

## Duplicate GCS scope

A GCS lifecycle rule has no id, so a second DLAP row for an identical live scope (same bucket, prefix, class and
age) is refused at planning with reason `DUPLICATE_SCOPE`; the message names the existing row. Remove or restore
the first row to plan another. The dry run (`POST /api/v1/archival/rules/plan`) answers 409.

A policy that fires again for a scope an identical live row already holds, and an operator request for it, are not
refused: the scope is already done. The asset ends `ALREADY_HELD`, no approval is asked, and one audit record
`retire.archive.already_held` is written for the holding row; later fires write nothing more. A policy fire whose
assets are all held stores no task.

Flyway V4 adds the partial unique index `ux_archive_rules_live_gcs_scope` so the database enforces this too, for
two approvals planned in the same instant. **V4 stops, and changes nothing, if live GCS rows already share a scope.**
The message lists each group and its rows, for example `bucket b, prefix "p/", class COLDLINE, after 0 days: rows
dlap-archive-x (APPLIED), dlap-archive-y (EXECUTING)`. Before starting the service again, keep one row per group and set each extra row to `REMOVED` by hand:
`UPDATE dlap_autoarchival.dlap_archive_rules SET status = 'REMOVED', updated_at = now() WHERE rule_id = '...'`. That
stops DLAP managing the extra row and leaves the provider rule, which the row you keep relies on. Do not restore the
extra rows through the API: a restore removes the provider rule they share with the row you keep.
Then start the service; Flyway applies V4.

## Watching for rules that never fire

A scheduled job (ShedLock singleton, injected clock) lists real rules that stay `APPLIED` for longer than
`dlap.autoarchival.reconcile.max-applied` plus their own `after_days` without proof, and rows stuck in `EXECUTING`. It logs them and
exposes `dlap_autoarchival_archive_rules_unproven` and `dlap_autoarchival_archive_rules_stuck`. It changes
no state.

## Build and test

```bash
./gradlew :da-autoarchival:compileJava
./gradlew :da-autoarchival:test      # the container tests need Docker
```
