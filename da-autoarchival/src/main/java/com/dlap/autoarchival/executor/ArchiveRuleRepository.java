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

package com.dlap.autoarchival.executor;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Data access for {@link ArchiveRuleEntity}. */
public interface ArchiveRuleRepository extends JpaRepository<ArchiveRuleEntity, String> {

    /**
     * Finds the action that ran under an approval.
     *
     * @param approvalId the approval id
     * @return the row, if the approval was seen before
     */
    Optional<ArchiveRuleEntity> findByApprovalId(String approvalId);

    /**
     * Finds the action a policy fire produced.
     *
     * @param fireId the fire id
     * @return the row, if the fire was seen before
     */
    Optional<ArchiveRuleEntity> findByFireId(String fireId);

    /**
     * Lists the rows of a task.
     *
     * @param taskId the task id
     * @return the rows
     */
    List<ArchiveRuleEntity> findByTaskId(String taskId);

    /**
     * Counts the rules DLAP keeps on a bucket, to stay under its own cap.
     *
     * @param provider    the provider
     * @param scopeBucket the bucket or container
     * @param statuses    the statuses that count as a rule in place
     * @return the count
     */
    long countByProviderAndScopeBucketAndStatusIn(String provider, String scopeBucket, Collection<String> statuses);

    /**
     * Lists the rows waiting for a provider event on a bucket, to match evidence against.
     *
     * @param provider    the provider
     * @param scopeBucket the bucket or container
     * @param statuses    the statuses that still wait for proof
     * @return the rows
     */
    List<ArchiveRuleEntity> findByProviderAndScopeBucketAndStatusIn(String provider, String scopeBucket,
            Collection<String> statuses);

    /**
     * Lists rows by status, newest first, for the read API.
     *
     * @param statuses the statuses
     * @param pageable the page
     * @return the rows
     */
    org.springframework.data.domain.Page<ArchiveRuleEntity> findByStatusInOrderByCreatedAtDesc(
            Collection<String> statuses, org.springframework.data.domain.Pageable pageable);

