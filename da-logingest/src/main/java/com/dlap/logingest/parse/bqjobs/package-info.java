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

/**
 * The {@code GCP_BQ_JOBS_VIEW} family (GCP-02): rows of BigQuery
 * {@code INFORMATION_SCHEMA.JOBS_BY_PROJECT} as read and write history of tables.
 *
 * <p>The view carries no version field, so a version is the documented column set
 * ({@link com.dlap.logingest.parse.bqjobs.BqJobsFormat}). One row is one finished job and yields one
 * {@code DATA_READ} event per table it read and one write event for the table it wrote. Query text
 * never enters an event or a raw record.
 *
 * <p>This package is JSON only (plan rule R10). The BigQuery client that produces the rows lives in
 * {@code providers/gcp/bigquery}.
 */
package com.dlap.logingest.parse.bqjobs;
