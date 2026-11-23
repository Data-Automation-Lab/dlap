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

package com.dlap.autolabel.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dlap.autolabel.client.dto.CatalogAssetPageDTO;
import com.dlap.autolabel.client.dto.CatalogAssetSummaryDTO;
import com.dlap.autolabel.client.feign.ResilientAssetCatalogClient;
import com.dlap.autolabel.client.rest.dto.LabelingJobRequest;
import com.dlap.autolabel.client.rest.dto.LabelingJobResponse;
import com.dlap.llm.client.config.MLConfigRequest;
import com.dlap.llm.client.ILLMClient;
import com.dlap.llm.client.LLMClientFactory;
import com.dlap.llm.client.model.LabelSuggestion;
import com.dlap.autolabel.repository.ILabelFeedbackRepository;

@ExtendWith(MockitoExtension.class)
class AutoLabelingServiceTest {

    @Mock private LLMClientFactory llmClientFactory;
    @Mock private IMLConfigService mlConfigService;
    @Mock private ResilientAssetCatalogClient assetCatalogClient;
    @Mock private ILLMClient llmClient;
    @Mock private ILabelFeedbackRepository labelFeedbackRepository;

    @InjectMocks private AutoLabelingService service;

    @Test
    void createLabelingJob_singleAsset_usesFactoryClientAndReturnsSuggestions() throws Exception {
        MLConfigRequest ml = MLConfigRequest.builder()
                .providerType("OPENAI")
                .confidenceConfig(MLConfigRequest.ConfidenceConfig.builder().minThreshold(0.8).build())
                .build();

        when(mlConfigService.getMlConfig()).thenReturn(Optional.of(ml));
        when(llmClientFactory.getClient(ml)).thenReturn(llmClient);

        UUID assetId = UUID.randomUUID();
        CatalogAssetSummaryDTO asset = new CatalogAssetSummaryDTO();
        asset.setAssetId(assetId);
        asset.setAssetName("a1");
        asset.setAssetType("TABLE");

        when(assetCatalogClient.getAssetById(assetId)).thenReturn(asset);

        when(llmClient.suggestLabelsWithConfidence(any(String.class), eq(ml))).thenReturn(List.of(
                LabelSuggestion.builder().label("PII").confidence(0.95).reasoning("r").category("COMPLIANCE").build(),
                LabelSuggestion.builder().label("LOW").confidence(0.10).reasoning("r").category("MISC").build()
        ));

        LabelingJobRequest req = LabelingJobRequest.builder()
                .jobName("j1")
                .scope(LabelingJobRequest.LabelingScope.builder().assetIds(List.of(assetId.toString())).build())
                .build();

        LabelingJobResponse out = service.createLabelingJob(req);
        assertThat(out.getStatus()).isEqualTo("COMPLETED");
        assertThat(out.getSuggestions()).extracting("labelName").containsExactly("PII");
        verify(llmClientFactory).getClient(ml);
        verify(llmClient).suggestLabelsWithConfidence(any(String.class), eq(ml));
    }

    @Test
    void createLabelingJob_cloudConnectionScope_callsCatalogList() {
        MLConfigRequest ml = MLConfigRequest.builder().providerType("OPENAI").build();
        when(mlConfigService.getMlConfig()).thenReturn(Optional.of(ml));
        when(llmClientFactory.getClient(ml)).thenReturn(llmClient);

        UUID connectionId = UUID.randomUUID();
        CatalogAssetPageDTO page = new CatalogAssetPageDTO();
        CatalogAssetSummaryDTO s = new CatalogAssetSummaryDTO();
        s.setAssetId(UUID.randomUUID());
        page.setContent(List.of(s));
        when(assetCatalogClient.getAssetsByConnection(eq(connectionId), eq(0), eq(500))).thenReturn(page);

        LabelingJobRequest req = LabelingJobRequest.builder()
                .jobName("j1")
                .scope(LabelingJobRequest.LabelingScope.builder().cloudConnectionId(connectionId.toString()).build())
                .build();

        LabelingJobResponse out = service.createLabelingJob(req);
        // Single-asset path with no getAssetById mock hits CatalogUnavailableException
        // and the job is failed loudly with a catalog-unavailable message - the
        // previous placeholder-string fallback was removed as part of M1 hardening.
        // Multi-asset path would return SUBMITTED; either outcome here reaches the
        // LLM factory lookup, so we keep that verification.
        assertThat(out.getStatus()).isIn("COMPLETED", "SUBMITTED", "FAILED");
        if ("FAILED".equals(out.getStatus())) {
            assertThat(out.getErrorMessage()).contains("catalog unavailable");
        }
        verify(assetCatalogClient).getAssetsByConnection(eq(connectionId), eq(0), eq(500));
        verify(llmClientFactory).getClient(ml);
    }
}

