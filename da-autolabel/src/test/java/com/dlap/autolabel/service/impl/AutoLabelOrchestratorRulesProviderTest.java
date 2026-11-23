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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.dlap.autolabel.client.dto.AssetLabelsPostFeignRequest;
import com.dlap.autolabel.client.dto.CatalogAssetSummaryDTO;
import com.dlap.autolabel.client.dto.LabelAssignmentFeignDTO;
import com.dlap.autolabel.client.feign.AssetCatalogClient;
import com.dlap.autolabel.entity.ManualReviewQueueEntity;
import com.dlap.autolabel.repository.ManualReviewQueueRepository;
import com.dlap.common.constants.DlapConstants;
import com.dlap.llm.client.LLMClientFactory;
import com.dlap.llm.client.impl.MockLLMClient;
import com.dlap.llm.client.impl.RuleBasedLabelClient;

/**
 * The auto-label pipeline run end to end (catalog and review queue mocked) with
 * {@code dlap.autolabel.llm-provider=rules}: real {@link LLMClientFactory}, real
 * {@link RuleBasedLabelClient}, real content builder.
 */
@ExtendWith(MockitoExtension.class)
class AutoLabelOrchestratorRulesProviderTest {

    private static final UUID ASSET_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Mock
    private AssetCatalogClient catalogClient;

    @Mock
    private ManualReviewQueueRepository manualReviewRepo;

    @Captor
    private ArgumentCaptor<AssetLabelsPostFeignRequest> requestCaptor;

    @Captor
    private ArgumentCaptor<List<ManualReviewQueueEntity>> queueCaptor;

    private AutoLabelOrchestratorImpl orchestrator;

    @BeforeEach
    void setUp() {
        LLMClientFactory factory = new LLMClientFactory(List.of(new RuleBasedLabelClient(), new MockLLMClient()));
        orchestrator = new AutoLabelOrchestratorImpl(catalogClient, factory, manualReviewRepo);
        ReflectionTestUtils.setField(orchestrator, "confidenceThreshold", 0.7);
        ReflectionTestUtils.setField(orchestrator, "defaultProvider", "rules");
        ReflectionTestUtils.setField(orchestrator, "defaultModel", "rules-v1");
    }

    @Test
    void strongRuleMatches_areAppliedThroughTheCatalog_namedByTheirRules() {
        when(catalogClient.getAssetById(ASSET_ID)).thenReturn(asset("customer_pii_export.csv", "S3_OBJECT",
                List.of("INTERNAL"), Map.of("owner", "data-team@example.com")));

        List<String> applied = orchestrator.labelAssetDetailed(ASSET_ID);

        assertThat(applied).containsExactly("PII", "CUSTOMER_DATA");
        verify(catalogClient).addLabelsToAsset(eq(ASSET_ID), requestCaptor.capture());
        List<LabelAssignmentFeignDTO> labels = requestCaptor.getValue().getLabels();
        assertThat(labels).extracting(LabelAssignmentFeignDTO::getLabelName).containsExactly("PII", "CUSTOMER_DATA");
        assertThat(labels).extracting(LabelAssignmentFeignDTO::getConfidenceScore).containsExactly(0.95, 0.85);
        verify(manualReviewRepo, never()).saveAll(any());
    }

    @Test
    void weakRuleMatch_goesToTheReviewQueue_withTheRulesProviderAndRuleReasoning() {
        when(catalogClient.getAssetById(ASSET_ID)).thenReturn(asset("exec_dashboard", "BIGQUERY_TABLE",
                List.of(), Map.of()));

        List<String> applied = orchestrator.labelAssetDetailed(ASSET_ID);

        assertThat(applied).isEmpty();
        verify(catalogClient, never()).addLabelsToAsset(any(), any());
        verify(manualReviewRepo).saveAll(queueCaptor.capture());
        assertThat(queueCaptor.getValue()).hasSize(1);
        ManualReviewQueueEntity queued = queueCaptor.getValue().get(0);
        assertThat(queued.getLabel()).isEqualTo("ANALYTICS");
        assertThat(queued.getLlmProvider()).isEqualTo("RULES");
        assertThat(queued.getCategory()).isEqualTo("Business Domain");
        assertThat(queued.getReasoning()).startsWith("Rule analytics.data: ");
        assertThat(queued.getStatus()).isEqualTo("PENDING");
    }

    @Test
    void ownerContactAndExistingLabels_doNotTriggerRules() {
        when(catalogClient.getAssetById(ASSET_ID)).thenReturn(asset("quarterly_numbers", "BIGQUERY_TABLE",
                List.of("PII"), Map.of("owner_email", "team@example.com")));

        assertThat(orchestrator.labelAssetDetailed(ASSET_ID)).isEmpty();

        verify(catalogClient, never()).addLabelsToAsset(any(), any());
        verify(manualReviewRepo, never()).saveAll(any());
    }

    @Test
    void repeatedRuns_applyTheSameLabelsInTheSameOrder() {
        when(catalogClient.getAssetById(ASSET_ID)).thenReturn(asset("patient_billing_ssn", "GCS_OBJECT",
                List.of(), Map.of()));

        List<String> first = orchestrator.labelAssetDetailed(ASSET_ID);

        assertThat(first).isNotEmpty();
        for (int i = 0; i < 5; i++) {
            assertThat(orchestrator.labelAssetDetailed(ASSET_ID)).isEqualTo(first);
        }
    }

    /**
     * Acceptance criterion "assignments are stamped SMART_LABEL_RULE" (LBL-01, Q-098). The
     * rule-based client marks every {@code LabelSuggestion} with source {@code SMART_LABEL_RULE}
     * and {@code AutoLabelOrchestratorImpl} passes that provenance on to the catalog.
     */
    @Test
    void ruleAssignments_areStampedSmartLabelRule() {
        when(catalogClient.getAssetById(ASSET_ID)).thenReturn(asset("customer_pii_export.csv", "S3_OBJECT",
                List.of(), Map.of()));

        orchestrator.labelAssetDetailed(ASSET_ID);

        verify(catalogClient).addLabelsToAsset(eq(ASSET_ID), requestCaptor.capture());
        assertThat(requestCaptor.getValue().getLabels())
                .extracting(LabelAssignmentFeignDTO::getSource)
                .containsOnly(DlapConstants.AssignmentSource.SMART_LABEL_RULE);
    }

    private static CatalogAssetSummaryDTO asset(String name, String type, List<String> labels,
            Map<String, Object> businessMetadata) {
        CatalogAssetSummaryDTO dto = new CatalogAssetSummaryDTO();
        dto.setAssetId(ASSET_ID);
        dto.setAssetName(name);
        dto.setCloudProvider("AWS");
        dto.setAssetType(type);
        dto.setRegion("us-east-1");
        dto.setLabels(labels);
        dto.setBusinessMetadataJson(businessMetadata);
        return dto;
    }
}
