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
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.dlap.common.model.entity.Asset;

/**
 * Spring Data JPA repository for the {@link Asset} entity.
 * 
 * Provides standard CRUD operations and custom query methods for asset catalog management.
 * 
 * @author DLAP Team
 * @version 1.0
 * @since 1.0
 */
@Repository
public interface IAssetRepository extends JpaRepository<Asset, UUID>, JpaSpecificationExecutor<Asset> {

    /**
     * Find asset by native asset ID (external system identifier)
     * 
     * @param nativeAssetId the native asset ID
     * @return optional asset
     */
    Optional<Asset> findByNativeAssetId(String nativeAssetId);

    /**
     * Find asset by URI
     * 
     * @param assetUri the asset URI
     * @return optional asset
     */
    Optional<Asset> findByAssetUri(String assetUri);

    /**
     * Find assets by cloud connection ID
     * 
     * @param cloudConnectionId the cloud connection ID
     * @param pageable pagination information
     * @return page of assets from the specified connection
     */
    Page<Asset> findByCloudConnectionId(UUID cloudConnectionId, Pageable pageable);

    /**
     * Find assets by cloud provider
     * 
     * @param cloudProvider the cloud provider
     * @param pageable pagination information
     * @return page of assets from the specified provider
     */
    Page<Asset> findByCloudProvider(String cloudProvider, Pageable pageable);

    /**
     * Find assets by asset type
     * 
     * @param assetType the asset type
     * @param pageable pagination information
     * @return page of assets of the specified type
     */
    Page<Asset> findByAssetType(String assetType, Pageable pageable);

    /**
     * Find assets by region
     * 
     * @param region the region
     * @param pageable pagination information
     * @return page of assets in the specified region
     */
    Page<Asset> findByRegion(String region, Pageable pageable);

    /**
     * Find assets by service type
     * 
     * @param serviceType the service type
     * @param pageable pagination information
     * @return page of assets of the specified service type
     */
    Page<Asset> findByServiceType(String serviceType, Pageable pageable);

    /**
     * Find assets by cloud provider and service type
     * 
     * @param cloudProvider the cloud provider
     * @param serviceType the service type
     * @param pageable pagination information
     * @return page of assets matching both criteria
     */
    Page<Asset> findByCloudProviderAndServiceType(String cloudProvider, String serviceType, Pageable pageable);

    /**
     * Find assets by status
     * 
     * @param status the asset status
     * @param pageable pagination information
     * @return page of assets with the specified status
     */
    Page<Asset> findByStatus(String status, Pageable pageable);

    /**
     * Search assets by name containing the given text (case-insensitive)
     * 
     * @param namePattern the pattern to search for
     * @param pageable pagination information
     * @return page of assets matching the name pattern
     */
    Page<Asset> findByAssetNameContainingIgnoreCase(String namePattern, Pageable pageable);

    /**
     * Search assets by description containing the given text (case-insensitive)
     * 
     * @param descriptionPattern the pattern to search for
     * @param pageable pagination information
     * @return page of assets matching the description pattern
     */
    Page<Asset> findByDescriptionContainingIgnoreCase(String descriptionPattern, Pageable pageable);

    /**
     * Find assets discovered within a time range
     * 
     * @param startTime the start time
     * @param endTime the end time
     * @param pageable pagination information
     * @return page of assets discovered within the time range
     */
    Page<Asset> findByDlapDiscoveredTsBetween(Instant startTime, Instant endTime, Pageable pageable);

    /**
     * Find assets last scanned within a time range
     * 
     * @param startTime the start time
     * @param endTime the end time
     * @param pageable pagination information
     * @return page of assets scanned within the time range
     */
    Page<Asset> findByDlapLastScannedTsBetween(Instant startTime, Instant endTime, Pageable pageable);

    /**
     * Find all active assets
     * 
     * @param pageable pagination information
     * @return page of active assets
     */
    @Query("SELECT a FROM Asset a WHERE a.status = :#{T(com.dlap.common.constants.DlapConstants$AssetStatus).ACTIVE}")
    Page<Asset> findActiveAssets(Pageable pageable);

