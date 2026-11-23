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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.dlap.autocompliance.client.dto.CatalogAssetDTO;
import com.dlap.autocompliance.client.dto.CatalogAssetPageDTO;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dlap.autocompliance.dto.ComplianceControlDTO;
import com.dlap.autocompliance.dto.ControlEvaluationRequestDTO;
import com.dlap.autocompliance.dto.ControlEvaluationResponseDTO;
import com.dlap.autocompliance.model.entity.ComplianceControlEntity;
import com.dlap.autocompliance.model.entity.ComplianceGeneratedReportEntity;
import com.dlap.autocompliance.model.entity.ControlEvaluationJobEntity;
import com.dlap.autocompliance.model.entity.ReportFindingData;
import com.dlap.autocompliance.model.mapper.ComplianceControlMapper;
import com.dlap.autocompliance.model.mapper.ControlEvaluationJobMapper;
import com.dlap.autocompliance.model.repository.ComplianceControlRepository;
import com.dlap.autocompliance.model.repository.ComplianceGeneratedReportRepository;
import com.dlap.autocompliance.model.repository.ControlEvaluationJobRepository;
import com.dlap.autocompliance.service.IComplianceControlService;
import com.dlap.autocompliance.service.exception.ControlEvaluationNotFoundException;
import com.dlap.autocompliance.service.exception.ControlNotFoundException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * R-P1-12 / ADR-0024 phase-2 (v0.2.0 N1): focused impl of the
 * compliance-control slice. Method bodies moved out of the
 * {@code ComplianceServiceImpl} god-class; the facade now delegates
 * here instead of holding the logic itself.
 *
 * <p>Owns control CRUD, control evaluation (findings-backed pass/fail
 * against prior generated reports), and evaluation-job status.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ComplianceControlServiceImpl implements IComplianceControlService {

    private final ComplianceControlRepository controlRepository;
    private final ControlEvaluationJobRepository controlEvaluationJobRepository;
    private final ComplianceGeneratedReportRepository generatedReportRepository;
    private final ComplianceControlMapper controlMapper;
    private final ControlEvaluationJobMapper controlEvaluationJobMapper;
    private final AssetComplianceEvaluator assetComplianceEvaluator;
    private final com.dlap.autocompliance.client.CatalogAssetClient catalogAssetClient;

    @Override
    public void initDefaultControls() {
        log.info("Controls initialization skipped - controls should be managed through database operations.");
    }

    @Override
    @Transactional(readOnly = true)
    public List<ComplianceControlDTO> listAvailableControls() {
        log.debug("Fetching all available and enabled compliance controls.");
        List<ComplianceControlEntity> entities = controlRepository.findByEnabledTrue();
        return controlMapper.toDtoList(entities);
    }

    @Override
    @Transactional
    public ComplianceControlDTO createComplianceControl(ComplianceControlDTO controlDto) {
        log.info("Creating new compliance control with ID: {}", controlDto.getControlId());
        if (controlRepository.findByControlIdIgnoreCase(controlDto.getControlId()).isPresent()) {
            throw new IllegalArgumentException("Control with ID '" + controlDto.getControlId() + "' already exists");
        }
        ComplianceControlEntity entity = controlMapper.toEntity(controlDto);
        entity = controlRepository.save(entity);
        log.info("Successfully created compliance control: {}", entity.getControlId());
        return controlMapper.toDto(entity);
    }

    @Override
    @Transactional
    public ComplianceControlDTO updateComplianceControl(String controlId, ComplianceControlDTO controlDto) {
        log.info("Updating compliance control with ID: {}", controlId);
        ComplianceControlEntity existingEntity = controlRepository.findByControlIdIgnoreCase(controlId)
                .orElseThrow(() -> new IllegalArgumentException("Control with ID '" + controlId + "' not found"));
        existingEntity.setName(controlDto.getName());
        existingEntity.setDescription(controlDto.getDescription());
        existingEntity.setSeverity(controlDto.getSeverity());
        existingEntity.setApplicableFrameworks(controlDto.getApplicableFrameworks());
        existingEntity.setTargetAssetTypes(controlDto.getTargetAssetTypes());
        existingEntity.setRemediationSteps(controlDto.getRemediationSteps());
        existingEntity.setEvaluationParametersDefinition(controlDto.getEvaluationParametersDefinition());
        existingEntity.setDetailsLink(controlDto.getDetailsLink());
        existingEntity.setEnabled(controlDto.isEnabled());
        existingEntity = controlRepository.save(existingEntity);
        log.info("Successfully updated compliance control: {}", existingEntity.getControlId());
        return controlMapper.toDto(existingEntity);
    }

    @Override
    @Transactional
    public void deleteComplianceControl(String controlId) {
        log.info("Deleting compliance control with ID: {}", controlId);
        ComplianceControlEntity existingEntity = controlRepository.findByControlIdIgnoreCase(controlId)
                .orElseThrow(() -> new IllegalArgumentException("Control with ID '" + controlId + "' not found"));
        controlRepository.delete(existingEntity);
        log.info("Successfully deleted compliance control: {}", controlId);
    }

    @Override
    @Transactional
    public ControlEvaluationResponseDTO evaluateControl(String controlId, ControlEvaluationRequestDTO request) {
        log.info("Request to evaluate control ID: {} with request: {}", controlId, request);

        ComplianceControlEntity controlEntity = controlRepository.findByControlIdIgnoreCase(controlId)
                .orElseThrow(() -> {
                    log.warn("Control ID '{}' not found.", controlId);
                    return new ControlNotFoundException("Control ID " + controlId + " not found.");
                });

        ControlEvaluationJobEntity jobEntity = controlEvaluationJobMapper.fromRequestDto(request, controlId);
        jobEntity = controlEvaluationJobRepository.save(jobEntity);

        log.info("Created control evaluation job {} for control {}", jobEntity.getJobId(), controlId);

        jobEntity.setStartedAt(java.time.LocalDateTime.now());
        jobEntity.setStatus("PROCESSING");
        controlEvaluationJobRepository.save(jobEntity);

        List<String> targets = request.getTargetAssetIds() != null
                ? request.getTargetAssetIds()
                : new ArrayList<>();
        int targetCount = targets.size();

        if (!controlEntity.isEnabled()) {
            jobEntity.setStatus("COMPLETED_FAILURE");
            jobEntity.setMessage("Control " + controlId + " is disabled; evaluation skipped.");
            jobEntity.setCompletedAt(java.time.LocalDateTime.now());
            controlEvaluationJobRepository.save(jobEntity);
            return controlEvaluationJobMapper.toResponseDto(jobEntity);
        }

        if (targetCount == 0) {
            jobEntity.setStatus("COMPLETED_PARTIAL");
            jobEntity.setMessage("No target assets supplied; nothing was evaluated. "
                    + "Submit target asset IDs or schedule a catalog-backed batch scan.");
            jobEntity.setCompletedAt(java.time.LocalDateTime.now());
            controlEvaluationJobRepository.save(jobEntity);
            return controlEvaluationJobMapper.toResponseDto(jobEntity);
        }

        int passCount = 0;
        int failCount = 0;
        int unknownCount = 0;
        List<String> failedAssetIds = new ArrayList<>();
        List<String> unknownAssetIds = new ArrayList<>();

        // Real evaluation (choke-point #2): each target asset is evaluated against the
        // control's actual strategy via AssetComplianceEvaluator (IAM-drift / PII-encryption /
        // GDPR-residency today), reading the asset's live technical/business metadata, labels,
        // and region from da-catalog. When the evaluator has no strategy for a control (returns
        // null), we fall back to the pre-existing report-findings lookup below. Cloud-neutral:
        // the evaluator inspects generic Asset attributes with zero provider-SDK code.
        Map<String, CatalogAssetDTO> assetIndex = fetchCatalogAssetIndex();

        // Historical fallback for controls the evaluator does not yet implement:
        // per-framework, per-control evaluators that:
        //   * map a control's frameworks + asset types (already modeled in the
        //     control_frameworks / control_asset_types tables) to concrete rules;
        //   * fetch the asset's technical/business metadata + IAM/config from
        //     da-catalog (and, where needed, the cloud provider) via the shared
        //     service-auth Feign clients;
        //   * apply the framework requirement and emit a COMPLIANT / NON_COMPLIANT
        //     ReportFindingData that this aggregation then rolls up.
        // Target frameworks / controls (seeded in V2__seed_controls.sql):
        //   * GDPR    - data residency (allowed regions), PII retention & erasure;
        //   * HIPAA   - PHI encryption at rest/in transit, minimum-necessary access;
        //   * SOC 2   - access reviews, change management, audit-log coverage;
        //   * PCI-DSS - cardholder-data isolation + encryption, key rotation;
        //   * IAM-DRIFT-001 - real IAM-policy analysis (public access, over-broad
        //     grants, drift vs. an approved baseline) rather than a findings lookup.
        // Each evaluator should be a strategy keyed by control id / framework so new
        // industries plug in without touching this aggregation. Tracked as Q-017 in
        // docs/OPEN_QUESTIONS.md (compliance module not built).
        for (String assetId : targets) {
            if (assetId == null || assetId.isBlank()) {
                unknownCount++;
                continue;
            }

            // PRIMARY: run the real control strategy against the asset's live attributes.
            CatalogAssetDTO catalogAsset = assetIndex.get(assetId);
            ReportFindingData realFinding = catalogAsset == null
                    ? null
                    : assetComplianceEvaluator.evaluate(controlEntity, catalogAsset);
            if (realFinding != null) {
                if ("NON_COMPLIANT".equalsIgnoreCase(realFinding.getStatus())) {
                    failCount++;
                    failedAssetIds.add(assetId);
                } else if ("COMPLIANT".equalsIgnoreCase(realFinding.getStatus())) {
                    passCount++;
                } else {
                    unknownCount++;
                    unknownAssetIds.add(assetId);
                }
                continue;
            }

            // FALLBACK: pre-existing report findings (checkId == controlId).
            List<ComplianceGeneratedReportEntity> reportsForAsset =
                    generatedReportRepository.findReportsContainingAssetId(assetId);

            boolean sawMatchingFinding = false;
            boolean assetFailed = false;
            boolean assetPassed = false;

            for (ComplianceGeneratedReportEntity rpt : reportsForAsset) {
                if (rpt.getFindingsData() == null) continue;
                for (ReportFindingData finding : rpt.getFindingsData()) {
                    if (!assetId.equals(finding.getAssetId())) continue;
                    if (finding.getCheckId() == null) continue;
                    if (!controlId.equalsIgnoreCase(finding.getCheckId())) continue;

                    sawMatchingFinding = true;
                    if ("NON_COMPLIANT".equalsIgnoreCase(finding.getStatus())) {
                        assetFailed = true;
                    } else if ("COMPLIANT".equalsIgnoreCase(finding.getStatus())) {
                        assetPassed = true;
                    }
                }
            }

            if (!sawMatchingFinding) {
                unknownCount++;
                unknownAssetIds.add(assetId);
            } else if (assetFailed) {
                failCount++;
                failedAssetIds.add(assetId);
            } else if (assetPassed) {
                passCount++;
            } else {
                unknownCount++;
                unknownAssetIds.add(assetId);
            }
        }

        String status;
        if (failCount == 0 && unknownCount == 0) {
            status = "COMPLETED_SUCCESS";
        } else if (failCount > 0 && passCount == 0 && unknownCount == 0) {
            status = "COMPLETED_FAILURE";
        } else {
            status = "COMPLETED_PARTIAL";
        }

        String detail = String.format(
                "Control %s (%s): %d/%d passed, %d failed, %d unknown.",
                controlId, controlEntity.getName(), passCount, targetCount, failCount, unknownCount);
        if (!failedAssetIds.isEmpty()) {
            detail += " Failed assets: " + failedAssetIds + ".";
        }
        if (!unknownAssetIds.isEmpty()) {
            detail += " Assets with no prior findings: " + unknownAssetIds + ".";
        }

        jobEntity.setStatus(status);
        jobEntity.setMessage(detail);
        jobEntity.setCompletedAt(java.time.LocalDateTime.now());
        controlEvaluationJobRepository.save(jobEntity);

        return controlEvaluationJobMapper.toResponseDto(jobEntity);
    }

    /**
     * Fetches catalog assets (paged) and indexes them by BOTH assetId and assetUri so a
     * control-evaluation request can look up its targets by either key. A catalog/transport
     * failure yields an empty index - evaluation then falls back to the findings lookup rather
     * than failing the job.
     */
    private Map<String, CatalogAssetDTO> fetchCatalogAssetIndex() {
        Map<String, CatalogAssetDTO> index = new HashMap<>();
        try {
            int page = 0;
            while (page < 50) {
                CatalogAssetPageDTO res = catalogAssetClient.getAssets(page, 200);
                if (res == null || res.getContent() == null || res.getContent().isEmpty()) {
                    break;
                }
                for (CatalogAssetDTO a : res.getContent()) {
                    if (a.getAssetId() != null) {
                        index.put(a.getAssetId(), a);
                    }
                    if (a.getAssetUri() != null) {
                        index.putIfAbsent(a.getAssetUri(), a);
                    }
                }
                if (res.isLast() || res.getContent().size() < 200) {
                    break;
                }
                page++;
            }
        } catch (Exception e) {
            log.warn("Could not fetch catalog assets for real control evaluation ({}); "
                    + "falling back to findings lookup.", e.getMessage());
        }
        return index;
    }

    @Override
    @Transactional(readOnly = true)
    public ControlEvaluationResponseDTO getControlEvaluationJobStatus(String jobId) {
        return controlEvaluationJobRepository.findById(jobId)
                .map(controlEvaluationJobMapper::toResponseDto)
                .orElseThrow(() -> new ControlEvaluationNotFoundException(
                        "Control evaluation job not found: " + jobId));
    }
}
