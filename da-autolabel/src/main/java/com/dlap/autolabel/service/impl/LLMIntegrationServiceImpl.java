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

import com.dlap.llm.client.config.MLConfigRequest;
import com.dlap.autolabel.exception.LlmProviderNotConfiguredException;
import com.dlap.llm.client.ILLMClient;
import com.dlap.autolabel.service.ILLMIntegrationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves the correct {@link ILLMClient} for a labeling request from the
 * injected map of LLM client beans.
 *
 * <p>Historically this class silently substituted {@code MockLLMClient} when
 * the ML configuration was missing or referenced an unknown provider, which
 * masked real misconfiguration and shipped fake label suggestions to callers.
 * That behavior is now gated behind the Spring property
 * {@code dlap.autolabel.allow-mock} which defaults to {@code false}. With the
 * flag off a misconfigured provider throws
 * {@link LlmProviderNotConfiguredException}; with the flag on we fall back to
 * the mock client and log a WARN every time so operators can see it.
 *
 * <p>The deterministic {@code rules} provider ({@code RuleBasedLabelClient},
 * bean {@code rulesLLMClient}) is a real provider, not a fallback: selecting it
 * explicitly with {@code providerType=rules} needs no flag. Its labels come from
 * named keyword and pattern rules over the asset's name, path and metadata, and
 * it is the offline option to prefer over the mock, whose labels are unrelated to
 * the asset. Nothing here ever falls back to {@code rules} silently.
 */
@Service
@Slf4j
public class LLMIntegrationServiceImpl implements ILLMIntegrationService {

    static final String MOCK_CLIENT_BEAN_NAME = "mockLLMClient";

    private static final String NOT_CONFIGURED_MESSAGE =
            "No LLM provider configured. Set ml_config.provider_type and credentials, "
                    + "use provider_type=rules for deterministic keyword and pattern labels without a model, "
                    + "or set dlap.autolabel.allow-mock=true for the asset-independent mock.";

    private final Map<String, ILLMClient> llmClients; // Injected map of all ILLMClient beans

    @Value("${dlap.autolabel.allow-mock:false}")
    private boolean allowMock;

    @Autowired
    public LLMIntegrationServiceImpl(Map<String, ILLMClient> llmClients) {
        this.llmClients = llmClients;
        log.info("Initialized LLMIntegrationService with available clients: {}", llmClients.keySet());
    }

    @Override
    public List<String> suggestLabelsForAsset(String assetContent, MLConfigRequest mlConfig) throws Exception {
        if (mlConfig == null || mlConfig.getProviderType() == null) {
            log.error("MLConfig or ProviderType is null. Cannot determine LLM client.");
            ILLMClient mockClient = maybeResolveMockClient();
            if (mockClient != null) {
                log.warn("Using MockLLMClient because dlap.autolabel.allow-mock=true");
                return mockClient.suggestLabels(assetContent, mlConfig != null ? mlConfig : new MLConfigRequest());
            }
            throw new LlmProviderNotConfiguredException(NOT_CONFIGURED_MESSAGE);
        }

        // Construct bean name based on provider type, e.g., "openaiLLMClient", "ollamaLLMClient".
        // This assumes ILLMClient beans are named like "[providerType]LLMClient".
        String clientBeanName = mlConfig.getProviderType().toLowerCase() + "LLMClient";
        ILLMClient client = llmClients.get(clientBeanName);

        if (client == null) {
            // Fallback: try finding client by provider name if naming convention isn't strict
            Optional<ILLMClient> foundClient = llmClients.values().stream()
                    .filter(c -> c.getProviderName().equalsIgnoreCase(mlConfig.getProviderType()))
                    .findFirst();
            if (foundClient.isPresent()) {
                client = foundClient.get();
                log.warn("LLM client for provider '{}' found by provider name, not bean naming convention.", mlConfig.getProviderType());
            } else {
                log.error("No LLM client found for provider type: {}. Available clients: {}. Attempted bean name: {}",
                        mlConfig.getProviderType(), llmClients.keySet(), clientBeanName);
                ILLMClient mockClient = maybeResolveMockClient();
                if (mockClient != null) {
                    log.warn("Using MockLLMClient because dlap.autolabel.allow-mock=true (requested provider '{}' not registered)",
                            mlConfig.getProviderType());
                    return mockClient.suggestLabels(assetContent, mlConfig);
                }
                throw new LlmProviderNotConfiguredException(
                        "No LLM client configured for provider '" + mlConfig.getProviderType() + "'. "
                                + NOT_CONFIGURED_MESSAGE);
            }
        }

        log.info("Using LLM client: {} (provider {})", client.getClass().getSimpleName(), client.getProviderName());
        return client.suggestLabels(assetContent, mlConfig);
    }

    /**
     * Returns the mock client only if the opt-in flag is enabled AND the bean
     * is actually registered. Returns {@code null} otherwise, the caller is
     * expected to raise {@link LlmProviderNotConfiguredException}.
     */
    private ILLMClient maybeResolveMockClient() {
        if (!allowMock) {
            return null;
        }
        ILLMClient mockClient = llmClients.get(MOCK_CLIENT_BEAN_NAME);
        if (mockClient == null) {
            log.error("dlap.autolabel.allow-mock=true but no '{}' bean is registered.", MOCK_CLIENT_BEAN_NAME);
        }
        return mockClient;
    }
}
