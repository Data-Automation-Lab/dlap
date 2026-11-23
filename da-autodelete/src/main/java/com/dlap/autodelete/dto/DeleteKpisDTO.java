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

package com.dlap.autodelete.dto;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * DTO representing the key performance indicators for the Delete Agent.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeleteKpisDTO {
    private int activePolicies;
    private long assetsDeleted;
    private int assetsDeletedLast24h;
    private int pendingDeletion;
    private int failedDeletion;
    private String storageReclaimed;
    private double estimatedMonthlySavings;
    private double totalSavingsToDate;
    private double complianceScore;
    private double averageDeletionTime;
    private String lastPolicyRun;
} 