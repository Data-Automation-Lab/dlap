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

import com.dlap.autoarchival.dto.ArchivalTaskStatusDTO;
import com.dlap.autoarchival.dto.ArchivalTaskResponse;
import com.dlap.autoarchival.dto.ArchivalTaskRequest;
import com.dlap.autoarchival.dto.RestoreRequestDTO;
import com.dlap.autoarchival.model.ArchivalTaskEntity;
import org.mapstruct.Mapper;
import org.springframework.stereotype.Component;

/**
 * Mapper for converting between ArchivalTaskEntity and DTOs.
 */
@Mapper(componentModel = "spring")
@Component
public interface ArchivalTaskMapper {
    
    // Methods expected by the service implementation
    ArchivalTaskEntity requestToEntity(ArchivalTaskRequest request);
    ArchivalTaskResponse entityToTaskResponse(ArchivalTaskEntity entity);
    ArchivalTaskStatusDTO entityToStatusDTO(ArchivalTaskEntity entity);
    ArchivalTaskEntity.RestoreRequestData restoreRequestDtoToData(RestoreRequestDTO dto);
    
    // Original methods
    ArchivalTaskStatusDTO toStatusDTO(ArchivalTaskEntity entity);
    ArchivalTaskResponse toResponseDTO(ArchivalTaskEntity entity);
    
} 