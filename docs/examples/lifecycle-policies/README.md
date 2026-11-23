# Example: lifecycle policies with schedule triggers

Runnable `dlap/v1` `kind: Policy` documents that use the POL-01 additions of the
Policy DSL v1.2 (`docs/specs/policy-dsl.md`): schedule triggers that fire, the
`createdAt` clause, and three-valued evaluation.

| File | Trigger | Shows |
|---|---|---|
| `archive-unread-buckets.yaml` | daily 02:00 UTC | `lastAccessedAt` on a destructive action: archived only under full read coverage; `dryRun` and `requireApproval` set. |
| `label-old-datasets.yaml` | Mondays 03:15 UTC | `createdAt` on the provider's creation time. |

## How they fire

`ScheduledPolicySweeper` checks every 30 seconds for policies with a cron slot due.
A new policy fires from its next slot, never retroactively. One sweep pages the
catalog activity report as of the sweep instant and evaluates every ACTIVE and
ARCHIVED asset; only a `TRUE` match publishes a `PolicyActionRequest`. An unknown
value (no recorded read, no creation time, incomplete read coverage for a
destructive action) matches nothing.

To see what a policy would do without any consumer acting, run the engine with
`dlap.policyengine.actions.enabled=false` (shadow mode): matches are logged and
counted in `policyengine.actions.shadowed`, nothing is published.

`docs/examples/access-policies/deny-stale-data.yaml` is the access-side
counterpart: a deny AccessPolicy denies data whose age is unknown.
