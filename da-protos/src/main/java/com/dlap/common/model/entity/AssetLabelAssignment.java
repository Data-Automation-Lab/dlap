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
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DLAP Asset Label Assignment Entity
 * 
 * Join table for the many-to-many relationship between assets and labels.
 * Records which labels are applied to which assets, including metadata about the assignment.
 * 
 * @author DLAP Team
 * @version 1.0
 * @since 1.0
 */
@Entity
@Table(name = "dlap_asset_label_assignments", schema = "dlap_common",
       indexes = {
           @Index(name = "idx_assignment_asset_id", columnList = "assetId"),
           @Index(name = "idx_assignment_label_id", columnList = "labelId"),
           @Index(name = "idx_assignment_assigned_by", columnList = "assignedByUserId"),
           @Index(name = "idx_assignment_source", columnList = "source")
       },
       uniqueConstraints = {
           @UniqueConstraint(name = "uk_asset_label", columnNames = {"assetId", "labelId"})
       })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@IdClass(AssetLabelAssignmentId.class)
public class AssetLabelAssignment {

    /**
     * Identifier of the asset being labeled
     */
    @Id
    @NotNull(message = "Asset ID is required")
    @Column(name = "asset_id", columnDefinition = "UUID")
    private UUID assetId;

    /**
     * Identifier of the label being applied
     */
    @Id
    @NotNull(message = "Label ID is required")
    @Column(name = "label_id", columnDefinition = "UUID")
    private UUID labelId;

    /**
     * The asset entity being labeled
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "asset_id", insertable = false, updatable = false)
    private Asset asset;

    /**
     * The label entity being applied
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "label_id", insertable = false, updatable = false)
    private Label label;

    /**
     * User who assigned this label. Nullable if assigned by system/automation.
     */
    @Column(name = "assigned_by_user_id", columnDefinition = "UUID")
    private UUID assignedByUserId;

    /**
     * The user entity who assigned this label. Lazily loaded; nullable for system assignments.
     * Backed by {@link #assignedByUserId}; mapping is read-only at the JPA level so writes
     * continue to flow through the UUID field.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
        name = "assigned_by_user_id",
        insertable = false,
        updatable = false,
        foreignKey = @jakarta.persistence.ForeignKey(name = "fk_assignment_assigned_by_user")
    )
    private User assignedByUser;

    /**
     * Timestamp when the label was assigned
     */
    @Column(name = "assigned_ts", nullable = false, updatable = false)
    private Instant assignedTs;

    /**
     * Confidence score if the label was suggested by an ML model (0.0 to 1.0)
     */
    @DecimalMin(value = "0.0", message = "Confidence score must be between 0.0 and 1.0")
    @DecimalMax(value = "1.0", message = "Confidence score must be between 0.0 and 1.0")
    // A Double maps to double precision (float8). columnDefinition="DECIMAL(3,2)"
    // does NOT change this for ddl-auto=validate (validation checks the field's
    // JDBC type, FLOAT, not the DDL string) - it only desynced the entity from the
    // numeric column. Range is enforced by the @DecimalMin/@DecimalMax above and a
    // CHECK in dlap_common.
    @Column(name = "confidence_score")
    private Double confidenceScore;

    /**
     * Source of the assignment (e.g., 'USER', 'SMART_LABEL_RULE', 'SMART_LABEL_ML')
     */
    @Size(max = 50, message = "Source cannot exceed 50 characters")
    @Column(name = "source", length = 50)
    private String source;

    /**
     * Constructor with required fields
     * 
     * @param assetId the asset ID
     * @param labelId the label ID
     * @param source the source of the assignment
     */
    public AssetLabelAssignment(UUID assetId, UUID labelId, String source) {
        this.assetId = assetId;
        this.labelId = labelId;
        this.source = source;
    }

    /**
     * Constructor with required fields and user
     * 
     * @param assetId the asset ID
     * @param labelId the label ID
     * @param source the source of the assignment
     * @param assignedByUserId the user who assigned the label
     */
    public AssetLabelAssignment(UUID assetId, UUID labelId, String source, UUID assignedByUserId) {
        this(assetId, labelId, source);
        this.assignedByUserId = assignedByUserId;
    }

    /**
     * Constructor with confidence score for ML assignments
     * 
     * @param assetId the asset ID
     * @param labelId the label ID
     * @param source the source of the assignment
     * @param confidenceScore the confidence score
     */
    public AssetLabelAssignment(UUID assetId, UUID labelId, String source, Double confidenceScore) {
        this(assetId, labelId, source);
        this.confidenceScore = confidenceScore;
    }

    /**
     * JPA lifecycle callback to set assignment timestamp
     */
    @PrePersist
    protected void onCreate() {
        if (this.assignedTs == null) {
            this.assignedTs = Instant.now();
        }
        if (this.source == null) {
            this.source = "SYSTEM";
        }
    }

    /**
     * Check if this assignment was made by a user
     * 
     * @return true if assigned by a user, false otherwise
     */
    public boolean isUserAssigned() {
        return "USER".equals(source);
    }

    /**
     * Check if this assignment was made by ML
     * 
     * @return true if assigned by ML, false otherwise
     */
    public boolean isMlAssigned() {
        return "SMART_LABEL_ML".equals(source);
    }

    /**
     * Check if this assignment was made by a rule
     * 
     * @return true if assigned by a rule, false otherwise
     */
    public boolean isRuleAssigned() {
        return "SMART_LABEL_RULE".equals(source);
    }

    /**
     * Check if this assignment was made by the system
     * 
     * @return true if assigned by system, false otherwise
     */
    public boolean isSystemAssigned() {
        return "SYSTEM".equals(source);
    }

    /**
     * Check if this assignment has a confidence score
     * 
     * @return true if confidence score is present, false otherwise
     */
    public boolean hasConfidenceScore() {
        return confidenceScore != null;
    }

    /**
     * Check if the confidence score is above a threshold
     * 
     * @param threshold the threshold to check against
     * @return true if confidence score is above threshold, false otherwise
     */
    public boolean isConfidenceAbove(double threshold) {
        return hasConfidenceScore() && confidenceScore > threshold;
    }

    /**
     * Check if this is a high-confidence assignment (>= 0.8)
     * 
     * @return true if high confidence, false otherwise
     */
    public boolean isHighConfidence() {
        return isConfidenceAbove(0.8);
    }

    /**
     * Check if this is a low-confidence assignment (<= 0.5)
     * 
     * @return true if low confidence, false otherwise
     */
    public boolean isLowConfidence() {
        return hasConfidenceScore() && confidenceScore <= 0.5;
    }

    /**
     * Set the asset entity and update the assetId
     * 
     * @param asset the asset entity
     */
    public void setAsset(Asset asset) {
        this.asset = asset;
        this.assetId = (asset != null) ? asset.getAssetId() : null;
    }

    /**
     * Set the label entity and update the labelId
     * 
     * @param label the label entity
     */
    public void setLabel(Label label) {
        this.label = label;
        this.labelId = (label != null) ? label.getLabelId() : null;
    }

    /**
     * Get the assigned timestamp (alias for assignedTs)
     * 
     * @return the assigned timestamp
     */
    public Instant getAssignedAt() {
        return this.assignedTs;
    }

    /**
     * Set the assigned timestamp (alias for assignedTs)
     * 
     * @param assignedAt the assigned timestamp
     */
    public void setAssignedAt(Instant assignedAt) {
        this.assignedTs = assignedAt;
    }
} 