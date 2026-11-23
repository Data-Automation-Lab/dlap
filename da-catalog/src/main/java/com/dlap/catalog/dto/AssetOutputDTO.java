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

package com.dlap.catalog.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.dlap.common.model.RetentionBasis;
import com.dlap.common.model.RetentionStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Asset as the catalog API returns it. The time, size and retention fields follow ADR-0046 and
 * {@code docs/specs/asset-descriptor.md}: every time is a business time taken from provider
 * evidence (except {@code retentionEvaluatedAt}), and a field that is unknown is omitted, never
 * guessed. Unknown is not stale and not fresh.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AssetOutputDTO {

    private UUID assetId;
    private String assetName;
    private UUID cloudConnectionId;
    private String cloudProvider;
    private String assetType;
    private String nativeAssetId;
    /**
     * Canonical cloud locator URI for the asset (e.g. {@code gs://bucket/key},
     * {@code bq://project/dataset/table}, {@code s3://bucket/key}). Copied from
     * {@code Asset.assetUri}. Cross-service consumers (e.g. da-autodelete) need
     * this to route deletion SDK calls to the correct provider location.
     */
    private String assetUri;
    private String region;
    private Map<String, Object> detailsJson; // Or a more structured DTO if details are well-defined
    private Instant discoveredAt;
    private Instant lastScannedAt;
    private UUID createdByUserId; // Or a UserSummaryDTO
    private Instant createdAt;
    private Instant updatedAt;
    private List<String> labels; // List of applied label names
    private Map<String, Object> businessMetadataJson;
    private String description;
    private Map<String, Object> technicalMetadata;
    private String serviceType;
    // private List<LabelDTO> labels; // Or list of LabelDTOs for more details

    /** Lifecycle status: ACTIVE, ARCHIVED, PENDING_DELETION or DELETED. */
    private String status;

    // ---- Creation and size (ADR-0046) ----
    /** When the provider created the current incarnation of the asset. Not {@code discoveredAt}. */
    private Instant cloudCreatedAt;
    /** Evidence channel for {@code cloudCreatedAt}, for example {@code CRAWLER}. */
    private String cloudCreatedSource;
    /** Latest observed size in bytes; absent when unknown, {@code 0} is a real size. */
    private Long sizeBytes;
    /** Business time at which {@code sizeBytes} was true. */
    private Instant sizeObservedAt;

    // ---- Activity (ADR-0046) ----
    /** Latest event time of a successful data read. Absent when no read is recorded. */
    private Instant lastAccessedAt;
    /** Evidence channel for {@code lastAccessedAt}. */
    private String lastAccessSource;
    /** Latest event time of a successful content write. Not {@code updatedAt}, the row time. */
    private Instant lastModifiedAt;
    /** Latest event time of a successful access-control change. */
    private Instant lastPermissionChangeAt;

    // ---- Lifecycle (ADR-0046) ----
    /** Event time of the last status transition applied. */
    private Instant lifecycleEventTime;
    /** Event time of a CREATE applied to a DELETED asset (name reuse). */
    private Instant recreatedAt;
    /** Channel through which DLAP first learned of the asset. */
    private String discoverySource;

    // ---- Retention (ADR-0046): evaluation output, legalHold is the one input ----
    /** Period in days of the rule that decided the last evaluation. */
    private Integer retentionDays;
    /** Basis of the deciding rule. */
    private RetentionBasis retentionBasis;
    /** Source of the deciding rule: POLICY, LABEL_DEFAULT, PROVIDER or MANUAL. */
    private String retentionSource;
    /** Policy whose {@code setRetention} action is the deciding rule, when the source is POLICY. */
    private UUID retentionPolicyId;
    /** The expiry the last evaluation found, exact or the proving bound. */
    private Instant retentionExpiresAt;
    /** Retention state as of {@code retentionEvaluatedAt}; never null, UNKNOWN by default. */
    private RetentionStatus retentionStatus;
    /** As-of instant of the last retention evaluation. */
    private Instant retentionEvaluatedAt;
    /** DLAP legal hold; while true the retention status is ON_HOLD. */
    private boolean legalHold;

    // ---- Read coverage (COV-01, Q-479) ----
    /**
     * Read-logging coverage from {@code dlap_assets.read_coverage}: {@code FULL}, {@code PARTIAL} or
     * {@code NONE}. Absent when the catalog holds none (unknown is never defaulted to {@code NONE}).
     * Only {@code FULL} lets a lifecycle rule or an access policy treat a missing read as "not read".
     */
    private String readCoverage;
    /** Why the coverage is what it is: the gaps, or the evidence for {@code FULL}. Absent when unknown. */
    private List<String> readCoverageReasons;
    /**
     * When the provider configuration behind {@code readCoverage} was read, by the collector's clock
     * (Q-475). Absent when no observation was applied. A consumer that acts on {@code FULL} compares it
     * with its own clock: a collector run that failed as a whole leaves the last value in place.
     */
    private Instant readCoverageObservedAt;
    /**
     * First observation of the current unbroken run of {@code FULL} observations (Q-434, Q-444): reads
     * are known to be recorded from here on. Absent unless {@code readCoverage} is {@code FULL}.
     */
    private Instant readCoverageSince;

    // Getters and Setters
    public UUID getAssetId() {
        return assetId;
    }

    public void setAssetId(UUID assetId) {
        this.assetId = assetId;
    }

    public String getAssetName() {
        return assetName;
    }

    public void setAssetName(String assetName) {
        this.assetName = assetName;
    }

    public UUID getCloudConnectionId() {
        return cloudConnectionId;
    }

    public void setCloudConnectionId(UUID cloudConnectionId) {
        this.cloudConnectionId = cloudConnectionId;
    }

    public String getCloudProvider() {
        return cloudProvider;
    }

    public void setCloudProvider(String cloudProvider) {
        this.cloudProvider = cloudProvider;
    }

    public String getAssetType() {
        return assetType;
    }

    public void setAssetType(String assetType) {
        this.assetType = assetType;
    }

    public String getNativeAssetId() {
        return nativeAssetId;
    }

    public void setNativeAssetId(String nativeAssetId) {
        this.nativeAssetId = nativeAssetId;
    }

    public String getAssetUri() {
        return assetUri;
    }

    public void setAssetUri(String assetUri) {
        this.assetUri = assetUri;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public Map<String, Object> getDetailsJson() {
        return detailsJson;
    }

    public void setDetailsJson(Map<String, Object> detailsJson) {
        this.detailsJson = detailsJson;
    }

    public Instant getDiscoveredAt() {
        return discoveredAt;
    }

    public void setDiscoveredAt(Instant discoveredAt) {
        this.discoveredAt = discoveredAt;
    }

    public Instant getLastScannedAt() {
        return lastScannedAt;
    }

    public void setLastScannedAt(Instant lastScannedAt) {
        this.lastScannedAt = lastScannedAt;
    }

    public UUID getCreatedByUserId() {
        return createdByUserId;
    }

    public void setCreatedByUserId(UUID createdByUserId) {
        this.createdByUserId = createdByUserId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public List<String> getLabels() {
        return labels;
    }

    public void setLabels(List<String> labels) {
        this.labels = labels;
    }

    public Map<String, Object> getBusinessMetadataJson() {
        return businessMetadataJson;
    }

    public void setBusinessMetadataJson(Map<String, Object> businessMetadataJson) {
        this.businessMetadataJson = businessMetadataJson;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Map<String, Object> getTechnicalMetadata() {
        return technicalMetadata;
    }

    public void setTechnicalMetadata(Map<String, Object> technicalMetadata) {
        this.technicalMetadata = technicalMetadata;
    }

    public String getServiceType() {
        return serviceType;
    }

    public void setServiceType(String serviceType) {
        this.serviceType = serviceType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getCloudCreatedAt() {
        return cloudCreatedAt;
    }

    public void setCloudCreatedAt(Instant cloudCreatedAt) {
        this.cloudCreatedAt = cloudCreatedAt;
    }

    public String getCloudCreatedSource() {
        return cloudCreatedSource;
    }

    public void setCloudCreatedSource(String cloudCreatedSource) {
        this.cloudCreatedSource = cloudCreatedSource;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(Long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public Instant getSizeObservedAt() {
        return sizeObservedAt;
    }

    public void setSizeObservedAt(Instant sizeObservedAt) {
        this.sizeObservedAt = sizeObservedAt;
    }

    public Instant getLastAccessedAt() {
        return lastAccessedAt;
    }

    public void setLastAccessedAt(Instant lastAccessedAt) {
        this.lastAccessedAt = lastAccessedAt;
    }

    public String getLastAccessSource() {
        return lastAccessSource;
    }

    public void setLastAccessSource(String lastAccessSource) {
        this.lastAccessSource = lastAccessSource;
    }

    public Instant getLastModifiedAt() {
        return lastModifiedAt;
    }

    public void setLastModifiedAt(Instant lastModifiedAt) {
        this.lastModifiedAt = lastModifiedAt;
    }

    public Instant getLastPermissionChangeAt() {
        return lastPermissionChangeAt;
    }

    public void setLastPermissionChangeAt(Instant lastPermissionChangeAt) {
        this.lastPermissionChangeAt = lastPermissionChangeAt;
    }

    public Instant getLifecycleEventTime() {
        return lifecycleEventTime;
    }

    public void setLifecycleEventTime(Instant lifecycleEventTime) {
        this.lifecycleEventTime = lifecycleEventTime;
    }

    public Instant getRecreatedAt() {
        return recreatedAt;
    }

    public void setRecreatedAt(Instant recreatedAt) {
        this.recreatedAt = recreatedAt;
    }

    public String getDiscoverySource() {
        return discoverySource;
    }

    public void setDiscoverySource(String discoverySource) {
        this.discoverySource = discoverySource;
    }

    public Integer getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(Integer retentionDays) {
        this.retentionDays = retentionDays;
    }

    public RetentionBasis getRetentionBasis() {
        return retentionBasis;
    }

    public void setRetentionBasis(RetentionBasis retentionBasis) {
        this.retentionBasis = retentionBasis;
    }

    public String getRetentionSource() {
        return retentionSource;
    }

    public void setRetentionSource(String retentionSource) {
        this.retentionSource = retentionSource;
    }

    public UUID getRetentionPolicyId() {
        return retentionPolicyId;
    }

    public void setRetentionPolicyId(UUID retentionPolicyId) {
        this.retentionPolicyId = retentionPolicyId;
    }

    public Instant getRetentionExpiresAt() {
        return retentionExpiresAt;
    }

    public void setRetentionExpiresAt(Instant retentionExpiresAt) {
        this.retentionExpiresAt = retentionExpiresAt;
    }

    public RetentionStatus getRetentionStatus() {
        return retentionStatus;
    }

    public void setRetentionStatus(RetentionStatus retentionStatus) {
        this.retentionStatus = retentionStatus;
    }

    public Instant getRetentionEvaluatedAt() {
        return retentionEvaluatedAt;
    }

    public void setRetentionEvaluatedAt(Instant retentionEvaluatedAt) {
        this.retentionEvaluatedAt = retentionEvaluatedAt;
    }

    public String getReadCoverage() {
        return readCoverage;
    }

    public void setReadCoverage(String readCoverage) {
        this.readCoverage = readCoverage;
    }

    public List<String> getReadCoverageReasons() {
        return readCoverageReasons;
    }

    public void setReadCoverageReasons(List<String> readCoverageReasons) {
        this.readCoverageReasons = readCoverageReasons;
    }

    public Instant getReadCoverageObservedAt() {
        return readCoverageObservedAt;
    }

    public void setReadCoverageObservedAt(Instant readCoverageObservedAt) {
        this.readCoverageObservedAt = readCoverageObservedAt;
    }

    public Instant getReadCoverageSince() {
        return readCoverageSince;
    }

    public void setReadCoverageSince(Instant readCoverageSince) {
        this.readCoverageSince = readCoverageSince;
    }

    public boolean isLegalHold() {
        return legalHold;
    }

    public void setLegalHold(boolean legalHold) {
        this.legalHold = legalHold;
    }
}
