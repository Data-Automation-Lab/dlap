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

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What the transport needs from BigQuery: the column set of the JOBS view, and rows. The production
 * implementation is {@link BigQueryJobsViewGateway}; tests substitute a fake.
 */
public interface JobsViewGateway {

    /**
     * One query to run.
     *
     * @param billingProject the project the query job runs in (bills and needs {@code bigquery.jobs.create})
     * @param location       the job location, which must be the region of the qualifier in the SQL
     * @param sql            standard SQL; parameters are named
     * @param timestamps     named {@code TIMESTAMP} parameters
     */
    record Query(String billingProject, String location, String sql, Map<String, Instant> timestamps) {

        /**
         * Validates the query.
         *
         * @param billingProject see the record description
         * @param location       see the record description
         * @param sql            see the record description
         * @param timestamps     see the record description
         */
        public Query {
            Objects.requireNonNull(billingProject, "billingProject");
            Objects.requireNonNull(location, "location");
            Objects.requireNonNull(sql, "sql");
            timestamps = timestamps == null ? Map.of() : Map.copyOf(timestamps);
        }
    }

    /**
     * The column names a query would return, without running it and without cost (a dry run).
     *
     * @param query the query
     * @return the result column names in result order
     * @throws ViewFailure when BigQuery refuses the query
     */
    List<String> columns(Query query);

    /**
     * Runs a query and streams its rows, one JSON object per row keyed by column name. Timestamps are
     * RFC 3339 text in UTC with the fraction BigQuery holds; records and arrays are nested; nulls are
     * JSON nulls, so a missing value and a missing column can be told apart.
     *
     * @param query the query
     * @return the rows; fetched lazily, page by page
     * @throws ViewFailure when BigQuery refuses the query
     */
    Iterator<ObjectNode> rows(Query query);

    /** BigQuery refused a query. The message never contains the SQL or a row. */
    final class ViewFailure extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /** Why the query was refused. */
        public enum Kind {
            /** The location does not match the region qualifier of the query. */
            LOCATION_MISMATCH,
            /** The caller lacks a permission ({@code bigquery.jobs.listAll}, {@code bigquery.jobs.create}). */
            PERMISSION_DENIED,
            /** Anything else: quota, an outage, a malformed request. */
            OTHER
        }

        private final Kind kind;

        /**
         * Creates the failure.
         *
         * @param kind    why the query was refused
         * @param message a message without SQL or data
         * @param cause   the SDK exception, or {@code null}
         */
        public ViewFailure(Kind kind, String message, Throwable cause) {
            super(message, cause);
            this.kind = Objects.requireNonNull(kind, "kind");
        }

        /**
         * Why the query was refused.
         *
         * @return the kind
         */
        public Kind kind() {
            return kind;
        }
    }
}
