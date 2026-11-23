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

import com.dlap.adminservice.dto.RoleDTO;
import com.dlap.adminservice.mapper.RoleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.RolesResource;
import org.keycloak.representations.idm.RoleRepresentation;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyList;

@ExtendWith(MockitoExtension.class)
class RoleServiceImplTest {

    @Mock
    private Keycloak keycloakAdminClient;
    @Mock
    private RoleMapper roleMapper;

    @InjectMocks
    private RoleServiceImpl roleService;

    @Mock
    private RealmResource realmResource;
    @Mock
    private RolesResource rolesResource;

    private String testRealmName = "test-realm";
    private RoleRepresentation roleRepresentation;
    private RoleDTO roleDTO;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(roleService, "realmName", testRealmName);

        roleRepresentation = new RoleRepresentation();
        roleRepresentation.setId("role-id");
        roleRepresentation.setName("TEST_ROLE");
        roleRepresentation.setDescription("A test role");

        roleDTO = RoleDTO.builder()
                .id("role-id")
                .name("TEST_ROLE")
                .description("A test role")
                .build();
        
        when(keycloakAdminClient.realm(testRealmName)).thenReturn(realmResource);
        when(realmResource.roles()).thenReturn(rolesResource);
    }

    @Test
    void getAllRealmRoles_shouldReturnRoleDtoList() {
        when(rolesResource.list()).thenReturn(Collections.singletonList(roleRepresentation));
        when(roleMapper.toDtoList(anyList())).thenReturn(Collections.singletonList(roleDTO));

        List<RoleDTO> result = roleService.getAllRealmRoles();

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(roleDTO.getName(), result.get(0).getName());
        verify(rolesResource).list();
        verify(roleMapper).toDtoList(Collections.singletonList(roleRepresentation));
    }
} 