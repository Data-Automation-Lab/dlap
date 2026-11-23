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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.dlap.common.constants.DlapConstants;
import com.dlap.llm.client.config.MLConfigRequest;
import com.dlap.autolabel.exception.LlmProviderNotConfiguredException;
import com.dlap.llm.client.ILLMClient;
import com.dlap.llm.client.impl.MockLLMClient;
import com.dlap.llm.client.impl.RuleBasedLabelClient;

@ExtendWith(MockitoExtension.class)
class LLMIntegrationServiceImplTest {

    @Mock
    private ILLMClient mockLlmClient;

    @Test
    void suggestLabels_allowMockFalse_throwsWhenUnconfigured() {
        LLMIntegrationServiceImpl svc = new LLMIntegrationServiceImpl(Map.of());
        ReflectionTestUtils.setField(svc, "allowMock", false);

        assertThatThrownBy(() -> svc.suggestLabelsForAsset("x", null))
                .isInstanceOf(LlmProviderNotConfiguredException.class);
    }

    @Test
    void suggestLabels_allowMockTrue_usesMockClient() throws Exception {
        when(mockLlmClient.getProviderName()).thenReturn("MOCK");
        when(mockLlmClient.suggestLabels(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any())).thenReturn(List.of("L1", "L2"));

        LLMIntegrationServiceImpl svc = new LLMIntegrationServiceImpl(
                Map.of(LLMIntegrationServiceImpl.MOCK_CLIENT_BEAN_NAME, mockLlmClient));
        ReflectionTestUtils.setField(svc, "allowMock", true);

        MLConfigRequest cfg = MLConfigRequest.builder().providerType("OPENAI").build();
        List<String> labels = svc.suggestLabelsForAsset("payload", cfg);
        assertThat(labels).containsExactly("L1", "L2");
    }

    // ---- rules provider (LBL-01) ----------------------------------------

    private static final String CUSTOMER_PII_CONTENT =
            "Asset name: customer_pii_export.csv\nCloud provider: AWS\nAsset type: S3_OBJECT\nRegion: us-east-1";

    @Test
    void suggestLabels_rulesProvider_resolvesByBeanNameWithoutTheMockFlag() throws Exception {
        LLMIntegrationServiceImpl svc = new LLMIntegrationServiceImpl(
                Map.of("rulesLLMClient", new RuleBasedLabelClient()));
        ReflectionTestUtils.setField(svc, "allowMock", false);

        for (String providerType : List.of("rules", "RULES", "Rules")) {
            MLConfigRequest cfg = MLConfigRequest.builder().providerType(providerType).build();

            assertThat(svc.suggestLabelsForAsset(CUSTOMER_PII_CONTENT, cfg))
                    .as(providerType).containsExactly("PII", "CUSTOMER_DATA");
        }
    }

    @Test
    void suggestLabels_rulesProvider_resolvesByProviderNameWhenTheBeanNameDiffers() throws Exception {
        LLMIntegrationServiceImpl svc = new LLMIntegrationServiceImpl(
                Map.of("customRuleSet", new RuleBasedLabelClient()));
        ReflectionTestUtils.setField(svc, "allowMock", false);

        List<String> labels = svc.suggestLabelsForAsset(CUSTOMER_PII_CONTENT,
                MLConfigRequest.builder().providerType("rules").build());

        assertThat(labels).containsExactly("PII", "CUSTOMER_DATA");
    }

    @Test
    void suggestLabels_rulesProvider_isNeverReplacedByTheMockWhenBothAreAvailable() throws Exception {
        LLMIntegrationServiceImpl svc = new LLMIntegrationServiceImpl(Map.of(
                "rulesLLMClient", new RuleBasedLabelClient(),
                LLMIntegrationServiceImpl.MOCK_CLIENT_BEAN_NAME, new MockLLMClient()));
        ReflectionTestUtils.setField(svc, "allowMock", true);

        List<String> labels = svc.suggestLabelsForAsset(CUSTOMER_PII_CONTENT,
                MLConfigRequest.builder().providerType("rules").build());

        assertThat(labels).containsExactly("PII", "CUSTOMER_DATA");
    }

    @Test
    void suggestLabels_rulesProviderNotRegistered_throwsAndNamesTheRulesOption() {
        LLMIntegrationServiceImpl svc = new LLMIntegrationServiceImpl(Map.of());
        ReflectionTestUtils.setField(svc, "allowMock", false);

        assertThatThrownBy(() -> svc.suggestLabelsForAsset("x", MLConfigRequest.builder().providerType("rules").build()))
                .isInstanceOf(LlmProviderNotConfiguredException.class)
                .hasMessageContaining("provider_type=rules");
    }

    @Test
    void suggestLabels_unconfigured_neverFallsBackToRulesSilently() {
        LLMIntegrationServiceImpl svc = new LLMIntegrationServiceImpl(
                Map.of("rulesLLMClient", new RuleBasedLabelClient()));
        ReflectionTestUtils.setField(svc, "allowMock", false);

        assertThatThrownBy(() -> svc.suggestLabelsForAsset(CUSTOMER_PII_CONTENT, null))
                .isInstanceOf(LlmProviderNotConfiguredException.class);
        assertThatThrownBy(() -> svc.suggestLabelsForAsset(CUSTOMER_PII_CONTENT,
                MLConfigRequest.builder().providerType("openai").build()))
                .isInstanceOf(LlmProviderNotConfiguredException.class);
    }

    // ---- mock stays gated and is now reproducible ----------------------

    @Test
    void suggestLabels_realMockClient_isGatedByTheFlag() {
        LLMIntegrationServiceImpl svc = new LLMIntegrationServiceImpl(
                Map.of(LLMIntegrationServiceImpl.MOCK_CLIENT_BEAN_NAME, new MockLLMClient()));
        ReflectionTestUtils.setField(svc, "allowMock", false);

        assertThatThrownBy(() -> svc.suggestLabelsForAsset("x", null))
                .isInstanceOf(LlmProviderNotConfiguredException.class);
    }

    @Test
    void suggestLabels_realMockClient_givesTheSameLabelsOnEveryCall() throws Exception {
        LLMIntegrationServiceImpl svc = new LLMIntegrationServiceImpl(
                Map.of(LLMIntegrationServiceImpl.MOCK_CLIENT_BEAN_NAME, new MockLLMClient()));
        ReflectionTestUtils.setField(svc, "allowMock", true);

        List<String> first = svc.suggestLabelsForAsset(CUSTOMER_PII_CONTENT, null);

        assertThat(first).isNotEmpty();
        for (int i = 0; i < 10; i++) {
            assertThat(svc.suggestLabelsForAsset(CUSTOMER_PII_CONTENT, null)).isEqualTo(first);
        }
    }

    // ---- provenance contract -------------------------------------------

    @Test
    void rulesProviderSource_matchesTheCatalogAssignmentSourceConstant() {
        assertThat(RuleBasedLabelClient.ASSIGNMENT_SOURCE)
                .isEqualTo(DlapConstants.AssignmentSource.SMART_LABEL_RULE);
    }
}
