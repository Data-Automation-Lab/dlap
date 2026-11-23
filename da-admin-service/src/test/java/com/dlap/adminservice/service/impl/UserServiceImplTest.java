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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.RoleMappingResource;
import org.keycloak.admin.client.resource.RoleResource;
import org.keycloak.admin.client.resource.RoleScopeResource;
import org.keycloak.admin.client.resource.RolesResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import com.dlap.adminservice.dto.UserDTO;
import com.dlap.adminservice.exception.ConflictException;
import com.dlap.adminservice.exception.NotFoundException;
import com.dlap.adminservice.mapper.UserMapper;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserServiceImplTest {

    @Mock
    private Keycloak keycloakAdminClient;
    @Mock
    private UserMapper userMapper;

    @InjectMocks
    private UserServiceImpl userService;

    @Mock
    private RealmResource realmResource;
    @Mock
    private UsersResource usersResource;
    @Mock
    private UserResource userResource;
    @Mock
    private RolesResource rolesResource;
    @Mock
    private RoleResource roleResource;
    @Mock
    private RoleMappingResource roleMappingResource;
    @Mock
    private RoleScopeResource roleScopeResource;

    private UserRepresentation userRepresentation;
    private UserDTO userDTO;
    private String testUserId = "test-user-id";
    private String testRealmName = "test-realm";

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(userService, "realmName", testRealmName);

        userRepresentation = new UserRepresentation();
        userRepresentation.setId(testUserId);
        userRepresentation.setUsername("testuser");
        userRepresentation.setEmail("test@example.com");

        userDTO = UserDTO.builder()
                .id(testUserId)
                .username("testuser")
                .email("test@example.com")
                .firstName("Test")
                .lastName("User")
                .enabled(true)
                .password("password") // Include password for create/update tests
                .roles(List.of("USER"))
                .build();

        // Common Keycloak resource mocking
        when(keycloakAdminClient.realm(testRealmName)).thenReturn(realmResource);
        when(realmResource.users()).thenReturn(usersResource);
    }

    @Test
    void getAllUsers_shouldReturnUserDtoList() {
        when(usersResource.list(anyInt(), anyInt())).thenReturn(Collections.singletonList(userRepresentation));
        when(userMapper.toDtoList(anyList())).thenReturn(Collections.singletonList(userDTO));

        List<UserDTO> result = userService.getAllUsers(0, 10);

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(userDTO.getUsername(), result.get(0).getUsername());
        verify(usersResource).list(0, 10);
    }
    
    @Test
    void getUserById_whenFound_shouldReturnUserDto() {
        when(usersResource.get(testUserId)).thenReturn(userResource);
        when(userResource.toRepresentation()).thenReturn(userRepresentation);
        when(userResource.roles()).thenReturn(roleMappingResource);
        when(roleMappingResource.realmLevel()).thenReturn(roleScopeResource);
        when(roleScopeResource.listEffective()).thenReturn(Collections.emptyList()); // Mock roles as needed
        when(userMapper.toDto(userRepresentation)).thenReturn(userDTO);

        Optional<UserDTO> result = userService.getUserById(testUserId);

        assertTrue(result.isPresent());
        assertEquals(userDTO.getUsername(), result.get().getUsername());
    }

    @Test
    void getUserById_whenNotFound_shouldReturnEmpty() {
        when(usersResource.get("unknown-id")).thenThrow(new jakarta.ws.rs.NotFoundException());
        Optional<UserDTO> result = userService.getUserById("unknown-id");
        assertFalse(result.isPresent());
    }
    
    @Test
    void getUserByUsername_whenFound_shouldReturnUserDto() {
        when(usersResource.searchByUsername(userDTO.getUsername(), true)).thenReturn(List.of(userRepresentation));
        when(usersResource.get(testUserId)).thenReturn(userResource);
        when(userResource.roles()).thenReturn(roleMappingResource);
        when(roleMappingResource.realmLevel()).thenReturn(roleScopeResource);
        when(roleScopeResource.listEffective()).thenReturn(Collections.emptyList());
        when(userMapper.toDto(userRepresentation)).thenReturn(userDTO);

        Optional<UserDTO> result = userService.getUserByUsername(userDTO.getUsername());

        assertTrue(result.isPresent());
        assertEquals(userDTO.getUsername(), result.get().getUsername());
    }

    @Test
    void createUser_shouldCreateAndReturnUserDto() {
        Response mockResponse = mock(Response.class);
        UriInfo mockUriInfo = mock(UriInfo.class);
        when(mockResponse.getStatus()).thenReturn(Response.Status.CREATED.getStatusCode());
        when(mockResponse.getLocation()).thenReturn(URI.create("http://localhost/users/" + testUserId));
        // when(mockResponse.getUriInfo()).thenReturn(mockUriInfo); // If using getPath()
        // when(mockUriInfo.getPath()).thenReturn("/users/" + testUserId);

        when(userMapper.toRepresentation(any(UserDTO.class))).thenReturn(userRepresentation);
        when(usersResource.create(any(UserRepresentation.class))).thenReturn(mockResponse);
        
        // Mock the follow-up getUserById call
        when(usersResource.get(testUserId)).thenReturn(userResource);
        when(userResource.toRepresentation()).thenReturn(userRepresentation);
        when(userResource.roles()).thenReturn(roleMappingResource);
        when(roleMappingResource.realmLevel()).thenReturn(roleScopeResource);
        when(roleScopeResource.listEffective()).thenReturn(Collections.emptyList());
        when(userMapper.toDto(userRepresentation)).thenReturn(userDTO);
         // Mock roles part
        when(realmResource.roles()).thenReturn(rolesResource);
        RoleRepresentation roleRep = new RoleRepresentation("USER", null, false);
        when(rolesResource.get("USER")).thenReturn(roleResource);
        when(roleResource.toRepresentation()).thenReturn(roleRep);


        UserDTO createdUser = userService.createUser(userDTO);

        assertNotNull(createdUser);
        assertEquals(userDTO.getUsername(), createdUser.getUsername());
        verify(usersResource).create(userRepresentation);
        verify(userResource.roles().realmLevel()).add(anyList());
    }
    
    @Test
    void createUser_whenConflict_shouldThrowConflictException() {
        Response mockResponse = mock(Response.class);
        when(mockResponse.getStatus()).thenReturn(Response.Status.CONFLICT.getStatusCode());

        when(userMapper.toRepresentation(any(UserDTO.class))).thenReturn(userRepresentation);
        when(usersResource.create(any(UserRepresentation.class))).thenReturn(mockResponse);

        assertThrows(ConflictException.class, () -> userService.createUser(userDTO));
    }

    @Test
    void updateUser_shouldUpdateAndReturnUserDto() {
        UserDTO updateRequest = UserDTO.builder()
                                .id(testUserId)
                                .firstName("Updated")
                                .lastName("Name")
                                .email("updated@example.com")
                                .enabled(false)
                                .roles(List.of("ADMIN"))
                                .build();
        
        UserRepresentation existingUserRep = new UserRepresentation();
        existingUserRep.setId(testUserId);
        existingUserRep.setUsername("testuser"); 
        existingUserRep.setFirstName("Test");
        existingUserRep.setLastName("User");
        existingUserRep.setEmail("test@example.com");
        existingUserRep.setEnabled(true);
        
        UserRepresentation updatedUserRep = new UserRepresentation(); 
        updatedUserRep.setId(testUserId);
        updatedUserRep.setUsername("testuser");
        updatedUserRep.setFirstName("Updated");
        updatedUserRep.setLastName("Name");
        updatedUserRep.setEmail("updated@example.com");
        updatedUserRep.setEnabled(false);

        // Mock the initial fetch and update operations
        when(usersResource.get(testUserId)).thenReturn(userResource);
        when(userResource.toRepresentation()).thenReturn(existingUserRep);
        when(userMapper.toRepresentation(updateRequest)).thenReturn(updatedUserRep);
        
        doNothing().when(userResource).update(any(UserRepresentation.class));

        // Mock role operations
        when(userResource.roles()).thenReturn(roleMappingResource);
        when(roleMappingResource.realmLevel()).thenReturn(roleScopeResource);
        when(roleScopeResource.listEffective()).thenReturn(Collections.singletonList(new RoleRepresentation("USER",null,false)));

        when(realmResource.roles()).thenReturn(rolesResource);
        RoleRepresentation adminRoleRep = new RoleRepresentation("ADMIN", null, false);
        when(rolesResource.get("ADMIN")).thenReturn(roleResource);
        when(roleResource.toRepresentation()).thenReturn(adminRoleRep);
        
        doNothing().when(roleScopeResource).add(anyList());
        doNothing().when(roleScopeResource).remove(anyList());

        // Mock the final getUserById call - create a separate mock chain
        UserResource finalUserResource = mock(UserResource.class);
        RoleMappingResource finalRoleMappingResource = mock(RoleMappingResource.class);
        RoleScopeResource finalRoleScopeResource = mock(RoleScopeResource.class);
        
        // The updateUser method calls getUserById at the end, which needs fresh mocks
        when(usersResource.get(testUserId)).thenReturn(userResource, finalUserResource);
        when(finalUserResource.toRepresentation()).thenReturn(updatedUserRep);
        when(finalUserResource.roles()).thenReturn(finalRoleMappingResource);
        when(finalRoleMappingResource.realmLevel()).thenReturn(finalRoleScopeResource);
        when(finalRoleScopeResource.listEffective()).thenReturn(Collections.singletonList(new RoleRepresentation("ADMIN",null,false)));
        
        UserDTO finalResult = UserDTO.builder()
                .id(testUserId)
                .username("testuser")
                .firstName("Updated")
                .lastName("Name")
                .email("updated@example.com")
                .enabled(false)
                .roles(List.of("ADMIN"))
                .build();
        when(userMapper.toDto(updatedUserRep)).thenReturn(finalResult);

        UserDTO result = userService.updateUser(testUserId, updateRequest);

        assertNotNull(result);
        assertEquals("Updated", result.getFirstName());
        assertFalse(result.isEnabled());
        verify(userResource).update(any(UserRepresentation.class));
        verify(roleScopeResource).add(anyList());
        verify(roleScopeResource).remove(anyList());
    }

    @Test
    void deleteUser_shouldCallRemove() {
        when(usersResource.get(testUserId)).thenReturn(userResource);
        doNothing().when(userResource).remove();
        userService.deleteUser(testUserId);
        verify(userResource).remove();
    }

    @Test
    void deleteUser_whenNotFound_shouldThrowNotFound() {
        when(usersResource.get("unknown-id")).thenThrow(new jakarta.ws.rs.NotFoundException());
        assertThrows(NotFoundException.class, () -> userService.deleteUser("unknown-id"));
    }
    
    @Test
    void assignRealmRolesToUser_shouldAddRoles() {
        when(usersResource.get(testUserId)).thenReturn(userResource);
        when(realmResource.roles()).thenReturn(rolesResource);
        
        RoleRepresentation roleRep = new RoleRepresentation("NEW_ROLE", null, false);
        when(rolesResource.get("NEW_ROLE")).thenReturn(roleResource);
        when(roleResource.toRepresentation()).thenReturn(roleRep);

        when(userResource.roles()).thenReturn(roleMappingResource);
        when(roleMappingResource.realmLevel()).thenReturn(roleScopeResource);
        doNothing().when(roleScopeResource).add(anyList());

        userService.assignRealmRolesToUser(testUserId, List.of("NEW_ROLE"));
        verify(roleScopeResource).add(List.of(roleRep));
    }
    
    @Test
    void getAvailableRealmRoles_shouldReturnRoleRepresentations() {
        RoleRepresentation roleRep = new RoleRepresentation("TEST_ROLE", "A test role", false);
        when(realmResource.roles()).thenReturn(rolesResource);
        when(rolesResource.list()).thenReturn(Collections.singletonList(roleRep));
        
        List<RoleRepresentation> roles = userService.getAvailableRealmRoles();
        
        assertNotNull(roles);
        assertEquals(1, roles.size());
        assertEquals("TEST_ROLE", roles.get(0).getName());
    }

    @Test
    void getUserRealmRoles_shouldReturnUserRoles() {
        RoleRepresentation roleRep = new RoleRepresentation("ASSIGNED_ROLE", null, false);
        when(usersResource.get(testUserId)).thenReturn(userResource);
        when(userResource.roles()).thenReturn(roleMappingResource);
        when(roleMappingResource.realmLevel()).thenReturn(roleScopeResource);
        when(roleScopeResource.listEffective()).thenReturn(Collections.singletonList(roleRep));
        
        List<RoleRepresentation> roles = userService.getUserRealmRoles(testUserId);
        
        assertNotNull(roles);
        assertEquals(1, roles.size());
        assertEquals("ASSIGNED_ROLE", roles.get(0).getName());
    }
} 