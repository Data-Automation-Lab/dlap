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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import javax.sql.DataSource;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Appends events to {@code dlap_common.audit_log} (docs/specs/audit-log.md, section "Writing").
 *
 * <p>Each append runs in its own transaction on a connection of its own, taken from the
 * {@link DataSource} of the {@code dlap_common} database, and commits before it returns. It is
 * therefore not part of the caller's transaction: a caller that audits a destructive action
 * appends a {@link AuditOutcome#REQUESTED} record before the action and the final outcome after
 * it. The database role of the {@code DataSource} must be the stream's writer role.
 *
 * <p>Within the transaction the writer takes the stream's advisory lock, reads the head, builds
 * the record with the injected {@link Clock} as {@code recordedAt}, computes the entry hash and
 * inserts. The insert trigger repeats the head and hash checks, so a writer bug cannot store a
 * broken chain. With an idempotency key, an entry that already carries the key is returned
 * instead of a new one, provided it records the same event.
 */
public final class AuditLogWriter {

    private static final String LOCK_SQL =
            "SELECT pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended('dlap_common.audit_log:' || ?, 0))";
    private static final String HEAD_SQL = "SELECT stream_id, seq, prev_hash, entry_hash, record"
            + " FROM dlap_common.audit_log WHERE stream_id = ? ORDER BY seq DESC LIMIT 1";
    private static final String BY_KEY_SQL = "SELECT stream_id, seq, prev_hash, entry_hash, record"
            + " FROM dlap_common.audit_log WHERE stream_id = ? AND idempotency_key = ?";
    private static final String INSERT_SQL = "INSERT INTO dlap_common.audit_log"
            + " (stream_id, seq, prev_hash, entry_hash, record) VALUES (?, ?, ?, ?, ?)";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final DataSource dataSource;
    private final Clock clock;

    /**
     * Creates a writer.
     *
     * @param dataSource connections to the {@code dlap_common} database as the writer role
     * @param clock      the clock for {@code recordedAt}
     */
    public AuditLogWriter(DataSource dataSource, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Appends one event and commits.
     *
     * @param streamId the stream; its writer role must be the connection's role
     * @param event    the event
     * @return the stored entry (or, for a repeated idempotency key, the entry stored first)
     * @throws AuditException           when the database refuses or fails
     * @throws IllegalArgumentException for an invalid stream id, or an idempotency key that an
     *                                  entry with a different event already carries
     */
    public AuditEntry append(String streamId, AuditEvent event) {
        AuditStreams.requireValid(streamId);
        Objects.requireNonNull(event, "event");
        try (Connection c = dataSource.getConnection()) {
            boolean autoCommit = c.getAutoCommit();
            int isolation = c.getTransactionIsolation();
            c.setAutoCommit(false);
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            try {
                AuditEntry entry = appendLocked(c, streamId, event);
                c.commit();
                return entry;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setTransactionIsolation(isolation);
                c.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new AuditException("append to audit stream " + streamId + " failed: " + e.getMessage(), e);
        }
    }

    private AuditEntry appendLocked(Connection c, String streamId, AuditEvent event) throws SQLException {
        try (PreparedStatement lock = c.prepareStatement(LOCK_SQL)) {
            lock.setString(1, streamId);
            lock.execute();
        }
        if (event.idempotencyKey() != null) {
            Optional<AuditEntry> existing = query(c, BY_KEY_SQL, streamId, event.idempotencyKey());
            if (existing.isPresent()) {
                requireSameEvent(existing.get(), event);
                return existing.get();
            }
        }
        Optional<AuditEntry> head = query(c, HEAD_SQL, streamId, null);
        long seq = head.map(h -> h.seq() + 1).orElse(1L);
        String prevHash = head.map(AuditEntry::entryHash).orElse(AuditChain.GENESIS_HASH);
        String record = AuditChain.record(event, clock.instant());
        String entryHash = AuditChain.entryHash(streamId, seq, prevHash, record);
        try (PreparedStatement insert = c.prepareStatement(INSERT_SQL)) {
            insert.setString(1, streamId);
            insert.setLong(2, seq);
            insert.setString(3, prevHash);
            insert.setString(4, entryHash);
            insert.setString(5, record);
            insert.executeUpdate();
        }
        return new AuditEntry(streamId, seq, prevHash, entryHash, record);
    }

    /** The stored record must be the new event written at the stored record's own recordedAt. */
    private static void requireSameEvent(AuditEntry existing, AuditEvent event) {
        Instant recordedAt;
        try {
            JsonNode node = JSON.readTree(existing.record());
            recordedAt = Instant.parse(node.path("recordedAt").asText());
        } catch (JsonProcessingException | RuntimeException e) {
            throw new IllegalArgumentException("stored audit entry " + existing.seq() + " of "
                    + existing.streamId() + " has no readable recordedAt", e);
        }
        if (!AuditChain.record(event, recordedAt).equals(existing.record())) {
            throw new IllegalArgumentException("idempotency key " + event.idempotencyKey()
                    + " already names entry " + existing.seq() + " of " + existing.streamId()
                    + " with a different event");
        }
    }

    private static Optional<AuditEntry> query(Connection c, String sql, String streamId, String key)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, streamId);
            if (key != null) {
                ps.setString(2, key);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(AuditLogReader.entry(rs)) : Optional.empty();
            }
        }
    }
}
