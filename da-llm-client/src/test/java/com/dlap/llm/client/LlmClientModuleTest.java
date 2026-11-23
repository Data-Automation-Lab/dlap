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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.dlap.llm.client.config.LlmProperties;
import com.dlap.llm.client.config.MLConfigRequest;
import com.dlap.llm.client.impl.MockLLMClient;
import com.dlap.llm.client.impl.OllamaLLMClient;
import com.dlap.llm.client.impl.OpenAiLLMClient;
import com.dlap.llm.client.impl.RuleBasedLabelClient;
import com.dlap.llm.client.rules.DefaultLabelRules;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Contract tests for the shared LLM client module (ADR-0044): provider
 * registry lookup, property-driven provider resolution, the mock client's
 * refusal to fabricate free-form completions, and registration of the
 * deterministic {@code rules} provider.
 */
class LlmClientModuleTest {

    private final LlmClientAutoConfiguration autoConfig = new LlmClientAutoConfiguration();

    // ---- LLMClientFactory ----------------------------------------------

    @Test
    void factory_registersProvidersByUpperCaseName() {
        LLMClientFactory factory = autoConfig.llmClientFactory(
                List.of(autoConfig.openAiLLMClient(), autoConfig.ollamaLLMClient(), autoConfig.mockLLMClient()));

        assertTrue(factory.getSupportedProviders().containsAll(List.of("OPENAI", "OLLAMA", "MOCK")));
        assertTrue(factory.isProviderSupported("openai"));
        assertFalse(factory.isProviderSupported("gemini"));
    }

    @Test
    void factory_registersTheRulesProvider_andResolvesItCaseInsensitively() {
        RuleBasedLabelClient rules = autoConfig.rulesLLMClient();
        LLMClientFactory factory = autoConfig.llmClientFactory(
                List.of(autoConfig.openAiLLMClient(), autoConfig.ollamaLLMClient(), autoConfig.mockLLMClient(), rules));

        assertTrue(factory.getSupportedProviders().containsAll(List.of("OPENAI", "OLLAMA", "MOCK", "RULES")));
        assertTrue(factory.isProviderSupported("rules"));
        assertSame(rules, factory.getClient(MLConfigRequest.builder().providerType("rules").build()));
        assertSame(rules, factory.getClient(MLConfigRequest.builder().providerType("Rules").build()));
        assertTrue(factory.validateConfiguration(MLConfigRequest.builder().providerType("rules").build()));
        assertTrue(factory.testConnection(MLConfigRequest.builder().providerType("rules").build()));
        assertEquals("RULES", factory.getAllProviderCapabilities().get("RULES").get("provider"));
    }

