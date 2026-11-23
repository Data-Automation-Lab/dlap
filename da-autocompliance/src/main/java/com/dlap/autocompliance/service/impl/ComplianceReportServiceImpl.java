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

package com.dlap.autocompliance.service.impl;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dlap.autocompliance.client.CatalogAssetClient;
import com.dlap.autocompliance.client.dto.CatalogAssetDTO;
import com.dlap.autocompliance.client.dto.CatalogAssetPageDTO;
import com.dlap.autocompliance.dto.ComplianceReportDTO;
import com.dlap.autocompliance.dto.ComplianceReportDefinitionDTO;
import com.dlap.autocompliance.dto.JobStatusForAdminAggregationDTO;
import com.dlap.autocompliance.dto.ReportGenerationRequestDTO;
import com.dlap.autocompliance.dto.ReportGenerationResponseDTO;
import com.dlap.autocompliance.dto.ReportJobStatusDTO;
import com.dlap.autocompliance.model.entity.ComplianceControlEntity;
import com.dlap.autocompliance.model.entity.ComplianceGeneratedReportEntity;
import com.dlap.autocompliance.model.entity.ComplianceReportDefinitionEntity;
import com.dlap.autocompliance.model.entity.ComplianceReportJobEntity;
import com.dlap.autocompliance.model.entity.ReportFindingData;
import com.dlap.autocompliance.model.mapper.ComplianceGeneratedReportMapper;
import com.dlap.autocompliance.model.mapper.ComplianceReportDefinitionMapper;
import com.dlap.autocompliance.model.mapper.ComplianceReportJobMapper;
import com.dlap.autocompliance.model.repository.ComplianceControlRepository;
import com.dlap.autocompliance.model.repository.ComplianceGeneratedReportRepository;
import com.dlap.autocompliance.model.repository.ComplianceReportDefinitionRepository;
import com.dlap.autocompliance.model.repository.ComplianceReportJobRepository;
import com.dlap.autocompliance.service.IComplianceReportService;
import com.dlap.autocompliance.service.exception.GeneratedReportNotFoundException;
import com.dlap.autocompliance.service.exception.ReportDefinitionNotFoundException;
import com.dlap.autocompliance.service.exception.ReportJobNotFoundException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * R-P1-12 / ADR-0024 phase-2 (v0.2.0 N1): focused impl of the
 * report slice. Method bodies (definition CRUD, report generation with
 * the {@code @Async} pipeline, job status, admin aggregation, generated
 * report read) moved out of the {@code ComplianceServiceImpl} god-class;
 * the facade now delegates here instead of holding the logic itself.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ComplianceReportServiceImpl implements IComplianceReportService {

    /** Page size when walking catalog assets during report generation. */
    private static final int CATALOG_PAGE_SIZE = 200;
    /** Safety cap on catalog pages to avoid an unbounded loop. */
    private static final int MAX_CATALOG_PAGES = 1000;

    private final ComplianceReportDefinitionRepository reportDefinitionRepository;
    private final ComplianceReportJobRepository reportJobRepository;
    private final ComplianceGeneratedReportRepository generatedReportRepository;

    private final ComplianceReportDefinitionMapper reportDefinitionMapper;
    private final ComplianceReportJobMapper reportJobMapper;
    private final ComplianceGeneratedReportMapper generatedReportMapper;

    private final ComplianceControlRepository controlRepository;
    private final CatalogAssetClient catalogAssetClient;
    private final AssetComplianceEvaluator evaluator;

    /**
     * Time source for report and job timestamps. Defaults to the system clock; a {@link Clock} bean,
     * when the application defines one, replaces it (rule R6).
     */
    private Clock clock = Clock.systemDefaultZone();

    /**
     * Self-reference used to invoke {@code @Async}-annotated methods through the
     * Spring proxy. Calling an async method via {@code this} bypasses the proxy
     * and the call becomes synchronous. Wired lazily to avoid a circular bean
     * dependency during container initialization.
     */
    @Autowired
    @Lazy
    private ComplianceReportServiceImpl self;

    /**
     * Optional {@link Clock} injection point; package-private so tests can fix the time without a
     * constructor change.
     */
    @Autowired(required = false)
    void setClock(Clock clock) {
        this.clock = clock;
    }

    @Override
    public void initDefaultReportDefinitions() {
        log.info("Report definitions initialization skipped - report definitions should be managed through database operations.");
    }

    @Override
    @Transactional(readOnly = true)
    public List<ComplianceReportDefinitionDTO> listAvailableReportDefinitions() {
        log.debug("Fetching all available report definitions.");
        List<ComplianceReportDefinitionEntity> entities = reportDefinitionRepository.findAll();
        return reportDefinitionMapper.toDtoList(entities);
    }

    @Override
    @Transactional
    public ComplianceReportDefinitionDTO createComplianceReportDefinition(ComplianceReportDefinitionDTO reportDefinitionDto) {
        log.info("Creating new compliance report definition with type: {}", reportDefinitionDto.getReportType());
        if (reportDefinitionRepository.findByReportTypeIgnoreCase(reportDefinitionDto.getReportType()).isPresent()) {
            throw new IllegalArgumentException("Report definition with type '" + reportDefinitionDto.getReportType() + "' already exists");
        }
        ComplianceReportDefinitionEntity entity = reportDefinitionMapper.toEntity(reportDefinitionDto);
        entity = reportDefinitionRepository.save(entity);
        log.info("Successfully created compliance report definition: {}", entity.getReportType());
        return reportDefinitionMapper.toDto(entity);
    }

    @Override
    @Transactional
    public ComplianceReportDefinitionDTO updateComplianceReportDefinition(String reportType, ComplianceReportDefinitionDTO reportDefinitionDto) {
        log.info("Updating compliance report definition with type: {}", reportType);
        ComplianceReportDefinitionEntity existingEntity = reportDefinitionRepository.findByReportTypeIgnoreCase(reportType)
                .orElseThrow(() -> new IllegalArgumentException("Report definition with type '" + reportType + "' not found"));
        existingEntity.setDisplayName(reportDefinitionDto.getDisplayName());
        existingEntity.setDescription(reportDefinitionDto.getDescription());
        existingEntity.setVersion(reportDefinitionDto.getVersion());
        existingEntity.setApplicableComplianceFrameworks(reportDefinitionDto.getApplicableComplianceFrameworks());
        existingEntity.setTargetAssetTypes(reportDefinitionDto.getTargetAssetTypes());
        existingEntity.setGenerationParametersDefinition(reportDefinitionDto.getGenerationParameters());
        existingEntity.setDetailsLink(reportDefinitionDto.getDetailsLink());
        existingEntity = reportDefinitionRepository.save(existingEntity);
        log.info("Successfully updated compliance report definition: {}", existingEntity.getReportType());
        return reportDefinitionMapper.toDto(existingEntity);
    }

    @Override
    @Transactional
    public void deleteComplianceReportDefinition(String reportType) {
        log.info("Deleting compliance report definition with type: {}", reportType);
        ComplianceReportDefinitionEntity existingEntity = reportDefinitionRepository.findByReportTypeIgnoreCase(reportType)
                .orElseThrow(() -> new IllegalArgumentException("Report definition with type '" + reportType + "' not found"));
        reportDefinitionRepository.delete(existingEntity);
        log.info("Successfully deleted compliance report definition: {}", reportType);
    }

    @Override
    @Transactional
    public ReportGenerationResponseDTO generateComplianceReport(String reportType, ReportGenerationRequestDTO request) {
        log.info("Request to generate report type: {} with parameters: {}", reportType, request.getParameters());

        ComplianceReportDefinitionEntity definitionEntity = reportDefinitionRepository.findByReportTypeIgnoreCase(reportType)
                .orElseThrow(() -> {
                    log.warn("Report type '{}' not found.", reportType);
                    return new ReportDefinitionNotFoundException("Report type " + reportType + " not found.");
                });

        if (request.getParameters() == null ||
            !request.getParameters().keySet().containsAll(definitionEntity.getGenerationParametersDefinition().keySet())) {
            String errorMsg = String.format("Missing required parameters for report type '%s'. Required: %s, Provided: %s",
                                   reportType, definitionEntity.getGenerationParametersDefinition().keySet(),
                                   request.getParameters() != null ? request.getParameters().keySet() : "null");
            log.warn(errorMsg);
            return ReportGenerationResponseDTO.builder()
                    .jobId(null).reportType(reportType).status("FAILED_VALIDATION")
                    .submittedAt(LocalDateTime.now(clock)).message(errorMsg)
                    .build();
        }

        ComplianceReportJobEntity jobEntity = reportJobMapper.fromGenerationRequest(request, reportType);
        jobEntity.setJobId(UUID.randomUUID().toString());
        jobEntity.setStatus("QUEUED");
        jobEntity.setSubmittedAt(LocalDateTime.now(clock));
        jobEntity.setReportType(definitionEntity.getReportType());

        reportJobRepository.save(jobEntity);
        log.info("Report generation job {} for report type '{}' accepted and queued.", jobEntity.getJobId(), reportType);

        try {
            self.processReportGenerationAsync(jobEntity.getJobId());
        } catch (Exception ex) {
            log.error("Failed to dispatch async report generation for job {}: {}", jobEntity.getJobId(), ex.getMessage(), ex);
            jobEntity.setStatus("COMPLETED_FAILURE");
            jobEntity.setMessage("Failed to dispatch async generation: " + ex.getMessage());
            jobEntity.setCompletedAt(LocalDateTime.now(clock));
            reportJobRepository.save(jobEntity);
        }

        return ReportGenerationResponseDTO.builder()
                .jobId(jobEntity.getJobId()).reportType(reportType).status("ACCEPTED")
                .submittedAt(jobEntity.getSubmittedAt()).message("Report generation accepted and queued.")
                .build();
    }

    /**
     * Builds the report from the current state of the catalog: every enabled control that applies to
     * the report's frameworks is evaluated against every catalog asset (narrowed to the report's
     * target asset types), exactly as a manual scan does. Earlier generated reports are never read,
     * so a new report cannot repeat stale findings, and a report over an unreachable catalog fails
     * instead of coming out empty.
     */
    @Async
    @Transactional
    public void processReportGenerationAsync(String jobId) {
        ComplianceReportJobEntity job = reportJobRepository.findById(jobId).orElse(null);
        if (job == null) {
            log.warn("Async report generation invoked for unknown job {}", jobId);
            return;
        }
        try {
            job.setStatus("PROCESSING");
            job.setStartedAt(LocalDateTime.now(clock));
            reportJobRepository.save(job);

            ComplianceReportDefinitionEntity definition = reportDefinitionRepository
                    .findByReportTypeIgnoreCase(job.getReportType())
                    .orElse(null);

            List<ComplianceControlEntity> controls = selectControls(definition);
            List<CatalogAssetDTO> assets = controls.isEmpty()
                    ? List.of()
                    : filterAssetsByType(fetchCurrentCatalogAssets(), definition);

            List<ReportFindingData> findings = new ArrayList<>();
            for (CatalogAssetDTO asset : assets) {
                for (ComplianceControlEntity control : controls) {
                    ReportFindingData finding = evaluator.evaluate(control, asset);
                    if (finding != null) {
                        findings.add(finding);
                    }
                }
            }

            int total = findings.size();
            long compliant = findings.stream()
                    .filter(f -> "COMPLIANT".equalsIgnoreCase(f.getStatus()))
                    .count();
            long nonCompliant = findings.stream()
                    .filter(f -> "NON_COMPLIANT".equalsIgnoreCase(f.getStatus()))
                    .count();

            String overallStatus;
            if (controls.isEmpty()) {
                overallStatus = "NO_CONTROLS";
            } else if (total == 0) {
                overallStatus = "NO_DATA";
            } else if (nonCompliant == 0) {
                overallStatus = "COMPLIANT";
            } else if (compliant == 0) {
                overallStatus = "NON_COMPLIANT";
            } else {
                overallStatus = "PARTIALLY_COMPLIANT";
            }

            ComplianceGeneratedReportEntity generated = ComplianceGeneratedReportEntity.builder()
                    .reportId(UUID.randomUUID().toString())
                    .reportType(job.getReportType())
                    .displayName(job.getReportType() + " report")
                    .generationTimestamp(LocalDateTime.now(clock))
                    .overallStatus(overallStatus)
                    .generationParameters(job.getGenerationParameters())
                    .findingsData(findings)
                    .reportJob(job)
                    .build();
            generatedReportRepository.save(generated);

            job.setStatus("COMPLETED_SUCCESS");
            job.setCompletedAt(LocalDateTime.now(clock));
            job.setGeneratedReportId(generated.getReportId());
            job.setMessage(String.format(
                    "Report generated from the current catalog: %d control(s) evaluated against %d asset(s), "
                            + "%d finding(s) (%d compliant, %d non-compliant).",
                    controls.size(), assets.size(), total, compliant, nonCompliant));
            reportJobRepository.save(job);
            log.info("Async report generation complete for job {} -> report {}", jobId, generated.getReportId());
        } catch (Exception ex) {
            log.error("Async report generation failed for job {}: {}", jobId, ex.getMessage(), ex);
            job.setStatus("COMPLETED_FAILURE");
            job.setCompletedAt(LocalDateTime.now(clock));
            job.setMessage("Report generation failed: " + ex.getMessage());
            reportJobRepository.save(job);
        }
    }

    /**
     * Enabled controls that belong to the report. When the report definition names frameworks, a
     * control must share at least one of them (compared ignoring case and punctuation, so
     * {@code PCI-DSS} matches {@code pci_dss}); a definition with no frameworks uses every enabled control.
     */
    private List<ComplianceControlEntity> selectControls(ComplianceReportDefinitionEntity definition) {
        List<ComplianceControlEntity> enabled = controlRepository.findByEnabledTrue();
        List<String> reportFrameworks = definition != null ? definition.getApplicableComplianceFrameworks() : null;
        if (reportFrameworks == null || reportFrameworks.isEmpty()) {
            return enabled;
        }
        Set<String> wanted = reportFrameworks.stream()
                .filter(Objects::nonNull)
                .map(ComplianceReportServiceImpl::normalizeName)
                .collect(Collectors.toSet());
        return enabled.stream()
                .filter(control -> control.getApplicableFrameworks() != null
                        && control.getApplicableFrameworks().stream()
                                .filter(Objects::nonNull)
                                .map(ComplianceReportServiceImpl::normalizeName)
                                .anyMatch(wanted::contains))
                .collect(Collectors.toList());
    }

    /** Keeps only assets of the report's target types; a definition with no target types keeps all. */
    private static List<CatalogAssetDTO> filterAssetsByType(List<CatalogAssetDTO> assets,
                                                            ComplianceReportDefinitionEntity definition) {
        List<String> targetTypes = definition != null ? definition.getTargetAssetTypes() : null;
        if (targetTypes == null || targetTypes.isEmpty()) {
            return assets;
        }
        Set<String> wanted = targetTypes.stream()
                .filter(Objects::nonNull)
                .map(ComplianceReportServiceImpl::normalizeName)
                .collect(Collectors.toSet());
        return assets.stream()
                .filter(asset -> asset.getAssetType() != null
                        && wanted.contains(normalizeName(asset.getAssetType())))
                .collect(Collectors.toList());
    }

    private static String normalizeName(String value) {
        return value.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    /**
     * Pages through every catalog asset through the da-catalog client. Stops at the reported last page
     * (or when a page is empty) and is bounded by {@link #MAX_CATALOG_PAGES}. An unreachable catalog
     * throws, which fails the job; it never yields an empty list.
     */
    private List<CatalogAssetDTO> fetchCurrentCatalogAssets() {
        List<CatalogAssetDTO> all = new ArrayList<>();
        int page = 0;
        while (page < MAX_CATALOG_PAGES) {
            CatalogAssetPageDTO pageResult = catalogAssetClient.getAssets(page, CATALOG_PAGE_SIZE);
            if (pageResult == null) {
                break;
            }
            List<CatalogAssetDTO> content = pageResult.getContent();
            if (content == null || content.isEmpty()) {
                break;
            }
            all.addAll(content);
            boolean lastPage = pageResult.isLast()
                    || (pageResult.getTotalPages() > 0 && page >= pageResult.getTotalPages() - 1)
                    || content.size() < CATALOG_PAGE_SIZE;
            if (lastPage) {
                break;
            }
            page++;
        }
        log.info("Fetched {} catalog asset(s) across {} page(s) for report generation", all.size(), page + 1);
        return all;
    }

    @Override
    @Transactional(readOnly = true)
    public ReportJobStatusDTO getReportGenerationJobStatus(String jobId) {
        log.debug("Fetching status for job ID: {}", jobId);
        ComplianceReportJobEntity jobEntity = reportJobRepository.findById(jobId)
                .orElseThrow(() -> {
                    log.warn("Job ID '{}' not found.", jobId);
                    return new ReportJobNotFoundException("Job ID " + jobId + " not found.");
                });
        return reportJobMapper.toDto(jobEntity);
    }

    @Override
    @Transactional(readOnly = true)
    public List<JobStatusForAdminAggregationDTO> listReportJobsForAdminAggregation() {
        return reportJobRepository.findAll(Sort.by(Sort.Direction.DESC, "submittedAt")).stream()
                .map(this::toAdminAggregationRow)
                .collect(Collectors.toList());
    }

    private JobStatusForAdminAggregationDTO toAdminAggregationRow(ComplianceReportJobEntity e) {
        Map<String, Object> details = new HashMap<>();
        details.put("reportType", e.getReportType());
        if (e.getGeneratedReportId() != null) {
            details.put("generatedReportId", e.getGeneratedReportId());
        }
        String normalizedStatus = normalizeStatusForAdminAggregation(e.getStatus());
        String raw = e.getStatus();
        String error = (raw != null && (raw.contains("FAIL") || "FAILED".equalsIgnoreCase(raw)))
                ? e.getMessage()
                : null;
        return JobStatusForAdminAggregationDTO.builder()
                .jobId(e.getJobId())
                .serviceName("AUTOCOMPLIANCE")
                .jobType("COMPLIANCE_REPORT:" + e.getReportType())
                .status(normalizedStatus)
                .submittedAt(e.getSubmittedAt())
                .startedAt(e.getStartedAt())
                .completedAt(e.getCompletedAt())
                .details(details)
                .errorDetails(error)
                .build();
    }

    private static String normalizeStatusForAdminAggregation(String status) {
        if (status == null) {
            return "UNKNOWN";
        }
        if ("COMPLETED_FAILURE".equals(status)) {
            return "COMPLETED_FAILED";
        }
        return status;
    }

    @Override
    @Transactional(readOnly = true)
    public ComplianceReportDTO getGeneratedReport(String reportId) {
        log.debug("Fetching generated report for report ID: {}", reportId);
        ComplianceGeneratedReportEntity reportEntity = generatedReportRepository.findById(reportId)
                .orElseThrow(() -> {
                    log.warn("Report ID '{}' not found.", reportId);
                    return new GeneratedReportNotFoundException("Generated report with ID " + reportId + " not found.");
                });
        return generatedReportMapper.toDto(reportEntity);
    }
}
