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

package com.dlap.autolabel.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.dlap.autolabel.client.dto.CatalogAssetPageDTO;
import com.dlap.autolabel.client.dto.CatalogAssetSummaryDTO;
import com.dlap.autolabel.client.feign.ResilientAssetCatalogClient;
import com.dlap.autolabel.client.rest.dto.LabelingFeedbackRequest;
import com.dlap.autolabel.client.rest.dto.LabelingFeedbackResponse;
import com.dlap.autolabel.client.rest.dto.LabelingJobRequest;
import com.dlap.autolabel.client.rest.dto.LabelingJobResponse;
import com.dlap.llm.client.config.MLConfigRequest;
import com.dlap.autolabel.entity.LabelFeedbackEntity;
import com.dlap.autolabel.exception.CatalogUnavailableException;
import com.dlap.llm.client.ILLMClient;
import com.dlap.llm.client.LLMClientFactory;
import com.dlap.llm.client.model.LabelSuggestion;
import com.dlap.autolabel.repository.ILabelFeedbackRepository;
/**
 * Core auto-labeling service that orchestrates the LLM-powered labeling workflow.
 * Handles job creation, execution, feedback processing, and active learning.
 */
@Service
public class AutoLabelingService {

    private static final Logger log = LoggerFactory.getLogger(AutoLabelingService.class);

    private final LLMClientFactory llmClientFactory;
    private final IMLConfigService mlConfigService;
    private final ResilientAssetCatalogClient assetCatalogClient;
    private final ILabelFeedbackRepository labelFeedbackRepository;

    @Autowired
    public AutoLabelingService(
            LLMClientFactory llmClientFactory,
            IMLConfigService mlConfigService,
            ResilientAssetCatalogClient assetCatalogClient,
            ILabelFeedbackRepository labelFeedbackRepository) {
        this.llmClientFactory = llmClientFactory;
        this.mlConfigService = mlConfigService;
        this.assetCatalogClient = assetCatalogClient;
        this.labelFeedbackRepository = labelFeedbackRepository;
    }

    /**
     * Creates and processes a labeling job for one or more assets.
     *
     * @param request The labeling job request containing scope and configuration
     * @return The labeling job response with job status
     */
    public LabelingJobResponse createLabelingJob(LabelingJobRequest request) {
        log.info("Creating labeling job: {}", request.getJobName());

        long startTime = System.currentTimeMillis();
        String jobId = UUID.randomUUID().toString();
        
        try {
            // Validate the request
            validateLabelingRequest(request);

            // Get ML configuration (use override or global default)
            MLConfigRequest mlConfig = request.getOverrideMlConfig() != null
                    ? request.getOverrideMlConfig()
                    : mlConfigService.getMlConfig().orElse(null);

            if (mlConfig == null) {
                throw new IllegalArgumentException("No ML configuration available");
            }

            // Get the appropriate LLM client
            ILLMClient llmClient = llmClientFactory.getClient(mlConfig);

            // Get assets to process based on scope
            List<String> assetIds = getAssetsFromScope(request.getScope());
            
            if (assetIds.isEmpty()) {
                return LabelingJobResponse.builder()
                        .jobId(jobId)
                        .status("COMPLETED")
                        .submittedAt(LocalDateTime.now())
                        .createdAt(LocalDateTime.now())
                        .completedAt(LocalDateTime.now())
                        .processingTimeMs(System.currentTimeMillis() - startTime)
                        .message("No assets found matching the specified scope")
                        .build();
            }

            // For single asset, process immediately
            if (assetIds.size() == 1) {
                String assetId = assetIds.get(0);

                // Get asset content for LLM processing. If the catalog cannot supply
                // real metadata we refuse to feed placeholder text to the LLM and
                // fail the job instead (see CatalogUnavailableException).
                String assetContent;
                try {
                    assetContent = getAssetContent(assetId);
                } catch (CatalogUnavailableException cue) {
                    log.error("Catalog unavailable for asset {}: {}", assetId, cue.getMessage());
                    return LabelingJobResponse.builder()
                            .jobId(jobId)
                            .assetId(assetId)
                            .status("FAILED")
                            .submittedAt(LocalDateTime.now())
                            .createdAt(LocalDateTime.now())
                            .completedAt(LocalDateTime.now())
                            .processingTimeMs(System.currentTimeMillis() - startTime)
                            .errorMessage("catalog unavailable for asset " + assetId)
                            .build();
                }

                // Get label suggestions from LLM. Any failure here must fail the job
                // - never substitute fake/placeholder suggestions.
                List<LabelSuggestion> suggestions;
                try {
                    suggestions = llmClient.suggestLabelsWithConfidence(assetContent, mlConfig);
                } catch (Exception llmError) {
                    log.error("LLM suggestLabelsWithConfidence failed for asset {}: {}", assetId, llmError.getMessage(), llmError);
                    return LabelingJobResponse.builder()
                            .jobId(jobId)
                            .assetId(assetId)
                            .status("FAILED")
                            .submittedAt(LocalDateTime.now())
                            .createdAt(LocalDateTime.now())
                            .completedAt(LocalDateTime.now())
                            .processingTimeMs(System.currentTimeMillis() - startTime)
                            .errorMessage("LLM call failed: " + llmError.getMessage())
                            .build();
                }

                // Filter suggestions based on confidence thresholds
                List<LabelSuggestion> filteredSuggestions = filterSuggestionsByConfidence(suggestions, mlConfig);

                return LabelingJobResponse.builder()
                        .jobId(jobId)
                        .assetId(assetId)
                        .status("COMPLETED")
                        .submittedAt(LocalDateTime.now())
                        .createdAt(LocalDateTime.now())
                        .completedAt(LocalDateTime.now())
                        .processingTimeMs(System.currentTimeMillis() - startTime)
                        .message("Labeling completed successfully")
                        .suggestions(convertToLabelSuggestionDTOs(filteredSuggestions))
                        .build();
            } else {
                // For multiple assets, return job submitted status
                // TODO: Implement async processing for large batches
                return LabelingJobResponse.builder()
                        .jobId(jobId)
                        .status("SUBMITTED")
                        .submittedAt(LocalDateTime.now())
                        .createdAt(LocalDateTime.now())
                        .message("Job submitted for processing " + assetIds.size() + " assets")
                        .build();
            }

        } catch (Exception e) {
            log.error("Failed to create labeling job {}: {}", request.getJobName(), e.getMessage(), e);
            
            return LabelingJobResponse.builder()
                    .jobId(jobId)
                    .status("FAILED")
                    .submittedAt(LocalDateTime.now())
                    .createdAt(LocalDateTime.now())
                    .completedAt(LocalDateTime.now())
                    .processingTimeMs(System.currentTimeMillis() - startTime)
                    .errorMessage("Failed to process labeling job: " + e.getMessage())
                    .build();
        }
    }

