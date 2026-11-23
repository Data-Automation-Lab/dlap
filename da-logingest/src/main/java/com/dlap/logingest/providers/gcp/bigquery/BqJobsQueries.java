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

package com.dlap.logingest.providers.gcp.bigquery;

import com.dlap.logingest.parse.bqjobs.BqJobsFormat;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The SQL of the transport. Everything that reaches the SQL text is validated first: the project and
 * the location go into back-quoted identifiers, and the column list comes from BigQuery's own schema
 * after an allow-list, so no caller-supplied text becomes SQL.
 */
public final class BqJobsQueries {

    /**
     * The longest a BigQuery job may run (BigQuery quotas: 6 hours for a query or load job). A job that
     * ended inside the window was created at most this long before it, which bounds the partition scan:
     * the view is partitioned by {@code creation_time}.
     */
    public static final Duration MAX_JOB_RUNTIME = Duration.ofHours(6);

    private static final Pattern PROJECT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern LOCATION = Pattern.compile("[A-Za-z]+(-[A-Za-z0-9]+)*");

    private BqJobsQueries() {
    }

    /**
     * Checks a project id before it becomes part of an identifier.
     *
     * @param project the project id, possibly domain-scoped ({@code example.com:project})
     * @return the project id
     * @throws IllegalArgumentException when it is not a plain project id
     */
    public static String requireProject(String project) {
        if (project == null || !PROJECT.matcher(project).matches()) {
            throw new IllegalArgumentException("not a BigQuery project id");
        }
        return project;
    }

    /**
     * Checks a BigQuery location ({@code US}, {@code EU}, {@code us-central1}).
     *
     * @param location the location
     * @return the location
     * @throws IllegalArgumentException when it is not a location name
     */
    public static String requireLocation(String location) {
        if (location == null || !LOCATION.matcher(location).matches()) {
            throw new IllegalArgumentException("not a BigQuery location");
        }
        return location;
    }

    /**
     * The fully qualified view: {@code `project`.`region-xx`.INFORMATION_SCHEMA.JOBS_BY_PROJECT}.
     *
     * @param project  the project whose jobs are read
     * @param location the location of the datasets and jobs
     * @return the view reference
     */
    public static String view(String project, String location) {
        return "`" + requireProject(project) + "`.`region-" + requireLocation(location).toLowerCase(Locale.ROOT)
                + "`.INFORMATION_SCHEMA.JOBS_BY_PROJECT";
    }

    /**
     * The query that tells the view's column set: a dry run of {@code SELECT *}. It reads nothing and
     * costs nothing; only the schema comes back.
     *
     * @param project  the project
     * @param location the location
     * @return the SQL
     */
    public static String columnsSql(String project, String location) {
        return "SELECT * FROM " + view(project, location);
    }

    /**
     * The query for finished jobs that ended in {@code [@from, @to)}, oldest first. Named parameters:
     * {@code from} and {@code to} (the end-time window) and {@code creation_floor} (the partition
     * bound).
     *
     * <p>Rows that have children (the parent of a script or a procedure call) are left out, as in the
     * zero-access scan: the children carry the reads. Only the allow-listed columns are selected, so
     * the query text, job stages and timeline are never read, which also keeps them out of the bill.
     *
     * @param project  the project whose jobs are read
     * @param location the location
     * @param columns  the columns to select, already filtered to {@link BqJobsFormat#KEPT_COLUMNS}
     * @return the SQL
     * @throws IllegalArgumentException when a column name is not a plain identifier, or none is given
     */
    public static String rowsSql(String project, String location, List<String> columns) {
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("no columns to select");
        }
        StringBuilder select = new StringBuilder();
        for (String c : columns) {
            if (!BqJobsFormat.isPlainColumnName(c)) {
                throw new IllegalArgumentException("not a plain column name");
            }
            if (select.length() > 0) {
                select.append(", ");
            }
            select.append("j.").append(c);
        }
        String view = view(project, location);
        return "SELECT " + select + "\n"
                + "FROM " + view + " AS j\n"
                + "WHERE j.creation_time >= @creation_floor\n"
                + "  AND j.end_time >= @from AND j.end_time < @to\n"
                + "  AND j.state = 'DONE'\n"
                + "  AND NOT EXISTS (SELECT 1 FROM " + view + " AS c\n"
                + "                  WHERE c.creation_time >= @creation_floor AND c.parent_job_id = j.job_id)\n"
                + "ORDER BY j.end_time, j.job_id";
    }
}
