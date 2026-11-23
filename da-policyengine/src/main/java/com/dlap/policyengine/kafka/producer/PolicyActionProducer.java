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

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import com.dlap.policyengine.event.PolicyActionEvent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Publishes policy actions ({@code PolicyActionRequest} v1, {@link PolicyActionEvent}) on
 * {@code dlap.policies.actions}.
 *
 * <p><b>Shadow mode (POL-01).</b> With {@code dlap.policyengine.actions.enabled=false} nothing is
 * published: each action that would have been sent is logged at INFO with its policy, action type
 * and asset, and counted in {@code policyengine.actions.shadowed}. Evaluation, schedule state and
 * evaluation records are unchanged, so an operator can run new policies against a real estate
 * and read what they would do before any consumer acts. The default is {@code true}.
 */
@Service
public class PolicyActionProducer {

    private static final Logger log = LoggerFactory.getLogger(PolicyActionProducer.class);

    /** Counter of actions withheld by shadow mode, tagged by {@code actionType}. */
    public static final String SHADOWED_COUNTER = "policyengine.actions.shadowed";

    /** Counter of actions handed to the Kafka producer, tagged by {@code actionType}. */
    public static final String PUBLISHED_COUNTER = "policyengine.actions.published";

    /** What became of one action. */
    public enum Outcome {
        /** Handed to the Kafka producer; a later send failure is counted, not returned. */
        PUBLISHED,
        /** Withheld because actions are disabled (shadow mode). */
        SHADOWED,
        /** Serialisation or dispatch failed before the send. */
        FAILED
    }

    /**
     * What became of one action, with its delivery (POL-02, Q-439).
     *
     * @param outcome   the synchronous outcome
     * @param delivered completes {@code true} when the broker acknowledged the record (or nothing
     *                  was to be sent: shadow mode), {@code false} when the send failed, before or
     *                  after the hand-off; never completes exceptionally
     */
    public record Dispatch(Outcome outcome, CompletableFuture<Boolean> delivered) {
    }

    @Value("${app.kafka.topic.policy-action-event:policy-action-events}")
    private String policyActionEventTopic;

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;
    private final boolean actionsEnabled;

    /**
     * Producer with actions enabled.
     *
     * @param kafkaTemplate the template
     * @param meterRegistry the metrics registry
     * @param objectMapper  the JSON mapper
     */
    public PolicyActionProducer(KafkaTemplate<String, Object> kafkaTemplate,
                                MeterRegistry meterRegistry,
                                ObjectMapper objectMapper) {
        this(kafkaTemplate, meterRegistry, objectMapper, true);
    }

    /**
     * Producer.
     *
     * @param kafkaTemplate  the template
     * @param meterRegistry  the metrics registry
     * @param objectMapper   the JSON mapper
     * @param actionsEnabled {@code dlap.policyengine.actions.enabled}; false is shadow mode
     */
    @Autowired
    public PolicyActionProducer(KafkaTemplate<String, Object> kafkaTemplate,
                                MeterRegistry meterRegistry,
                                ObjectMapper objectMapper,
                                @Value("${dlap.policyengine.actions.enabled:true}") boolean actionsEnabled) {
        this.kafkaTemplate = kafkaTemplate;
        this.meterRegistry = meterRegistry;
        this.objectMapper = objectMapper;
        this.actionsEnabled = actionsEnabled;
        if (!actionsEnabled) {
            log.warn("Policy actions are disabled (dlap.policyengine.actions.enabled=false): shadow mode, "
                    + "matches are logged and counted, nothing is published");
        }
    }

    /**
     * Whether actions are published (false in shadow mode).
     *
     * @return the setting
     */
    public boolean actionsEnabled() {
        return actionsEnabled;
    }

    /**
     * Publishes one typed action.
     *
     * @param event the action
     * @return what became of it
     */
    public Outcome send(PolicyActionEvent event) {
        return dispatch(event).outcome();
    }

    /**
     * Publishes one typed action and returns its delivery, so a caller that must know whether the
     * broker took the record (the scheduled sweep, Q-439) can wait for it.
     *
     * @param event the action
     * @return the outcome and the delivery
     */
    public Dispatch dispatch(PolicyActionEvent event) {
        Map<String, Object> wire = objectMapper.convertValue(event, new TypeReference<Map<String, Object>>() { });
        return dispatchMap(wire);
    }

    /**
     * Publishes one action given as its JSON map, asynchronously. The Kafka producer is
     * idempotent with retries (KafkaReliabilityConfiguration); a send failure is surfaced as the
     * {@code policyengine.send.failures} metric and an error log, never thrown back into the
     * evaluation.
     *
     * @param event the action map
     * @return what became of it
     */
    public Outcome sendPolicyActionEvent(Map<String, Object> event) {
        return dispatchMap(event).outcome();
    }

    private Dispatch dispatchMap(Map<String, Object> event) {
        final String assetId = event != null && event.get("assetId") != null ? event.get("assetId").toString() : "unknown";
        final String actionType = event != null && event.get("actionType") != null
                ? event.get("actionType").toString() : "unknown";
        if (!actionsEnabled) {
            log.info("SHADOW policy action (not published): policyId={} actionType={} assetId={} fireId={}",
                    event != null ? event.get("policyId") : null, actionType, assetId,
                    event != null ? event.get("fireId") : null);
            meterRegistry.counter(SHADOWED_COUNTER, "actionType", actionType).increment();
            return new Dispatch(Outcome.SHADOWED, CompletableFuture.completedFuture(Boolean.TRUE));
        }
        try {
            log.info("Sending PolicyActionEvent to topic '{}': AssetId={}, ActionType={}",
                     policyActionEventTopic, assetId, actionType);
            // The producer's value-serializer is StringSerializer, so the event map is written as
            // a JSON string here; the consumers read it into a Map.
            final String payload = objectMapper.writeValueAsString(event);
            CompletableFuture<SendResult<String, Object>> future =
                    kafkaTemplate.send(policyActionEventTopic, assetId, payload);
            future.whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("Failed to publish PolicyActionEvent for assetId {} actionType {}: {}",
                            assetId, actionType, ex.getMessage(), ex);
                    meterRegistry.counter("policyengine.send.failures",
                            "topic", policyActionEventTopic,
                            "actionType", actionType).increment();
                }
            });
            meterRegistry.counter(PUBLISHED_COUNTER, "actionType", actionType).increment();
            return new Dispatch(Outcome.PUBLISHED, future.handle((result, ex) -> ex == null));
        } catch (Exception e) {
            // Serialisation or pre-send failure: must not throw into the evaluation.
            log.error("Error dispatching PolicyActionEvent to Kafka for assetId {}: {}",
                    assetId, e.getMessage(), e);
            meterRegistry.counter("policyengine.send.failures",
                    "topic", policyActionEventTopic,
                    "actionType", actionType,
                    "stage", "dispatch").increment();
            return new Dispatch(Outcome.FAILED, CompletableFuture.completedFuture(Boolean.FALSE));
        }
    }
}
