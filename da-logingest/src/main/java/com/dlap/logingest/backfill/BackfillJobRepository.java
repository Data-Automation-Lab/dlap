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

package com.dlap.logingest.backfill;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Persistence of {@link BackfillJobEntity}. Every state change is a guarded update that names the
 * states it may start from, so two replicas or a cancel racing a progress write cannot undo each
 * other: the loser updates zero rows and stops.
 */
@Repository
public interface BackfillJobRepository extends JpaRepository<BackfillJobEntity, UUID> {

    /**
     * The job occupying a binding, if any.
     *
     * @param bindingId the binding
     * @param statuses  the active states
     * @return the job
     */
    Optional<BackfillJobEntity> findFirstByBindingIdAndStatusIn(String bindingId, Collection<BackfillStatus> statuses);

    /**
     * Jobs a scheduler may run, oldest request first.
     *
     * @param statuses the active states
     * @param page     the page size
     * @return the jobs
     */
    @Query("select j from BackfillJobEntity j where j.status in :statuses order by j.requestedAt, j.id")
    List<BackfillJobEntity> runnable(@Param("statuses") Collection<BackfillStatus> statuses, Pageable page);

    /**
     * One page of the list, newest first.
     *
     * @param bindingId only this binding, or {@code null}
     * @param status    only this state, or {@code null}
     * @param page      the page
     * @return the jobs
     */
    @Query("select j from BackfillJobEntity j where (:bindingId is null or j.bindingId = :bindingId) "
            + "and (:status is null or j.status = :status) order by j.requestedAt desc, j.id")
    List<BackfillJobEntity> find(@Param("bindingId") String bindingId, @Param("status") BackfillStatus status,
                                 Pageable page);

    /**
     * Counts the jobs the list would return.
     *
     * @param bindingId only this binding, or {@code null}
     * @param status    only this state, or {@code null}
     * @return the count
     */
    @Query("select count(j) from BackfillJobEntity j where (:bindingId is null or j.bindingId = :bindingId) "
            + "and (:status is null or j.status = :status)")
    long countMatching(@Param("bindingId") String bindingId, @Param("status") BackfillStatus status);

    /**
     * Starts or keeps running a job.
     *
     * @param id      the job
     * @param running the RUNNING state
     * @param active  the states it may start from
     * @param at      the start time, kept when already set
     * @return rows updated: 1, or 0 when the job ended meanwhile
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update BackfillJobEntity j set j.status = :running, j.startedAt = coalesce(j.startedAt, :at) "
            + "where j.id = :id and j.status in :active")
    int markRunning(@Param("id") UUID id, @Param("running") BackfillStatus running,
                    @Param("active") Collection<BackfillStatus> active, @Param("at") Instant at);

    /**
     * Writes the progress of a running job.
     *
     * @param id       the job
     * @param running  the RUNNING state
     * @param progress the progress JSON
     * @return rows updated: 1, or 0 when the job was cancelled or ended
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update BackfillJobEntity j set j.progress = :progress where j.id = :id and j.status = :running")
    int saveProgress(@Param("id") UUID id, @Param("running") BackfillStatus running,
                     @Param("progress") Map<String, Object> progress);

    /**
     * Ends a running job.
     *
     * @param id       the job
     * @param running  the RUNNING state
     * @param terminal the new terminal state
     * @param at       the end time
     * @param error    the reason, empty on success
     * @param progress the last progress JSON
     * @return rows updated: 1, or 0 when the job was cancelled meanwhile
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update BackfillJobEntity j set j.status = :terminal, j.finishedAt = :at, j.errorDetail = :error, "
            + "j.progress = :progress where j.id = :id and j.status = :running")
    int finish(@Param("id") UUID id, @Param("running") BackfillStatus running,
               @Param("terminal") BackfillStatus terminal, @Param("at") Instant at, @Param("error") String error,
               @Param("progress") Map<String, Object> progress);

    /**
     * Cancels a job that has not ended.
     *
     * @param id        the job
     * @param cancelled the CANCELLED state
     * @param active    the states it may be cancelled from
     * @param at        the end time
     * @param reason    the reason
     * @return rows updated: 1, or 0 when the job had ended
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update BackfillJobEntity j set j.status = :cancelled, j.finishedAt = :at, j.errorDetail = :reason "
            + "where j.id = :id and j.status in :active")
    int cancel(@Param("id") UUID id, @Param("cancelled") BackfillStatus cancelled,
               @Param("active") Collection<BackfillStatus> active, @Param("at") Instant at,
               @Param("reason") String reason);
}
