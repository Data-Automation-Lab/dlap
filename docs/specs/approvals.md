# Approvals and dual control

Spec version: 1.2 | Status: normative | Decision: ADR-0095 in `docs/DECISIONS.md` | Implementation: `da-policyengine/src/main/java/com/dlap/policyengine/approval/`

## 1. Purpose

A policy that deletes or archives data must not act on one person's say-so. This spec defines how
DLAP holds a destructive action until enough different people approve it, who may approve, what is
recorded, and how an executor knows an action was approved. The archive and delete executors
(RET-02, RET-03) and the approval inbox (APPR-02) build on this contract.

The key words MUST, MUST NOT, SHOULD and MAY are used as in RFC 2119.

## 2. Rules

1. **Destructive actions wait.** Every archive, soft delete and hard delete a policy fires waits for
   approval, whatever the policy's `requireApproval` says. Any other action waits when its policy
   sets `requireApproval: true`. A dry run (`dryRun: true`) of a destructive action executes
   nothing and does not wait, unless its policy also sets `requireApproval: true`.
2. **N distinct approvers.** A request is approved when `requiredApprovals` different people
   approve it. Distinct means a different JWT `sub`; one person decides one request once.
3. **Floors.** A hard delete never needs fewer than 2 approvals; every other class never fewer
   than 1. A deployment, a policy or an API caller MAY raise the count, never lower it. The
   database enforces the hard-delete floor too.
