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

package com.dlap.policyengine.approval;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

import javax.sql.DataSource;

import com.dlap.common.audit.AuditEntry;
import com.dlap.common.audit.AuditException;
import com.dlap.common.audit.AuditStreams;

/**
 * Reads the entry a stream already holds under an idempotency key (docs/specs/audit-log.md section 6),
 * so a retried change can replay it (Q-692): the change then commits with the time the chain already
 * states, instead of being refused because its own clock moved on. Service roles have SELECT on
 * {@code dlap_common.audit_log}; the query uses the unique partial index on
 * {@code (stream_id, idempotency_key)}.
 */
public class AuditKeyLookup {

    private static final String BY_KEY_SQL = "SELECT stream_id, seq, prev_hash, entry_hash, record"
            + " FROM dlap_common.audit_log WHERE stream_id = ? AND idempotency_key = ?";

    private final DataSource dataSource;

    /**
     * Creates the lookup.
     *
     * @param dataSource connections to the {@code dlap_common} database
     */
    public AuditKeyLookup(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /**
     * The entry stored under a key, if any.
     *
     * @param streamId the stream
     * @param key      the idempotency key
     * @return the entry, or empty when the stream holds no entry with that key
     * @throws AuditException when the database cannot be read
     */
    public Optional<AuditEntry> find(String streamId, String key) {
        AuditStreams.requireValid(streamId);
        Objects.requireNonNull(key, "key");
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(BY_KEY_SQL)) {
            ps.setString(1, streamId);
            ps.setString(2, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new AuditEntry(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                        rs.getString(5)));
            }
        } catch (SQLException e) {
            throw new AuditException("read of audit stream " + streamId + " failed: " + e.getMessage(), e);
        }
    }
}
