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
package com.dlap.autolabel.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.dlap.autolabel.service.IMLConfigService;
import com.dlap.llm.client.config.MLConfigRequest;

/**
 * SEC-03 (Q-027): the body of {@code PUT /api/v1/labeling/config/ml} is validated before the
 * configuration service is reached, and an unset optional field stays valid.
 */
@WebMvcTest(LabelingConfigController.class)
class LabelingConfigValidationTest {

    private static final String PATH = "/api/v1/labeling/config/ml";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IMLConfigService mlConfigService;

    @Test
    @WithMockUser(authorities = "ROLE_ADMIN")
    void aConfigurationWithOnlyTheCommonFieldsIsAccepted() throws Exception {
        mockMvc
            .perform(
                put(PATH)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"providerType\":\"ollama\",\"baseUrl\":\"http://localhost:11434\",\"modelName\":\"llama3\"}")
            )
            .andExpect(status().isOk());

        verify(mlConfigService, times(1)).updateMlConfig(any(MLConfigRequest.class));
    }

    @Test
    @WithMockUser(authorities = "ROLE_ADMIN")
    void aFullyPopulatedConfigurationIsAccepted() throws Exception {
        String body =
            "{\"providerType\":\"openai\",\"apiKey\":\"k\",\"baseUrl\":\"https://api.example.test/v1\","
            + "\"modelName\":\"m\",\"promptTemplate\":\"p\",\"additionalParameters\":{\"temperature\":0.2},"
            + "\"suggestionConfig\":{\"maxSuggestions\":5,\"includeConfidenceScores\":true},"
            + "\"confidenceConfig\":{\"minThreshold\":0.7,\"actionBelowThreshold\":\"REJECT\"}}";

        mockMvc.perform(put(PATH).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());

        verify(mlConfigService, times(1)).updateMlConfig(any(MLConfigRequest.class));
    }

    @Test
    @WithMockUser(authorities = "ROLE_ADMIN")
    void aBadValueIsABadRequestAndTheServiceIsNotReached() throws Exception {
        String longText = "x".repeat(257);
        for (String body : new String[] {
            "{\"baseUrl\":\"ftp://files.example.test\"}",
            "{\"baseUrl\":\"file:///etc/passwd\"}",
            "{\"baseUrl\":\"http://a b\"}",
            "{\"modelName\":\"" + longText + "\"}",
            "{\"providerType\":\"" + "p".repeat(65) + "\"}",
            "{\"promptTemplate\":\"" + "t".repeat(MLConfigRequest.MAX_PROMPT_TEMPLATE_LENGTH + 1) + "\"}",
            "{\"suggestionConfig\":{\"maxSuggestions\":0}}",
            "{\"suggestionConfig\":{\"maxSuggestions\":101}}",
            "{\"confidenceConfig\":{\"minThreshold\":1.5}}",
            "{\"confidenceConfig\":{\"minThreshold\":-0.1}}",
            "{\"confidenceConfig\":{\"actionBelowThreshold\":\"" + "a".repeat(65) + "\"}}",
        }) {
            mockMvc
                .perform(put(PATH).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        }

        verifyNoInteractions(mlConfigService);
    }
}
