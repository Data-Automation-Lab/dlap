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

package com.dlap.autoarchival.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for recent archival activity events.
 * Represents individual archival events with detailed information.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecentActivityDTO {
    
    /**
     * Unique identifier for the activity
     */
    private String id;
    
    /**
     * Type of event (e.g., "Asset Archived", "Archival Started")
     */
    private String event;
    
    /**
     * Name of the asset involved
     */
    private String assetName;
    
    /**
     * Name of the policy that triggered the action
     */
    private String policy;
    
    /**
     * Storage size of the asset (e.g., "2.3 GB")
     */
    private String storageSize;
    
    /**
     * Cost savings amount in USD
     */
    private Double savingsAmount;
    
    /**
     * Status of the activity
     */
    private String status; // Success, Pending, Failed, In Progress
    
    /**
     * Human-readable timestamp (e.g., "15 minutes ago")
     */
    private String timestamp;
    
    /**
     * Additional details about the activity
     */
    private String details;
} 