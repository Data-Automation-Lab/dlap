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
 * Entity to store archival activities for the recent activities section.
 * This entity tracks various activities that occur during the archival process.
 */
@Entity
@Table(name = "dlap_archival_activities")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ArchivalActivityEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "activity_id", unique = true, nullable = false)
    private String activityId;

    @Column(name = "event_type", nullable = false)
    private String eventType; // ASSET_ARCHIVED, ARCHIVAL_STARTED, ASSET_IDENTIFIED, ARCHIVAL_FAILED, etc.

    @Column(name = "asset_name")
    private String assetName;

    @Column(name = "policy_name")
    private String policyName;

    @Column(name = "storage_size_gb")
    private Double storageSizeGb;

    @Column(name = "savings_amount")
    private Double savingsAmount;

    @Column(name = "status", nullable = false)
    private String status; // SUCCESS, IN_PROGRESS, PENDING, FAILED

    @Column(name = "activity_timestamp", nullable = false)
    private LocalDateTime activityTimestamp;

    @Column(name = "details", columnDefinition = "TEXT")
    private String details;

    @Column(name = "task_id")
    private String taskId; // Reference to ArchivalTaskEntity

    @Column(name = "asset_id")
    private String assetId; // Reference to Asset entity from da-protos

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
} 