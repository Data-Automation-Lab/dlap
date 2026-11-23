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

package com.dlap.policyengine.kafka.consumer;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import com.dlap.common.constants.DlapConstants;
import com.dlap.policyengine.config.KafkaReliabilityConfiguration;
import com.dlap.policyengine.dto.EventStreamDTO;
import com.dlap.policyengine.model.EventSeverity;
import com.dlap.policyengine.model.EventType;
import com.dlap.policyengine.service.IEventSubscriptionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Ingests the platform's real lifecycle Kafka events into the Event Center feed.
 *
 * <p>da-policyengine already owns the event store ({@code StreamEventLedger}),
 * the persistence path ({@link IEventSubscriptionService#processIncomingEvent}),
 * and the read endpoints ({@code GET /api/v1/policyengine/events/stream[/all]}).
 * What was missing was a consumer that actually feeds it - so the Event Center
 * rendered empty even while the platform emitted policy-action, catalog, and
 * compliance events. This consumer subscribes to those topics, maps each message
 * to an {@link EventStreamDTO}, and records it (which also fans it out to any
 * matching subscription).
 *
 * <p>Its own consumer group is independent of the DSL policy consumer, so both
 * read the same topics without stealing each other's messages. Gated by
 * {@code dlap.policyengine.event-stream-consumer-enabled=true} (default true).
 *
 * <p><b>At least once, applied once (Q-824).</b> Delivery on these topics is at least once
 * (docs/specs/log-event.md section 5.5), so the feed entry's id is derived from the message instead of
 * drawn at random: {@link #eventIdFor} gives the same id to every copy of one message, and
 * {@code processIncomingEvent} records an id it has seen once and ignores it afterwards, so a duplicate
 * adds no feed row, no subscription match and no second notification. A record that cannot be recorded
 * is not acknowledged: the container retries it and then dead-letters it, instead of losing the event
 * from the feed (Q-823 applies the same rule to the router).
 */
@Component
@ConditionalOnProperty(
        name = "dlap.policyengine.event-stream-consumer-enabled",
        havingValue = "true",
        matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class LifecycleEventStreamConsumer {

    private final IEventSubscriptionService eventSubscriptionService;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = {
                    DlapConstants.KafkaTopic.POLICY_ACTION_EVENT,
                    DlapConstants.KafkaTopic.ASSET_CHANGE_EVENT,
                    DlapConstants.KafkaTopic.CATALOG_ASSET_UPSERTED,
                    DlapConstants.KafkaTopic.LABEL_ASSIGNMENT_CHANGED,
                    DlapConstants.KafkaTopic.COMPLIANCE_VIOLATION_EVENT,
                    DlapConstants.KafkaTopic.ARCHIVAL_REQUEST_EVENT,
                    DlapConstants.KafkaTopic.DELETION_REQUEST_EVENT,
                    DlapConstants.KafkaTopic.DISCOVERY_ASSET_DISCOVERED
            },
            groupId = "da-policyengine-event-stream-consumer",
            containerFactory = KafkaReliabilityConfiguration.MANUAL_ACK_FACTORY)
    public void onLifecycleEvent(
            @Payload String payload,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            Acknowledgment ack) {
        if (payload == null || payload.isBlank()) {
            log.warn("Empty lifecycle event on topic {}; acknowledging to drain", topic);
            ack.acknowledge();
            return;
        }
        Map<String, Object> event;
        try {
            event = parse(payload);
        } catch (Exception parseEx) {
            // Not JSON (or an unexpected shape) - record a minimal event so the
            // activity is still visible, rather than dropping it silently.
            event = Map.of("raw", payload);
        }

        // A failure here (the database is down) is rethrown: the record is not acknowledged, the
        // container retries it and then dead-letters it. The id is the message's own, so a retry
        // or a duplicate cannot record the event twice.
        eventSubscriptionService.processIncomingEvent(toEventStreamDto(topic, event, payload));
        ack.acknowledge();
    }

    /** The event as a map; a JSON string that holds the event (the legacy double encoding) is unwrapped once. */
    private Map<String, Object> parse(String payload) throws JsonProcessingException {
        JsonNode node = objectMapper.readTree(payload);
        if (node != null && node.isTextual()) {
            node = objectMapper.readTree(node.asText());
        }
        Map<String, Object> event = objectMapper.convertValue(node, new TypeReference<>() { });
        if (event == null) {
            throw new IllegalArgumentException("the message is not a JSON object");
        }
        return event;
    }

    /**
     * The feed id of one message: the same for every copy of it, different for different messages.
     * It is the name-based UUID of the topic and the message's own identity: the {@code fireId} of a
     * policy action, else the {@code eventId} (or {@code event_id}) of a log event, else the message
     * text itself (a copy of a message is byte for byte the same).
     *
     * @param topic   the topic the message came from
     * @param event   the parsed message
     * @param payload the message text
     * @return the feed event id
     */
    static UUID eventIdFor(String topic, Map<String, Object> event, String payload) {
        String identity = null;
        for (String key : new String[] {"fireId", "eventId", "event_id"}) {
            Object value = event.get(key);
            if (value != null && !value.toString().isBlank()) {
                identity = key + "=" + value;
                break;
            }
        }
        if (identity == null) {
            identity = "payload=" + payload;
        }
        return UUID.nameUUIDFromBytes(("dlap-event-center|" + topic + "|" + identity).getBytes(StandardCharsets.UTF_8));
    }

    private EventStreamDTO toEventStreamDto(String topic, Map<String, Object> event, String payload) {
        String actionType = str(event, "actionType", "action", "action_type");

        EventStreamDTO dto = new EventStreamDTO();
        dto.setEventId(eventIdFor(topic, event, payload));
        dto.setEventType(eventTypeFor(topic, actionType));
        dto.setSeverity(severityFor(topic, actionType));
        dto.setSourceService(sourceServiceFor(topic));
        dto.setAssetId(uuid(str(event, "assetId", "asset_id", "resourceId", "resource_id", "id")));
        dto.setAssetName(str(event, "assetName", "asset_name", "name"));
        dto.setPolicyId(uuid(str(event, "policyId", "policy_id")));
        dto.setPolicyName(str(event, "policyName", "policy_name"));
        dto.setEventData(event);
        dto.setTimestamp(Instant.now());
        dto.setTitle(titleFor(dto.getEventType(), actionType, dto.getAssetName(), dto.getAssetId()));
        return dto;
    }

    private EventType eventTypeFor(String topic, String actionType) {
        if (DlapConstants.KafkaTopic.POLICY_ACTION_EVENT.equals(topic)) {
            if (actionType == null) {
                return EventType.POLICY_APPLIED;
            }
            String a = actionType.toLowerCase();
            if (a.contains("archive")) {
                return EventType.ASSET_ARCHIVAL_SCHEDULED;
            }
            if (a.contains("delete")) {
                return EventType.ASSET_DELETION_SCHEDULED;
            }
            if (a.contains("label")) {
                return EventType.ASSET_CLASSIFICATION_CHANGED;
            }
            return EventType.POLICY_APPLIED;
        }
        return switch (topic) {
            case DlapConstants.KafkaTopic.COMPLIANCE_VIOLATION_EVENT -> EventType.COMPLIANCE_VIOLATION;
            case DlapConstants.KafkaTopic.ARCHIVAL_REQUEST_EVENT -> EventType.ASSET_ARCHIVAL_SCHEDULED;
            case DlapConstants.KafkaTopic.DELETION_REQUEST_EVENT -> EventType.ASSET_DELETION_SCHEDULED;
            case DlapConstants.KafkaTopic.LABEL_ASSIGNMENT_CHANGED -> EventType.ASSET_CLASSIFICATION_CHANGED;
            case DlapConstants.KafkaTopic.CATALOG_ASSET_UPSERTED,
                 DlapConstants.KafkaTopic.DISCOVERY_ASSET_DISCOVERED -> EventType.ASSET_DISCOVERED;
            case DlapConstants.KafkaTopic.ASSET_CHANGE_EVENT -> EventType.ASSET_METADATA_CHANGED;
            default -> EventType.ASSET_METADATA_CHANGED;
        };
    }

    private EventSeverity severityFor(String topic, String actionType) {
        if (DlapConstants.KafkaTopic.COMPLIANCE_VIOLATION_EVENT.equals(topic)) {
            return EventSeverity.HIGH;
        }
        if (DlapConstants.KafkaTopic.DELETION_REQUEST_EVENT.equals(topic)) {
            return EventSeverity.MEDIUM;
        }
        if (actionType != null && actionType.toLowerCase().contains("delete")) {
            return EventSeverity.MEDIUM;
        }
        return EventSeverity.LOW;
    }

    private String sourceServiceFor(String topic) {
        if (topic == null) {
            return "da-policyengine";
        }
        if (topic.startsWith("dlap.policies")) {
            return "da-policyengine";
        }
        if (topic.startsWith("dlap.catalog") || topic.startsWith("dlap.asset")) {
            return "da-catalog";
        }
        if (topic.startsWith("dlap.compliance")) {
            return "da-autocompliance";
        }
        if (topic.startsWith("dlap.archival")) {
            return "da-autoarchival";
        }
        if (topic.startsWith("dlap.deletion")) {
            return "da-autodelete";
        }
        if (topic.startsWith("dlap.labeling")) {
            return "da-autolabel";
        }
        if (topic.startsWith("dlap.discovery")) {
            return "da-discovery";
        }
        return "da-policyengine";
    }

    private String titleFor(EventType type, String actionType, String assetName, UUID assetId) {
        String subject = assetName != null ? assetName : (assetId != null ? assetId.toString() : "an asset");
        return switch (type) {
            case ASSET_ARCHIVAL_SCHEDULED -> "Archival scheduled for " + subject;
            case ASSET_DELETION_SCHEDULED -> "Deletion scheduled for " + subject;
            case ASSET_CLASSIFICATION_CHANGED -> "Labels updated on " + subject;
            case ASSET_DISCOVERED -> "Asset discovered: " + subject;
            case ASSET_METADATA_CHANGED -> "Metadata changed on " + subject;
            case COMPLIANCE_VIOLATION -> "Compliance violation on " + subject;
            case POLICY_APPLIED -> "Policy "
                    + (actionType != null ? "action '" + actionType + "' " : "")
                    + "applied to " + subject;
            default -> type.name() + " on " + subject;
        };
    }

    /** First non-blank value among the given keys, else null. */
    private String str(Map<String, Object> map, String... keys) {
        for (String k : keys) {
            Object v = map.get(k);
            if (v != null && !v.toString().isBlank()) {
                return v.toString();
            }
        }
        return null;
    }

    /** Parse a UUID leniently; return null if the value is absent or not a UUID. */
    private UUID uuid(String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
