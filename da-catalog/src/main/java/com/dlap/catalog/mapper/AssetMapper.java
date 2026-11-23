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

package com.dlap.catalog.mapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.dlap.catalog.dto.AssetActivityRowDTO;
import com.dlap.catalog.dto.AssetOutputDTO;
import com.dlap.common.model.entity.Asset;
import com.dlap.common.model.entity.AssetLabelAssignment;
import com.dlap.common.model.entity.Label;
import com.dlap.common.repository.IAssetLabelAssignmentRepository;

/**
 * Maps {@link Asset} entities to {@link AssetOutputDTO} objects for REST responses.
 *
 * <p>Label names on the DTO are resolved by looking up
 * {@link AssetLabelAssignment} records via the shared repository. Callers that
 * need to return a page of assets already pay one label-assignment query per
 * asset today; a future optimization is to batch-load assignments by asset id
 * list.
 */
@Component
public class AssetMapper {

    private static final Logger log = LoggerFactory.getLogger(AssetMapper.class);

    private final IAssetLabelAssignmentRepository assetLabelAssignmentRepository;

    @Autowired
    public AssetMapper(IAssetLabelAssignmentRepository assetLabelAssignmentRepository) {
        this.assetLabelAssignmentRepository = assetLabelAssignmentRepository;
    }

    public AssetOutputDTO toAssetOutputDTO(Asset asset) {
        if (asset == null) {
            return null;
        }

        AssetOutputDTO dto = new AssetOutputDTO();
        dto.setAssetId(asset.getAssetId());
        dto.setAssetName(asset.getAssetName());
        dto.setCloudConnectionId(asset.getCloudConnectionId());
        dto.setCloudProvider(asset.getCloudProvider());
        dto.setAssetType(asset.getAssetType());
        dto.setNativeAssetId(asset.getNativeAssetId());
        dto.setAssetUri(asset.getAssetUri());
        dto.setRegion(asset.getRegion());
        dto.setDetailsJson(asset.getDetailsJson());
        dto.setDiscoveredAt(asset.getDiscoveredAt());
        dto.setLastScannedAt(asset.getLastScannedAt());
        dto.setCreatedByUserId(asset.getCreatedByUserId());
        dto.setCreatedAt(asset.getCreatedTs());
        dto.setUpdatedAt(asset.getLastModifiedTs());
        dto.setBusinessMetadataJson(asset.getBusinessMetadataJson());
        dto.setDescription(asset.getDescription());
        dto.setTechnicalMetadata(asset.getTechnicalMetadata());
        dto.setServiceType(asset.getServiceType());
        dto.setStatus(asset.getStatus());

        // ADR-0046: stored values only. A null is unknown and stays null.
        dto.setCloudCreatedAt(asset.getCloudCreatedAt());
        dto.setCloudCreatedSource(asset.getCloudCreatedSource());
        dto.setSizeBytes(asset.getSizeBytes());
        dto.setSizeObservedAt(asset.getSizeObservedAt());
        dto.setLastAccessedAt(asset.getLastAccessedAt());
        dto.setLastAccessSource(asset.getLastAccessSource());
        dto.setLastModifiedAt(asset.getLastModifiedAt());
        dto.setLastPermissionChangeAt(asset.getLastPermissionChangeAt());
        dto.setLifecycleEventTime(asset.getLifecycleEventTime());
        dto.setRecreatedAt(asset.getRecreatedAt());
        dto.setDiscoverySource(asset.getDiscoverySource());
        dto.setRetentionDays(asset.getRetentionDays());
        dto.setRetentionBasis(asset.getRetentionBasis());
        dto.setRetentionSource(asset.getRetentionSource());
        dto.setRetentionPolicyId(asset.getRetentionPolicyId());
        dto.setRetentionExpiresAt(asset.getRetentionExpiresAt());
        dto.setRetentionStatus(asset.getRetentionStatus());
        dto.setRetentionEvaluatedAt(asset.getRetentionEvaluatedAt());
        dto.setLegalHold(asset.isLegalHold());

        // COV-01 (Q-479): the dlap_assets columns, so the event and PDP paths of the policy engine see them.
        dto.setReadCoverage(readCoverageOf(asset));
        dto.setReadCoverageReasons(readCoverageReasonsOf(asset));
        dto.setReadCoverageObservedAt(asset.getReadCoverageObservedAt());
        dto.setReadCoverageSince(readCoverageSinceOf(asset));

        dto.setLabels(loadLabelNames(asset));

        return dto;
    }

