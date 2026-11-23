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
 * Entity to store storage consumer data for the storage consumers section.
 * This entity tracks storage usage by different consumers and their archival recommendations.
 */
@Entity
@Table(name = "dlap_storage_consumers")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StorageConsumerEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "consumer_name", nullable = false)
    private String consumerName;

    @Column(name = "current_size_tb", nullable = false)
    private Double currentSizeTb;

    @Column(name = "last_accessed")
    private LocalDateTime lastAccessed;

    @Column(name = "monthly_cost")
    private Double monthlyCost;

    @Column(name = "recommendation")
    private String recommendation; // ARCHIVE_NOW, MONITOR, KEEP_ACTIVE

    @Column(name = "priority")
    private String priority; // HIGH, MEDIUM, LOW

    @Column(name = "consumer_type")
    private String consumerType; // DATASET, BACKUP, DEVELOPMENT, STAGING, etc.

    @Column(name = "estimated_savings")
    private Double estimatedSavings;

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