    /**
     * Processes multiple labeling jobs in batch for efficiency.
     *
     * @param jobRequests List of labeling job requests
     * @return List of labeling job responses
     */
    public List<LabelingJobResponse> createBatchLabelingJobs(List<LabelingJobRequest> jobRequests) {
        log.info("Processing batch of {} labeling jobs", jobRequests.size());

        List<LabelingJobResponse> allResponses = new ArrayList<>();

        // Group requests by ML configuration to process efficiently
        Map<MLConfigRequest, List<LabelingJobRequest>> configGroups = jobRequests.stream()
                .collect(Collectors.groupingBy(request ->
                        request.getOverrideMlConfig() != null
                                ? request.getOverrideMlConfig()
                                : mlConfigService.getMlConfig().orElse(null)));

        for (Map.Entry<MLConfigRequest, List<LabelingJobRequest>> entry : configGroups.entrySet()) {
            MLConfigRequest mlConfig = entry.getKey();
            List<LabelingJobRequest> requests = entry.getValue();

            try {
                ILLMClient llmClient = llmClientFactory.getClient(mlConfig);

                for (LabelingJobRequest request : requests) {
                    try {
                        LabelingJobResponse response = createLabelingJob(request);
                        allResponses.add(response);
                    } catch (Exception e) {
                        log.error("Failed to process job {}: {}", request.getJobName(), e.getMessage(), e);
                        
                        LabelingJobResponse errorResponse = LabelingJobResponse.builder()
                                .jobId(UUID.randomUUID().toString())
                                .status("FAILED")
                                .submittedAt(LocalDateTime.now())
                                .createdAt(LocalDateTime.now())
                                .completedAt(LocalDateTime.now())
                                .errorMessage("Job processing failed: " + e.getMessage())
                                .build();
                        allResponses.add(errorResponse);
                    }
                }

            } catch (Exception e) {
                log.error("Failed to process batch with ML config {}: {}", mlConfig.getProviderType(), e.getMessage(), e);
                
                // Create error responses for all requests in this batch
                for (LabelingJobRequest request : requests) {
                    LabelingJobResponse errorResponse = LabelingJobResponse.builder()
                            .jobId(UUID.randomUUID().toString())
                            .status("FAILED")
                            .submittedAt(LocalDateTime.now())
                            .createdAt(LocalDateTime.now())
                            .completedAt(LocalDateTime.now())
                            .errorMessage("Batch processing failed: " + e.getMessage())
                            .build();
                    allResponses.add(errorResponse);
                }
            }
        }

        return allResponses;
    }

