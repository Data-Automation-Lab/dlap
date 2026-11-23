# Zero-access fitness scan kit

Open SQL that a customer runs in their own BigQuery or Databricks console, plus a small Python
script they run on their own machine. Nothing is installed in the customer's account, no role is
granted to DLAP, and only aggregates leave: per table, the creation date, last write, last read,
reader counts by principal type and the table's class against the customer's retention schedule.

The kit is also the read-semantics oracle for the BigQuery JOBS reader (work package GCP-02):
the rules under "What counts as a read" are the contract that reader must reproduce.

| File | What it is |
|---|---|
| `bigquery/last_read.sql` | One statement per project and region: inventory, storage, expiry, labels, reads |
| `databricks/last_read.sql` | One statement per Unity Catalog metastore: inventory, tags, lineage and credential reads |
| `aggregate.py` | Merges scan CSVs, classifies tables, applies the verdicts, writes the report |

## Validation status

- The SQL has **not yet run against a live account**. Live validation waits for the founder's GCP
  sandbox project (`dlap-demo`); the Databricks SQL waits for a Databricks trial workspace or a
  qualified prospect. Until then, items marked [unverified] below are documented behaviour or
  inference, not observed behaviour.
- `python -I scripts/scan/aggregate.py --selftest` passes. It covers the parser, merge rules,
  verdicts, redaction, refusal of query-text and principal columns, and a lint of both SQL files
  (contract columns in order, region qualifiers consistent, no query-text columns, no wildcard
  selects, only the two allowed audit request parameters).
- The live validation plan is at the end of this file.

## What leaves, and what never does