    /**
     * Find all archived assets
     *
     * @param pageable pagination information
     * @return page of archived assets
     */
    @Query("SELECT a FROM Asset a WHERE a.status = :#{T(com.dlap.common.constants.DlapConstants$AssetStatus).ARCHIVED}")
    Page<Asset> findArchivedAssets(Pageable pageable);

    /**
     * Find assets pending deletion
     *
     * @param pageable pagination information
     * @return page of assets pending deletion
     */
    @Query("SELECT a FROM Asset a WHERE a.status = :#{T(com.dlap.common.constants.DlapConstants$AssetStatus).PENDING_DELETION}")
    Page<Asset> findAssetsPendingDeletion(Pageable pageable);

    /**
     * Find assets that haven't been scanned recently
     * 
     * @param cutoffTime the cutoff time for considering assets stale
     * @param pageable pagination information
     * @return page of stale assets
     */
    @Query("SELECT a FROM Asset a WHERE a.dlapLastScannedTs < :cutoffTime OR a.dlapLastScannedTs IS NULL")
    Page<Asset> findStaleAssets(@Param("cutoffTime") Instant cutoffTime, Pageable pageable);

    /**
     * Find assets by owner info containing the given text
     * 
     * @param ownerPattern the pattern to search for in owner info
     * @param pageable pagination information
     * @return page of assets matching the owner pattern
     */
    Page<Asset> findByOwnerInfoContainingIgnoreCase(String ownerPattern, Pageable pageable);

    /**
     * Check if asset exists by URI
     * 
     * @param assetUri the asset URI
     * @return true if asset exists, false otherwise
     */
    boolean existsByAssetUri(String assetUri);

    /**
     * Count assets by cloud provider
     * 
     * @param cloudProvider the cloud provider
     * @return number of assets from the provider
     */
    long countByCloudProvider(String cloudProvider);

    /**
     * Count assets by service type
     * 
     * @param serviceType the service type
     * @return number of assets of the service type
     */
    long countByServiceType(String serviceType);

    /**
     * Count assets by status
     * 
     * @param status the asset status
     * @return number of assets with the status
     */
    long countByStatus(String status);

    /**
     * Count active assets
     * 
     * @return number of active assets
     */
    @Query("SELECT COUNT(a) FROM Asset a WHERE a.status = :#{T(com.dlap.common.constants.DlapConstants$AssetStatus).ACTIVE}")
    long countActiveAssets();

    /**
     * Find all distinct cloud providers
     * 
     * @return list of distinct cloud providers
     */
    @Query("SELECT DISTINCT a.cloudProvider FROM Asset a ORDER BY a.cloudProvider")
    List<String> findDistinctCloudProviders();

    /**
     * Find all distinct service types
     * 
     * @return list of distinct service types
     */
    @Query("SELECT DISTINCT a.serviceType FROM Asset a ORDER BY a.serviceType")
    List<String> findDistinctServiceTypes();

    /**
     * Find all distinct service types for a cloud provider
     * 
     * @param cloudProvider the cloud provider
     * @return list of distinct service types for the provider
     */
    @Query("SELECT DISTINCT a.serviceType FROM Asset a WHERE a.cloudProvider = :cloudProvider ORDER BY a.serviceType")
    List<String> findDistinctServiceTypesByProvider(@Param("cloudProvider") String cloudProvider);

    @Query("SELECT COUNT(DISTINCT ala.asset.assetId) FROM AssetLabelAssignment ala")
    long countDistinctAssetsWithLabelAssignments();

    @Query("SELECT COUNT(a) FROM Asset a WHERE a.technicalMetadata IS NOT NULL OR a.businessMetadataJson IS NOT NULL")
    long countAssetsWithAnyMetadata();

    @Query("SELECT COUNT(a) FROM Asset a WHERE a.businessMetadataJson IS NOT NULL OR (a.description IS NOT NULL AND a.description <> '')")
    long countAssetsWithBusinessContextSignals();

    @Query("SELECT COUNT(a) FROM Asset a WHERE a.dlapLineageInfo IS NOT NULL")
    long countAssetsWithLineageMetadata();
} 