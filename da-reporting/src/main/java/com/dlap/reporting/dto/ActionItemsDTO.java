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

package com.dlap.reporting.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DTO for Action Items Aggregation Dashboard response
 * Provides comprehensive action items from all DLAP services
 * 
 * @author DLAP Development Team
 * @since 2025-01-02
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActionItemsDTO {

    /**
     * Total number of active action items
     */
    private Integer totalActionItems;

    /**
     * Action items by priority/severity
     */
    private ActionItemsSummary summary;

    /**
     * Detailed list of action items
     */
    private List<ActionItem> actionItems;

    /**
     * SLA compliance metrics
     */
    private SlaMetrics slaMetrics;

    /**
     * Business impact assessment
     */
    private BusinessImpactSummary businessImpact;

    /**
     * Last aggregation timestamp
     */
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime lastAggregated;

    /**
     * Data sources included in aggregation
     */
    private List<String> includedServices;

    /**
     * True when the policy engine upstream responded successfully. False means
     * the upstream call failed and the list is empty - distinguish from the
     * case where the policy engine responded with zero items.
     */
    private Boolean dataAvailable;

    /**
     * Non-null error summary when the upstream call failed. Purely diagnostic.
     */
    private String lastError;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ActionItemsSummary {
        private Integer criticalCount;
        private Integer highCount;
        private Integer mediumCount;
        private Integer lowCount;
        
        private Integer overdueCount;
        private Integer dueTodayCount;
        private Integer dueThisWeekCount;
        
        private Integer assignedCount;
        private Integer unassignedCount;
        private Integer inProgressCount;
        private Integer completedCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ActionItem {
        private String id;
        private String title;
        private String description;
        private String category; // "COMPLIANCE_VIOLATION", "POLICY_BREACH", "METADATA_MISSING", "DISCOVERY_FAILED", etc.
        private String severity; // "CRITICAL", "HIGH", "MEDIUM", "LOW"
        private String status; // "OPEN", "IN_PROGRESS", "PENDING_APPROVAL", "COMPLETED", "DISMISSED"
        
        private String sourceService; // "da-autocompliance", "da-policyengine", etc.
        private String sourceEntityType; // "ASSET", "POLICY", "USER", "CONNECTION"
        private String sourceEntityId;
        private String sourceEntityName;
        
        private String assignedTo; // user ID or team
        private String assignedTeam; // "DATA_ENGINEERING", "COMPLIANCE", "SECURITY"
        
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        private LocalDateTime createdAt;
        
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        private LocalDateTime dueDate;
        
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        private LocalDateTime lastUpdated;
        
        private String actionRequired;
        private String businessJustification;
        private String estimatedEffort; // "MINUTES", "HOURS", "DAYS", "WEEKS"
        private Integer businessImpactScore; // 1-10
        
        private List<String> tags;
        private String detailsUrl; // link to specific service UI
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SlaMetrics {
        private Double averageResolutionTimeHours;
        private Double slaComplianceRate; // percentage
        private Integer breachedSlaCount;
        private Integer nearBreachCount; // within 24 hours of SLA breach
        
        private SlaByCategory complianceViolations;
        private SlaByCategory policyBreaches;
        private SlaByCategory metadataIssues;
        private SlaByCategory discoveryIssues;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SlaByCategory {
        private Integer totalItems;
        private Integer onTimeResolved;
        private Integer breachedSla;
        private Double averageResolutionHours;
        private Double complianceRate;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BusinessImpactSummary {
        private Integer highImpactItems; // score 8-10
        private Integer mediumImpactItems; // score 5-7
        private Integer lowImpactItems; // score 1-4
        
        private Double averageImpactScore;
        private String highestImpactCategory;
        
        private List<ImpactArea> impactAreas;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImpactArea {
        private String areaName; // "DATA_PRIVACY", "REGULATORY_COMPLIANCE", "OPERATIONAL_EFFICIENCY", "COST_OPTIMIZATION"
        private Integer affectedItems;
        private Double averageImpactScore;
        private String riskLevel; // "HIGH", "MEDIUM", "LOW"
        private String description;
    }
} 