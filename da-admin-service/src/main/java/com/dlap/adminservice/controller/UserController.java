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

import com.dlap.adminservice.dto.UserDTO;
import com.dlap.adminservice.service.IUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.keycloak.representations.idm.RoleRepresentation;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
@Tag(name = "User Management", description = "APIs for managing user accounts.")
@PreAuthorize("hasRole('ADMIN')") // All user management operations require ADMIN role
public class UserController {

    private final IUserService userService;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'CDO', 'DIRECTOR')") // read-only oversight (ADR-0032)
    @Operation(summary = "Get all users",
               description = "Retrieves a paginated list of all users.",
               responses = @ApiResponse(responseCode = "200", description = "Successfully retrieved users"))
    public ResponseEntity<List<UserDTO>> getAllUsers(
            @Parameter(description = "Offset for pagination") @RequestParam(required = false) Integer firstResult,
            @Parameter(description = "Maximum number of results per page") @RequestParam(required = false) Integer maxResults) {
        return ResponseEntity.ok(userService.getAllUsers(firstResult, maxResults));
    }

    @GetMapping("/{userId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'CDO', 'DIRECTOR')") // read-only oversight (ADR-0032)
    @Operation(summary = "Get user by ID",
               description = "Retrieves a specific user by their ID.",
               responses = {
                   @ApiResponse(responseCode = "200", description = "Successfully retrieved user"),
                   @ApiResponse(responseCode = "404", description = "User not found")
               })
    public ResponseEntity<UserDTO> getUserById(
            @Parameter(description = "ID of the user to retrieve", example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
            @PathVariable String userId) {
        return userService.getUserById(userId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
    
    @GetMapping("/username/{username}")
    @PreAuthorize("hasAnyRole('ADMIN', 'CDO', 'DIRECTOR')") // read-only oversight (ADR-0032)
    @Operation(summary = "Get user by username",
            description = "Retrieves a specific user by their username.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Successfully retrieved user"),
                    @ApiResponse(responseCode = "404", description = "User not found")
            })
    public ResponseEntity<UserDTO> getUserByUsername(
            @Parameter(description = "Username of the user to retrieve", example = "johndoe")
            @PathVariable String username) {
        return userService.getUserByUsername(username)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    @Operation(summary = "Create a new user",
               description = "Creates a new user account.",
               responses = {
                   @ApiResponse(responseCode = "201", description = "User created successfully"),
                   @ApiResponse(responseCode = "400", description = "Invalid input data"),
                   @ApiResponse(responseCode = "409", description = "User already exists")
               })
    public ResponseEntity<UserDTO> createUser(@Valid @RequestBody UserDTO userDTO) {
        UserDTO createdUser = userService.createUser(userDTO);
        return ResponseEntity.status(HttpStatus.CREATED).body(createdUser);
    }

    @PutMapping("/{userId}")
    @Operation(summary = "Update an existing user",
               description = "Updates an existing user account.",
               responses = {
                   @ApiResponse(responseCode = "200", description = "User updated successfully"),
                   @ApiResponse(responseCode = "400", description = "Invalid input data"),
                   @ApiResponse(responseCode = "404", description = "User not found")
               })
    public ResponseEntity<UserDTO> updateUser(
            @Parameter(description = "ID of the user to update") @PathVariable String userId,
            @Valid @RequestBody UserDTO userDTO) {
        UserDTO updatedUser = userService.updateUser(userId, userDTO);
        return ResponseEntity.ok(updatedUser);
    }

    @DeleteMapping("/{userId}")
    @Operation(summary = "Delete a user",
               description = "Deletes a user account by their ID.",
               responses = {
                   @ApiResponse(responseCode = "204", description = "User deleted successfully"),
                   @ApiResponse(responseCode = "404", description = "User not found")
               })
    public ResponseEntity<Void> deleteUser(
            @Parameter(description = "ID of the user to delete") @PathVariable String userId) {
        userService.deleteUser(userId);
        return ResponseEntity.noContent().build();
    }
    
    // Role Management specific to a user might be better here than a top-level /roles endpoint if it's always user-centric
    @GetMapping("/{userId}/roles")
    @PreAuthorize("hasAnyRole('ADMIN', 'CDO', 'DIRECTOR')") // read-only oversight (ADR-0032)
    @Operation(summary = "Get user's realm roles",
            description = "Retrieves the realm roles assigned to a specific user.")
    public ResponseEntity<List<RoleRepresentation>> getUserRealmRoles(@PathVariable String userId) {
        return ResponseEntity.ok(userService.getUserRealmRoles(userId));
    }

    @PostMapping("/{userId}/roles")
    @Operation(summary = "Assign realm roles to user",
            description = "Assigns a list of realm roles to a specific user. Replaces existing roles if not additive.")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void assignRealmRolesToUser(
            @PathVariable String userId,
            @Parameter(description = "List of role names to assign") @RequestBody List<String> roleNames) {
        userService.assignRealmRolesToUser(userId, roleNames);
    }
} 