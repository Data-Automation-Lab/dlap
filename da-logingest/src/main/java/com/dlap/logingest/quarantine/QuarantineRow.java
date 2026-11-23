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
import com.fasterxml.jackson.annotation.JsonProperty;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

/**
 * One row of {@code dlap_logingest.quarantine_record}, as the API returns it. The excerpt is the
 * stored, masked and bounded text; the raw record itself is never kept (design B3).
 *
 * @param quarantineId      the record's id, {@code SHA-256(binding|uri|index|raw sha)}
 * @param bindingId         the binding
 * @param sourceProvider    the provider name
 * @param sourceKind        the source kind
 * @param envelope          the envelope
 * @param detectedVersion   the version detected on the record, empty when none
 * @param pinnedVersion     the version the binding pins, empty for auto
 * @param parserVersion     {@code <parser-id>@<semver>} that quarantined it
 * @param reason            the reason code, for example {@code UNKNOWN_MAJOR_VERSION}
 * @param reasonDetail      the detail, at most 1024 bytes, never a value
 * @param rawRef            where the record lives at its source: transport, uri, recordIndex, cursor
 * @param rawSha256         SHA-256 of the complete raw bytes
 * @param rawSizeBytes      size of the complete raw bytes
 * @param rawExcerpt        leading part of the record, masked and bounded
 * @param rawTruncated      true when the excerpt is shorter than the record
 * @param eventTime         the provider event time when it could be extracted
 * @param nativeEventId     the native idempotency key when it could be extracted
 * @param passThroughEventId the event id of the published pass-through event, empty when none
 * @param quarantinedAt     when DLAP first quarantined the record
 * @param replayedAt        when a replay resolved it, {@code null} while it is pending
 * @param replayCount       how many replays tried it
 */
public record QuarantineRow(
        String quarantineId,
        String bindingId,
        String sourceProvider,
        String sourceKind,
        String envelope,
        String detectedVersion,
        String pinnedVersion,
        String parserVersion,
        String reason,
        String reasonDetail,
        Map<String, Object> rawRef,
        String rawSha256,
        long rawSizeBytes,
        String rawExcerpt,
        boolean rawTruncated,
        Instant eventTime,
        String nativeEventId,
        String passThroughEventId,
        Instant quarantinedAt,
        Instant replayedAt,
        int replayCount) {

    /** Why a row cannot be replayed from the table. */
    public enum NotReplayable {
        /** The excerpt is shorter than the record. */
        TRUNCATED,
        /** The excerpt differs from the original bytes: an email or address was masked, or the bytes were not UTF-8. */
        ALTERED
    }

    /**
     * Whether the stored excerpt is exactly the original record, so a replay re-runs the real bytes.
     * True only when nothing was cut or masked: the excerpt's SHA-256 and size equal the recorded
     * ones. A masked copy is never replayed, because it would store masked identities as real ones.
     *
     * @return null when replayable, else the reason it is not
     */
    @JsonProperty("notReplayableReason")
    public NotReplayable notReplayableReason() {
        if (rawTruncated) {
            return NotReplayable.TRUNCATED;
        }
        byte[] bytes = rawExcerpt.getBytes(StandardCharsets.UTF_8);
        if (bytes.length != rawSizeBytes || !LogEventIds.sha256Hex(rawExcerpt).equals(rawSha256)) {
            return NotReplayable.ALTERED;
        }
        return null;
    }

    /**
     * Whether a replay can re-run this row.
     *
     * @return true when {@link #notReplayableReason()} is null
     */
    @JsonProperty("replayable")
    public boolean replayable() {
        return notReplayableReason() == null;
    }
}
