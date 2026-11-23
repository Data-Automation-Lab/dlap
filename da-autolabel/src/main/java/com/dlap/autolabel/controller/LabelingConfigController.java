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

package com.dlap.autolabel.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

import com.dlap.llm.client.config.MLConfigRequest;
import com.dlap.autolabel.service.IMLConfigService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * REST controller for managing AutoLabel ML configurations.
 */
@RestController
@RequestMapping("/api/v1/labeling")
@RequiredArgsConstructor
@Slf4j
public class LabelingConfigController {

    private final IMLConfigService mlConfigService;

    /**
     * Updates the Machine Learning (ML) configuration for auto-labeling.
     * Requires ADMIN role.
     *
     * @param mlConfigRequest The ML configuration request.
     * @return ResponseEntity indicating success or failure.
     */
    @PutMapping("/config/ml")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<Void> updateMlConfiguration(@Valid @RequestBody MLConfigRequest mlConfigRequest) {
        log.info("Received request to update ML configuration: {}", mlConfigRequest);
        try {
            mlConfigService.updateMlConfig(mlConfigRequest);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            log.error("Invalid ML configuration provided: {}", e.getMessage());
            return ResponseEntity.badRequest().build(); // Or a more specific error response
        } catch (Exception e) {
            log.error("Error updating ML configuration: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Retrieves the current Machine Learning (ML) configuration for auto-labeling.
     * Requires ADMIN role.
     *
     * @return ResponseEntity with the current MLConfigRequest or not found/error.
     */
    @GetMapping("/config/ml")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<MLConfigRequest> getMlConfiguration() {
        log.info("Received request to get ML configuration.");
        try {
            Optional<MLConfigRequest> config = mlConfigService.getMlConfig();
            return config
                    .map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (Exception e) {
            log.error("Error retrieving ML configuration: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
} 