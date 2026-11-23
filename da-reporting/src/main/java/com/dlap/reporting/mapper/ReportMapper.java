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

package com.dlap.reporting.mapper;

import com.dlap.reporting.dto.GeneratedReportOutputDTO;
import com.dlap.reporting.dto.ReportDefinitionDTO;
import com.dlap.reporting.model.GeneratedReport;
import com.dlap.reporting.model.ReportDefinition;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

import java.util.List;

@Mapper(componentModel = "spring")
public interface ReportMapper {

    ReportMapper INSTANCE = Mappers.getMapper(ReportMapper.class);

    // ReportDefinition Mappings
    ReportDefinitionDTO toDTO(ReportDefinition entity);
    ReportDefinition toEntity(ReportDefinitionDTO dto);
    List<ReportDefinitionDTO> toDTOList(List<ReportDefinition> entityList);

    // GeneratedReport Mappings
    @Mapping(source = "reportDefinition.reportKey", target = "reportKey")
    @Mapping(source = "reportDefinition.displayName", target = "reportDisplayName")
    GeneratedReportOutputDTO toDTO(GeneratedReport entity);
    List<GeneratedReportOutputDTO> toGeneratedReportDTOList(List<GeneratedReport> entityList);

    // We typically don't map from GeneratedReportOutputDTO back to GeneratedReport entity directly,
    // as creation/update paths for GeneratedReport are usually more complex and handled by services.
} 