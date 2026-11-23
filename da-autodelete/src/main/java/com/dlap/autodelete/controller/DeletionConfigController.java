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

package com.dlap.autodelete.controller;

import com.dlap.autodelete.dto.DeletionConfigDTO;
import com.dlap.autodelete.service.IDeletionConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/deletion/config")
@Tag(name = "Deletion Configuration API", description = "APIs for managing auto-deletion configurations")
@RequiredArgsConstructor
public class DeletionConfigController {

    private final IDeletionConfigService deletionConfigService;

    @Operation(summary = "Get current deletion configuration",
               responses = {
                   @ApiResponse(responseCode = "200", description = "Successfully retrieved configuration"),
                   @ApiResponse(responseCode = "403", description = "Forbidden if user does not have required role")
               })
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'CDO')")
    public ResponseEntity<DeletionConfigDTO> getDeletionConfiguration() {
        return ResponseEntity.ok(deletionConfigService.getDeletionConfig());
    }

    @Operation(summary = "Update deletion configuration",
               description = "Updates the global auto-deletion configuration. Only ADMINs can perform this.",
               responses = {
                   @ApiResponse(responseCode = "200", description = "Configuration updated successfully"),
                   @ApiResponse(responseCode = "400", description = "Invalid configuration provided"),
                   @ApiResponse(responseCode = "403", description = "Forbidden if user is not an ADMIN")
               })
    @PutMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> updateDeletionConfiguration(@Valid @RequestBody DeletionConfigDTO deletionConfigDTO) {
        try {
            deletionConfigService.updateDeletionConfig(deletionConfigDTO);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }
} 