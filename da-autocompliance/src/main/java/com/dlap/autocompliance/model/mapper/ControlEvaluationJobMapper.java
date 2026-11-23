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

package com.dlap.autocompliance.model.mapper;

import java.time.LocalDateTime;
import java.util.UUID;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

import com.dlap.autocompliance.dto.ControlEvaluationRequestDTO;
import com.dlap.autocompliance.dto.ControlEvaluationResponseDTO;
import com.dlap.autocompliance.model.entity.ControlEvaluationJobEntity;

/**
 * Mapper interface for converting between ControlEvaluationJobEntity and DTOs.
 */
@Mapper(componentModel = "spring", imports = {UUID.class, LocalDateTime.class})
public interface ControlEvaluationJobMapper {

    ControlEvaluationJobMapper INSTANCE = Mappers.getMapper(ControlEvaluationJobMapper.class);

    // To DTO (ControlEvaluationResponseDTO is simpler, doesn't carry all entity details by default)
    @Mapping(target = "targetedAssetCount", expression = "java(entity.getTargetAssetIds() != null ? entity.getTargetAssetIds().size() : 0)")
    ControlEvaluationResponseDTO toResponseDto(ControlEvaluationJobEntity entity);

    // From Request DTO to Entity
    @Mapping(target = "jobId", expression = "java(UUID.randomUUID().toString())")
    @Mapping(target = "submittedAt", expression = "java(LocalDateTime.now())")
    @Mapping(target = "status", constant = "QUEUED")
    @Mapping(target = "startedAt", ignore = true)
    @Mapping(target = "completedAt", ignore = true)
    @Mapping(target = "message", ignore = true)
    ControlEvaluationJobEntity fromRequestDto(ControlEvaluationRequestDTO requestDto, String controlId);

    // If you need a more detailed DTO for job status (like ReportJobStatusDTO), you'd create another one.
    // For now, the response DTO is used for the immediate POST response.
} 