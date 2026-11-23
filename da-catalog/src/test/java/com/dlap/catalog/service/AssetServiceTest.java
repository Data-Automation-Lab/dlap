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

package com.dlap.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import com.dlap.catalog.dto.AssetLabelsPostRequestDTO;
import com.dlap.catalog.dto.LabelAssignmentInputDTO;
import com.dlap.catalog.kafka.CanonicalUriIndex;
import com.dlap.catalog.kafka.CatalogIngestProperties;
import com.dlap.catalog.mapper.AssetMapper;
import com.dlap.catalog.repository.AssetLineageRepository;
import com.dlap.catalog.usage.AssetUsageDailyStore;
import com.dlap.common.model.entity.Asset;
import com.dlap.common.model.entity.AssetLabelAssignment;
import com.dlap.common.model.entity.Label;
import com.dlap.common.repository.IAssetLabelAssignmentRepository;
import com.dlap.common.repository.IAssetRepository;
import com.dlap.common.repository.ILabelRepository;
import com.dlap.common.repository.IUserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class AssetServiceTest {

    @Mock
    private IAssetRepository assetRepository;
    @Mock
    private AssetMapper assetMapper;
    @Mock
    private ILabelRepository labelRepository;
    @Mock
    private IAssetLabelAssignmentRepository assetLabelAssignmentRepository;
    @Mock
    private IUserRepository userRepository;
    @Mock
    private AssetLineageRepository assetLineageRepository;
    @Mock
    private LineageService lineageService;
    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;
    @Mock
    private CanonicalUriIndex canonicalUriIndex;
    @Mock
    private AssetUsageDailyStore usageStore;

    private static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");

    private AssetService assetService;

    @BeforeEach
    void setUp() {
        assetService = new AssetService(
                assetRepository,
                assetMapper,
                labelRepository,
                assetLabelAssignmentRepository,
                userRepository,
                assetLineageRepository,
                lineageService,
                kafkaTemplate,
                new ObjectMapper(),
                new com.dlap.catalog.integration.NoopExternalCatalogIntegration(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                new CatalogIngestProperties(),
                canonicalUriIndex,
                usageStore);
        ReflectionTestUtils.setField(assetService, "labelAssignmentChangedTopic", "dlap.asset.label.assignment.changed.test");
    }

    @Test
    void assignLabelsToAsset_publishesKafkaEvent() throws Exception {
        UUID assetId = UUID.randomUUID();
        UUID labelId = UUID.randomUUID();
        UUID assigner = UUID.randomUUID();

        Asset asset = new Asset("gs://b/o", "GCP", "GCS_BUCKET");
        asset.setAssetId(assetId);
        when(assetRepository.findById(assetId)).thenReturn(Optional.of(asset));
        when(assetRepository.existsById(assetId)).thenReturn(true);

        Label label = Label.builder().labelId(labelId).labelName("PII").labelCategory("sec").build();
        when(labelRepository.findByLabelName("PII")).thenReturn(Optional.of(label));
        when(assetLabelAssignmentRepository.findByAssetAndLabel(asset, label)).thenReturn(Optional.empty());
        when(assetLabelAssignmentRepository.save(any(AssetLabelAssignment.class))).thenAnswer(inv -> inv.getArgument(0));

        AssetLabelAssignment row = new AssetLabelAssignment();
        row.setAssetId(assetId);
        row.setLabelId(labelId);
        row.setLabel(label);
        row.setAsset(asset);
        when(assetLabelAssignmentRepository.findByAssetAssetId(assetId)).thenReturn(List.of(row));

        AssetLabelsPostRequestDTO req = new AssetLabelsPostRequestDTO();
        LabelAssignmentInputDTO in = new LabelAssignmentInputDTO();
        in.setLabelName("PII");
        req.setLabels(List.of(in));

        assetService.assignLabelsToAsset(assetId, req, assigner);

        ArgumentCaptor<String> valueCap = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(
                eq("dlap.asset.label.assignment.changed.test"),
                eq(assetId.toString()),
                valueCap.capture());
        assertThat(valueCap.getValue()).contains("LABELS_ASSIGNED").contains(assetId.toString());
    }

    /**
     * Q-417: asset-change events go to {@code dlap.asset.change.event}, the topic da-policyengine's
     * LifecycleEventStreamConsumer subscribes to (see LifecycleEventStreamTopicsTest there), not to
     * {@code dlap.assets.changes}.
     */
    @Test
    void publishAssetChangeEvent_usesTheTopicThePolicyEngineReads() {
        Asset asset = new Asset("s3://b/k", "AWS", "S3_BUCKET");
        asset.setAssetId(UUID.randomUUID());

        assetService.publishAssetChangeEvent(asset, "ASSET_UPDATED", "user-1");

        ArgumentCaptor<String> topic = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate, org.mockito.Mockito.atLeastOnce()).send(topic.capture(), eq(asset.getAssetId().toString()),
                org.mockito.ArgumentMatchers.<Object>any());
        assertThat(topic.getAllValues()).contains("dlap.asset.change.event")
                .doesNotContain("dlap.assets.changes");
        assertThat(AssetService.ASSET_CHANGE_TOPIC)
                .isEqualTo(com.dlap.common.constants.DlapConstants.KafkaTopic.ASSET_CHANGE_EVENT);
    }

    @Test
    void getBusinessMetadata_parsesNestedOwnerUuid() {
        UUID ownerId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        Asset asset = new Asset("s3://b/k", "AWS", "S3_BUCKET");
        asset.setAssetId(assetId);
        asset.setBusinessMetadataJson(Map.of(
                "businessOwner", Map.of("userId", ownerId.toString(), "name", "Owner"),
                "dataSteward", "not-a-map"));

        when(assetRepository.findById(assetId)).thenReturn(Optional.of(asset));

        var resp = assetService.getBusinessMetadata(assetId);
        assertThat(resp.getBusinessMetadata().getBusinessOwner().getUserId()).isEqualTo(ownerId);
    }
}