    /**
     * Processes feedback for active learning and model improvement.
     *
     * @param feedback The feedback request containing user corrections
     * @return The feedback response
     */
    public LabelingFeedbackResponse processFeedback(LabelingFeedbackRequest feedback) {
        log.info("Processing feedback for asset: {}", feedback.getAssetId());

        try {
            String feedbackId = UUID.randomUUID().toString();
            LocalDateTime now = LocalDateTime.now();

            // Persist one row per feedback item so downstream active-learning
            // pipelines can aggregate them later. The mapping from the DTO's
            // FeedbackType to an accepted/rejected boolean is: CONFIRMED is an
            // accept; REJECTED / CORRECTED / FLAGGED are all non-accept signals.
            if (feedback.getFeedbackItems() != null) {
                for (LabelingFeedbackRequest.FeedbackItem item : feedback.getFeedbackItems()) {
                    boolean accepted = item.getType() == LabelingFeedbackRequest.FeedbackType.CONFIRMED;
                    LabelFeedbackEntity entity = LabelFeedbackEntity.builder()
                            .assetId(feedback.getAssetId())
                            .suggestedLabel(item.getSuggestedLabel())
                            .accepted(accepted)
                            .userId(feedback.getUserId())
                            .comment(item.getUserComment())
                            .timestamp(now)
                            .build();
                    labelFeedbackRepository.save(entity);
                }
            }

            // TODO(M2): trigger active-learning / model retraining once enough
            // feedback has accumulated - out of scope for M1.

            return LabelingFeedbackResponse.builder()
                    .feedbackId(feedbackId)
                    .assetId(feedback.getAssetId())
                    .status("PROCESSED")
                    .message("Feedback processed successfully")
                    .processedAt(now)
                    .build();

        } catch (Exception e) {
            log.error("Failed to process feedback for asset {}: {}", feedback.getAssetId(), e.getMessage(), e);
            
            return LabelingFeedbackResponse.builder()
                    .feedbackId(UUID.randomUUID().toString())
                    .assetId(feedback.getAssetId())
                    .status("FAILED")
                    .message("Failed to process feedback: " + e.getMessage())
                    .processedAt(LocalDateTime.now())
                    .build();
        }
    }

