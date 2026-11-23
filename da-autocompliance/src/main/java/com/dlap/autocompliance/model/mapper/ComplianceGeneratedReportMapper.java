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

import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

import com.dlap.autocompliance.dto.ComplianceReportDTO;
import com.dlap.autocompliance.model.entity.ComplianceGeneratedReportEntity;
import com.dlap.autocompliance.model.entity.ReportFindingData;
import com.dlap.autocompliance.model.entity.ReportSummaryData;

/**
 * Mapper interface for converting between ComplianceGeneratedReportEntity and DTOs.
 */
@Mapper(componentModel = "spring")
public interface ComplianceGeneratedReportMapper {

    ComplianceGeneratedReportMapper INSTANCE = Mappers.getMapper(ComplianceGeneratedReportMapper.class);

    // Entity to DTO
    @Mapping(source = "summaryData", target = "summary")
    @Mapping(source = "findingsData", target = "findings")
    ComplianceReportDTO toDto(ComplianceGeneratedReportEntity entity);

    List<ComplianceReportDTO> toDtoList(List<ComplianceGeneratedReportEntity> entities);

    // DTO to Entity
    @Mapping(source = "summary", target = "summaryData")
    @Mapping(source = "findings", target = "findingsData")
    @Mapping(target = "reportJob", ignore = true) // Typically set separately or via jobId
    ComplianceGeneratedReportEntity toEntity(ComplianceReportDTO dto);

    // Mappings for nested POJOs (if MapStruct doesn't handle them automatically, which it often does)
    // These might not be strictly necessary if names and types match and MapStruct can infer them.
    ReportSummaryData toReportSummaryData(ComplianceReportDTO.SummaryDTO summaryDTO);
    ComplianceReportDTO.SummaryDTO toSummaryDTO(ReportSummaryData reportSummaryData);

    List<ReportFindingData> toReportFindingDataList(List<ComplianceReportDTO.FindingDTO> findingDTOs);
    List<ComplianceReportDTO.FindingDTO> toFindingDTOList(List<ReportFindingData> reportFindingDataList);
    
    ReportFindingData toReportFindingData(ComplianceReportDTO.FindingDTO findingDTO);
    ComplianceReportDTO.FindingDTO toFindingDTO(ReportFindingData reportFindingData);

} 