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

package com.dlap.discovery.client.rest;

import com.dlap.discovery.client.rest.dto.DiscoveryStatsDTO;
import com.dlap.discovery.stats.service.IDiscoveryStatsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for retrieving discovery statistics.
 */
@RestController
@RequestMapping("/api/v1/discovery/statistics")
@RequiredArgsConstructor
@Slf4j
public class DiscoveryStatsController {

    private final IDiscoveryStatsService discoveryStatsService;

    /**
     * Retrieves aggregated statistics for the discovery service.
     * Requires appropriate permissions (e.g., ADMIN, DATA_STEWARD, or a specific monitoring role).
     *
     * @return ResponseEntity with DiscoveryStatsDTO.
     */
    @GetMapping
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST')") // Adjust roles as needed
    public ResponseEntity<DiscoveryStatsDTO> getDiscoveryStats() {
        log.info("REST request to get discovery statistics.");
        DiscoveryStatsDTO stats = discoveryStatsService.getDiscoveryStats();
        return ResponseEntity.ok(stats);
    }
}
