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

package com.dlap.autocompliance.signal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.dlap.autocompliance.client.CatalogAssetClient;
import com.dlap.autocompliance.client.dto.AssetIdResolutionResponse;
import com.dlap.common.constants.DlapConstants;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

@ExtendWith(MockitoExtension.class)
class CatalogEventComplianceLabelSinkTest {

    @Mock
    private CatalogAssetClient catalogClient;
    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private CatalogEventComplianceLabelSink sink() {
        return new CatalogEventComplianceLabelSink(catalogClient, kafkaTemplate, objectMapper);
    }

    @Test
    void applyLabel_resolvesAndPublishesAddLabelEvent() {
        UUID assetId = UUID.randomUUID();
        when(catalogClient.resolveAssetId("gs://b/pii"))
                .thenReturn(new AssetIdResolutionResponse(assetId, "gs://b/pii"));
        // The sink now blocks on the send result (synchronous+checked), so return a future.
        doReturn(CompletableFuture.completedFuture(null)).when(kafkaTemplate).send(any(), any(), any());

        sink().applyLabel("gs://b/pii", "compliance:gdpr:violation", "art-17");

        ArgumentCaptor<Object> valueCap = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq(DlapConstants.KafkaTopic.POLICY_ACTION_EVENT),
                eq(assetId.toString()), valueCap.capture());
        // The value is an ObjectNode (NOT a String) so JsonSerializer emits a raw JSON object
        // that da-autolabel's StringDeserializer consumer can readTree.
        assertThat(valueCap.getValue()).isInstanceOf(ObjectNode.class);
        ObjectNode event = (ObjectNode) valueCap.getValue();
        assertThat(event.get("assetId").asText()).isEqualTo(assetId.toString());
        assertThat(event.get("actionType").asText()).isEqualTo("addLabel");
        assertThat(event.get("source").asText()).isEqualTo("compliance");
        assertThat(event.get("actionParameters").get("labelName").asText())
                .isEqualTo("compliance:gdpr:violation");
    }

    @Test
    void applyLabel_whenUriUnresolvable_publishesNothing() {
        when(catalogClient.resolveAssetId(any())).thenThrow(new RuntimeException("404 Not Found"));

        sink().applyLabel("gs://b/unknown", "compliance:gdpr:violation", null);

        verify(kafkaTemplate, never()).send(any(), any(), any());
    }

    @Test
    void applyLabel_dedupesRepeatEmissionsOfSameLabel() {
        UUID assetId = UUID.randomUUID();
        when(catalogClient.resolveAssetId("gs://b/pii"))
                .thenReturn(new AssetIdResolutionResponse(assetId, "gs://b/pii"));
        doReturn(CompletableFuture.completedFuture(null)).when(kafkaTemplate).send(any(), any(), any());

        CatalogEventComplianceLabelSink sink = sink();
        // The periodic re-sync would call applyLabel with the same (uri,label) repeatedly.
        sink.applyLabel("gs://b/pii", "compliance:gdpr:violation", null);
        sink.applyLabel("gs://b/pii", "compliance:gdpr:violation", null);
        sink.applyLabel("gs://b/pii", "compliance:gdpr:violation", null);

        // Published exactly once; subsequent identical emissions are suppressed.
        verify(kafkaTemplate, times(1)).send(any(), any(), any());
    }

    private ObjectNode publishedEvent(CatalogEventComplianceLabelSink sink, String uri, UUID assetId, String label) {
        when(catalogClient.resolveAssetId(uri)).thenReturn(new AssetIdResolutionResponse(assetId, uri));
        doReturn(CompletableFuture.completedFuture(null)).when(kafkaTemplate).send(any(), any(), any());
        sink.applyLabel(uri, label, null);
        ArgumentCaptor<Object> valueCap = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate, atLeastOnce())
                .send(eq(DlapConstants.KafkaTopic.POLICY_ACTION_EVENT), eq(assetId.toString()), valueCap.capture());
        return (ObjectNode) valueCap.getValue();
    }

    /** IDEM-02 (Q-877): the event carries a name UUID of the asset id, the label and the compliance source. */
    @Test
    void applyLabel_setsADeterministicFireIdFromAssetLabelAndSource() {
        UUID assetId = UUID.randomUUID();

        ObjectNode event = publishedEvent(sink(), "gs://b/pii", assetId, "compliance:gdpr:violation");

        String expected = UUID.nameUUIDFromBytes(
                (assetId + "|compliance:gdpr:violation|compliance").getBytes(StandardCharsets.UTF_8)).toString();
        assertThat(event.get("fireId").asText()).isEqualTo(expected);
        assertThat(UUID.fromString(event.get("fireId").asText()).version()).isEqualTo(3);
    }

    /** IDEM-02 (Q-877): a restart (a new sink, an empty in-memory guard) republishes the same fireId. */
    @Test
    void applyLabel_repeatedByANewInstanceCarriesTheSameFireId() {
        UUID assetId = UUID.randomUUID();

        String first = publishedEvent(sink(), "gs://b/pii", assetId, "compliance:gdpr:violation")
                .get("fireId").asText();
        String second = publishedEvent(sink(), "gs://b/pii", assetId, "compliance:gdpr:violation")
                .get("fireId").asText();

        assertThat(second).isEqualTo(first);
    }

    /** IDEM-02 (Q-877): another label or another asset is another fire. */
    @Test
    void applyLabel_differentLabelOrAssetGivesADifferentFireId() {
        UUID assetA = UUID.randomUUID();
        UUID assetB = UUID.randomUUID();

        assertThat(CatalogEventComplianceLabelSink.fireIdFor(assetA, "compliance:gdpr:violation"))
                .isNotEqualTo(CatalogEventComplianceLabelSink.fireIdFor(assetA, "compliance:gdpr:ok"))
                .isNotEqualTo(CatalogEventComplianceLabelSink.fireIdFor(assetB, "compliance:gdpr:violation"));
    }
}
