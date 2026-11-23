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

import com.dlap.autodelete.domain.SoftDeleteRecordEntity;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Persistence layer for soft-delete state. This is the table that
 * receives policy-driven DELETE actions (see
 * {@code DeletionServiceImpl.queueSoftDelete}); the Delete Agent
 * dashboard and the {@code /deletion/tasks} list read their KPIs and
 * rows from here. The retire sweeper (RET-02) claims one due row at a time with
 * {@link #claimNextDue}, a {@code SELECT ... FOR UPDATE SKIP LOCKED}, so two replicas never act on
 * the same row (Q-548).
 */
@Repository
public interface SoftDeleteRecordRepository extends JpaRepository<SoftDeleteRecordEntity, UUID> {

    Optional<SoftDeleteRecordEntity> findFirstByAssetIdAndStatusOrderByQueuedAtDesc(UUID assetId, String status);

    List<SoftDeleteRecordEntity> findByStatusAndScheduledHardDeleteAtBefore(String status, Instant cutoff);

    long countByStatus(String status);

    /** Count of rows whose hard-delete completed at/after the given instant (last-24h metric). */
    long countByStatusAndHardDeletedAtAfter(String status, Instant after);

    /**
     * Sum of the catalog sizes of the records in a state; null sizes count as zero (Q-619, the
     * storage-reclaimed KPI for {@code HARD_DELETED}).
     *
     * @param status the state
     * @return the bytes
     */
    @Query("SELECT COALESCE(SUM(s.sizeBytes), 0) FROM SoftDeleteRecordEntity s WHERE s.status = :status")
    long sumSizeBytesByStatus(@Param("status") String status);

    /**
     * Like {@link #sumSizeBytesByStatus} for the records the provider proved deleted at or after an instant.
     *
     * @param status the state
     * @param since  the earliest provider event time
     * @return the bytes
     */
    @Query("SELECT COALESCE(SUM(s.sizeBytes), 0) FROM SoftDeleteRecordEntity s "
            + "WHERE s.status = :status AND s.hardDeletedAt >= :since")
    long sumSizeBytesByStatusSince(@Param("status") String status, @Param("since") Instant since);

    /** Most-recent queued-at timestamp, i.e. when a policy last queued a delete. */
    @Query("SELECT MAX(s.queuedAt) FROM SoftDeleteRecordEntity s")
    Optional<Instant> findMaxQueuedAt();

    /** Distinct non-null policy ids that have driven a soft-delete. */
    @Query("SELECT COUNT(DISTINCT s.policyId) FROM SoftDeleteRecordEntity s WHERE s.policyId IS NOT NULL")
    long countDistinctPolicyIds();

    /** All records, newest-queued first, for the reconciled task list. */
    Page<SoftDeleteRecordEntity> findAllByOrderByQueuedAtDesc(Pageable pageable);

    /** Records queued in the given window, newest first, for recent-activity. */
    List<SoftDeleteRecordEntity> findByStatusOrderByQueuedAtAsc(String status);

    /**
     * The row queued for a fire, if any: a redelivered fire joins it.
     *
     * @param policyFireId the fire id
     * @return the row
     */
    Optional<SoftDeleteRecordEntity> findFirstByPolicyFireId(UUID policyFireId);

    /**
     * The newest row of an asset in one of the given states.
     *
     * @param assetId  the asset
     * @param statuses the states
     * @return the row
     */
    Optional<SoftDeleteRecordEntity> findFirstByAssetIdAndStatusInOrderByQueuedAtDesc(UUID assetId,
            Collection<String> statuses);

    /**
     * Reads a row and locks it until the transaction ends (restore and proof paths), waiting for a
     * sweeper that holds it.
     *
     * @param id the row id
     * @return the locked row
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM SoftDeleteRecordEntity s WHERE s.id = :id")
    Optional<SoftDeleteRecordEntity> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Claims the next row the sweeper has to act on and locks it until the transaction ends. Rows
     * another transaction holds are skipped, not waited for ({@code SKIP LOCKED}), so two
     * replicas, or a replica whose ShedLock lease ran out, never act on the same row.
     *
     * <p>Due rows: {@code SOFT_DELETED} whose window ended (and whose retry time, if any, passed),
     * {@code HARD_DELETE_PENDING_APPROVAL} whose {@code next_check_at} passed, and
     * {@code AWAITING_PROOF} whose proof is overdue.
     *
     * @param now the sweep time
     * @return the claimed row, or empty when nothing is due
     */
    @Query(value = "SELECT * FROM dlap_autodelete.soft_delete_records"
            + " WHERE (status IN ('SOFT_DELETED', 'HARD_DELETE_PENDING_APPROVAL')"
            + "        AND COALESCE(next_check_at, scheduled_hard_delete_at) <= :now)"
            + "    OR (status = 'AWAITING_PROOF' AND next_check_at <= :now)"
            + " ORDER BY COALESCE(next_check_at, scheduled_hard_delete_at), id"
            + " LIMIT 1 FOR UPDATE SKIP LOCKED", nativeQuery = true)
    Optional<SoftDeleteRecordEntity> claimNextDue(@Param("now") Instant now);

    /**
     * Claims the next record whose bucket, prefix or container rule has to end and locks it until the transaction
     * ends; rows another transaction holds (a restore, the sweeper, another replica) are skipped. A record
     * qualifies when its rule was applied at or before {@code appliedBefore} (the maximum age passed) and its
     * rule-end retry time, if any, passed, and it is either
     * <ul>
     *   <li>{@code RULE_IN_FORCE} (RET-06, Q-677), or</li>
     *   <li>{@code AWAITING_PROOF} with an applied native container rule (S3, GCS or Azure lifecycle, not a dry
     *       run, with its stored document) whose disposition is still {@code AWAITING_PROOF} or
     *       {@code PROOF_OVERDUE}: applied and never proven (RET-08, Q-695).</li>
     * </ul>
     * Oldest apply first.
     *
     * @param appliedBefore the latest apply time of a rule that has to end
     * @param now           the run time
     * @return the claimed row, or empty when no rule has to end
     */
    @Query(value = "SELECT r.* FROM dlap_autodelete.soft_delete_records r"
            + " JOIN dlap_autodelete.retire_dispositions d ON d.soft_delete_record_id = r.id"
            + " WHERE d.applied_at <= :appliedBefore"
            + "   AND (r.rule_end_retry_at IS NULL OR r.rule_end_retry_at <= :now)"
            + "   AND (r.status = 'RULE_IN_FORCE'"
            + "        OR (r.status = 'AWAITING_PROOF' AND d.status IN ('AWAITING_PROOF', 'PROOF_OVERDUE')"
            + "            AND d.mode = 'NATIVE_RULE' AND d.dry_run = FALSE AND d.rule_document IS NOT NULL"
            + "            AND d.platform IN ('S3_LIFECYCLE', 'GCS_LIFECYCLE', 'AZURE_BLOB_LIFECYCLE')))"
            + " ORDER BY d.applied_at, r.id"
            + " LIMIT 1 FOR UPDATE OF r SKIP LOCKED", nativeQuery = true)
    Optional<SoftDeleteRecordEntity> claimNextRuleToEnd(@Param("appliedBefore") Instant appliedBefore,
            @Param("now") Instant now);

    /**
     * Rows in the given states, soonest hard delete first (the would-delete list).
     *
     * @param statuses the states
     * @param pageable the page
     * @return the page
     */
    Page<SoftDeleteRecordEntity> findByStatusInOrderByScheduledHardDeleteAtAsc(Collection<String> statuses,
            Pageable pageable);

    /**
     * Moves a row's next check, outside the transaction that failed on it, so one failing row does
     * not block the sweep.
     *
     * @param id the row
     * @param at the next check
     * @return the rows changed
     */
    @Modifying
    @Query("UPDATE SoftDeleteRecordEntity s SET s.nextCheckAt = :at WHERE s.id = :id")
    int deferNextCheck(@Param("id") UUID id, @Param("at") Instant at);

    /**
     * Moves a record's rule-end retry time, outside the transaction that failed on it, so one failing record does
     * not block the rule-end run and the sweeper's {@code next_check_at} is left alone (RET-08).
     *
     * @param id the row
     * @param at the earliest next attempt
     * @return the rows changed
     */
    @Modifying
    @Query("UPDATE SoftDeleteRecordEntity s SET s.ruleEndRetryAt = :at WHERE s.id = :id")
    int deferRuleEnd(@Param("id") UUID id, @Param("at") Instant at);
}
