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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/**
 * The record a transport hands over instead of rows when a whole run cannot be read: the result set
 * lost a required column or a load-bearing optional one that the last run saw, or BigQuery refused the query for its location. It has no job id, so the
 * registry quarantines it as an unknown envelope; the quarantine record, whose excerpt is this
 * object, is how the operator learns of the binding problem. The marker holds column names, hashes
 * and a location, never a row, a query or a provider message.
 *
 * <p>It is stable for as long as the problem is: the same problem gives the same bytes, so repeated
 * polls produce one quarantine id, not a new one each time.
 */
public final class BqJobsRunProblem {

    /** The one top-level key of a marker record. */
    public static final String KEY = "dlap_run_problem";

    /** Why a run could not be read. */
    public enum Kind {
        /** A required column is missing from the result set: the view's column set changed. */
        COLUMN_DRIFT,
        /**
         * A kept optional column that the previous run saw is gone from the result set (cache_hit,
         * statement_type, error_result, destination_table, parent_job_id): reading on would miscount.
         */
        COLUMN_LOST,
        /** BigQuery refused the query because the location does not match the region qualifier. */
        REGION_MISMATCH
    }

    private BqJobsRunProblem() {
    }

    /**
     * The marker of a column-set drift.
     *
     * @param location     the region qualifier that was queried
     * @param missing      the required columns the result set lacks
     * @param observedHash {@link BqJobsFormat#columnSetHash} of the result set
     * @param expectedHash the hash recorded by the previous run, or {@code null} for a first run
     * @return the marker object
     */
    public static ObjectNode columnDrift(String location, List<String> missing, String observedHash, String expectedHash) {
        ObjectNode problem = JsonNodeFactory.instance.objectNode();
        problem.put("kind", Kind.COLUMN_DRIFT.name());
        ArrayNode columns = problem.putArray("missing");
        missing.forEach(columns::add);
        problem.put("column_set_hash", observedHash);
        if (expectedHash != null) {
            problem.put("expected_column_set_hash", expectedHash);
        }
        problem.put(BqJobsFormat.LOCATION_COLUMN, location);
        return wrap(problem);
    }

    /**
     * The marker of a lost load-bearing optional column (Q-396). Same shape as the drift marker with
     * {@code lost} in place of {@code missing}.
     *
     * @param location     the region qualifier that was queried
     * @param lost         the columns the previous run saw and this result set lacks
     * @param observedHash {@link BqJobsFormat#columnSetHash} of the result set
     * @param expectedHash the hash recorded by the previous run, or {@code null} when none was recorded
     * @return the marker object
     */
    public static ObjectNode columnLost(String location, List<String> lost, String observedHash, String expectedHash) {
        ObjectNode problem = JsonNodeFactory.instance.objectNode();
        problem.put("kind", Kind.COLUMN_LOST.name());
        ArrayNode columns = problem.putArray("lost");
        lost.forEach(columns::add);
        problem.put("column_set_hash", observedHash);
        if (expectedHash != null) {
            problem.put("expected_column_set_hash", expectedHash);
        }
        problem.put(BqJobsFormat.LOCATION_COLUMN, location);
        return wrap(problem);
    }

    /**
     * The marker of a location that does not match the region qualifier.
     *
     * @param location the location that was used
     * @return the marker object
     */
    public static ObjectNode regionMismatch(String location) {
        ObjectNode problem = JsonNodeFactory.instance.objectNode();
        problem.put("kind", Kind.REGION_MISMATCH.name());
        problem.put(BqJobsFormat.LOCATION_COLUMN, location);
        problem.put("detail", "the query location does not match the region qualifier");
        return wrap(problem);
    }

    /**
     * Whether a record body is a marker.
     *
     * @param body the decoded record
     * @return true when it holds {@link #KEY}
     */
    public static boolean isMarker(JsonNode body) {
        return body != null && body.isObject() && body.has(KEY);
    }

    private static ObjectNode wrap(ObjectNode problem) {
        ObjectNode marker = JsonNodeFactory.instance.objectNode();
        marker.set(KEY, problem);
        return marker;
    }
}
