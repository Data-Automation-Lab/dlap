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

package com.dlap.common.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.dlap.common.model.entity.Asset;
import com.dlap.common.model.entity.AssetLabelAssignment;
import com.dlap.common.model.entity.AssetLabelAssignmentId;
import com.dlap.common.model.entity.Label;

/**
 * Spring Data JPA repository for the {@link AssetLabelAssignment} entity.
 * 
 * Provides standard CRUD operations and custom query methods for managing asset-label relationships.
 * 
 * @author DLAP Team
 * @version 1.0
 * @since 1.0
 */
@Repository
public interface IAssetLabelAssignmentRepository extends JpaRepository<AssetLabelAssignment, AssetLabelAssignmentId> {

    /**
     * Find all label assignments for a specific asset
     * 
     * @param assetId the asset ID
     * @return list of label assignments for the asset
     */
    List<AssetLabelAssignment> findByAssetId(UUID assetId);

    /**
     * Find all label assignments for a specific asset (using asset ID property)
     * 
     * @param assetId the asset ID
     * @return list of label assignments for the asset
     */
    List<AssetLabelAssignment> findByAssetAssetId(UUID assetId);

    /**
     * Find assignment by asset and label entities
     * 
     * @param asset the asset entity
     * @param label the label entity
     * @return optional assignment
     */
    Optional<AssetLabelAssignment> findByAssetAndLabel(Asset asset, Label label);

    /**
     * Find all assignments for a specific label entity
     * 
     * @param label the label entity
     * @return list of assignments for the label
     */
    List<AssetLabelAssignment> findByLabel(Label label);

    /**
     * Find all assets assigned to a specific label
     * 
     * @param labelId the label ID
     * @param pageable pagination information
     * @return page of asset assignments for the label
     */
    Page<AssetLabelAssignment> findByLabelId(UUID labelId, Pageable pageable);

    /**
     * Find assignments by source
     * 
     * @param source the assignment source
     * @param pageable pagination information
     * @return page of assignments from the specified source
     */
    Page<AssetLabelAssignment> findBySource(String source, Pageable pageable);

    /**
     * Find assignments by assigned user
     * 
     * @param assignedByUserId the user who assigned the labels
     * @param pageable pagination information
     * @return page of assignments made by the user
     */
    Page<AssetLabelAssignment> findByAssignedByUserId(UUID assignedByUserId, Pageable pageable);

    /**
     * Find user assignments for a specific asset
     * 
     * @param assetId the asset ID
     * @return list of user-made assignments for the asset
     */
    @Query("SELECT ala FROM AssetLabelAssignment ala WHERE ala.assetId = :assetId AND ala.source = 'USER'")
    List<AssetLabelAssignment> findUserAssignmentsByAsset(@Param("assetId") UUID assetId);

    /**
     * Find ML assignments for a specific asset
     * 
     * @param assetId the asset ID
     * @return list of ML-made assignments for the asset
     */
    @Query("SELECT ala FROM AssetLabelAssignment ala WHERE ala.assetId = :assetId AND ala.source = 'SMART_LABEL_ML'")
    List<AssetLabelAssignment> findMlAssignmentsByAsset(@Param("assetId") UUID assetId);

    /**
     * Find assignments with confidence score above threshold
     * 
     * @param threshold the confidence threshold
     * @param pageable pagination information
     * @return page of high-confidence assignments
     */
    @Query("SELECT ala FROM AssetLabelAssignment ala WHERE ala.confidenceScore >= :threshold")
    Page<AssetLabelAssignment> findByConfidenceScoreGreaterThanEqual(@Param("threshold") Double threshold, Pageable pageable);

    /**
     * Find assignments with confidence score below threshold
     * 
     * @param threshold the confidence threshold
     * @param pageable pagination information
     * @return page of low-confidence assignments
     */
    @Query("SELECT ala FROM AssetLabelAssignment ala WHERE ala.confidenceScore < :threshold")
    Page<AssetLabelAssignment> findByConfidenceScoreLessThan(@Param("threshold") Double threshold, Pageable pageable);

    /**
     * Find assignments created within a time range
     * 
     * @param startTime the start time
     * @param endTime the end time
     * @param pageable pagination information
     * @return page of assignments created within the time range
     */
    Page<AssetLabelAssignment> findByAssignedTsBetween(Instant startTime, Instant endTime, Pageable pageable);

    /**
     * Check if a specific asset-label assignment exists
     * 
     * @param assetId the asset ID
     * @param labelId the label ID
     * @return true if assignment exists, false otherwise
     */
    boolean existsByAssetIdAndLabelId(UUID assetId, UUID labelId);

    /**
     * Count assignments for a specific asset
     * 
     * @param assetId the asset ID
     * @return number of labels assigned to the asset
     */
    long countByAssetId(UUID assetId);

    /**
     * Count assignments for a specific label
     * 
     * @param labelId the label ID
     * @return number of assets assigned to the label
     */
    long countByLabelId(UUID labelId);

    /**
     * Count assignments for a specific label entity
     * 
     * @param label the label entity
     * @return number of assets assigned to the label
     */
    long countByLabel(Label label);

    /**
     * Count assignments by source
     * 
     * @param source the assignment source
     * @return number of assignments from the source
     */
    long countBySource(String source);

    /**
     * Count user assignments
     * 
     * @return number of user-made assignments
     */
    @Query("SELECT COUNT(ala) FROM AssetLabelAssignment ala WHERE ala.source = 'USER'")
    long countUserAssignments();

    /**
     * Count ML assignments
     * 
     * @return number of ML-made assignments
     */
    @Query("SELECT COUNT(ala) FROM AssetLabelAssignment ala WHERE ala.source = 'SMART_LABEL_ML'")
    long countMlAssignments();

    /**
     * Count high-confidence assignments
     * 
     * @param threshold the confidence threshold
     * @return number of assignments above threshold
     */
    @Query("SELECT COUNT(ala) FROM AssetLabelAssignment ala WHERE ala.confidenceScore >= :threshold")
    long countHighConfidenceAssignments(@Param("threshold") Double threshold);

    /**
     * Delete all assignments for a specific asset
     * 
     * @param assetId the asset ID
     */
    @Modifying
    @Query("DELETE FROM AssetLabelAssignment ala WHERE ala.assetId = :assetId")
    void deleteByAssetId(@Param("assetId") UUID assetId);

    /**
     * Delete all assignments for a specific label
     * 
     * @param labelId the label ID
     */
    @Modifying
    @Query("DELETE FROM AssetLabelAssignment ala WHERE ala.labelId = :labelId")
    void deleteByLabelId(@Param("labelId") UUID labelId);

    /**
     * Delete assignments by source
     * 
     * @param source the assignment source
     */
    @Modifying
    @Query("DELETE FROM AssetLabelAssignment ala WHERE ala.source = :source")
    void deleteBySource(@Param("source") String source);

    /**
     * Find labels assigned to a specific asset (returns label IDs)
     * 
     * @param assetId the asset ID
     * @return list of label IDs assigned to the asset
     */
    @Query("SELECT ala.labelId FROM AssetLabelAssignment ala WHERE ala.assetId = :assetId")
    List<UUID> findLabelIdsByAssetId(@Param("assetId") UUID assetId);

    /**
     * Find assets assigned to a specific label (returns asset IDs)
     * 
     * @param labelId the label ID
     * @return list of asset IDs assigned to the label
     */
    @Query("SELECT ala.assetId FROM AssetLabelAssignment ala WHERE ala.labelId = :labelId")
    List<UUID> findAssetIdsByLabelId(@Param("labelId") UUID labelId);
} 