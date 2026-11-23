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
 * The BigQuery transport of the {@code GCP_BQ_JOBS_VIEW} family (GCP-02): it reads finished jobs from
 * {@code INFORMATION_SCHEMA.JOBS_BY_PROJECT} and hands them to the parser as JSON rows.
 *
 * <p>Only {@code BigQueryJobsViewGateway} and {@code BigQueryRowJson} touch the BigQuery SDK; the
 * transport itself ({@code BqJobsViewTransport}) works on the {@code JobsViewGateway} interface, so
 * its windowing, drift detection and cursor handling are tested without a network.
 */
package com.dlap.logingest.providers.gcp.bigquery;