4. **Separation of duties.** Nobody decides a request they asked for (`requestedBy`), submitted
   (`submittedBy`), or whose policy they authored (the policy's recorded creator and last editor).
   A service account never decides. Withdrawing (rule 11) is not deciding. The authors are recorded:
   the creator of a policy (including a policy a draft publish creates) and each later editor are
   stored on the policy and read by the gate when it holds a fire (section 13, item 3).
5. **The approver is the JWT `sub`.** Every decision records the `sub`, the `preferred_username`
   for display, the role authorities and a comment. A request without a bearer token subject is
   refused.
6. **Decisions are immutable.** A decision row is never updated or deleted; the database refuses
   UPDATE, DELETE and TRUNCATE.
7. **One rejection ends a request.** A REJECT by an eligible approver moves the request to
   REJECTED, whatever approvals it already has.
8. **Requests expire.** A request not decided by `expiresAt` (default 7 days after creation) is
   EXPIRED and never executable.
9. **Break-glass is loud.** An administrator MAY release a pending request without the quorum,
   with a justification of at least 20 characters. It is logged at ERROR, counted and published on
   `dlap.approvals.break-glass`.
10. **Executors verify.** An executor MUST NOT carry out a destructive action unless the action
    carries an executable approval (section 9).
11. **A service account may withdraw.** A pending request that has become pointless, for example
    because the person restored the record a hard delete waits on, is withdrawn by a service
    account on behalf of that person (section 8). It moves to `WITHDRAWN`, a terminal state: not
    approved, not rejected, never executable, never releasable. Only a service account may do it,
    only from `PENDING`, and it is written to the audit log.
12. **Every decision and withdrawal is chained.** Approve, reject, break-glass and withdraw are
    appended to the tamper-evident audit log (`audit-log.md`, stream `govern.approval`) as the last
    step of the transaction that records them (section 8.1). When the append fails nothing is
    recorded and the call answers `503 audit-unavailable`.

## 3. Action classes

| Class | Action | Default approvals | Floor |
|---|---|---|---|
| `ARCHIVE` | an `actionType` that contains `ARCHIVE` (case-insensitive) | 1 | 1 |
| `SOFT_DELETE` | `delete` or `AUTO_DELETE` with `softDeleteWindowDays` above 0 or absent (default 30) | 1 | 1 |
| `HARD_DELETE` | `delete` or `AUTO_DELETE` with `softDeleteWindowDays` 0 or less, with `hardDelete: true`, or with a window that is not a number | 2 | 2 |
| `OTHER` | any other action whose policy sets `requireApproval: true` | 1 | 1 |

The mapping follows the consumer rules of `policy-dsl.md`. When the parameters are unclear the
stricter class applies. A policy MAY raise the count with the action parameter
`requiredApprovers` (an integer, capped at 10). An API caller MAY raise it with `requiredApprovals`
(1 to 10).

A soft delete is reversible inside its window, so it needs one approval. The hard delete at the end
of the window is a separate request of class `HARD_DELETE` that the delete executor asks for
(RET-02).

## 4. States

```
PENDING --(quorum of APPROVE)--------> APPROVED
PENDING --(BREAK_GLASS by an admin)--> APPROVED_BREAK_GLASS
PENDING --(one REJECT)---------------> REJECTED
PENDING --(expiresAt passed)---------> EXPIRED
PENDING --(withdrawn by a service account)--> WITHDRAWN
```

| State | Executable | Meaning |
|---|---|---|
| `PENDING` | no | waiting for decisions |
| `APPROVED` | yes | `requiredApprovals` distinct approvers approved |
| `APPROVED_BREAK_GLASS` | yes | an administrator released it without the quorum |
| `REJECTED` | no | an eligible approver rejected it |
| `EXPIRED` | no | nobody decided in time |
| `WITHDRAWN` | no | a service account withdrew it on behalf of a person (rule 11); no decision row is written |

A request moves once, from `PENDING` to a terminal state. The database refuses any other change of
state, any change of the request's identity columns and any delete. A withdrawn request keeps who
withdrew it (`withdrawnBy`, the service principal's `sub`), for whom (`onBehalfOf`), why
(`withdrawReason`) and when (`withdrawnAt`, from the injected clock, equal to `resolvedAt`); the
database refuses a change to any of the four afterwards, and a `WITHDRAWN` row without all four.

A decision that arrives at or after `expiresAt` is refused with `409 expired`; the request is
moved to `EXPIRED` in the same transaction and the late decision is not recorded. The maintenance
job also expires overdue requests every few seconds.

## 5. Who may do what

Authorization follows `access-model.md`.

| Operation | Roles |
|---|---|
| Ask (`POST /approvals`) | ADMIN, CDO, DIRECTOR, POLICY_MANAGER, DATA_ENGINEER, DATA_SCIENTIST, SERVICE_ACCOUNT |
| Read (`GET /approvals`, `GET /approvals/{id}`) | the roles above and AUDITOR |
| Inbox, approve, reject | ADMIN, CDO, DIRECTOR, POLICY_MANAGER, narrowed by `approver-roles` |
| Break-glass | ADMIN |
| Withdraw | SERVICE_ACCOUNT only; no human role, ADMIN included |

On top of the role:

- **Not a party.** The caller's `sub` is not in the request's `excludedSubjects`: the requester,
  the submitter and the authors of the firing policy.
- **Not a machine.** A token with `ROLE_SERVICE_ACCOUNT` never approves, rejects or breaks glass,
  even when it also carries a human role.
- **MFA.** With `require-mfa-marker=true` the token MUST carry the realm role `dlap-mfa-required`.
  The production realm template grants that marker only through roles whose sign-in runs the OTP
  flow (IAM-02), so the marker proves the approver's sign-in passed MFA. Production MUST set it.
- **Once.** The caller has not decided the request yet.

Only a service account MAY name the person it acts for (`onBehalfOf`). The request then records
that person as `requestedBy` and the service account as `submittedBy`, and excludes both.

The "not a party" and "once" rules above do not apply to a withdrawal: it is not an approval, so the
service account that submitted the request, or acts for the requester, withdraws it.

## 6. Where requests come from

### 6.1 Policy fires

The event consumer (`LifecyclePolicyConsumer`), the schedule sweep (`ScheduledPolicySweeper`) and
the on-demand REST evaluation (`PolicyEvaluationService`, section 6.1.1) route every fire through
`ApprovalGate`. A fire that must wait (section 2, rule 1) is recorded as a
request and is **not published** on `dlap.policies.actions`:

| Field | Value |
|---|---|
| `origin` | `POLICY` |
| `requestKey` | `fire:<fireId>`, so a redelivered event or a re-run schedule slot adds nothing |
| `requestedBy` | `policy:<policyDbId>` |
| `requesterKind` | `POLICY` |
| `submittedBy` | `da-policyengine` |
| `reason` | `actionParameters.auditNote`, else a sentence naming the policy and trigger |
| `actionPayload` | the `PolicyActionRequest` exactly as fired |

At most one request per (policy, asset, action type) is pending at a time: a schedule that matches
the same asset every day adds no second request while the first waits. After the first is decided,
the next fire asks again.

In shadow mode (`dlap.policyengine.actions.enabled=false`) nothing is held: the fire goes to the
producer, which logs and counts it, so a shadow pilot records no approval requests.

If the request cannot be recorded (the database is down), the event record is not acknowledged and
is retried, and a schedule sweep is aborted and re-runs its slot. A destructive fire is never
published to get past an outage.

### 6.1.1 On-demand evaluation

`POST /api/v1/policyengine/{policyId}/evaluations` (ADMIN) fires the policy's actions through the
same `ApprovalGate` (Q-569). An archive, a soft delete or a hard delete that is not a dry run, and
any action of a policy with `requireApproval: true`, is recorded as a request with `triggerType`
`manual` and is not published; a dry run and any other action are published at once, as before.
The fire id is derived from the evaluation id and the action type, so each evaluation asks anew,
and the one-pending-request rule above still applies per (policy, asset, action type). The
response shows where each action went in `evaluationDetails.actionRoutes` (`HELD`,
`ALREADY_HELD`, `PUBLISHED`, `SHADOWED` or `FAILED`); `triggeredActions` lists the actions that
fired, held or not. If the request cannot be recorded, the call fails with the database error,
its evaluation record rolls back and nothing is published.

### 6.2 API requests

A person or a service asks with `POST /approvals` (section 8). Such a request has no
`actionPayload`; nothing is published on `dlap.policies.actions` when it is approved. The executor
that asked acts on the `DECIDED` event or re-reads the request.

## 7. What an approval releases

When a policy fire's request becomes `APPROVED` or `APPROVED_BREAK_GLASS`, the policy engine
publishes the stored fire on `dlap.policies.actions` with two changes:

- `requireApproval` is `false`, because the approval it waited for exists;
- an `approval` object is added:

```json
"approval": {
    "approvalId": "0d4f9c40-1a2b-3c4d-8e5f-000000000002",
    "status": "APPROVED",
    "actionClass": "HARD_DELETE",
    "requiredApprovals": 2,
    "approvers": ["7d0e6c55-...-alice", "a41f9b02-...-bob"],
    "breakGlass": false,
    "decidedAt": "2026-10-21T09:14:03.120004Z"
}
```

Every other field, the `fireId` included, is the fire's own. `approvers` lists the `sub` of each
approval in decision order, plus the administrator on a break-glass release. A fire without an
approval never carries the `approval` key. This is a backward-compatible addition to the
`PolicyActionRequest` v1 wire format (`policy-dsl.md`, Versioning).

If policy actions are disabled when the request is approved, nothing is released and the request
records `dispatchStatus: SHADOWED`.

## 8. REST API

Base path `/api/v1/policyengine/approvals`. The OpenAPI contract is
`docs/api/openapi/policyengine.json`.

| Method and path | Body | Answer |
|---|---|---|
| `POST /` | `ApprovalCreateBody` | 201 the new request; 200 the earlier request with the same `idempotencyKey` from the same caller |
| `GET /?status=&actionClass=&assetId=&page=0&size=50` | none | 200 `ApprovalPageView`, newest first; rows carry no decisions |
| `GET /inbox?page=0&size=50` | none | 200 the pending, unexpired requests the caller may still decide, soonest expiry first |
| `GET /{id}` | none | 200 `ApprovalView` with its decisions |
| `POST /{id}/decisions` | `{"decision": "APPROVE" or "REJECT", "comment": "..."}` | 200 the request after the decision; 503 `audit-unavailable` when the audit log cannot record it (nothing is recorded) |
| `POST /{id}/break-glass` | `{"justification": "..."}` | 200 the request after the release; 503 `audit-unavailable` when the audit log cannot record it (nothing is recorded) |
| `POST /{id}/withdraw` | `ApprovalWithdrawBody` | 200 the request, now `WITHDRAWN`; 409 `{"status": "<current status>", ...}` when it is not `PENDING`; 404 when it does not exist; 503 `audit-unavailable` when the audit log cannot record it (nothing is recorded) |

`ApprovalCreateBody`:

| Field | Required | Notes |
|---|---|---|
| `actionClass` | yes | `ARCHIVE`, `SOFT_DELETE`, `HARD_DELETE` or `OTHER` |
| `actionType` | yes | what the executor runs, for example `delete` |
| `assetId` | yes | catalog asset UUID |
| `assetUri`, `provider`, `serviceType` | no | asset identity, as in `PolicyActionRequest` |
| `actionParameters` | no | what the executor needs, for example a soft-delete record id |
| `reason` | yes | why; at most `max-comment-length` characters |
| `requiredApprovals` | no | 1 to 10; raises the class default, never lowers it |
| `idempotencyKey` | no | up to 128 characters; scoped to the caller's `sub` |
| `onBehalfOf` | no | service accounts only: the `sub` of the person they act for |

Comments are 1 to `max-comment-length` (default 2000) characters after trimming. A break-glass
justification is at least 20.

`ApprovalWithdrawBody` (`POST /{id}/withdraw`, `ROLE_SERVICE_ACCOUNT` only through `@PreAuthorize`):

| Field | Required | Notes |
|---|---|---|
| `reason` | yes | why; at least 10 and at most `max-comment-length` characters after trimming |
| `onBehalfOf` | yes | the `sub` of the person the service account withdraws it for; at most 255 characters |

A withdrawal answers 200 with the `ApprovalView` of the request: `status` `WITHDRAWN`,
`executable` false, `withdrawnBy` (the token's `sub`), `onBehalfOf`, `withdrawReason`,
`withdrawnAt` and `resolvedAt` (the same instant). The self-approval rule does not apply. A request
that is not `PENDING` answers 409 and names its state as `status`; the numeric code moves to
`httpStatus` for this one answer:

```json
{"timestamp": "...", "status": "APPROVED", "httpStatus": 409, "error": "Conflict", "message": "the approval request is APPROVED", "reason": "not-pending", "path": "..."}
```

A request whose time is up (`expiresAt` passed, not yet marked) is moved to `EXPIRED` and answers
409 `expired` with `status` `EXPIRED`. Withdrawing a `WITHDRAWN` request answers 409 with
`status` `WITHDRAWN`, so a retry is safe to read.

Errors use the service's error shape plus a machine `reason`:

```json
{"timestamp": "...", "status": 403, "error": "Forbidden", "message": "...", "reason": "self-approval", "path": "..."}
```

| Status | `reason` |
|---|---|
| 400 | `invalid` |
| 403 | `self-approval`, `not-an-approver`, `mfa-required`, `no-subject`, `break-glass-disabled`, `on-behalf-refused`, `service-account-only` |
| 404 | `not-found` |
| 409 | `not-pending`, `expired`, `already-decided` |
| 503 | `audit-unavailable` |

`service-account-only` is the service's own answer for a withdrawal by a caller that is not a
service account; the `@PreAuthorize` of the endpoint refuses such a caller with a plain 403 first.
`audit-unavailable` means the audit log could not record a decision, a break-glass release or a
withdrawal, so nothing was recorded and the request is unchanged (no decision row, no state change, no
outbox message); retry (section 8.1).

### 8.1 Audit log entries

Every state change a person or a service account makes to a request is appended to the stream
`govern.approval` (`audit-log.md`) in the transaction that records it, as its last step, so the change
and its entry either both exist or neither does. The entry holds the `sub` of the decider (never a
display name), the request as target, the SHA-256 of the comment, justification or reason (never the
text) and no volatile counters.

| Operation | `eventType` | Actor | Idempotency key |
|---|---|---|---|
| approve, reject | `govern.approval.decided` | the decider (`USER`) | `<approvalId>:decision:<sub>` |
| break-glass | `govern.approval.break_glass` | the administrator (`USER`) | `<approvalId>:decision:<sub>` |
| withdraw | `govern.approval.withdrawn` | the service principal (`SERVICE`) | `<approvalId>:withdrawn` |

A `sub` longer than 64 characters or not printable ASCII without spaces appears in the key as
`sha256-` and the first 32 hex characters of its SHA-256. The details of a decision name the
`approvalId`, the `decisionId` (the id of the decision row, name-based per request and approver), the
`decision` (`APPROVE`, `REJECT` or `BREAK_GLASS`), the class, type and asset of the request, the
required approvals, the origin and `commentSha256`. A decision that expires the request instead
(section 4) appends nothing.

**Fail closed.** Any failure of the append (the audit database is down, no writer is configured, the
database refuses the writer role, the stored chain cannot be read, the append is rejected as invalid)
rolls the transaction back and answers `503 audit-unavailable`. The service starts without the audit
database; the first decision, break-glass release or withdrawal then fails closed.

**Replay rule.** An entry can be committed while the request's own transaction is not (a crash between
the two commits). Before it records a change the service therefore reads the entry the stream already
holds under the change's key. If there is one, the retry:

1. takes the entry's `occurredAt` as the time of the change (`decidedAt`; `withdrawnAt` and
   `resolvedAt`), not the clock's, so the row states the time the chain states;
2. appends nothing when the entry records the same change, and commits the row;
3. answers `503 audit-unavailable`, changing nothing, when the entry records another change: another
   event type (approve against reject, or break-glass), another actor, another `commentSha256` or
   `reasonSha256`, another person acted for. The chain holds hashes only, so the original comment or
   reason cannot be recovered from it: only a retry that sends the original text completes the change
   (Q-717).

**Orphan entry.** An entry without a matching row is possible and is never removed (the chain is
append-only). It stays when the attempt that wrote it did not commit and the request then left `PENDING`
before an identical retry arrived, for example because the maintenance job expired it (section 4); the
late retry answers `409 not-pending`. A reader of the chain matches a decision entry to the row with the
same `decisionId`; an entry with no such row records an attempt that did not take effect, and the request
row and its decision rows state what did.

## 9. Executor rules

An executor of archive or delete actions:

1. MUST NOT execute a destructive action from `dlap.policies.actions` unless it carries an
   `approval` whose `status` is `APPROVED` or `APPROVED_BREAK_GLASS`. An action with
   `requireApproval: true` and no approval is acknowledged and not executed.
2. MUST, for a hard delete, also check `approval.actionClass` is `HARD_DELETE` and that
   `approvers` holds at least 2 distinct subjects, unless `breakGlass` is true.
3. SHOULD re-read `GET /approvals/{approvalId}` (as `ROLE_SERVICE_ACCOUNT`) before it acts and
   refuse when the answer is not executable. Kafka write access to `dlap.policies.actions` then
   cannot forge an approval.
4. For an API request it made, acts on the `DECIDED` event whose `approvalId` it stored, or polls
   `GET /approvals/{id}`; it acts only when `executable` is true.
5. Treats a repeated `approvalId` or `fireId` as the same decision.
6. Writes its own audit record naming the `approvalId`.
7. An executor that makes its own approval request pointless (a restore of the record a hard
   delete waits on) MAY withdraw the request with its service-account token
   (`POST /approvals/{id}/withdraw`, section 8) and `onBehalfOf` the person who acted. A
   `WITHDRAWN` request is not executable (`executable` false, never `APPROVED`), so rules 1 to 4
   already refuse it; no executor change is needed to stay safe.

## 10. Events

Three topics, constants in `DlapConstants.KafkaTopic`, keyed by the request id, one JSON object per
record (`ApprovalEvent` v1):

| Topic | Constant | `eventType` | When |
|---|---|---|---|
| `dlap.approvals.requested` | `APPROVAL_REQUESTED` | `REQUESTED` | a request starts to wait |
| `dlap.approvals.decided` | `APPROVAL_DECIDED` | `DECIDED` | a request reaches a terminal state (a withdrawal included, `status` `WITHDRAWN`, `executable` false) |
| `dlap.approvals.break-glass` | `APPROVAL_BREAK_GLASS` | `BREAK_GLASS` | an administrator broke glass (the alert stream) |

Fields: `schemaVersion` (`v1`), `eventId`, `eventType`, `occurredAt`, `approvalId`, `status`,
`executable`, `origin`, `actionClass`, `actionType`, `actionParameters`, `assetId`, `assetUri`,
`provider`, `serviceType`, `policyDbId`, `policyId`, `fireId`, `requestedBy`, `requesterKind`,
`submittedBy`, `reason`, `requiredApprovals`, `approvals`, `createdAt`, `expiresAt`,
`resolvedAt`; on `DECIDED` and `BREAK_GLASS` also `decisions` (`approverSub`, `decision`,
`decidedAt`); on `BREAK_GLASS` also `justification`.

On `DECIDED` for a `WITHDRAWN` request the event also carries `withdrawnBy`, `onBehalfOf` and
`withdrawnAt` (a backward-compatible addition to `ApprovalEvent` v1).

Decision comments and the withdrawal reason are never put on a topic; read them through the API. `eventId` is a name-based
UUID of (event type, request id), so a message sent twice carries the same id.

Messages are written to the table `approval_outbox` in the same transaction as the state change
and sent by the maintenance job in write order, waiting for the broker's acknowledgment (at least
once). A send failure stops the batch and is retried on the next tick, so a request's messages
never overtake each other. `dlap.approvals.decided.DLT` is provisioned for executors that
dead-letter.

