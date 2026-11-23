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

package com.dlap.reporting.service;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.dlap.reporting.dto.GeneratedReportOutputDTO;
import com.dlap.reporting.dto.ReportDefinitionDTO;
import com.dlap.reporting.dto.ReportGenerationRequestDTO;

public interface IReportService {

    // Report Definition Management
    ReportDefinitionDTO createReportDefinition(ReportDefinitionDTO reportDefinitionDTO);
    ReportDefinitionDTO getReportDefinitionById(UUID id);
    ReportDefinitionDTO getReportDefinitionByKey(String reportKey);
    Page<ReportDefinitionDTO> getAllReportDefinitions(Pageable pageable);
    ReportDefinitionDTO updateReportDefinition(UUID id, ReportDefinitionDTO reportDefinitionDTO);
    void deleteReportDefinition(UUID id);

    // Report Generation & Retrieval
    GeneratedReportOutputDTO generateReport(String reportKey, ReportGenerationRequestDTO requestDTO, UUID triggeredByUserId);
    GeneratedReportOutputDTO generateReport(UUID reportDefinitionId, ReportGenerationRequestDTO requestDTO, UUID triggeredByUserId);
    GeneratedReportOutputDTO regenerateReport(UUID reportId, UUID triggeredByUserId);
    GeneratedReportOutputDTO getGeneratedReportById(UUID generatedReportId);
    Page<GeneratedReportOutputDTO> getGeneratedReportsByReportKey(String reportKey, Pageable pageable);
    Page<GeneratedReportOutputDTO> getAllGeneratedReports(Pageable pageable);
    // TODO: Add methods for querying generated reports by status, user, date range, etc.

    // Internal method for scheduled or event-triggered generation (implementation detail)
    // void processReportGenerationJob(UUID generatedReportId);
} 