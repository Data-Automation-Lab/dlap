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

package com.dlap.reporting.drift;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.dlap.reporting.model.AssetReadRecordEntity;
import com.dlap.reporting.model.DriftFindingEntity;

/**
 * The queries the order-independent drift join needs beyond the buffer repositories: the
 * per-key transaction lock, the reads a late decision applies to, and the findings of one read.
 */
public interface DriftJoinRepository extends Repository<AssetReadRecordEntity, UUID> {

    /**
     * Takes the transaction-scoped PostgreSQL advisory lock of one {@code (asset, subject)} join
     * key. A read and a decision for the same key, consumed by two listeners or two replicas,
     * then run one after the other, and the second sees the first's committed row. The lock is
     * released at commit or rollback.
     *
     * @param key the 64-bit lock key ({@code DriftDetectionOrchestrator.joinLockKey})
     * @return always 1
     */
    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(:key)", nativeQuery = true)
    Integer lockJoinKey(@Param("key") long key);

    /**
     * The buffered reads of one key with a read time in {@code [from, to]}, oldest first.
     *
     * @param assetId   the asset key
     * @param subjectId the subject key
     * @param from      the earliest read time, inclusive
     * @param to        the latest read time, inclusive
     * @return the reads
     */
    @Query("""
            SELECT r FROM AssetReadRecordEntity r
            WHERE r.assetId = :assetId AND r.subjectId = :subjectId
              AND r.readAt >= :from AND r.readAt <= :to
            ORDER BY r.readAt, r.eventId
            """)
    List<AssetReadRecordEntity> findReadsInWindow(@Param("assetId") String assetId,
                                                  @Param("subjectId") String subjectId,
                                                  @Param("from") Instant from,
                                                  @Param("to") Instant to);

    /**
     * The findings stored for one read event.
     *
     * @param readEventId the read's {@code eventId}
     * @return the findings, at most one per drift type
     */
    @Query("SELECT f FROM DriftFindingEntity f WHERE f.readEventId = :readEventId")
    List<DriftFindingEntity> findFindingsForRead(@Param("readEventId") String readEventId);
}
