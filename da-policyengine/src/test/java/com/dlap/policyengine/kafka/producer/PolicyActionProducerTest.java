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

package com.dlap.policyengine.kafka.producer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import com.dlap.policyengine.dsl.PolicyActionRequests;
import com.dlap.policyengine.dsl.PolicyDsl;
import com.dlap.policyengine.dsl.PolicyDslParser;
import com.dlap.policyengine.event.PolicyActionEvent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * POL-01: actions carry assetId, assetUri, provider and serviceType on the wire;
 * {@code dlap.policyengine.actions.enabled=false} is shadow mode and publishes nothing.
 */
class PolicyActionProducerTest {

    private static final String TOPIC = "dlap.policies.actions";
    private static final ObjectMapper JSON = new ObjectMapper();

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private PolicyActionProducer producer(boolean enabled) {
        PolicyActionProducer p = new PolicyActionProducer(kafka, registry, JSON, enabled);
        ReflectionTestUtils.setField(p, "policyActionEventTopic", TOPIC);
        return p;
    }

    private static PolicyActionEvent eventFire(Map<String, Object> snapshot) {
        PolicyDsl dsl = new PolicyDslParser().parse("apiVersion: dlap/v1\nkind: Policy\n"
                + "metadata: {id: archive-stale-pii, name: Archive stale PII data}\nspec:\n"
                + "  triggers: [{type: event, lifecycle: accessed}]\n  match: {cloudProvider: GCP}\n"
                + "  action: {type: archive, parameters: {targetStorageClass: ARCHIVE}}\n");
        return PolicyActionRequests.forEvent(dsl, UUID.fromString("6c1e0d2a-0000-4000-8000-000000000001"), snapshot,
                "accessed", "GCP_CLOUD_AUDIT|abc123", Instant.parse("2026-10-20T02:00:00Z"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> publishedPayload() throws IOException {
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(kafka).send(eq(TOPIC), anyString(), payload.capture());
        return JSON.readValue((String) payload.getValue(), new TypeReference<Map<String, Object>>() { });
    }

    @Test
    void thePublishedActionCarriesTheAssetIdentityAtTheTopLevel() throws IOException {
        when(kafka.send(anyString(), anyString(), any())).thenReturn(new CompletableFuture<>());

        PolicyActionProducer.Outcome outcome = producer(true).send(eventFire(Map.of(
                "assetId", "1f2d3a4b-0000-4000-8000-000000000001", "assetUri", "gs://prod-customer-exports",
                "cloudProvider", "GCP", "serviceType", "GCS_BUCKET")));

        assertThat(outcome).isEqualTo(PolicyActionProducer.Outcome.PUBLISHED);
        Map<String, Object> wire = publishedPayload();
        assertThat(wire).containsEntry("assetId", "1f2d3a4b-0000-4000-8000-000000000001")
                .containsEntry("assetUri", "gs://prod-customer-exports")
                .containsEntry("provider", "GCP")
                .containsEntry("serviceType", "GCS_BUCKET")
                .containsEntry("schemaVersion", "v1");
        assertThat(registry.counter(PolicyActionProducer.PUBLISHED_COUNTER, "actionType", "archive").count())
                .isEqualTo(1.0);
    }

    @Test
    void anUnknownIdentityFieldIsWrittenAsNullNotLeftOut() throws IOException {
        when(kafka.send(anyString(), anyString(), any())).thenReturn(new CompletableFuture<>());

        producer(true).send(eventFire(Map.of("assetId", "1f2d3a4b-0000-4000-8000-000000000001")));

        Map<String, Object> wire = publishedPayload();
        assertThat(wire).containsKeys("assetUri", "provider", "serviceType");
        assertThat(wire.get("assetUri")).isNull();
        assertThat(wire.get("provider")).isNull();
        assertThat(wire).doesNotContainKeys("scheduledFor", "evaluationId");
    }

    @Test
    void shadowModePublishesNothingAndCountsTheAction() {
        PolicyActionProducer.Outcome outcome = producer(false).send(eventFire(Map.of("assetId", "a-1")));

        assertThat(outcome).isEqualTo(PolicyActionProducer.Outcome.SHADOWED);
        verifyNoInteractions(kafka);
        assertThat(registry.counter(PolicyActionProducer.SHADOWED_COUNTER, "actionType", "archive").count())
                .isEqualTo(1.0);
        assertThat(producer(false).actionsEnabled()).isFalse();
    }

    @Test
    void theListOfIdentityFieldsIsStable() {
        assertThat(List.of(PolicyActionEvent.class.getRecordComponents()))
                .extracting(java.lang.reflect.RecordComponent::getName)
                .contains("assetId", "assetUri", "provider", "serviceType");
    }
}
