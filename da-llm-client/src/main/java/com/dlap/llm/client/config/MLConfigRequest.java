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

package com.dlap.llm.client.config;

import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.ToString;

/**
 * Represents the request to configure Machine Learning (ML) settings for auto-labeling.
 * This DTO is designed to be flexible to accommodate various LLM providers and their configurations.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MLConfigRequest {

    /** Largest prompt template accepted (characters). */
    public static final int MAX_PROMPT_TEMPLATE_LENGTH = 20_000;

    /** Most additional provider parameters accepted. */
    public static final int MAX_ADDITIONAL_PARAMETERS = 50;

    /**
     * Specifies the ML provider to be used.
     * Examples: "openai", "ollama".
     */
    @Size(max = 64)
    private String providerType;

    /**
     * The API key for the selected ML provider, if applicable.
     * This should be stored securely. It is left out of {@code toString()} so a log line that prints
     * the request never prints the key.
     */
    @ToString.Exclude
    @Size(max = 4096)
    private String apiKey;

    /**
     * The base URL for the ML provider's API.
     * Especially relevant for self-hosted models like Ollama or enterprise Azure OpenAI endpoints.
     */
    @Size(max = 2048)
    @Pattern(regexp = "(https?://[!-~]+)?", message = "must be an http or https URL")
    private String baseUrl;

    /**
     * The specific model name to be used from the provider.
     * Examples: "gpt-3.5-turbo", "llama2".
     */
    @Size(max = 256)
    private String modelName;

    /**
     * The prompt template to be used for generating labels.
     * This template can include placeholders for metadata fields.
     * Example: "Based on the following metadata, suggest relevant labels: {metadata_summary}"
     */
    @Size(max = MAX_PROMPT_TEMPLATE_LENGTH)
    private String promptTemplate;
    
    /**
     * A map for any additional provider-specific parameters.
     * This allows for extending configuration without changing the DTO structure.
     * Keys could be "temperature", "maxTokens", "deploymentName" (for Azure OpenAI), etc.
     */
    @Size(max = MAX_ADDITIONAL_PARAMETERS)
    private Map<String, Object> additionalParameters;

    /**
     * Configuration for how many labels to suggest.
     */
    @Valid
    private SuggestionConfig suggestionConfig;

    /**
     * Configuration for confidence thresholds.
     */
    @Valid
    private ConfidenceConfig confidenceConfig;


    /**
     * Nested DTO for label suggestion configuration.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SuggestionConfig {
        /**
         * Maximum number of labels to suggest.
         */
        @Min(1)
        @Max(100)
        private Integer maxSuggestions;

        /**
         * Whether to include confidence scores with suggestions.
         */
        private Boolean includeConfidenceScores;
    }

    /**
     * Nested DTO for confidence threshold configuration.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ConfidenceConfig {
        /**
         * Minimum confidence score for a suggestion to be considered.
         * (Value between 0.0 and 1.0)
         */
        @DecimalMin("0.0")
        @DecimalMax("1.0")
        private Double minThreshold;

        /**
         * Action to take if confidence is below the minimum threshold.
         * Examples: "REJECT", "FLAG_FOR_REVIEW"
         */
        @Size(max = 64)
        private String actionBelowThreshold;
    }
} 