| Stays with the customer | May leave (customer's choice) | Never produced |
|---|---|---|
| The scan CSV files | `summary.json`, `summary.md` (counts and bytes only) | Query text |
| `redaction_map.KEEP_LOCAL.csv` | `tables.csv` (per table; names can be redacted) | Job IDs, statement IDs |
| | | Principal names, emails or IDs |

- The SQL counts distinct principals per table and type; it never outputs a principal.
- The BigQuery SQL never selects the JOBS `query` column and never selects every column.
- The Databricks SQL never reads the `system.query` schema, and from `system.access.audit` it reads
  only the request parameters `table_full_name` and `operation`. Query text there has been masked
  since 2026-08-26 for everyone except account admins and members of `databricks_pii_access`;
  the kit does not need it and never asks for it.
- `aggregate.py` refuses any input with a column such as `query`, `statement_text`, `user_email`,
  `principal_subject` or `created_by`.

## BigQuery

### Permissions

On the scanned project, for the person who runs the query:

- `bigquery.jobs.listAll` and `bigquery.jobs.create`: roles BigQuery Resource Viewer
  (`roles/bigquery.resourceViewer`) plus BigQuery User (`roles/bigquery.user`).
- `bigquery.tables.get` and `bigquery.tables.list`: role BigQuery Metadata Viewer
  (`roles/bigquery.metadataViewer`).

`bigquery.jobs.listAll` can see other users' query text in the console. The scan SQL does not
select it, but the grant allows it, so the customer's own administrator should run the scan.

### Region

INFORMATION_SCHEMA views need a region qualifier, and the query must run in that location.
Regions are not hierarchical and one query cannot join two regions. The file uses `region-us`
four times (JOBS_BY_PROJECT, TABLES, TABLE_STORAGE, TABLE_OPTIONS); change all four together:

```bash
sed 's/`region-us`/`region-europe-west2`/g' scripts/scan/bigquery/last_read.sql > /tmp/scan-ew2.sql
```

Run the scan once per region that holds datasets.

### Run

```bash
# Dry run first: prints bytes processed, runs nothing.
bq query --use_legacy_sql=false --location=US --dry_run < scripts/scan/bigquery/last_read.sql

# The scan. CSV output, all rows.
bq query --use_legacy_sql=false --location=US --format=csv --max_rows=1000000 \
  < scripts/scan/bigquery/last_read.sql > scan-bigquery-PROJECT-us.csv
```

In the console: paste the file, set the processing location under More, Query settings, run,
then Save results, CSV. The project the query runs in is the project scanned.

To scan several projects, run the statement in each project and pass every CSV to
`aggregate.py`. A table in project A read by jobs in project B appears in B's result as a row with
`in_inventory = false`; `aggregate.py` adds those reads to A's table.

### Cost

INFORMATION_SCHEMA queries are billed as normal queries (on-demand: a 10 MB minimum, results are
never cached). The statement reads 180 days of JOBS metadata plus the table metadata views. Use
the dry run to see the bytes.

### What counts as a read

A **read job** is a row of `region-REGION.INFORMATION_SCHEMA.JOBS_BY_PROJECT` with:

1. `creation_time` within the last 180 days (the view's full history);
2. `job_type = 'QUERY'`, `state = 'DONE'` and `error_result IS NULL`;
3. `cache_hit IS NOT TRUE`: a cached result reads no table, and `referenced_tables` is empty
   for cache hits anyway;
4. not a parent: no other job in the window names it as `parent_job_id`, and its
   `statement_type` is not `SCRIPT`. A multi-statement script's child jobs carry the reads and
   are counted; the parent is not, so nothing is counted twice;
5. `statement_type` is not a definition-only statement: CREATE, ALTER or DROP of a view, schema,
   routine, row access policy or external table, the other DROP and ALTER statements, and
   TRUNCATE (full list in the SQL). Redeploying views (for example a dbt run) therefore does not
   make base tables look read. Statements that read rows (SELECT, INSERT ... SELECT, CREATE TABLE
   AS SELECT, MERGE, UPDATE, DELETE, EXPORT DATA, CREATE MODEL) are counted.

Each read job reads:

- every table in `referenced_tables`, **except** the job's own `destination_table`: a table a
  job both references and writes (MERGE, UPDATE, DELETE, or INSERT that selects from its target)
  counts as written by that job, not read. A nightly retention DELETE does not keep a table
  looking used;
- every view in `referenced_logical_views`;
- nothing in a dataset whose name contains `INFORMATION_SCHEMA`.

Per object: `read_count` is the number of distinct read jobs by non-system principals;
`last_read_at` is the latest `end_time` among them; `system_read_count` and
`last_system_read_at` cover system principals, which never set `last_read_at`.

### Principal types (BigQuery)

The principal key is `user_email`, or `principal_subject` when the email is empty. In order:

| Type | Rule |
|---|---|
| `system` | the key matches `system_principal_regex` in the `params` block: Google service agents (`@gcp-sa-*.iam.gserviceaccount.com`, `@cloudservices.gserviceaccount.com`) by default. Add monitoring, profiling or catalog crawler identities there. |
| `service` | `principal_subject` starts with `serviceAccount:`, the email ends with `.gserviceaccount.com`, or the subject names a workload identity pool (`/workloadIdentityPools/`) |
| `human` | the subject names a workforce pool (`/workforcePools/`), starts with `user:`, or the email contains `@` |
| `unknown` | anything else, including no principal |

The `principal_subject` formats are [unverified]; validation case 6 checks them.

### Inventory fields

- `created_at`: `TABLES.creation_time`.
- `last_modified_at`: `TABLE_STORAGE.storage_last_modified_time`, the most recent write
  (`last_modified_source = storage`). Views have no storage row.
- `size_bytes`, `row_count`: `TABLE_STORAGE.total_logical_bytes`, `total_rows`.
- `expiration_at`, `partition_expiration_days`, `labels`: `TABLE_OPTIONS`. The expiry string
  format `TIMESTAMP "2020-01-16T21:12:28.000Z"` is documented; the labels format
  `[STRUCT("key", "value"), ...]` is [unverified], so `aggregate.py` also accepts `key=value;...`.

## Databricks

### Permissions

For the person or service principal that runs the statement: CAN USE on one SQL warehouse;
`USE CATALOG` on `system`; `USE SCHEMA` and `SELECT` on `system.access` and
`system.information_schema`. The `access` system schema must be enabled for the metastore (an
account or metastore admin enables it). Tables the principal cannot see are missing from the
inventory.

### Run

Open a SQL editor on a SQL warehouse, paste `databricks/last_read.sql`, run it, and download the
result as CSV (`scan-databricks-METASTORE.csv`). Run it once per metastore; system tables are
regional.

### What counts as a read

From `system.access.table_lineage`, events in the last 365 days:

1. a **lineage read** of table T is an event whose `source_table_full_name` is T;
2. not when the event's `target_type` is `VIEW` or `METRIC_VIEW`: that records a definition
   (CREATE VIEW), not a data read;
3. not when the target is T itself: an event that reads and writes the same table (MERGE,
   UPDATE, DELETE) counts as a write;
4. reads through a view count for the view and, by view expansion (`direct_access = false`), for
   every table it expands to;
5. `read_count` is the number of distinct lineage events (`event_id`, or `record_id` when the
   event ID is empty).

From `system.access.audit`, events in the last 365 days:

6. a **credential read** of T is a `unityCatalog` `generateTemporaryTableCredential` event with
   request parameter `operation = 'READ'`, `table_full_name` T, and `response.status_code = 200`.
   Engines that read table files with vended credentials show up here and not in lineage.
   `credential_read_count` counts these events separately, because Databricks compute may
   produce both a lineage event and a credential event for one read [unverified].

`last_read_at` is the latest of both kinds by non-system principals. `last_modified_at` is the
latest lineage event whose target is the table (`last_modified_source = lineage`); it is not an
authoritative last write. `last_altered_at` is `information_schema.tables.last_altered`, a
definition change, not a data write.

### Principal types (Databricks)

The principal is lineage `created_by` or audit `user_identity.email`. In order: `system` when it
matches `system_principal_regex` (`System-User` by default); `service` for a UUID or a number (a
service principal ID); `human` when it contains `@`; otherwise, or with no principal, `unknown`
(group names land here).

## Scan contract

Every scan CSV has these columns, in this order. Timestamps are UTC, `yyyy-MM-ddTHH:mm:ssZ`.
`aggregate.py` requires all but the six marked optional and tolerates extra columns other than
the refused ones.

| Column | Meaning | BigQuery | Databricks |
|---|---|---|---|
| `platform` | `bigquery` or `databricks` | | |
| `scan_scope` | what was scanned | project the jobs ran in | metastore ID |
| `window_start`, `window_end` | requested history window | now minus 180 days, now | now minus 365 days, now |
| `earliest_event_at` | earliest event the history holds | earliest job | earliest lineage event |
| `table_catalog`, `table_schema`, `table_name` | object name | project, dataset, table | catalog, schema, table (lower case) |
| `table_type` | object type | `TABLES.table_type` | `tables.table_type` |
| `in_inventory` | the object is in this scan's inventory | | |
| `created_at` | creation time | `TABLES.creation_time` | `tables.created` |
| `last_modified_at`, `last_modified_source` | last write and where it came from | storage | lineage |
| `last_altered_at` (optional) | last definition change | empty | `tables.last_altered` |
| `size_bytes`, `row_count` (`row_count` optional) | size | `TABLE_STORAGE` | empty |
| `expiration_at`, `partition_expiration_days` (optional) | native expiry | `TABLE_OPTIONS` | empty |
| `labels` (optional) | labels or governed tags | raw option value | `key=value;...` |
| `read_count` | primary read events | read jobs | lineage read events |
| `credential_read_count` (optional) | credential reads | empty | credential events |
| `system_read_count`, `last_system_read_at` | reads by system principals | | |
| `last_read_at` | last read by a non-system principal | | |
| `readers_human`, `readers_service`, `readers_system`, `readers_unknown` | distinct readers by type | | |
| `read_sources` | what the reads came from | `bq_jobs` | `uc_lineage,uc_credentials` |

Asset URIs follow ADR-0047: `bq://{project}/{dataset}/{table}` (case kept) and
`uc://{metastore}/{catalog}/{schema}/{table}` (lower case). `aggregate.py` builds them.

## aggregate.py

Python 3.9 or later, standard library only. Run it with `python -I` so nothing from the current
directory is imported.

```bash
python -I scripts/scan/aggregate.py scan-bigquery-acme-prod-us.csv scan-bigquery-acme-bi-us.csv \
  --schedule schedule.csv --classes classes.csv --out-dir report/
python -I scripts/scan/aggregate.py --selftest
```

Options: `--stale-days N` (default 90), `--window observed|nominal` (default observed),
`--default-class NAME`, `--redact`, `--redact-key-file PATH`.

### schedule.csv

The customer's retention schedule, one row per class. Either number may be blank.

```csv
class,retention_days,stale_days
customer_pii,365,
finance,2555,180
scratch,,30
```

### classes.csv

How tables get a class. The first matching row wins; a table no row matches takes
`--default-class`, or stays unclassified.

```csv
match,pattern,class
label,data_class=pii,customer_pii
name,acme-prod.finance.*,finance
label_value,dlap_class,
name,bq://acme-prod/tmp_*,scratch
```

| `match` | `pattern` | Matches when |
|---|---|---|
| `name` | a glob | the dotted name (`project.dataset.table`, `catalog.schema.table`) matches, or the asset URI when the pattern contains `://`. Databricks names compare in lower case. |
| `label` | `key=glob` | the table has that label or tag and its value matches (`key=` matches any value) |
| `label_value` | a label key | the table has that label; its value is the class (leave `class` empty) |
| `type` | a glob | the table type matches, for example `MATERIALIZED*` |

### Verdicts

"Now" is the `window_end` of the scan that inventoried the table; the script never reads the
clock. The history used starts at the later of `window_start` and `earliest_event_at`
(`--window observed`), or at `window_start` (`--window nominal`, for a project known to have full
history). **Unknown is never stale or fresh.**

Usage, with S the class's `stale_days` or `--stale-days`:

| Verdict | Rule, in order |
|---|---|
| `ACTIVE` | a non-system read within the last S days (from any scan) |
| `UNKNOWN_WINDOW` | the history used starts later than S days ago |
| `UNKNOWN_CREATED` | no creation time |
| `NEW` | created within the last S days |
| `STALE` | otherwise: no non-system read seen in the last S days |

Retention, with R the class's `retention_days`:

| Verdict | Rule, in order |
|---|---|
| `NOT_APPLICABLE` | a logical view (BigQuery `VIEW`; Databricks `VIEW`, `METRIC_VIEW`) |
| `EXTERNAL_STORAGE` | data stored outside the platform (BigQuery `EXTERNAL`; Databricks `EXTERNAL`, `EXTERNAL_SHALLOW_CLONE`, `FOREIGN`): retention belongs to the storage location |
| `UNKNOWN_TYPE` | any other type the kit does not know |
| `UNCLASSIFIED` | no class |
| `NO_RULE` | the class has no `retention_days` |
| `UNKNOWN_CREATED` | no creation time |
| `MANAGED_BY_EXPIRY` | partition expiry of at most R days, or a table expiry no later than creation plus R |
| `OVER_RETENTION` | older than R days |
| `WITHIN` | otherwise |

`all_rows_past_retention` is set only for `OVER_RETENTION` with an authoritative last write
(BigQuery storage): `true` when the last write is older than R days, so every row is past
retention by write time; `false` when the table was written within R days and may hold younger
rows. It stays empty otherwise.

### Outputs

- `tables.csv`: one row per inventoried table with its class, age, last read, readers, size,
  expiry, both verdicts, `as_of` and `history_start`. `readers_lower_bound = true` when several
  scans saw reads: distinct readers cannot be added across scans, so the larger count is shown.
- `summary.json`: counts and bytes by verdict and class, findings (over retention and stale,
  over retention with every row past it, never read in the history used), reader mix, objects
  read but not inventoried, scan windows and the coverage notes. No table names.
- `summary.md`: the same as text for an evidence memo, with the ten largest over-retention tables.

The script refuses a scan file given twice, a table inventoried by two scans (the same project
and region scanned twice), a file that mixes scans, and any file with query-text or principal
columns.

### Redaction

`--redact` replaces asset names and scan scopes in all three outputs with HMAC-SHA256 tokens and
writes `redaction_map.KEEP_LOCAL.csv`, which stays with the customer. Without
`--redact-key-file` the key is random per run; with it, tokens are stable across runs.

## Known gaps

BigQuery:

- Reads that are not query jobs are invisible: the Storage Read API (Spark and Dataflow
  connectors, many BI tools), `tabledata.list` (console table preview, `bq head`), and EXTRACT and
  COPY jobs, whose source table JOBS does not name. A `STALE` table had no query-job read; it is
  not proof of no access. Data Access audit logs (`tableDataRead`) cover these paths.
- Only jobs run in the scanned project are visible. Reads of its tables by jobs in other projects
  appear only when those projects are scanned too. `JOBS_BY_ORGANIZATION` would cover the
  organization in one query, but needs organization-level permission and is not used [its
  column set was not checked].
- History is 180 days at most, and shorter if the project moved into an organization within that
  time (earlier jobs are not accessible). `--window observed` handles this conservatively.
- Whether `referenced_tables` lists the base tables of a referenced view, and how it lists
  wildcard tables, is [unverified] (validation cases 5 and 12).
- Whether `CREATE_VIEW` jobs populate `referenced_tables` is [unverified]; they are excluded
  either way.
- `TABLE_STORAGE` freshness is seconds to minutes; expirations can take up to a day to show.

Databricks:

- Lineage is "a subset of events, recorded when it can be inferred". Not captured: RDDs, global
  temp views, tables used inside UDF bodies, and lineage from renamed objects. Path-only events
  (no table name) are not attributed to a table.
- Lineage events persist when a statement fails or rolls back, so reads may be over-counted.
  Filtering them would need `system.query.history`, which the kit does not read.
- Delta Sharing reads (`deltaSharingQueryTable`) are not attributed to the shared table; `getTable`
  is a metadata fetch, not a read.
- Size and row counts are not available set-wise; `size_bytes` is empty.
- The audit `operation` value `READ` comes from the open-source Unity Catalog spec; the
  Databricks-hosted values are [unverified]. `READ_WRITE` credentials are not counted as reads.
- History is 365 days by default (system-table retention is configurable, 30 to 3,650 days).

Both:

- Monitoring, profiling and catalog crawlers read tables too. Add their identities to
  `system_principal_regex` or their reads keep tables `ACTIVE`.
- Table age is a proxy for data age. A table older than R days may hold only young rows (rolling
  partitions without native expiry); `all_rows_past_retention` separates the certain cases on
  BigQuery.

## Live validation plan (dlap-demo)

The founder's GCP sandbox provides the project. Create dataset `scan_check` in `US` with tables `a`, `b`,
`c`, `d` (a few rows each), view `v` over `b`, and the cases below, using one human account and
one service account. Wait an hour for JOBS and TABLE_STORAGE to settle, run the scan, run
`aggregate.py`, and attach the CSVs and `summary.md` to the PR. Expected results:

| # | Scripted action | Expected in the scan row |
|---|---|---|
| 1 | Human runs `SELECT * FROM scan_check.a` | `a`: `read_count` +1, `readers_human` 1 |
| 2 | Same query again (cache hit) | `a`: unchanged |
| 3 | Script with two SELECTs on `a` and `b` | `a` and `b` +1 each; the parent job adds nothing |
| 4 | A SELECT on `c` that fails at run time (for example `SELECT 1/0 FROM scan_check.c`) | `c`: unchanged |
| 5 | `SELECT * FROM scan_check.v` | `v` +1; record whether `b` also gains a read |
| 6 | Service account runs `SELECT * FROM scan_check.a` | `a`: `readers_service` 1; record the `principal_subject` forms |
| 7 | `MERGE scan_check.c USING scan_check.a ...` | `a` +1, `c` unchanged, `c.last_modified_at` moves |
| 8 | `bq extract scan_check.d gs://...` | `d`: unchanged (known gap) |
| 9 | Console preview of `d` | `d`: unchanged (known gap) |
| 10 | `CREATE OR REPLACE VIEW scan_check.v2 AS SELECT * FROM scan_check.d` | `d`: unchanged |
| 11 | Set a label, a table expiry and a partition expiry | `labels`, `expiration_at`, `partition_expiration_days` filled; record the labels format |
| 12 | Tables `shard_1`, `shard_2`; `SELECT * FROM scan_check.shard_*` | record whether each shard gains a read |

The Databricks SQL gets the same treatment on a trial workspace: a SQL warehouse read, a notebook
read, a read through a view, a MERGE, a CREATE VIEW, and an external engine read with a vended
credential.

## Sources

Taken from DLAP's log-source research of 2026-10-18 and the pages it cites. The JOBS, TABLES,
TABLE_STORAGE and TABLE_OPTIONS columns and the Databricks lineage columns and audit request
parameters were re-checked on the live documentation on 2026-10-18.

- INFORMATION_SCHEMA.JOBS: https://docs.cloud.google.com/bigquery/docs/information-schema-jobs
- INFORMATION_SCHEMA.TABLES: https://docs.cloud.google.com/bigquery/docs/information-schema-tables
- INFORMATION_SCHEMA.TABLE_STORAGE: https://docs.cloud.google.com/bigquery/docs/information-schema-table-storage
- INFORMATION_SCHEMA.TABLE_OPTIONS: https://docs.cloud.google.com/bigquery/docs/information-schema-table-options
- Databricks lineage system tables: https://docs.databricks.com/aws/en/admin/system-tables/lineage
- Databricks audit log reference: https://docs.databricks.com/aws/en/admin/account-settings/audit-logs
- Databricks query text masking (2026-08-26): https://docs.databricks.com/aws/en/release-notes/product/2026/august
- Databricks information_schema.tables: https://docs.databricks.com/aws/en/sql/language-manual/information-schema/tables
