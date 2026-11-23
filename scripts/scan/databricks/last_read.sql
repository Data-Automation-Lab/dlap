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
-- DLAP zero-access fitness scan for Databricks Unity Catalog: one metastore.
--
-- Returns one row per table or view in system.information_schema.tables, plus one row per object
-- that lineage or credential events show as read but that is not in that inventory. Each row has
-- the creation time, last write seen in lineage, last definition change, tags, last read, read
-- counts and the number of distinct readers by principal type, from the last 365 days of:
--   system.access.table_lineage  reads (source side) and writes (target side), table level;
--   system.access.audit          unityCatalog generateTemporaryTableCredential with operation
--                                READ: credential vending to engines that read table files.
--
-- It never reads query text. It does not use the system.query schema, and from the audit table
-- it reads only two request parameters, table_full_name and operation. It never returns
-- principal names: principals are counted, not listed.
-- The columns are the scan contract in scripts/scan/README.md; aggregate.py reads them.
--
-- Run (README, "Databricks"): in a SQL editor attached to a SQL warehouse, run this statement
-- and download the result as CSV (scan-databricks-METASTORE.csv).
-- Permissions: CAN USE on the warehouse; USE CATALOG on system; USE SCHEMA and SELECT on
-- system.access and system.information_schema. Tables the principal cannot see are missing.

WITH

-- Edit here. A principal matching this pattern is typed 'system' (the platform's own identity
-- by default). Add monitoring or profiling identities so their reads are counted apart and never
-- make a table look used.
params AS (
  SELECT '^System-User$' AS system_principal_regex
),

-- The observation window. Lineage and audit keep 365 days by default (configurable).
scan_window AS (
  SELECT
    current_timestamp() - INTERVAL 365 DAYS AS window_start,
    current_timestamp() AS window_end
),

-- Tables and views in this metastore, without the platform's own catalogs.
inventory AS (
  SELECT
    lower(t.table_catalog) AS table_catalog,
    lower(t.table_schema) AS table_schema,
    lower(t.table_name) AS table_name,
    concat_ws('.', lower(t.table_catalog), lower(t.table_schema), lower(t.table_name))
      AS table_full_name,
    t.table_type AS table_type,
    t.created AS created,
    t.last_altered AS last_altered
  FROM system.information_schema.tables AS t
  WHERE lower(t.table_catalog) NOT IN ('system', '__databricks_internal')
    AND lower(t.table_schema) <> 'information_schema'
),

-- Governed tags, as key=value pairs joined with ';'.
tags AS (
  SELECT
    concat_ws('.', lower(g.catalog_name), lower(g.schema_name), lower(g.table_name))
      AS table_full_name,
    array_join(array_sort(collect_list(concat(g.tag_name, '=', coalesce(g.tag_value, '')))), ';')
      AS labels
  FROM system.information_schema.table_tags AS g
  GROUP BY concat_ws('.', lower(g.catalog_name), lower(g.schema_name), lower(g.table_name))
),

-- Lineage events in the window, reduced to the columns the scan needs.
lineage AS (
  SELECT
    lower(l.source_table_full_name) AS source_name,
    lower(l.target_table_full_name) AS target_name,
    l.target_type AS target_type,
    l.event_time AS event_time,
    coalesce(l.event_id, l.record_id) AS event_key,
    l.created_by AS principal
  FROM system.access.table_lineage AS l
  CROSS JOIN scan_window AS w
  WHERE l.event_date >= date_sub(current_date(), 366)
    AND l.event_time >= w.window_start
),

-- How far back the lineage history actually reaches.
history AS (
  SELECT min(event_time) AS earliest_event_at
  FROM lineage
),

-- A lineage read: the table is the source of an event. An event whose target is a view or metric
-- view only records a definition, so it is not a read. An event that reads and writes the same
-- table (MERGE, UPDATE, DELETE) counts as a write of it, not a read. Reads through a view count
-- for the view and for every table it expands to.
lineage_reads AS (
  SELECT
    source_name AS table_full_name,
    event_time,
    event_key,
    principal,
    'lineage' AS via
  FROM lineage
  WHERE source_name IS NOT NULL
    AND coalesce(target_type, '') NOT IN ('VIEW', 'METRIC_VIEW')
    AND (target_name IS NULL OR target_name <> source_name)
),

