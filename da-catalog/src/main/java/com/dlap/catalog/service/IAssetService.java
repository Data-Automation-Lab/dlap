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

package com.dlap.catalog.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.dlap.catalog.dto.AssetActivityPageDTO;
import com.dlap.catalog.dto.AssetActivityRowDTO;
import com.dlap.catalog.dto.AssetComplianceStatusDTO;
import com.dlap.catalog.dto.AssetInputDTO;
import com.dlap.catalog.dto.AssetLabelsPostRequestDTO;
import com.dlap.catalog.dto.AssetOutputDTO;
import com.dlap.catalog.dto.AssetPolicyMappingDTO;
import com.dlap.catalog.dto.AssetSchemaDTO;
import com.dlap.catalog.dto.AssetUsageAnalyticsDTO;
import com.dlap.catalog.dto.BusinessMetadataInputDTO;
import com.dlap.catalog.dto.BusinessMetadataResponseDTO;
import com.dlap.catalog.dto.CatalogFiltersDTO;
import com.dlap.catalog.dto.EnhancedLineageDTO;
import com.dlap.catalog.dto.LabelOutputDTO;
import com.dlap.catalog.dto.LineageResponseDTO;

public interface IAssetService {

    Page<AssetOutputDTO> getAllAssets(
        Pageable pageable,
        String cloudProvider,
        String assetType,
        String region,
        List<String> labels, // Filter by assets having ALL these labels
        String nameContains,
        UUID connectionId
        // Add other filter parameters as needed
    );

    AssetOutputDTO getAssetById(UUID assetId);

    /**
     * Resolve an asset URI to its catalog id (the join key), or empty if unknown.
     *
     * <p>The input is normalized through {@code AssetIdNormalizer} (ADR-0047), so any alias of a
     * resource finds its row: {@code projects/_/buckets/b} finds {@code gs://b}. A row stored in
     * an older form is found through {@code normalize(stored asset_uri)} as the event consumers
     * do (ADR-0055). An input that does not normalize is looked up as written. An object URI
     * finds a row only when the object has its own row (a governed prefix); it never resolves to
     * its container.
     *
     * @param assetUri the identifier in any supported form
     * @return the asset id, or empty when no row matches
     */
    Optional<UUID> resolveAssetIdByUri(String assetUri);

    /**
     * Filters of the activity query. Every field is optional: {@code null} or an empty list means
     * no filter on that dimension. Vocabulary values are validated by
     * {@link #getAssetActivity(ActivityQuery, Pageable)}.
     *
     * <p>An unknown value is never stale and never fresh (ADR-0046): {@code lastReadBefore} and
     * {@code lastReadAfter} match only assets whose last read is known. Use {@code lastReadKnown}
     * to ask for the assets whose last read is unknown.
     *
     * @param providers         cloud providers ({@code AWS}, {@code GCP}, ...), any of
     * @param kinds             asset kinds ({@code serviceType}, for example {@code GCS_BUCKET}), any of
     * @param statuses          lifecycle statuses ({@code ACTIVE}, {@code ARCHIVED},
     *                          {@code PENDING_DELETION}, {@code DELETED}), any of
     * @param lastReadBefore    only assets whose last read is strictly earlier than this instant
     * @param lastReadAfter     only assets whose last read is strictly later than this instant
     * @param lastReadKnown     {@code true}: only assets with a recorded last read; {@code false}:
     *                          only assets without one
     * @param retentionStatuses stored retention statuses ({@code UNKNOWN}, {@code NO_POLICY},
     *                          {@code RETAINED}, {@code DUE}, {@code ON_HOLD}), any of
     * @param legalHold         {@code true}: only held assets; {@code false}: only assets not held
     * @param readCoverages     read coverage ({@code FULL}, {@code PARTIAL}, {@code NONE}, or
     *                          {@code UNKNOWN} for an asset with none recorded), any of
     * @param asOf              the instant the derived day counts are computed against; the
     *                          service clock when {@code null}
     */
    record ActivityQuery(
            List<String> providers,
            List<String> kinds,
            List<String> statuses,
            Instant lastReadBefore,
            Instant lastReadAfter,
            Boolean lastReadKnown,
            List<String> retentionStatuses,
            Boolean legalHold,
            List<String> readCoverages,
            Instant asOf) {

        /**
         * Makes the lists immutable and never null.
         *
         * @param providers         cloud providers
         * @param kinds             asset kinds
         * @param statuses          lifecycle statuses
         * @param lastReadBefore    upper bound of the last read
         * @param lastReadAfter     lower bound of the last read
         * @param lastReadKnown     whether the last read must be known
         * @param retentionStatuses retention statuses
         * @param legalHold         legal-hold filter
         * @param readCoverages     read coverages
         * @param asOf              report instant
         */
        public ActivityQuery {
            providers = providers == null ? List.of() : List.copyOf(providers);
            kinds = kinds == null ? List.of() : List.copyOf(kinds);
            statuses = statuses == null ? List.of() : List.copyOf(statuses);
            retentionStatuses = retentionStatuses == null ? List.of() : List.copyOf(retentionStatuses);
            readCoverages = readCoverages == null ? List.of() : List.copyOf(readCoverages);
        }

        /**
         * A query with no filter, as of the service clock.
         *
         * @return the unfiltered query
         */
        public static ActivityQuery unfiltered() {
            return new ActivityQuery(null, null, null, null, null, null, null, null, null, null);
        }
    }

