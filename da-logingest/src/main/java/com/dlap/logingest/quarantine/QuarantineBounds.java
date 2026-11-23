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

import com.dlap.logingest.model.NulSafe;
import com.dlap.logingest.parse.core.ParseResult;
import com.dlap.logingest.parse.core.RawExcerpts;
import com.dlap.proto.log.v1.LogQuarantineRecord;
import com.dlap.proto.log.v1.QuarantineReason;
import com.dlap.proto.log.v1.RawRecordRef;
import java.nio.charset.StandardCharsets;

/**
 * The limits a sink enforces on a quarantine record before it stores or publishes it. The mapper
 * already bounds and masks the excerpt ({@code CanonicalEventMapper#quarantine}); a sink does not
 * trust that, because a record can reach a sink from another producer and the rule (log-event
 * spec section 9, CLAUDE.md section 11) must hold where the data leaves the service.
 *
 * <p>Guarantees of {@link #bounded}: U+0000 is removed from the excerpt, the detail, the native id and
 * the raw reference (PostgreSQL text and jsonb refuse it, so one such record would stop its partition at
 * every poll; Q-661, the one place both sinks pass through); the excerpt is masked again (masking is idempotent), cut at a
 * character boundary to at most the configured bytes and flagged truncated when anything was cut;
 * {@code reason_detail} is at most 1024 bytes; {@code schema_version} is {@code v1}.
 */
public final class QuarantineBounds {

    private QuarantineBounds() {
    }

    /**
     * Applies the limits.
     *
     * @param record      the record as the mapper built it
     * @param maxExcerpt  the most excerpt bytes to keep
     * @return the record with a masked, bounded excerpt and a bounded detail
     */
    public static LogQuarantineRecord bounded(LogQuarantineRecord record, int maxExcerpt) {
        String masked = RawExcerpts.redact(NulSafe.strip(record.getRawExcerpt()));
        String cut = cutUtf8(masked, maxExcerpt);
        boolean truncated = record.getRawTruncated() || cut.length() < masked.length();
        RawRecordRef ref = record.getRawRef();
        return record.toBuilder()
                .setRawExcerpt(cut)
                .setRawTruncated(truncated)
                .setReasonDetail(ParseResult.boundDetail(NulSafe.strip(record.getReasonDetail())))
                .setNativeEventId(NulSafe.strip(record.getNativeEventId()))
                .setRawRef(ref.toBuilder()
                        .setTransport(NulSafe.strip(ref.getTransport()))
                        .setUri(NulSafe.strip(ref.getUri()))
                        .setCursor(NulSafe.strip(ref.getCursor())))
                .setSchemaVersion("v1")
                .build();
    }

    /**
     * The reason without the proto prefix, as the outcome code and the metric label spell it.
     *
     * @param reason the proto reason
     * @return for example {@code UNKNOWN_MAJOR_VERSION}; {@code UNSPECIFIED} for an unusable value
     */
    public static String reasonCode(QuarantineReason reason) {
        if (reason == null || reason == QuarantineReason.QUARANTINE_REASON_UNSPECIFIED
                || reason == QuarantineReason.UNRECOGNIZED) {
            return "UNSPECIFIED";
        }
        return reason.name().substring(ParseResult.Quarantined.REASON_PREFIX.length());
    }

    /**
     * Parses a reason code.
     *
     * @param code a code as {@link #reasonCode} spells it, with or without the proto prefix
     * @return the reason
     * @throws IllegalArgumentException when the code names no reason
     */
    public static QuarantineReason parseReason(String code) {
        String name = code.startsWith(ParseResult.Quarantined.REASON_PREFIX) ? code
                : ParseResult.Quarantined.REASON_PREFIX + code;
        try {
            QuarantineReason r = QuarantineReason.valueOf(name);
            if (r == QuarantineReason.QUARANTINE_REASON_UNSPECIFIED || r == QuarantineReason.UNRECOGNIZED) {
                throw new IllegalArgumentException("unknown quarantine reason");
            }
            return r;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown quarantine reason: " + code);
        }
    }

    /**
     * Cuts a string to at most a number of UTF-8 bytes without splitting a character.
     *
     * @param text     the text
     * @param maxBytes the limit
     * @return the longest prefix that fits
     */
    static String cutUtf8(String text, int maxBytes) {
        if (text.getBytes(StandardCharsets.UTF_8).length <= maxBytes) {
            return text;
        }
        StringBuilder sb = new StringBuilder();
        int used = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int len = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
            if (used + len > maxBytes) {
                break;
            }
            sb.appendCodePoint(cp);
            used += len;
            i += Character.charCount(cp);
        }
        return sb.toString();
    }
}