    /**
     * Builds one row of the activity report.
     *
     * <p>Nothing is invented: an unknown time, size or coverage stays {@code null}. The day counts
     * are whole 24-hour days against {@code asOf}, and are {@code null} when their base time is
     * unknown or lies after {@code asOf} (the stored value is newer than the report instant, so
     * the as-of view cannot be reconstructed). {@code idleDays} is never derived from the
     * creation time: an asset with no recorded read is not "idle since creation" unless read
     * coverage proves it, and that judgement belongs to the retention evaluator.
     *
     * @param asset  the stored asset, never {@code null}
     * @param labels the names of the labels assigned to the asset; {@code null} means none
     * @param asOf   the instant the derived values are computed against, never {@code null}
     * @return the row
     */
    public AssetActivityRowDTO toAssetActivityRowDTO(Asset asset, List<String> labels, Instant asOf) {
        return new AssetActivityRowDTO(
                asset.getAssetId(),
                asset.getAssetUri(),
                asset.getAssetName(),
                asset.getCloudProvider(),
                asset.getServiceType(),
                asset.getAssetType(),
                asset.getRegion(),
                asset.getStatus(),
                asset.getOwnerInfo(),
                asset.getDiscoveredAt(),
                asset.getDiscoverySource(),
                asset.getCloudCreatedAt(),
                asset.getCloudCreatedSource(),
                wholeDaysUntil(asset.getCloudCreatedAt(), asOf),
                asset.getSizeBytes(),
                asset.getSizeObservedAt(),
                asset.getLastAccessedAt(),
                asset.getLastAccessSource(),
                wholeDaysUntil(asset.getLastAccessedAt(), asOf),
                readCoverageOf(asset),
                readCoverageReasonsOf(asset),
                asset.getReadCoverageObservedAt(),
                readCoverageSinceOf(asset),
                asset.getLastModifiedAt(),
                asset.getLastPermissionChangeAt(),
                asset.getRecreatedAt(),
                asset.getRetentionStatus(),
                asset.getRetentionExpiresAt(),
                asset.getRetentionEvaluatedAt(),
                asset.getRetentionDays(),
                asset.getRetentionBasis(),
                asset.getRetentionSource(),
                asset.getRetentionPolicyId(),
                asset.isLegalHold(),
                labels,
                asOf);
    }

    /**
     * Whole 24-hour days from {@code from} to {@code asOf}, rounded down.
     *
     * @param from the base time; may be {@code null}
     * @param asOf the report instant
     * @return the day count, or {@code null} when {@code from} is unknown or after {@code asOf}
     */
    static Long wholeDaysUntil(Instant from, Instant asOf) {
        if (from == null || asOf == null || from.isAfter(asOf)) {
            return null;
        }
        return ChronoUnit.DAYS.between(from, asOf);
    }

    /**
     * The read-logging coverage stored for an asset ({@code dlap_assets.read_coverage}, COV-01), or
     * {@code null} when the catalog holds none. Unknown is not defaulted to {@code NONE}.
     *
     * @param asset the stored asset
     * @return {@code FULL}, {@code PARTIAL} or {@code NONE}; {@code null} when unknown
     */
    static String readCoverageOf(Asset asset) {
        String value = asset.getReadCoverage();
        return value == null || value.isBlank() ? null : value.strip().toUpperCase(Locale.ROOT);
    }

    /**
     * The start of the current run of {@code FULL} coverage ({@code dlap_assets.read_coverage_since},
     * Q-434). It is returned only while the stored coverage is {@code FULL}: the coverage applier
     * clears it for any other level, and this guard keeps a stray stored value from ever reading as
     * "reads have been recorded since" for an asset that is not fully covered.
     *
     * @param asset the stored asset
     * @return the instant, or {@code null} when coverage is not {@code FULL} or no start is stored
     */
    static Instant readCoverageSinceOf(Asset asset) {
        return "FULL".equals(readCoverageOf(asset)) ? asset.getReadCoverageSince() : null;
    }

    /**
     * The reasons stored with the coverage, or {@code null} when no observation was applied.
     *
     * @param asset the stored asset
     * @return the reasons, in stored order
     */
    static List<String> readCoverageReasonsOf(Asset asset) {
        List<String> reasons = asset.getReadCoverageReasons();
        return reasons == null ? null : List.copyOf(reasons);
    }

    public List<AssetOutputDTO> toAssetOutputDTOList(List<Asset> assets) {
        if (assets == null) {
            return Collections.emptyList();
        }
        return assets.stream()
                .map(this::toAssetOutputDTO)
                .collect(Collectors.toList());
    }

    /**
     * Loads the distinct label names assigned to the given asset. Failures to
     * load labels are logged and treated as "no labels" so that a label-lookup
     * problem does not break the asset read API.
     */
    private List<String> loadLabelNames(Asset asset) {
        if (asset == null || asset.getAssetId() == null) {
            return Collections.emptyList();
        }
        try {
            List<AssetLabelAssignment> assignments =
                    assetLabelAssignmentRepository.findByAssetAssetId(asset.getAssetId());
            if (assignments == null || assignments.isEmpty()) {
                return Collections.emptyList();
            }
            return assignments.stream()
                    .map(AssetLabelAssignment::getLabel)
                    .filter(label -> label != null && label.getLabelName() != null)
                    .map(Label::getLabelName)
                    .distinct()
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Failed to load label assignments for asset {}: {}", asset.getAssetId(), e.getMessage());
            return Collections.emptyList();
        }
    }
}
