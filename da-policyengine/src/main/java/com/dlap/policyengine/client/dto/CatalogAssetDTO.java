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

package com.dlap.policyengine.client.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Subset of da-catalog {@code AssetOutputDTO} JSON ({@code GET /api/v1/catalog/assets/{id}}) for
 * Feign deserialization in the policy engine.
 *
 * <p>POL-01 adds the identity and lifecycle fields the DSL evaluates and the action contract
 * carries: {@code assetUri}, {@code serviceType}, {@code status}, the ADR-0046 event-time fields,
 * read coverage, legal hold and retention state. A field the catalog does not send stays null, and
 * null means unknown, never zero or never-read. POL-03 adds the coverage observation time and the
 * coverage start (Q-837, Q-434), so the event and PDP paths apply the same freshness rule as the
 * sweep.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CatalogAssetDTO {

    /** Catalog asset id. */
    private UUID assetId;

    /** Display name. */
    private String assetName;

    /** Cloud connection the asset was discovered through. */
    private UUID cloudConnectionId;

    /** Cloud provider, for example {@code GCP}. */
    private String cloudProvider;

    /** Asset type, for example {@code GCS_BUCKET}. */
    private String assetType;

    /** Asset kind ({@code serviceType}), for example {@code GCS_BUCKET}. */
    private String serviceType;

    /** Provider-native identifier. */
    private String nativeAssetId;

    /** Canonical asset URI (ADR-0047). */
    private String assetUri;

    /** Region or location. */
    private String region;

    /** Lifecycle status: ACTIVE, ARCHIVED, PENDING_DELETION or DELETED. */
    private String status;

    /** Free-form discovery details. */
    private Map<String, Object> detailsJson;

    /** Technical metadata; carries {@code readCoverage} until it has a column. */
    private Map<String, Object> technicalMetadata;

    /** When DLAP first saw the asset (not the provider creation time). */
    private Instant discoveredAt;

    /** Last crawler scan. */
    private Instant lastScannedAt;

    /** Provider creation time of the current incarnation (ADR-0046); null when unknown. */
    private Instant cloudCreatedAt;

    /** Latest observed size in bytes; null when unknown. */
    private Long sizeBytes;

    /** Event time of the latest recorded successful read; null when none is recorded. */
    private Instant lastAccessedAt;

    /** Event time of the latest recorded successful content write. */
    private Instant lastModifiedAt;

    /** Read-logging coverage ({@code FULL}, {@code PARTIAL}, {@code NONE}) when the catalog serves it as a field. */
    private String readCoverage;

    /**
     * When the catalog last observed {@code readCoverage} (ISO-8601 text; COV-02, ADR-0129). Read as
     * text, like the activity row's field, so an unexpected value never fails the asset read;
     * {@code AssetSnapshots} parses it and an unparseable value counts as stale (POL-03, Q-837).
     */
    private String readCoverageObservedAt;

    /**
     * Start of the current unbroken run of {@code FULL} coverage (ISO-8601 text; COV-02, ADR-0129).
     * The catalog sends it only while coverage is {@code FULL}. Read as text; an unparseable value
     * counts as absent (POL-03, Q-434).
     */
    private String readCoverageSince;

    /** DLAP legal hold flag. */
    private Boolean legalHold;

    /** Stored retention status (UNKNOWN, NO_POLICY, RETAINED, DUE, ON_HOLD). */
    private String retentionStatus;

    /** Expiry the last retention evaluation found. */
    private Instant retentionExpiresAt;

    /** Names of the labels assigned to the asset. */
    private List<String> labels;

    /** Business metadata. */
    private Map<String, Object> businessMetadataJson;

    /**
     * Returns the assetId field.
     *
     * @return catalog asset id.
     */
    public UUID getAssetId() {
        return assetId;
    }

    /**
     * Sets the assetId field.
     *
     * @param assetId the value
     */
    public void setAssetId(UUID assetId) {
        this.assetId = assetId;
    }

    /**
     * Returns the assetName field.
     *
     * @return display name.
     */
    public String getAssetName() {
        return assetName;
    }

    /**
     * Sets the assetName field.
     *
     * @param assetName the value
     */
    public void setAssetName(String assetName) {
        this.assetName = assetName;
    }

    /**
     * Returns the cloudConnectionId field.
     *
     * @return cloud connection the asset was discovered through.
     */
    public UUID getCloudConnectionId() {
        return cloudConnectionId;
    }

    /**
     * Sets the cloudConnectionId field.
     *
     * @param cloudConnectionId the value
     */
    public void setCloudConnectionId(UUID cloudConnectionId) {
        this.cloudConnectionId = cloudConnectionId;
    }

    /**
     * Returns the cloudProvider field.
     *
     * @return cloud provider, for example {@code GCP}.
     */
    public String getCloudProvider() {
        return cloudProvider;
    }

    /**
     * Sets the cloudProvider field.
     *
     * @param cloudProvider the value
     */
    public void setCloudProvider(String cloudProvider) {
        this.cloudProvider = cloudProvider;
    }

    /**
     * Returns the assetType field.
     *
     * @return asset type, for example {@code GCS_BUCKET}.
     */
    public String getAssetType() {
        return assetType;
    }

    /**
     * Sets the assetType field.
     *
     * @param assetType the value
     */
    public void setAssetType(String assetType) {
        this.assetType = assetType;
    }

    /**
     * Returns the serviceType field.
     *
     * @return asset kind ({@code serviceType}), for example {@code GCS_BUCKET}.
     */
    public String getServiceType() {
        return serviceType;
    }

    /**
     * Sets the serviceType field.
     *
     * @param serviceType the value
     */
    public void setServiceType(String serviceType) {
        this.serviceType = serviceType;
    }

    /**
     * Returns the nativeAssetId field.
     *
     * @return provider-native identifier.
     */
    public String getNativeAssetId() {
        return nativeAssetId;
    }

    /**
     * Sets the nativeAssetId field.
     *
     * @param nativeAssetId the value
     */
    public void setNativeAssetId(String nativeAssetId) {
        this.nativeAssetId = nativeAssetId;
    }

    /**
     * Returns the assetUri field.
     *
     * @return canonical asset URI (ADR-0047).
     */
    public String getAssetUri() {
        return assetUri;
    }

    /**
     * Sets the assetUri field.
     *
     * @param assetUri the value
     */
    public void setAssetUri(String assetUri) {
        this.assetUri = assetUri;
    }

    /**
     * Returns the region field.
     *
     * @return region or location.
     */
    public String getRegion() {
        return region;
    }

    /**
     * Sets the region field.
     *
     * @param region the value
     */
    public void setRegion(String region) {
        this.region = region;
    }

    /**
     * Returns the status field.
     *
     * @return lifecycle status: ACTIVE, ARCHIVED, PENDING_DELETION or DELETED.
     */
    public String getStatus() {
        return status;
    }

    /**
     * Sets the status field.
     *
     * @param status the value
     */
    public void setStatus(String status) {
        this.status = status;
    }

    /**
     * Returns the detailsJson field.
     *
     * @return free-form discovery details.
     */
    public Map<String, Object> getDetailsJson() {
        return detailsJson;
    }

    /**
     * Sets the detailsJson field.
     *
     * @param detailsJson the value
     */
    public void setDetailsJson(Map<String, Object> detailsJson) {
        this.detailsJson = detailsJson;
    }

    /**
     * Returns the technicalMetadata field.
     *
     * @return technical metadata; carries {@code readCoverage} until it has a column.
     */
    public Map<String, Object> getTechnicalMetadata() {
        return technicalMetadata;
    }

    /**
     * Sets the technicalMetadata field.
     *
     * @param technicalMetadata the value
     */
    public void setTechnicalMetadata(Map<String, Object> technicalMetadata) {
        this.technicalMetadata = technicalMetadata;
    }

    /**
     * Returns the discoveredAt field.
     *
     * @return when DLAP first saw the asset (not the provider creation time).
     */
    public Instant getDiscoveredAt() {
        return discoveredAt;
    }

    /**
     * Sets the discoveredAt field.
     *
     * @param discoveredAt the value
     */
    public void setDiscoveredAt(Instant discoveredAt) {
        this.discoveredAt = discoveredAt;
    }

    /**
     * Returns the lastScannedAt field.
     *
     * @return last crawler scan.
     */
    public Instant getLastScannedAt() {
        return lastScannedAt;
    }

    /**
     * Sets the lastScannedAt field.
     *
     * @param lastScannedAt the value
     */
    public void setLastScannedAt(Instant lastScannedAt) {
        this.lastScannedAt = lastScannedAt;
    }

    /**
     * Returns the cloudCreatedAt field.
     *
     * @return provider creation time of the current incarnation (ADR-0046); null when unknown.
     */
    public Instant getCloudCreatedAt() {
        return cloudCreatedAt;
    }

    /**
     * Sets the cloudCreatedAt field.
     *
     * @param cloudCreatedAt the value
     */
    public void setCloudCreatedAt(Instant cloudCreatedAt) {
        this.cloudCreatedAt = cloudCreatedAt;
    }

    /**
     * Returns the sizeBytes field.
     *
     * @return latest observed size in bytes; null when unknown.
     */
    public Long getSizeBytes() {
        return sizeBytes;
    }

    /**
     * Sets the sizeBytes field.
     *
     * @param sizeBytes the value
     */
    public void setSizeBytes(Long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    /**
     * Returns the lastAccessedAt field.
     *
     * @return event time of the latest recorded successful read; null when none is recorded.
     */
    public Instant getLastAccessedAt() {
        return lastAccessedAt;
    }

    /**
     * Sets the lastAccessedAt field.
     *
     * @param lastAccessedAt the value
     */
    public void setLastAccessedAt(Instant lastAccessedAt) {
        this.lastAccessedAt = lastAccessedAt;
    }

    /**
     * Returns the lastModifiedAt field.
     *
     * @return event time of the latest recorded successful content write.
     */
    public Instant getLastModifiedAt() {
        return lastModifiedAt;
    }

    /**
     * Sets the lastModifiedAt field.
     *
     * @param lastModifiedAt the value
     */
    public void setLastModifiedAt(Instant lastModifiedAt) {
        this.lastModifiedAt = lastModifiedAt;
    }

    /**
     * Returns the readCoverage field.
     *
     * @return read-logging coverage ({@code FULL}, {@code PARTIAL}, {@code NONE}) when the catalog serves it as a field.
     */
    public String getReadCoverage() {
        return readCoverage;
    }

    /**
     * Sets the readCoverage field.
     *
     * @param readCoverage the value
     */
    public void setReadCoverage(String readCoverage) {
        this.readCoverage = readCoverage;
    }

    /**
     * Returns the readCoverageObservedAt field.
     *
     * @return when the catalog last observed the read coverage, as the text it sent; null when unknown
     */
    public String getReadCoverageObservedAt() {
        return readCoverageObservedAt;
    }

    /**
     * Sets the readCoverageObservedAt field.
     *
     * @param readCoverageObservedAt the value
     */
    public void setReadCoverageObservedAt(String readCoverageObservedAt) {
        this.readCoverageObservedAt = readCoverageObservedAt;
    }

    /**
     * Returns the readCoverageSince field.
     *
     * @return start of the current run of FULL coverage, as the text the catalog sent; null when unknown
     */
    public String getReadCoverageSince() {
        return readCoverageSince;
    }

    /**
     * Sets the readCoverageSince field.
     *
     * @param readCoverageSince the value
     */
    public void setReadCoverageSince(String readCoverageSince) {
        this.readCoverageSince = readCoverageSince;
    }

    /**
     * Returns the legalHold field.
     *
     * @return dLAP legal hold flag.
     */
    public Boolean getLegalHold() {
        return legalHold;
    }

    /**
     * Sets the legalHold field.
     *
     * @param legalHold the value
     */
    public void setLegalHold(Boolean legalHold) {
        this.legalHold = legalHold;
    }

    /**
     * Returns the retentionStatus field.
     *
     * @return stored retention status (UNKNOWN, NO_POLICY, RETAINED, DUE, ON_HOLD).
     */
    public String getRetentionStatus() {
        return retentionStatus;
    }

    /**
     * Sets the retentionStatus field.
     *
     * @param retentionStatus the value
     */
    public void setRetentionStatus(String retentionStatus) {
        this.retentionStatus = retentionStatus;
    }

    /**
     * Returns the retentionExpiresAt field.
     *
     * @return expiry the last retention evaluation found.
     */
    public Instant getRetentionExpiresAt() {
        return retentionExpiresAt;
    }

    /**
     * Sets the retentionExpiresAt field.
     *
     * @param retentionExpiresAt the value
     */
    public void setRetentionExpiresAt(Instant retentionExpiresAt) {
        this.retentionExpiresAt = retentionExpiresAt;
    }

    /**
     * Returns the labels field.
     *
     * @return names of the labels assigned to the asset.
     */
    public List<String> getLabels() {
        return labels;
    }

    /**
     * Sets the labels field.
     *
     * @param labels the value
     */
    public void setLabels(List<String> labels) {
        this.labels = labels;
    }

    /**
     * Returns the businessMetadataJson field.
     *
     * @return business metadata.
     */
    public Map<String, Object> getBusinessMetadataJson() {
        return businessMetadataJson;
    }

    /**
     * Sets the businessMetadataJson field.
     *
     * @param businessMetadataJson the value
     */
    public void setBusinessMetadataJson(Map<String, Object> businessMetadataJson) {
        this.businessMetadataJson = businessMetadataJson;
    }
}
