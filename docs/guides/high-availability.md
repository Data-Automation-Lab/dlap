# Running more than one replica: scheduled jobs

Status: HA-01, audited at `6790a884` (feature/deployment). Every `file:line` below is a line in that
tree; re-run the audit command at the end of this page before you rely on a row after a later merge.

Plan rule R7: a singleton `@Scheduled` job uses ShedLock JDBC with `usingDbTime()` and a
`lockAtMostFor` of at least twice the expected run. The lock table (`shedlock`) lives in the owning
service's own schema and is created by that service's own Flyway migration. Nothing here changes how
Kafka consumers scale: they share one consumer group per service, so each partition is read by one
replica.

## 1. What the lock does

Every replica fires the same timer. The replica that inserts or updates the job's `shedlock` row
runs the job; the others skip that tick. Three bounds matter:

| Bound | Meaning | What goes wrong if it is set badly |
|---|---|---|
| `lockAtMostFor` | The longest a crashed holder keeps the row. | Shorter than the run: a second replica starts while the first still runs. Longer than needed: after a crash the job is skipped until the row expires. |
| `lockAtLeastFor` | The shortest time the row is held after a run. | Too short for a `fixedDelay` job: each replica's own timer fires in turn and the cluster runs about once per replica per interval. |
| the clock | `usingDbTime()` reads the database clock. | Without it, replica clock skew shortens or extends a lock. |

For a `fixedDelay` job, set `lockAtLeastFor` to the interval. The compliance signal sync does this by
default: `lock-at-least-for` falls back to `interval-ms`, so changing the interval moves both.

A crashed holder is replaced when its row expires: the next job fires after at most `lockAtMostFor`
(30 minutes for the signal sync, so one missed 15 minute cycle at worst).

## 2. Inventory

Audit command: `grep -rn "@Scheduled\|scheduleWithFixedDelay\|taskScheduler.schedule" --include=*.java da-*/src/main`
(in `da-protos`, `da-llm-client`, `da-admin-service`, `da-autoarchival`, `da-autolabel` and
`da-mcp-pep` it finds nothing; `da-reporting` enables scheduling at `DaReportingApplication.java:30`
but has no scheduled method).

### 2.1 Singleton jobs that hold a lock

| Service | Job | Where | Lock name | `lockAtMostFor` / `lockAtLeastFor` |
|---|---|---|---|---|
| da-autocompliance | Compliance signal sync (ADR-0035), HA-01 | `ComplianceSignalSyncService.java:94` | `autocompliance-signal-sync` | `PT30M` / the interval (`PT15M`) |
| da-catalog | Retention sweep | `RetentionSweepJob.java:62` | `catalog-retention-sweep` | `PT2H` / `PT1M` |
| da-catalog | Retention-due publisher | `RetentionDuePublisher.java:87` | `catalog-retention-due-publisher` | `PT10M` / `PT1S` |
| da-discovery | Scheduled scan trigger | `ScheduledDiscoveryTrigger.java:180` | `dlap-discovery-scheduled-scan` | `PT30M` / `PT2M` |
| da-discovery | Read-coverage collection | `ReadCoverageCollectionJob.java:90` (lock taken in code through `LockingTaskExecutor`, line 96) | `discovery-read-coverage` | `PT1H` / `PT1M` |
| da-logingest | Binding status publisher | `BindingStatusPublisher.java:91` | `logingest-binding-status` | `PT10M` / `PT5S` |
| da-logingest | Quarantine retention sweep | `QuarantineRetentionSweeper.java:75` | `logingest-quarantine-retention` | `PT2H` / `PT1M` |
| da-logingest | Log-event retention sweep | `LogEventRetentionSweeper.java:139` | `logingest-log-event-retention` | `PT2H` / `PT1M` |
| da-policyengine | Scheduled policy sweep (POL-01) | `ScheduledPolicySweeper.java:136` | `policy-schedule-sweeper` | `PT1H` / `PT5S` |
| da-autodelete | Retire sweep (RET-02, ADR-0101): window ends, hard-delete approvals, proof overdue; each row claimed with `FOR UPDATE SKIP LOCKED` in its own transaction (closes Q-548) | `RetireSweeper.java:95` | `autodelete-retire-sweep` | `PT10M` / `PT5S` |

The da-logingest registry pipeline polls each stream under a per-stream PostgreSQL advisory lock
instead (`StreamPollScheduler.java:82` schedules, `StreamRunner` takes the lock and skips the poll
when another replica holds it). That is also a single run per stream, and needs no ShedLock row.

### 2.2 Per-instance jobs (no lock, on purpose)

These run on every replica because each replica holds state that only it can refresh.

