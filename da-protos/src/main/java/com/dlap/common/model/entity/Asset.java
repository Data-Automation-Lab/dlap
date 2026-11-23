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

package com.dlap.common.model.entity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.dlap.common.model.RetentionBasis;
import com.dlap.common.model.RetentionStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DLAP Asset Entity
 * 
 * Central repository for metadata about discovered data assets.
 * Represents data assets from various cloud providers with their technical and business metadata.
 * 
 * <p>The time, size and retention fields follow ADR-0046 (spec {@code docs/specs/asset-descriptor.md}):
 * every one of them except {@link #retentionEvaluatedAt} is a business time taken from provider
 * evidence, {@code null} means unknown, and unknown is never treated as stale or as fresh.
 *
 * @author DLAP Team
 * @version 1.0
 * @since 1.0
 */
@Entity
@Table(name = "dlap_assets", schema = "dlap_common",
       indexes = {
           @Index(name = "idx_asset_uri", columnList = "assetUri", unique = true),
           @Index(name = "idx_asset_cloud_provider", columnList = "cloudProvider"),
           @Index(name = "idx_asset_service_type", columnList = "serviceType"),
           @Index(name = "idx_asset_status", columnList = "status"),
           @Index(name = "idx_asset_name", columnList = "assetName"),
           @Index(name = "idx_asset_cloud_connection", columnList = "cloudConnectionId"),
           @Index(name = "idx_asset_created_by_user", columnList = "createdByUserId"),
           // ADR-0046 sort and filter indexes; idx_asset_legal_hold is partial and lives in SQL only.
           @Index(name = "idx_asset_cloud_created_at", columnList = "cloudCreatedAt"),
           @Index(name = "idx_asset_size_bytes", columnList = "sizeBytes"),
           @Index(name = "idx_asset_last_accessed_at", columnList = "lastAccessedAt"),
           @Index(name = "idx_asset_last_modified_at", columnList = "lastModifiedAt"),
           @Index(name = "idx_asset_retention_expires_at", columnList = "retentionExpiresAt"),
           @Index(name = "idx_asset_retention_status_expires", columnList = "retentionStatus, retentionExpiresAt"),
           @Index(name = "idx_asset_retention_evaluated_at", columnList = "retentionEvaluatedAt"),
           // COV-01 read-coverage filter index.
           @Index(name = "idx_asset_read_coverage", columnList = "readCoverage")
       })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Asset {

    /**
     * Unique DLAP identifier for the data asset
     */
    @Id
    @Column(name = "asset_id", columnDefinition = "UUID")
    private UUID assetId;

    /**
     * Unique identifier of the asset in its source cloud system (e.g., S3 URI, BigQuery table ID)
     */
    @NotBlank(message = "Asset URI is required")
    @Size(max = 2048, message = "Asset URI cannot exceed 2048 characters")
    @Column(name = "asset_uri", length = 2048, nullable = false, unique = true)
    private String assetUri;

    /**
     * Cloud provider (e.g., 'GCP', 'AWS', 'AZURE', 'OCI')
     */
    @NotBlank(message = "Cloud provider is required")
    @Size(max = 20, message = "Cloud provider cannot exceed 20 characters")
    @Column(name = "cloud_provider", length = 20, nullable = false)
    private String cloudProvider;

    /**
     * Cloud service type (e.g., 'GCS_BUCKET', 'S3_BUCKET', 'BIGQUERY_TABLE')
     */
    @NotBlank(message = "Service type is required")
    @Size(max = 50, message = "Service type cannot exceed 50 characters")
    @Column(name = "service_type", length = 50, nullable = false)
    private String serviceType;

    /**
     * Asset type for catalog classification (e.g., 'TABLE', 'BUCKET', 'DATABASE')
     */
    @Size(max = 50, message = "Asset type cannot exceed 50 characters")
    @Column(name = "asset_type", length = 50)
    private String assetType;

    /**
     * Native asset identifier in the source system
     */
    @Size(max = 500, message = "Native asset ID cannot exceed 500 characters")
    @Column(name = "native_asset_id", length = 500)
    private String nativeAssetId;

    /**
     * Cloud region where the asset is located
     */
    @Size(max = 50, message = "Region cannot exceed 50 characters")
    @Column(name = "region", length = 50)
    private String region;

    /**
     * Cloud connection ID used to discover this asset
     */
    @Column(name = "cloud_connection_id", columnDefinition = "UUID")
    private UUID cloudConnectionId;

    /**
     * Business name or user-defined alias for the asset
     */
    @Size(max = 255, message = "Asset name cannot exceed 255 characters")
    @Column(name = "asset_name", length = 255)
    private String assetName;

    /**
     * Business description or context for the asset
     */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /**
     * Business metadata provided by users
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "business_metadata", columnDefinition = "jsonb")
    private Map<String, Object> businessMetadataJson;

    /**
     * Technical details (schema, size, creation date, last modified, region, etc.)
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "technical_metadata", columnDefinition = "jsonb")
    private Map<String, Object> technicalMetadata;

    /**
     * Additional details in JSON format
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details_json", columnDefinition = "jsonb")
    private Map<String, Object> detailsJson;

    /**
     * User who created this asset record
     */
    @Column(name = "created_by_user_id", columnDefinition = "UUID")
    private UUID createdByUserId;

    /**
     * Timestamp when DLAP first discovered this asset
     */
    @Column(name = "dlap_discovered_ts")
    private Instant dlapDiscoveredTs;

    /**
     * Alternative name for discovered timestamp
     */
    @Column(name = "discovered_at")
    private Instant discoveredAt;

    /**
     * Timestamp when DLAP last scanned/updated this asset's metadata
     */
    @Column(name = "dlap_last_scanned_ts")
    private Instant dlapLastScannedTs;

    /**
     * Alternative name for last scanned timestamp
     */
    @Column(name = "last_scanned_at")
    private Instant lastScannedAt;

    /**
     * Usage information (e.g., last access timestamp, access frequency)
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "dlap_usage_metrics", columnDefinition = "jsonb")
    private Map<String, Object> dlapUsageMetrics;

    /**
     * Status related to applied policies (e.g., applied policy IDs, compliance status per regulation)
     * Example: {"applied_policy_ids": ["uuid1", "uuid2"], "compliance_HIPAA": "COMPLIANT"}
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "dlap_policy_status", columnDefinition = "jsonb")
    private Map<String, Object> dlapPolicyStatus;

    /**
     * Information about data lineage (e.g., upstream and downstream asset IDs)
     * Example: {"upstream_asset_ids": ["uuid_A"], "downstream_asset_ids": ["uuid_C"]}
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "dlap_lineage_info", columnDefinition = "jsonb")
    private Map<String, Object> dlapLineageInfo;

    /**
     * Textual information about the asset's owner (e.g., email, team name)
     */
    @Column(name = "owner_info", columnDefinition = "TEXT")
    private String ownerInfo;

    /**
     * Current lifecycle status of the asset (e.g., 'ACTIVE', 'ARCHIVED', 'PENDING_DELETION', 'DELETED')
     */
    @Size(max = 20, message = "Status cannot exceed 20 characters")
    @Column(name = "status", length = 20)
    private String status;

    /**
     * Timestamp of asset record creation in DLAP
     */
    @Column(name = "created_ts", nullable = false, updatable = false)
    private Instant createdTs;

    /**
     * Timestamp of last modification to asset record in DLAP
     */
    @Column(name = "last_modified_ts", nullable = false)
    private Instant lastModifiedTs;

    // ---- Creation and size (ADR-0046) ---------------------------------------------------------

    /**
     * When the provider created the current incarnation of the asset: the least value known from
     * crawler creation times and CREATE events. Distinct from {@link #discoveredAt}, which is when
     * DLAP first saw the asset.
     */
    @Column(name = "cloud_created_at")
    private Instant cloudCreatedAt;

    /**
     * Evidence channel for {@link #cloudCreatedAt}, for example {@code CRAWLER} or
     * {@code AUDIT_LOG:GCP_CLOUD_AUDIT} (vocabulary in the asset-descriptor spec).
     */
    @Size(max = 64, message = "Cloud created source cannot exceed 64 characters")
    @Column(name = "cloud_created_source", length = 64)
    private String cloudCreatedSource;

    /**
     * Latest observed size in bytes. {@code null} is unknown; {@code 0} is a real size.
     */
    @PositiveOrZero(message = "Size cannot be negative")
    @Column(name = "size_bytes")
    private Long sizeBytes;

    /**
     * Business time at which {@link #sizeBytes} was true. A newer observation replaces an older one.
     */
    @Column(name = "size_observed_at")
    private Instant sizeObservedAt;

    // ---- Activity (ADR-0046) ------------------------------------------------------------------

    /**
     * Latest event time of a successful data read (log-event categories DATA_READ and
     * DATA_READ_OBSERVED). Only ever moves forward. Supersedes
     * {@code dlapUsageMetrics.lastAccessedAt}, which holds DLAP processing time.
     */
    @Column(name = "last_accessed_at")
    private Instant lastAccessedAt;

    /**
     * Evidence channel for {@link #lastAccessedAt}.
     */
    @Size(max = 64, message = "Last access source cannot exceed 64 characters")
    @Column(name = "last_access_source", length = 64)
    private String lastAccessSource;

    /**
     * Latest event time of a successful content write. Not to be confused with
     * {@link #lastModifiedTs}, the DLAP row modification time.
     */
    @Column(name = "last_modified_at")
    private Instant lastModifiedAt;

    /**
     * Latest event time of a successful change to who can access the asset.
     */
    @Column(name = "last_permission_change_at")
    private Instant lastPermissionChangeAt;

    // ---- Lifecycle (ADR-0046) -----------------------------------------------------------------

    /**
     * Event time of the last status transition applied. A transition with an older event time
     * is ignored, so late events never move the status backwards.
     */
    @Column(name = "lifecycle_event_time")
    private Instant lifecycleEventTime;

    /**
     * Event time of a CREATE applied while the asset was DELETED (name reuse). Evidence older
     * than this instant belongs to the previous incarnation and no longer changes this row.
     */
    @Column(name = "recreated_at")
    private Instant recreatedAt;

    /**
     * Channel through which DLAP first learned of the asset, for example {@code CRAWLER},
     * {@code AUDIT_LOG} or {@code MANUAL}.
     */
    @Size(max = 64, message = "Discovery source cannot exceed 64 characters")
    @Column(name = "discovery_source", length = 64)
    private String discoverySource;

    // ---- Retention (ADR-0046) -----------------------------------------------------------------
    // Evaluation output only. No rule input is stored on the asset. Each rule stays at its source
    // (spec "Retention > Rule"): a policy's setRetention action, the label-default configuration,
    // a provider setting in technicalMetadata, or the catalog's retention rule store for MANUAL
    // rules. The retention evaluator (CAT-03) writes the deciding rule and its result here on
    // every run and never reads them back as input, so an evaluation, a hold or a release cannot
    // overwrite or lose a rule. legalHold is the one input in this group.

    /**
     * Period in days of the rule that decided the last evaluation. {@code null} when no rule
     * decided or the deciding rule's basis is {@link RetentionBasis#FIXED_DATE}. Output only.
     */
    @PositiveOrZero(message = "Retention days cannot be negative")
    @Column(name = "retention_days")
    private Integer retentionDays;

    /**
     * Basis of the rule that decided the last evaluation. Output only.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "retention_basis", length = 20)
    private RetentionBasis retentionBasis;

    /**
     * Source of the rule that decided the last evaluation: {@code POLICY}, {@code LABEL_DEFAULT},
     * {@code PROVIDER} or {@code MANUAL}. Output only: a {@code MANUAL} rule is kept in the
     * catalog's retention rule store, not here.
     */
    @Size(max = 64, message = "Retention source cannot exceed 64 characters")
    @Column(name = "retention_source", length = 64)
    private String retentionSource;

    /**
     * Policy-engine policy whose {@code setRetention} action is the deciding rule, when
     * {@link #retentionSource} is {@code POLICY}. Not a foreign key: policies live in another
     * service's database. Output only.
     */
    @Column(name = "retention_policy_id", columnDefinition = "UUID")
    private UUID retentionPolicyId;

    /**
     * The expiry the last evaluation found: exact when the basis time is exact (for a
     * {@link RetentionBasis#FIXED_DATE} rule, the rule's own date, copied from its source),
     * otherwise the bound that proves the status (ADR-0046). {@code null} when the status is
     * {@link RetentionStatus#UNKNOWN} or {@link RetentionStatus#NO_POLICY}. Under
     * {@link RetentionStatus#ON_HOLD} it is what the rules alone give, for information only.
     * Output only: the evaluator never reads it back, so clearing it loses no rule.
     */
    @Column(name = "retention_expires_at")
    private Instant retentionExpiresAt;

    /**
     * Retention state as of {@link #retentionEvaluatedAt}. Never {@code null}; defaults to
     * {@link RetentionStatus#UNKNOWN}.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "retention_status", length = 20, nullable = false)
    @Builder.Default
    private RetentionStatus retentionStatus = RetentionStatus.UNKNOWN;

    /**
     * As-of instant of the last retention evaluation, from the evaluator's injected clock. The
     * only field in this group that is not a business time.
     */
    @Column(name = "retention_evaluated_at")
    private Instant retentionEvaluatedAt;

    /**
     * DLAP legal hold. While {@code true} the status is {@link RetentionStatus#ON_HOLD} (enforced by
     * a CHECK constraint) and nothing may retire the asset. An input, not evaluation output: only
     * an audited action by a named role changes it, through {@link #placeLegalHold()} and
     * {@link #releaseLegalHold()}. The generated setter does not adjust the status; clearing the
     * flag through it leaves {@code ON_HOLD} stored until the next evaluation, which fails safe.
     */
    @Column(name = "legal_hold", nullable = false)
    private boolean legalHold;

    // ---- Read coverage (COV-01) ----------------------------------------------------------------
    // Whether the provider configuration and DLAP's log bindings prove that data reads of this asset
    // are recorded (spec "Read coverage" in docs/specs/asset-descriptor.md). The da-catalog coverage
    // applier is the only writer and writes with SQL, so these columns are read-only here: an entity
    // save never overwrites a newer observation with the value it loaded.

    /**
     * Read-logging coverage: {@code FULL}, {@code PARTIAL} or {@code NONE}; {@code null} is unknown.
     * Only {@code FULL} lets a report or a retention rule treat a missing read as "not read".
     */
    @Column(name = "read_coverage", length = 10, insertable = false, updatable = false)
    private String readCoverage;

    /**
     * Why the coverage is what it is: for {@code PARTIAL}, {@code NONE} and unknown the gaps (an
     * unknown names the missing permission), for {@code FULL} the evidence. {@code null} when no
     * observation was applied.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "read_coverage_reasons", columnDefinition = "jsonb", insertable = false, updatable = false)
    private List<String> readCoverageReasons;

    /**
     * When the provider configuration behind {@link #readCoverage} was read, by the collector's
     * clock. An older observation never replaces a newer one.
     */
    @Column(name = "read_coverage_observed_at", insertable = false, updatable = false)
    private Instant readCoverageObservedAt;

    /**
     * First observation of the current unbroken run of {@code FULL} observations: reads are known
     * to be recorded from here on. {@code null} unless {@link #readCoverage} is {@code FULL}.
     */
    @Column(name = "read_coverage_since", insertable = false, updatable = false)
    private Instant readCoverageSince;

    /**
     * Constructor with required fields
     * 
     * @param assetUri the unique URI of the asset
     * @param cloudProvider the cloud provider
     * @param serviceType the service type
     */
    public Asset(String assetUri, String cloudProvider, String serviceType) {
        this.assetId = UUID.randomUUID();
        this.assetUri = assetUri;
        this.cloudProvider = cloudProvider;
        this.serviceType = serviceType;
        this.status = "ACTIVE";
        this.retentionStatus = RetentionStatus.UNKNOWN;
    }

    /**
     * Constructor with required fields and asset name
     * 
     * @param assetUri the unique URI of the asset
     * @param cloudProvider the cloud provider
     * @param serviceType the service type
     * @param assetName the business name of the asset
     */
    public Asset(String assetUri, String cloudProvider, String serviceType, String assetName) {
        this(assetUri, cloudProvider, serviceType);
        this.assetName = assetName;
    }

    /**
     * JPA lifecycle callback to set creation timestamp
     */
    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdTs = now;
        this.lastModifiedTs = now;
        this.dlapDiscoveredTs = now;
        if (this.assetId == null) {
            this.assetId = UUID.randomUUID();
        }
        if (this.status == null) {
            this.status = "ACTIVE";
        }
        normalizeRetentionStatus();
    }

    /**
     * JPA lifecycle callback to update modification timestamp
     */
    @PreUpdate
    protected void onUpdate() {
        this.lastModifiedTs = Instant.now();
        normalizeRetentionStatus();
    }

    /**
     * Keeps the stored status consistent with the column constraints: never {@code null}, and
     * {@link RetentionStatus#ON_HOLD} whenever the asset is on legal hold.
     */
    private void normalizeRetentionStatus() {
        if (this.legalHold) {
            this.retentionStatus = RetentionStatus.ON_HOLD;
        } else if (this.retentionStatus == null) {
            this.retentionStatus = RetentionStatus.UNKNOWN;
        }
    }

    /**
     * Retention state as of the last evaluation.
     *
     * @return the status; {@link RetentionStatus#UNKNOWN} when none was set, and
     *         {@link RetentionStatus#ON_HOLD} whenever the asset is on legal hold
     */
    public RetentionStatus getRetentionStatus() {
        if (legalHold) {
            return RetentionStatus.ON_HOLD;
        }
        return retentionStatus == null ? RetentionStatus.UNKNOWN : retentionStatus;
    }

    /**
     * Places a DLAP legal hold. The status becomes {@link RetentionStatus#ON_HOLD} at once, so the
     * asset cannot read as due between now and the next retention evaluation. Nothing else
     * changes: the last evaluation's deciding rule and expiry stay, for information.
     */
    public void placeLegalHold() {
        this.legalHold = true;
        this.retentionStatus = RetentionStatus.ON_HOLD;
    }

    /**
     * Releases the DLAP legal hold. The status returns to {@link RetentionStatus#UNKNOWN} and the
     * evaluation result ({@link #retentionExpiresAt}, {@link #retentionEvaluatedAt}) is cleared, so
     * the asset stays non-retirable until it is evaluated again. Nothing else changes: every
     * evidence field stays, and the deciding-rule fields keep naming the last evaluation's rule
     * until the next evaluation rewrites them. No rule can be lost, because rules are kept at
     * their source, never on the asset.
     */
    public void releaseLegalHold() {
        this.legalHold = false;
        this.retentionStatus = RetentionStatus.UNKNOWN;
        this.retentionExpiresAt = null;
        this.retentionEvaluatedAt = null;
    }

    /**
     * Whether retention lets a retire action (archive or delete) proceed: the last evaluation found
     * the asset {@link RetentionStatus#DUE} and it is not on legal hold. Approval and fail-closed
     * dispatch rules still apply on top of this.
     *
     * @return {@code true} only for a due asset without legal hold
     */
    public boolean retentionPermitsRetire() {
        return !legalHold && getRetentionStatus().permitsRetire();
    }

    /**
     * Check if the asset is active
     * 
     * @return true if asset status is ACTIVE, false otherwise
     */
    public boolean isActive() {
        return "ACTIVE".equals(status);
    }

    /**
     * Check if the asset is archived
     * 
     * @return true if asset status is ARCHIVED, false otherwise
     */
    public boolean isArchived() {
        return "ARCHIVED".equals(status);
    }

    /**
     * Check if the asset is pending deletion
     * 
     * @return true if asset status is PENDING_DELETION, false otherwise
     */
    public boolean isPendingDeletion() {
        return "PENDING_DELETION".equals(status);
    }

    /**
     * Check if the asset is deleted
     * 
     * @return true if asset status is DELETED, false otherwise
     */
    public boolean isDeleted() {
        return "DELETED".equals(status);
    }

    /**
     * Check if the asset is from a specific cloud provider
     * 
     * @param provider the cloud provider to check
     * @return true if asset is from the specified provider, false otherwise
     */
    public boolean isFromCloudProvider(String provider) {
        return cloudProvider != null && cloudProvider.equalsIgnoreCase(provider);
    }

    /**
     * Check if the asset is of a specific service type
     * 
     * @param type the service type to check
     * @return true if asset is of the specified type, false otherwise
     */
    public boolean isOfServiceType(String type) {
        return serviceType != null && serviceType.equalsIgnoreCase(type);
    }

    /**
     * Update the last scanned timestamp to current time
     */
    public void updateLastScannedTimestamp() {
        this.dlapLastScannedTs = Instant.now();
    }

    /**
     * Mark the asset as archived
     */
    public void markAsArchived() {
        this.status = "ARCHIVED";
    }

    /**
     * Mark the asset as pending deletion
     */
    public void markAsPendingDeletion() {
        this.status = "PENDING_DELETION";
    }

    /**
     * Mark the asset as deleted
     */
    public void markAsDeleted() {
        this.status = "DELETED";
    }

    /**
     * Restore the asset to active status
     */
    public void restore() {
        this.status = "ACTIVE";
    }
} 