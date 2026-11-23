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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

/**
 * Binds the platform-wide {@code llm.*} properties that were already declared
 * in {@code da-autolabel/src/main/resources/application.properties} but had no
 * binder. Key names are unchanged, so existing deployments keep working:
 *
 * <pre>
 * llm.provider              = openai | ollama   (new; blank means "no provider")
 * llm.openai.api-key        = ${OPENAI_API_KEY:}
 * llm.openai.model          = ${OPENAI_MODEL:gpt-3.5-turbo}
 * llm.openai.max-tokens     = ${OPENAI_MAX_TOKENS:1000}
 * llm.openai.temperature    = ${OPENAI_TEMPERATURE:0.3}
 * llm.ollama.base-url       = ${OLLAMA_BASE_URL:http://localhost:11434}
 * llm.ollama.model          = ${OLLAMA_MODEL:llama2}
 * llm.ollama.timeout        = ${OLLAMA_TIMEOUT:30000}
 * </pre>
 *
 * <p>This is the configuration source for services that have no per-tenant ML
 * configuration table of their own. {@code da-autolabel} continues to resolve
 * its {@link MLConfigRequest} from the database and is unaffected.
 *
 * <p>Nothing here is ever logged; {@link #resolve()} hands the secret straight
 * to the provider client.
 */
@Data
@ConfigurationProperties(prefix = "llm")
public class LlmProperties {

    /** Selected provider: {@code openai} or {@code ollama}. Blank disables LLM features. */
    private String provider;

    private final OpenAi openai = new OpenAi();
    private final Ollama ollama = new Ollama();

    /** OpenAI-specific settings, bound from {@code llm.openai.*}. */
    @Data
    public static class OpenAi {
        /** API key. Supplied via {@code OPENAI_API_KEY}; never logged. */
        private String apiKey;
        /** Model name, e.g. {@code gpt-4o}. */
        private String model = "gpt-3.5-turbo";
        /** Upper bound on completion tokens. */
        private Integer maxTokens = 1000;
        /** Sampling temperature. */
        private Double temperature = 0.3;
    }

    /** Ollama-specific settings, bound from {@code llm.ollama.*}. */
    @Data
    public static class Ollama {
        /** Base URL of the Ollama daemon. */
        private String baseUrl = "http://localhost:11434";
        /** Model name, e.g. {@code llama3}. */
        private String model = "llama2";
        /** Request timeout in milliseconds. */
        private Integer timeout = 30000;
    }

    /**
     * Resolves these properties into an {@link MLConfigRequest} for the selected
     * provider.
     *
     * <p>Returns {@link Optional#empty()} when no provider is selected, when the
     * selected provider is unknown, or when the selected provider is missing a
     * credential or model it cannot work without. Callers must treat an empty
     * result as "LLM unavailable" and must not substitute defaults.
     *
     * @return The resolved provider configuration, or empty when unusable.
     */
    public Optional<MLConfigRequest> resolve() {
        if (provider == null || provider.isBlank()) {
            return Optional.empty();
        }
        String selected = provider.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (selected) {
            case "OPENAI" -> resolveOpenAi();
            case "OLLAMA" -> resolveOllama();
            default -> Optional.empty();
        };
    }

    private Optional<MLConfigRequest> resolveOpenAi() {
        if (openai.getApiKey() == null || openai.getApiKey().isBlank()) {
            return Optional.empty();
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("maxTokens", openai.getMaxTokens());
        params.put("temperature", openai.getTemperature());
        return Optional.of(MLConfigRequest.builder()
                .providerType("OPENAI")
                .apiKey(openai.getApiKey())
                .modelName(openai.getModel())
                .additionalParameters(params)
                .build());
    }

    private Optional<MLConfigRequest> resolveOllama() {
        if (ollama.getModel() == null || ollama.getModel().isBlank()) {
            return Optional.empty();
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("max_tokens", 1000);
        params.put("timeoutMillis", ollama.getTimeout());
        return Optional.of(MLConfigRequest.builder()
                .providerType("OLLAMA")
                .baseUrl(ollama.getBaseUrl())
                .modelName(ollama.getModel())
                .additionalParameters(params)
                .build());
    }
}