| Service | Job | Where | Why it is per instance |
|---|---|---|---|
| da-policyengine | Access policy cache refresh | `AccessPolicyRefreshListener.java:64` | Reloads this replica's in-memory PDP cache; a lock would leave the other replicas stale. |
| da-discovery | Exception summary log | `ExceptionMetricsCollector.java:134` | Logs this replica's in-memory exception counters. |
| da-logingest | Health snapshot refresh | `HealthSnapshotService.java:110` | Refreshes this replica's exported gauges; each replica reports its own. |
| da-discovery | Cron-scheduled discovery jobs | `DefaultDiscoveryScheduler.java:77` | The registry is an in-memory map (see the TODO at line 50): a job runs only on the replica that took the schedule request and is lost on restart. This is a gap, not a design; see 2.3. |

### 2.3 Singleton jobs without a lock (gaps)

These are outside HA-01's files. Each has an owner or an open question; none is fixed here.

| Service | Job | Where | Effect with two replicas | Owner |
|---|---|---|---|---|
| da-catalog | Processed-event pruner | `ProcessedEventPruner.java:75` | Both replicas run the same batched delete. Idempotent, wasteful, can contend on the same rows. | none yet (Q-549) |
| da-logingest | Legacy AWS poll | `CloudTrailLogSource.java:297` | Both replicas poll the same regions; the overlap is dropped by the ingest service's duplicate check, at the cost of double API calls. Legacy pipeline only (`dlap.logingest.pipeline=legacy`). | none yet (Q-550) |
| da-logingest | Legacy GCP poll | `GcpLogSource.java:250` | Same as the AWS poll. | none yet (Q-550) |
| da-discovery | Cron-scheduled discovery jobs | `DefaultDiscoveryScheduler.java:77` | See 2.2: the schedule lives in one replica's memory. | none yet (Q-551) |

## 3. The compliance signal sync

`ComplianceSignalSyncService` exists only when `dlap.compliance.signal-sync.enabled=true`. Its lock
configuration (`ShedLockConfiguration`, a member class of the service) exists under the same
condition, so a default deployment adds nothing.

| Property | Default | Meaning |
|---|---|---|
| `dlap.compliance.signal-sync.interval-ms` | `900000` | Delay between runs on one replica. |
| `dlap.compliance.signal-sync.initial-delay` | `PT0S` | Delay before a replica's first run. |
| `dlap.compliance.signal-sync.lock-at-most-for` | `PT30M` | Crash bound. Keep it above twice the longest run. A run is one HTTP fetch (5 s connect, 15 s read, `HttpComplianceSignalProvider.java:61-62`) and one Kafka publish per signal. |
| `dlap.compliance.signal-sync.lock-at-least-for` | the value of `interval-ms` | Minimum hold, as ISO-8601 or milliseconds. |

The table is `dlap_autocompliance.shedlock`, created by `V5__shedlock.sql`. To see who holds the lock:

```sql
SELECT name, locked_by, locked_at, lock_until FROM dlap_autocompliance.shedlock;
```

A `sync()` call made directly (a test or a future endpoint) takes no lock. Only the scheduled entry
point `scheduledSync()` does.

## 4. How the tests prove a single run

Both run with `./gradlew :da-autocompliance:test` (the first needs Docker):

| Test | Proves |
|---|---|
| `ComplianceSignalSyncLockTest` | Two Spring contexts share one PostgreSQL built by the real Flyway migrations V1 to V5. A replica that is mid-sync makes the other skip; a late replica skips the same interval; an expired lock is taken over; two replicas on real timers never overlap, start at least one interval apart, and run at most 5 times in 6.5 s where two unlocked replicas would run 8 or more times; `lock-at-least-for` follows `interval-ms` and can be set apart; V5 re-runs cleanly. |
| `SignalSyncLockContextTest` | The whole service (prod profile, platform init scripts, its own Flyway as the service database user) with the sync enabled wires the lock provider and a ShedLock-proxied sync, and one tick writes its row into `dlap_autocompliance.shedlock`. |
| `ScheduledJobsLockCoverageTest` | Every `@Scheduled` method in the module holds a `@SchedulerLock` with a unique name under 64 characters. A new unlocked job fails the build; a deliberately per-instance job goes into the test's allowlist and into section 2.2. |

## 5. Adding a scheduled job

1. Decide singleton or per instance. If every replica holds the state the job works on, it is per
   instance; say why in section 2.2. Anything that writes shared state, publishes events or calls a
   cloud API is a singleton.
2. Add `shedlock-spring` and `shedlock-provider-jdbc-template` at the version the other modules use
   (`7.10.1`; Apache-2.0).
3. Add `V{N}__shedlock.sql` to the service's migrations, using the column set in
   `da-autocompliance/src/main/resources/db/migration/V5__shedlock.sql`.
4. Provide a `LockProvider` built with `usingDbTime()` and the schema-qualified table, behind
   `@EnableSchedulerLock` (copy `ComplianceSignalSyncService.ShedLockConfiguration`).
5. Annotate the method with `@SchedulerLock(name, lockAtMostFor, lockAtLeastFor)`. The name is unique
   across the platform's tables and under 64 characters.
6. Add a two-context test like `ComplianceSignalSyncLockTest`, and a row to section 2.1.
