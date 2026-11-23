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
import com.dlap.logingest.parse.core.FormatKey;
import com.dlap.logingest.parse.core.RawRecord;
import com.dlap.logingest.parse.core.VersionDetector;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;

/**
 * Detects the version of a BigQuery sink row. It is code, not a mapping-YAML rule, because the
 * BigQuery v2 type sits inside the {@code metadataJson} string column, which a declarative
 * condition cannot read. It applies the rules the {@code json} envelope's YAML applies, to the
 * row's LogEntry view ({@link BqSinkRows}). A row without an AuditLog payload, such as a row of the
 * sink's {@code export_errors} table, is not a record of the envelope, so the registry quarantines
 * it as {@code UNKNOWN_ENVELOPE}: the export-error signal reaches the quarantine, never the events.
 */
public final class BqSinkRowDetector implements VersionDetector {

    private static final EnvelopeKey KEY = GcpAuditFormat.key(GcpAuditFormat.BQ_SINK_ROW);

    @Override
    public EnvelopeKey envelopeKey() {
        return KEY;
    }

    @Override
    public Optional<FormatKey> detect(RawRecord record) {
        RawRecord.Decoded decoded = record.decode();
        if (!decoded.ok()) {
            return Optional.empty();
        }
        BqSinkRows.View view = BqSinkRows.toEntry(decoded.body());
        if (!view.ok()) {
            return Optional.empty();
        }
        return version(view.entry()).map(KEY::at);
    }

    /**
     * The structural version of a LogEntry: BigQuery v2 metadata, BigQuery v1 service data, else a
     * plain AuditLog.
     *
     * @param entry the LogEntry JSON
     * @return the version, or empty when the payload is not an AuditLog
     */
    static Optional<String> version(JsonNode entry) {
        JsonNode payload = entry.path("protoPayload");
        if (!payload.isObject()) {
            return Optional.empty();
        }
        if (GcpAuditFormat.BQ_AUDIT_METADATA_TYPE.equals(GcpJson.textOr(payload, "metadata", "@type"))) {
            return Optional.of(GcpAuditFormat.BQ_AUDIT_METADATA);
        }
        if (GcpAuditFormat.BQ_AUDIT_DATA_TYPE.equals(GcpJson.textOr(payload, "serviceData", "@type"))) {
            return Optional.of(GcpAuditFormat.BQ_AUDIT_DATA_LEGACY);
        }
        String type = GcpJson.textOr(payload, "@type");
        if (type.isEmpty() || GcpAuditFormat.AUDIT_LOG_TYPE.equals(type)) {
            return Optional.of(GcpAuditFormat.AUDITLOG);
        }
        return Optional.empty();
    }
}
