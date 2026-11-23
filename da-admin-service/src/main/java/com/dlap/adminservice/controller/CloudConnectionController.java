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
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dlap.adminservice.dto.CloudConnectionDTO;
import com.dlap.adminservice.dto.CloudConnectionTestResultDTO;
import com.dlap.adminservice.service.ICloudConnectionService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * REST controller for managing cloud connections
 */
@RestController
@RequestMapping("/api/v1/admin/cloud-connections")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Cloud Connection Management", description = "APIs for managing cloud connections")
public class CloudConnectionController {

    private final ICloudConnectionService cloudConnectionService;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SERVICE_ACCOUNT')") // + M2M service account (da-discovery scan path, ADR-0034)
    @Operation(summary = "Get all cloud connections")
    public ResponseEntity<List<CloudConnectionDTO>> getAllCloudConnections() {
        log.debug("Getting all cloud connections");
        List<CloudConnectionDTO> connections = cloudConnectionService.getAllCloudConnections();
        return ResponseEntity.ok(connections);
    }

    @GetMapping("/{connectionId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SERVICE_ACCOUNT')") // + M2M service account (da-discovery scan path, ADR-0034)
    @Operation(summary = "Get cloud connection by ID")
    public ResponseEntity<CloudConnectionDTO> getCloudConnectionById(@PathVariable String connectionId) {
        log.debug("Getting cloud connection with id: {}", connectionId);
        Optional<CloudConnectionDTO> connection = cloudConnectionService.getCloudConnectionById(connectionId);
        return connection.map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Create new cloud connection")
    public ResponseEntity<CloudConnectionDTO> createCloudConnection(@Valid @RequestBody CloudConnectionDTO cloudConnectionDTO) {
        log.debug("Creating new cloud connection: {}", cloudConnectionDTO.getName());
        CloudConnectionDTO created = cloudConnectionService.createCloudConnection(cloudConnectionDTO);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{connectionId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Update cloud connection")
    public ResponseEntity<CloudConnectionDTO> updateCloudConnection(
            @PathVariable String connectionId,
            @Valid @RequestBody CloudConnectionDTO cloudConnectionDTO) {
        log.debug("Updating cloud connection with id: {}", connectionId);
        CloudConnectionDTO updated = cloudConnectionService.updateCloudConnection(connectionId, cloudConnectionDTO);
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{connectionId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Delete cloud connection")
    public ResponseEntity<Void> deleteCloudConnection(@PathVariable String connectionId) {
        log.debug("Deleting cloud connection with id: {}", connectionId);
        cloudConnectionService.deleteCloudConnection(connectionId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{connectionId}/test")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Test cloud connection")
    public ResponseEntity<CloudConnectionTestResultDTO> testCloudConnection(@PathVariable String connectionId) {
        log.debug("Testing cloud connection with id: {}", connectionId);
        CloudConnectionTestResultDTO result = cloudConnectionService.testCloudConnection(connectionId);
        return ResponseEntity.ok(result);
    }
} 