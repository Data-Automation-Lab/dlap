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

package com.dlap.discovery.event.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import com.dlap.common.constants.DlapConstants;
import com.dlap.discovery.common.model.CloudResource;
import com.dlap.discovery.common.model.ResourceChange.ChangeType;
import com.dlap.discovery.event.service.type.ResourceEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Proves the crawler→spine bridge forwards technical metadata onto the flat DlapLogEvent,
 * so da-catalog persists it into dlp_assets.technical_metadata (the fuel for /schema and the
 * IAM-drift / PII-encryption compliance evaluators). Cloud-neutral - one path, every provider.
 */
class CrawlerAssetSpineBridgeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @SuppressWarnings("unchecked")
    void forwardsTechnicalMetadataOntoTheSpineEvent() throws Exception {
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        doReturn(CompletableFuture.completedFuture(null)).when(kafkaTemplate).send(any(), any(), any());

        CloudResource resource = mock(CloudResource.class);
        when(resource.getUri()).thenReturn("gs://acme-data/customers");
        when(resource.getTypeId()).thenReturn("gcs_bucket");
        when(resource.getRegion()).thenReturn("us");
        when(resource.getTechnicalMetadata()).thenReturn(Map.of(
                "storageClass", "STANDARD",
                "sizeBytes", "10485760",
                "isPublic", "true",
                "encryption", "google-managed"));

        ResourceEvent event = mock(ResourceEvent.class);
        when(event.getPayload()).thenReturn(resource);
        when(event.getChangeType()).thenReturn(ChangeType.CREATE);
        when(event.getCloudProvider()).thenReturn("GCP");

        new CrawlerAssetSpineBridge(kafkaTemplate, objectMapper, new SimpleMeterRegistry()).process(event);

        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq(DlapConstants.KafkaTopic.LOG_EVENT_NORMALIZED), any(), valueCaptor.capture());

        JsonNode node = objectMapper.readTree(valueCaptor.getValue());
        JsonNode tech = node.path("targetResource").path("technicalMetadata");
        assertThat(tech.path("storageClass").asText()).isEqualTo("STANDARD");
        assertThat(tech.path("isPublic").asText()).isEqualTo("true");
        assertThat(tech.path("encryption").asText()).isEqualTo("google-managed");
    }
}
