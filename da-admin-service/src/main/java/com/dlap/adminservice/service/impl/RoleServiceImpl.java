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
import com.dlap.adminservice.exception.KeycloakAdminException;
import com.dlap.adminservice.mapper.RoleMapper;
import com.dlap.adminservice.service.IRoleService;
import jakarta.ws.rs.WebApplicationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.RoleRepresentation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class RoleServiceImpl implements IRoleService {

    private final Keycloak keycloakAdminClient;
    private final RoleMapper roleMapper;

    @Value("${keycloak.realm}")
    private String realmName;

    private RealmResource getRealmResource() {
        return keycloakAdminClient.realm(realmName);
    }

    @Override
    public List<RoleDTO> getAllRealmRoles() {
        log.debug("Fetching all available realm roles from Keycloak realm: {}", realmName);
        try {
            List<RoleRepresentation> roleRepresentations = getRealmResource().roles().list();
            return roleMapper.toDtoList(roleRepresentations);
        } catch (WebApplicationException e) {
            log.error("Keycloak admin error fetching available realm roles: {}", e.getMessage(), e);
            throw new KeycloakAdminException("Error fetching available realm roles: " + e.getMessage(), e);
        }
    }
} 