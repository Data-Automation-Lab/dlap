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

import com.dlap.logingest.model.LogEventEntity;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Persistence layer for {@link LogEventEntity}: the ingest paths write through it and the replay
 * queries read through it.
 *
 * <p>Retention no longer deletes rows here: since Flyway V3 {@code log_events} is partitioned by
 * month of {@code ingest_time} and {@link LogEventPartitionRepository} drops whole partitions
 * (ADR-0060). {@code existsById} and {@code findById} look an event up by {@code event_id} across
 * every partition; writers call {@link #lockEventId} first, which keeps {@code event_id} unique
 * although the primary key also holds {@code ingest_time}.
 */
@Repository
public interface LogEventRepository extends JpaRepository<LogEventEntity, String>, LogEventIdLock {

    /**
     * Events of a time window, oldest first.
     *
     * @param from     inclusive start of the event-time window
     * @param to       inclusive end of the event-time window
     * @param pageable page
     * @return the events
     */
    List<LogEventEntity> findByEventTimeBetweenOrderByEventTimeAsc(
            Instant from, Instant to, Pageable pageable);

    /**
     * Events of one provider in a time window.
     *
     * @param sourceProvider provider name
     * @param from           inclusive start of the event-time window
     * @param to             inclusive end of the event-time window
     * @param pageable       page
     * @return the events
     */
    List<LogEventEntity> findBySourceProviderAndEventTimeBetween(
            String sourceProvider, Instant from, Instant to, Pageable pageable);
}
