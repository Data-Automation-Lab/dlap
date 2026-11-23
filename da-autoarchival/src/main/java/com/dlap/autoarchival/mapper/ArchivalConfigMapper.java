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

package com.dlap.autoarchival.mapper;

import com.dlap.autoarchival.dto.ArchivalConfigDTO;
import com.dlap.autoarchival.model.ArchivalConfigEntity;
import org.mapstruct.Mapper;
import org.mapstruct.MappingTarget;
import org.springframework.stereotype.Component;

/**
 * Mapper for converting between ArchivalConfigEntity and ArchivalConfigDTO.
 */
@Mapper(componentModel = "spring")
@Component
public interface ArchivalConfigMapper {
    
    // Methods expected by the service implementation
    ArchivalConfigDTO toDto(ArchivalConfigEntity entity);
    void updateEntityFromDto(ArchivalConfigDTO dto, @MappingTarget ArchivalConfigEntity entity);
    
    // Original methods
    ArchivalConfigDTO toDTO(ArchivalConfigEntity entity);
    ArchivalConfigEntity toEntity(ArchivalConfigDTO dto);
    
} 