## 11. Storage

Flyway V7 of `da-policyengine` (`V7__approvals.sql`), schema `dlap_policyengine`; V8
(`V8__approval_withdrawal.sql`) adds the state `WITHDRAWN` and the columns `withdrawn_by`,
`on_behalf_of`, `withdraw_reason` and `withdrawn_at` to `approval_requests`, a CHECK that ties the
four to the state (`ck_approval_requests_withdrawal`), and extends the guard trigger so they never
change once the request has left `PENDING`:

| Table | Holds | Guard |
|---|---|---|
| `approval_requests` | one row per request | identity columns immutable; state moves once from PENDING; approval count only grows; no DELETE or TRUNCATE; `CHECK` hard delete `required_approvals >= 2`; requester and submitter always in `excluded_subjects` |
| `approval_decisions` | one row per person per request | `UNIQUE (request_id, approver_sub)`; non-blank comment; no UPDATE, DELETE or TRUNCATE |
| `approval_outbox` | the messages of section 10 | relay state only |

No foreign key points at `policies`: deleting a policy never deletes its approval history. The
triggers stop the service and anyone without the rights to drop them; the tamper-evident audit
chain (AUD-01, `audit-log.md`) is what proves the rows were not rewritten by someone who can.

## 12. Configuration and operations

| Property (env) | Default | Meaning |
|---|---|---|
| `dlap.policyengine.approvals.ttl` (`DLAP_APPROVALS_TTL`) | `P7D` | time to decide; PT1H to P90D |
| `...required.archive` / `.soft-delete` / `.hard-delete` / `.other` | 1 / 1 / 2 / 1 | approvals per class; floors 1 / 1 / 2 / 1, ceiling 10 |
| `...approver-roles` (`DLAP_APPROVALS_APPROVER_ROLES`) | `ADMIN,CDO,DIRECTOR,POLICY_MANAGER` | subset of those four |
| `...require-mfa-marker` (`DLAP_APPROVALS_REQUIRE_MFA_MARKER`) | `false` | `true` in production; a WARN is logged at start while false |
| `...mfa-marker-role` | `dlap-mfa-required` | the marker realm role |
| `...break-glass.enabled` (`DLAP_APPROVALS_BREAK_GLASS_ENABLED`) | `true` | `false` refuses break-glass |
| `...break-glass.min-justification-length` | `20` | at least 20 |
| `...max-comment-length` | `2000` | 1 to 10000 |
| `...maintenance.enabled` / `.tick` / `.lock-at-most-for` | `true` / `PT5S` / `PT10M` | expiry and outbox relay; ShedLock lock `approval-maintenance`, database time |
| `...relay-batch-size` / `...send-timeout` | `50` / `PT5S` | messages per tick; broker acknowledgment wait |