    /**
     * Pages the asset activity report: age, size, last use and retention of each asset, filtered
     * by {@code query} and computed as of {@code query.asOf()}.
     *
     * <p>Sorting accepts {@code assetUri}, {@code assetName}, {@code cloudProvider},
     * {@code serviceType}, {@code status}, {@code discoveredAt}, {@code cloudCreatedAt},
     * {@code sizeBytes}, {@code lastAccessedAt}, {@code lastModifiedAt}, {@code retentionStatus}
     * and {@code retentionExpiresAt}. Rows whose sort value is unknown come last in either
     * direction, and {@code assetUri} breaks ties, so a page is stable. The default order is
     * {@code assetUri} ascending.
     *
     * @param query    the filters and the report instant
     * @param pageable page number and size (capped at the configured maximum) and sort
     * @return one page of rows
     * @throws IllegalArgumentException for an unknown provider, status, retention status or read
     *                                  coverage, a malformed kind, or an unsupported sort property
     */
    Page<AssetActivityRowDTO> getAssetActivity(ActivityQuery query, Pageable pageable);

    /**
     * Keyset page of the asset activity report (Q-445, D45): the next {@code size} assets whose
     * {@code assetUri} sorts strictly after {@code after}, in {@code assetUri} ascending order, with
     * the same filters, row shape and report instant as
     * {@link #getAssetActivity(ActivityQuery, Pageable)}.
     *
     * <p>The cursor is a value, not a position, so rows written while a caller walks the report
     * cannot shift a page: an asset that stays in the table is returned exactly once. An asset
     * inserted ahead of the cursor is returned, one inserted behind it is not. {@code nextAfter} of
     * the result is the cursor of the next page and is {@code null} on the last page.
     *
     * @param query the filters and the report instant
     * @param after the {@code assetUri} of the last row already seen; {@code null} or blank starts
     *              at the first row
     * @param size  rows wanted, clamped to 1 and the configured maximum
     * @return one page, with {@code nextAfter}
     * @throws IllegalArgumentException for a bad filter value or a cursor longer than an asset URI
     */
    AssetActivityPageDTO getAssetActivityKeyset(ActivityQuery query, String after, int size);
    
    AssetOutputDTO createAsset(AssetInputDTO assetInputDTO, UUID creatorUserId);
    
    AssetOutputDTO updateBusinessMetadata(UUID assetId, Map<String, Object> businessMetadata, UUID updaterUserId);
    
