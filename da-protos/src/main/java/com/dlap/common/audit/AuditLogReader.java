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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import javax.sql.DataSource;

/**
 * Reads {@code dlap_common.audit_log} and {@code dlap_common.audit_streams}. Needs only
 * {@code SELECT}, which every service role has.
 */
public final class AuditLogReader implements AuditEntryPages {

    /** Largest page {@link #page} returns. */
    public static final int MAX_PAGE_SIZE = 5_000;

    private static final String STREAMS_SQL =
            "SELECT stream_id, writer_role, description FROM dlap_common.audit_streams ORDER BY stream_id";
    private static final String HEAD_SQL = "SELECT stream_id, seq, prev_hash, entry_hash, record"
            + " FROM dlap_common.audit_log WHERE stream_id = ? ORDER BY seq DESC LIMIT 1";
    private static final String PAGE_SQL = "SELECT stream_id, seq, prev_hash, entry_hash, record"
            + " FROM dlap_common.audit_log WHERE stream_id = ? AND seq > ? ORDER BY seq LIMIT ?";
    private static final String ENTRY_SQL = "SELECT stream_id, seq, prev_hash, entry_hash, record"
            + " FROM dlap_common.audit_log WHERE stream_id = ? AND seq = ?";

    private final DataSource dataSource;

    /**
     * Creates a reader.
     *
     * @param dataSource connections to the {@code dlap_common} database
     */
    public AuditLogReader(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /**
     * Every registered stream, by id.
     *
     * @return the streams
     */
    public List<AuditStream> streams() {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(STREAMS_SQL);
                ResultSet rs = ps.executeQuery()) {
            List<AuditStream> out = new ArrayList<>();
            while (rs.next()) {
                out.add(new AuditStream(rs.getString(1), rs.getString(2), rs.getString(3)));
            }
            return out;
        } catch (SQLException e) {
            throw new AuditException("reading the audit streams failed: " + e.getMessage(), e);
        }
    }

    /**
     * The last entry of a stream.
     *
     * @param streamId the stream
     * @return the head, or empty for a stream without entries
     */
    public Optional<AuditEntry> head(String streamId) {
        List<AuditEntry> rows = run(HEAD_SQL, streamId, null, null);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * One entry.
     *
     * @param streamId the stream
     * @param seq      the position
     * @return the entry, or empty
     */
    public Optional<AuditEntry> entry(String streamId, long seq) {
        List<AuditEntry> rows = run(ENTRY_SQL, streamId, seq, null);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * Entries after a position, in order.
     *
     * @param streamId the stream
     * @param afterSeq the last position already read; 0 to start at the beginning
     * @param limit    1 to {@link #MAX_PAGE_SIZE}
     * @return up to {@code limit} entries with {@code seq > afterSeq}
     */
    @Override
    public List<AuditEntry> page(String streamId, long afterSeq, int limit) {
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("limit must be 1 to " + MAX_PAGE_SIZE);
        }
        return run(PAGE_SQL, streamId, afterSeq, limit);
    }

    private List<AuditEntry> run(String sql, String streamId, Long seq, Integer limit) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, streamId);
            if (seq != null) {
                ps.setLong(2, seq);
            }
            if (limit != null) {
                ps.setInt(3, limit);
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<AuditEntry> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(entry(rs));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new AuditException("reading audit stream " + streamId + " failed: " + e.getMessage(), e);
        }
    }

    /**
     * Maps the current row of a result with the columns
     * {@code stream_id, seq, prev_hash, entry_hash, record}.
     *
     * @param rs the result, positioned on a row
     * @return the entry
     * @throws SQLException when a column cannot be read
     */
    static AuditEntry entry(ResultSet rs) throws SQLException {
        return new AuditEntry(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5));
    }
}
