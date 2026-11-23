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

package com.dlap.autoarchival.service.storage;

import java.time.LocalDateTime;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result of a restoration operation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RestorationResult {
    
    private UUID assetId;
    private String operationId;
    private String archivedLocation;
    private String restoredLocation;
    private String targetTier;
    private OperationStatus status;
    private LocalDateTime initiatedAt;
    private LocalDateTime completedAt;
    private LocalDateTime expiresAt; // For temporary restoration
    private Long dataSizeBytes;
    private String errorMessage;
    private Double estimatedCost;
} 