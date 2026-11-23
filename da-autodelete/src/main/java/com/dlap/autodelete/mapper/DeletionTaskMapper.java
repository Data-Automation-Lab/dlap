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

package com.dlap.autodelete.mapper;

import com.dlap.autodelete.dto.DeletionTaskRequest;
import com.dlap.autodelete.dto.DeletionTaskResponse;
import com.dlap.autodelete.dto.DeletionTaskStatusDTO;
import com.dlap.autodelete.model.DeletionTaskEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.NullValuePropertyMappingStrategy;
import org.mapstruct.factory.Mappers;

@Mapper(componentModel = "spring",
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
public interface DeletionTaskMapper {

    DeletionTaskMapper INSTANCE = Mappers.getMapper(DeletionTaskMapper.class);

    // From Request DTO to Entity
    @Mapping(target = "taskId", ignore = true) // taskId is generated in service
    @Mapping(target = "status", ignore = true) // status is set in service
    @Mapping(target = "submittedAt", ignore = true)
    @Mapping(target = "startedAt", ignore = true)
    @Mapping(target = "completedAt", ignore = true)
    @Mapping(target = "totalAssetsInScope", ignore = true)
    @Mapping(target = "assetsProcessedSuccessfully", ignore = true)
    @Mapping(target = "assetsFailedToProcess", ignore = true)
    @Mapping(target = "assetsPendingProcessing", ignore = true)
    @Mapping(target = "assetStatuses", ignore = true)
    @Mapping(target = "errorMessages", ignore = true)
    @Mapping(target = "approvalComments", ignore = true)
    @Mapping(target = "rejectionComments", ignore = true)
    @Mapping(target = "requestedBy", ignore = true) // RET-02: the JWT subject, set in the service
    @Mapping(target = "nextCheckAt", ignore = true)
    DeletionTaskEntity requestToEntity(DeletionTaskRequest requestDto);

    // From Entity to Response DTO (simple initial response)
    DeletionTaskResponse entityToTaskResponse(DeletionTaskEntity entity);

    // From Entity to Status DTO (detailed status); the window end exists on soft-delete list rows only
    @Mapping(target = "scheduledHardDeleteAt", ignore = true)
    DeletionTaskStatusDTO entityToStatusDTO(DeletionTaskEntity entity);

    // For mapping nested DTOs to nested Entity data classes (if not automatically handled by MapStruct)
    DeletionTaskEntity.DeletionScopeData scopeDtoToData(DeletionTaskRequest.DeletionScope scopeDto);
    DeletionTaskRequest.DeletionScope scopeDataToDto(DeletionTaskEntity.DeletionScopeData scopeData);

    DeletionTaskEntity.DeletionConfigOverrideData overrideDtoToData(DeletionTaskRequest.DeletionConfigOverride overrideDto);
    DeletionTaskRequest.DeletionConfigOverride overrideDataToDto(DeletionTaskEntity.DeletionConfigOverrideData overrideData);
    
    // If AssetDeletionStatusData needs explicit mapping (often MapStruct handles lists of identical beans)
    // List<DeletionTaskEntity.AssetDeletionStatusData> assetStatusDtoListToDataList(List<AssetDeletionStatusDTO> dtoList);
    // List<AssetDeletionStatusDTO> assetStatusDataListToDtoList(List<DeletionTaskEntity.AssetDeletionStatusData> dataList);
} 