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

package com.dlap.policyengine.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import com.dlap.policyengine.kafka.producer.PolicyActionProducer;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

/**
 * OBS-01: the two publish-failure counters of da-policyengine appear in the Prometheus scrape under
 * the names the alerts {@code PolicyDecisionEventsNotPublished} and {@code PolicyActionEventsNotPublished}
 * read (deployment/platform/prometheus/alerts.yml).
 *
 * <p>Scope: the real producers with a Kafka template that fails on send and a real Prometheus meter
 * registry; no broker. Both producers are failure-isolated by design, so the call returns normally and
 * only the counter shows the failure.
 */
class PublishFailureMetricsTest {

    @SuppressWarnings("unchecked")
    private static KafkaTemplate<String, Object> failingKafka() {
        KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), any())).thenThrow(new IllegalStateException("broker down"));
        return kafka;
    }

    /** An access-decision event that cannot be sent is counted as policyengine_access_decision_publish_failures_total. */
    @Test
    void anAccessDecisionPublishFailureIsCountedUnderTheSeriesTheAlertReads() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        AccessDecisionEventProducer producer =
                new AccessDecisionEventProducer(failingKafka(), registry, new ObjectMapper());
        AccessDecision decision = new AccessDecision("dec-obs01", true, "test", List.of(), List.of(), false, 1L);

        producer.publish(new AuthZenEvaluationRequest(), decision, null, "2026-01-01T00:00:00Z");

        // Micrometer 1.17 writes the Prometheus text with the client 1.x exposition, which has no comma
        // after the last label (BOOT-01); the series name and labels the alert reads are unchanged.
        assertThat(registry.scrape())
                .contains("policyengine_access_decision_publish_failures_total{decision=\"ALLOW\",stage=\"dispatch\"} 1.0");
    }

    /** A policy action event that cannot be sent is counted as policyengine_send_failures_total. */
    @Test
    void aPolicyActionSendFailureIsCountedUnderTheSeriesTheAlertReads() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        PolicyActionProducer producer = new PolicyActionProducer(failingKafka(), registry, new ObjectMapper());
        ReflectionTestUtils.setField(producer, "policyActionEventTopic", "policy-action-events");

        producer.sendPolicyActionEvent(Map.of("assetId", "asset-1", "actionType", "ARCHIVE"));

        assertThat(registry.scrape())
                .contains("policyengine_send_failures_total{actionType=\"ARCHIVE\",stage=\"dispatch\",topic=\"policy-action-events\"} 1.0");
    }
}
