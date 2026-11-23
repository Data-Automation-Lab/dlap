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

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dlap.llm.client.config.MLConfigRequest;
import com.dlap.llm.client.ILLMClient;
import com.dlap.llm.client.model.LLMResponse;
import com.dlap.llm.client.model.LabelSuggestion;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.theokanning.openai.completion.chat.ChatCompletionRequest;
import com.theokanning.openai.completion.chat.ChatCompletionResult;
import com.theokanning.openai.completion.chat.ChatMessage;
import com.theokanning.openai.completion.chat.ChatMessageRole;
import com.theokanning.openai.service.OpenAiService;

/**
 * OpenAI GPT implementation of the LLM client.
 * Supports GPT-3.5-turbo, GPT-4, and other OpenAI models.
 *
 * <p>Registered as the {@code openAiLLMClient} bean by
 * {@link com.dlap.llm.client.LlmClientAutoConfiguration}.
 */
public class OpenAiLLMClient implements ILLMClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiLLMClient.class);
    private static final String PROVIDER_NAME = "OPENAI";
    private static final ObjectMapper objectMapper = new ObjectMapper();

    // Default prompt template for label suggestion
    private static final String DEFAULT_PROMPT_TEMPLATE = """
        You are a data governance AI assistant. Based on the following asset metadata, suggest relevant data labels.
        
        Asset Information:
        {metadata_summary}
        
        Please suggest up to {max_suggestions} relevant labels for this data asset. Focus on:
        - Data sensitivity (PII, Confidential, Public, etc.)
        - Business purpose and domain
        - Technical characteristics
        - Compliance requirements
        
        Respond with a JSON array of objects, each containing:
        - "label": the suggested label text
        - "confidence": confidence score (0.0 to 1.0)
        - "reasoning": brief explanation for the suggestion
        - "category": category type (PII, BUSINESS, TECHNICAL, COMPLIANCE)
        
        Example response:
        [
          {"label": "PII", "confidence": 0.95, "reasoning": "Contains personal identifiers", "category": "PII"},
          {"label": "Customer Data", "confidence": 0.87, "reasoning": "Related to customer information", "category": "BUSINESS"}
        ]
        """;

    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Sends the prompt as a single user message and returns the first
     * choice's content verbatim. Never logs the API key.
     */
    @Override
    public String complete(String prompt, MLConfigRequest mlConfig) throws Exception {
        String model = mlConfig.getModelName() != null ? mlConfig.getModelName() : "gpt-3.5-turbo";
        log.info("OpenAI completion request: model={}, promptChars={}", model, prompt.length());

        OpenAiService service = createOpenAiService(mlConfig);
        ChatCompletionRequest request = ChatCompletionRequest.builder()
                .model(model)
                .messages(List.of(new ChatMessage(ChatMessageRole.USER.value(), prompt)))
                .temperature(getDoubleParameter(mlConfig, "temperature", 0.0))
                .maxTokens(getIntegerParameter(mlConfig, "maxTokens", 1000))
                .build();

        ChatCompletionResult result = service.createChatCompletion(request);
        if (result.getChoices() == null || result.getChoices().isEmpty()) {
            throw new IllegalStateException("OpenAI returned no completion choices for model " + model);
        }
        return result.getChoices().get(0).getMessage().getContent();
    }

    @Override
    public List<String> suggestLabels(String assetContent, MLConfigRequest mlConfig) throws Exception {
        List<LabelSuggestion> suggestions = suggestLabelsWithConfidence(assetContent, mlConfig);
        return suggestions.stream()
                .map(LabelSuggestion::getLabel)
                .collect(Collectors.toList());
    }

    @Override
    public List<LabelSuggestion> suggestLabelsWithConfidence(String assetContent, MLConfigRequest mlConfig) throws Exception {
        log.info("Requesting label suggestions from OpenAI for content: {}", 
                assetContent.length() > 100 ? assetContent.substring(0, 100) + "..." : assetContent);

        try {
            OpenAiService service = createOpenAiService(mlConfig);
            String prompt = buildPrompt(assetContent, mlConfig);
            
            ChatCompletionRequest request = ChatCompletionRequest.builder()
                    .model(mlConfig.getModelName() != null ? mlConfig.getModelName() : "gpt-3.5-turbo")
                    .messages(List.of(new ChatMessage(ChatMessageRole.USER.value(), prompt)))
                    .temperature(getDoubleParameter(mlConfig, "temperature", 0.7))
                    .maxTokens(getIntegerParameter(mlConfig, "maxTokens", 1000))
                    .build();

            ChatCompletionResult result = service.createChatCompletion(request);
            
            if (result.getChoices() != null && !result.getChoices().isEmpty()) {
                String responseContent = result.getChoices().get(0).getMessage().getContent();
                return parseLabelsFromResponse(responseContent);
            }
            
            log.warn("No choices returned from OpenAI response");
            return new ArrayList<>();
            
        } catch (Exception e) {
            log.error("Error getting label suggestions from OpenAI: {}", e.getMessage(), e);
            throw new Exception("Failed to get suggestions from OpenAI: " + e.getMessage(), e);
        }
    }

    @Override
    public List<LLMResponse> batchSuggestLabels(List<String> assetContents, MLConfigRequest mlConfig) throws Exception {
        log.info("Processing batch of {} assets for label suggestions", assetContents.size());
        
        List<LLMResponse> responses = new ArrayList<>();
        long startTime = System.currentTimeMillis();
        
        for (String content : assetContents) {
            long itemStartTime = System.currentTimeMillis();
            try {
                List<LabelSuggestion> suggestions = suggestLabelsWithConfidence(content, mlConfig);
                long processingTime = System.currentTimeMillis() - itemStartTime;
                
                LLMResponse response = LLMResponse.builder()
                        .inputContent(content)
                        .suggestions(suggestions)
                        .processingTimeMs(processingTime)
                        .timestamp(LocalDateTime.now())
                        .provider(PROVIDER_NAME)
                        .modelName(mlConfig.getModelName())
                        .successful(true)
                        .overallConfidence(calculateOverallConfidence(suggestions))
                        .build();
                
                responses.add(response);
                
            } catch (Exception e) {
                log.error("Error processing asset in batch: {}", e.getMessage(), e);
                
                LLMResponse errorResponse = LLMResponse.builder()
                        .inputContent(content)
                        .suggestions(new ArrayList<>())
                        .processingTimeMs(System.currentTimeMillis() - itemStartTime)
                        .timestamp(LocalDateTime.now())
                        .provider(PROVIDER_NAME)
                        .modelName(mlConfig.getModelName())
                        .successful(false)
                        .errorMessage(e.getMessage())
                        .build();
                
                responses.add(errorResponse);
            }
        }
        
        log.info("Completed batch processing in {} ms", System.currentTimeMillis() - startTime);
        return responses;
    }

    @Override
    public boolean validateConfiguration(MLConfigRequest mlConfig) {
        if (mlConfig == null) {
            return false;
        }
        
        // Check provider type
        if (!PROVIDER_NAME.equalsIgnoreCase(mlConfig.getProviderType())) {
            return false;
        }
        
        // Check required fields
        if (mlConfig.getApiKey() == null || mlConfig.getApiKey().trim().isEmpty()) {
            return false;
        }
        
        // Validate model name if provided
        String modelName = mlConfig.getModelName();
        if (modelName != null && !isValidOpenAiModel(modelName)) {
            log.warn("Unknown OpenAI model: {}", modelName);
            // Still return true as new models might be added
        }
        
        return true;
    }

    @Override
    public Map<String, Object> getCapabilities() {
        Map<String, Object> capabilities = new HashMap<>();
        capabilities.put("maxTokens", 4096);
        capabilities.put("supportsBatch", true);
        capabilities.put("supportsConfidence", true);
        capabilities.put("supportsReasoning", true);
        capabilities.put("supportedModels", Arrays.asList(
                "gpt-3.5-turbo", "gpt-3.5-turbo-16k", "gpt-4", "gpt-4-turbo", "gpt-4o"
        ));
        capabilities.put("costPerToken", 0.0000015); // Approximate for GPT-3.5-turbo
        return capabilities;
    }

    @Override
    public boolean testConnection(MLConfigRequest mlConfig) {
        try {
            if (!validateConfiguration(mlConfig)) {
                return false;
            }
            
            OpenAiService service = createOpenAiService(mlConfig);
            
            // Simple test request
            ChatCompletionRequest testRequest = ChatCompletionRequest.builder()
                    .model(mlConfig.getModelName() != null ? mlConfig.getModelName() : "gpt-3.5-turbo")
                    .messages(List.of(new ChatMessage(ChatMessageRole.USER.value(), "Test connection. Respond with 'OK'.")))
                    .maxTokens(10)
                    .build();
            
            ChatCompletionResult result = service.createChatCompletion(testRequest);
            return result.getChoices() != null && !result.getChoices().isEmpty();
            
        } catch (Exception e) {
            log.error("Connection test failed for OpenAI: {}", e.getMessage(), e);
            return false;
        }
    }

    private OpenAiService createOpenAiService(MLConfigRequest mlConfig) {
        Duration timeout = Duration.ofSeconds(getIntegerParameter(mlConfig, "timeoutSeconds", 60));
        return new OpenAiService(mlConfig.getApiKey(), timeout);
    }

    private String buildPrompt(String assetContent, MLConfigRequest mlConfig) {
        String template = mlConfig.getPromptTemplate() != null ? 
                mlConfig.getPromptTemplate() : DEFAULT_PROMPT_TEMPLATE;
        
        Integer maxSuggestions = mlConfig.getSuggestionConfig() != null ? 
                mlConfig.getSuggestionConfig().getMaxSuggestions() : 5;
        
        return template
                .replace("{metadata_summary}", assetContent)
                .replace("{max_suggestions}", String.valueOf(maxSuggestions));
    }

    private List<LabelSuggestion> parseLabelsFromResponse(String response) {
        List<LabelSuggestion> suggestions = new ArrayList<>();
        
        try {
            // Try to parse as JSON first
            JsonNode jsonArray = objectMapper.readTree(response);
            if (jsonArray.isArray()) {
                for (JsonNode item : jsonArray) {
                    LabelSuggestion suggestion = LabelSuggestion.builder()
                            .label(item.path("label").asText())
                            .confidence(item.path("confidence").asDouble(0.5))
                            .reasoning(item.path("reasoning").asText())
                            .category(item.path("category").asText())
                            .build();
                    suggestions.add(suggestion);
                }
                return suggestions;
            }
        } catch (JsonProcessingException e) {
            log.debug("Response is not valid JSON, trying text parsing: {}", e.getMessage());
        }
        
        // Fallback: parse as plain text
        return parseLabelsFromText(response);
    }

    private List<LabelSuggestion> parseLabelsFromText(String response) {
        List<LabelSuggestion> suggestions = new ArrayList<>();
        
        // Look for patterns like "- Label" or "1. Label" or just lines with labels
        Pattern labelPattern = Pattern.compile("(?:[-*]|\\d+\\.)\\s*([A-Za-z_][A-Za-z0-9_\\s]*)", Pattern.MULTILINE);
        Matcher matcher = labelPattern.matcher(response);
        
        while (matcher.find()) {
            String label = matcher.group(1).trim();
            suggestions.add(LabelSuggestion.builder()
                    .label(label)
                    .confidence(0.7) // Default confidence for text parsing
                    .reasoning("Extracted from text response")
                    .category("UNKNOWN")
                    .build());
        }
        
        return suggestions;
    }

    private double calculateOverallConfidence(List<LabelSuggestion> suggestions) {
        if (suggestions.isEmpty()) {
            return 0.0;
        }
        
        return suggestions.stream()
                .mapToDouble(s -> s.getConfidence() != null ? s.getConfidence() : 0.5)
                .average()
                .orElse(0.0);
    }

    private boolean isValidOpenAiModel(String modelName) {
        Set<String> validModels = Set.of(
                "gpt-3.5-turbo", "gpt-3.5-turbo-16k", "gpt-3.5-turbo-0613", "gpt-3.5-turbo-16k-0613",
                "gpt-4", "gpt-4-0613", "gpt-4-32k", "gpt-4-32k-0613", "gpt-4-turbo", "gpt-4o"
        );
        return validModels.contains(modelName);
    }

    private Double getDoubleParameter(MLConfigRequest mlConfig, String key, Double defaultValue) {
        if (mlConfig.getAdditionalParameters() != null && mlConfig.getAdditionalParameters().containsKey(key)) {
            Object value = mlConfig.getAdditionalParameters().get(key);
            if (value instanceof Number) {
                return ((Number) value).doubleValue();
            }
        }
        return defaultValue;
    }

    private Integer getIntegerParameter(MLConfigRequest mlConfig, String key, Integer defaultValue) {
        if (mlConfig.getAdditionalParameters() != null && mlConfig.getAdditionalParameters().containsKey(key)) {
            Object value = mlConfig.getAdditionalParameters().get(key);
            if (value instanceof Number) {
                return ((Number) value).intValue();
            }
        }
        return defaultValue;
    }
} 