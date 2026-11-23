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

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dlap.llm.client.config.MLConfigRequest;

/**
 * Factory for creating and managing LLM clients.
 * Handles provider selection and client lifecycle.
 *
 * <p>Registered as the {@code llmClientFactory} bean by
 * {@link LlmClientAutoConfiguration}.
 */
public class LLMClientFactory {

    private static final Logger log = LoggerFactory.getLogger(LLMClientFactory.class);
    
    private final Map<String, ILLMClient> clients;

    /**
     * Registers every {@link ILLMClient} on the classpath, keyed by upper-case
     * provider name.
     *
     * @param llmClients All LLM client beans available in the context.
     */
    public LLMClientFactory(List<ILLMClient> llmClients) {
        this.clients = new HashMap<>();
        
        // Register all available clients
        for (ILLMClient client : llmClients) {
            clients.put(client.getProviderName().toUpperCase(), client);
            log.info("Registered LLM client: {}", client.getProviderName());
        }
    }

    /**
     * Gets the appropriate LLM client based on the ML configuration.
     *
     * @param mlConfig The ML configuration specifying the provider
     * @return The LLM client for the specified provider
     * @throws IllegalArgumentException if the provider is not supported
     */
    public ILLMClient getClient(MLConfigRequest mlConfig) {
        if (mlConfig == null || mlConfig.getProviderType() == null) {
            throw new IllegalArgumentException("ML configuration and provider type cannot be null");
        }

        String providerType = mlConfig.getProviderType().toUpperCase();
        ILLMClient client = clients.get(providerType);
        
        if (client == null) {
            throw new IllegalArgumentException("Unsupported LLM provider: " + providerType);
        }

        return client;
    }

    /**
     * Gets all available LLM clients.
     *
     * @return Collection of all registered LLM clients
     */
    public Collection<ILLMClient> getAllClients() {
        return clients.values();
    }

    /**
     * Gets the names of all supported providers.
     *
     * @return Set of provider names
     */
    public Set<String> getSupportedProviders() {
        return clients.keySet();
    }

    /**
     * Validates if a provider is supported.
     *
     * @param providerType The provider type to check
     * @return true if supported, false otherwise
     */
    public boolean isProviderSupported(String providerType) {
        if (providerType == null) {
            return false;
        }
        return clients.containsKey(providerType.toUpperCase());
    }

    /**
     * Gets capabilities for all providers.
     *
     * @return Map of provider names to their capabilities
     */
    public Map<String, Map<String, Object>> getAllProviderCapabilities() {
        Map<String, Map<String, Object>> allCapabilities = new HashMap<>();
        
        for (Map.Entry<String, ILLMClient> entry : clients.entrySet()) {
            allCapabilities.put(entry.getKey(), entry.getValue().getCapabilities());
        }
        
        return allCapabilities;
    }

    /**
     * Tests connection for a specific configuration.
     *
     * @param mlConfig The ML configuration to test
     * @return true if the connection test passes, false otherwise
     */
    public boolean testConnection(MLConfigRequest mlConfig) {
        try {
            ILLMClient client = getClient(mlConfig);
            return client.testConnection(mlConfig);
        } catch (Exception e) {
            log.error("Connection test failed: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Validates a configuration for a specific provider.
     *
     * @param mlConfig The ML configuration to validate
     * @return true if the configuration is valid, false otherwise
     */
    public boolean validateConfiguration(MLConfigRequest mlConfig) {
        try {
            ILLMClient client = getClient(mlConfig);
            return client.validateConfiguration(mlConfig);
        } catch (Exception e) {
            log.error("Configuration validation failed: {}", e.getMessage(), e);
            return false;
        }
    }
} 