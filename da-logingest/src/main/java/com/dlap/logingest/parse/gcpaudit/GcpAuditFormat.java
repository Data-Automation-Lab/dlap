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

package com.dlap.logingest.parse.gcpaudit;

import com.dlap.logingest.parse.core.EnvelopeKey;
import com.dlap.logingest.parse.core.VersionRange;
import java.util.List;

/**
 * Names of the {@code GCP_CLOUD_AUDIT} family: source kind, envelopes, versions and the type URLs
 * that decide the version of an entry.
 */
public final class GcpAuditFormat {

    /** The fixture and mapping family id. */
    public static final String FAMILY = "gcpaudit";

    /** The source kind (log-event spec section 5.2). */
    public static final String SOURCE_KIND = "GCP_CLOUD_AUDIT";

    /** The entry from the Cloud Logging API client, printed to JSON by the transport. */
    public static final String API_PROTO = "api-proto";

    /** REST JSON: {@code entries.list} over HTTPS and Cloud Storage sink shards. */
    public static final String JSON = "json";

    /** A row of a BigQuery Log Router sink table. */
    public static final String BQ_SINK_ROW = "bq-sink-row";

    /** Every envelope of the family. */
    public static final List<String> ENVELOPES = List.of(API_PROTO, JSON, BQ_SINK_ROW);

    /** A plain AuditLog entry (Cloud Storage and every other service). */
    public static final String AUDITLOG = "auditlog";

    /** Fixture profile: AuditLog without {@code authorizationInfo[].permissionType}. */
    public static final String AUDITLOG_2021 = "auditlog-2021";

    /** Fixture profile: AuditLog with {@code authorizationInfo[].permissionType} (proto change of 2025-03-06). */
    public static final String AUDITLOG_2025 = "auditlog-2025";

    /** BigQuery v2: {@code BigQueryAuditMetadata} in {@code protoPayload.metadata}. */
    public static final String BQ_AUDIT_METADATA = "bq-auditmetadata";

    /** BigQuery v1: {@code AuditData} in {@code protoPayload.serviceData}. */
    public static final String BQ_AUDIT_DATA_LEGACY = "bq-auditdata-legacy";

    /** {@code @type} of an AuditLog payload. */
    public static final String AUDIT_LOG_TYPE = "type.googleapis.com/google.cloud.audit.AuditLog";

    /** {@code @type} of BigQuery v2 metadata. */
    public static final String BQ_AUDIT_METADATA_TYPE = "type.googleapis.com/google.cloud.audit.BigQueryAuditMetadata";

    /** {@code @type} of BigQuery v1 service data. */
    public static final String BQ_AUDIT_DATA_TYPE = "type.googleapis.com/google.cloud.bigquery.logging.v1.AuditData";

    /** {@code @type} of the IAM policy delta some services put in service data. */
    public static final String IAM_AUDIT_DATA_TYPE = "type.googleapis.com/google.iam.v1.logging.AuditData";

    /** {@code @type} of VPC Service Controls denial metadata. */
    public static final String VPC_SC_METADATA_TYPE =
            "type.googleapis.com/google.cloud.audit.VpcServiceControlAuditMetadata";

    /** The Cloud Storage service name. */
    public static final String STORAGE = "storage.googleapis.com";

    /** The BigQuery service name. */
    public static final String BIGQUERY = "bigquery.googleapis.com";

    /** Parser id of the family's three parsers, one per envelope. */
    public static final String PARSER_ID_PREFIX = "gcpaudit-";

    /** Parser version. Bump the minor version when output changes for some input. */
    public static final String PARSER_SEMVER = "1.0.0";

    private GcpAuditFormat() {
    }

    /**
     * The versions every parser of the family reads. {@code auditlog-2021} and {@code auditlog-2025}
     * are the fixture profiles of {@code auditlog}: a binding may name them, but records are always
     * detected as {@code auditlog}, so pin {@code auditlog} instead.
     *
     * @return the named range
     */
    public static VersionRange supported() {
        return VersionRange.named(AUDITLOG, AUDITLOG_2021, AUDITLOG_2025, BQ_AUDIT_METADATA, BQ_AUDIT_DATA_LEGACY);
    }

    /**
     * The version a parser treats a record as, with the fixture profiles folded into {@code auditlog}.
     *
     * @param effectiveVersion the effective version the registry hands the parser
     * @return {@code auditlog}, {@code bq-auditmetadata} or {@code bq-auditdata-legacy}
     */
    public static String schemaOf(String effectiveVersion) {
        if (AUDITLOG_2021.equals(effectiveVersion) || AUDITLOG_2025.equals(effectiveVersion)) {
            return AUDITLOG;
        }
        return effectiveVersion;
    }

    /**
     * The registry key of one envelope.
     *
     * @param envelope the envelope id
     * @return the key
     */
    public static EnvelopeKey key(String envelope) {
        return new EnvelopeKey(SOURCE_KIND, envelope);
    }
}
