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
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DLAP Label Entity
 * 
 * Defines the taxonomy of labels that can be applied to assets.
 * Labels are used for classification, governance, and compliance purposes.
 * 
 * @author DLAP Team
 * @version 1.0
 * @since 1.0
 */
@Entity
@Table(name = "dlap_labels", schema = "dlap_common",
       indexes = {
           @Index(name = "idx_label_name", columnList = "labelName", unique = true),
           @Index(name = "idx_label_category", columnList = "labelCategory"),
           @Index(name = "idx_label_created_by", columnList = "createdByUserId")
       })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Label {

    /**
     * Unique identifier for the label definition
     */
    @Id
    @Column(name = "label_id", columnDefinition = "UUID")
    private UUID labelId;

    /**
     * The actual name of the label (e.g., "PII", "Confidential-Internal")
     */
    @NotBlank(message = "Label name is required")
    @Size(max = 255, message = "Label name cannot exceed 255 characters")
    @Column(name = "label_name", length = 255, nullable = false, unique = true)
    private String labelName;

    /**
     * Category of the label (e.g., "Sensitivity", "Data Type", "Business Domain")
     */
    @Size(max = 100, message = "Label category cannot exceed 100 characters")
    @Column(name = "label_category", length = 100)
    private String labelCategory;

    /**
     * Detailed description of what the label represents
     */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /**
     * User who defined this label. Nullable if system-defined.
     */
    @Column(name = "created_by_user_id", columnDefinition = "UUID")
    private UUID createdByUserId;

    /**
     * Timestamp of label definition creation
     */
    @Column(name = "created_ts", nullable = false, updatable = false)
    private Instant createdTs;

    /**
     * Timestamp of last modification to label definition
     */
    @Column(name = "last_modified_ts", nullable = false)
    private Instant lastModifiedTs;

    /**
     * Constructor with required fields
     * 
     * @param labelName the name of the label
     * @param labelCategory the category of the label
     * @param description the description of the label
     */
    public Label(String labelName, String labelCategory, String description) {
        this.labelId = UUID.randomUUID();
        this.labelName = labelName;
        this.labelCategory = labelCategory;
        this.description = description;
    }

    /**
     * Constructor with required fields and creator
     * 
     * @param labelName the name of the label
     * @param labelCategory the category of the label
     * @param description the description of the label
     * @param createdByUserId the user who created this label
     */
    public Label(String labelName, String labelCategory, String description, UUID createdByUserId) {
        this(labelName, labelCategory, description);
        this.createdByUserId = createdByUserId;
    }

    /**
     * JPA lifecycle callback to set creation timestamp
     */
    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdTs = now;
        this.lastModifiedTs = now;
        if (this.labelId == null) {
            this.labelId = UUID.randomUUID();
        }
    }

    /**
     * JPA lifecycle callback to update modification timestamp
     */
    @PreUpdate
    protected void onUpdate() {
        this.lastModifiedTs = Instant.now();
    }

    /**
     * Check if this is a system-defined label
     * 
     * @return true if system-defined (no creator), false otherwise
     */
    public boolean isSystemDefined() {
        return createdByUserId == null;
    }

    /**
     * Check if this label is in a specific category
     * 
     * @param category the category to check
     * @return true if label is in the specified category, false otherwise
     */
    public boolean isInCategory(String category) {
        return labelCategory != null && labelCategory.equalsIgnoreCase(category);
    }

    /**
     * Check if this is a sensitivity label
     * 
     * @return true if this is a sensitivity label, false otherwise
     */
    public boolean isSensitivityLabel() {
        return isInCategory("Sensitivity");
    }

    /**
     * Check if this is a compliance label
     * 
     * @return true if this is a compliance label, false otherwise
     */
    public boolean isComplianceLabel() {
        return isInCategory("Compliance");
    }

    /**
     * Check if this is a data type label
     * 
     * @return true if this is a data type label, false otherwise
     */
    public boolean isDataTypeLabel() {
        return isInCategory("Data Type");
    }
} 