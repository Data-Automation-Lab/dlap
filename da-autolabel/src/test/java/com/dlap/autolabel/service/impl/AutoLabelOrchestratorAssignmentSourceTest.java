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
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.dlap.autolabel.client.dto.AssetLabelsPostFeignRequest;
import com.dlap.autolabel.client.dto.CatalogAssetSummaryDTO;
import com.dlap.autolabel.client.dto.LabelAssignmentFeignDTO;
import com.dlap.autolabel.client.feign.AssetCatalogClient;
import com.dlap.autolabel.repository.ManualReviewQueueRepository;
import com.dlap.common.constants.DlapConstants;
import com.dlap.llm.client.ILLMClient;
import com.dlap.llm.client.LLMClientFactory;
import com.dlap.llm.client.model.LabelSuggestion;

/**
 * Provenance stamping in {@link AutoLabelOrchestratorImpl} (Q-098): a suggestion that carries
 * {@code SMART_LABEL_RULE} keeps it, a model suggestion without a source is
 * {@code SMART_LABEL_ML}, and a provider can never stamp any other provenance.
 */
@ExtendWith(MockitoExtension.class)
class AutoLabelOrchestratorAssignmentSourceTest {

    private static final UUID ASSET_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Mock
    private AssetCatalogClient catalogClient;

    @Mock
    private ManualReviewQueueRepository manualReviewRepo;

    @Mock
    private ILLMClient stubClient;

    private AutoLabelOrchestratorImpl orchestrator;

    @BeforeEach
    void setUp() {
        when(stubClient.getProviderName()).thenReturn("STUB");
        orchestrator = new AutoLabelOrchestratorImpl(catalogClient,
                new LLMClientFactory(List.of(stubClient)), manualReviewRepo);
        ReflectionTestUtils.setField(orchestrator, "confidenceThreshold", 0.7);
        ReflectionTestUtils.setField(orchestrator, "defaultProvider", "stub");
        ReflectionTestUtils.setField(orchestrator, "defaultModel", "stub-v1");
        CatalogAssetSummaryDTO asset = new CatalogAssetSummaryDTO();
        asset.setAssetId(ASSET_ID);
        asset.setAssetName("orders");
        when(catalogClient.getAssetById(ASSET_ID)).thenReturn(asset);
    }

    @Test
    void sourceFromTheSuggestion_isKeptOnlyForTheTwoSmartLabelSources() throws Exception {
        when(stubClient.suggestLabelsWithConfidence(any(), any())).thenReturn(List.of(
                suggestion("RULE_LABEL", DlapConstants.AssignmentSource.SMART_LABEL_RULE),
                suggestion("ML_EXPLICIT", DlapConstants.AssignmentSource.SMART_LABEL_ML),
                suggestion("ML_NULL", null),
                suggestion("ML_BLANK", "  "),
                suggestion("FORGED_USER", DlapConstants.AssignmentSource.USER),
                suggestion("FORGED_POLICY", DlapConstants.AssignmentSource.POLICY_ENGINE),
                suggestion("UNKNOWN", "AUTO_LABEL")));

        List<String> applied = orchestrator.labelAssetDetailed(ASSET_ID);

        assertThat(applied).hasSize(7);
        ArgumentCaptor<AssetLabelsPostFeignRequest> captor = ArgumentCaptor.forClass(AssetLabelsPostFeignRequest.class);
        verify(catalogClient).addLabelsToAsset(eq(ASSET_ID), captor.capture());
        assertThat(captor.getValue().getLabels())
                .extracting(LabelAssignmentFeignDTO::getLabelName, LabelAssignmentFeignDTO::getSource)
                .containsExactly(
                        tuple("RULE_LABEL", DlapConstants.AssignmentSource.SMART_LABEL_RULE),
                        tuple("ML_EXPLICIT", DlapConstants.AssignmentSource.SMART_LABEL_ML),
                        tuple("ML_NULL", DlapConstants.AssignmentSource.SMART_LABEL_ML),
                        tuple("ML_BLANK", DlapConstants.AssignmentSource.SMART_LABEL_ML),
                        tuple("FORGED_USER", DlapConstants.AssignmentSource.SMART_LABEL_ML),
                        tuple("FORGED_POLICY", DlapConstants.AssignmentSource.SMART_LABEL_ML),
                        tuple("UNKNOWN", DlapConstants.AssignmentSource.SMART_LABEL_ML));
    }

    private static LabelSuggestion suggestion(String label, String source) {
        return LabelSuggestion.builder().label(label).confidence(0.9).source(source).build();
    }
}
