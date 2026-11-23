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

import com.dlap.adminservice.dto.RoleDTO;
import com.dlap.adminservice.service.IRoleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/roles")
@RequiredArgsConstructor
@Tag(name = "Role Management", description = "APIs for viewing available roles.")
@PreAuthorize("hasRole('ADMIN')")
public class RoleController {

    private final IRoleService roleService;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'CDO', 'DIRECTOR')") // read-only oversight (ADR-0032)
    @Operation(summary = "Get all available realm roles",
               description = "Retrieves a list of all available roles in the configured Keycloak realm.",
               responses = @ApiResponse(responseCode = "200", description = "Successfully retrieved roles"))
    public ResponseEntity<List<RoleDTO>> getAllRealmRoles() {
        return ResponseEntity.ok(roleService.getAllRealmRoles());
    }
} 