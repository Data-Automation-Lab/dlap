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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dlap.autocompliance.client.CatalogAssetClient;
import com.dlap.autocompliance.client.dto.CatalogAssetDTO;
import com.dlap.autocompliance.client.dto.CatalogAssetPageDTO;
import com.dlap.autocompliance.dto.AssetComplianceStatusDTO;
import com.dlap.autocompliance.dto.ComplianceViolationDTO;
import com.dlap.autocompliance.model.entity.ComplianceControlEntity;
import com.dlap.autocompliance.model.entity.ComplianceGeneratedReportEntity;
import com.dlap.autocompliance.model.entity.ComplianceReportJobEntity;
import com.dlap.autocompliance.model.entity.ReportFindingData;
import com.dlap.autocompliance.model.repository.ComplianceControlRepository;
import com.dlap.autocompliance.model.repository.ComplianceGeneratedReportRepository;
import com.dlap.autocompliance.model.repository.ComplianceReportJobRepository;
import com.dlap.autocompliance.service.IComplianceScanService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * R-P1-12 / ADR-0024 phase-2 (v0.2.0 N1): focused impl of the scan /
 * violation / asset-status slice. Method bodies moved out of the
 * {@code ComplianceServiceImpl} god-class; the facade now delegates
 * here instead of holding the logic itself.
 *
 * <p>Owns the findings-derived violation list, per-asset compliance
 * status aggregation, and the manual scan trigger with its
 * {@code @Async} pipeline.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ComplianceScanServiceImpl implements IComplianceScanService {

    /** Page size when walking catalog assets during a scan. */
    private static final int CATALOG_PAGE_SIZE = 200;
    /** Safety cap on catalog pages to avoid an unbounded loop. */
    private static final int MAX_CATALOG_PAGES = 1000;

    private final ComplianceGeneratedReportRepository generatedReportRepository;
    private final ComplianceReportJobRepository reportJobRepository;
    private final ComplianceControlRepository controlRepository;
    private final CatalogAssetClient catalogAssetClient;
    private final AssetComplianceEvaluator evaluator;

    /**
     * Self-reference used to invoke {@code @Async}-annotated methods through the
     * Spring proxy. Calling an async method via {@code this} bypasses the proxy
     * and the call becomes synchronous. Wired lazily to avoid a circular bean
     * dependency during container initialization.
     */
    @Autowired
    @Lazy
    private ComplianceScanServiceImpl self;

    @Override
    @Transactional(readOnly = true)
    public AssetComplianceStatusDTO getAssetComplianceStatus(String assetId) {
        log.debug("Fetching compliance status for asset ID: {}", assetId);

        List<ComplianceGeneratedReportEntity> reportsWithAsset = generatedReportRepository.findReportsContainingAssetId(assetId);

        if (reportsWithAsset.isEmpty()) {
            log.warn("Asset ID '{}' not found in any generated reports or no compliance data available.", assetId);
            return AssetComplianceStatusDTO.builder()
                    .assetId(assetId)
                    .overallComplianceStatus("UNKNOWN")
                    .lastEvaluatedAt(LocalDateTime.now())
                    .build();
        }

        List<AssetComplianceStatusDTO.ComplianceFindingSummaryDTO> assetFindingsSummaries = new ArrayList<>();
        List<String> relevantReportIds = new ArrayList<>();
        int compliantCount = 0;
        int nonCompliantCount = 0;
        String assetType = "UNKNOWN";
        String assetName = "UNKNOWN";
        LocalDateTime lastEvalTime = null;

        for (ComplianceGeneratedReportEntity reportEntity : reportsWithAsset) {
            relevantReportIds.add(reportEntity.getReportId());
            if (reportEntity.getFindingsData() != null) {
                for (ReportFindingData findingData : reportEntity.getFindingsData()) {
                    if (assetId.equals(findingData.getAssetId())) {
                        if ("UNKNOWN".equals(assetType) && findingData.getAssetType() != null) assetType = findingData.getAssetType();
                        if ("UNKNOWN".equals(assetName) && findingData.getAssetName() != null) assetName = findingData.getAssetName();

                        if ("NON_COMPLIANT".equalsIgnoreCase(findingData.getStatus())) {
                            nonCompliantCount++;
                            assetFindingsSummaries.add(AssetComplianceStatusDTO.ComplianceFindingSummaryDTO.builder()
                                    .checkId(findingData.getCheckId())
                                    .description(findingData.getDescription())
                                    .severity(findingData.getSeverity())
                                    .reportId(reportEntity.getReportId())
                                    .findingTimestamp(reportEntity.getGenerationTimestamp())
                                    .build());
                        } else if ("COMPLIANT".equalsIgnoreCase(findingData.getStatus())) {
                            compliantCount++;
                        }
                        if (lastEvalTime == null || reportEntity.getGenerationTimestamp().isAfter(lastEvalTime)) {
                            lastEvalTime = reportEntity.getGenerationTimestamp();
                        }
                    }
                }
            }
        }

        String overallStatus = "UNKNOWN";
        if (compliantCount > 0 || nonCompliantCount > 0) {
            overallStatus = nonCompliantCount > 0 ? "NON_COMPLIANT" : "COMPLIANT";
        } else if (!reportsWithAsset.isEmpty()) {
             overallStatus = "NO_FINDINGS";
        }

        return AssetComplianceStatusDTO.builder()
                .assetId(assetId)
                .assetType(assetType)
                .assetName(assetName)
                .overallComplianceStatus(overallStatus)
                .lastEvaluatedAt(lastEvalTime != null ? lastEvalTime : LocalDateTime.now())
                .totalChecksApplied(compliantCount + nonCompliantCount)
                .compliantChecks(compliantCount)
                .nonCompliantChecks(nonCompliantCount)
                .recentNonCompliantFindings(assetFindingsSummaries.stream().limit(5).collect(Collectors.toList()))
                .relevantReportIds(relevantReportIds)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ComplianceViolationDTO> getComplianceViolations() {
        List<ComplianceGeneratedReportEntity> reports = generatedReportRepository.findAll();
        List<ComplianceViolationDTO> violations = new ArrayList<>();

        for (ComplianceGeneratedReportEntity report : reports) {
            if (report.getFindingsData() != null) {
                for (ReportFindingData finding : report.getFindingsData()) {
                    if ("NON_COMPLIANT".equalsIgnoreCase(finding.getStatus())) {
                        String stableKey = String.join("|",
                                report.getReportId() != null ? report.getReportId() : "",
                                finding.getCheckId() != null ? finding.getCheckId() : "",
                                finding.getAssetId() != null ? finding.getAssetId() : "");
                        ComplianceViolationDTO violation = ComplianceViolationDTO.builder()
                                .id(stableViolationId(stableKey))
                                .controlId(finding.getCheckId())
                                .title(finding.getDescription())
                                .description(finding.getDescription())
                                .severity(finding.getSeverity())
                                .regulation(report.getReportType())
                                .affectedAssets(finding.getAssetId() != null ? List.of(finding.getAssetId()) : new ArrayList<>())
                                .detectedAt(report.getGenerationTimestamp().toString())
                                .status("Open")
                                .assignedTo(null)
                                .dueDate(report.getGenerationTimestamp() != null
                                        ? report.getGenerationTimestamp().plusDays(30)
                                                .toLocalDate()
                                                .format(DateTimeFormatter.ISO_LOCAL_DATE)
                                        : LocalDate.now().plusDays(30)
                                                .format(DateTimeFormatter.ISO_LOCAL_DATE))
                                .build();
                        violations.add(violation);
                    }
                }
            }
        }
        return violations;
    }

    private static String stableViolationId(String key) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return UUID.randomUUID().toString();
        }
    }

    @Override
    public String triggerComplianceScan() {
        log.info("Triggering manual compliance scan across all monitored assets");

        String jobId = UUID.randomUUID().toString();
        ComplianceReportJobEntity job = ComplianceReportJobEntity.builder()
                .jobId(jobId)
                .reportType("MANUAL_SCAN")
                .status("QUEUED")
                .submittedAt(LocalDateTime.now())
                .message("Manual compliance scan requested.")
                .build();
        // NOTE: this method is intentionally NOT @Transactional. The job row must be
        // COMMITTED before we dispatch the @Async worker; otherwise the worker runs on
        // its own thread/transaction and its findById(jobId) sees no row ("unknown job")
        // and the scan silently does nothing. The repository save() commits on return.
        reportJobRepository.save(job);

        try {
            self.processManualScanAsync(jobId);
        } catch (Exception ex) {
            log.error("Failed to dispatch async manual scan for job {}: {}", jobId, ex.getMessage(), ex);
            job.setStatus("COMPLETED_FAILURE");
            job.setMessage("Failed to dispatch async scan: " + ex.getMessage());
            job.setCompletedAt(LocalDateTime.now());
            reportJobRepository.save(job);
        }

        log.info("Compliance scan job {} created and dispatched", jobId);
        return jobId;
    }

    @Async
    @Transactional
    public void processManualScanAsync(String jobId) {
        ComplianceReportJobEntity job = reportJobRepository.findById(jobId).orElse(null);
        if (job == null) {
            log.warn("Async manual scan invoked for unknown job {}", jobId);
            return;
        }
        try {
            job.setStatus("PROCESSING");
            job.setStartedAt(LocalDateTime.now());
            reportJobRepository.save(job);

            List<ComplianceControlEntity> enabledControls = controlRepository.findByEnabledTrue();
            int controlCount = enabledControls.size();

            // Fetch every catalog asset (paged) and evaluate each enabled control
            // against the asset's REAL attributes. Findings are honest: each row
            // is COMPLIANT / NON_COMPLIANT derived from the asset's own metadata,
            // labels, and region - never fabricated. Assets outside a control's
            // scope yield no finding (evaluator returns null).
            List<CatalogAssetDTO> assets = fetchAllCatalogAssets();
            int assetCount = assets.size();

            List<ReportFindingData> findings = new ArrayList<>();
            int compliant = 0;
            int nonCompliant = 0;
            for (CatalogAssetDTO asset : assets) {
                for (ComplianceControlEntity control : enabledControls) {
                    ReportFindingData finding = evaluator.evaluate(control, asset);
                    if (finding == null) {
                        continue; // control out of scope for this asset
                    }
                    findings.add(finding);
                    if ("NON_COMPLIANT".equalsIgnoreCase(finding.getStatus())) {
                        nonCompliant++;
                    } else if ("COMPLIANT".equalsIgnoreCase(finding.getStatus())) {
                        compliant++;
                    }
                }
            }

            String overallStatus;
            if (controlCount == 0) {
                overallStatus = "NO_CONTROLS";
            } else if (findings.isEmpty()) {
                overallStatus = "NO_DATA";
            } else if (nonCompliant > 0) {
                overallStatus = "NON_COMPLIANT";
            } else {
                overallStatus = "COMPLIANT";
            }

            ComplianceGeneratedReportEntity generated = ComplianceGeneratedReportEntity.builder()
                    .reportId(UUID.randomUUID().toString())
                    .reportType("MANUAL_SCAN")
                    .displayName("Manual compliance scan")
                    .generationTimestamp(LocalDateTime.now())
                    .overallStatus(overallStatus)
                    .findingsData(findings)
                    .reportJob(job)
                    .build();
            generatedReportRepository.save(generated);

            job.setStatus("COMPLETED_SUCCESS");
            job.setCompletedAt(LocalDateTime.now());
            job.setGeneratedReportId(generated.getReportId());
            job.setMessage(String.format(
                    "Manual scan completed. %d enabled control(s) evaluated against %d "
                            + "catalog asset(s): %d finding(s) recorded (%d compliant, %d non-compliant).",
                    controlCount, assetCount, findings.size(), compliant, nonCompliant));
            reportJobRepository.save(job);
            log.info("Async manual scan complete for job {} -> report {} ({} findings: {} compliant, {} non-compliant)",
                    jobId, generated.getReportId(), findings.size(), compliant, nonCompliant);
        } catch (Exception ex) {
            log.error("Async manual scan failed for job {}: {}", jobId, ex.getMessage(), ex);
            job.setStatus("COMPLETED_FAILURE");
            job.setCompletedAt(LocalDateTime.now());
            job.setMessage("Manual scan failed: " + ex.getMessage());
            reportJobRepository.save(job);
        }
    }

    /**
     * Page through every catalog asset via the da-catalog Feign client. Stops at
     * the reported last page (or when a page returns no content) and is bounded
     * by {@link #MAX_CATALOG_PAGES} as a safety net against a mis-reporting page
     * source.
     */
    private List<CatalogAssetDTO> fetchAllCatalogAssets() {
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
        log.info("Fetched {} catalog asset(s) across {} page(s) for compliance scan",
                all.size(), page + 1);
        return all;
    }
}
