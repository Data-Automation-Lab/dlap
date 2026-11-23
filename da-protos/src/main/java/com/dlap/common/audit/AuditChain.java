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

package com.dlap.common.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The hash chain and the record form (docs/specs/audit-log.md, sections "Record" and
 * "Hash chain"). Pure functions; the writer, the verifier and the tests share them.
 */
public final class AuditChain {

    /** {@code prev_hash} of the first entry of every stream: 64 zeros. */
    public static final String GENESIS_HASH = "0".repeat(64);

    /** The first line of every hash input. */
    public static final String HASH_INPUT_PREFIX = "DLAP-AUDIT-1";

    /** The {@code schema} member of every record. */
    public static final String RECORD_SCHEMA = "dlap-audit-record/1";

    /** Maximum length of a record. */
    public static final int MAX_RECORD_LENGTH = 32_768;

    /** Instants in records: UTC, always six fraction digits, so the text sorts like the time. */
    public static final DateTimeFormatter INSTANT_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);

    private static final Pattern HASH = Pattern.compile("^[0-9a-f]{64}$");

    private AuditChain() {
    }

    /**
     * The canonical record of an event.
     *
     * @param event      the event
     * @param recordedAt the writer's clock when the record is written
     * @return canonical JSON, printable ASCII
     * @throws IllegalArgumentException when the record would exceed {@link #MAX_RECORD_LENGTH}
     */
    public static String record(AuditEvent event, Instant recordedAt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema", RECORD_SCHEMA);
        m.put("eventType", event.eventType());
        m.put("occurredAt", formatInstant(event.occurredAt()));
        m.put("recordedAt", formatInstant(recordedAt));
        m.put("actor", Map.of("kind", event.actor().kind().name(), "id", event.actor().id()));
        if (event.target() != null) {
            m.put("target", Map.of("kind", event.target().kind(), "id", event.target().id()));
        }
        m.put("outcome", event.outcome().name());
        if (event.correlationId() != null) {
            m.put("correlationId", event.correlationId());
        }
        if (event.idempotencyKey() != null) {
            m.put("idempotencyKey", event.idempotencyKey());
        }
        m.put("details", event.details());
        String json = CanonicalJson.write(m);
        if (json.length() > MAX_RECORD_LENGTH) {
            throw new IllegalArgumentException("audit record has " + json.length()
                    + " characters; the limit is " + MAX_RECORD_LENGTH);
        }
        return json;
    }

    /**
     * Formats an instant the way records and checkpoints carry it, truncated to microseconds.
     *
     * @param instant the instant
     * @return for example {@code 2026-10-21T08:15:30.000000Z}
     */
    public static String formatInstant(Instant instant) {
        return INSTANT_FORMAT.format(instant.truncatedTo(ChronoUnit.MICROS));
    }

    /**
     * The text that is hashed: {@code DLAP-AUDIT-1} LF stream id LF seq LF prev hash LF record.
     *
     * @param streamId the stream
     * @param seq      the position, from 1
     * @param prevHash the previous entry hash, or {@link #GENESIS_HASH}
     * @param record   the record exactly as stored
     * @return the hash input
     */
    public static String hashInput(String streamId, long seq, String prevHash, String record) {
        return HASH_INPUT_PREFIX + '\n' + streamId + '\n' + seq + '\n' + prevHash + '\n' + record;
    }

    /**
     * The entry hash: SHA-256 of {@link #hashInput} as UTF-8, lower-case hex.
     *
     * @param streamId the stream
     * @param seq      the position
     * @param prevHash the previous entry hash
     * @param record   the record exactly as stored
     * @return 64 hex characters
     */
    public static String entryHash(String streamId, long seq, String prevHash, String record) {
        return sha256Hex(hashInput(streamId, seq, prevHash, record));
    }

    /**
     * SHA-256 of a text as UTF-8, lower-case hex.
     *
     * @param text the text
     * @return 64 hex characters
     */
    public static String sha256Hex(String text) {
        return HexFormat.of().formatHex(sha256(text.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * SHA-256 of bytes.
     *
     * @param bytes the input
     * @return the 32-byte digest
     */
    public static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }

    /**
     * Whether a value is a lower-case hex SHA-256.
     *
     * @param value the candidate
     * @return true for 64 characters of {@code [0-9a-f]}
     */
    public static boolean isHash(String value) {
        return value != null && HASH.matcher(value).matches();
    }
}
