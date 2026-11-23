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

package com.dlap.autolabel.service.impl;

import com.dlap.autolabel.client.dto.AssetIdentifier;
import com.dlap.autolabel.client.dto.AssetLabelsPostFeignRequest;
import com.dlap.autolabel.client.dto.CatalogAssetPageDTO;
import com.dlap.autolabel.client.dto.CatalogAssetSummaryDTO;
import com.dlap.autolabel.client.dto.LabelAssignmentFeignDTO;
import com.dlap.autolabel.client.feign.AssetCatalogClient;
import com.dlap.autolabel.client.rest.dto.*;
import com.dlap.autolabel.entity.LabelingFeedbackEntity;
import com.dlap.autolabel.entity.LabelingJobEntity;
import com.dlap.autolabel.exception.AutoLabelingException;
import com.dlap.autolabel.mapper.LabelingJobMapper;
import com.dlap.autolabel.repository.LabelingFeedbackRepository;
import com.dlap.autolabel.repository.LabelingJobRepository;
import com.dlap.autolabel.service.IMLConfigService;
import com.dlap.autolabel.service.ILabelingJobService;
import com.dlap.autolabel.service.ILLMIntegrationService;
import com.dlap.llm.client.config.MLConfigRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class LabelingJobServiceImpl implements ILabelingJobService {

    private final IMLConfigService mlConfigService;
    private final LabelingJobRepository labelingJobRepository;
    private final LabelingFeedbackRepository labelingFeedbackRepository;
    private final LabelingJobMapper labelingJobMapper;
    private final AssetCatalogClient assetCatalogClient;
    private final ILLMIntegrationService llmIntegrationService;

    @Override
    @Transactional
    public LabelingJobResponse submitLabelingJob(LabelingJobRequest request) throws AutoLabelingException {
        log.info("Received request to submit labeling job: {}", request.getJobName());

        MLConfigRequest currentMlConfig = request.getOverrideMlConfig() != null
                ? request.getOverrideMlConfig()
                : mlConfigService.getMlConfig().orElse(null);

        if (currentMlConfig == null) {
            log.error("ML configuration not found. Cannot submit labeling job.");
            throw new AutoLabelingException("Auto-labeling ML configuration is not set. Please configure it via PUT /api/v1/labeling/config/ml.");
        }
        // Warn when the caller has not specified an LLM provider OR is explicitly
        // requesting the mock model by name - either case means the downstream
        // LLMIntegrationService will only succeed if dlap.autolabel.allow-mock=true.
        if (currentMlConfig.getProviderType() == null || "MOCK".equalsIgnoreCase(currentMlConfig.getModelName())) {
            log.warn("MLConfig for job '{}' targets no real provider (providerType={}, modelName={}); will only succeed if dlap.autolabel.allow-mock=true.",
                    request.getJobName(), currentMlConfig.getProviderType(), currentMlConfig.getModelName());
        }

        String jobId = UUID.randomUUID().toString();
        LocalDateTime submittedAt = LocalDateTime.now();

        LabelingJobEntity jobEntity = LabelingJobEntity.builder()
                .jobId(jobId)
                .jobName(request.getJobName())
                .status(LabelingJobEntity.JobStatus.SUBMITTED)
                .submittedAt(submittedAt)
                .lastUpdatedAt(submittedAt)
                .scope(request.getScope())
                .effectiveMlConfig(currentMlConfig)
                .totalAssetsToProcess(0)
                .assetsProcessed(0)
                .assetsSuccessfullyLabeled(0)
                .assetsFailed(0)
                .progressPercentage(0.0)
                .errorMessages(new ArrayList<>())
                .build();
        labelingJobRepository.save(jobEntity);

        log.info("Labeling job {} submitted successfully. Job ID: {}", request.getJobName(), jobId);
        processLabelingJobAsync(jobId, request.getJobName(), request.getScope(), currentMlConfig);

        return LabelingJobResponse.builder()
                .jobId(jobId)
                .status("SUBMITTED")
                .submittedAt(submittedAt)
                .message("Labeling job '" + request.getJobName() + "' submitted successfully.")
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public LabelingJobStatusResponse getJobStatus(String jobId) {
        log.debug("Fetching status for job ID: {}", jobId);
        return labelingJobRepository.findById(jobId)
                .map(labelingJobMapper::toStatusResponse)
                .orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public LabelingJobListResponse listJobs(Pageable pageable) {
        log.debug("Fetching list of jobs with pagination: {}", pageable);
        Page<LabelingJobEntity> jobPage = labelingJobRepository.findAll(pageable);
        return labelingJobMapper.toJobListResponse(jobPage);
    }

    @Async
    @Transactional
    public void processLabelingJobAsync(String jobId, String jobName, LabelingJobRequest.LabelingScope scope, MLConfigRequest mlConfig) {
        log.info("[Job ID: {}] Starting asynchronous processing for job: {}", jobId, jobName);
        updateJobStatus(jobId, LabelingJobEntity.JobStatus.PREPARING_DATA, "Fetching asset list...", null, null, null, null, 0.05);

        List<AssetIdentifier> targetAssets;
        try {
            targetAssets = resolveAssetIdentifiers(scope);
        } catch (Exception e) {
            log.error("[Job ID: {}] Failed to resolve asset identifiers: {}", jobId, e.getMessage(), e);
            updateJobStatus(jobId, LabelingJobEntity.JobStatus.FAILED, "Failed to resolve asset scope.", 0,0,0,0, 1.0);
            labelingJobRepository.findById(jobId).ifPresent(job -> job.setCompletedAt(LocalDateTime.now()));
            return;
        }

        if (targetAssets.isEmpty()) {
            log.warn("[Job ID: {}] No assets found for the given scope.", jobId);
            updateJobStatus(jobId, LabelingJobEntity.JobStatus.FAILED, "No assets found for the given scope.", 0, 0, 0, 0, 1.0);
            labelingJobRepository.findById(jobId).ifPresent(job -> job.setCompletedAt(LocalDateTime.now()));
            return;
        }
        updateJobStatus(jobId, null, "Identified " + targetAssets.size() + " assets for labeling.", targetAssets.size(), 0, 0, 0, 0.1);
        labelingJobRepository.findById(jobId).ifPresent(job -> job.setStartedAt(LocalDateTime.now()));

        int processed = 0;
        int succeeded = 0;
        int failed = 0;

        for (AssetIdentifier asset : targetAssets) {
            String assetId = asset.getAssetId();
            try {
                log.info("[Job ID: {}] Processing asset: {}", jobId, assetId);
                updateJobStatus(jobId, LabelingJobEntity.JobStatus.CALLING_LLM, "Processing asset: " + assetId, null, null, null, null, 0.1 + (0.8 * ((double)processed / targetAssets.size())));

                String assetTechnicalMetadata = fetchCatalogSnapshotForLlm(assetId);

                List<String> suggestedLabels = llmIntegrationService.suggestLabelsForAsset(assetTechnicalMetadata, mlConfig);
                log.info("[Job ID: {}] LLM suggested labels for {}: {}", jobId, assetId, suggestedLabels);

                try {
                    UUID catalogId = UUID.fromString(assetId);
                    if (suggestedLabels != null && !suggestedLabels.isEmpty()) {
                        assetCatalogClient.addLabelsToAsset(catalogId, toAssignLabelsRequest(suggestedLabels));
                    }
                    log.info("[Job ID: {}] Applied labels via catalog for asset: {}", jobId, assetId);
                } catch (IllegalArgumentException e) {
                    log.warn("[Job ID: {}] assetId {} is not a catalog UUID; skipping catalog label write", jobId, assetId);
                } catch (Exception e) {
                    log.warn("[Job ID: {}] Catalog label update failed for asset {}: {}", jobId, assetId, e.getMessage());
                }
                succeeded++;

            } catch (InterruptedException e) {
                log.warn("[Job ID: {}] Processing for asset {} interrupted.", jobId, assetId);
                Thread.currentThread().interrupt();
                failed++;
                addErrorMessageToJob(jobId, "Processing interrupted for asset " + assetId);
            } catch (Exception e) {
                log.error("[Job ID: {}] Error processing asset {}: {}", jobId, assetId, e.getMessage(), e);
                failed++;
                addErrorMessageToJob(jobId, "Failed to process asset " + assetId + ": " + e.getMessage());
            }
            processed++;
            updateJobStatus(jobId, null, "Processed asset: " + assetId, null, processed, succeeded, failed, 0.1 + (0.8 * ((double)processed / targetAssets.size())));
        }

        LabelingJobEntity.JobStatus finalStatus = failed == 0 ? LabelingJobEntity.JobStatus.COMPLETED : (succeeded > 0 ? LabelingJobEntity.JobStatus.PARTIALLY_COMPLETED : LabelingJobEntity.JobStatus.FAILED);
        String finalMessage = String.format("Job %s. Total: %d, Succeeded: %d, Failed: %d.",
                finalStatus.name().toLowerCase(), targetAssets.size(), succeeded, failed);
        updateJobStatus(jobId, finalStatus, finalMessage, null, processed, succeeded, failed, 1.0);
        labelingJobRepository.findById(jobId).ifPresent(job -> job.setCompletedAt(LocalDateTime.now()));
        log.info("[Job ID: {}] Labeling job processing finished with status: {}. Message: {}", jobId, finalStatus, finalMessage);
    }

    private void updateJobStatus(String jobId, LabelingJobEntity.JobStatus status, String stageDescription,
                                 Integer totalAssets, Integer processedAssets,
                                 Integer succeededAssets, Integer failedAssets, Double progress) {
        labelingJobRepository.findById(jobId).ifPresent(job -> {
            if (status != null) job.setStatus(status);
            if (stageDescription != null) job.setCurrentStageDescription(stageDescription);
            if (totalAssets != null) job.setTotalAssetsToProcess(totalAssets);
            if (processedAssets != null) job.setAssetsProcessed(processedAssets);
            if (succeededAssets != null) job.setAssetsSuccessfullyLabeled(succeededAssets);
            if (failedAssets != null) job.setAssetsFailed(failedAssets);
            if (progress != null) job.setProgressPercentage(progress);
            job.setLastUpdatedAt(LocalDateTime.now());
            labelingJobRepository.save(job);
            log.debug("[Job ID: {}] Status updated: {}", jobId, job);
        });
    }

    private void addErrorMessageToJob(String jobId, String errorMessage) {
        labelingJobRepository.findById(jobId).ifPresent(job -> {
            if(job.getErrorMessages() == null) job.setErrorMessages(new ArrayList<>());
            job.getErrorMessages().add(errorMessage);
            labelingJobRepository.save(job);
        });
    }

    @Override
    @Transactional
    public LabelingFeedbackResponse processLabelingFeedback(LabelingFeedbackRequest request) throws AutoLabelingException {
        log.info("Received labeling feedback for asset ID: {}, job ID: {}", request.getAssetId(), request.getLabelingJobId());

        if (request.getFeedbackItems() == null || request.getFeedbackItems().isEmpty()) {
            log.warn("Labeling feedback request for asset {} is empty.", request.getAssetId());
            throw new AutoLabelingException("Feedback items cannot be empty.");
        }

        String feedbackId = UUID.randomUUID().toString();
        LabelingFeedbackEntity feedbackEntity = LabelingFeedbackEntity.builder()
                .feedbackId(feedbackId)
                .assetId(request.getAssetId())
                .labelingJobId(request.getLabelingJobId())
                .feedbackItems(request.getFeedbackItems())
                .userId(request.getUserId())
                .additionalContext(request.getAdditionalContext())
                .receivedAt(LocalDateTime.now())
                .processingStatus("RECEIVED")
                .build();
        labelingFeedbackRepository.save(feedbackEntity);

        UUID assetUuid;
        try {
            assetUuid = UUID.fromString(request.getAssetId());
        } catch (IllegalArgumentException e) {
            throw new AutoLabelingException("assetId must be a catalog UUID for feedback processing.");
        }

        for (LabelingFeedbackRequest.FeedbackItem item : request.getFeedbackItems()) {
            try {
                switch (item.getType()) {
                    case CONFIRMED:
                        if (item.getSuggestedLabel() != null) {
                            assetCatalogClient.addLabelsToAsset(assetUuid, toAssignLabelsRequest(List.of(item.getSuggestedLabel())));
                        }
                        log.info("[Feedback ID: {}] Asset: {}, Label '{}' confirmed via catalog.", feedbackId, request.getAssetId(), item.getSuggestedLabel());
                        break;
                    case CORRECTED:
                        if (item.getSuggestedLabel() != null) {
                            assetCatalogClient.removeLabelFromAsset(assetUuid, item.getSuggestedLabel());
                        }
                        if (item.getCorrectedLabel() != null) {
                            assetCatalogClient.addLabelsToAsset(assetUuid, toAssignLabelsRequest(List.of(item.getCorrectedLabel())));
                        }
                        log.info("[Feedback ID: {}] Asset: {}, Label '{}' corrected to '{}'.",
                                feedbackId, request.getAssetId(), item.getSuggestedLabel(), item.getCorrectedLabel());
                        break;
                    case REJECTED:
                        if (item.getSuggestedLabel() != null) {
                            assetCatalogClient.removeLabelFromAsset(assetUuid, item.getSuggestedLabel());
                        }
                        log.info("[Feedback ID: {}] Asset: {}, Label '{}' rejected (removed from catalog).", feedbackId, request.getAssetId(), item.getSuggestedLabel());
                        break;
                    case FLAGGED:
                        log.info("[Feedback ID: {}] Asset: {}, Label '{}' flagged for review.", feedbackId, request.getAssetId(), item.getSuggestedLabel());
                        break;
                }
            } catch (Exception e) {
                log.error("[Feedback ID: {}] Error processing feedback item for asset {}: {} - {}", feedbackId, request.getAssetId(), item, e.getMessage(), e);
            }
        }

        feedbackEntity.setProcessingStatus("PROCESSED");
        feedbackEntity.setProcessedAt(LocalDateTime.now());
        labelingFeedbackRepository.save(feedbackEntity);

        return LabelingFeedbackResponse.builder()
                .feedbackId(feedbackId)
                .assetId(request.getAssetId())
                .status(feedbackEntity.getProcessingStatus())
                .message("Feedback processed successfully.")
                .receivedAt(feedbackEntity.getReceivedAt())
                .build();
    }

    private List<AssetIdentifier> resolveAssetIdentifiers(LabelingJobRequest.LabelingScope scope) throws AutoLabelingException {
        if (scope == null) return List.of();

        List<AssetIdentifier> assets = new ArrayList<>();
        if (scope.getAssetIds() != null && !scope.getAssetIds().isEmpty()) {
            assets.addAll(scope.getAssetIds().stream().map(id -> AssetIdentifier.builder().assetId(id).build()).collect(Collectors.toList()));
        }
        
        if (assets.isEmpty() && scope.getCloudConnectionId() != null) {
            try {
                UUID connectionId = UUID.fromString(scope.getCloudConnectionId());
                CatalogAssetPageDTO page = assetCatalogClient.getAssetsByConnection(connectionId, 0, 500);
                if (page.getContent() != null) {
                    for (var row : page.getContent()) {
                        if (row.getAssetId() != null) {
                            assets.add(AssetIdentifier.builder()
                                    .assetId(row.getAssetId().toString())
                                    .provider(row.getCloudProvider())
                                    .build());
                        }
                    }
                }
            } catch (Exception e) {
                log.error("Failed to resolve assets for cloudConnectionId {}: {}", scope.getCloudConnectionId(), e.getMessage());
            }
        }

        if (assets.isEmpty()) {
            log.warn("No assets resolved for labeling scope.");
        }
        return assets.stream().distinct().collect(Collectors.toList());
    }

    private String fetchCatalogSnapshotForLlm(String assetId) {
        try {
            UUID uuid = UUID.fromString(assetId);
            CatalogAssetSummaryDTO dto = assetCatalogClient.getAssetById(uuid);
            return buildCatalogSnapshotText(dto);
        } catch (Exception e) {
            log.warn("Could not load catalog snapshot for asset {}: {}", assetId, e.getMessage());
            return "Asset " + assetId + " (catalog metadata unavailable)";
        }
    }

    private static AssetLabelsPostFeignRequest toAssignLabelsRequest(List<String> labelNames) {
        List<LabelAssignmentFeignDTO> labels = labelNames.stream()
                .filter(java.util.Objects::nonNull)
                .map(n -> LabelAssignmentFeignDTO.builder().labelName(n).build())
                .collect(Collectors.toList());
        return AssetLabelsPostFeignRequest.builder().labels(labels).build();
    }

    private static String buildCatalogSnapshotText(CatalogAssetSummaryDTO dto) {
        if (dto == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Asset name: ").append(dto.getAssetName()).append('\n');
        sb.append("Type: ").append(dto.getAssetType()).append('\n');
        sb.append("Provider: ").append(dto.getCloudProvider()).append('\n');
        sb.append("Region: ").append(dto.getRegion()).append('\n');
        if (dto.getLabels() != null && !dto.getLabels().isEmpty()) {
            sb.append("Labels: ").append(String.join(", ", dto.getLabels())).append('\n');
        }
        if (dto.getBusinessMetadataJson() != null && !dto.getBusinessMetadataJson().isEmpty()) {
            sb.append("Business metadata: ").append(dto.getBusinessMetadataJson()).append('\n');
        }
        return sb.toString();
    }
} 