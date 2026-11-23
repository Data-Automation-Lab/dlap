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

package com.dlap.discovery.log.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import com.dlap.common.constants.DlapConstants;
import com.dlap.discovery.common.model.CloudService;
import com.dlap.discovery.common.model.ResourceChange;
import com.dlap.discovery.common.model.ResourceType;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Contract test for {@link LogAnalyzerSpinePublisher}.
 *
 * <p>Proves the log-analyzer path emits the canonical FLAT {@code DlapLogEvent} shape that
 * {@code DiscoveryLogEventProcessor} (router) and da-catalog consume - a TOP-LEVEL
 * {@code changeType} and {@code targetResource.id} - rather than the legacy nested
 * {@code LogEvent{ payload:@class }} shape that dead-lettered at the router.
 */
@ExtendWith(MockitoExtension.class)
class LogAnalyzerSpinePublisherTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Test
    void emitsFlatDlapLogEventWithTopLevelChangeTypeAndTargetResourceId() throws Exception {
        doReturn(CompletableFuture.completedFuture(null))
                .when(kafkaTemplate).send(any(), any(), any());

        LogAnalyzerSpinePublisher publisher = new LogAnalyzerSpinePublisher(kafkaTemplate, objectMapper);

        CloudService gcs = new CloudService("gcp_gcs", "Cloud Storage", CloudProvider.GCP);
        ResourceType type = new ResourceType("gcp_gcs_bucket", "GCS Bucket", gcs);
        ResourceChange change = new ResourceChange(
                "demo-bucket", type, ResourceChange.ChangeType.CREATE,
                Instant.parse("2026-07-04T09:30:00Z"), "alice@example.com");

        publisher.publish("GCP", "demo-project", change);

        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        // Partitioned by asset identity (resourceId), published to the normalized spine.
        verify(kafkaTemplate).send(
                eq(DlapConstants.KafkaTopic.LOG_EVENT_NORMALIZED), eq("demo-bucket"), valueCaptor.capture());

        // The value is a pre-serialized JSON String (LOG_EVENT_NORMALIZED convention).
        JsonNode node = objectMapper.readTree(valueCaptor.getValue());

        // The exact fields DiscoveryLogEventProcessor reads: top-level changeType (else it
        // DLQs) and targetResource.id (else da-catalog drops it as "missing assetUri").
        assertEquals("CREATE", node.path("changeType").asText(),
                "changeType must be TOP-LEVEL, not nested under payload");
        assertEquals("demo-bucket", node.path("targetResource").path("id").asText(),
                "targetResource.id must carry the asset identity");
        assertEquals("GCP", node.path("sourceProvider").asText());
        // The "source" marker is gone from the wire: the codec printed it as origin and source_format
        // (log-event.md section 2, one encoding).
        assertTrue(node.path("source").isMissingNode(), "the legacy source marker is not on the wire");
        assertEquals("ORIGIN_CLOUD_LOG", node.path("origin").asText());
        assertEquals("DLAP_LOGANALYZER/resource-change", node.path("sourceFormat").asText());
        // No @class type info leaks onto the wire.
        assertTrue(node.path("payload").isMissingNode(), "no nested @class payload on the wire");
        // The analyzer's own identifier stays in id; the canonical id (ADR-0047) is added next to it,
        // resolved with the project as the hint. The Kafka key is unchanged (ADR-0047 item 8).
        assertEquals("gs://demo-bucket", node.path("targetResource").path("canonicalUri").asText());
    }

    @Test
    void aNameThatDoesNotNormalizeIsPublishedWithoutACanonicalUri() throws Exception {
        doReturn(CompletableFuture.completedFuture(null))
                .when(kafkaTemplate).send(any(), any(), any());
        LogAnalyzerSpinePublisher publisher = new LogAnalyzerSpinePublisher(kafkaTemplate, objectMapper);

        CloudService gcs = new CloudService("gcp_gcs", "Cloud Storage", CloudProvider.GCP);
        ResourceType type = new ResourceType("gcp_gcs_bucket", "GCS Bucket", gcs);
        // Not a GCS bucket name (a space and a bang), so nothing is guessed.
        ResourceChange change = new ResourceChange(
                "bad bucket!", type, ResourceChange.ChangeType.UPDATE,
                Instant.parse("2026-07-04T09:30:00Z"), "alice@example.com");

        publisher.publish("GCP", "demo-project", change);

        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(
                eq(DlapConstants.KafkaTopic.LOG_EVENT_NORMALIZED), eq("bad bucket!"), valueCaptor.capture());
        JsonNode node = objectMapper.readTree(valueCaptor.getValue());
        assertEquals("bad bucket!", node.path("targetResource").path("id").asText());
        // The canonical print writes an unset string as "", which the reader takes as absent.
        assertEquals("", node.path("targetResource").path("canonicalUri").asText());
    }

    @Test
    void theEventIsReadBackByTheCodecWithTheCanonicalUri() throws Exception {
        doReturn(CompletableFuture.completedFuture(null))
                .when(kafkaTemplate).send(any(), any(), any());
        LogAnalyzerSpinePublisher publisher = new LogAnalyzerSpinePublisher(kafkaTemplate, objectMapper);
        CloudService gcs = new CloudService("gcp_gcs", "Cloud Storage", CloudProvider.GCP);
        ResourceChange change = new ResourceChange(
                "demo-bucket", new ResourceType("gcp_gcs_bucket", "GCS Bucket", gcs),
                ResourceChange.ChangeType.CREATE, Instant.parse("2026-07-04T09:30:00Z"), "alice@example.com");

        publisher.publish("GCP", "demo-project", change);

        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(any(), any(), valueCaptor.capture());
        var event = com.dlap.common.logevent.DlapLogEventCodec.defaults().decode(valueCaptor.getValue());
        assertEquals("gs://demo-bucket", event.getTargetResource().getCanonicalUri());
        assertEquals(com.dlap.proto.log.v1.Origin.ORIGIN_CLOUD_LOG, event.getOrigin());
    }
}
