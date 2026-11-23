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

package com.dlap.logingest.repository;

import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@link LogEventIdLock} with {@code pg_advisory_xact_lock(NAMESPACE, key)}, where the key is the
 * first 32 bits of the hex event id. Two ids that share a key only wait for each other; they are
 * never merged. Spring Data wires this class into {@link LogEventRepository} by its name.
 */
public class LogEventIdLockImpl implements LogEventIdLock {

    /** Advisory lock namespace of event-id locks ("DL" plus 1); stream locks use 2, partitions 3. */
    public static final int NAMESPACE = 0x444C_0001;

    private final JdbcTemplate jdbc;

    /**
     * Creates the fragment.
     *
     * @param jdbc the service datasource's template; it joins the caller's transaction
     */
    public LogEventIdLockImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void lockEventId(String eventId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalTransactionStateException("the event-id lock needs an active transaction");
        }
        jdbc.query("SELECT pg_advisory_xact_lock(?, ?)", rs -> null, NAMESPACE, key(eventId));
    }

    /**
     * The second half of the lock key: the first 32 bits of a hex event id, else the id's hash.
     *
     * @param eventId the event id
     * @return the key
     */
    static int key(String eventId) {
        Objects.requireNonNull(eventId, "eventId");
        if (eventId.length() >= 8 && eventId.substring(0, 8).chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
            return (int) Long.parseLong(eventId.substring(0, 8), 16);
        }
        return eventId.hashCode();
    }
}
