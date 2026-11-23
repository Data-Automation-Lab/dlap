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

import com.dlap.autolabel.client.dto.AssetLabelsPostFeignRequest;
import com.dlap.autolabel.client.dto.CatalogAssetSummaryDTO;
import com.dlap.autolabel.client.dto.LabelAssignmentFeignDTO;
import com.dlap.autolabel.client.feign.AssetCatalogClient;
import com.dlap.llm.client.config.MLConfigRequest;
import com.dlap.autolabel.entity.ManualReviewQueueEntity;
import com.dlap.common.constants.DlapConstants;
import com.dlap.llm.client.ILLMClient;
import com.dlap.llm.client.LLMClientFactory;
import com.dlap.llm.client.model.LabelSuggestion;
import com.dlap.autolabel.repository.ManualReviewQueueRepository;
import com.dlap.autolabel.service.IAutoLabelOrchestrator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default implementation of {@link IAutoLabelOrchestrator}.
 *
 * <p>Single-asset path: fetch via Feign → build content → invoke
 * configured ILLMClient → split suggestions by confidence threshold
 * → apply confident ones via Feign → queue rest in
 * {@code manual_review_queue}.
 *
 * <p>Default LLM provider: Ollama (ADR-0015). Override via
 * {@code dlap.autolabel.llm-provider}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AutoLabelOrchestratorImpl implements IAutoLabelOrchestrator {

    private final AssetCatalogClient catalogClient;
    private final LLMClientFactory llmClientFactory;
    private final ManualReviewQueueRepository manualReviewRepo;

    @Value("${dlap.autolabel.confidence-threshold:0.7}")
    private double confidenceThreshold;

    @Value("${dlap.autolabel.llm-provider:ollama}")
    private String defaultProvider;

    @Value("${dlap.autolabel.llm-model:llama3}")
    private String defaultModel;

    @Override
    @Transactional
    public int labelAsset(UUID assetId) {
        return labelAssetDetailed(assetId).size();
    }

    @Override
    @Transactional
    public List<String> labelAssetDetailed(UUID assetId) {
        if (assetId == null) {
            log.warn("labelAsset called with null assetId");
            return List.of();
        }

        CatalogAssetSummaryDTO asset;
        try {
            asset = catalogClient.getAssetById(assetId);
        } catch (Exception e) {
            log.warn("Failed to fetch asset {} from catalog: {}", assetId, e.getMessage());
            return List.of();
        }
        if (asset == null) {
            log.warn("Catalog returned null for asset {}", assetId);
            return List.of();
        }

        String content = buildAssetContent(asset);
        MLConfigRequest mlConfig = MLConfigRequest.builder()
                .providerType(defaultProvider)
                .modelName(defaultModel)
                .build();

        ILLMClient client;
        try {
            client = llmClientFactory.getClient(mlConfig);
        } catch (IllegalArgumentException e) {
            log.error("Configured LLM provider '{}' is not registered: {}", defaultProvider, e.getMessage());
            return List.of();
        }

        List<LabelSuggestion> suggestions;
        try {
            suggestions = client.suggestLabelsWithConfidence(content, mlConfig);
        } catch (Exception e) {
            log.warn("LLM call for asset {} failed via provider {}: {}",
                    assetId, client.getProviderName(), e.getMessage());
            return List.of();
        }
        if (suggestions == null || suggestions.isEmpty()) {
            log.debug("Asset {}: LLM returned no suggestions", assetId);
            return List.of();
        }

        List<LabelAssignmentFeignDTO> applied = new ArrayList<>();
        List<ManualReviewQueueEntity> queued = new ArrayList<>();
        for (LabelSuggestion s : suggestions) {
            if (s.getLabel() == null || s.getLabel().isBlank()) {
                continue;
            }
            double conf = s.getConfidence() != null ? s.getConfidence() : 0.0;
            if (conf >= confidenceThreshold) {
                applied.add(LabelAssignmentFeignDTO.builder()
                        .labelName(s.getLabel())
                        .confidenceScore(conf)
                        .source(assignmentSource(s))
                        .build());
            } else {
                queued.add(ManualReviewQueueEntity.builder()
                        .assetId(assetId)
                        .label(s.getLabel())
                        .category(s.getCategory())
                        .confidence(conf)
                        .reasoning(s.getReasoning())
                        .llmProvider(client.getProviderName())
                        .status("PENDING")
                        .createdAt(Instant.now())
                        .build());
            }
        }

        // Apply confident labels via the catalog Feign client.
        if (!applied.isEmpty()) {
            try {
                catalogClient.addLabelsToAsset(assetId,
                        AssetLabelsPostFeignRequest.builder().labels(applied).build());
                log.info("Applied {} confident labels to asset {} via provider {}",
                        applied.size(), assetId, client.getProviderName());
            } catch (Exception e) {
                log.warn("Failed to apply labels for asset {}: {}", assetId, e.getMessage());
                // Re-queue applied ones for manual review since they didn't land.
                for (LabelAssignmentFeignDTO a : applied) {
                    queued.add(ManualReviewQueueEntity.builder()
                            .assetId(assetId)
                            .label(a.getLabelName())
                            .confidence(a.getConfidenceScore())
                            .llmProvider(client.getProviderName())
                            .status("PENDING")
                            .reasoning("Auto-apply failed; queued for human review.")
                            .createdAt(Instant.now())
                            .build());
                }
                applied = Collections.emptyList();
            }
        }

        // Queue sub-threshold suggestions.
        if (!queued.isEmpty()) {
            manualReviewRepo.saveAll(queued);
            log.info("Queued {} sub-threshold suggestions for asset {} (threshold={})",
                    queued.size(), assetId, confidenceThreshold);
        }

        return applied.stream()
                .map(LabelAssignmentFeignDTO::getLabelName)
                .collect(Collectors.toList());
    }

    /**
     * Provenance to stamp on the assignment for one suggestion (Q-098).
     *
     * <p>A deterministic provider (the rule-based client) marks its suggestions
     * {@code SMART_LABEL_RULE}; every other suggestion is model-inferred and gets
     * {@code SMART_LABEL_ML}. Only these two sources are honoured: any other value
     * ({@code USER}, {@code SYSTEM}, {@code POLICY_ENGINE}, or an unknown string) would
     * either forge human or policy provenance or fail the catalog's source check, so it
     * falls back to {@code SMART_LABEL_ML}.
     */
    private static String assignmentSource(LabelSuggestion suggestion) {
        String source = suggestion.getSource();
        if (DlapConstants.AssignmentSource.SMART_LABEL_RULE.equals(source)) {
            return DlapConstants.AssignmentSource.SMART_LABEL_RULE;
        }
        return DlapConstants.AssignmentSource.SMART_LABEL_ML;
    }

    /**
     * Build a compact content string the LLM can classify against.
     * Picks high-signal fields from the catalog asset summary.
     */
    private String buildAssetContent(CatalogAssetSummaryDTO asset) {
        StringBuilder sb = new StringBuilder();
        sb.append("Asset name: ").append(asset.getAssetName() != null ? asset.getAssetName() : "(unknown)")
          .append("\nCloud provider: ").append(asset.getCloudProvider())
          .append("\nAsset type: ").append(asset.getAssetType())
          .append("\nRegion: ").append(asset.getRegion());
        if (asset.getLabels() != null && !asset.getLabels().isEmpty()) {
            sb.append("\nExisting labels: ").append(String.join(", ", asset.getLabels()));
        }
        if (asset.getBusinessMetadataJson() != null && !asset.getBusinessMetadataJson().isEmpty()) {
            sb.append("\nBusiness metadata: ").append(asset.getBusinessMetadataJson());
        }
        return sb.toString();
    }
}
