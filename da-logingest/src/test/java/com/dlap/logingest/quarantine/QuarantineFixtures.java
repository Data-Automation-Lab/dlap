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

package com.dlap.logingest.quarantine;

import com.dlap.common.logevent.LogEventIds;
import com.dlap.proto.log.v1.LogQuarantineRecord;
import com.dlap.proto.log.v1.QuarantineReason;
import com.dlap.proto.log.v1.RawRecordRef;
import com.dlap.proto.log.v1.SourceProvider;
import com.google.protobuf.Timestamp;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.JdbcTemplate;

/** Test inputs of the quarantine tests: quarantine records as the mapper builds them, and binding rows. */
public final class QuarantineFixtures {

    /** The source kind of the records. */
    public static final String KIND = "DLAP_NORMALIZED_AWS";

    /** The envelope of the records. */
    public static final String ENVELOPE = "ingest-dto";

    private QuarantineFixtures() {
    }

    /**
     * A quarantine record whose excerpt is the whole record, so its hash and size match.
     *
     * @param bindingId the binding
     * @param text      the raw record text
     * @param reason    the reason
     * @param index     the record index in the container
     * @param at        when it was quarantined
     * @return the record
     */
    public static LogQuarantineRecord exact(String bindingId, String text, QuarantineReason reason, long index,
                                            Instant at) {
        RawRecordRef ref = RawRecordRef.newBuilder().setTransport("REST").setUri("rest:/api/v1/logingest/events")
                .setRecordIndex(index).build();
        String sha = LogEventIds.sha256Hex(text);
        return LogQuarantineRecord.newBuilder()
                .setQuarantineId(LogEventIds.quarantineId(bindingId, ref, sha))
                .setBindingId(bindingId)
                .setSourceProvider(SourceProvider.AWS)
                .setSourceKind(KIND)
                .setEnvelope(ENVELOPE)
                .setDetectedVersion("v1")
                .setParserVersion("dlap-normalized@0.9.0")
                .setReason(reason)
                .setReasonDetail("changeType is not a known value")
                .setRawRef(ref)
                .setRawExcerpt(text)
                .setRawTruncated(false)
                .setRawSha256(sha)
                .setRawSizeBytes(text.getBytes(StandardCharsets.UTF_8).length)
                .setQuarantinedTime(Timestamp.newBuilder().setSeconds(at.getEpochSecond()))
                .setSchemaVersion("v1")
                .build();
    }

    /**
     * Inserts a binding row, because {@code quarantine_record} references {@code source_binding}.
     *
     * @param jdbc      the template
     * @param bindingId the binding
     */
    public static void insertBinding(JdbcTemplate jdbc, String bindingId) {
        OffsetDateTime now = OffsetDateTime.of(2026, 10, 20, 12, 0, 0, 0, ZoneOffset.UTC);
        jdbc.update("INSERT INTO dlap_logingest.source_binding (binding_id, source_id, source_kind, config_origin, "
                        + "state, enabled, config_hash, first_synced_at, last_synced_at) "
                        + "VALUES (?, ?, ?, 'FILE', 'ACTIVE', true, ?, ?, ?) ON CONFLICT (binding_id) DO NOTHING",
                bindingId, "test-source", KIND, "0".repeat(64), now, now);
    }
}
