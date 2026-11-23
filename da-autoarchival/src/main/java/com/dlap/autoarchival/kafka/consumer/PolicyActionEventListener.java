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

package com.dlap.autoarchival.kafka.consumer;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import com.dlap.autoarchival.config.KafkaReliabilityConfiguration;
import com.dlap.autoarchival.service.IArchivalTaskService;

@Component
public class PolicyActionEventListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(PolicyActionEventListener.class);

    private final IArchivalTaskService archivalTaskService;

    public PolicyActionEventListener(IArchivalTaskService archivalTaskService) {
        this.archivalTaskService = archivalTaskService;
    }

    @KafkaListener(
            topics = "${dlap.kafka.topics.policy-actions}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = KafkaReliabilityConfiguration.MANUAL_ACK_FACTORY)
    public void handlePolicyActionEvent(
            @Payload Map<String, Object> event,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment acknowledgment) {

        if (event == null) {
            LOGGER.error("Received null PolicyActionEvent on topic {}, partition {}, offset {} - acknowledging to skip",
                    topic, partition, offset);
            acknowledgment.acknowledge();
            return;
        }

        String eventId = event.get("eventId") != null ? String.valueOf(event.get("eventId")) : null;
        String policyId = event.get("policyId") != null ? String.valueOf(event.get("policyId")) : null;
        String actionType = event.get("actionType") != null ? String.valueOf(event.get("actionType")) : null;

        LOGGER.info(
                "Received PolicyActionEvent on topic: {}, partition: {}, offset: {}. Event ID: {}, Policy ID: {}, Action: {}",
                topic, partition, offset, eventId, policyId, actionType);

        if (isArchiveAction(actionType)) {
            // A dry run executes nothing: it plans the rule and logs what it would do (the dry-run listing).
            // Every other archive needs an executable approval (docs/specs/approvals.md section 9, rule 1);
            // processArchivalAction refuses and audits one that carries none, whatever requireApproval says.
            boolean dryRun = Boolean.TRUE.equals(event.get("dryRun"));
            if (dryRun) {
                LOGGER.info("DRY-RUN archive on asset {} (policy {}) - no archival task created",
                        event.get("assetId"), policyId);
                archivalTaskService.logDryRun(event);
                acknowledgment.acknowledge();
                return;
            }
            try {
                archivalTaskService.processArchivalAction(event);
                LOGGER.debug("Successfully triggered archival processing for eventId: {}", eventId);
            } catch (RuntimeException ex) {
                // Do NOT acknowledge - let the container's DefaultErrorHandler
                // (configured in KafkaReliabilityConfiguration) run the retry
                // backoff and route to the DLQ on exhaustion. Re-throwing is
                // required for the error handler to see the failure.
                LOGGER.error(
                        "Failed to process archival action (topic={}, partition={}, offset={}, eventId={}, policyId={}): {}",
                        topic, partition, offset, eventId, policyId, ex.getMessage(), ex);
                throw ex;
            }
        } else {
            LOGGER.debug("Skipping PolicyActionEvent with action type: {} for eventId: {}", actionType, eventId);
        }

        acknowledgment.acknowledge();
    }

    /**
     * Decides whether a policy action should trigger archival. The policy engine
     * emits an author-defined {@code actionType} on the wire; there is no canonical
     * enum shared across services, and observed values include {@code ARCHIVE},
     * {@code AUTO_ARCHIVE}, and the documented example {@code ARCHIVE_ASSET}, with
     * inconsistent casing. Match on the {@code ARCHIVE} substring, case-insensitively,
     * so every archive variant creates an ArchivalTask while non-archive actions
     * (addLabel/notify/DELETE) are ignored cleanly.
     *
     * @param actionType raw action type from the event, may be {@code null}
     * @return true if the action denotes an archive intent
     */
    private static boolean isArchiveAction(String actionType) {
        if (actionType == null) {
            return false;
        }
        String normalized = actionType.trim().toUpperCase(java.util.Locale.ROOT);
        return normalized.contains("ARCHIVE");
    }
}
