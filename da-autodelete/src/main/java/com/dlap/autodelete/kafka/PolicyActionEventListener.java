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

package com.dlap.autodelete.kafka;

import com.dlap.autodelete.config.KafkaReliabilityConfiguration;
import com.dlap.autodelete.service.IDeletionService;
import com.dlap.common.constants.DlapConstants;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Drives the delete executor off {@link DlapConstants.KafkaTopic#POLICY_ACTION_EVENT}
 * (docs/specs/approvals.md section 9, RET-02).
 *
 * <p>Only {@code delete} and {@code AUTO_DELETE} actions cause work here; everything else is
 * acknowledged and ignored. A policy dry run ({@code dryRun: true}) executes nothing and writes a
 * {@code retire.delete.dry_run} audit record. Every other
 * delete goes to {@link IDeletionService#queueSoftDelete}, which executes it only when it carries an
 * approval that the policy engine confirms and every executor rule passes; a refusal is audited and
 * acknowledged, never retried. An action with {@code requireApproval: true} and no approval is such
 * a refusal. When the policy engine, the catalog or the audit log gives no answer, the record is
 * retried and then dead-lettered by the {@code MANUAL_ACK_FACTORY} error handler.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PolicyActionEventListener {

    private final IDeletionService deletionService;

    /**
     * Handles one policy action.
     *
     * @param event  the decoded {@code PolicyActionRequest}
     * @param topic  the topic
     * @param offset the offset
     * @param ack    the acknowledgement
     */
    @KafkaListener(
            topics = DlapConstants.KafkaTopic.POLICY_ACTION_EVENT,
            groupId = "da-autodelete-policy-actions",
            containerFactory = KafkaReliabilityConfiguration.MANUAL_ACK_FACTORY)
    public void onPolicyAction(
            @Payload(required = false) Map<String, Object> event,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment ack) {

        if (event == null) {
            log.warn("Null policy-action payload at offset {}; acknowledging to drain", offset);
            ack.acknowledge();
            return;
        }

        String actionType = String.valueOf(event.getOrDefault("actionType", ""));
        if (!"DELETE".equalsIgnoreCase(actionType) && !"AUTO_DELETE".equalsIgnoreCase(actionType)) {
            ack.acknowledge();
            return;
        }

        if (Boolean.TRUE.equals(event.get("dryRun"))) {
            log.info("Policy dry run: delete of asset {} (policy {}) is not executed",
                    event.get("assetId"), event.get("policyId"));
            // Nothing is decided, but the log still says it happened (Q-632); when it cannot be written the
            // record is retried and then dead-lettered, as for any other action.
            deletionService.recordPolicyDryRun(event);
            ack.acknowledge();
            return;
        }

        try {
            deletionService.queueSoftDelete(event);
            ack.acknowledge();
        } catch (RuntimeException e) {
            log.warn("Delete action on topic {} offset {} could not be decided now: {}", topic, offset, e.toString());
            throw e;
        }
    }
}
