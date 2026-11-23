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

import com.dlap.discovery.config.dto.ConnectionDiscoveryConfigDTO;
import com.dlap.discovery.config.dto.GlobalDiscoveryConfigDTO;
import com.dlap.discovery.config.service.IDiscoveryConfigService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST controller for managing discovery configurations (global and connection-specific).
 */
@RestController
@RequestMapping("/api/v1/discovery/config")
@RequiredArgsConstructor
@Slf4j
public class DiscoveryConfigController {

    private final IDiscoveryConfigService configService;

    /**
     * Retrieves the global discovery configuration.
     * @return ResponseEntity with GlobalDiscoveryConfigDTO.
     */
    @GetMapping("/global")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR')")
    public ResponseEntity<GlobalDiscoveryConfigDTO> getGlobalConfig() {
        log.debug("REST request to get global discovery configuration.");
        return ResponseEntity.ok(configService.getGlobalDiscoveryConfig());
    }

    /**
     * Updates the global discovery configuration.
     * @param dto The GlobalDiscoveryConfigDTO with new settings.
     * @return ResponseEntity with the updated GlobalDiscoveryConfigDTO.
     */
    @PutMapping("/global")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<GlobalDiscoveryConfigDTO> updateGlobalConfig(@Valid @RequestBody GlobalDiscoveryConfigDTO dto) {
        log.info("REST request to update global discovery configuration: {}", dto);
        configService.saveGlobalDiscoveryConfig(dto);
        return ResponseEntity.ok(dto);
    }

    /**
     * Retrieves the discovery configuration for a specific connection.
     * @param connectionId The ID of the cloud connection.
     * @return ResponseEntity with ConnectionDiscoveryConfigDTO if found, or 404.
     */
    @GetMapping("/connections/{connectionId}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR')")
    public ResponseEntity<ConnectionDiscoveryConfigDTO> getConnectionConfig(@PathVariable String connectionId) {
        log.debug("REST request to get discovery configuration for connection: {}", connectionId);
        return configService
            .getConnectionDiscoveryConfig(connectionId)
            .map(ResponseEntity::ok)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Configuration not found for connection: " + connectionId)
            );
    }

    /**
     * Updates or creates the discovery configuration for a specific connection.
     * @param connectionId The ID of the cloud connection.
     * @param dto The ConnectionDiscoveryConfigDTO with new settings.
     * @return ResponseEntity with the updated or created ConnectionDiscoveryConfigDTO.
     */
    @PutMapping("/connections/{connectionId}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ConnectionDiscoveryConfigDTO> updateConnectionConfig(
        @PathVariable String connectionId,
        @Valid @RequestBody ConnectionDiscoveryConfigDTO dto
    ) {
        log.info("REST request to update discovery configuration for connection {}: {}", connectionId, dto);
        return ResponseEntity.ok(configService.saveConnectionDiscoveryConfig(connectionId, dto));
    }

    /**
     * Deletes the discovery configuration for a specific connection.
     * @param connectionId The ID of the cloud connection.
     * @return ResponseEntity with status 204 (No Content).
     */
    @DeleteMapping("/connections/{connectionId}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT) // Ensures 204 is returned on success
    public ResponseEntity<Void> deleteConnectionConfig(@PathVariable String connectionId) {
        log.info("REST request to delete discovery configuration for connection: {}", connectionId);
        configService.deleteConnectionDiscoveryConfig(connectionId);
        return ResponseEntity.noContent().build();
    }
}
