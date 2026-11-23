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

package com.dlap.adminservice.mapper;

import com.dlap.adminservice.dto.UserDTO;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.mapstruct.ReportingPolicy;

import java.util.Collections;
import java.util.List;

@Mapper(
    componentModel = "spring",
    unmappedTargetPolicy = ReportingPolicy.IGNORE
)
public interface UserMapper {

    @Mapping(target = "roles", source = "realmRoles") // Assuming roles are stored in realmRoles
    UserDTO toDto(UserRepresentation userRepresentation);

    List<UserDTO> toDtoList(List<UserRepresentation> userRepresentations);

    // For creating/updating users in Keycloak
    @Mapping(target = "realmRoles", source = "roles")
    @Mapping(target = "credentials", source = "password", qualifiedByName = "passwordToCredentials")
    UserRepresentation toRepresentation(UserDTO userDTO);

    @Named("passwordToCredentials")
    default List<CredentialRepresentation> passwordToCredentials(String password) {
        if (password == null || password.isEmpty()) {
            return Collections.emptyList();
        }
        CredentialRepresentation credential = new CredentialRepresentation();
        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(password);
        credential.setTemporary(false); // Set to true if password reset is required on first login
        return Collections.singletonList(credential);
    }
} 