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

package com.dlap.common.logevent;

import com.dlap.proto.log.v1.DlapLogEvent;
import com.dlap.proto.log.v1.RawRecordRef;
import com.dlap.proto.log.v1.SourceProvider;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Idempotency keys of the log contract (ADR-0045, spec section
 * "Idempotency"). One implementation, so producers in every module hash the
 * same bytes.
 *
 * <p>{@code event_id = hex(SHA-256(UTF-8("<SOURCE_PROVIDER>|<native key>")))},
 * lowercase. This is byte-for-byte the formula da-logingest has used since
 * v0.1 ({@code LogIngestServiceImpl.computeEventId} with a non-blank
 * {@code requestId}), so events stored by the legacy pipeline keep their ids
 * when the parser registry takes over.
 */
public final class LogEventIds {

    /** Separator between the parts of a native key and of the hash input. */
    public static final String SEPARATOR = "|";

    /** Separator between a record's native key and a fan-out event's target. */
    public static final String FAN_OUT_SEPARATOR = "#";

    private static final List<String> LOG_NAME_PARENTS =
            List.of("projects/", "organizations/", "folders/", "billingAccounts/");

    private LogEventIds() {
    }

    /**
     * Computes an event id.
     *
     * @param provider  the source provider; must not be unspecified
     * @param nativeKey the native key for the source kind
     * @return lowercase hex SHA-256
     */
    public static String eventId(SourceProvider provider, String nativeKey) {
        Objects.requireNonNull(provider, "provider");
        if (provider == SourceProvider.SOURCE_PROVIDER_UNSPECIFIED || provider == SourceProvider.UNRECOGNIZED) {
            throw new IllegalArgumentException("event ids need a known source provider");
        }
        return eventId(provider.name(), nativeKey);
    }

    /**
     * Computes an event id from a provider name, as the REST ingest path
     * receives it.
     *
     * @param providerName the provider enum name, for example {@code "AWS"}
     * @param nativeKey    the native key for the source kind
     * @return lowercase hex SHA-256
     */
    public static String eventId(String providerName, String nativeKey) {
        requireText(providerName, "providerName");
        requireText(nativeKey, "nativeKey");
        return sha256Hex(providerName + SEPARATOR + nativeKey);
    }

    /**
     * Native key of a CloudTrail record in any envelope: its {@code eventID},
     * unchanged.
     *
     * @param eventId the record's {@code eventID}
     * @return the native key
     */
    public static String cloudTrailNativeKey(String eventId) {
        requireText(eventId, "eventID");
        return eventId;
    }

    /**
     * Native key of a GCP Cloud Audit Logs entry in any envelope:
     * {@code logName|timestamp|insertId}.
     *
     * @param logName   the full log name as in LogEntry JSON, for example
     *                  {@code projects/p/logs/cloudaudit.googleapis.com%2Fdata_access}
     * @param timestamp the entry's {@code timestamp}
     * @param insertId  the entry's {@code insertId}
     * @return the native key; the timestamp is rendered by {@link Instant#toString()}
     *         (UTC, 0, 3, 6 or 9 fraction digits)
     * @throws IllegalArgumentException when the log name is not a full resource name
     */
    public static String gcpAuditNativeKey(String logName, Instant timestamp, String insertId) {
        requireText(logName, "logName");
        Objects.requireNonNull(timestamp, "timestamp");
        requireText(insertId, "insertId");
        boolean full = LOG_NAME_PARENTS.stream().anyMatch(logName::startsWith) && logName.contains("/logs/");
        if (!full) {
            throw new IllegalArgumentException(
                    "logName must be the full resource name (projects/{id}/logs/{url-encoded log id})");
        }
        return logName + SEPARATOR + timestamp + SEPARATOR + insertId;
    }

    /**
     * Native key of a source that is not its provider's primary audit stream:
     * the source kind, then the parts, joined with {@code |}. The kind prefix
     * keeps keys of different sources of one provider apart.
     *
     * @param sourceKind upper snake case source kind, for example {@code DATABRICKS_LINEAGE}
     * @param parts      the source's unique id parts, in the order the spec lists
     * @return the native key
     */
    public static String namespacedNativeKey(String sourceKind, String... parts) {
        requireText(sourceKind, "sourceKind");
        if (parts == null || parts.length == 0) {
            throw new IllegalArgumentException("a namespaced native key needs at least one part");
        }
        StringBuilder sb = new StringBuilder(sourceKind);
        for (String part : parts) {
            requireText(part, "part");
            sb.append(SEPARATOR).append(part);
        }
        return sb.toString();
    }

    /**
     * Native key of an additional event a parser emits from one record. The
     * primary event keeps the record's own key, so its id matches the id the
     * one-event-per-record legacy pipeline produced.
     *
     * @param recordKey       the record's native key
     * @param targetCanonical the additional event's target canonical URI
     * @return {@code recordKey#targetCanonical}
     */
    public static String fanOutNativeKey(String recordKey, String targetCanonical) {
        requireText(recordKey, "recordKey");
        requireText(targetCanonical, "targetCanonical");
        return recordKey + FAN_OUT_SEPARATOR + targetCanonical;
    }

    /**
     * Key a usage counter dedupes on. Copies of one action delivered to
     * several accounts (CloudTrail {@code sharedEventID}) count once per
     * target; everything else counts once per {@code event_id}.
     *
     * @param event the event
     * @return a lowercase hex SHA-256, or the event id
     */
    public static String usageDedupKey(DlapLogEvent event) {
        String shared = event.getSharedEventId();
        if (shared.isBlank()) {
            return event.getEventId();
        }
        return sha256Hex(event.getSourceProvider().name() + SEPARATOR + "shared" + SEPARATOR + shared
                + SEPARATOR + LogEventSemantics.assetKey(event));
    }

    /**
     * Deterministic id of a quarantine record: quarantining the same raw
     * record twice yields the same id.
     *
     * @param bindingId the binding
     * @param ref       where the record lives
     * @param rawSha256 hex SHA-256 of the complete raw record
     * @return lowercase hex SHA-256
     */
    public static String quarantineId(String bindingId, RawRecordRef ref, String rawSha256) {
        requireText(bindingId, "bindingId");
        Objects.requireNonNull(ref, "ref");
        requireText(rawSha256, "rawSha256");
        return sha256Hex(bindingId + SEPARATOR + ref.getUri() + SEPARATOR + ref.getRecordIndex()
                + SEPARATOR + rawSha256);
    }

    /**
     * Lowercase hex SHA-256 of the UTF-8 bytes of a string.
     *
     * @param input the text
     * @return 64 hex characters
     */
    public static String sha256Hex(String input) {
        return sha256Hex(input.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Lowercase hex SHA-256 of bytes.
     *
     * @param input the bytes
     * @return 64 hex characters
     */
    public static String sha256Hex(byte[] input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
        } catch (NoSuchAlgorithmException e) {
            // Every JDK must provide SHA-256 (java.security.MessageDigest Javadoc).
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
