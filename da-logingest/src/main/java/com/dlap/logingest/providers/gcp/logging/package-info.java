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
 * The GCP Cloud Audit Logs transports (GCP-01): {@code LOGGING_API} (Cloud Logging
 * {@code entries.list}, envelope {@code api-proto}), {@code GCS_SINK} (Cloud Storage sink shards,
 * envelope {@code json}) and {@code BQ_SINK} (BigQuery sink tables, envelope {@code bq-sink-row}).
 * The only package of da-logingest besides the legacy source that uses the Google Cloud client
 * libraries; transports never parse, store or publish (the stream runner does). No Pub/Sub transport
 * (ADR-0023, decision D6).
 */
package com.dlap.logingest.providers.gcp.logging;