    /**
     * Update enhanced business context and metadata for an asset.
     * Supports steward relationships, compliance classification, and business context.
     * 
     * @param assetId the ID of the asset to update
     * @param businessMetadataInput enhanced business metadata with steward relationships and compliance info
     * @param updaterUserId the ID of the user performing the update
     * @return enhanced business metadata response with validation status and recommendations
     */
    BusinessMetadataResponseDTO updateBusinessContext(UUID assetId, BusinessMetadataInputDTO businessMetadataInput, UUID updaterUserId);
    
    /**
     * Get comprehensive business metadata for an asset.
     * Includes business context, steward relationships, compliance classification,
     * audit information, and validation status.
     * 
     * @param assetId the ID of the asset
     * @return comprehensive business metadata response with audit and validation information
     */
    BusinessMetadataResponseDTO getBusinessMetadata(UUID assetId);
    
    LineageResponseDTO getAssetLineage(UUID assetId);
    
    List<LabelOutputDTO> getAssetLabels(UUID assetId);

    List<LabelOutputDTO> assignLabelsToAsset(UUID assetId, AssetLabelsPostRequestDTO labelsToAssign, UUID assignerUserId);

    void removeLabelFromAsset(UUID assetId, String labelName, UUID removerUserId);

    /**
     * Get comprehensive usage analytics for an asset.
     * Provides insights into access patterns, user behavior, performance metrics,
     * and optimization recommendations.
     * 
     * @param assetId the ID of the asset to analyze
     * @return comprehensive usage analytics including access patterns, user analytics, performance metrics, and recommendations
     */
    AssetUsageAnalyticsDTO getAssetUsageAnalytics(UUID assetId);

    /**
     * Get comprehensive asset schema information.
     * Priority 2 endpoint: Enhanced catalog functionality with detailed schema structure.
     * 
     * @param assetId UUID of the asset
     * @return AssetSchemaDTO with detailed schema information including fields, constraints, statistics, and lineage
     */
    AssetSchemaDTO getAssetSchema(UUID assetId);

    /**
     * Get enhanced data lineage visualization information.
     * Priority 2 endpoint: Provides detailed lineage with visualization metadata for graph rendering.
     * 
     * @param assetId UUID of the asset
     * @param maxDepth Maximum depth to traverse (optional, default 5)
     * @return EnhancedLineageDTO with graph nodes, edges, and visualization metadata
     */
    EnhancedLineageDTO getEnhancedLineage(UUID assetId, Integer maxDepth);

    /**
     * Get asset-policy mapping information.
     * Priority 2 endpoint: Shows which policies apply to an asset and their compliance status.
     * 
     * @param assetId UUID of the asset
     * @return AssetPolicyMappingDTO with applicable policies and compliance information
     */
    AssetPolicyMappingDTO getAssetPolicyMapping(UUID assetId);

    /**
     * Get catalog faceted search filters.
     * Priority 2 endpoint: Provides filter metadata for advanced search and filtering in the catalog UI.
     * 
     * @return CatalogFiltersDTO with filter categories, options, and search capabilities
     */
    CatalogFiltersDTO getCatalogFilters();

    /**
     * Get asset compliance status information.
     * Priority 2 endpoint: Provides detailed compliance status and monitoring for assets.
     * 
     * @param assetId UUID of the asset
     * @return AssetComplianceStatusDTO with framework compliance, violations, and recommendations
     */
    AssetComplianceStatusDTO getAssetComplianceStatus(UUID assetId);

    long countTotalAssets();

    long countClassifiedAssets();

    long countAssetsWithMetadata();

    long countAssetsWithBusinessContext();

    long countAssetsWithLineage();

    long countLineageConnections();

    long countAssetsByStatus(String status);

    Map<String, Object> getGovernanceIssuesSnapshot(String severityFilter);

    Map<String, Long> getClassificationBreakdown();

    // Placeholder for creating/updating assets, to be defined by POST/PUT API tasks
    // AssetOutputDTO createAsset(AssetInputDTO assetInputDTO);
    // AssetOutputDTO updateAsset(UUID assetId, AssetUpdateDTO assetUpdateDTO);
} 