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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

import com.dlap.reporting.config.KafkaConsumerConfig;
import com.dlap.reporting.dto.NotificationRequestDTO;
import com.dlap.reporting.model.NotificationChannel;
import com.dlap.reporting.service.INotificationService;
import com.dlap.reporting.service.PolicyActionEventRecorder;

@Service
public class PolicyActionConsumer {

    private static final Logger log = LoggerFactory.getLogger(PolicyActionConsumer.class);
    
    private final INotificationService notificationService;
    private final PolicyActionEventRecorder policyActionEventRecorder;

    public PolicyActionConsumer(INotificationService notificationService,
            PolicyActionEventRecorder policyActionEventRecorder) {
        this.notificationService = notificationService;
        this.policyActionEventRecorder = policyActionEventRecorder;
    }

    @KafkaListener(
            topics = "${app.kafka.topic.policy-action-event:dlap.policies.actions}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = KafkaConsumerConfig.MANUAL_ACK_FACTORY)
    public void handlePolicyActionEvent(@Payload Map<String, Object> event, Acknowledgment acknowledgment) {
        if (event == null) {
            log.error("Received null PolicyActionEvent - acknowledging to skip");
            acknowledgment.acknowledge();
            return;
        }

        log.info("Received PolicyActionEvent: AssetId={}, ActionType={}",
                event.get("assetId"), event.get("actionType"));

        String assetId = (String) event.get("assetId");

        NotificationRequestDTO request = buildNotification(event);
        String fireId = PolicyActionEventRecorder.fireIdOf(event);
        boolean recorded;
        try {
            // One transaction claims the fire (unique fire_id), sends, and marks the row dispatched. A failed
            // send rolls the claim back, so the redelivery sends it; a repeated fireId changes nothing (Q-855).
            recorded = policyActionEventRecorder.recordOnce(event, () -> {
                if (request == null) {
                    return false;
                }
                notificationService.sendNotification(request);
                log.info("Notification sent for PolicyActionEvent on assetId: {}", assetId);
                return true;
            });
        } catch (DataIntegrityViolationException e) {
            // A copy of the same fire committed between the existence check and our insert: it is the duplicate.
            if (fireId == null || !policyActionEventRecorder.isRecorded(fireId)) {
                log.error("Failed to persist policy action event for assetId {}: {}", assetId, e.getMessage(), e);
                throw e;
            }
            recorded = false;
        } catch (RuntimeException e) {
            log.error("Failed to persist policy action event for assetId {}: {}", assetId, e.getMessage(), e);
            throw e;
        }
        if (!recorded) {
            log.info("PolicyActionEvent with fireId {} was already recorded; no second row, no second notification",
                    fireId);
        }
        acknowledgment.acknowledge();
    }

    /**
     * Builds the notification of a {@code NOTIFY*} action.
     *
     * @param event the decoded action
     * @return the request, or null when the action does not notify
     */
    @SuppressWarnings("unchecked")
    private NotificationRequestDTO buildNotification(Map<String, Object> event) {
        String actionType = (String) event.get("actionType");
        String policyId = (String) event.get("policyId");
        String assetId = (String) event.get("assetId");
        if (actionType == null || !actionType.toUpperCase().startsWith("NOTIFY")) {
            return null;
        }
        String subject = String.format("Policy Alert on Asset %s (Policy: %s)", assetId, policyId);

        StringBuilder body = new StringBuilder();
        body.append(String.format("Policy ID '%s' triggered action: %s\n", policyId, actionType));
        body.append(String.format("Target Asset ID: %s\n", assetId));
        if (event.containsKey("evaluationId")) {
            body.append(String.format("Evaluation ID: %s\n", event.get("evaluationId")));
        }

        Map<String, Object> params = event;
        if (params != null && !params.isEmpty()) {
            body.append("Action Parameters:\n");
            params.forEach((k, v) -> {
                body.append(String.format("  %s: %s\n", k, valueToString(v)));
            });
        }
        if (event.containsKey("eventTimestamp")) {
             String eventTimestamp = (String) event.get("eventTimestamp");
             body.append(String.format("Event Timestamp: %s\n", eventTimestamp));
        }

        NotificationRequestDTO.NotificationRequestDTOBuilder requestBuilder = NotificationRequestDTO.builder()
                .subject(subject)
                .body(body.toString());

        Map<String, Object> contentModelForTemplate = new HashMap<>();
        contentModelForTemplate.put("policyId", policyId);
        contentModelForTemplate.put("assetId", assetId);
        contentModelForTemplate.put("actionType", actionType);
        if (event.containsKey("evaluationId")) contentModelForTemplate.put("evaluationId", event.get("evaluationId"));

        Map<String, String> simpleParams = new HashMap<>();
        params.forEach((k,v) -> simpleParams.put(k, valueToString(v)));
        contentModelForTemplate.put("actionParameters", simpleParams);
        if (event.containsKey("eventTimestamp")) {
             String eventTimestamp = (String) event.get("eventTimestamp");
             contentModelForTemplate.put("eventTimestamp", eventTimestamp);
        }
        requestBuilder.contentModel(contentModelForTemplate);

        String notificationKeyForPolicyAlerts = "POLICY_ALERT";
        Object notificationKeyValue = params.get("notificationTypeKey");
        if (notificationKeyValue instanceof String) {
             notificationKeyForPolicyAlerts = (String) notificationKeyValue;
        } else {
             log.warn("No specific notificationTypeKey in actionParameters for policy alert, using default key: {}", notificationKeyForPolicyAlerts);
        }
        requestBuilder.notificationTypeKey(notificationKeyForPolicyAlerts);

        Object channelsValue = params.get("channels");
        if (channelsValue instanceof List) {
            try {
                List<String> channelNames = (List<String>) channelsValue;
                requestBuilder.channels(channelNames.stream().map(s -> NotificationChannel.valueOf(s.toUpperCase())).toList());
            } catch (Exception e) {
                log.warn("Could not parse 'channels' from actionParameters, defaulting to EMAIL and SLACK. Error: {}", e.getMessage());
                requestBuilder.channels(List.of(NotificationChannel.EMAIL, NotificationChannel.SLACK));
            }
        } else {
             requestBuilder.channels(List.of(NotificationChannel.EMAIL, NotificationChannel.SLACK));
        }
        return requestBuilder.build();
    }

    // Helper method to convert protobuf Value to String for logging/display
    private String valueToString(Object value) {
        if (value == null) return "null";
        if (value instanceof String) return (String) value;
        if (value instanceof Number) return value.toString();
        if (value instanceof Boolean) return Boolean.toString((Boolean) value);
        if (value instanceof Map) {
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            ((Map<?, ?>) value).forEach((k, v) -> {
                sb.append("\"").append(k).append("\": ").append(valueToString(v)).append(", ");
            });
            sb.append("}");
            return sb.toString();
        }
        if (value instanceof List) {
            StringBuilder sb = new StringBuilder();
            sb.append("[");
            for (Object item : (List<?>) value) {
                sb.append(valueToString(item)).append(", ");
            }
            sb.append("]");
            return sb.toString();
        }
        return "<unknown_value_type>";
    }
} 