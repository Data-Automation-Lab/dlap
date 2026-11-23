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

package com.dlap.logingest.transport.lock;

import com.dlap.common.logevent.LogEventIds;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Component;

/**
 * {@link AdvisoryLocks} with PostgreSQL session-level advisory locks
 * ({@code pg_try_advisory_lock(namespace, key)}).
 *
 * <p>A lease holds one pooled connection for the duration of the poll; the poll's own work runs on
 * other connections. Closing the lease unlocks and returns the connection. If the unlock fails the
 * connection is aborted instead of returned, so a held lock can never leak to the next borrower;
 * if the process dies, PostgreSQL drops the session and its locks.
 */
@Component
public class PgAdvisoryLocks implements AdvisoryLocks {

    /** Advisory lock namespace of stream polls ("DL" plus 2); event-id locks use 1. */
    public static final int STREAM_NAMESPACE = 0x444C_0002;

    private static final Logger log = LoggerFactory.getLogger(PgAdvisoryLocks.class);

    private final DataSource dataSource;

    /**
     * Creates the lock service.
     *
     * @param dataSource the service datasource
     */
    public PgAdvisoryLocks(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Optional<Lease> tryLockStream(String bindingId, String streamId) {
        return tryLock(STREAM_NAMESPACE, streamKey(bindingId, streamId));
    }

    /**
     * Tries to take a lock.
     *
     * @param namespace the lock namespace
     * @param key       the key
     * @return the lease, or empty when another session holds the lock
     * @throws DataAccessResourceFailureException when no connection can be obtained
     */
    public Optional<Lease> tryLock(int namespace, int key) {
        Connection c;
        try {
            c = dataSource.getConnection();
        } catch (SQLException e) {
            throw new DataAccessResourceFailureException("no connection for an advisory lock", e);
        }
        try {
            if (!c.getAutoCommit()) {
                c.setAutoCommit(true);
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT pg_try_advisory_lock(?, ?)")) {
                ps.setInt(1, namespace);
                ps.setInt(2, key);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getBoolean(1)) {
                        return Optional.of(new PgLease(c, namespace, key));
                    }
                }
            }
            c.close();
            return Optional.empty();
        } catch (SQLException e) {
            abort(c);
            throw new DataAccessResourceFailureException("advisory lock query failed", e);
        }
    }

    /**
     * The lock key of a stream: the first 32 bits of {@code SHA-256(bindingId|streamId)}.
     *
     * @param bindingId the binding
     * @param streamId  the stream
     * @return the key
     */
    static int streamKey(String bindingId, String streamId) {
        return (int) Long.parseLong(LogEventIds.sha256Hex(bindingId + "|" + streamId).substring(0, 8), 16);
    }

    private static void abort(Connection c) {
        try {
            c.abort(Runnable::run);
        } catch (SQLException | RuntimeException e) {
            log.warn("Could not abort a connection after an advisory lock failure: {}", e.getMessage());
        }
    }

    /** A held session lock and its connection. */
    private static final class PgLease implements Lease {
        private final Connection connection;
        private final int namespace;
        private final int key;
        private boolean closed;

        PgLease(Connection connection, int namespace, int key) {
            this.connection = connection;
            this.namespace = namespace;
            this.key = key;
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            try (PreparedStatement ps = connection.prepareStatement("SELECT pg_advisory_unlock(?, ?)")) {
                ps.setInt(1, namespace);
                ps.setInt(2, key);
                ps.executeQuery().close();
                connection.close();
            } catch (SQLException e) {
                log.warn("Advisory unlock failed; dropping the connection so the lock is released: {}", e.getMessage());
                abort(connection);
            }
        }
    }
}
