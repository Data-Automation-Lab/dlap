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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import com.dlap.common.constants.DlapConstants;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Publishes the Consumed-stage access-decision log event
 * (enforcement-contract.md §6) onto {@link
 * DlapConstants.KafkaTopic#ACCESS_DECISION} - one event per evaluation, allow or
 * deny.
 *
 * <p>Mirrors {@link com.dlap.policyengine.kafka.producer.PolicyActionProducer}:
 * reuses the shared {@code KafkaTemplate<String, Object>} (idempotent producer,
 * retries - {@code KafkaReliabilityConfiguration}), serializes to a JSON string,
 * and is <b>failure-isolated</b>: a Kafka error is logged + counted but never
 * thrown into the caller, so an audit-publish failure cannot fail the
 * evaluation response.
 */
@Service
public class AccessDecisionEventProducer {

    private static final Logger log = LoggerFactory.getLogger(AccessDecisionEventProducer.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;

    public AccessDecisionEventProducer(KafkaTemplate<String, Object> kafkaTemplate,
                                       MeterRegistry meterRegistry,
                                       ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.meterRegistry = meterRegistry;
        this.objectMapper = objectMapper;
    }

    /**
     * Publish the decision-log event. Never throws.
     *
     * @param request  the evaluated request (subject / action / on-behalf-of)
     * @param decision the deterministic outcome
     * @param resolved the resolved resource (asset id / uri for the audit join)
     * @param timestamp ISO-8601 evaluation timestamp
     */
    public void publish(AuthZenEvaluationRequest request,
                        AccessDecision decision,
                        IAssetSnapshotResolver.ResolvedResource resolved,
                        String timestamp) {
        final String key = decision.decisionId();
        try {
            Map<String, Object> event = buildEvent(request, decision, resolved, timestamp);
            final String payload = objectMapper.writeValueAsString(event);
            CompletableFuture<SendResult<String, Object>> future =
                    kafkaTemplate.send(DlapConstants.KafkaTopic.ACCESS_DECISION, key, payload);
            future.whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("Failed to publish access-decision event {} ({}): {}",
                            decision.decisionId(), decision.decisionLabel(), ex.getMessage(), ex);
                    meterRegistry.counter("policyengine.access.decision.publish.failures",
                            "decision", decision.decisionLabel()).increment();
                }
            });
        } catch (Exception e) {
            // Serialization / pre-send failure - must not fail the verdict response.
            log.error("Error dispatching access-decision event {}: {}",
                    decision.decisionId(), e.getMessage(), e);
            meterRegistry.counter("policyengine.access.decision.publish.failures",
                    "decision", decision.decisionLabel(), "stage", "dispatch").increment();
        }
    }

    private static Map<String, Object> buildEvent(AuthZenEvaluationRequest request,
                                                  AccessDecision decision,
                                                  IAssetSnapshotResolver.ResolvedResource resolved,
                                                  String timestamp) {
        AuthZenEvaluationRequest.Subject subject =
                request != null ? request.getSubject() : null;
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("decisionId", decision.decisionId());
        event.put("timestamp", timestamp);
        event.put("subjectType", subject != null ? subject.getType() : null);
        event.put("subjectId", subject != null ? subject.getId() : null);
        event.put("onBehalfOf", onBehalfOf(subject));
        event.put("action", request != null && request.getAction() != null
                ? request.getAction().getName() : null);
        event.put("assetId", resolved != null ? resolved.assetId() : null);
        event.put("assetUri", resolved != null ? resolved.assetUri() : null);
        event.put("decision", decision.decisionLabel());
        event.put("policyIds", decision.policyIds());
        event.put("obligations", obligationsToWire(decision.obligations()));
        event.put("defaultApplied", decision.defaultApplied());
        event.put("latencyMs", decision.latencyMs());
        return event;
    }

    private static Object onBehalfOf(AuthZenEvaluationRequest.Subject subject) {
        if (subject != null && subject.getProperties() != null) {
            return subject.getProperties().get("on_behalf_of");
        }
        return null;
    }

    private static List<Map<String, Object>> obligationsToWire(
            List<AccessDecision.PolicyDslObligation> obligations) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (obligations != null) {
            for (AccessDecision.PolicyDslObligation o : obligations) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("type", o.type());
                m.put("labels", o.labels());
                out.add(m);
            }
        }
        return out;
    }
}
