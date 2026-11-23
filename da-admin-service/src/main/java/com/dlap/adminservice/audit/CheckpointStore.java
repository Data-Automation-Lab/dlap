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

package com.dlap.adminservice.audit;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * Reads and writes {@code dlap_admin.audit_checkpoints} (V2) in the service's own database.
 * Constant SQL with bind parameters only.
 */
public class CheckpointStore {

    private static final String COLUMNS = "stream_id, seq, entry_hash, key_id, signed_at, payload, signature,"
            + " export_sink, export_location, exported_at, export_attempts, last_export_error";
    private static final String INSERT_SQL = "INSERT INTO dlap_admin.audit_checkpoints"
            + " (stream_id, seq, entry_hash, key_id, signed_at, payload, signature) VALUES (?, ?, ?, ?, ?, ?, ?)"
            + " ON CONFLICT (stream_id, seq) DO NOTHING";
    private static final String LATEST_SQL = "SELECT " + COLUMNS + " FROM dlap_admin.audit_checkpoints"
            + " WHERE stream_id = ? ORDER BY seq DESC LIMIT 1";
    private static final String BY_STREAM_SQL = "SELECT " + COLUMNS + " FROM dlap_admin.audit_checkpoints"
            + " WHERE stream_id = ? ORDER BY seq DESC LIMIT ?";
    private static final String ALL_OF_STREAM_SQL = "SELECT " + COLUMNS + " FROM dlap_admin.audit_checkpoints"
            + " WHERE stream_id = ? ORDER BY seq";
    private static final String UNEXPORTED_SQL = "SELECT " + COLUMNS + " FROM dlap_admin.audit_checkpoints"
            + " WHERE exported_at IS NULL ORDER BY signed_at, stream_id, seq LIMIT ?";
    private static final String EXPORTED_SQL = "UPDATE dlap_admin.audit_checkpoints SET export_sink = ?,"
            + " export_location = ?, exported_at = ?, export_attempts = export_attempts + 1, last_export_error = NULL"
            + " WHERE stream_id = ? AND seq = ? AND exported_at IS NULL";
    private static final String FAILED_SQL = "UPDATE dlap_admin.audit_checkpoints SET export_sink = ?,"
            + " export_attempts = export_attempts + 1, last_export_error = ?"
            + " WHERE stream_id = ? AND seq = ? AND exported_at IS NULL";

    private static final int MAX_ERROR_LENGTH = 1000;

    private static final RowMapper<StoredCheckpoint> ROW = CheckpointStore::map;

    private final JdbcTemplate jdbc;

    /**
     * Creates the store.
     *
     * @param jdbc the service database
     */
    public CheckpointStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts a new checkpoint; an existing row for the same stream and position stays.
     *
     * @param c the checkpoint, not yet exported
     * @return true when the row was inserted
     */
    public boolean insert(StoredCheckpoint c) {
        return jdbc.update(INSERT_SQL, c.streamId(), c.seq(), c.entryHash(), c.keyId(), Timestamp.from(c.signedAt()),
                c.payload(), c.signature()) == 1;
    }

    /**
     * The newest checkpoint of a stream.
     *
     * @param streamId the stream
     * @return the checkpoint with the highest seq, or empty
     */
    public Optional<StoredCheckpoint> latest(String streamId) {
        return jdbc.query(LATEST_SQL, ROW, streamId).stream().findFirst();
    }

    /**
     * The newest checkpoints of a stream.
     *
     * @param streamId the stream
     * @param limit    the most to return
     * @return newest first
     */
    public List<StoredCheckpoint> recent(String streamId, int limit) {
        return jdbc.query(BY_STREAM_SQL, ROW, streamId, limit);
    }

    /**
     * Every checkpoint of a stream.
     *
     * @param streamId the stream
     * @return oldest first
     */
    public List<StoredCheckpoint> all(String streamId) {
        return jdbc.query(ALL_OF_STREAM_SQL, ROW, streamId);
    }

    /**
     * Checkpoints whose write-once copy does not exist yet.
     *
     * @param limit the most to return
     * @return oldest first
     */
    public List<StoredCheckpoint> unexported(int limit) {
        return jdbc.query(UNEXPORTED_SQL, ROW, limit);
    }

    /**
     * Records a successful export.
     *
     * @param streamId the stream
     * @param seq      the position
     * @param sink     the sink name
     * @param location the copy's location
     * @param at       when
     */
    public void markExported(String streamId, long seq, String sink, String location, Instant at) {
        jdbc.update(EXPORTED_SQL, sink, location, Timestamp.from(at), streamId, seq);
    }

    /**
     * Records a failed export.
     *
     * @param streamId the stream
     * @param seq      the position
     * @param sink     the sink name
     * @param error    the error message; cut to 1000 characters
     */
    public void markExportFailed(String streamId, long seq, String sink, String error) {
        String e = error == null ? "unknown error" : error;
        jdbc.update(FAILED_SQL, sink, e.length() > MAX_ERROR_LENGTH ? e.substring(0, MAX_ERROR_LENGTH) : e, streamId, seq);
    }

    private static StoredCheckpoint map(ResultSet rs, int row) throws SQLException {
        Timestamp exported = rs.getTimestamp(10);
        return new StoredCheckpoint(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                rs.getTimestamp(5).toInstant(), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
                exported == null ? null : exported.toInstant(), rs.getInt(11), rs.getString(12));
    }
}
