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

package com.dlap.autoarchival.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "dlap_archival_tasks")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ArchivalTaskEntity {

    @Id
    private String taskId;

    private String taskName;

    @Enumerated(EnumType.STRING)
    private ArchivalStatus status;

    private LocalDateTime submittedAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;

    private String triggeredBy;
    private String justification;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private ArchivalScopeData scope; // Store as JSON

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private ArchivalConfigOverrideData overrideConfig; // Store as JSON

    private int totalAssetsInScope;
    private int assetsArchivedSuccessfully;
    private int assetsFailedToArchive;
    private int assetsPendingArchival;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<AssetArchivalStatusData> assetStatuses; // Store as JSON

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> errorMessages; // Store as JSON
    
    private String approvalComments;
    private String rejectionComments;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private RestoreRequestData restoreRequestDetails; // Store restore request as JSON
    private LocalDateTime lastRestoreRequestedAt;
    private String lastRestoreStatus;


    // Static inner classes for JSONB data
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class ArchivalScopeData {
        private List<String> assetIds;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class ArchivalConfigOverrideData {
        private String targetArchivalTier;
        private Long retentionDays;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class AssetArchivalStatusData {
        private String assetId;
        private String assetName;
        private String status; // PENDING, IN_PROGRESS, ARCHIVED, FAILED, RESTORED
        /**
         * The asset's real storage location: the source URI before archival, then the
         * archived URI once the cloud call completes. Resolved from da-catalog or the
         * policy event's match snapshot - never synthesised. Null means unresolved,
         * and the task fails closed rather than archiving a made-up location.
         */
        private String archivalLocation;
        /**
         * Cloud owning this asset, as resolved alongside {@code archivalLocation}.
         * Authoritative over URI-scheme inference when routing to an
         * {@code ICloudStorageService}. Stored inside the existing JSONB column, so no
         * schema migration is involved.
         */
        private String cloudProvider;
        private String errorMessage;
        private LocalDateTime archivedAt;
        private LocalDateTime restoredAt;
        /** RET-03: the approval this asset's archive runs under (docs/specs/approvals.md section 9). */
        private String approvalId;
        /** RET-03: the native rule (or direct change) row in {@code dlap_archive_rules}. */
        private String ruleId;

        /**
         * Creates a status without an approval or rule yet.
         *
         * @param assetId         the catalog asset id
         * @param assetName       the display name
         * @param status          the status
         * @param archivalLocation the storage location
         * @param cloudProvider   the provider
         * @param errorMessage    the failure text
         * @param archivedAt      when archived
         * @param restoredAt      when restored
         */
        public AssetArchivalStatusData(String assetId, String assetName, String status, String archivalLocation,
                String cloudProvider, String errorMessage, LocalDateTime archivedAt, LocalDateTime restoredAt) {
            this(assetId, assetName, status, archivalLocation, cloudProvider, errorMessage, archivedAt, restoredAt,
                    null, null);
        }
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class RestoreRequestData {
        private String restoreTier;
        private int availabilityDays;
        private String triggeredBy;
        private String justification;
        private LocalDateTime requestedAt;
    }
} 