    /**
     * Updates ML configuration and validates it.
     *
     * @param mlConfig The new ML configuration
     * @return true if configuration is valid and updated, false otherwise
     */
    public boolean updateMLConfiguration(MLConfigRequest mlConfig) {
        log.info("Updating ML configuration for provider: {}", mlConfig.getProviderType());

        try {
            // Validate configuration
            boolean isValid = llmClientFactory.validateConfiguration(mlConfig);
            if (!isValid) {
                log.warn("ML configuration validation failed for provider: {}", mlConfig.getProviderType());
                return false;
            }

            // Test connection
            boolean connectionOk = llmClientFactory.testConnection(mlConfig);
            if (!connectionOk) {
                log.warn("ML configuration connection test failed for provider: {}", mlConfig.getProviderType());
                return false;
            }

            mlConfigService.updateMlConfig(mlConfig);
            log.info("ML configuration updated successfully for provider: {}", mlConfig.getProviderType());
            return true;

        } catch (Exception e) {
            log.error("Failed to update ML configuration: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Gets all available LLM providers and their capabilities.
     *
     * @return Map of provider capabilities
     */
    public Map<String, Map<String, Object>> getProviderCapabilities() {
        return llmClientFactory.getAllProviderCapabilities();
    }

    // Private helper methods

    private void validateLabelingRequest(LabelingJobRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Labeling request cannot be null");
        }
        if (request.getJobName() == null || request.getJobName().trim().isEmpty()) {
            throw new IllegalArgumentException("Job name cannot be null or empty");
        }
        if (request.getScope() == null) {
            throw new IllegalArgumentException("Labeling scope cannot be null");
        }
        
        // Validate that at least one asset selection method is provided
        if ((request.getScope().getAssetIds() == null || request.getScope().getAssetIds().isEmpty()) &&
            request.getScope().getCloudConnectionId() == null &&
            (request.getScope().getAssetCriteria() == null || request.getScope().getAssetCriteria().isEmpty()) &&
            request.getScope().getAssetGroupId() == null) {
            throw new IllegalArgumentException("At least one asset selection method must be provided in scope");
        }
    }

    private String prepareAssetContent(LabelingJobRequest request) {
        // This method is no longer used with the new DTO structure
        // Asset content is now retrieved per asset ID via getAssetContent()
        return "Asset content preparation moved to getAssetContent() method";
    }

    private List<LabelSuggestion> filterSuggestionsByConfidence(List<LabelSuggestion> suggestions, MLConfigRequest mlConfig) {
        if (mlConfig.getConfidenceConfig() == null || mlConfig.getConfidenceConfig().getMinThreshold() == null) {
            return suggestions; // No filtering
        }

        double minThreshold = mlConfig.getConfidenceConfig().getMinThreshold();
        
        return suggestions.stream()
                .filter(suggestion -> suggestion.getConfidence() != null && suggestion.getConfidence() >= minThreshold)
                .collect(Collectors.toList());
    }

    /**
     * Get list of asset IDs from the labeling scope.
     */
    private List<String> getAssetsFromScope(LabelingJobRequest.LabelingScope scope) {
        List<String> assetIds = new ArrayList<>();
        
        if (scope.getAssetIds() != null && !scope.getAssetIds().isEmpty()) {
            assetIds.addAll(scope.getAssetIds());
        }

        if (assetIds.isEmpty() && scope.getCloudConnectionId() != null) {
            try {
                UUID connectionId = UUID.fromString(scope.getCloudConnectionId());
                CatalogAssetPageDTO page = assetCatalogClient.getAssetsByConnection(connectionId, 0, 500);
                if (page.getContent() != null) {
                    page.getContent().stream()
                            .map(CatalogAssetSummaryDTO::getAssetId)
                            .filter(Objects::nonNull)
                            .map(UUID::toString)
                            .forEach(assetIds::add);
                }
            } catch (Exception e) {
                log.warn("Could not list assets for connection {}: {}", scope.getCloudConnectionId(), e.getMessage());
            }
        }

        return assetIds;
    }

    /**
     * Get asset content for LLM processing.
     *
     * @throws CatalogUnavailableException when the catalog lookup fails OR when
     *         the catalog returns no row for the given asset id. Callers must
     *         fail the labeling job rather than feed a placeholder to the LLM.
     */
    private String getAssetContent(String assetId) {
        CatalogAssetSummaryDTO dto;
        try {
            UUID uuid = UUID.fromString(assetId);
            dto = assetCatalogClient.getAssetById(uuid);
        } catch (IllegalArgumentException badUuid) {
            throw new CatalogUnavailableException(
                    "Asset id '" + assetId + "' is not a catalog UUID", badUuid);
        } catch (Exception e) {
            log.warn("Catalog lookup failed for asset {}: {}", assetId, e.getMessage());
            throw new CatalogUnavailableException(
                    "Catalog lookup failed for asset " + assetId + ": " + e.getMessage(), e);
        }
        if (dto == null) {
            throw new CatalogUnavailableException(
                    "No catalog row found for asset " + assetId);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Name: ").append(dto.getAssetName()).append('\n');
        sb.append("Type: ").append(dto.getAssetType()).append('\n');
        sb.append("Provider: ").append(dto.getCloudProvider()).append('\n');
        sb.append("Region: ").append(dto.getRegion()).append('\n');
        if (dto.getLabels() != null) {
            sb.append("Labels: ").append(String.join(", ", dto.getLabels())).append('\n');
        }
        if (dto.getBusinessMetadataJson() != null) {
            sb.append("Business metadata: ").append(dto.getBusinessMetadataJson());
        }
        return sb.toString();
    }

    /**
     * Convert LLM label suggestions to DTOs.
     */
    private List<LabelingJobResponse.LabelSuggestionDTO> convertToLabelSuggestionDTOs(List<LabelSuggestion> suggestions) {
        return suggestions.stream()
                .map(suggestion -> LabelingJobResponse.LabelSuggestionDTO.builder()
                        .labelName(suggestion.getLabel())
                        .confidence(suggestion.getConfidence())
                        .reasoning(suggestion.getReasoning())
                        .category(suggestion.getCategory())
                        .build())
                .collect(Collectors.toList());
    }
} 