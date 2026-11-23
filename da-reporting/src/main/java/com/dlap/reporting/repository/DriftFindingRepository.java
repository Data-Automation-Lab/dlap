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

package com.dlap.reporting.repository;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.dlap.reporting.drift.DriftType;
import com.dlap.reporting.model.DriftFindingEntity;

public interface DriftFindingRepository extends JpaRepository<DriftFindingEntity, UUID> {

    /** Idempotency guard: one finding per (type, read event). */
    boolean existsByDriftTypeAndReadEventId(DriftType driftType, String readEventId);

    /**
     * Filtered, paged listing for the query API. Every filter is optional
     * (null = ignore), so the single query backs all combinations the
     * controller accepts. Ordered newest-read first.
     *
     * <p>The time bounds are cast in their {@code IS NULL} test. PostgreSQL cannot infer the type
     * of a bare {@code ? IS NULL} parameter, and the driver binds a null {@code Instant} without a
     * type, so without the cast every call with no time bound fails with "could not determine
     * data type of parameter".
     */
    @Query("""
            SELECT f FROM DriftFindingEntity f
            WHERE (:type IS NULL OR f.driftType = :type)
              AND (:assetId IS NULL OR f.assetId = :assetId)
              AND (:subjectId IS NULL OR f.subjectId = :subjectId)
              AND (cast(:from as Instant) IS NULL OR f.readAt >= :from)
              AND (cast(:to as Instant) IS NULL OR f.readAt <= :to)
            ORDER BY f.readAt DESC
            """)
    Page<DriftFindingEntity> search(@Param("type") DriftType type,
                                    @Param("assetId") String assetId,
                                    @Param("subjectId") String subjectId,
                                    @Param("from") Instant from,
                                    @Param("to") Instant to,
                                    Pageable pageable);
}
