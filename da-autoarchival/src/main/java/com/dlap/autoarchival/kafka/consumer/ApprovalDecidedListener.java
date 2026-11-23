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
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import com.dlap.autoarchival.config.KafkaReliabilityConfiguration;
import com.dlap.autoarchival.service.IArchivalTaskService;

/**
 * Resumes an archive that was asked for through this service's REST API once its approval is
 * decided (docs/specs/approvals.md section 9, rule 4: act on the {@code DECIDED} event whose
 * {@code approvalId} you stored).
 *
 * <p>The event is only a prompt. The executor re-reads the approval from the policy engine before
 * it acts, so a forged event cannot start an archive. An event for an approval this service never
 * stored is ignored.
 */
@Component
public class ApprovalDecidedListener {

    private static final Logger LOG = LoggerFactory.getLogger(ApprovalDecidedListener.class);

    private final IArchivalTaskService tasks;

    /**
     * Creates the listener.
     *
     * @param tasks the task service
     */
    public ApprovalDecidedListener(IArchivalTaskService tasks) {
        this.tasks = tasks;
    }

    /**
     * Handles one approval decision.
     *
     * @param event the {@code ApprovalEvent} (docs/specs/approvals.md section 10)
     * @param ack   manual acknowledgement
     */
    @KafkaListener(
            topics = "${dlap.kafka.topics.approval-decided:dlap.approvals.decided}",
            groupId = "${spring.kafka.consumer.group-id}-approvals",
            containerFactory = KafkaReliabilityConfiguration.MANUAL_ACK_FACTORY)
    public void onDecided(@Payload Map<String, Object> event, Acknowledgment ack) {
        if (event != null && event.get("approvalId") != null) {
            String approvalId = String.valueOf(event.get("approvalId"));
            boolean executable = Boolean.TRUE.equals(event.get("executable"));
            String status = String.valueOf(event.get("status"));
            LOG.debug("approval {} decided: {} (executable={})", approvalId, status, executable);
            tasks.onApprovalDecided(approvalId, executable, status);
        }
        ack.acknowledge();
    }
}
