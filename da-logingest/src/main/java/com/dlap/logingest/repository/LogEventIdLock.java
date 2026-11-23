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

/**
 * Serializes writers of one {@code event_id} (ADR-0060); a fragment of {@link LogEventRepository}.
 *
 * <p>Flyway V3 partitions {@code log_events} by month of {@code ingest_time}, and PostgreSQL
 * requires the partition key in every unique constraint, so the primary key became
 * {@code (event_id, ingest_time)} and no longer stops two concurrent ingests of one event from
 * both inserting it. Every writer therefore takes this transaction-scoped advisory lock on the id
 * before it checks for a stored copy and inserts: the second writer waits for the first to
 * commit, then sees its row and treats the event as a duplicate (log-event spec section 5.4,
 * layer 1).
 */
public interface LogEventIdLock {

    /**
     * Takes the advisory lock of an event id until the current transaction ends.
     *
     * @param eventId the event id
     * @throws org.springframework.transaction.IllegalTransactionStateException when no transaction
     *                                                                          is active, where the
     *                                                                          lock would end with
     *                                                                          its own statement
     */
    void lockEventId(String eventId);
}
