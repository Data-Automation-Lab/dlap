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

import com.dlap.adminservice.dto.CloudConnectionDTO;
import com.dlap.adminservice.model.CloudConnectionEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

import java.util.List;

/**
 * Mapper interface for converting between CloudConnectionEntity and CloudConnectionDTO.
 */
@Mapper(
    componentModel = "spring",
    unmappedTargetPolicy = ReportingPolicy.IGNORE,
    nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE
)
public interface CloudConnectionMapper {

    CloudConnectionMapper INSTANCE = Mappers.getMapper(CloudConnectionMapper.class);

    // When mapping from Entity to DTO, ignore encryptedCredentials
    @Mapping(target = "sensitiveCredentials", ignore = true)
    CloudConnectionDTO toDto(CloudConnectionEntity entity);

    List<CloudConnectionDTO> toDtoList(List<CloudConnectionEntity> entities);

    // When mapping from DTO to Entity for creation/update, sensitiveCredentials from DTO will be handled by service for encryption
    // The encryptedCredentials field in entity will be set by the service, not directly by mapper from DTO.sensitiveCredentials
    @Mapping(target = "id", ignore = true) // ID is generated or path param
    @Mapping(target = "encryptedCredentials", ignore = true) // Handled by service
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "createdDate", ignore = true)
    @Mapping(target = "lastModifiedBy", ignore = true)
    @Mapping(target = "lastModifiedDate", ignore = true)
    @Mapping(target = "lastConnectionTestAt", ignore = true) // These are set by the system
    @Mapping(target = "lastConnectionTestStatus", ignore = true)
    void updateEntityFromDto(CloudConnectionDTO dto, @MappingTarget CloudConnectionEntity entity);

    @Mapping(target = "id", ignore = true) // ID is generated
    @Mapping(target = "encryptedCredentials", ignore = true) // Handled by service
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "createdDate", ignore = true)
    @Mapping(target = "lastModifiedBy", ignore = true)
    @Mapping(target = "lastModifiedDate", ignore = true)
    @Mapping(target = "lastConnectionTestAt", ignore = true)
    @Mapping(target = "lastConnectionTestStatus", ignore = true)
    CloudConnectionEntity toEntity(CloudConnectionDTO dto);
} 