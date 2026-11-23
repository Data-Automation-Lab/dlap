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
 * The {@code GCP_CLOUD_AUDIT} family (GCP-01): Cloud Audit Logs entries, a {@code LogEntry} whose
 * {@code protoPayload} is an {@code AuditLog}, including Cloud Storage and BigQuery entries in
 * both BigQuery formats.
 *
 * <p>Envelopes:
 * <ul>
 *   <li>{@code api-proto}: the entry from the Cloud Logging API client, printed to JSON by the
 *       transport (log-event spec section 6); legacy {@code serviceData} is {@code {typeUrl, base64}}.</li>
 *   <li>{@code json}: REST JSON, as {@code entries.list} over HTTPS returns it and as Cloud Storage
 *       sink shards hold it; {@code serviceData} is expanded with its {@code @type}.</li>
 *   <li>{@code bq-sink-row}: a row of a BigQuery Log Router sink table ({@code protopayload_auditlog},
 *       {@code metadataJson}, {@code servicedata_v1_bigquery}); the parser turns it into the
 *       {@code LogEntry} JSON view first ({@link com.dlap.logingest.parse.gcpaudit.BqSinkRows}).</li>
 * </ul>
 *
 * <p>Versions are structural, because an audit entry carries no version field: {@code auditlog},
 * {@code bq-auditmetadata} (BigQuery v2, {@code BigQueryAuditMetadata} in {@code metadata}) and
 * {@code bq-auditdata-legacy} (BigQuery v1, {@code AuditData} in {@code serviceData}). The fixture
 * corpus splits {@code auditlog} into the profiles {@code auditlog-2021} and {@code auditlog-2025}
 * ({@code authorizationInfo[].permissionType} absent or present); the parser reads both names, and
 * the detected and published version is always {@code auditlog}.
 *
 * <p>JSON only, no provider SDK: the legacy AuditData bytes of the {@code api-proto} form are read
 * with the protobuf runtime against a descriptor set shipped as a resource
 * ({@link com.dlap.logingest.parse.gcpaudit.AuditDataDecoder}).
 */
package com.dlap.logingest.parse.gcpaudit;