-- The last lineage write per table (the table is the target of an event).
lineage_writes AS (
  SELECT
    target_name AS table_full_name,
    max(event_time) AS last_write_at
  FROM lineage
  WHERE target_name IS NOT NULL
    AND coalesce(target_type, '') NOT IN ('VIEW', 'METRIC_VIEW')
  GROUP BY target_name
),

-- A credential read: Unity Catalog vended a READ credential for the table's files and the call
-- succeeded. External engines and credential-vending clients show up here, not in lineage.
credential_reads AS (
  SELECT
    lower(a.request_params['table_full_name']) AS table_full_name,
    a.event_time AS event_time,
    coalesce(a.event_id, a.request_id) AS event_key,
    a.user_identity.email AS principal,
    'credential' AS via
  FROM system.access.audit AS a
  CROSS JOIN scan_window AS w
  WHERE a.event_date >= date_sub(current_date(), 366)
    AND a.event_time >= w.window_start
    AND a.service_name = 'unityCatalog'
    AND a.action_name = 'generateTemporaryTableCredential'
    AND a.request_params['operation'] = 'READ'
    AND a.response.status_code = 200
    AND a.request_params['table_full_name'] IS NOT NULL
),

all_reads AS (
  SELECT table_full_name, event_time, event_key, principal, via FROM lineage_reads
  UNION ALL
  SELECT table_full_name, event_time, event_key, principal, via FROM credential_reads
),

-- Principal type: 'system' by the pattern above, 'service' for a service principal ID (a UUID
-- or a number), 'human' for a user name (an email), 'unknown' for anything else or no principal.
typed_reads AS (
  SELECT
    r.table_full_name,
    r.event_time,
    r.event_key,
    r.principal,
    r.via,
    CASE
      WHEN r.principal IS NULL THEN 'unknown'
      WHEN r.principal RLIKE p.system_principal_regex THEN 'system'
      WHEN r.principal RLIKE '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
        THEN 'service'
      WHEN r.principal RLIKE '^[0-9]+$' THEN 'service'
      WHEN r.principal LIKE '%@%' THEN 'human'
      ELSE 'unknown'
    END AS principal_type
  FROM all_reads AS r
  CROSS JOIN params AS p
  WHERE split_part(r.table_full_name, '.', 1) NOT IN ('system', '__databricks_internal')
),

-- Per object read: lineage read events, credential read events, last read and distinct readers by
-- principal type. Reads by 'system' principals are kept apart and never set last_read_at.
read_stats AS (
  SELECT
    table_full_name,
    count(DISTINCT CASE WHEN via = 'lineage' AND principal_type <> 'system' THEN event_key END)
      AS read_count,
    count(DISTINCT CASE WHEN via = 'credential' AND principal_type <> 'system' THEN event_key END)
      AS credential_read_count,
    count(DISTINCT CASE WHEN principal_type = 'system' THEN concat(via, ':', event_key) END)
      AS system_read_count,
    max(CASE WHEN principal_type <> 'system' THEN event_time END) AS last_read_at,
    max(CASE WHEN principal_type = 'system' THEN event_time END) AS last_system_read_at,
    count(DISTINCT CASE WHEN principal_type = 'human' THEN principal END) AS readers_human,
    count(DISTINCT CASE WHEN principal_type = 'service' THEN principal END) AS readers_service,
    count(DISTINCT CASE WHEN principal_type = 'system' THEN principal END) AS readers_system,
    count(DISTINCT CASE WHEN principal_type = 'unknown' THEN coalesce(principal, '(none)') END)
      AS readers_unknown
  FROM typed_reads
  GROUP BY table_full_name
),

