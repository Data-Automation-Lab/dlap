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

package com.dlap.llm.client;

import java.util.List;
import java.util.Map;

import com.dlap.llm.client.config.MLConfigRequest;
import com.dlap.llm.client.model.LLMResponse;
import com.dlap.llm.client.model.LabelSuggestion;

/**
 * Interface for a client that interacts with a Large Language Model (LLM).
 * Provides abstraction for different LLM providers (OpenAI, Ollama, etc.).
 *
 * <p>Two families of operations live here. The {@code suggestLabels*} family is
 * the Auto-Label pipeline's contract. {@link #complete(String, MLConfigRequest)}
 * is the provider-neutral free-form completion seam used by callers that own
 * their own prompt and response grammar (for example the policy engine's
 * natural-language policy drafting).
 */
public interface ILLMClient {

    /**
     * Gets the provider name for this LLM client (e.g., "OPENAI", "OLLAMA").
     *
     * @return The provider name.
     */
    String getProviderName();

    /**
     * Sends a single free-form prompt to the provider and returns the raw
     * completion text, with no parsing or interpretation applied.
     *
     * <p>Callers that need structured output are responsible for their own
     * prompt contract and for validating whatever comes back. Implementations
     * must never substitute invented content when the provider fails: throw
     * instead, so the caller can surface the failure.
     *
     * @param prompt The complete prompt to send.
     * @param mlConfig The ML configuration selecting model, credentials and
     *     provider-specific parameters (e.g. {@code temperature}, {@code maxTokens}).
     * @return The raw completion text returned by the provider.
     * @throws UnsupportedOperationException if the provider has no free-form
     *     completion capability.
     * @throws Exception if there is an error during LLM interaction.
     */
    String complete(String prompt, MLConfigRequest mlConfig) throws Exception;

    /**
     * Suggests a list of labels for the given asset content/metadata using the LLM.
     *
     * @param assetContent A string representation of the asset content or relevant metadata.
     * @param mlConfig The ML configuration to use, containing model details, prompt templates, etc.
     * @return A list of suggested label strings.
     * @throws Exception if there is an error during LLM interaction.
     */
    List<String> suggestLabels(String assetContent, MLConfigRequest mlConfig) throws Exception;

    /**
     * Suggests labels with confidence scores for better decision making.
     *
     * @param assetContent A string representation of the asset content or relevant metadata.
     * @param mlConfig The ML configuration to use, containing model details, prompt templates, etc.
     * @return A list of label suggestions with confidence scores.
     * @throws Exception if there is an error during LLM interaction.
     */
    List<LabelSuggestion> suggestLabelsWithConfidence(String assetContent, MLConfigRequest mlConfig) throws Exception;

    /**
     * Processes multiple assets in batch for efficiency.
     *
     * @param assetContents List of asset content strings to process.
     * @param mlConfig The ML configuration to use.
     * @return List of LLM responses corresponding to each input.
     * @throws Exception if there is an error during LLM interaction.
     */
    List<LLMResponse> batchSuggestLabels(List<String> assetContents, MLConfigRequest mlConfig) throws Exception;

    /**
     * Validates that the ML configuration is compatible with this LLM client.
     *
     * @param mlConfig The ML configuration to validate.
     * @return true if the configuration is valid for this client, false otherwise.
     */
    boolean validateConfiguration(MLConfigRequest mlConfig);

    /**
     * Gets the capabilities of this LLM client.
     *
     * @return A map of capabilities (e.g., "maxTokens", "supportsBatch", "supportsConfidence").
     */
    Map<String, Object> getCapabilities();

    /**
     * Tests the connection to the LLM provider with the given configuration.
     *
     * @param mlConfig The ML configuration to test.
     * @return true if the connection is successful, false otherwise.
     */
    boolean testConnection(MLConfigRequest mlConfig);
} 