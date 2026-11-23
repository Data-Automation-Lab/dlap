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

package com.dlap.autolabel.kafka;

import com.dlap.autolabel.config.KafkaReliabilityConfiguration;
import com.dlap.autolabel.service.ILabelingRunService;
import com.dlap.common.constants.DlapConstants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Consumes {@link DlapConstants.KafkaTopic#LABELING_REQUEST_EVENT} messages
 * published by the SmartLabel Agent "trigger labeling" action.
 *
 * <p>Before this listener existed, triggering a run only put a message on the
 * wire with no handler, so no {@code LabelingJob} row was ever created and the
 * dashboard KPIs stayed at zero. Each request now drives one real batch
 * labeling pass ({@link ILabelingRunService#runManualLabeling}) that enumerates
 * catalog assets, applies labels via the mock/real LLM path, and persists a
 * durable job row the dashboard reads.
 *
 * <p>Manual ack only after the job is persisted; a failure falls through to the
 * {@code .DLT} topic via {@link KafkaReliabilityConfiguration}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LabelingRequestConsumer {

    private final ILabelingRunService labelingRunService;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = DlapConstants.KafkaTopic.LABELING_REQUEST_EVENT,
            groupId = "da-autolabel-labeling-request",
            containerFactory = KafkaReliabilityConfiguration.MANUAL_ACK_FACTORY
    )
    public void onLabelingRequested(String payload, Acknowledgment ack) {
        String requestId = null;
        String triggeredBy = "da-autolabel";
        try {
            JsonNode node = objectMapper.readTree(payload);
            if (node.hasNonNull("requestId")) {
                requestId = node.get("requestId").asText();
            }
            if (node.hasNonNull("triggeredBy")) {
                triggeredBy = node.get("triggeredBy").asText();
            } else if (node.hasNonNull("sourceService")) {
                triggeredBy = node.get("sourceService").asText();
            }
        } catch (Exception e) {
            log.warn("Failed to parse labeling-request payload ({}). Running with defaults.", e.getMessage());
        }

        try {
            String jobId = labelingRunService.runManualLabeling(requestId, triggeredBy);
            log.info("Labeling request {} produced job {}", requestId, jobId);
            ack.acknowledge();
        } catch (Exception e) {
            // Do not ack - DefaultErrorHandler retries with backoff and routes
            // to the DLQ when retries exhaust.
            log.error("Labeling request {} failed: {}; will retry / DLQ", requestId, e.getMessage());
            throw new RuntimeException("Labeling request " + requestId + " failed", e);
        }
    }
}
