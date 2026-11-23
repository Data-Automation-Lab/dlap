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

package com.dlap.adminservice.controller;

import java.util.List;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dlap.adminservice.dto.ServiceConfigDTO;
import com.dlap.adminservice.service.IServiceConfigService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * REST controller for managing service configurations
 */
@RestController
@RequestMapping("/api/v1/admin/config/services")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Service Configuration Management", description = "APIs for managing service configurations")
public class ServiceConfigController {

    private final IServiceConfigService serviceConfigService;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Get all service configurations")
    public ResponseEntity<List<ServiceConfigDTO>> getAllServiceConfigs() {
        log.debug("Getting all service configurations");
        List<ServiceConfigDTO> configs = serviceConfigService.getAllServiceConfigs();
        return ResponseEntity.ok(configs);
    }

    @GetMapping("/{serviceId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Get service configuration by service ID")
    public ResponseEntity<ServiceConfigDTO> getServiceConfigById(@PathVariable String serviceId) {
        log.debug("Getting service configuration for service: {}", serviceId);
        Optional<ServiceConfigDTO> config = serviceConfigService.getServiceConfigById(serviceId);
        return config.map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Create new service configuration")
    public ResponseEntity<ServiceConfigDTO> createServiceConfig(@Valid @RequestBody ServiceConfigDTO serviceConfigDTO) {
        log.debug("Creating new service configuration for service: {}", serviceConfigDTO.getServiceId());
        ServiceConfigDTO created = serviceConfigService.createServiceConfig(serviceConfigDTO);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{serviceId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Update service configuration")
    public ResponseEntity<ServiceConfigDTO> updateServiceConfig(
            @PathVariable String serviceId,
            @Valid @RequestBody ServiceConfigDTO serviceConfigDTO) {
        log.debug("Updating service configuration for service: {}", serviceId);
        ServiceConfigDTO updated = serviceConfigService.updateServiceConfig(serviceId, serviceConfigDTO);
        return ResponseEntity.ok(updated);
    }
} 