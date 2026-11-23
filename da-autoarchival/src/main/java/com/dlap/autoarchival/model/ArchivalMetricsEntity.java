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

import java.time.LocalDateTime;

/**
 * Entity to store archival metrics and KPIs for the overview dashboard.
 * This entity tracks various metrics that are displayed on the archival overview page.
 */
@Entity
@Table(name = "dlap_archival_metrics")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ArchivalMetricsEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "metric_date", nullable = false)
    private LocalDateTime metricDate;

    @Column(name = "active_policies")
    private Integer activePolicies;

    @Column(name = "assets_identified")
    private Integer assetsIdentified;

    @Column(name = "assets_archived")
    private Integer assetsArchived;

    @Column(name = "estimated_monthly_savings")
    private Double estimatedMonthlySavings;

    @Column(name = "total_savings_to_date")
    private Double totalSavingsToDate;

    @Column(name = "storage_reclaimed_tb")
    private Double storageReclaimedTb;

    @Column(name = "pending_archival")
    private Integer pendingArchival;

    @Column(name = "failed_archival")
    private Integer failedArchival;

    @Column(name = "average_archival_time_minutes")
    private Double averageArchivalTimeMinutes;

    @Column(name = "last_policy_run")
    private LocalDateTime lastPolicyRun;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
} 