    /**
     * Claims a planned row: one conditional update from {@code PLANNED} to {@code EXECUTING}, committed
     * on its own. Of any number of workers that call it for one row at the same moment, the database
     * lets exactly one see a count of 1 (Q-609).
     *
     * @param ruleId the rule id
     * @param now    time from the injected clock
     * @return 1 when this caller claimed the row, 0 when it was no longer {@code PLANNED}
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ArchiveRuleEntity r set r.status = 'EXECUTING', r.updatedAt = :now "
            + "where r.ruleId = :ruleId and r.status = 'PLANNED'")
    int claimPlanned(@Param("ruleId") String ruleId, @Param("now") Instant now);

    /**
     * Ends a row that nobody has claimed: one conditional update that changes it only while it is still
     * {@code PLANNED}. Every status write made before the claim (a refusal, a transient failure, a reject)
     * goes through it, so a worker holding an old copy of the row can never overwrite the claim or the
     * outcome of the worker that won it (Q-609).
     *
     * @param ruleId the rule id
     * @param status the new status
     * @param error  the error text, already clipped; may be {@code null}
     * @param now    time from the injected clock
     * @return 1 when this caller changed the row, 0 when it was no longer {@code PLANNED}
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ArchiveRuleEntity r set r.status = :status, r.lastError = :error, r.updatedAt = :now "
            + "where r.ruleId = :ruleId and r.status = 'PLANNED'")
    int endIfPlanned(@Param("ruleId") String ruleId, @Param("status") String status,
            @Param("error") String error, @Param("now") Instant now);

    /**
     * Records the claimer's own successful outcome (Q-712): {@code EXECUTING} to {@code APPLIED} or {@code RENDERED},
     * only while the row is still {@code EXECUTING}. A row that an administrator reset (or anything else changed)
     * while the worker was only slow keeps its new state; the late worker changes nothing.
     *
     * @param ruleId         the rule id
     * @param status         the new status ({@code APPLIED} or {@code RENDERED})
     * @param document       the rendered rule document
     * @param mock           whether the provider call was mocked
     * @param alreadyPresent whether the provider held the identical rule before
     * @param now            time from the injected clock; also the apply time
     * @return 1 when this caller recorded the outcome, 0 when the row was no longer {@code EXECUTING}
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ArchiveRuleEntity r set r.status = :status, r.ruleDocument = :document, r.mock = :mock, "
            + "r.alreadyPresent = :alreadyPresent, r.appliedAt = :now, r.lastError = null, r.updatedAt = :now "
            + "where r.ruleId = :ruleId and r.status = 'EXECUTING'")
    int finishIfExecuting(@Param("ruleId") String ruleId, @Param("status") String status,
            @Param("document") String document, @Param("mock") boolean mock,
            @Param("alreadyPresent") boolean alreadyPresent, @Param("now") Instant now);

    /**
     * Records the claimer's own failure (Q-712): {@code EXECUTING} to {@code FAILED}, only while the row is still
     * {@code EXECUTING}.
     *
     * @param ruleId the rule id
     * @param error  the error text, already clipped
     * @param now    time from the injected clock
     * @return 1 when this caller recorded the failure, 0 when the row was no longer {@code EXECUTING}
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ArchiveRuleEntity r set r.status = 'FAILED', r.lastError = :error, r.updatedAt = :now "
            + "where r.ruleId = :ruleId and r.status = 'EXECUTING'")
    int failIfExecuting(@Param("ruleId") String ruleId, @Param("error") String error, @Param("now") Instant now);

    /**
     * Marks a waiting row {@code EXECUTED} from provider evidence, only while it still has the status the caller
     * loaded. A restore that removed the same row at the same moment wins, and this update then changes nothing
     * (Q-674).
     *
     * @param ruleId   the rule id
     * @param expected the status the caller loaded ({@code APPLIED} or {@code RENDERED})
     * @param eventId  the proving event id
     * @param operation the event's operation
     * @param eventTime the provider's event time
     * @param kind     the evidence kind
     * @param now      time from the injected clock
     * @return 1 when this caller marked the row, 0 when its status had changed
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ArchiveRuleEntity r set r.status = 'EXECUTED', r.evidenceEventId = :eventId, "
            + "r.evidenceOperation = :operation, r.evidenceEventTime = :eventTime, r.evidenceKind = :kind, "
            + "r.executedAt = :eventTime, r.updatedAt = :now where r.ruleId = :ruleId and r.status = :expected")
    int markExecutedIf(@Param("ruleId") String ruleId, @Param("expected") String expected,
            @Param("eventId") String eventId, @Param("operation") String operation,
            @Param("eventTime") Instant eventTime, @Param("kind") String kind, @Param("now") Instant now);

    /**
     * Marks a row {@code REMOVED} (a restore), only while it still has the status the caller loaded, so a proof
     * update or a claim that ran in between is never overwritten (Q-674).
     *
     * @param ruleId   the rule id
     * @param expected the status the caller loaded
     * @param now      time from the injected clock
     * @return 1 when this caller removed the row, 0 when its status had changed
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ArchiveRuleEntity r set r.status = 'REMOVED', r.updatedAt = :now "
            + "where r.ruleId = :ruleId and r.status = :expected")
    int markRemovedIf(@Param("ruleId") String ruleId, @Param("expected") String expected, @Param("now") Instant now);

    /**
     * Admin reset of a claimed row whose provider rule exists (Q-669): {@code EXECUTING} to {@code APPLIED}.
     * The apply time is taken from the claim time (the last update), the earliest moment the rule could have been
     * written, so proof events after the claim still count. It never applies anything.
     *
     * @param ruleId         the rule id
     * @param alreadyPresent whether a restore must leave the provider rule alone (the rule cannot be told from a
     *                       customer's identical rule)
     * @param now            time from the injected clock
     * @return 1 when the row was reset, 0 when it was no longer {@code EXECUTING}
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ArchiveRuleEntity r set r.status = 'APPLIED', r.appliedAt = r.updatedAt, "
            + "r.alreadyPresent = :alreadyPresent, r.lastError = null, r.updatedAt = :now "
            + "where r.ruleId = :ruleId and r.status = 'EXECUTING'")
    int resetExecutingToApplied(@Param("ruleId") String ruleId, @Param("alreadyPresent") boolean alreadyPresent,
            @Param("now") Instant now);

    /**
     * Admin reset of a claimed row whose provider rule is absent (Q-669): {@code EXECUTING} to {@code FAILED}.
     *
     * @param ruleId the rule id
     * @param error  the error text, already clipped
     * @param now    time from the injected clock
     * @return 1 when the row was reset, 0 when it was no longer {@code EXECUTING}
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ArchiveRuleEntity r set r.status = 'FAILED', r.lastError = :error, r.updatedAt = :now "
            + "where r.ruleId = :ruleId and r.status = 'EXECUTING'")
    int resetExecutingToFailed(@Param("ruleId") String ruleId, @Param("error") String error,
            @Param("now") Instant now);

    /**
     * Finds the other live rows with an identical scope, class and age, for the duplicate-scope refusal (Q-673).
     * Mocked rows wrote nothing at the provider and are left out.
     *
     * @param provider    the provider
     * @param bucket      the bucket
     * @param prefix      the prefix
     * @param nativeClass the provider storage class
     * @param afterDays   the age in days
     * @param statuses    the statuses that count as live
     * @param excludeRuleId the caller's own rule id
     * @return the rows
     */
    @Query("select r from ArchiveRuleEntity r where r.provider = :provider and r.scopeBucket = :bucket "
            + "and r.scopePrefix = :prefix and r.nativeClass = :nativeClass and r.afterDays = :afterDays "
            + "and r.mode = 'NATIVE_RULE' and r.mock = false and r.status in :statuses "
            + "and r.ruleId <> :excludeRuleId order by r.createdAt asc")
    List<ArchiveRuleEntity> findLiveScope(@Param("provider") String provider, @Param("bucket") String bucket,
            @Param("prefix") String prefix, @Param("nativeClass") String nativeClass,
            @Param("afterDays") int afterDays, @Param("statuses") Collection<String> statuses,
            @Param("excludeRuleId") String excludeRuleId);

    /**
     * Lists the oldest real rows in a status that were applied before a cutoff, for the reconciliation log.
     *
     * @param status   the status
     * @param cutoff   rows applied before this time are listed
     * @param pageable the bound
     * @return the rows, oldest first
     */
    List<ArchiveRuleEntity> findByStatusAndMockFalseAndAppliedAtBeforeOrderByAppliedAtAsc(String status,
            Instant cutoff, Pageable pageable);

    /**
     * Counts rows in a status that last changed before a cutoff (a claim that never finished).
     *
     * @param status the status
     * @param cutoff rows changed before this time count
     * @return the count
     */
    long countByStatusAndUpdatedAtBefore(String status, Instant cutoff);

    /**
     * Lists the oldest rows in a status that last changed before a cutoff, for the reconciliation log.
     *
     * @param status   the status
     * @param cutoff   rows changed before this time are listed
     * @param pageable the bound
     * @return the rows, oldest first
     */
    List<ArchiveRuleEntity> findByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(String status, Instant cutoff,
            Pageable pageable);

    /**
     * Counts the rows in a status.
     *
     * @param status the status
     * @return the count
     */
    long countByStatus(String status);
}
