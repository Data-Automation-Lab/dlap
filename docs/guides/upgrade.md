# Upgrading an existing DLAP database

How to move a database created by an earlier DLAP build onto the schema contract the services
now enforce. Read this if you keep a database across upgrades. If your database is disposable,
recreate it instead (see [Disposable databases](#disposable-databases)).

## What the services enforce

Every service with a database declares `spring.jpa.hibernate.ddl-auto=validate` as its default
in `application.properties` (CLAUDE.md section 6). Hibernate compares each mapped table and column with the live schema at
boot and refuses to start on a mismatch. It never alters the schema. Before DB-01, `da-catalog`
and `da-autodelete` defaulted to `update`, which silently created missing tables and columns and
never checked types.

| Layer | Source of truth | How a change reaches a live database |
|---|---|---|
| Per-service schema (`dlap_<service>`) | `<service>/src/main/resources/db/migration/V{N}__name.sql` | The service runs Flyway at startup. Nothing to do. |
| Shared `dlap_common` | `deployment/platform/database/init-scripts/02-create-common-schema.sql` | Fresh databases get it from the init scripts. Existing databases get the dated deltas in `deployment/platform/database/upgrades/` from `run-upgrades.sh`, which records each applied file in `dlap_common.schema_upgrades` (see [The upgrade runner](#the-upgrade-runner)). |

`da-catalog` spans both layers. It reads and writes `dlap_common` (the shared Asset, Label and
User entities) and owns `dlap_catalog.dlap_asset_lineage`. Both layers are validated.

What `validate` checks: that every mapped table and column exists and has a compatible type. What
it does not check: CHECK constraints, indexes, defaults, nullability, extra unmapped columns.
Those stay your responsibility, which is why step 4 below inspects the CHECK constraints.

`JPA_DDL_AUTO` overrides the default for one service. See the warning under
[Reading a validation failure](#reading-a-validation-failure) before using it.

## Disposable databases

Dev and demo databases do not need an upgrade. Drop the volume and let the init scripts and
Flyway build the schema again:

```bash
cd deployment/environments/dev
docker compose -f docker-compose.yml -f docker-compose.microservices.yml down -v
docker compose -f docker-compose.yml -f docker-compose.microservices.yml up -d --build
```

A database from before the DALab to DLAP rename (`dalab_*` schemas and tables) has no in-place
upgrade path in this repository. Recreate it as above.

## Upgrade a database you want to keep

1. **Back up.** One custom-format dump per database: `dlap_common` and each `da_*` database.

   ```bash
   pg_dump -Fc -h "$DB_HOST" -U "$DB_ADMIN_USER" -f dlap_common.dump dlap_common
   pg_dump -Fc -h "$DB_HOST" -U "$DB_ADMIN_USER" -f da_autodelete.dump da_autodelete
   ```

2. **Stop the services** that use the database, at least `da-catalog` and `da-autodelete`.

3. **Apply the `dlap_common` deltas** with the runner, as the database owner or a superuser.
   Preview first, then apply:

   ```bash
   export DLAP_COMMON_DB_URL="postgresql://dlap@db.example.internal:5432/dlap_common"   # plus PGPASSWORD
   bash deployment/platform/database/upgrades/run-upgrades.sh --dry-run
   bash deployment/platform/database/upgrades/run-upgrades.sh
   ```

   The runner applies every delta the database has not had, in file-name order, and records
   each one with its checksum. A database that had some deltas applied by hand is fine: each
   delta is idempotent, so the runner applies it again and records it. Running the runner again
   applies nothing. See [The upgrade runner](#the-upgrade-runner) for the connection options,
   the exit codes and what to do when it refuses.

4. **Check the CHECK constraints** (optional now that the ledger exists). `validate` does not
   see them, and an old constraint rejects new asset kinds at insert time, long after the
   service started.

   ```sql
   SELECT conname, pg_get_constraintdef(oid)
     FROM pg_constraint
    WHERE conrelid = 'dlap_common.dlap_assets'::regclass AND contype = 'c';
   ```

   Compare the `ck_asset_cloud_provider_valid` and `ck_asset_service_type_valid` lists with
   `02-create-common-schema.sql`. If they differ, the runner did not finish: read its output.

5. **Start the new version.** Flyway runs first, then Hibernate validates. Flyway migration
   `V4__deletion_config_and_tasks.sql` in `da-autodelete` creates the deletion config and task
   tables. On a database that `update` already created, it changes nothing and keeps your rows.
   Reaching the `Started ...` log line means validation passed. A mismatch stops the service with
   `Schema-validation: ...`; see the next section.

6. **Verify.** Flyway history, per service schema:

   ```sql
   SELECT installed_rank, version, description, success
     FROM dlap_autodelete.flyway_schema_history ORDER BY installed_rank;
   ```

   Every row must have `success = true` and versions must run 1..N with no gaps.

To rehearse first, restore the dumps into a scratch PostgreSQL 15 and run steps 3 to 6 against
that copy.

## The upgrade runner

`deployment/platform/database/upgrades/run-upgrades.sh` (ADR-0056) is the only supported way to
move an existing `dlap_common` database forward. Postgres runs the init scripts only on an
empty data directory, so without the runner a delta reaches a database only if someone applies
it by hand.

What it does, in order:

1. Lists the `YYYY-MM-DD_name.sql` files in its own folder and computes the SHA-256 of each
   (carriage returns ignored).
2. Reads the ledger, `dlap_common.schema_upgrades` (columns `file_name`, `checksum_sha256`,
   `applied_at`, `applied_by`). Fresh databases get the table from
   `02-create-common-schema.sql`; the runner creates it on an older database.
3. Refuses to continue if any applied file has changed, if a pending file sorts before the
   newest applied one, or if a file name is malformed. Nothing is applied after a refusal.
4. Applies each pending file with `psql -v ON_ERROR_STOP=1`, then records it. The first failure
   stops the run and is not recorded.

```bash
bash deployment/platform/database/upgrades/run-upgrades.sh --help
```

| Option | Effect |
|---|---|
| `--dry-run` | Verify and list what would be applied. Changes nothing, not even the ledger table. Exits 2 on a refusal, so it doubles as a pre-flight check. |
| `--allow-out-of-order` | Apply a pending file that sorts before the newest applied file. |
| `--dir DIR` | Read deltas from `DIR` (default: the script's folder). Also `DLAP_UPGRADES_DIR`. |
| `--verbose` | Print psql output for every file, not only for a failure. |

The role must own the `dlap_common` objects or be a superuser, and the connection must name the
database `dlap_common` (the `2026-06-28` delta reconnects to it by that name). Connection settings:

| Setting | Meaning |
|---|---|
| `DLAP_COMMON_DB_URL` | libpq URI or conninfo. A leading `jdbc:` is dropped. Without it the runner uses `PGHOST`, `PGPORT`, `PGUSER`, `PGPASSWORD` and `PGDATABASE` (default `dlap_common`). |
| `DLAP_PSQL` | The psql command, default `psql`. Delta files are sent on stdin, so the client can be a container. |

To reach the dev stack's database without a host `psql` (container `dlap-postgresql`, user
`dlap`, from `deployment/environments/dev/docker-compose.yml`):

```bash
DLAP_PSQL="docker exec -i dlap-postgresql psql -U dlap" \
  bash deployment/platform/database/upgrades/run-upgrades.sh
```

The pilot overlay and the Helm chart are to call the runner at install and upgrade time
(hardening packages DEP-01 and DEP-02). Until they do, call it yourself before starting the new
version.

Exit codes: `0` done (including nothing to do), `1` failure (connection, SQL error, usage),
`2` refused.

Read the ledger:

```sql
SELECT file_name, left(checksum_sha256, 12) AS sha256, applied_at, applied_by
  FROM dlap_common.schema_upgrades ORDER BY file_name;
```

### When the runner refuses

| Message | Cause | Fix |
|---|---|---|
| `REFUSED: <file> changed since it was applied` | The file differs from what the ledger recorded. Someone edited an applied delta. | Restore the recorded content from version control. To change the schema again, add a new, later delta. If the edit was a comment only and you have confirmed the database matches the new text, update the recorded value: `UPDATE dlap_common.schema_upgrades SET checksum_sha256 = '<sha>' WHERE file_name = '<file>'`, with `<sha>` taken from `tr -d '\r' < <file> | sha256sum`. |
| `REFUSED: <file> is pending but sorts before the newest applied delta` | A delta with an older date was added after a newer one was applied. Deltas can redefine the same CHECK constraint, so a late older one could undo a newer one. | Rename it to a later date. Use `--allow-out-of-order` only after reading both files. |
| `REFUSED: <file> is not named YYYY-MM-DD_name.sql` | A stray `.sql` file in the folder. | Rename or remove it. |
| `ERROR: <file> failed and was not recorded` | The delta raised an error. Earlier files stay recorded. | Fix the cause (the psql error is printed above) and re-run. |
| `WARNING: <file> is in the ledger but not in ...` | A retired delta. | None. The runner only warns. |

Limits: run one runner at a time (two concurrent runs cannot both record a file, but both may
apply it; serialise in the caller). A crash between applying and recording leaves the file
unrecorded, and the next run applies it again, which is safe because deltas are idempotent. The
ledger table is created without grants, so service roles cannot read or write it.

## Reading a validation failure

| Message | Cause | Fix |
|---|---|---|
| `Schema-validation: missing table [x]` | The table was never created, usually because the database predates the migration that adds it. | `dlap_common`: apply the deltas (step 3). Per-service schema: start the service so Flyway can run, and check `flyway_schema_history` for a failed row. |
| `Schema-validation: missing column [x] in table [t]` | The entity has a column the database lacks. | Same as above. If no delta or migration adds it, the entity and the schema have drifted: that is a bug, report it. |
| `wrong column type encountered in column [c] in table [t]; found [a], but expecting [b]` | The database column and the entity mapping disagree. | Do not alter the column to silence it. Compare the entity with the column; one of them is wrong. |

Never set `JPA_DDL_AUTO=update` on a production database to get past a failure. `update` only
adds objects and never checks types. It hid a real defect here: `User.roles` was mapped as jsonb
against a `text[]` column, so every write of a user failed, and nothing complained until
`validate` was turned on.

## Per-service upgrade notes

### da-catalog: retention sweep (CAT-03)

An upgrade to a build with the catalog retention sweep (ADR-0046, `docs/specs/asset-descriptor.md`,
"Retention") needs these steps. Do them in this order.

1. **`dlap_common`.** Apply the delta `2026-10-18_asset_time_size_retention.sql` with the runner
   ([The upgrade runner](#the-upgrade-runner)). It adds the `retention_*` columns of `dlap_assets`
   that the sweep writes.
2. **Flyway V4 (`V4__shedlock_and_read_signal.sql`).** `da-catalog` applies it at start-up. It creates
   five tables in `dlap_catalog`: `shedlock`, `retention_rule`, `log_binding_status`,
   `retention_outbox` and `retention_audit`. Nothing to do by hand; V3 must already be applied.
3. **ShedLock rows.** The first run of each singleton job inserts one row into
   `dlap_catalog.shedlock`: `catalog-retention-sweep` and `catalog-retention-due-publisher`. Both
   jobs are cluster-wide, so any number of catalog instances is safe. Do not delete a row while a
   pass is running.
4. **Consumer groups.** Two new groups read Kafka from the earliest offset the first time the
   service starts: `da-catalog-retention-actions` (topic `dlap.policies.actions`, the
   `setRetention` actions) and `da-catalog-binding-status` (topic `dlap.logingest.binding.status`).
   Expect lag on both until the service has caught up.
5. **Topics.** The service now provisions `dlap.catalog.asset.retention-due` and its dead-letter
   topic, and `da-logingest` provisions the compacted `dlap.logingest.binding.status`. See
   [Kafka topics](#kafka-topics). A binding-status topic that already exists keeps its old
   settings; compact it by hand (below), or the topic never forgets a binding that ended.
6. **Settings.** The `dlap.catalog.retention.*` properties are new. The defaults need no change;
   the table is in `docs/specs/asset-descriptor.md`, "Settings". The sweep runs hourly
   (`dlap.catalog.retention.cron`); set `dlap.catalog.retention.enabled=false` to hold it while
   you check the rules.

Roll back by restoring the dumps. An older build ignores the V4 tables and the extra
`dlap_assets` columns.

### Kafka topics

From FU-3B each service that produces topics declares them, and their `.DLT` dead-letter topics, as
`NewTopic` beans (`config/KafkaTopicsConfiguration.java`), and Spring's `KafkaAdmin` creates what is
missing when the service starts. Before this, every topic relied on the broker's auto-create, which
a production cluster switches off (Q-336).

| Service | Topics it provisions (each with `<topic>.DLT`) |
|---|---|
| `da-logingest` | `dlap.log.event.normalized`, `dlap.log.event.quarantine` (30 days), `dlap.logingest.binding.status` (compacted) |
| `da-catalog` | `dlap.catalog.asset.upserted`, `dlap.asset.label.assignment.changed`, `dlap.catalog.asset.retention-due` (14 days) |
| `da-discovery` | the six `dlap.discovery.asset.*` topics (the four `resource-*` topics keep their own beans) |
| `da-policyengine` | `dlap.policies.actions`, `dlap.access.decision` |
| `da-autolabel` | `dlap.labeling.request.event` |

Settings, the same in every service (prefix `dlap.kafka`; environment form `DLAP_KAFKA_...`):

| Property | Default | Meaning |
|---|---|---|
| `provision-topics` | `true` | `false` declares nothing, for a cluster whose topics are managed outside the services |
| `topic-partitions` | `-1` | partitions; below 1 means the broker's `num.partitions` (3 in the dev compose, 1 in the demo) |
| `topic-replication-factor` | `-1` | replicas; below 1 means the broker's `default.replication.factor` |
| `dlt-partitions`, `dlt-retention` | `1`, `P14D` | dead-letter topics |
| `quarantine-retention` | `P30D` | `da-logingest`, the quarantine topic |
| `retention-due-retention` | `P14D` | `da-catalog`, the retention-due topic |

A production cluster sets `DLAP_KAFKA_TOPIC_PARTITIONS` and `DLAP_KAFKA_TOPIC_REPLICATION_FACTOR` (6 and
3 are the values of `deployment/PRODUCTION.md`). `da-discovery` under the `prod` profile takes them from
`KAFKA_ADMIN_PARTITIONS` and `KAFKA_ADMIN_REPLICATION_FACTOR`, as before.

`KafkaAdmin` creates a missing topic and adds partitions to a topic that has fewer than declared. It
never lowers a partition count and never changes the settings of a topic that exists, so a topic
that auto-create made earlier keeps the broker defaults. On an existing cluster, set the intended
values by hand once:

```bash
KAFKA="kafka-configs --bootstrap-server $BOOTSTRAP_SERVERS --alter --entity-type topics"
$KAFKA --entity-name dlap.logingest.binding.status --add-config cleanup.policy=compact
$KAFKA --entity-name dlap.log.event.quarantine --add-config retention.ms=2592000000
$KAFKA --entity-name dlap.catalog.asset.retention-due --add-config retention.ms=1209600000
```

A broker that is down when a service starts is logged and does not stop the service; the next start
tries again. Two services never declare the same topic, so a rolling start in any order is safe.

### da-catalog: asset URI re-key (ID-03)

Flyway `V5__asset_uri_rekey_map.sql` creates three tables in `dlap_catalog`:
`asset_uri_rekey_run`, `asset_uri_rekey_map` and `asset_uri_rekey_undo`. `da-catalog` applies it at
start-up; nothing to do by hand. The tables stay empty until you run the re-key described in
[Re-key asset URIs](#re-key-asset-uris). Run it once after the upgrade that brings it, and again
after any change of a canonical form (an `AssetIdNormalizer.SPEC_VERSION` bump,
`docs/specs/asset-id.md` section 16).

## Re-key asset URIs

Since CAT-02a the catalog writes the canonical URI of `AssetIdNormalizer` (ADR-0047,
`docs/specs/asset-id.md`) for every row it creates. Rows written earlier can hold older forms: a GCS
bucket as `b` or `projects/_/buckets/b`, a BigQuery table as `p:d.t`, an Azure container as an
`https://` URL. The event matcher finds those rows through `normalize(asset_uri)`, and two rows can
name one asset. The re-key moves every row to its canonical URI and merges the duplicates. It is a
one-shot command of the `da-catalog` image, not a service.

### What it changes

| Row | Change |
|---|---|
| Alone under its canonical URI, stored in another form | `asset_uri` becomes the canonical URI; `native_asset_id`, when empty, gets the old form |
| Canonical URI, but a service type or provider that the URI contradicts | `service_type` and `cloud_provider` come from the canonical ID (counted as `kindChanges`) |
| Several rows that normalize to one URI (a collision group) | the oldest row (`created_ts`, then `asset_id`: the row the event matcher already picks) survives with its `asset_id`; the others are merged into it and deleted |
| Stored URI that does not normalize | nothing; listed under `rejected` with the reason |

Normalization uses the row's service type, provider and region as hints, plus the
`DLAP_CATALOG_DEFAULT_*` hints of the service, exactly as the event matcher does.

A merge moves to the survivor: label assignments, `dlap_cloud_resources` rows, lineage edges
(both ends), `asset_usage_daily` rows, `processed_event` rows and retention rules. Duplicates are
dropped: a label the survivor already has (the survivor's assignment stays), an edge it already
has, a self edge the merge would create, an event it already processed. Same-day usage counts are
added. Of two `POLICY` rules of one policy, the one issued later stays. On the asset row itself:
blank descriptive fields are filled from the merged rows, JSON metadata is merged with the
survivor's keys winning, `cloud_created_at` is the least known value, `last_accessed_at`,
`last_modified_at` and `last_permission_change_at` the greatest, size and read coverage come from
the newer observation, status from the later lifecycle event, and a legal hold on any row holds
the survivor.

Not changed: `retention_audit` rows keep the `asset_id` they were written for (the trail is
append-only), and other services' databases (`da_policyengine`, `da_autodelete`, `da_reporting`,
`da_autolabel`, `da_autoarchival`, `da_autocompliance`) keep the ids and URIs they stored.
Resolve an old id or URI through the map:

```sql
SELECT m.survivor_asset_id, m.new_asset_uri
  FROM dlap_catalog.asset_uri_rekey_map m
  JOIN dlap_catalog.asset_uri_rekey_run r USING (run_id)
 WHERE r.status = 'APPLIED' AND (m.asset_id = :old_id OR m.old_asset_uri = :old_uri);
```

`last_modified_ts` of every changed row becomes the time of the apply: the `dlap_assets` trigger
sets it on every update.

### Commands

The re-key runs when `da-catalog` starts with `DLAP_CATALOG_REKEY_COMMAND` set. It stops the
catalog's Kafka listeners, runs the command, prints a JSON report between the lines
`----- BEGIN DLAP ASSET URI REKEY REPORT -----` and `----- END DLAP ASSET URI REKEY REPORT -----`,
and exits.

| Command | Effect |
|---|---|
| `dry-run` | Plans in a read-only snapshot and reports. Changes nothing and takes no lock, so it is safe on a live system. |
| `apply` | Locks `dlap_common.dlap_assets` against writers (`SHARE ROW EXCLUSIVE`; readers continue), plans again in the same transaction, refuses on a blocker, then re-keys, merges and records the map and the undo log. All or nothing. When every row is already canonical it changes nothing and records no run, so a second apply is a no-op. |
| `revert` | Undoes the latest applied run from its undo log, in one transaction. |

| Setting (environment) | Default | Meaning |
|---|---|---|
| `DLAP_CATALOG_REKEY_COMMAND` | empty (the service runs normally) | `dry-run`, `apply` or `revert` |
| `DLAP_CATALOG_REKEY_RUN_ID` | the latest applied run | the run `revert` undoes; any other than the latest is refused |
| `DLAP_CATALOG_REKEY_REPORT_FILE` | none | also write the report to this file |
| `DLAP_CATALOG_REKEY_LOCK_TIMEOUT` | `PT30S` | how long `apply` and `revert` wait for the table lock |
| `DLAP_CATALOG_REKEY_OPERATOR` | none | a name recorded on the run |

Exit codes: `0` done (or nothing to do), `1` failed (nothing committed), `2` refused (for
`dry-run`: an `apply` would be refused now). The report has `summary` (`scanned`, `unchanged`,
`rekeyed`, `kindChanges`, `mergeGroups`, `mergedRows`, `rejected`, `rejectedByReason`),
`collisions` (survivor, merged rows and, per group, the rows that will move), `rekeys`, `rejected`
and `blockers`. An apply adds `runId` and `applied` (what moved, what was dropped, and the size of
the undo log), and stores the report in `asset_uri_rekey_run.report`.

Blocker: `UNPUBLISHED_RETENTION_DUE`, a retention-due event in `dlap_catalog.retention_outbox` that
names an old URI and is not published yet. Start `da-catalog` with Kafka reachable until the outbox
is empty, then stop it again.

Run it with the image and settings of the stack, as a one-off container. With the dev compose pair
(add your own overrides the same way):

```bash
cd deployment/environments/dev
docker compose -f docker-compose.yml -f docker-compose.microservices.yml run --rm --no-deps -T \
  -e DLAP_CATALOG_REKEY_COMMAND=dry-run \
  -e SPRING_MAIN_WEB_APPLICATION_TYPE=none \
  -e SPRING_KAFKA_LISTENER_AUTO_STARTUP=false \
  -e DLAP_CATALOG_INGEST_MATCH_INDEX_ON_STARTUP=false \
  -e DLAP_CATALOG_RETENTION_ENABLED=false \
  -e DLAP_CATALOG_RETENTION_PUBLISH_ENABLED=false \
  -e DLAP_CATALOG_PROCESSED_EVENT_PRUNE_ENABLED=false \
  da-catalog
```

The last six settings keep the one-off process from serving HTTP, consuming Kafka and running the
scheduled jobs while the command runs. Use the role and database of the service
(`da_catalog_user` on `dlap_common`): the role owns the asset tables.

### Runbook

1. **Back up** `dlap_common` (`pg_dump -Fc`, as in
   [Upgrade a database you want to keep](#upgrade-a-database-you-want-to-keep)). A revert works
   only until the data changes again; the dump always works.
2. **Preview** on the live system with `dry-run`. Read `collisions` (is the oldest row the one to
   keep?), the `kindChanges` among `rekeys` (a row that changes service type matches other policies
   afterwards) and `rejected` (rows the re-key leaves as they are; fix their URIs or the default
   hints first, for example `DLAP_CATALOG_DEFAULT_PROJECT` for bare BigQuery names).
3. **Drain the topics**, from the edge inward, so that no record keyed or addressed by an old URI
   is in flight when the rows move and the producers' keys switch:
   1. Stop the log sources: `da-logingest`, and anything else that produces to
      `dlap.log.event.normalized`.
   2. Wait until the `da-discovery` consumer groups have no lag, then stop `da-discovery`.
   3. Wait until the `da-catalog` groups (`da-catalog`, `da-catalog-lineage`,
      `da-catalog-read-coverage`, `da-catalog-binding-status`, `da-catalog-retention-actions`) have
      no lag and `SELECT count(*) FROM dlap_catalog.retention_outbox WHERE published_at IS NULL`
      returns 0. Then stop every `da-catalog` replica.

   ```bash
   kafka-consumer-groups --bootstrap-server "$BOOTSTRAP_SERVERS" --describe --all-groups \
     | awk '$1 ~ /^da-catalog/ && $6 ~ /^[0-9]+$/ { s += $6 } END { print "lag", s + 0 }'
   ```

4. **Apply** with `apply`. Keep the report (`DLAP_CATALOG_REKEY_REPORT_FILE`) and note the `runId`.
5. **Verify**: a second `dry-run` reports `rekeyed` 0 and `mergeGroups` 0. Spot-check a survivor:
   `SELECT asset_uri, native_asset_id FROM dlap_common.dlap_assets WHERE asset_id = '...'`.
6. **Switch the keys while the topics are still empty.** Kafka keeps the order of one key only
   within a partition, and a new key can hash to another partition, so a key must never change
   while records under the old key wait to be consumed. This is the moment to start producers that
   key by the canonical URI: `DLAP_LOGINGEST_PIPELINE=registry` (its normalized events are keyed by
   the target's canonical URI, `LogEventSemantics.partitionKey`) and crawlers that emit canonical
   IDs.
7. **Start** `da-catalog`, then `da-discovery`, then `da-logingest`.

To rehearse, `bash e2e-tests/asset-uri-rekey-e2e.sh` runs steps 3 to 5, a second apply, a revert
and a re-apply on a copy of a harness database (see `e2e-tests/README.md`).

### Revert

`revert` replays the run's undo log backwards: rows the run deleted come back, rows it moved go
back to their asset, rows it updated get their before-image, and restored merged rows are indexed
again (the other index entries heal themselves, below). It refuses (exit 2) and changes nothing when:

- the run is not the latest applied run, or was reverted already;
- a row the run changed has another `asset_uri`, `service_type` or `cloud_provider` now, or a
  merged row exists again;
- a row created since the run holds one of the old URIs;
- an inverse step touches other than exactly one row (the data changed since the run).

Revert before you start the services again. Changes made after the apply to the rows it touched
are lost by a revert; once the services have run, restore the dump instead.

### Limits

- The apply holds the table lock and one transaction for its whole duration; run it in the drain
  window. The plan keeps the id, URI and type of every row in memory. Measured on a developer
  machine (Docker Desktop on Windows 11, `postgres:15.1` in a container, ID-03, 2026-10-20):

  | Catalog | dry run | apply | second apply | revert |
  |---|---|---|---|---|
  | 20,100 rows: 19,900 re-keyed in place, 100 merged | 0.9 s | 6.0 s | 0.1 s | 18 s |
  | 22,000 rows: 18,000 re-keyed in place, 2,000 merged (each with a label and 7 usage days) | 7.0 s | 33 s | 0.1 s | 36 s |

  A merge costs about 15 ms and an in-place re-key under 0.3 ms; scale from your dry run's
  `mergedRows` and `rekeyed`.
- An old URI longer than 500 characters is not copied into `native_asset_id` (the column width);
  the map keeps it.
- The `dlap_catalog.asset_uri_canonical` entries of rows re-keyed in place (and of every row a
  revert restores in place) keep the URI they were computed from. The catalog treats such an entry
  as stale and recomputes it before use and in its start-up build; events find those rows by exact
  URI meanwhile.

## Roll back

Restore the dumps from step 1.

## Changing the schema (contributors)

- **Per-service table:** add `V{N+1}__short_name.sql` under that service's `db/migration`, using
  `IF NOT EXISTS` forms so it is safe on databases that `update` once touched. Never skip a
  version number.
- **Shared `dlap_common` table or column:** edit `02-create-common-schema.sql` and add a dated,
  idempotent delta under `deployment/platform/database/upgrades/`. A change to a CHECK constraint
  must drop and re-add it in the delta. Name it `YYYY-MM-DD_short_name.sql` with a date later
  than every existing delta, and never edit it once it has shipped: the runner records its
  checksum and refuses a changed file.
- **Run the schema tests**, which need Docker. They build the database the way compose does and
  fail on any drift between entities and schema. `CommonSchemaUpgradeTest` builds a database from
  the previous release's init scripts (fixtures in
  `da-catalog/src/test/resources/db/init-scripts-7984d1d`), runs the real runner and deltas, and
  requires `validate` to pass and the result to equal a fresh install. When a release is cut,
  replace those fixtures with that release's init scripts.

  ```bash
  ./gradlew :da-catalog:test --tests "*CommonSchemaValidateTest*" \
            --tests "*CommonSchemaUpgradeTest*" --tests "*UpgradeRunnerScriptTest*" \
            :da-autodelete:test --tests "*AutodeleteFlywayValidateTest*"
  ```

  A Docker-less run reports `initializationError`. That means the schema is unverified, not
  that it passed.

## Known limits

- Entity string columns that declare no length are `varchar(255)`. In `da-autodelete` that
  includes `justification`, `approval_comments` and `rejection_comments`; a longer value is
  rejected by the database. Widening needs an entity change and a new migration.
- A delta that was applied by hand before the ledger existed is applied again by the runner. That
  is safe because every delta is idempotent, and it is recorded from then on.
