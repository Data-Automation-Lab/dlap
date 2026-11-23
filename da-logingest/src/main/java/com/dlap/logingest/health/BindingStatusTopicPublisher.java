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

package com.dlap.logingest.health;

import com.dlap.common.constants.DlapConstants;
import com.dlap.common.logevent.DlapLogEventCodec;
import com.dlap.proto.log.v1.LogBindingStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Sends a {@code LogBindingStatus} to {@code dlap.logingest.binding.status}, keyed by binding id,
 * as proto3 JSON (log-event spec section 10). The latest message per key is the binding's current
 * state, so the topic may be compacted; the deployment sets {@code cleanup.policy=compact} on it.
 *
 * <p>Like the quarantine topic, the value is sent as a map so the shared template writes a JSON
 * object, not a string holding JSON. A failed send is counted by {@code PublishFailureListener}.
 */
@Component
public class BindingStatusTopicPublisher {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper json;
    private final DlapLogEventCodec codec = DlapLogEventCodec.defaults();

    /**
     * Creates the publisher.
     *
     * @param kafkaTemplate the service's template
     * @param json          the JSON mapper
     */
    public BindingStatusTopicPublisher(KafkaTemplate<String, Object> kafkaTemplate, ObjectMapper json) {
        this.kafkaTemplate = kafkaTemplate;
        this.json = json;
    }

    /**
     * Publishes a status.
     *
     * @param status the status
     * @throws IllegalStateException when the status cannot be encoded
     */
    public void publish(LogBindingStatus status) {
        kafkaTemplate.send(DlapConstants.KafkaTopic.LOGINGEST_BINDING_STATUS, status.getBindingId(), toPayload(status));
    }

    /**
     * The value that is sent.
     *
     * @param status the status
     * @return the status as a JSON-compatible map
     */
    Map<String, Object> toPayload(LogBindingStatus status) {
        try {
            return json.readValue(codec.encodeMessage(status), MAP);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("binding status of " + status.getBindingId() + " cannot be encoded", e);
        }
    }
}
