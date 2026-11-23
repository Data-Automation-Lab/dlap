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

package com.dlap.autodelete.repository;

import com.dlap.autodelete.domain.DeletionAuditEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Repository for the compliance deletion audit trail.
 *
 * <p>All queries here are read-oriented (or append-only inserts via {@code save}).
 * Callers must never update or delete rows - the table is the persisted trail.
 */
@Repository
public interface IDeletionAuditRepository extends JpaRepository<DeletionAuditEntity, Long> {

    List<DeletionAuditEntity> findByTaskId(String taskId);

    List<DeletionAuditEntity> findByAssetId(String assetId);

    /**
     * Sum of bytes-deleted across successful audit rows. Returns 0 if no data.
     */
    @Query("SELECT COALESCE(SUM(a.bytesDeleted), 0) FROM DeletionAuditEntity a " +
           "WHERE a.status = 'SUCCESS' AND a.bytesDeleted IS NOT NULL")
    long sumBytesDeletedAllTime();

    /**
     * Sum of bytes-deleted across successful audit rows since the given timestamp.
     */
    @Query("SELECT COALESCE(SUM(a.bytesDeleted), 0) FROM DeletionAuditEntity a " +
           "WHERE a.status = 'SUCCESS' AND a.bytesDeleted IS NOT NULL AND a.performedAt >= :since")
    long sumBytesDeletedSince(@Param("since") LocalDateTime since);

    long countByStatus(String status);
}
