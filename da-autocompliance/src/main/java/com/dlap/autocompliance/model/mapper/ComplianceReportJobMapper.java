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

import com.dlap.autocompliance.dto.ReportJobStatusDTO;
import com.dlap.autocompliance.dto.ReportGenerationRequestDTO; // For mapping parameters
import com.dlap.autocompliance.model.entity.ComplianceReportJobEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.mapstruct.factory.Mappers;

import java.util.Map;
import java.util.List;

/**
 * Mapper interface for converting between ComplianceReportJobEntity and DTOs.
 */
@Mapper(componentModel = "spring")
public interface ComplianceReportJobMapper {

    ComplianceReportJobMapper INSTANCE = Mappers.getMapper(ComplianceReportJobMapper.class);

    // From Entity to DTO (ReportJobStatusDTO)
    @Mapping(target = "reportId", ignore = true)
    @Mapping(target = "downloadLink", ignore = true)
    ReportJobStatusDTO toDto(ComplianceReportJobEntity entity);

    List<ReportJobStatusDTO> toDtoList(List<ComplianceReportJobEntity> entities);

    // From DTO (ReportGenerationRequestDTO for submission) to Entity
    // Note: JobId will be set in service. Status will be set. reportId will be null initially.
    @Mapping(source = "dto.parameters", target = "generationParameters") // Map<String, Object> to Map<String, Object>
    @Mapping(target = "jobId", ignore = true) // Set by service
    @Mapping(target = "status", ignore = true) // Set by service
    @Mapping(target = "submittedAt", ignore = true) // Set by service or DB default
    @Mapping(target = "startedAt", ignore = true)
    @Mapping(target = "completedAt", ignore = true)
    @Mapping(target = "message", ignore = true)
    @Mapping(target = "generatedReportId", ignore = true)
    ComplianceReportJobEntity fromGenerationRequest(ReportGenerationRequestDTO dto, String reportType);

} 