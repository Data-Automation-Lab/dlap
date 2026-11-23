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

package com.dlap.policyengine.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.dlap.policyengine.model.PolicyDraft;
import com.dlap.policyengine.model.PolicyDraftStatus;

/**
 * Repository interface for PolicyDraft entities.
 * Provides comprehensive query methods for draft management and workflow operations.
 */
@Repository
public interface PolicyDraftRepository extends JpaRepository<PolicyDraft, UUID>, JpaSpecificationExecutor<PolicyDraft> {

    /**
     * Find all drafts by their current status.
     */
    Page<PolicyDraft> findByStatus(PolicyDraftStatus status, Pageable pageable);

    /**
     * Find all drafts created by a specific user.
     */
    Page<PolicyDraft> findByCreatedByUserId(UUID createdByUserId, Pageable pageable);

    /**
     * Find all drafts where the user is a stakeholder.
     */
    @Query("SELECT pd FROM PolicyDraft pd WHERE :userId MEMBER OF pd.stakeholders")
    Page<PolicyDraft> findByStakeholder(@Param("userId") UUID userId, Pageable pageable);

    /**
     * Find all drafts pending review (submitted or under review).
     */
    @Query("SELECT pd FROM PolicyDraft pd WHERE pd.status IN ('SUBMITTED', 'UNDER_REVIEW')")
    Page<PolicyDraft> findPendingReview(Pageable pageable);

    /**
     * Find all drafts that require attention from a specific user (created by them or they are stakeholder).
     */
    @Query("SELECT DISTINCT pd FROM PolicyDraft pd WHERE pd.createdByUserId = :userId OR :userId MEMBER OF pd.stakeholders")
    Page<PolicyDraft> findRequiringAttention(@Param("userId") UUID userId, Pageable pageable);

    /**
     * Find drafts by name containing specific text (case insensitive).
     */
    Page<PolicyDraft> findByNameContainingIgnoreCase(String name, Pageable pageable);

    /**
     * Find drafts by category.
     */
    Page<PolicyDraft> findByCategory(String category, Pageable pageable);

    /**
     * Find drafts by priority level.
     */
    Page<PolicyDraft> findByPriority(String priority, Pageable pageable);

    /**
     * Find drafts that are based on a specific policy (updates to existing policies).
     */
    List<PolicyDraft> findByBasePolicyId(UUID basePolicyId);

    /**
     * Find the latest version of drafts for a specific base policy.
     */
    @Query("SELECT pd FROM PolicyDraft pd WHERE pd.basePolicyId = :basePolicyId ORDER BY pd.version DESC")
    List<PolicyDraft> findLatestVersionByBasePolicyId(@Param("basePolicyId") UUID basePolicyId);

    /**
     * Find drafts with target implementation date before specified date.
     */
    List<PolicyDraft> findByTargetImplementationDateBefore(Instant date);

    /**
     * Find drafts created within a specific time range.
     */
    Page<PolicyDraft> findByCreatedAtBetween(Instant startDate, Instant endDate, Pageable pageable);

    /**
     * Find drafts that have been in a specific status for longer than specified time.
     */
    @Query("SELECT pd FROM PolicyDraft pd WHERE pd.status = :status AND pd.updatedAt < :cutoffTime")
    List<PolicyDraft> findStaleInStatus(@Param("status") PolicyDraftStatus status, @Param("cutoffTime") Instant cutoffTime);

    /**
     * Find drafts containing specific tags.
     */
    @Query("SELECT pd FROM PolicyDraft pd WHERE :tag MEMBER OF pd.tags")
    Page<PolicyDraft> findByTag(@Param("tag") String tag, Pageable pageable);

    /**
     * Find drafts by multiple criteria (complex search).
     */
    @Query("SELECT pd FROM PolicyDraft pd WHERE " +
           "(:status IS NULL OR pd.status = :status) AND " +
           "(:category IS NULL OR pd.category = :category) AND " +
           "(:priority IS NULL OR pd.priority = :priority) AND " +
           "(:createdBy IS NULL OR pd.createdByUserId = :createdBy) AND " +
           "(:nameFilter IS NULL OR LOWER(pd.name) LIKE LOWER(CONCAT('%', :nameFilter, '%')))")
    Page<PolicyDraft> findByCriteria(
        @Param("status") PolicyDraftStatus status,
        @Param("category") String category,
        @Param("priority") String priority,
        @Param("createdBy") UUID createdBy,
        @Param("nameFilter") String nameFilter,
        Pageable pageable
    );

    /**
     * Count drafts by status.
     */
    long countByStatus(PolicyDraftStatus status);

    /**
     * Count drafts created by a specific user.
     */
    long countByCreatedByUserId(UUID createdByUserId);

    /**
     * Check if a draft name already exists (for validation).
     */
    boolean existsByName(String name);

    /**
     * Check if a draft name exists excluding a specific draft (for updates).
     */
    @Query("SELECT CASE WHEN COUNT(pd) > 0 THEN true ELSE false END FROM PolicyDraft pd WHERE pd.name = :name AND pd.id != :excludeId")
    boolean existsByNameExcludingId(@Param("name") String name, @Param("excludeId") UUID excludeId);

    /**
     * Find all distinct categories used in drafts.
     */
    @Query("SELECT DISTINCT pd.category FROM PolicyDraft pd WHERE pd.category IS NOT NULL")
    List<String> findAllCategories();

    /**
     * Find all distinct tags used in drafts.
     */
    @Query("SELECT DISTINCT tag FROM PolicyDraft pd JOIN pd.tags tag")
    List<String> findAllTags();

    /**
     * Get draft statistics for dashboard.
     */
    @Query("SELECT pd.status, COUNT(pd) FROM PolicyDraft pd GROUP BY pd.status")
    List<Object[]> getDraftStatisticsByStatus();

    /**
     * Get drafts summary by user.
     */
    @Query("SELECT pd.createdByUserId, pd.status, COUNT(pd) FROM PolicyDraft pd GROUP BY pd.createdByUserId, pd.status")
    List<Object[]> getDraftStatisticsByUser();

    /**
     * Find overdue drafts (target implementation date passed and still not published).
     */
    @Query("SELECT pd FROM PolicyDraft pd WHERE pd.targetImplementationDate < :currentDate AND pd.status != 'PUBLISHED' AND pd.status != 'REJECTED' AND pd.status != 'ARCHIVED'")
    List<PolicyDraft> findOverdueDrafts(@Param("currentDate") Instant currentDate);
} 