/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.dlap.logingest.parse.bqjobs;

import com.dlap.common.logevent.LogEventIds;
import com.dlap.logingest.parse.core.VersionRange;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Names, column contract and small pure helpers of the BigQuery JOBS family.
 *
 * <p>The columns follow the JOBS view page as opened on 2026-10-19 (hardening research, GCP report
 * sections 4 and 7; coverage spec {@code bqjobs}, version {@code jobs-2026-10}). The view has no
 * version field: the documented column set is the version, and {@link #columnSetHash(Collection)}
 * fingerprints a result set so a run can notice drift.
 */
public final class BqJobsFormat {

    /** Directory of the family mapping and fixtures. */
    public static final String FAMILY = "bqjobs";

    /** Source kind of every record of the family. */
    public static final String SOURCE_KIND = "GCP_BQ_JOBS_VIEW";

    /** The one envelope: a row of the view as a JSON object keyed by column name. */
    public static final String ENVELOPE = "jobs-view-row";

    /** The column set documented in October 2026. */
    public static final String VERSION = "jobs-2026-10";

    /** Range the parser reads. */
    public static final VersionRange RANGE = VersionRange.named(VERSION);

    /** Parser id in {@code parser_version}. */
    public static final String PARSER_ID = "bqjobs";

    /** Parser semantic version. */
    public static final String PARSER_SEMVER = "1.0.0";

    /** {@code source_service} of every event. */
    public static final String SERVICE = "bigquery.googleapis.com";

    /** Provider resource kind of a table or view target. */
    public static final String TABLE_KIND = "bigquery_table";

    /** Provider resource kind of the job, a related resource. */
    public static final String JOB_KIND = "bigquery_job";

    /**
     * The location of the region the rows were read from. The view has no such column: the transport
     * adds it from the region qualifier it queried, because a job id is only unique with it
     * (log-event.md section 5.2). A row without it uses {@link #UNKNOWN_LOCATION}.
     */
    public static final String LOCATION_COLUMN = "location";

    /** Location used in a native key when a row carries none. */
    public static final String UNKNOWN_LOCATION = "_";

    /**
     * The retention of the view: 180 days of job history (GCP report section 4). Nothing older can be
     * read, so no query asks for more.
     */
    public static final Duration RETENTION = Duration.ofDays(180);

    /**
     * Columns every result set must have (coverage spec {@code jobs-2026-10}, {@code required_fields}).
     * A value may be null (a running job has no {@code end_time}); the column may not be missing.
     */
    public static final List<String> REQUIRED_COLUMNS = List.of(
            "job_id", "project_id", "creation_time", "end_time", "user_email", "job_type", "state",
            "referenced_tables");

    /**
     * Kept optional columns the parser's read and write decisions depend on (Q-396, D45). They are not
     * required, so a view that never had one still works, but a view that had one and loses it would
     * make the run miscount silently: a cache hit counted as a read ({@code cache_hit}), a failed job
     * as a success ({@code error_result}), a DML target as a read ({@code statement_type},
     * {@code destination_table}), a script child as a top-level job ({@code parent_job_id}). Losing a
     * previously present one is drift, like a missing required column.
     */
    public static final List<String> LOAD_BEARING_OPTIONAL_COLUMNS = List.of(
            "cache_hit", "destination_table", "error_result", "parent_job_id", "statement_type");

    /**
     * Columns the transport reads and the parser keeps in {@code raw_event}. The query text, job stages
     * and timeline are not on the list: they are never fetched and never stored.
     */
    public static final Set<String> KEPT_COLUMNS = Set.of(
            "job_id", "project_id", "project_number", "creation_time", "start_time", "end_time",
            "user_email", "principal_subject", "job_type", "statement_type", "state", "error_result",
            "cache_hit", "priority", "parent_job_id", "destination_table", "referenced_tables",
            "referenced_logical_views", "total_bytes_billed", "total_bytes_processed", "total_slot_ms",
            "labels", "reservation_id", "edition", "dml_statistics", "session_info", "transaction_id",
            LOCATION_COLUMN);

    /**
     * Documented columns that are never kept or fetched. {@code query} exposes every user's query text
     * (GCP report section 4); the other two are large and carry no read or write fact.
     */
    public static final Set<String> DROPPED_COLUMNS = Set.of("query", "job_stages", "timeline");

    /**
     * Documented columns the parser ignores without a warning: they are neither kept nor unknown. A
     * column in none of the three sets is reported as an unknown field.
     */
    public static final Set<String> IGNORED_COLUMNS = Set.of("continuous", "query_info", "transferred_bytes",
            "bi_engine_statistics", "total_modified_partitions", "materialized_view_statistics",
            "metadata_cache_statistics", "search_statistics", "vector_search_statistics", "query_dialect",
            "job_creation_reason", "reservation_usage", "resource_warning");

    private static final Pattern COLUMN_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private BqJobsFormat() {
    }

    /**
     * Whether a column name is safe to put into a generated query. BigQuery column names are letters,
     * digits and underscores; anything else is refused rather than quoted.
     *
     * @param name the column name
     * @return true for a plain identifier
     */
    public static boolean isPlainColumnName(String name) {
        return name != null && COLUMN_NAME.matcher(name).matches();
    }

    /**
     * The fingerprint of a result set's columns: SHA-256 of the sorted, lower-cased names joined by a
     * line feed, so it does not depend on column order or case.
     *
     * @param columns the column names of a result set
     * @return lowercase hex digest
     */
    public static String columnSetHash(Collection<String> columns) {
        TreeSet<String> sorted = new TreeSet<>();
        for (String c : columns) {
            sorted.add(c.toLowerCase(Locale.ROOT));
        }
        return LogEventIds.sha256Hex(String.join("\n", sorted));
    }

    /**
     * The required columns a result set lacks.
     *
     * @param columns the column names of a result set
     * @return the missing required columns in contract order; empty when the set is complete
     */
    public static List<String> missingRequired(Collection<String> columns) {
        Set<String> have = new TreeSet<>();
        for (String c : columns) {
            have.add(c.toLowerCase(Locale.ROOT));
        }
        List<String> missing = new ArrayList<>();
        for (String required : REQUIRED_COLUMNS) {
            if (!have.contains(required)) {
                missing.add(required);
            }
        }
        return List.copyOf(missing);
    }

    /**
     * The load-bearing optional columns a result set has.
     *
     * @param columns the column names of a result set
     * @return the present ones from {@link #LOAD_BEARING_OPTIONAL_COLUMNS}, in that order
     */
    public static List<String> presentLoadBearing(Collection<String> columns) {
        Set<String> have = new TreeSet<>();
        for (String c : columns) {
            have.add(c.toLowerCase(Locale.ROOT));
        }
        return LOAD_BEARING_OPTIONAL_COLUMNS.stream().filter(have::contains).toList();
    }

    /**
     * The load-bearing optional columns a previous run saw and this result set no longer has.
     *
     * @param previouslyPresent the columns {@link #presentLoadBearing} reported on the last good run
     * @param columns           the column names of this result set
     * @return the lost columns in contract order; empty when none was lost
     */
    public static List<String> lostLoadBearing(Collection<String> previouslyPresent, Collection<String> columns) {
        List<String> now = presentLoadBearing(columns);
        return LOAD_BEARING_OPTIONAL_COLUMNS.stream()
                .filter(c -> previouslyPresent.contains(c) && !now.contains(c))
                .toList();
    }

    /**
     * Whether a dataset is one of BigQuery's hidden anonymous datasets, which hold the temporary result
     * tables of queries. Their names start with an underscore (BigQuery documentation, "Anonymous
     * datasets").
     *
     * @param datasetId the dataset id
     * @return true for an anonymous dataset
     */
    public static boolean isAnonymousDataset(String datasetId) {
        return datasetId != null && datasetId.startsWith("_");
    }

    /**
     * Whether a dataset is an INFORMATION_SCHEMA view dataset, whose reads are not asset reads.
     *
     * @param datasetId the dataset id
     * @return true when the id contains INFORMATION_SCHEMA in any case
     */
    public static boolean isInformationSchema(String datasetId) {
        return datasetId != null && datasetId.toUpperCase(Locale.ROOT).contains("INFORMATION_SCHEMA");
    }
}
