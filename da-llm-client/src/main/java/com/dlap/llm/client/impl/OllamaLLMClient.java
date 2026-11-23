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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dlap.llm.client.config.MLConfigRequest;
import com.dlap.llm.client.ILLMClient;
import com.dlap.llm.client.model.LLMResponse;
import com.dlap.llm.client.model.LabelSuggestion;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Ollama implementation of the LLM client for local/self-hosted open-source models.
 * Supports models like Llama2, CodeLlama, Mistral, Gemma, DeepSeek, etc.
 *
 * <p>Registered as the {@code ollamaLLMClient} bean by
 * {@link com.dlap.llm.client.LlmClientAutoConfiguration}.
 */
public class OllamaLLMClient implements ILLMClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaLLMClient.class);
    private static final String PROVIDER_NAME = "OLLAMA";
    private static final ObjectMapper objectMapper = new ObjectMapper();

    // Default Ollama endpoint
    private static final String DEFAULT_BASE_URL = "http://localhost:11434";

    // Default prompt template for label suggestion
    private static final String DEFAULT_PROMPT_TEMPLATE = """
        You are a data governance expert. Analyze the following data asset and suggest appropriate labels.
        
        Data Asset Information:
        {metadata_summary}
        
        Based on this information, suggest up to {max_suggestions} relevant data classification labels. Focus on:
        1. Data sensitivity levels (PII, Confidential, Internal, Public)
        2. Business context and domain
        3. Technical characteristics
        4. Regulatory compliance needs
        
        Provide your response as a JSON array with this format:
        [
          {
            "label": "label name",
            "confidence": 0.95,
            "reasoning": "explanation for this label",
            "category": "PII|BUSINESS|TECHNICAL|COMPLIANCE"
          }
        ]
        
        Only return the JSON array, no additional text.
        """;

    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Posts the prompt to {@code /api/generate} with streaming disabled and
     * returns the {@code response} field verbatim.
     */
    @Override
    public String complete(String prompt, MLConfigRequest mlConfig) throws Exception {
        String baseUrl = mlConfig.getBaseUrl() != null ? mlConfig.getBaseUrl() : DEFAULT_BASE_URL;
        String model = mlConfig.getModelName() != null ? mlConfig.getModelName() : "llama2";
        log.info("Ollama completion request: model={}, promptChars={}", model, prompt.length());

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("prompt", prompt);
        requestBody.put("stream", false);

        Map<String, Object> options = new HashMap<>();
        options.put("temperature", getDoubleParameter(mlConfig, "temperature", 0.0));
        options.put("max_tokens", getIntegerParameter(mlConfig, "max_tokens", 1000));
        requestBody.put("options", options);

        String responseJson = makeHttpRequest(baseUrl + "/api/generate", requestBody);
        JsonNode node = objectMapper.readTree(responseJson);
        if (!node.hasNonNull("response")) {
            throw new IllegalStateException("Ollama returned no 'response' field for model " + model);
        }
        return node.path("response").asText();
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
        log.info("Requesting label suggestions from Ollama for content: {}", 
                assetContent.length() > 100 ? assetContent.substring(0, 100) + "..." : assetContent);

        try {
            String baseUrl = mlConfig.getBaseUrl() != null ? mlConfig.getBaseUrl() : DEFAULT_BASE_URL;
            String prompt = buildPrompt(assetContent, mlConfig);
            
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", mlConfig.getModelName() != null ? mlConfig.getModelName() : "llama2");
            requestBody.put("prompt", prompt);
            requestBody.put("stream", false);
            
            // Add optional parameters
            Map<String, Object> options = new HashMap<>();
            options.put("temperature", getDoubleParameter(mlConfig, "temperature", 0.7));
            options.put("top_p", getDoubleParameter(mlConfig, "top_p", 0.9));
            options.put("max_tokens", getIntegerParameter(mlConfig, "max_tokens", 1000));
            requestBody.put("options", options);

            String responseJson = makeHttpRequest(baseUrl + "/api/generate", requestBody);
            return parseOllamaResponse(responseJson);
            
        } catch (Exception e) {
            log.error("Error getting label suggestions from Ollama: {}", e.getMessage(), e);
            throw new Exception("Failed to get suggestions from Ollama: " + e.getMessage(), e);
        }
    }

    @Override
    public List<LLMResponse> batchSuggestLabels(List<String> assetContents, MLConfigRequest mlConfig) throws Exception {
        log.info("Processing batch of {} assets for label suggestions with Ollama", assetContents.size());
        
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
                        .estimatedCost(0.0) // Local models have no API cost
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
                        .estimatedCost(0.0)
                        .build();
                
                responses.add(errorResponse);
            }
        }
        
        log.info("Completed Ollama batch processing in {} ms", System.currentTimeMillis() - startTime);
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
        
        // For Ollama, API key is not required but model name is important
        String modelName = mlConfig.getModelName();
        if (modelName == null || modelName.trim().isEmpty()) {
            log.warn("Model name not specified for Ollama client");
            return false;
        }
        
        return true;
    }

    @Override
    public Map<String, Object> getCapabilities() {
        Map<String, Object> capabilities = new HashMap<>();
        capabilities.put("maxTokens", 4096); // Depends on the model
        capabilities.put("supportsBatch", true);
        capabilities.put("supportsConfidence", true);
        capabilities.put("supportsReasoning", true);
        capabilities.put("supportedModels", Arrays.asList(
                "llama2", "llama2:7b", "llama2:13b", "llama2:70b",
                "codellama", "codellama:7b", "codellama:13b", "codellama:34b",
                "mistral", "mistral:7b", "mistral:instruct",
                "gemma:2b", "gemma:7b",
                "deepseek-coder", "deepseek-coder:6.7b", "deepseek-coder:33b"
        ));
        capabilities.put("costPerToken", 0.0); // Local models have no API cost
        capabilities.put("requiresLocalSetup", true);
        return capabilities;
    }

    @Override
    public boolean testConnection(MLConfigRequest mlConfig) {
        try {
            if (!validateConfiguration(mlConfig)) {
                return false;
            }
            
            String baseUrl = mlConfig.getBaseUrl() != null ? mlConfig.getBaseUrl() : DEFAULT_BASE_URL;
            
            // Test with a simple request
            Map<String, Object> testRequest = new HashMap<>();
            testRequest.put("model", mlConfig.getModelName());
            testRequest.put("prompt", "Test connection. Respond with OK.");
            testRequest.put("stream", false);
            
            Map<String, Object> options = new HashMap<>();
            options.put("max_tokens", 10);
            testRequest.put("options", options);
            
            String response = makeHttpRequest(baseUrl + "/api/generate", testRequest);
            return response != null && !response.trim().isEmpty();
            
        } catch (Exception e) {
            log.error("Connection test failed for Ollama: {}", e.getMessage(), e);
            return false;
        }
    }

    private String makeHttpRequest(String url, Map<String, Object> requestBody) throws Exception {
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpPost httpPost = new HttpPost(url);
            httpPost.setHeader("Content-Type", "application/json");
            
            String jsonBody = objectMapper.writeValueAsString(requestBody);
            httpPost.setEntity(new StringEntity(jsonBody, StandardCharsets.UTF_8));
            
            return httpClient.execute(httpPost, response -> {
                if (response.getCode() == 200) {
                    return new String(response.getEntity().getContent().readAllBytes(), StandardCharsets.UTF_8);
                } else {
                    throw new IOException("HTTP " + response.getCode() + ": " + response.getReasonPhrase());
                }
            });
            
        } catch (Exception e) {
            log.error("HTTP request failed: {}", e.getMessage(), e);
            throw e;
        }
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

    private List<LabelSuggestion> parseOllamaResponse(String responseJson) {
        try {
            JsonNode responseNode = objectMapper.readTree(responseJson);
            String responseText = responseNode.path("response").asText();
            
            return parseLabelsFromResponse(responseText);
            
        } catch (JsonProcessingException e) {
            log.error("Failed to parse Ollama response JSON: {}", e.getMessage(), e);
            return new ArrayList<>();
        }
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
                    .confidence(0.6) // Default confidence for text parsing from local models
                    .reasoning("Extracted from Ollama text response")
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