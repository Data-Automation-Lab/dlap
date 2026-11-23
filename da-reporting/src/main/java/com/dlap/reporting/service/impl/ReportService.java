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

package com.dlap.reporting.service.impl;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dlap.reporting.client.ResilientCatalogClient;
import com.dlap.reporting.client.ResilientPolicyEngineClient;
import com.dlap.reporting.common.exception.BadRequestException;
import com.dlap.reporting.common.exception.ResourceNotFoundException;
import com.dlap.reporting.dto.GeneratedReportOutputDTO;
import com.dlap.reporting.dto.ReportDefinitionDTO;
import com.dlap.reporting.dto.ReportGenerationRequestDTO;
import com.dlap.reporting.mapper.ReportMapper;
import com.dlap.reporting.model.GeneratedReport;
import com.dlap.reporting.model.ReportDefinition;
import com.dlap.reporting.model.ReportStatus;
import com.dlap.reporting.repository.GeneratedReportRepository;
import com.dlap.reporting.repository.ReportDefinitionRepository;
import com.dlap.reporting.service.IReportService;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class ReportService implements IReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    private final ReportDefinitionRepository reportDefinitionRepository;
    private final GeneratedReportRepository generatedReportRepository;
    private final ReportMapper reportMapper;
    private final ResilientCatalogClient catalogClient;
    private final ResilientPolicyEngineClient policyEngineClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    // In a real app, this might be a ApplicationEventPublisher to trigger async job via Spring Events
    // private final ApplicationEventPublisher eventPublisher;

    public ReportService(ReportDefinitionRepository reportDefinitionRepository,
                        GeneratedReportRepository generatedReportRepository,
                        ReportMapper reportMapper,
                        ResilientCatalogClient catalogClient,
                        ResilientPolicyEngineClient policyEngineClient) {
        this.reportDefinitionRepository = reportDefinitionRepository;
        this.generatedReportRepository = generatedReportRepository;
        this.reportMapper = reportMapper;
        this.catalogClient = catalogClient;
        this.policyEngineClient = policyEngineClient;
    }

    @Override
    @Transactional
    public ReportDefinitionDTO createReportDefinition(ReportDefinitionDTO reportDefinitionDTO) {
        if (reportDefinitionRepository.findByReportKey(reportDefinitionDTO.getReportKey()).isPresent()) {
            throw new BadRequestException("Report definition with key '" + reportDefinitionDTO.getReportKey() + "' already exists.");
        }
        ReportDefinition entity = reportMapper.toEntity(reportDefinitionDTO);
        // The id is generated. A client-sent id used to be dropped by Hibernate's merge, which inserted a
        // new row; since Hibernate ORM 6.6 that merge fails optimistic locking instead, so the id is
        // cleared and the save stays an insert (BOOT-01).
        entity.setId(null);
        entity.setEnabled(true); // Default to enabled
        return reportMapper.toDTO(reportDefinitionRepository.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public ReportDefinitionDTO getReportDefinitionById(UUID id) {
        return reportDefinitionRepository.findById(id)
                .map(reportMapper::toDTO)
                .orElseThrow(() -> new ResourceNotFoundException("ReportDefinition", "id", id));
    }

    @Override
    @Transactional(readOnly = true)
    public ReportDefinitionDTO getReportDefinitionByKey(String reportKey) {
        return reportDefinitionRepository.findByReportKey(reportKey)
                .map(reportMapper::toDTO)
                .orElseThrow(() -> new ResourceNotFoundException("ReportDefinition", "reportKey", reportKey));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ReportDefinitionDTO> getAllReportDefinitions(Pageable pageable) {
        return reportDefinitionRepository.findAll(pageable).map(reportMapper::toDTO);
    }

    @Override
    @Transactional
    public ReportDefinitionDTO updateReportDefinition(UUID id, ReportDefinitionDTO reportDefinitionDTO) {
        ReportDefinition existingDefinition = reportDefinitionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ReportDefinition", "id", id));

        // Check if reportKey is being changed and if it conflicts
        if (!existingDefinition.getReportKey().equals(reportDefinitionDTO.getReportKey()) &&
                reportDefinitionRepository.findByReportKey(reportDefinitionDTO.getReportKey()).isPresent()) {
            throw new BadRequestException("Report definition with key '" + reportDefinitionDTO.getReportKey() + "' already exists.");
        }

        existingDefinition.setReportKey(reportDefinitionDTO.getReportKey());
        existingDefinition.setDisplayName(reportDefinitionDTO.getDisplayName());
        existingDefinition.setDescription(reportDefinitionDTO.getDescription());
        existingDefinition.setDefaultParameters(reportDefinitionDTO.getDefaultParameters());
        existingDefinition.setDefaultScheduleCron(reportDefinitionDTO.getDefaultScheduleCron());
        existingDefinition.setEnabled(reportDefinitionDTO.isEnabled());

        return reportMapper.toDTO(reportDefinitionRepository.save(existingDefinition));
    }

    @Override
    @Transactional
    public void deleteReportDefinition(UUID id) {
        if (!reportDefinitionRepository.existsById(id)) {
            throw new ResourceNotFoundException("ReportDefinition", "id", id);
        }
        // TODO: Add logic to handle existing GeneratedReport instances if a definition is deleted.
        // E.g., mark them as orphaned, disallow new generations, or prevent deletion if reports exist.
        reportDefinitionRepository.deleteById(id);
    }

    @Override
    @Transactional
    public GeneratedReportOutputDTO generateReport(String reportKey, ReportGenerationRequestDTO requestDTO, UUID triggeredByUserId) {
        ReportDefinition reportDefinition = reportDefinitionRepository.findByReportKey(reportKey)
                .orElseThrow(() -> new ResourceNotFoundException("ReportDefinition", "reportKey", reportKey));
        return createAndQueueReportGeneration(reportDefinition, requestDTO, triggeredByUserId);
    }

    @Override
    @Transactional
    public GeneratedReportOutputDTO generateReport(UUID reportDefinitionId, ReportGenerationRequestDTO requestDTO, UUID triggeredByUserId) {
        ReportDefinition reportDefinition = reportDefinitionRepository.findById(reportDefinitionId)
                .orElseThrow(() -> new ResourceNotFoundException("ReportDefinition", "id", reportDefinitionId));
        return createAndQueueReportGeneration(reportDefinition, requestDTO, triggeredByUserId);
    }

    private GeneratedReportOutputDTO createAndQueueReportGeneration(ReportDefinition reportDefinition, ReportGenerationRequestDTO requestDTO, UUID triggeredByUserId) {
        if (!reportDefinition.isEnabled()) {
            throw new BadRequestException("Report definition '" + reportDefinition.getReportKey() + "' is disabled.");
        }

        GeneratedReport generatedReport = new GeneratedReport();
        generatedReport.setReportDefinition(reportDefinition);
        generatedReport.setStatus(ReportStatus.PENDING);
        generatedReport.setRequestedByUserId(triggeredByUserId);
        generatedReport.setRequestedAt(Instant.now());

        Map<String, Object> effectiveParams = new HashMap<>();
        if (reportDefinition.getDefaultParameters() != null) {
            effectiveParams.putAll(reportDefinition.getDefaultParameters());
        }
        if (requestDTO != null && requestDTO.getParameters() != null) {
            effectiveParams.putAll(requestDTO.getParameters());
        }
        generatedReport.setGenerationParameters(effectiveParams);

        GeneratedReport savedReport = generatedReportRepository.save(generatedReport);
        log.info("Queued report generation for reportKey: {}, generatedReportId: {}", reportDefinition.getReportKey(), savedReport.getId());

        // Trigger asynchronous processing of the report
        // This could be publishing an event, sending a message to a queue, or calling an @Async method directly.
        processReportGenerationJobAsync(savedReport.getId(), reportDefinition.getReportKey(), effectiveParams);

        return reportMapper.toDTO(savedReport);
    }

    @Async("taskExecutor") // Assuming a TaskExecutor bean is configured for async tasks
    public void processReportGenerationJobAsync(UUID generatedReportId, String reportKey, Map<String, Object> parameters) {
        log.info("Starting asynchronous processing for generatedReportId: {}, reportKey: {}", generatedReportId, reportKey);
        GeneratedReport report = generatedReportRepository.findById(generatedReportId)
                .orElseThrow(() -> new ResourceNotFoundException("GeneratedReport", "id", generatedReportId)); // Should not happen if just saved

        report.setStatus(ReportStatus.GENERATING);
        report.setStartedAt(Instant.now());
        generatedReportRepository.save(report);

        try {
            log.info("Building report content for: {} with params: {}", reportKey, parameters);

            // Minimal real aggregation: pull catalog + policy-engine summary metrics so the
            // report reflects actual platform state rather than a static stub. Individual
            // report keys can expand this into bespoke queries later.
            // TODO(M1): expand report content to cover per-reportKey data shapes
            // (e.g. asset inventory CSV, policy-violation detail rows, archival runbook).
            String reportContent = buildReportContentJson(reportKey, parameters);

            report.setReportContent(reportContent);
            report.setStatus(ReportStatus.COMPLETED);
        } catch (Exception e) {
            log.error("Error during report generation for ID {}: {}", generatedReportId, e.getMessage(), e);
            report.setStatus(ReportStatus.FAILED);
            report.setFailureReason(e.getMessage());
        } finally {
            report.setCompletedAt(Instant.now());
            generatedReportRepository.save(report);
            log.info("Finished processing for generatedReportId: {}. Status: {}", generatedReportId, report.getStatus());
            // TODO: Trigger notifications if configured (e.g., on completion or failure)
        }
    }

    @Override
    @Transactional(readOnly = true)
    public GeneratedReportOutputDTO getGeneratedReportById(UUID generatedReportId) {
        return generatedReportRepository.findById(generatedReportId)
                .map(reportMapper::toDTO)
                .orElseThrow(() -> new ResourceNotFoundException("GeneratedReport", "id", generatedReportId));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<GeneratedReportOutputDTO> getGeneratedReportsByReportKey(String reportKey, Pageable pageable) {
        ReportDefinition reportDefinition = reportDefinitionRepository.findByReportKey(reportKey)
                .orElseThrow(() -> new ResourceNotFoundException("ReportDefinition", "reportKey", reportKey));
        return generatedReportRepository.findByReportDefinition(reportDefinition, pageable).map(reportMapper::toDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<GeneratedReportOutputDTO> getAllGeneratedReports(Pageable pageable) {
        return generatedReportRepository.findAll(pageable).map(reportMapper::toDTO);
    }

    /**
     * Build a data-driven JSON report body by querying catalog and policy-engine metrics.
     * Upstream failures fall back to zero counts with an {@code errors} field so report
     * consumers can distinguish "zero items" from "upstream unavailable".
     */
    private String buildReportContentJson(String reportKey, Map<String, Object> parameters) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reportKey", reportKey);
        body.put("generatedAt", Instant.now().toString());
        body.put("parameters", parameters != null ? parameters : Map.of());

        Map<String, Object> errors = new LinkedHashMap<>();

        Map<String, Object> catalogSummary = new LinkedHashMap<>();
        try {
            catalogSummary.put("totalAssets", nz(catalogClient.getTotalAssetCount()));
            catalogSummary.put("classifiedAssets", nz(catalogClient.getClassifiedAssetsCount()));
            catalogSummary.put("assetsWithMetadata", nz(catalogClient.getAssetsWithMetadataCount()));
            catalogSummary.put("assetsWithBusinessContext", nz(catalogClient.getAssetsWithBusinessContextCount()));
            catalogSummary.put("assetsWithLineage", nz(catalogClient.getAssetsWithLineageCount()));
            catalogSummary.put("totalLineageConnections", nz(catalogClient.getTotalLineageConnectionsCount()));
            catalogSummary.put("archivedAssets", nz(catalogClient.countAssetsByStatus("ARCHIVED")));
            catalogSummary.put("pendingDeletionAssets", nz(catalogClient.countAssetsByStatus("PENDING_DELETION")));
        } catch (Exception e) {
            log.warn("Catalog metrics unavailable during report build for {}: {}", reportKey, e.getMessage());
            errors.put("catalog", e.getMessage());
        }
        body.put("catalog", catalogSummary);

        Map<String, Object> policySummary = new LinkedHashMap<>();
        try {
            policySummary.put("activePolicies", nz(policyEngineClient.getActivePoliciesCount()));
            policySummary.put("violations", nz(policyEngineClient.getPolicyViolationsCount(null)));
            Double complianceRate = policyEngineClient.getPolicyComplianceRate();
            policySummary.put("complianceRate", complianceRate);
            policySummary.put("actionItems", policyEngineClient.getPolicyActionItems(null, null, 50));
        } catch (Exception e) {
            log.warn("Policy engine metrics unavailable during report build for {}: {}", reportKey, e.getMessage());
            errors.put("policyengine", e.getMessage());
        }
        body.put("policyEngine", policySummary);

        if (!errors.isEmpty()) {
            body.put("errors", errors);
        }

        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            log.error("Failed to serialize report content for {}: {}", reportKey, e.getMessage(), e);
            // Degraded fallback: still returns a valid JSON envelope so downstream
            // consumers can render the error without parsing issues.
            return "{\"reportKey\":\"" + reportKey + "\",\"error\":\"serialization_failed\"}";
        }
    }

    private static long nz(Long v) {
        return v != null ? v : 0L;
    }

    @Override
    @Transactional
    public GeneratedReportOutputDTO regenerateReport(UUID reportId, UUID triggeredByUserId) {
        GeneratedReport existingReport = generatedReportRepository.findById(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("GeneratedReport", "id", reportId));
        
        // Create a new report generation based on the existing report's definition
        return createAndQueueReportGeneration(existingReport.getReportDefinition(), null, triggeredByUserId);
    }
} 