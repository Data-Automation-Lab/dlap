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

package com.dlap.adminservice.service.impl;

import com.dlap.adminservice.dto.UserDTO;
import com.dlap.adminservice.exception.ConflictException;
import com.dlap.adminservice.exception.KeycloakAdminException;
import com.dlap.adminservice.exception.NotFoundException;
import com.dlap.adminservice.mapper.UserMapper;
import com.dlap.adminservice.service.IUserService;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.RoleMappingResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserServiceImpl implements IUserService {

    private final Keycloak keycloakAdminClient;
    private final UserMapper userMapper;

    @Value("${keycloak.realm}")
    private String realmName;

    private RealmResource getRealmResource() {
        return keycloakAdminClient.realm(realmName);
    }

    private UsersResource getUsersResource() {
        return getRealmResource().users();
    }

    @Override
    public List<UserDTO> getAllUsers(Integer firstResult, Integer maxResults) {
        log.debug("Fetching all users from Keycloak. First result: {}, Max results: {}", firstResult, maxResults);
        try {
            List<UserRepresentation> userRepresentations = getUsersResource().list(firstResult, maxResults);
            return userMapper.toDtoList(userRepresentations);
        } catch (WebApplicationException e) {
            log.error("Keycloak admin error while fetching all users: {}", e.getMessage(), e);
            throw new KeycloakAdminException("Error fetching users from Keycloak: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<UserDTO> getUserById(String userId) {
        log.debug("Fetching user by ID: {}", userId);
        try {
            UserResource userResource = getUsersResource().get(userId);
            UserRepresentation userRepresentation = userResource.toRepresentation();
            // Enhance with realm roles
            List<RoleRepresentation> realmRoles = userResource.roles().realmLevel().listEffective();
            userRepresentation.setRealmRoles(realmRoles.stream().map(RoleRepresentation::getName).collect(Collectors.toList()));
            return Optional.of(userMapper.toDto(userRepresentation));
        } catch (jakarta.ws.rs.NotFoundException e) {
            log.warn("User not found in Keycloak with ID: {}", userId);
            return Optional.empty();
        } catch (WebApplicationException e) {
            log.error("Keycloak admin error while fetching user by ID {}: {}", userId, e.getMessage(), e);
            throw new KeycloakAdminException("Error fetching user " + userId + " from Keycloak: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<UserDTO> getUserByUsername(String username) {
        log.debug("Fetching user by username: {}", username);
        try {
            List<UserRepresentation> users = getUsersResource().searchByUsername(username, true);
            if (users.isEmpty()) {
                return Optional.empty();
            }
            // Assuming username is unique, take the first one.
            UserRepresentation userRepresentation = users.get(0);
            UserResource userResource = getUsersResource().get(userRepresentation.getId());
            List<RoleRepresentation> realmRoles = userResource.roles().realmLevel().listEffective();
            userRepresentation.setRealmRoles(realmRoles.stream().map(RoleRepresentation::getName).collect(Collectors.toList()));
            return Optional.of(userMapper.toDto(userRepresentation));
        } catch (WebApplicationException e) {
            log.error("Keycloak admin error while fetching user by username {}: {}", username, e.getMessage(), e);
            throw new KeycloakAdminException("Error fetching user " + username + " from Keycloak: " + e.getMessage(), e);
        }
    }

    @Override
    @Transactional
    public UserDTO createUser(UserDTO userDTO) {
        log.info("Creating new user in Keycloak with username: {}", userDTO.getUsername());
        UserRepresentation userRepresentation = userMapper.toRepresentation(userDTO);
        userRepresentation.setEnabled(userDTO.isEnabled()); // Ensure enabled status is set

        String createdUserId = null;
        try (Response response = getUsersResource().create(userRepresentation)) {
            if (response.getStatus() == Response.Status.CREATED.getStatusCode()) {
                createdUserId = response.getLocation().getPath().replaceAll(".*/([^/]+)$", "$1");
                log.info("User created successfully in Keycloak with ID: {}", createdUserId);
            } else if (response.getStatus() == Response.Status.CONFLICT.getStatusCode()) {
                log.warn("User creation conflict in Keycloak (username/email might exist): {}", userDTO.getUsername());
                throw new ConflictException("User with username '" + userDTO.getUsername() + "' or email '" + userDTO.getEmail() + "' may already exist.");
            } else {
                String errorDetails = response.readEntity(String.class);
                log.error("Failed to create user in Keycloak. Status: {}, Details: {}", response.getStatus(), errorDetails);
                throw new KeycloakAdminException("Failed to create user in Keycloak. Status: " + response.getStatus() + ", Details: " + errorDetails, null);
            }
        } catch (WebApplicationException e) {
            log.error("Keycloak admin error during user creation: {}", e.getMessage(), e);
            throw new KeycloakAdminException("Error creating user in Keycloak: " + e.getMessage(), e);
        }

        if (createdUserId == null) {
            // Defensive: the flow above should have either set the id or thrown already.
            throw new KeycloakAdminException("Keycloak did not return an ID for the newly created user", null);
        }

        // Role assignment is a separate call; if it fails, compensate by removing the
        // Keycloak user to keep both systems consistent (Keycloak operations are NOT
        // enrolled in the local DB transaction).
        final String newUserId = createdUserId;
        try {
            if (userDTO.getRoles() != null && !userDTO.getRoles().isEmpty()) {
                assignRealmRolesToUser(newUserId, userDTO.getRoles().stream().collect(Collectors.toList()));
            }
            return getUserById(newUserId).orElseThrow(() ->
                new KeycloakAdminException("Failed to retrieve newly created user " + newUserId, null));
        } catch (RuntimeException ex) {
            log.error("Post-create step failed for user {}; compensating by deleting the Keycloak user", newUserId, ex);
            try {
                getUsersResource().get(newUserId).remove();
                log.info("Compensating delete succeeded for Keycloak user {}", newUserId);
            } catch (Exception cleanupEx) {
                log.error("Compensating delete FAILED for Keycloak user {}; manual cleanup required", newUserId, cleanupEx);
            }
            throw ex;
        }
    }

    @Override
    public UserDTO updateUser(String userId, UserDTO userDTO) {
        log.info("Updating user in Keycloak with ID: {}", userId);
        UserResource userResource = getUsersResource().get(userId);
        try {
            UserRepresentation existingUserRep = userResource.toRepresentation(); // Fetch current state
            
            // Map DTO to representation, but don't overwrite username or credentials directly unless password is set
            UserRepresentation updatedRep = userMapper.toRepresentation(userDTO);
            updatedRep.setUsername(existingUserRep.getUsername()); // Username cannot be changed this way usually
            
            // Only update password if provided. UserMapper.passwordToCredentials returns
            // an empty list (never null) for a blank/null password, so we must guard
            // against an empty credentials list before indexing.
            if (userDTO.getPassword() != null && !userDTO.getPassword().isEmpty()
                    && updatedRep.getCredentials() != null && !updatedRep.getCredentials().isEmpty()) {
                 userResource.resetPassword(updatedRep.getCredentials().get(0));
                 log.debug("Password updated for user ID: {}", userId);
            }

            // Update other attributes
            existingUserRep.setFirstName(userDTO.getFirstName());
            existingUserRep.setLastName(userDTO.getLastName());
            existingUserRep.setEmail(userDTO.getEmail());
            existingUserRep.setEnabled(userDTO.isEnabled());
            // Keycloak might require emailVerified to be true if email is changed
            // existingUserRep.setEmailVerified(true); 

            userResource.update(existingUserRep);
            log.info("User attributes updated for ID: {}", userId);

            // Update roles
            if (userDTO.getRoles() != null) {
                // Get current roles
                List<RoleRepresentation> currentRealmRoles = userResource.roles().realmLevel().listEffective();
                List<String> currentRoleNames = currentRealmRoles.stream().map(RoleRepresentation::getName).collect(Collectors.toList());

                // Roles to add
                List<RoleRepresentation> rolesToAdd = userDTO.getRoles().stream()
                    .filter(roleName -> !currentRoleNames.contains(roleName))
                    .map(roleName -> getRealmResource().roles().get(roleName).toRepresentation())
                    .collect(Collectors.toList());
                if (!rolesToAdd.isEmpty()) {
                    userResource.roles().realmLevel().add(rolesToAdd);
                    log.debug("Added roles {} to user ID: {}", rolesToAdd.stream().map(RoleRepresentation::getName).collect(Collectors.toList()), userId);
                }

                // Roles to remove
                List<RoleRepresentation> rolesToRemove = currentRealmRoles.stream()
                    .filter(roleRep -> !userDTO.getRoles().contains(roleRep.getName()))
                    .collect(Collectors.toList());
                if (!rolesToRemove.isEmpty()) {
                     userResource.roles().realmLevel().remove(rolesToRemove);
                     log.debug("Removed roles {} from user ID: {}", rolesToRemove.stream().map(RoleRepresentation::getName).collect(Collectors.toList()), userId);
                }
            }

            return getUserById(userId).orElseThrow(() -> 
                new KeycloakAdminException("Failed to retrieve updated user " + userId, null));
        } catch (jakarta.ws.rs.NotFoundException e) {
            log.warn("User not found in Keycloak for update, ID: {}", userId);
            throw new NotFoundException("User with ID '" + userId + "' not found.");
        } catch (WebApplicationException e) {
            log.error("Keycloak admin error during user {} update: {}", userId, e.getMessage(), e);
            throw new KeycloakAdminException("Error updating user " + userId + " in Keycloak: " + e.getMessage(), e);
        }
    }

    @Override
    public void deleteUser(String userId) {
        log.info("Deleting user from Keycloak with ID: {}", userId);
        try {
            getUsersResource().get(userId).remove();
            log.info("User with ID: {} deleted successfully from Keycloak.", userId);
        } catch (jakarta.ws.rs.NotFoundException e) {
            log.warn("User not found in Keycloak for deletion, ID: {}", userId);
            // Optionally throw NotFoundException or just log if delete is idempotent
            throw new NotFoundException("User with ID '" + userId + "' not found for deletion.");
        } catch (WebApplicationException e) {
            log.error("Keycloak admin error during user {} deletion: {}", userId, e.getMessage(), e);
            throw new KeycloakAdminException("Error deleting user " + userId + " from Keycloak: " + e.getMessage(), e);
        }
    }
    
    @Override
    public void assignRealmRolesToUser(String userId, List<String> roleNames) {
        log.info("Assigning realm roles {} to user ID: {}", roleNames, userId);
        // Keycloak's UsersResource.get(userId) returns a proxy that throws on access
        // to a non-existent user; it never returns null, so no null-check is needed here.
        UserResource userResource = getUsersResource().get(userId);
        List<RoleRepresentation> rolesToAssign = roleNames.stream()
            .map(roleName -> {
                try {
                    return getRealmResource().roles().get(roleName).toRepresentation();
                } catch (jakarta.ws.rs.NotFoundException e) {
                    log.warn("Role '{}' not found in realm '{}' during assignment to user {}", roleName, realmName, userId);
                    return null; // Or throw an exception for invalid role
                }
            })
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toList());

        if (!rolesToAssign.isEmpty()) {
            try {
                userResource.roles().realmLevel().add(rolesToAssign);
                log.info("Successfully assigned roles {} to user ID: {}", roleNames, userId);
            } catch (WebApplicationException e) {
                log.error("Keycloak admin error assigning roles to user {}: {}", userId, e.getMessage(), e);
                throw new KeycloakAdminException("Error assigning roles to user " + userId + ": " + e.getMessage(), e);
            }
        }
    }

    @Override
    public List<RoleRepresentation> getAvailableRealmRoles() {
        log.debug("Fetching all available realm roles from Keycloak realm: {}", realmName);
        try {
            return getRealmResource().roles().list();
        } catch (WebApplicationException e) {
            log.error("Keycloak admin error fetching available realm roles: {}", e.getMessage(), e);
            throw new KeycloakAdminException("Error fetching available realm roles: " + e.getMessage(), e);
        }
    }

    @Override
    public List<RoleRepresentation> getUserRealmRoles(String userId) {
        log.debug("Fetching realm roles for user ID: {}", userId);
        try {
            UserResource userResource = getUsersResource().get(userId);
            return userResource.roles().realmLevel().listEffective();
        } catch (jakarta.ws.rs.NotFoundException e) {
            log.warn("User not found in Keycloak with ID: {} when fetching roles", userId);
            throw new NotFoundException("User with ID '" + userId + "' not found when fetching roles.");
        } catch (WebApplicationException e) {
            log.error("Keycloak admin error fetching roles for user {}: {}", userId, e.getMessage(), e);
            throw new KeycloakAdminException("Error fetching roles for user " + userId + ": " + e.getMessage(), e);
        }
    }
} 