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

package com.dlap.autoarchival.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dlap.autoarchival.dto.ArchivalConfigDTO;
import com.dlap.autoarchival.service.IArchivalConfigService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * REST controller for managing archival configuration.
 */
@RestController
@RequestMapping("/api/v1/archival/config")
@RequiredArgsConstructor
@Slf4j
public class ArchivalConfigController {

    private final IArchivalConfigService archivalConfigService;

    /**
     * Retrieves the current archival configuration.
     * Requires ADMIN or DATA_STEWARD role.
     *
     * @return ResponseEntity with current ArchivalConfigDTO.
     */
    @GetMapping
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR')")
    public ResponseEntity<ArchivalConfigDTO> getArchivalConfig() {
        log.info("Request to retrieve archival configuration");
        try {
            ArchivalConfigDTO config = archivalConfigService.getArchivalConfig();
            return ResponseEntity.ok(config);
        } catch (Exception e) {
            log.error("Error retrieving archival configuration: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Updates the archival configuration.
     * Requires ADMIN role.
     *
     * @param configDTO The new archival configuration.
     * @return ResponseEntity with updated configuration.
     */
    @PutMapping
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ArchivalConfigDTO> updateArchivalConfig(@Valid @RequestBody ArchivalConfigDTO configDTO) {
        log.info("Request to update archival configuration: {}", configDTO);
        try {
            archivalConfigService.updateArchivalConfig(configDTO);
            ArchivalConfigDTO updatedConfig = archivalConfigService.getArchivalConfig();
            return ResponseEntity.ok(updatedConfig);
        } catch (IllegalArgumentException e) {
            log.warn("Archival configuration refused: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("Error updating archival configuration: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }
} 