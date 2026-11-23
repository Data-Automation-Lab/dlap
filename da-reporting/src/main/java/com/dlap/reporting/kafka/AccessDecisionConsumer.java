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

package com.dlap.reporting.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import com.dlap.common.constants.DlapConstants;
import com.dlap.reporting.config.KafkaConsumerConfig;
import com.dlap.reporting.drift.DecisionRecord;
import com.dlap.reporting.drift.DriftDetectionOrchestrator;
import com.dlap.reporting.drift.DriftEventProjector;
import com.dlap.reporting.drift.DriftEventSkips;

/**
 * Consumes PDP access-decision events from {@link
 * DlapConstants.KafkaTopic#ACCESS_DECISION} (enforcement-contract.md section 6) and
 * buffers each into the drift join (ADR-0040, section 6.1).
 *
 * <p>{@link DriftEventProjector#projectDecision} takes the decision time from {@code timestamp}
 * (a decision without one is skipped, never stamped with the consumer's clock), requires an
 * {@code ALLOW} or {@code DENY} verdict, and builds the canonical asset key. A rejected event is
 * acknowledged, logged and counted on {@link DriftEventSkips#SKIPPED_COUNTER}, so a bad decision
 * event cannot stall the drift loop. Manual-ack + DLQ via
 * {@link KafkaConsumerConfig#MANUAL_ACK_FACTORY} (CLAUDE.md section 11): a persistence failure is
 * rethrown so the error handler retries, then dead-letters.
 */
@Service
public class AccessDecisionConsumer {

    private static final Logger log = LoggerFactory.getLogger(AccessDecisionConsumer.class);

    private final DriftDetectionOrchestrator orchestrator;
    private final DriftEventProjector projector;
    private final DriftEventSkips skips;

    /**
     * Creates the consumer.
     *
     * @param orchestrator the drift join
     * @param projector    the event projection
     * @param skips        the skip counter
     */
    public AccessDecisionConsumer(DriftDetectionOrchestrator orchestrator,
                                  DriftEventProjector projector,
                                  DriftEventSkips skips) {
        this.orchestrator = orchestrator;
        this.projector = projector;
        this.skips = skips;
    }

    /**
     * Handles one decision event.
     *
     * @param record the record; its value is a JSON object as a {@code Map}, JSON text, or {@code null}
     * @param ack   the manual acknowledgment
     */
    @KafkaListener(
            topics = DlapConstants.KafkaTopic.ACCESS_DECISION,
            groupId = "da-reporting-drift-decision",
            containerFactory = KafkaConsumerConfig.MANUAL_ACK_FACTORY)
    public void onDecision(ConsumerRecord<String, Object> record, Acknowledgment ack) {
        Object event = record.value();
        DriftEventProjector.Projection<DecisionRecord> projection = projector.projectDecision(event);
        if (!projection.isAccepted()) {
            skips.count(DriftEventSkips.Stream.DECISION, projection.skip());
            log.warn("Access-decision event {} skipped: {} (payload type {})", projection.ref(), projection.skip().tag(),
                    event == null ? "none" : event.getClass().getName());
            ack.acknowledge();
            return;
        }
        try {
            orchestrator.recordDecision(projection.record());
            ack.acknowledge();
        } catch (RuntimeException e) {
            log.error("Failed to record access-decision event {}: {}", projection.ref(), e.getMessage(), e);
            throw e;
        }
    }
}
