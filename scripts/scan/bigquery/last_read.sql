-- Licensed to the Apache Software Foundation (ASF) under one
-- or more contributor license agreements.  See the NOTICE file
-- distributed with this work for additional information
-- regarding copyright ownership.  The ASF licenses this file
-- to you under the Apache License, Version 2.0 (the
-- "License"); you may not use this file except in compliance
-- with the License.  You may obtain a copy of the License at
--
--   http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing,
-- software distributed under the License is distributed on an
-- "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
-- KIND, either express or implied.  See the License for the
-- specific language governing permissions and limitations
-- under the License.
--
-- DLAP zero-access fitness scan for BigQuery: one project, one region.
--
-- Returns one row per table or view in this project and region (INFORMATION_SCHEMA.TABLES,
-- TABLE_STORAGE and TABLE_OPTIONS), plus one row per object that this project's query jobs read
-- but that is not in that inventory (another project's table, or a deleted table). Each row has
-- the creation time, last write, size, native expiry, labels, last read, read count and the
-- number of distinct readers by principal type, from the last 180 days of
-- INFORMATION_SCHEMA.JOBS_BY_PROJECT.
--
-- It never returns query text, job IDs or principal names: principals are counted, not listed.
-- The columns are the scan contract in scripts/scan/README.md; aggregate.py reads them.
--
-- Run (README, "BigQuery"):
--   bq query --use_legacy_sql=false --location=US --format=csv --max_rows=1000000 \
--     < scripts/scan/bigquery/last_read.sql > scan-bigquery-PROJECT-us.csv
-- Region: replace all four `region-us` qualifiers below with the region your datasets live in,
-- and run the query in that same location.
-- Permissions on the project: bigquery.jobs.listAll and bigquery.jobs.create (BigQuery Resource
-- Viewer plus BigQuery User) and bigquery.tables.get and bigquery.tables.list (BigQuery Metadata
-- Viewer).

WITH

-- Edit here. A principal whose user_email (or, when that is empty, principal_subject) matches
-- this pattern is typed 'system': Google service agents by default. Add monitoring or profiling
-- identities so their reads are counted apart and never make a table look used.
params AS (
  SELECT
    r'(@gcp-sa-[a-z0-9-]+\.iam\.gserviceaccount\.com|@cloudservices\.gserviceaccount\.com)$'
      AS system_principal_regex
),

-- The observation window. JOBS keeps 180 days of history, so a longer window reads nothing more.
scan_window AS (
  SELECT
    TIMESTAMP_SUB(CURRENT_TIMESTAMP(), INTERVAL 180 DAY) AS window_start,
    CURRENT_TIMESTAMP() AS window_end
),

-- Every job created in the window, reduced to the columns the scan needs.
all_jobs AS (
  SELECT
    j.project_id,
    j.job_id,
    j.parent_job_id,
    j.creation_time,
    j.end_time,
    j.job_type,
    j.state,
    j.statement_type,
    j.cache_hit,
    j.error_result IS NOT NULL AS failed,
    j.destination_table,
    j.referenced_tables,
    j.referenced_logical_views,
    j.user_email,
    j.principal_subject
  FROM `region-us`.INFORMATION_SCHEMA.JOBS_BY_PROJECT AS j
  CROSS JOIN scan_window AS w
  WHERE j.creation_time >= w.window_start
),

-- Jobs that started child jobs (multi-statement scripts, procedure calls). The children carry
-- the reads, so a parent is never counted.
parent_jobs AS (
  SELECT DISTINCT parent_job_id AS job_id
  FROM all_jobs
  WHERE parent_job_id IS NOT NULL
),

-- A read job: a query job that finished without error, did not come from the result cache, is
-- not a parent of other jobs, and is not a statement that only defines or drops objects.
read_jobs AS (
  SELECT
    j.job_id,
    j.end_time,
    j.destination_table,
    j.referenced_tables,
    j.referenced_logical_views,
    IFNULL(j.user_email, j.principal_subject) AS principal_key,
    CASE
      WHEN REGEXP_CONTAINS(LOWER(IFNULL(j.user_email, IFNULL(j.principal_subject, ''))),
                           p.system_principal_regex) THEN 'system'
      WHEN STARTS_WITH(IFNULL(j.principal_subject, ''), 'serviceAccount:')
        OR ENDS_WITH(LOWER(IFNULL(j.user_email, '')), '.gserviceaccount.com') THEN 'service'
      WHEN STRPOS(IFNULL(j.principal_subject, ''), '/workloadIdentityPools/') > 0 THEN 'service'
      WHEN STRPOS(IFNULL(j.principal_subject, ''), '/workforcePools/') > 0 THEN 'human'
      WHEN STARTS_WITH(IFNULL(j.principal_subject, ''), 'user:')
        OR STRPOS(IFNULL(j.user_email, ''), '@') > 0 THEN 'human'
      ELSE 'unknown'
    END AS principal_type
  FROM all_jobs AS j
  CROSS JOIN params AS p
  LEFT JOIN parent_jobs AS pj ON pj.job_id = j.job_id
  WHERE j.job_type = 'QUERY'
    AND j.state = 'DONE'
    AND NOT j.failed
    AND j.cache_hit IS NOT TRUE
    AND pj.job_id IS NULL
    AND IFNULL(j.statement_type, '') NOT IN (
      'SCRIPT',
      'CREATE_VIEW', 'ALTER_VIEW', 'DROP_VIEW',
      'ALTER_MATERIALIZED_VIEW', 'DROP_MATERIALIZED_VIEW',
      'ALTER_TABLE', 'DROP_TABLE', 'DROP_EXTERNAL_TABLE', 'TRUNCATE_TABLE',
      'DROP_SNAPSHOT_TABLE', 'CREATE_EXTERNAL_TABLE',
      'CREATE_SCHEMA', 'ALTER_SCHEMA', 'DROP_SCHEMA',
      'CREATE_FUNCTION', 'CREATE_TABLE_FUNCTION', 'DROP_FUNCTION', 'DROP_TABLE_FUNCTION',
      'CREATE_PROCEDURE', 'DROP_PROCEDURE',
      'CREATE_ROW_ACCESS_POLICY', 'DROP_ROW_ACCESS_POLICY', 'DROP_ALL_ROW_ACCESS_POLICIES',
      'DROP_MODEL', 'DROP_SEARCH_INDEX', 'DROP_VECTOR_INDEX'
    )
),

-- One row per (read job, object read). A job's destination is what it writes, so a table that a
-- job both references and writes (MERGE, UPDATE, DELETE, or INSERT that selects from its own
-- target) counts as written by that job, not read. A logical view a job references counts as a
-- read of the view.
reads AS (
  SELECT
    t.project_id,
    t.dataset_id,
    t.table_id,
    r.job_id,
    r.end_time,
    r.principal_key,
    r.principal_type
  FROM read_jobs AS r, UNNEST(r.referenced_tables) AS t
  WHERE NOT IFNULL(
    t.project_id = r.destination_table.project_id
      AND t.dataset_id = r.destination_table.dataset_id
      AND t.table_id = r.destination_table.table_id,
    FALSE)
  UNION ALL
  SELECT
    v.project_id,
    v.dataset_id,
    v.table_id,
    r.job_id,
    r.end_time,
    r.principal_key,
    r.principal_type
  FROM read_jobs AS r, UNNEST(r.referenced_logical_views) AS v
),

-- Per object read: read jobs, last read and distinct readers by principal type. Reads by 'system'
-- principals are kept apart and never set last_read_at.
read_stats AS (
  SELECT
    project_id,
    dataset_id,
    table_id,
    COUNT(DISTINCT IF(principal_type != 'system', job_id, NULL)) AS read_count,
    COUNT(DISTINCT IF(principal_type = 'system', job_id, NULL)) AS system_read_count,
    MAX(IF(principal_type != 'system', end_time, NULL)) AS last_read_at,
    MAX(IF(principal_type = 'system', end_time, NULL)) AS last_system_read_at,
    COUNT(DISTINCT IF(principal_type = 'human', principal_key, NULL)) AS readers_human,
    COUNT(DISTINCT IF(principal_type = 'service', principal_key, NULL)) AS readers_service,
    COUNT(DISTINCT IF(principal_type = 'system', principal_key, NULL)) AS readers_system,
    COUNT(DISTINCT IF(principal_type = 'unknown', IFNULL(principal_key, '(none)'), NULL))
      AS readers_unknown
  FROM reads
  WHERE NOT REGEXP_CONTAINS(UPPER(IFNULL(dataset_id, '')), r'INFORMATION_SCHEMA')
  GROUP BY project_id, dataset_id, table_id
),

-- How far back the job history actually reaches, and the project the jobs ran in.
history AS (
  SELECT
    MIN(creation_time) AS earliest_event_at,
    ANY_VALUE(project_id) AS job_project
  FROM all_jobs
),

-- Tables and views in this project and region, with storage facts where the object has storage.
inventory AS (
  SELECT
    t.table_catalog AS project_id,
    t.table_schema AS dataset_id,
    t.table_name AS table_id,
    t.table_type,
    t.creation_time,
    s.storage_last_modified_time,
    s.total_logical_bytes,
    s.total_rows
  FROM `region-us`.INFORMATION_SCHEMA.TABLES AS t
  LEFT JOIN `region-us`.INFORMATION_SCHEMA.TABLE_STORAGE AS s
    ON s.project_id = t.table_catalog
    AND s.table_schema = t.table_schema
    AND s.table_name = t.table_name
    AND s.deleted IS NOT TRUE
),

-- Native expiry and labels. option_value is a string: expiration_timestamp looks like
-- TIMESTAMP "2026-01-16T21:12:28.000Z"; labels are passed through for aggregate.py to parse.
options AS (
  SELECT
    table_catalog AS project_id,
    table_schema AS dataset_id,
    table_name AS table_id,
    MAX(IF(option_name = 'expiration_timestamp',
           COALESCE(
             SAFE.PARSE_TIMESTAMP('%Y-%m-%dT%H:%M:%E*SZ',
                                  REGEXP_EXTRACT(option_value, r'"([^"]*)"')),
             SAFE_CAST(REGEXP_EXTRACT(option_value, r'"([^"]*)"') AS TIMESTAMP)),
           NULL)) AS expiration_at,
    MAX(IF(option_name = 'partition_expiration_days', SAFE_CAST(option_value AS FLOAT64), NULL))
      AS partition_expiration_days,
    MAX(IF(option_name = 'labels', option_value, NULL)) AS labels
  FROM `region-us`.INFORMATION_SCHEMA.TABLE_OPTIONS
  WHERE option_name IN ('expiration_timestamp', 'partition_expiration_days', 'labels')
  GROUP BY table_catalog, table_schema, table_name
),

-- The project this scan covers: where the jobs ran, or, with no jobs at all, the inventory's.
scope AS (
  SELECT COALESCE(h.job_project, (SELECT ANY_VALUE(project_id) FROM inventory)) AS scan_scope
  FROM history AS h
)

SELECT
  'bigquery' AS platform,
  sc.scan_scope AS scan_scope,
  FORMAT_TIMESTAMP('%Y-%m-%dT%H:%M:%SZ', w.window_start, 'UTC') AS window_start,
  FORMAT_TIMESTAMP('%Y-%m-%dT%H:%M:%SZ', w.window_end, 'UTC') AS window_end,
  FORMAT_TIMESTAMP('%Y-%m-%dT%H:%M:%SZ', h.earliest_event_at, 'UTC') AS earliest_event_at,
  COALESCE(i.project_id, r.project_id) AS table_catalog,
  COALESCE(i.dataset_id, r.dataset_id) AS table_schema,
  COALESCE(i.table_id, r.table_id) AS table_name,
  i.table_type AS table_type,
  i.project_id IS NOT NULL AS in_inventory,
  FORMAT_TIMESTAMP('%Y-%m-%dT%H:%M:%SZ', i.creation_time, 'UTC') AS created_at,
  FORMAT_TIMESTAMP('%Y-%m-%dT%H:%M:%SZ', i.storage_last_modified_time, 'UTC') AS last_modified_at,
  IF(i.storage_last_modified_time IS NULL, NULL, 'storage') AS last_modified_source,
  CAST(NULL AS STRING) AS last_altered_at,
  i.total_logical_bytes AS size_bytes,
  i.total_rows AS row_count,
  FORMAT_TIMESTAMP('%Y-%m-%dT%H:%M:%SZ', o.expiration_at, 'UTC') AS expiration_at,
  o.partition_expiration_days AS partition_expiration_days,
  o.labels AS labels,
  IFNULL(r.read_count, 0) AS read_count,
  CAST(NULL AS INT64) AS credential_read_count,
  IFNULL(r.system_read_count, 0) AS system_read_count,
  FORMAT_TIMESTAMP('%Y-%m-%dT%H:%M:%SZ', r.last_read_at, 'UTC') AS last_read_at,
  FORMAT_TIMESTAMP('%Y-%m-%dT%H:%M:%SZ', r.last_system_read_at, 'UTC') AS last_system_read_at,
  IFNULL(r.readers_human, 0) AS readers_human,
  IFNULL(r.readers_service, 0) AS readers_service,
  IFNULL(r.readers_system, 0) AS readers_system,
  IFNULL(r.readers_unknown, 0) AS readers_unknown,
  'bq_jobs' AS read_sources
FROM inventory AS i
FULL OUTER JOIN read_stats AS r
  ON r.project_id = i.project_id
  AND r.dataset_id = i.dataset_id
  AND r.table_id = i.table_id
LEFT JOIN options AS o
  ON o.project_id = i.project_id
  AND o.dataset_id = i.dataset_id
  AND o.table_id = i.table_id
CROSS JOIN scan_window AS w
CROSS JOIN history AS h
CROSS JOIN scope AS sc
ORDER BY table_catalog, table_schema, table_name
