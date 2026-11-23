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

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import com.dlap.llm.client.config.LlmProperties;
import com.dlap.llm.client.impl.MockLLMClient;
import com.dlap.llm.client.impl.OllamaLLMClient;
import com.dlap.llm.client.impl.OpenAiLLMClient;
import com.dlap.llm.client.impl.RuleBasedLabelClient;

/**
 * Registers the shared LLM provider abstraction in any DLAP service that puts
 * {@code da-llm-client} on its classpath.
 *
 * <p>Beans are declared explicitly rather than component-scanned: these classes
 * now live outside every service's {@code @SpringBootApplication} base package,
 * and explicit {@code @Bean} methods pin the bean names that callers already
 * resolve by name ({@code openAiLLMClient}, {@code ollamaLLMClient},
 * {@code rulesLLMClient}, {@code mockLLMClient}) instead of leaving them to
 * scan-time derivation.
 *
 * <p>Every bean is {@link ConditionalOnMissingBean}, so a service can still
 * override any provider with its own implementation.
 */
@AutoConfiguration
@EnableConfigurationProperties(LlmProperties.class)
public class LlmClientAutoConfiguration {

    /**
     * OpenAI provider client.
     *
     * @return The {@code openAiLLMClient} bean.
     */
    @Bean
    @ConditionalOnMissingBean(OpenAiLLMClient.class)
    public OpenAiLLMClient openAiLLMClient() {
        return new OpenAiLLMClient();
    }

    /**
     * Ollama provider client for local / self-hosted models.
     *
     * @return The {@code ollamaLLMClient} bean.
     */
    @Bean
    @ConditionalOnMissingBean(OllamaLLMClient.class)
    public OllamaLLMClient ollamaLLMClient() {
        return new OllamaLLMClient();
    }

    /**
     * Deterministic rule-based label provider ({@code rules}). Needs no model,
     * network or credentials, and stamps its assignments as rule matches. Always
     * available, so it is the honest offline choice for demos and air-gapped
     * installs. Refuses free-form completion.
     *
     * @return The {@code rulesLLMClient} bean.
     */
    @Bean
    @ConditionalOnMissingBean(RuleBasedLabelClient.class)
    public RuleBasedLabelClient rulesLLMClient() {
        return new RuleBasedLabelClient();
    }

    /**
     * Offline mock client. Present in the context but only usable by callers
     * that explicitly opt in; it refuses free-form completion outright. Its
     * labels are deterministic but unrelated to the asset; use
     * {@link #rulesLLMClient()} for labels grounded in the asset.
     *
     * @return The {@code mockLLMClient} bean.
     */
    @Bean
    @ConditionalOnMissingBean(MockLLMClient.class)
    public MockLLMClient mockLLMClient() {
        return new MockLLMClient();
    }

    /**
     * Provider registry keyed by provider name.
     *
     * @param clients All registered {@link ILLMClient} beans.
     * @return The {@code llmClientFactory} bean.
     */
    @Bean
    @ConditionalOnMissingBean(LLMClientFactory.class)
    public LLMClientFactory llmClientFactory(List<ILLMClient> clients) {
        return new LLMClientFactory(clients);
    }
}
