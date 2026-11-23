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

import com.dlap.discovery.log.service.ILogAnalyzer;
import com.dlap.discovery.log.service.gcp.config.GcpLogSourceType;
import com.dlap.discovery.log.service.gcp.service.IGcpLogConfigService;
import com.dlap.discovery.log.service.gcp.web.dto.ExclusionFiltersDto;
import com.dlap.discovery.log.service.gcp.web.dto.SourceDetailsDto;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for managing GCP log configuration.
 * Exposes endpoints to configure log sources and exclusion filters.
 */
@RestController
@RequestMapping("/api/v1/gcp/log-config")
@ConditionalOnProperty(name = "cloud.provider.gcp.enabled", havingValue = "true", matchIfMissing = false)
public class GcpLogConfigController {

    private final ILogAnalyzer logAnalyzer;
    private final IGcpLogConfigService logConfigService;

    /**
     * Constructor with required dependencies.
     *
     * @param logAnalyzer      The GCP log analyzer implementation
     * @param logConfigService The log configuration service
     */
    @Autowired
    public GcpLogConfigController(@Qualifier("gcpLogAnalyzer") ILogAnalyzer logAnalyzer, IGcpLogConfigService logConfigService) {
        this.logAnalyzer = logAnalyzer;
        this.logConfigService = logConfigService;
    }

    /**
     * Gets the source details for a specific account and source type.
     *
     * @param accountId  The account ID
     * @param sourceType The source type (API, BIGQUERY, GCS)
     * @return Response containing the source details
     */
    @GetMapping("/{accountId}/source/{sourceType}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<Map<String, String>> getSourceDetails(@PathVariable String accountId, @PathVariable GcpLogSourceType sourceType) {
        Map<String, String> details = logConfigService.loadSourceDetails(accountId, sourceType);
        return ResponseEntity.ok(details);
    }

    /**
     * Gets the exclusion filters for a specific account.
     *
     * @param accountId The account ID
     * @return Response containing the exclusion filters
     */
    @GetMapping("/{accountId}/filters")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<List<String>> getExclusionFilters(@PathVariable String accountId) {
        List<String> filters = logConfigService.loadExclusionFilters(accountId);
        return ResponseEntity.ok(filters);
    }

    /**
     * Sets the source details for a specific account and source type.
     *
     * @param accountId  The account ID
     * @param sourceType The source type (API, BIGQUERY, GCS)
     * @param dto        The source details DTO
     * @return Response indicating success or failure
     */
    @PostMapping("/{accountId}/source/{sourceType}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<String> setSourceDetails(
        @PathVariable String accountId,
        @PathVariable GcpLogSourceType sourceType,
        @RequestBody SourceDetailsDto dto
    ) {
        boolean success = logConfigService.saveSourceDetails(accountId, sourceType, dto.getDetails());

        if (success) {
            return ResponseEntity.ok("Source details saved successfully.");
        } else {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed to save source details.");
        }
    }

    /**
     * Sets the exclusion filters for a specific account.
     *
     * @param accountId The account ID
     * @param dto       The exclusion filters DTO
     * @return Response indicating success or failure
     */
    @PostMapping("/{accountId}/filters")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<String> setExclusionFilters(@PathVariable String accountId, @RequestBody ExclusionFiltersDto dto) {
        boolean success = logConfigService.saveExclusionFilters(accountId, dto.getFilters());

        if (success) {
            return ResponseEntity.ok("Exclusion filters saved successfully.");
        } else {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed to save exclusion filters.");
        }
    }
}