A value below a floor stops the start-up with the property's name.

Metrics: `policyengine.approvals.requested{actionClass,origin}`,
`policyengine.approvals.decisions{decision}`, `policyengine.approvals.resolved{status,actionClass}`,
`policyengine.approvals.break_glass{actionClass}` (alert on any increase),
`policyengine.approvals.refused{reason}`, `policyengine.approvals.outbox{kind,result}`,
`policyengine.approvals.withdrawn{actionClass}`.

The audit log connection of decisions, break-glass releases and withdrawals is `dlap.audit.datasource.url` / `.username` /
`.password` (`audit-log.md`); blank derives the `dlap_common` database next to the service's own
and reuses its user, `da_policyengine_user`, the writer of the stream `govern.approval`.

## 13. Known gaps (verified against code, 2026-11-01)

1. **The REST evaluation path is gated, and its trigger is excluded (closed, Q-569).**
   `PolicyEvaluationService` (on-demand `POST /{policyId}/evaluations`) routes its fires through
   `ApprovalGate` (section 6.1.1) and passes the JWT `sub` of the administrator who triggered the
   evaluation, which `ApprovalService.hold` adds to the excluded subjects next to the policy and its
   authors. Who triggers a fire never decides it.
2. **Executors verify (closed, RET-02, RET-03).** The delete and archive executors check the approval
   before they act (`ApprovalVerifier` in `da-autodelete` and in `da-autoarchival`), as section 9 requires.