    @Test
    void autoConfiguration_exposesTheRulesClientUnderTheNamingConventionAutolabelResolves() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(LlmClientAutoConfiguration.class))
                .run(context -> {
                    // da-autolabel resolves a provider type "rules" as bean "rulesLLMClient".
                    assertTrue(context.containsBean("rulesLLMClient"));
                    assertTrue(context.getBean("rulesLLMClient") instanceof RuleBasedLabelClient);
                    assertTrue(context.getBean(LLMClientFactory.class).isProviderSupported("rules"));
                    assertTrue(context.containsBean("mockLLMClient"));
                    assertTrue(context.containsBean("openAiLLMClient"));
                    assertTrue(context.containsBean("ollamaLLMClient"));
                });
    }

    @Test
    void autoConfiguration_letsAServiceOverrideTheRulesClient() {
        RuleBasedLabelClient custom = new RuleBasedLabelClient(
                DefaultLabelRules.all().subList(0, 1), "custom", Clock.systemDefaultZone());

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(LlmClientAutoConfiguration.class))
                .withBean("customRules", RuleBasedLabelClient.class, () -> custom)
                .run(context -> {
                    assertFalse(context.containsBean("rulesLLMClient"));
                    assertSame(custom, context.getBean(RuleBasedLabelClient.class));
                });
    }

    @Test
    void factory_getClient_resolvesByProviderTypeCaseInsensitively() {
        OpenAiLLMClient openAi = autoConfig.openAiLLMClient();
        LLMClientFactory factory = autoConfig.llmClientFactory(List.of(openAi));

        ILLMClient resolved = factory.getClient(
                MLConfigRequest.builder().providerType("openai").build());

        assertSame(openAi, resolved);
    }

    @Test
    void factory_getClient_unknownProvider_throws() {
        LLMClientFactory factory = autoConfig.llmClientFactory(List.of(autoConfig.openAiLLMClient()));

        assertThrows(IllegalArgumentException.class, () ->
                factory.getClient(MLConfigRequest.builder().providerType("gemini").build()));
    }

    // ---- MockLLMClient -------------------------------------------------

    @Test
    void mockClient_refusesFreeFormCompletion() {
        MockLLMClient mock = autoConfig.mockLLMClient();

        UnsupportedOperationException ex = assertThrows(UnsupportedOperationException.class,
                () -> mock.complete("draft me a policy", MLConfigRequest.builder().build()));

        assertTrue(ex.getMessage().contains("MOCK"));
    }

    // ---- LlmProperties -------------------------------------------------

    @Test
    void properties_rulesProvider_isNotAFreeFormCompletionProvider() {
        // llm.provider=rules must not resolve: services that draft policies from text need a real model,
        // and a rule set cannot complete a prompt.
        LlmProperties props = new LlmProperties();
        props.setProvider("rules");

        assertTrue(props.resolve().isEmpty());
        assertThrows(UnsupportedOperationException.class,
                () -> autoConfig.rulesLLMClient().complete("draft me a policy", MLConfigRequest.builder().build()));
    }

    @Test
    void properties_noProviderSelected_resolvesEmpty() {
        assertTrue(new LlmProperties().resolve().isEmpty());
    }

    @Test
    void properties_unknownProvider_resolvesEmpty() {
        LlmProperties props = new LlmProperties();
        props.setProvider("gemini");

        assertTrue(props.resolve().isEmpty());
    }

    @Test
    void properties_openAiWithoutApiKey_resolvesEmpty() {
        LlmProperties props = new LlmProperties();
        props.setProvider("openai");

        assertTrue(props.resolve().isEmpty());
    }

    @Test
    void properties_openAiWithApiKey_resolvesConfig() {
        LlmProperties props = new LlmProperties();
        props.setProvider("OpenAI");
        props.getOpenai().setApiKey("unit-test-placeholder-key");
        props.getOpenai().setModel("gpt-4o");

        Optional<MLConfigRequest> resolved = props.resolve();

        assertTrue(resolved.isPresent());
        assertEquals("OPENAI", resolved.get().getProviderType());
        assertEquals("gpt-4o", resolved.get().getModelName());
        assertEquals("unit-test-placeholder-key", resolved.get().getApiKey());
        assertEquals(1000, resolved.get().getAdditionalParameters().get("maxTokens"));
    }

    @Test
    void properties_ollama_resolvesWithoutApiKey() {
        LlmProperties props = new LlmProperties();
        props.setProvider("ollama");
        props.getOllama().setModel("llama3");
        props.getOllama().setBaseUrl("http://ollama:11434");

        Optional<MLConfigRequest> resolved = props.resolve();

        assertTrue(resolved.isPresent());
        assertEquals("OLLAMA", resolved.get().getProviderType());
        assertEquals("llama3", resolved.get().getModelName());
        assertEquals("http://ollama:11434", resolved.get().getBaseUrl());
    }

    @Test
    void properties_ollamaWithBlankModel_resolvesEmpty() {
        LlmProperties props = new LlmProperties();
        props.setProvider("ollama");
        props.getOllama().setModel("  ");

        assertTrue(props.resolve().isEmpty());
    }

    // ---- provider client identity --------------------------------------

    @Test
    void providerClients_reportStableNames() {
        assertEquals("OPENAI", new OpenAiLLMClient().getProviderName());
        assertEquals("OLLAMA", new OllamaLLMClient().getProviderName());
        assertEquals("MOCK", new MockLLMClient().getProviderName());
        assertEquals("RULES", new RuleBasedLabelClient().getProviderName());
    }
}
