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

package com.dlap.discovery.client.rest.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for discovery service statistics.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DiscoveryStatsDTO {

    private Long totalScansSubmitted;
    private Long scansPending;
    private Long scansRunning;
    private Long scansSucceeded;
    private Long scansFailed;
    private Long scansCancelled;
    private Double averageScanDurationSeconds; // In seconds
    private Long totalAssetsInCatalog; // From da-catalog
    private Long assetsDiscoveredLast24h;
    private Long assetsAddedToCatalogLast24h;
    private Long assetsDiscoveredLast7d;
    private Long assetsAddedToCatalogLast7d;
    private Long scansFailedLast24h;
    private Long scansCancelledLast24h;
    private Long scansSucceededLast24h;
    private Long scansPendingLast24h;
    private Long scansRunningLast24h;
    private Long scansSubmittedLast24h;
    private Long scansSucceededLast7d;
    // Potentially add more detailed stats like per-provider counts, error rates, etc.
}