3. **Policy authors are recorded (closed, AUD-02a).** `Policy.createdByUserId` is set when a policy is
   created, a draft publish included, and `updatedByUserId` on every update, status change and delete
   (`PolicyService`); the gate excludes both (rule 4).
4. **No account scope.** Any eligible approver may decide any request. RBAC-02 adds scopes.
5. **Decisions are in the audit chain (closed, AUD-02a, ADR-0112).** Approve, reject and break-glass
   append `govern.approval.decided` and `govern.approval.break_glass` as withdrawals append
   `govern.approval.withdrawn` (section 8.1). One part stays open: a retry with another comment or
   reason than the entry a lost commit left cannot complete (Q-717).

## 14. Changelog

| Version | Change |
|---|---|
| 1.2 | Decisions and break-glass are chained in `govern.approval` (`govern.approval.decided`, `govern.approval.break_glass`, key `<approvalId>:decision:<sub>`) and fail closed with `503 audit-unavailable` like withdrawals; the replay rule and the orphan entry (section 8.1); policy authors are recorded; the evaluation trigger is excluded and the executors verify (section 13 gaps closed); rule 12 (AUD-02a, ADR-0108, ADR-0112, Q-687, Q-692, Q-716, D39). |
| 1.1 | `WITHDRAWN` state and `POST /approvals/{id}/withdraw` for service accounts (APPR-03, Q-676, D37); reasons `service-account-only` and `audit-unavailable`; Flyway V8; `DECIDED` events carry `withdrawnBy`, `onBehalfOf`, `withdrawnAt`; withdrawals are audited in `govern.approval`. |
| 1.0 | First normative version (APPR-01, ADR-0095). |