-- One row per object, timestamps still typed.
joined AS (
  SELECT
    coalesce(i.table_catalog, split_part(r.table_full_name, '.', 1)) AS table_catalog,
    coalesce(i.table_schema, split_part(r.table_full_name, '.', 2)) AS table_schema,
    coalesce(i.table_name, split_part(r.table_full_name, '.', 3)) AS table_name,
    i.table_type AS table_type,
    i.table_full_name IS NOT NULL AS in_inventory,
    i.created AS created_at,
    lw.last_write_at AS last_modified_at,
    i.last_altered AS last_altered_at,
    tg.labels AS labels,
    coalesce(r.read_count, 0) AS read_count,
    coalesce(r.credential_read_count, 0) AS credential_read_count,
    coalesce(r.system_read_count, 0) AS system_read_count,
    r.last_read_at AS last_read_at,
    r.last_system_read_at AS last_system_read_at,
    coalesce(r.readers_human, 0) AS readers_human,
    coalesce(r.readers_service, 0) AS readers_service,
    coalesce(r.readers_system, 0) AS readers_system,
    coalesce(r.readers_unknown, 0) AS readers_unknown
  FROM inventory AS i
  FULL OUTER JOIN read_stats AS r
    ON r.table_full_name = i.table_full_name
  LEFT JOIN lineage_writes AS lw
    ON lw.table_full_name = coalesce(i.table_full_name, r.table_full_name)
  LEFT JOIN tags AS tg
    ON tg.table_full_name = i.table_full_name
)

-- Timestamps are rendered in UTC as yyyy-MM-ddTHH:mm:ssZ whatever the session time zone is.
SELECT
  'databricks' AS platform,
  element_at(split(current_metastore(), ':'), -1) AS scan_scope,
  concat(replace(date_format(to_utc_timestamp(w.window_start, current_timezone()),
                             'yyyy-MM-dd HH:mm:ss'), ' ', 'T'), 'Z') AS window_start,
  concat(replace(date_format(to_utc_timestamp(w.window_end, current_timezone()),
                             'yyyy-MM-dd HH:mm:ss'), ' ', 'T'), 'Z') AS window_end,
  concat(replace(date_format(to_utc_timestamp(h.earliest_event_at, current_timezone()),
                             'yyyy-MM-dd HH:mm:ss'), ' ', 'T'), 'Z') AS earliest_event_at,
  j.table_catalog AS table_catalog,
  j.table_schema AS table_schema,
  j.table_name AS table_name,
  j.table_type AS table_type,
  j.in_inventory AS in_inventory,
  concat(replace(date_format(to_utc_timestamp(j.created_at, current_timezone()),
                             'yyyy-MM-dd HH:mm:ss'), ' ', 'T'), 'Z') AS created_at,
  concat(replace(date_format(to_utc_timestamp(j.last_modified_at, current_timezone()),
                             'yyyy-MM-dd HH:mm:ss'), ' ', 'T'), 'Z') AS last_modified_at,
  CASE WHEN j.last_modified_at IS NOT NULL THEN 'lineage' END AS last_modified_source,
  concat(replace(date_format(to_utc_timestamp(j.last_altered_at, current_timezone()),
                             'yyyy-MM-dd HH:mm:ss'), ' ', 'T'), 'Z') AS last_altered_at,
  CAST(NULL AS BIGINT) AS size_bytes,
  CAST(NULL AS BIGINT) AS row_count,
  CAST(NULL AS STRING) AS expiration_at,
  CAST(NULL AS DOUBLE) AS partition_expiration_days,
  j.labels AS labels,
  j.read_count AS read_count,
  j.credential_read_count AS credential_read_count,
  j.system_read_count AS system_read_count,
  concat(replace(date_format(to_utc_timestamp(j.last_read_at, current_timezone()),
                             'yyyy-MM-dd HH:mm:ss'), ' ', 'T'), 'Z') AS last_read_at,
  concat(replace(date_format(to_utc_timestamp(j.last_system_read_at, current_timezone()),
                             'yyyy-MM-dd HH:mm:ss'), ' ', 'T'), 'Z') AS last_system_read_at,
  j.readers_human AS readers_human,
  j.readers_service AS readers_service,
  j.readers_system AS readers_system,
  j.readers_unknown AS readers_unknown,
  'uc_lineage,uc_credentials' AS read_sources
FROM joined AS j
CROSS JOIN scan_window AS w
CROSS JOIN history AS h
ORDER BY table_catalog, table_schema, table_name
