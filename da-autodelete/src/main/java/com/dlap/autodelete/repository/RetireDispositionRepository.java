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

import com.dlap.autodelete.domain.RetireDispositionEntity;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Persistence of {@link RetireDispositionEntity} (RET-02, Flyway V6).
 */
@Repository
public interface RetireDispositionRepository extends JpaRepository<RetireDispositionEntity, UUID> {

    /**
     * The disposition of a soft-delete record.
     *
     * @param softDeleteRecordId the record
     * @return the disposition
     */
    Optional<RetireDispositionEntity> findBySoftDeleteRecordId(UUID softDeleteRecordId);

    /**
     * Dispositions on one of the given targets in one of the given states: the candidates a proof
     * event on a URI (or below it) may prove.
     *
     * @param targetUris the event's URI and each of its parents
     * @param statuses   the states
     * @return the dispositions
     */
    List<RetireDispositionEntity> findByTargetUriInAndStatusIn(Collection<String> targetUris,
            Collection<String> statuses);

    /**
     * The dispositions of several records (the task list shows the age of each rule in force, RET-06).
     *
     * @param softDeleteRecordIds the records
     * @return their dispositions
     */
    List<RetireDispositionEntity> findBySoftDeleteRecordIdIn(Collection<UUID> softDeleteRecordIds);

    /**
     * Reads a disposition and locks it until the transaction ends.
     *
     * @param id the id
     * @return the locked disposition
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM RetireDispositionEntity d WHERE d.id = :id")
    Optional<RetireDispositionEntity> findByIdForUpdate(@Param("id") UUID id);
}
