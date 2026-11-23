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

package com.dlap.llm.client.impl;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.dlap.llm.client.config.MLConfigRequest;
import com.dlap.llm.client.ILLMClient;
import com.dlap.llm.client.model.LLMResponse;
import com.dlap.llm.client.model.LabelSuggestion;

import lombok.extern.slf4j.Slf4j;

/**
 * Offline placeholder that returns labels unrelated to the asset. Only for local
 * development and plumbing tests; callers must gate it behind an explicit opt-in
 * flag ({@code dlap.autolabel.allow-mock}, default {@code false}).
 *
 * <p>The output is a pure function of the input text: the same content always
 * yields the same labels, confidences and order, on every JVM and every run. The
 * labels are still <em>not</em> derived from the asset, so a demo that needs
 * labels grounded in an asset's name or metadata must use the {@code rules}
 * provider ({@link RuleBasedLabelClient}) instead. The reasoning text says so.
 *
 * <p>Registered as the {@code mockLLMClient} bean by
 * {@link com.dlap.llm.client.LlmClientAutoConfiguration}.
 */
@Slf4j
public class MockLLMClient implements ILLMClient {

    private static final List<String> MOCK_LABELS = List.of(
            "PII", "Confidential", "Public", "Sensitive", "FinancialData",
            "HealthRecord", "SourceCode", "UserGeneratedContent", "Archive", "Temporary"
    );

    private final Clock clock;

    /** Creates a mock client that stamps batch responses with the system default clock. */
    public MockLLMClient() {
        this(Clock.systemDefaultZone());
    }

    /**
     * Creates a mock client with a supplied clock, so tests can pin response timestamps.
     *
     * @param clock time source for batch response timestamps
     */
    public MockLLMClient(Clock clock) {
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
    }

    @Override
    public String getProviderName() {
        return "MOCK";
    }

    /**
     * {@inheritDoc}
     *
     * <p>Unsupported by design. Fabricating a completion would let callers that
     * build structured artefacts (policies, rules) ship invented content that
     * looks provider-generated. Callers must treat {@code MOCK} as "no provider".
     *
     * @throws UnsupportedOperationException always.
     */
    @Override
    public String complete(String prompt, MLConfigRequest mlConfig) {
        throw new UnsupportedOperationException(
                "MOCK provider does not support free-form completion; configure a real LLM provider");
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns one to three distinct labels chosen by the input text alone.
     */
    @Override
    public List<String> suggestLabels(String assetContent, MLConfigRequest mlConfig) {
        log.info("MockLLMClient: suggesting labels for {} input chars (model {})",
                length(assetContent), modelOf(mlConfig));
        Random random = randomFor(assetContent);
        List<String> shuffled = shuffledLabels(random);
        int count = 1 + random.nextInt(3);
        List<String> suggested = new ArrayList<>(shuffled.subList(0, count));
        log.info("MockLLMClient: suggested labels: {}", suggested);
        return suggested;
    }

    @Override
    public boolean validateConfiguration(MLConfigRequest mlConfig) {
        log.info("MockLLMClient: validating configuration for model: {}", modelOf(mlConfig));
        return true;
    }

    @Override
    public boolean testConnection(MLConfigRequest mlConfig) {
        log.info("MockLLMClient: testing connection for model: {}", modelOf(mlConfig));
        return true;
    }

    @Override
    public Map<String, Object> getCapabilities() {
        Map<String, Object> capabilities = new HashMap<>();
        capabilities.put("maxTokens", 4096);
        capabilities.put("supportsBatch", true);
        capabilities.put("supportsConfidence", true);
        capabilities.put("supportsReasoning", false);
        capabilities.put("deterministic", true);
        capabilities.put("derivedFromAsset", false);
        capabilities.put("provider", "MOCK");
        capabilities.put("cost", 0.0);
        return capabilities;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns two to five distinct labels with confidences in {@code [0.50, 1.00]},
     * rounded to two decimals, chosen by the input text alone.
     */
    @Override
    public List<LabelSuggestion> suggestLabelsWithConfidence(String assetContent, MLConfigRequest mlConfig) {
        log.info("MockLLMClient: suggesting labels with confidence for {} input chars (model {})",
                length(assetContent), modelOf(mlConfig));
        Random random = randomFor(assetContent);
        List<String> shuffled = shuffledLabels(random);
        int count = 2 + random.nextInt(4);

        List<LabelSuggestion> suggestions = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String label = shuffled.get(i);
            double confidence = Math.round((0.5 + random.nextDouble() * 0.5) * 100.0) / 100.0;
            suggestions.add(LabelSuggestion.builder()
                    .label(label)
                    .confidence(confidence)
                    .reasoning("MOCK placeholder for " + label
                            + ": chosen from the input text hash, not derived from the asset")
                    .category("MOCK_CATEGORY")
                    .build());
        }
        return suggestions;
    }

    @Override
    public List<LLMResponse> batchSuggestLabels(List<String> assetContents, MLConfigRequest mlConfig) {
        log.info("MockLLMClient: processing batch of {} assets (model {})",
                assetContents == null ? 0 : assetContents.size(), modelOf(mlConfig));
        List<LLMResponse> responses = new ArrayList<>();
        if (assetContents == null) {
            return responses;
        }
        for (String content : assetContents) {
            responses.add(LLMResponse.builder()
                    .inputContent(content)
                    .successful(true)
                    .suggestions(suggestLabelsWithConfidence(content, mlConfig))
                    .provider("MOCK")
                    .modelName(modelOf(mlConfig))
                    .processingTimeMs(0L)
                    .timestamp(LocalDateTime.now(clock))
                    .build());
        }
        return responses;
    }

    /**
     * Seeds a generator from the input text. {@link String#hashCode()} is specified by
     * the Java language, and {@link Random} with an explicit seed is specified by its
     * Javadoc, so the sequence is identical on every compliant JVM.
     */
    private static Random randomFor(String assetContent) {
        return new Random(assetContent == null ? 0L : assetContent.hashCode());
    }

    private static List<String> shuffledLabels(Random random) {
        List<String> shuffled = new ArrayList<>(MOCK_LABELS);
        Collections.shuffle(shuffled, random);
        return shuffled;
    }

    private static int length(String text) {
        return text == null ? 0 : text.length();
    }

    private static String modelOf(MLConfigRequest mlConfig) {
        return mlConfig == null ? null : mlConfig.getModelName();
    }
}
